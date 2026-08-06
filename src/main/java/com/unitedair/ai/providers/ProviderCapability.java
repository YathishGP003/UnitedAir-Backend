package com.unitedair.ai.providers;

/** Honest disclosure attached to every provider-backed tool result. */
public record ProviderCapability(
        String mode,
        String provider,
        boolean live,
        boolean supportsMutation) {

    public ProviderCapability {
        mode = mode == null ? "simulator" : mode;
        provider = provider == null ? "UNITEDAIR_SIMULATOR" : provider;
    }

    public static ProviderCapability simulator() {
        return new ProviderCapability(
                "simulator", "UNITEDAIR_SIMULATOR", false, true);
    }
}
