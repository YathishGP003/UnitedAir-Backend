package com.unitedair.ai.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.unitedair.ai.identity.Role;
import org.junit.jupiter.api.Test;

class BookingAccessTest {

    @Test
    void passengerCanReadOnlyTheirOwnBooking() {
        BookingAccess passenger = new BookingAccess(Role.PASSENGER, 7L);

        assertThat(passenger.canRead(7L)).isTrue();
        assertThat(passenger.canRead(8L)).isFalse();
        assertThat(passenger.canRead(null)).isFalse();
    }

    @Test
    void staffAndAdminCanServiceAnyBooking() {
        assertThat(new BookingAccess(Role.AIRLINE_STAFF, 12L).canRead(8L)).isTrue();
        assertThat(new BookingAccess(Role.ADMIN, 13L).canRead(null)).isTrue();
    }
}
