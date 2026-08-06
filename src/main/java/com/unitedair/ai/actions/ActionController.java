package com.unitedair.ai.actions;

import java.util.List;

import com.unitedair.ai.tools.ToolDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two-phase action endpoints of SRS 5.
 *
 * <p>Proposing is a read: it computes and quotes. Confirming is the only call that changes
 * a booking, and it can only act on an action id that was quoted first.
 */
@RestController
@RequestMapping("/actions")
@Tag(name = "Actions", description = "Two-phase booking mutations (propose, then confirm)")
public class ActionController {

    private final ActionService actions;

    public ActionController(ActionService actions) {
        this.actions = actions;
    }

    @PostMapping
    @Operation(summary = "Propose an action and receive a quote to confirm")
    public ResponseEntity<ActionDtos.ActionView> propose(@RequestBody ActionDtos.ProposeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(actions.propose(request, request.sessionId()));
    }

    @GetMapping("/{actionId}")
    @Operation(summary = "Retrieve an action and its current status")
    public ActionDtos.ActionView get(@PathVariable String actionId) {
        return actions.get(actionId);
    }

    @PostMapping("/{actionId}/confirm")
    @Operation(summary = "Confirm and execute a proposed action")
    public ActionDtos.ActionView confirm(@PathVariable String actionId) {
        return actions.confirm(actionId);
    }

    @PostMapping("/{actionId}/cancel")
    @Operation(summary = "Abandon a proposed action")
    public ActionDtos.ActionView cancel(@PathVariable String actionId) {
        return actions.cancel(actionId);
    }

    @GetMapping
    @Operation(summary = "Pending proposals in a session")
    public List<ActionDtos.ActionView> pending(@RequestParam String sessionId) {
        return actions.pendingFor(sessionId);
    }

    @GetMapping("/quote/{pnr}")
    @Operation(summary = "Refund arithmetic for a booking, without proposing anything")
    public ToolDtos.RefundQuote quote(@PathVariable String pnr) {
        return actions.quote(pnr);
    }
}
