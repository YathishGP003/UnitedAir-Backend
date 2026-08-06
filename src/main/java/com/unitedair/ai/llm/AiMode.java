package com.unitedair.ai.llm;

import java.util.Locale;

import com.unitedair.ai.shared.UnitedAirProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Whether the assistant calls a hosted model or runs fully locally.
 *
 * <p>Offline mode is not a stub. Retrieval, metadata filtering, the grounding gate,
 * citation building, evaluation, escalation and audit all behave exactly as they do in
 * live mode; only the two model calls (embedding and generation) are replaced with
 * deterministic local implementations. That keeps the system demonstrable when the
 * GitHub Models quota is exhausted - a documented pain point of the reference environment -
 * and makes the test suite hermetic.
 */
public enum AiMode {

    LIVE,
    OFFLINE;

    public boolean isLive() {
        return this == LIVE;
    }

    @Configuration
    public static class Resolver {

        private static final Logger log = LoggerFactory.getLogger(Resolver.class);

        @Bean
        public AiMode aiMode(UnitedAirProperties properties,
                             @Value("${spring.ai.openai.api-key:}") String apiKey) {

            String configured = properties.getAiMode() == null
                    ? "auto"
                    : properties.getAiMode().trim().toLowerCase(Locale.ROOT);

            boolean tokenPresent = apiKey != null
                    && !apiKey.isBlank()
                    && !"not-configured".equals(apiKey);

            AiMode resolved = switch (configured) {
                case "live" -> AiMode.LIVE;
                case "offline" -> AiMode.OFFLINE;
                default -> tokenPresent ? AiMode.LIVE : AiMode.OFFLINE;
            };

            if (resolved == AiMode.LIVE && !tokenPresent) {
                // Explicitly requested live without a credential: say so plainly rather
                // than failing later with an opaque 401 on the first question asked.
                log.error("""

                        UNITEDAIR_AI_MODE=live but no GITHUB_TOKEN is set.
                        Model calls will fail. Set GITHUB_TOKEN in .env, or use
                        UNITEDAIR_AI_MODE=offline to run without a hosted model.
                        """);
            }

            log.info("AI mode resolved to {} (configured '{}', credential {})",
                    resolved, configured, tokenPresent ? "present" : "absent");
            return resolved;
        }
    }
}
