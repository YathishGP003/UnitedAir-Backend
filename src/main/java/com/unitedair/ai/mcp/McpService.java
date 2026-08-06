package com.unitedair.ai.mcp;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.unitedair.ai.tools.BookingAccess;
import com.unitedair.ai.tools.BookingManagementTool;
import com.unitedair.ai.tools.CheckInStatusTool;
import com.unitedair.ai.tools.DisruptionRecoveryWorker;
import com.unitedair.ai.tools.FlightSearchTool;
import com.unitedair.ai.tools.ToolDtos;
import com.unitedair.ai.commerce.RefundDtos;
import com.unitedair.ai.commerce.RefundOperationsWorker;
import org.springframework.stereotype.Service;

/**
 * MCP-compatible, authenticated read-only facade over the same typed tools used by chat.
 * It deliberately has no SQL, URL or mutation primitive.
 */
@Service
public class McpService {

    private final ObjectMapper mapper;
    private final FlightSearchTool flights;
    private final BookingManagementTool bookings;
    private final CheckInStatusTool status;
    private final DisruptionRecoveryWorker recovery;
    private final RefundOperationsWorker refunds;

    public McpService(
            ObjectMapper mapper,
            FlightSearchTool flights,
            BookingManagementTool bookings,
            CheckInStatusTool status,
            DisruptionRecoveryWorker recovery,
            RefundOperationsWorker refunds) {
        this.mapper = mapper;
        this.flights = flights;
        this.bookings = bookings;
        this.status = status;
        this.recovery = recovery;
        this.refunds = refunds;
    }

    public McpDtos.Response handle(McpDtos.Request request, BookingAccess access) {
        if (request == null || !"2.0".equals(request.jsonrpc()) || request.method() == null) {
            return McpDtos.Response.failed(
                    request == null ? null : request.id(), -32600, "Invalid Request", null);
        }
        try {
            return switch (request.method()) {
                case "initialize" -> McpDtos.Response.ok(request.id(), initialize());
                case "notifications/initialized" ->
                        McpDtos.Response.ok(request.id(), mapper.createObjectNode());
                case "tools/list" -> McpDtos.Response.ok(request.id(), toolsList());
                case "tools/call" -> McpDtos.Response.ok(
                        request.id(), call(request.params(), requireAccess(access)));
                default -> McpDtos.Response.failed(
                        request.id(), -32601, "Method not found", null);
            };
        } catch (IllegalArgumentException exception) {
            return McpDtos.Response.failed(
                    request.id(), -32602, exception.getMessage(), null);
        } catch (RuntimeException exception) {
            ObjectNode data = mapper.createObjectNode();
            data.put("isError", true);
            return McpDtos.Response.failed(
                    request.id(), -32000,
                    exception.getMessage() == null ? "Tool call failed." : exception.getMessage(),
                    data);
        }
    }

    public JsonNode toolsList() {
        ArrayNode tools = mapper.createArrayNode();
        tools.add(tool(
                "search_flights",
                "Search UnitedAir flight inventory by route and date.",
                Map.of(
                        "origin", "string",
                        "destination", "string",
                        "date", "string",
                        "cabin", "string"),
                List.of("origin", "destination", "date")));
        tools.add(tool(
                "get_booking",
                "Retrieve one authorized booking. Passengers can read only their own booking.",
                Map.of("pnr", "string"),
                List.of("pnr")));
        tools.add(tool(
                "get_flight_status",
                "Read live simulator status, delay, terminal and gate.",
                Map.of("flightNo", "string", "date", "string"),
                List.of("flightNo")));
        tools.add(tool(
                "find_disruption_alternatives",
                "Find authorized same-route alternatives for a delayed or cancelled booking.",
                Map.of("pnr", "string"),
                List.of("pnr")));
        tools.add(tool(
                "get_refund_status",
                "Read one authorized refund status. Passengers can read only their own case.",
                Map.of("pnr", "string"),
                List.of("pnr")));
        tools.add(tool(
                "list_refund_cases",
                "List refund cases for Airline Staff or Admin.",
                Map.of("status", "string", "limit", "integer"),
                List.of()));
        return mapper.createObjectNode().set("tools", tools);
    }

    private JsonNode initialize() {
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", "2025-06-18");
        result.set("capabilities", mapper.valueToTree(Map.of("tools", Map.of("listChanged", false))));
        result.set("serverInfo", mapper.valueToTree(
                Map.of("name", "unitedair-ai-readonly", "version", "1.0.0")));
        return result;
    }

    private JsonNode call(JsonNode params, BookingAccess access) {
        if (params == null || !params.hasNonNull("name")) {
            throw new IllegalArgumentException("tools/call requires a tool name.");
        }
        String name = params.get("name").asText();
        JsonNode arguments = params.path("arguments");
        Object data = switch (name) {
            case "search_flights" -> flights.search(
                    new ToolDtos.FlightSearchRequest(
                            required(arguments, "origin"),
                            required(arguments, "destination"),
                            LocalDate.parse(required(arguments, "date")),
                            optional(arguments, "cabin"), 1, 0, 0),
                    access.role().name()).data();
            case "get_booking" ->
                    bookings.retrieve(required(arguments, "pnr"), access).data();
            case "get_flight_status" -> status.flightStatus(
                    required(arguments, "flightNo"),
                    optionalDate(arguments, "date"),
                    access.role().name()).data();
            case "find_disruption_alternatives" ->
                    recovery.findAlternatives(required(arguments, "pnr"), access);
            case "get_refund_status" ->
                    requireSuccessful(refunds.status(required(arguments, "pnr"), access));
            case "list_refund_cases" ->
                    requireSuccessful(refunds.listCases(
                            new RefundDtos.RefundCaseQuery(
                                    optional(arguments, "status"),
                                    null,
                                    optionalInt(arguments, "limit", 50)),
                            access));
            default -> throw new IllegalArgumentException("Unknown read-only tool: " + name);
        };
        ObjectNode result = mapper.createObjectNode();
        result.set("structuredContent", mapper.valueToTree(data));
        ArrayNode content = result.putArray("content");
        content.addObject().put("type", "text").put("text", mapper.valueToTree(data).toString());
        result.put("isError", false);
        return result;
    }

    private ObjectNode tool(
            String name, String description, Map<String, String> properties, List<String> required) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode fields = schema.putObject("properties");
        properties.forEach((key, type) -> fields.putObject(key).put("type", type));
        ArrayNode requiredFields = schema.putArray("required");
        required.forEach(requiredFields::add);
        schema.put("additionalProperties", false);

        ObjectNode tool = mapper.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);
        tool.set("inputSchema", schema);
        return tool;
    }

    private static String required(JsonNode node, String field) {
        String value = optional(node, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required argument: " + field);
        }
        return value;
    }

    private static String optional(JsonNode node, String field) {
        return node == null || !node.hasNonNull(field) ? null : node.get(field).asText();
    }

    private static LocalDate optionalDate(JsonNode node, String field) {
        String value = optional(node, field);
        return value == null || value.isBlank() ? null : LocalDate.parse(value);
    }

    private static int optionalInt(JsonNode node, String field, int fallback) {
        return node == null || !node.hasNonNull(field)
                ? fallback : node.get(field).asInt(fallback);
    }

    private static Object requireSuccessful(ToolDtos.ToolOutcome outcome) {
        if (!outcome.success()) {
            throw new IllegalArgumentException(outcome.errorMessage());
        }
        return outcome.data();
    }

    private static BookingAccess requireAccess(BookingAccess access) {
        if (access == null) {
            throw new IllegalArgumentException("Authenticated tool access is required.");
        }
        return access;
    }
}
