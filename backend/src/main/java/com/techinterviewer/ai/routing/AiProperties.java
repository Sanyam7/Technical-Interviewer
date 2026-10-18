package com.techinterviewer.ai.routing;

import com.techinterviewer.ai.provider.ThinkingLevel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The AI layer ({@code app.ai}): whether it is on, the providers, and the route of every task. The routes are listed
 * in {@code application.yml}; the {@code local-ollama} profile moves them all to a local model.
 *
 * @param enabled   the AI layer replaces the default "unavailable" ports. Off by default so that the application and
 *                  its tests run without any provider; the {@code prod} profile turns it on
 * @param providers connection settings per provider
 * @param routes    provider, model and settings per task; every task needs one when the layer is enabled
 */
@ConfigurationProperties("app.ai")
@Validated
public record AiProperties(
        @DefaultValue("false") boolean enabled,
        @Valid @NotNull @DefaultValue Providers providers,
        @NotNull @DefaultValue Map<AiTask, @Valid Route> routes) {

    /** The providers; a provider is registered only when it is configured (Gemini: an API key; Ollama: enabled). */
    public record Providers(@Valid @NotNull @DefaultValue Gemini gemini, @Valid @NotNull @DefaultValue Ollama ollama) {
    }

    /**
     * Google Gemini.
     *
     * @param apiKey     {@code GEMINI_API_KEY}; without it Gemini is not registered
     * @param baseUrl    REST base, up to and including the API version
     * @param timeout    longest single call (the task's budget may cut it shorter)
     * @param maxRetries extra attempts after a rate limit, an outage or a timeout, within the task's budget
     */
    public record Gemini(
            @Nullable String apiKey,
            @NotNull @DefaultValue("https://generativelanguage.googleapis.com/v1beta") URI baseUrl,
            @NotNull @DurationMin(seconds = 1) @DurationMax(minutes = 10) @DefaultValue("30s") Duration timeout,
            @Min(0) @Max(5) @DefaultValue("2") int maxRetries) {

        public boolean configured() {
            return apiKey != null && !apiKey.isBlank();
        }

        /** The key never appears in logs or in the actuator. */
        @Override
        public String toString() {
            return "Gemini[apiKey=" + (configured() ? "<redacted>" : "<unset>") + ", baseUrl=" + baseUrl
                    + ", timeout=" + timeout + ", maxRetries=" + maxRetries + "]";
        }
    }

    /**
     * A local Ollama server.
     *
     * @param enabled    register the provider ({@code OLLAMA_ENABLED})
     * @param baseUrl    server address ({@code OLLAMA_BASE_URL})
     * @param timeout    longest single call; local models on a CPU are slow
     * @param maxRetries extra attempts after an outage or a timeout
     * @param contextWindow tokens of prompt and answer together ({@code num_ctx}, {@code OLLAMA_CONTEXT_WINDOW}); Ollama's own default of
     *                      4,096 cuts the answer of a long prompt such as a system design evaluation
     */
    public record Ollama(
            @DefaultValue("false") boolean enabled,
            @NotNull @DefaultValue("http://localhost:11434") URI baseUrl,
            @NotNull @DurationMin(seconds = 1) @DurationMax(minutes = 30) @DefaultValue("120s") Duration timeout,
            @Min(0) @Max(5) @DefaultValue("1") int maxRetries,
            @Min(2048) @Max(262144) @DefaultValue("16384") int contextWindow) {
    }

    /**
     * How one task is served.
     *
     * @param provider        {@code gemini} or {@code ollama}
     * @param model           provider model name
     * @param temperature     sampling temperature
     * @param maxOutputTokens output limit (thinking included on Gemini 3)
     * @param thinking        reasoning effort
     * @param budget          the longest the whole operation may take: every model call of it, retries and the repair
     *                        attempt included. The interview holds a request thread meanwhile
     */
    public record Route(
            @NotBlank String provider,
            @NotBlank String model,
            @DecimalMin("0.0") @DecimalMax("2.0") double temperature,
            @Min(1) @Max(65_536) int maxOutputTokens,
            @NotNull ThinkingLevel thinking,
            @NotNull @DurationMin(seconds = 1) @DurationMax(minutes = 30) Duration budget) {
    }
}
