package com.unitedair.ai.providers.http;

import java.time.Duration;

import com.unitedair.ai.shared.ApiExceptions;
import com.unitedair.ai.shared.UnitedAirProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

final class ProviderHttpSupport {

    private ProviderHttpSupport() { }

    static RestClient client(
            String providerName, UnitedAirProperties.Providers.Endpoint endpoint) {
        require(providerName, endpoint);
        SimpleClientHttpRequestFactory requestFactory =
                new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(
                Duration.ofMillis(endpoint.getConnectTimeoutMs()));
        requestFactory.setReadTimeout(
                Duration.ofMillis(endpoint.getReadTimeoutMs()));
        return RestClient.builder()
                .baseUrl(endpoint.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + endpoint.getApiKey())
                .requestFactory(requestFactory)
                .build();
    }

    static void require(
            String providerName, UnitedAirProperties.Providers.Endpoint endpoint) {
        if (endpoint == null
                || endpoint.getBaseUrl() == null
                || endpoint.getBaseUrl().isBlank()
                || endpoint.getApiKey() == null
                || endpoint.getApiKey().isBlank()) {
            throw new IllegalStateException(
                    "UNITEDAIR_PROVIDER_MODE=real requires "
                            + providerName
                            + " base URL and API key. Configure the corresponding "
                            + "environment variables; real mode never falls back to simulator.");
        }
        if (endpoint.getConnectTimeoutMs() <= 0
                || endpoint.getReadTimeoutMs() <= 0) {
            throw new IllegalStateException(
                    providerName + " provider timeouts must be positive.");
        }
    }

    static RuntimeException map(String provider, RuntimeException failure) {
        if (failure instanceof RestClientResponseException response
                && response.getStatusCode().value() == 404) {
            return new ApiExceptions.NotFound(
                    provider + " did not find the requested operational record.");
        }
        if (failure instanceof ApiExceptions.ApiException api) {
            return api;
        }
        return new ApiExceptions.UpstreamUnavailable(
                provider + " sandbox is unavailable; no simulator fallback was used.",
                failure);
    }
}
