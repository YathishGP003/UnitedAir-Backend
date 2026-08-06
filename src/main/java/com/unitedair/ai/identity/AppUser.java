package com.unitedair.ai.identity;

import java.time.Instant;

/**
 * An authenticated user of the assistant.
 *
 * <p>{@code passwordHash} is carried on the record because the repository reads it during
 * authentication, but it is never serialised: no controller returns {@code AppUser}
 * directly, only the projections in {@link AuthDtos}.
 */
public record AppUser(
        Long id,
        String email,
        String passwordHash,
        String displayName,
        Role role,
        boolean active,
        Instant createdAt,
        Instant lastLoginAt) {

    /** Copy without the credential, for anywhere the hash has no business travelling. */
    public AppUser withoutSecret() {
        return new AppUser(id, email, null, displayName, role, active, createdAt, lastLoginAt);
    }
}
