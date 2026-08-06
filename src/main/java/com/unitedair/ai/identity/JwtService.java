package com.unitedair.ai.identity;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.unitedair.ai.shared.UnitedAirProperties;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Issues the HS256 bearer tokens the frontend presents on every call.
 *
 * <p>Claims are deliberately minimal: subject, role, display name and the standard time
 * claims. Nothing that could identify a passenger's travel goes in a token, because tokens
 * end up in browser storage and in proxy logs.
 */
@Service
public class JwtService {

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_NAME = "name";
    private static final String CLAIM_UID = "uid";

    private final JwtEncoder encoder;
    private final int ttlMinutes;

    public JwtService(JwtEncoder encoder, UnitedAirProperties properties) {
        this.encoder = encoder;
        this.ttlMinutes = properties.getSecurity().getJwtTtlMinutes();
    }

    public IssuedToken issue(AppUser user) {
        Instant now = Instant.now();
        Instant expiry = now.plus(ttlMinutes, ChronoUnit.MINUTES);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("unitedair-ai")
                .issuedAt(now)
                .expiresAt(expiry)
                .subject(user.email())
                .claim(CLAIM_UID, user.id())
                .claim(CLAIM_ROLE, user.role().name())
                .claim(CLAIM_NAME, user.displayName())
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        return new IssuedToken(token, expiry, ttlMinutes * 60L);
    }

    public record IssuedToken(String token, Instant expiresAt, long expiresInSeconds) { }
}
