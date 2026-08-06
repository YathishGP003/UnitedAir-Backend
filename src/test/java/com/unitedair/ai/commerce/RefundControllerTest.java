package com.unitedair.ai.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import com.unitedair.ai.identity.CurrentUser;
import com.unitedair.ai.identity.Role;
import com.unitedair.ai.shared.ApiExceptions;
import org.junit.jupiter.api.Test;

class RefundControllerTest {

    @Test
    void passengerListUsesOnlyTheAuthenticatedPassengerId() {
        CurrentUser current = mock(CurrentUser.class);
        RefundWorkItemService refunds = mock(RefundWorkItemService.class);
        when(current.require()).thenReturn(new CurrentUser.Authenticated(
                17L, "passenger@example.com", "Maya", Role.PASSENGER));
        when(refunds.listForPassenger(17L)).thenReturn(List.of());

        List<RefundDtos.RefundCaseView> result =
                new RefundController(current, refunds).passengerCases();

        assertThat(result).isEmpty();
    }

    @Test
    void staffTransitionUsesAuthenticatedStaffIdentity() {
        CurrentUser current = mock(CurrentUser.class);
        RefundWorkItemService refunds = mock(RefundWorkItemService.class);
        UUID caseUuid = UUID.randomUUID();
        when(current.require()).thenReturn(new CurrentUser.Authenticated(
                3L, "staff@example.com", "Vikram", Role.AIRLINE_STAFF));
        when(refunds.transition(caseUuid, "PROCESSING", "", 3L))
                .thenThrow(new ApiExceptions.Conflict("state sentinel"));

        assertThatThrownBy(() -> new RefundController(current, refunds).transition(
                caseUuid, new RefundDtos.TransitionRequest("PROCESSING", "")))
                .isInstanceOf(ApiExceptions.Conflict.class)
                .hasMessage("state sentinel");
    }

    @Test
    void passengerCannotCallStaffRefundQueueEvenWithoutMethodSecurityProxy() {
        CurrentUser current = mock(CurrentUser.class);
        when(current.require()).thenReturn(new CurrentUser.Authenticated(
                17L, "passenger@example.com", "Maya", Role.PASSENGER));

        assertThatThrownBy(() ->
                new RefundController(current, mock(RefundWorkItemService.class))
                        .staffCases(null))
                .isInstanceOf(ApiExceptions.Forbidden.class);
    }
}
