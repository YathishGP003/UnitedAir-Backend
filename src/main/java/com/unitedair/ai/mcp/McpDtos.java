package com.unitedair.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/** Minimal JSON-RPC 2.0 envelopes used by the read-only MCP endpoint. */
public final class McpDtos {

    private McpDtos() { }

    public record Request(String jsonrpc, Object id, String method, JsonNode params) { }

    public record RpcError(int code, String message, JsonNode data) { }

    public record Response(String jsonrpc, Object id, JsonNode result, RpcError error) {
        public static Response ok(Object id, JsonNode result) {
            return new Response("2.0", id, result, null);
        }

        public static Response failed(Object id, int code, String message, JsonNode data) {
            return new Response("2.0", id, null, new RpcError(code, message, data));
        }
    }
}
