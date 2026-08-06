package com.unitedair.ai.identity;

import java.util.List;
import java.util.Locale;

/**
 * The three user classes of SRS 2.3.
 *
 * <p>Each role carries the KB audience labels it may read. This is the single place where
 * "what may this actor see?" is decided, and the retrieval layer pushes the result into SQL
 * so a Passenger's query is structurally incapable of returning a staff-only chunk. Hiding
 * staff content in the UI would not be enough; the chunk must never enter the prompt.
 *
 * <p>Roles are cumulative in capability - an Admin can do everything Staff can - but
 * audience visibility is expressed explicitly rather than derived, because "Admin can read
 * staff documents" is a policy decision, not an inevitability of the hierarchy.
 */
public enum Role {

    PASSENGER("Passenger", List.of("Passenger", "All"), 10),

    AIRLINE_STAFF("Airline Staff", List.of("Passenger", "Airline Staff", "All"), 20),

    ADMIN("Admin", List.of("Passenger", "Airline Staff", "Admin", "All"), 30);

    private final String displayName;
    private final List<String> readableAudiences;
    private final int rank;

    Role(String displayName, List<String> readableAudiences, int rank) {
        this.displayName = displayName;
        this.readableAudiences = readableAudiences;
        this.rank = rank;
    }

    /** The label as it appears in KB document front matter ("Audience | Passenger, ..."). */
    public String displayName() {
        return displayName;
    }

    /** Audience labels this role is permitted to retrieve. */
    public List<String> readableAudiences() {
        return readableAudiences;
    }

    public boolean atLeast(Role other) {
        return this.rank >= other.rank;
    }

    /** Spring Security authority name, e.g. {@code ROLE_AIRLINE_STAFF}. */
    public String authority() {
        return "ROLE_" + name();
    }

    public static Role fromString(String value) {
        if (value == null || value.isBlank()) {
            return PASSENGER;
        }
        String normalised = value.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (normalised.startsWith("ROLE_")) {
            normalised = normalised.substring(5);
        }
        for (Role role : values()) {
            if (role.name().equals(normalised)) {
                return role;
            }
        }
        // "Staff" and "Airline Staff" both appear in KB front matter
        if (normalised.contains("STAFF")) {
            return AIRLINE_STAFF;
        }
        if (normalised.contains("ADMIN")) {
            return ADMIN;
        }
        return PASSENGER;
    }
}
