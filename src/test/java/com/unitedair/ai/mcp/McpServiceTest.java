package com.unitedair.ai.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unitedair.ai.tools.BookingManagementTool;
import com.unitedair.ai.tools.CheckInStatusTool;
import com.unitedair.ai.tools.DisruptionRecoveryWorker;
import com.unitedair.ai.tools.FlightSearchTool;
import com.unitedair.ai.commerce.RefundOperationsWorker;
import org.junit.jupiter.api.Test;

class McpServiceTest {

    @Test
    void advertisesOnlyFixedReadOnlyAirlineTools() {
        McpService service = new McpService(
                new ObjectMapper(),
                mock(FlightSearchTool.class),
                mock(BookingManagementTool.class),
                mock(CheckInStatusTool.class),
                mock(DisruptionRecoveryWorker.class),
                mock(RefundOperationsWorker.class));

        String tools = service.toolsList().toString();

        assertThat(tools).contains(
                "search_flights", "get_booking", "get_flight_status",
                "find_disruption_alternatives", "get_refund_status", "list_refund_cases");
        assertThat(tools).doesNotContainIgnoringCase("sql");
        assertThat(tools).doesNotContain("cancel_booking");
    }

    @Test
    void rejectsUnknownJsonRpcMethodsWithTheStandardCode() {
        McpService service = new McpService(
                new ObjectMapper(),
                mock(FlightSearchTool.class),
                mock(BookingManagementTool.class),
                mock(CheckInStatusTool.class),
                mock(DisruptionRecoveryWorker.class),
                mock(RefundOperationsWorker.class));

        McpDtos.Response response = service.handle(
                new McpDtos.Request("2.0", "7", "database/query", null),
                null);

        assertThat(response.error().code()).isEqualTo(-32601);
    }
}
