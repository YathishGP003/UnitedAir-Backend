package com.unitedair.ai.identity;

import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.unitedair.ai.shared.UnitedAirProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless bearer-token security.
 *
 * <p>Authorisation is expressed twice on purpose. The URL rules below are the coarse gate,
 * and {@code @PreAuthorize} on the service methods is the real one. FR-031 requires that KB
 * ingestion be restricted to Admins, and a rule that lives only in a URL matcher stops
 * being true the moment someone adds a second route to the same method.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final UnitedAirProperties properties;

    public SecurityConfig(UnitedAirProperties properties) {
        this.properties = properties;
    }

    /**
     * HS256 needs at least 256 bits of key. Rather than let a short or missing secret
     * produce a confusing runtime failure on the first login, refuse to start and say
     * exactly what to do about it.
     */
    private SecretKeySpec secretKey() {
        String secret = properties.getSecurity().getJwtSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("""
                    JWT_SECRET is not set.

                    Run bootstrap.ps1 (it generates one into .env), or set the variable yourself:
                      $env:JWT_SECRET = '<64 or more random characters>'
                    """);
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET must be at least 32 bytes for HS256; got "
                            + secret.getBytes(StandardCharsets.UTF_8).length + ".");
        }
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey()));
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withSecretKey(secretKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    /** Maps the {@code role} claim onto a single {@code ROLE_*} authority. */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthorityPrefix("ROLE_");
        authorities.setAuthoritiesClaimName("role");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationConverter converter)
            throws Exception {

        http
            .csrf(csrf -> csrf.disable())   // stateless bearer tokens; no cookie to forge
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .requestMatchers(
                            "/auth/login",
                            "/auth/register",
                            "/auth/demo-users",
                            "/actuator/health",
                            "/actuator/health/**",
                            "/actuator/info",
                            "/v3/api-docs/**",
                            "/swagger-ui/**",
                            "/swagger-ui.html").permitAll()
                    // FR-031: ingestion and KB mutation are Admin-only. POST /kb/search is
                    // a read expressed as a POST because it carries a filter body, so it is
                    // listed ahead of the blanket POST rule rather than being locked away.
                    .requestMatchers(HttpMethod.POST,   "/kb/search").authenticated()
                    .requestMatchers(HttpMethod.POST,   "/kb/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.PUT,    "/kb/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.DELETE, "/kb/**").hasRole("ADMIN")
                    // Ingestion governance is Admin-only; the escalation queue is also
                    // Staff's to work (FR-030), so /admin/** cannot be a single ADMIN rule.
                    // The narrower @PreAuthorize on each method is what actually decides.
                    .requestMatchers("/admin/ingestion-jobs").hasRole("ADMIN")
                    .requestMatchers("/admin/**").hasAnyRole("AIRLINE_STAFF", "ADMIN")
                    .anyRequest().authenticated())
            .oauth2ResourceServer(oauth -> oauth
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
            .httpBasic(Customizer.withDefaults());

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.getCors().getAllowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("X-Trace-Id"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
