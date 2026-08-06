package com.unitedair.ai.mcp;

import com.unitedair.ai.identity.CurrentUser;
import com.unitedair.ai.tools.BookingAccess;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated HTTP transport for the bounded read-only MCP service. */
@RestController
public class McpController {

    private final McpService service;
    private final CurrentUser currentUser;

    public McpController(McpService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @PostMapping("/mcp")
    public ResponseEntity<McpDtos.Response> handle(@RequestBody McpDtos.Request request) {
        CurrentUser.Authenticated user = currentUser.require();
        McpDtos.Response response = service.handle(
                request, new BookingAccess(user.role(), user.id()));
        return ResponseEntity.ok(response);
    }
}
