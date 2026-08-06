package com.unitedair.ai.commerce;

import java.util.List;
import java.util.UUID;

import com.unitedair.ai.identity.CurrentUser;
import com.unitedair.ai.identity.Role;
import com.unitedair.ai.shared.ApiExceptions;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RefundController {

    private final CurrentUser currentUser;
    private final RefundWorkItemService refunds;

    public RefundController(
            CurrentUser currentUser,
            RefundWorkItemService refunds) {
        this.currentUser = currentUser;
        this.refunds = refunds;
    }

    @GetMapping("/commerce/refunds")
    @PreAuthorize("hasRole('PASSENGER')")
    public List<RefundDtos.RefundCaseView> passengerCases() {
        return refunds.listForPassenger(requirePassenger().id());
    }

    @GetMapping("/staff/refunds")
    @PreAuthorize("hasRole('AIRLINE_STAFF')")
    public List<RefundDtos.RefundCaseView> staffCases(
            @RequestParam(required = false) String status) {
        requireStaff();
        return refunds.listForStaff(status);
    }

    @PostMapping("/staff/refunds/{caseUuid}/transition")
    @PreAuthorize("hasRole('AIRLINE_STAFF')")
    public RefundDtos.RefundCaseView transition(
            @PathVariable UUID caseUuid,
            @RequestBody RefundDtos.TransitionRequest request) {
        CurrentUser.Authenticated staff = requireStaff();
        if (request == null || request.status() == null) {
            throw new ApiExceptions.BadRequest("Choose a refund status.");
        }
        return refunds.transition(
                caseUuid, request.status(), request.note(), staff.id());
    }

    private CurrentUser.Authenticated requirePassenger() {
        CurrentUser.Authenticated user = currentUser.require();
        if (user.role() != Role.PASSENGER || user.id() == null) {
            throw new ApiExceptions.Forbidden("Passenger access required.");
        }
        return user;
    }

    private CurrentUser.Authenticated requireStaff() {
        CurrentUser.Authenticated user = currentUser.require();
        if (user.role() != Role.AIRLINE_STAFF || user.id() == null) {
            throw new ApiExceptions.Forbidden("Airline Staff access required.");
        }
        return user;
    }
}
