package com.unitedair.ai.orchestration;

import java.time.LocalDate;

/** Prompt-safe labels derived exclusively from trusted server-side state. */
public record TrustedConversationState(
        boolean ownedBookingResolved,
        String selectedFlightNo,
        String selectedRoute,
        LocalDate selectedTravelDate,
        String pendingOperation,
        String pendingSlot,
        String pendingActionType,
        String pendingActionStatus,
        boolean suppliedTrustedSlot,
        boolean activeBookingDraft,
        String bookingDraftState) {

    public static TrustedConversationState empty() {
        return new TrustedConversationState(
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                null);
    }
}
