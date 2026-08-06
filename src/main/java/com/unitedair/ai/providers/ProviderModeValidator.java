package com.unitedair.ai.providers;

import java.util.Locale;
import java.util.Set;

import com.unitedair.ai.shared.UnitedAirProperties;
import org.springframework.stereotype.Component;

/** Fails startup instead of silently selecting a different operational provider. */
@Component
public class ProviderModeValidator {

    public ProviderModeValidator(UnitedAirProperties properties) {
        String configured = properties.getProviders().getMode();
        String mode = configured == null
                ? "simulator" : configured.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("simulator", "real").contains(mode)) {
            throw new IllegalStateException(
                    "UNITEDAIR_PROVIDER_MODE must be 'simulator' or 'real'; received '"
                            + configured + "'.");
        }
    }
}
