package com.unitedair.ai.tools;

import com.unitedair.ai.identity.CurrentUser;
import com.unitedair.ai.identity.Role;

/**
 * Caller identity used at the reservation-system boundary.
 *
 * <p>Passenger access is ownership-based. Staff and Admin access is servicing-based and
 * remains auditable through the tool invocation logger.
 */
public record BookingAccess(Role role, Long userId) {

    public BookingAccess {
        role = role == null ? Role.PASSENGER : role;
    }

    public static BookingAccess from(CurrentUser.Authenticated user) {
        return new BookingAccess(user.role(), user.id());
    }

    public static BookingAccess of(Role role, Long userId) {
        return new BookingAccess(role, userId);
    }

    public boolean privileged() {
        return role.atLeast(Role.AIRLINE_STAFF);
    }

    public boolean canRead(Long bookingUserId) {
        return privileged()
                || (userId != null && bookingUserId != null && userId.equals(bookingUserId));
    }
}
