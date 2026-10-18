package com.techinterviewer.ai.provider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The HTTP exchange shared by the providers: POST a JSON body, wait for the whole response at most the request's
 * timeout (body included: {@link HttpRequest#timeout} alone only bounds the wait for the headers), and turn transport
 * and status failures into {@link LlmException}s.
 *
 * <p>Status mapping: 429 is {@link RateLimitedException} (with {@code Retry-After} in seconds when present); 408 and
 * 5xx are retryable {@link ProviderUnavailableException}s; every other 4xx is an {@link InvalidRequestException}.
 */
public final class ProviderHttp {

    /** Longest provider error text kept in a message (it ends up in {@code llm_calls.error_message}). */
    static final int MAX_ERROR_DETAIL = 300;

    private final HttpClient client;
    private final ObjectMapper json;

    public ProviderHttp(HttpClient client, ObjectMapper json) {
        this.client = client;
        this.json = json;
    }

    /** A client with a bounded connection time; the per-call limit comes from each request. */
    public static HttpClient defaultClient(Duration connectTimeout) {
        return HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * @param provider name used in messages
     * @param headers  extra headers (an API key travels here, never in the URL)
     * @return the parsed response body of a 2xx answer
     */
    public JsonNode postJson(String provider, URI uri, Map<String, String> headers, JsonNode body, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        HttpResponse<String> response = send(provider, builder.build(), timeout);
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            try {
                return json.readTree(response.body());
            } catch (JacksonException unreadable) {
                throw new ProviderUnavailableException(provider + " returned a response that is not JSON", false, unreadable);
            }
        }
        String detail = provider + " answered HTTP " + status + errorDetail(response.body());
        if (status == 429) {
            Duration header = retryAfter(response);
            throw new RateLimitedException(detail, header != null ? header : retryDelay(response.body()));
        }
        if (status == 408 || status >= 500) {
            throw new ProviderUnavailableException(detail, true);
        }
        throw new InvalidRequestException(detail);
    }

    private HttpResponse<String> send(String provider, HttpRequest request, Duration timeout) {
        CompletableFuture<HttpResponse<String>> pending =
                client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            return pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException late) {
            pending.cancel(true);
            throw new LlmTimeoutException(provider + " did not answer within " + timeout, late);
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new ProviderUnavailableException("interrupted while waiting for " + provider, false, interrupted);
        } catch (CancellationException cancelled) {
            throw new ProviderUnavailableException("the call to " + provider + " was cancelled", false, cancelled);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause() == null ? failed : failed.getCause();
            if (cause instanceof HttpTimeoutException) {
                throw new LlmTimeoutException(provider + " did not answer within " + timeout, cause);
            }
            if (cause instanceof IOException) {
                throw new ProviderUnavailableException(provider + " cannot be reached: " + cause.getClass().getSimpleName(), true, cause);
            }
            throw new ProviderUnavailableException("the call to " + provider + " failed: " + cause.getClass().getSimpleName(), false, cause);
        }
    }

    /** The provider's own error message, shortened: Gemini sends {@code {"error":{"message":...}}}, Ollama {@code {"error":"..."}}. */
    private String errorDetail(@Nullable String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String message;
        try {
            JsonNode error = json.readTree(body).path("error");
            message = error.isString() ? error.asString() : error.path("message").asString("");
        } catch (JacksonException notJson) {
            message = "";
        }
        if (message.isBlank()) {
            return "";
        }
        return ": " + (message.length() > MAX_ERROR_DETAIL ? message.substring(0, MAX_ERROR_DETAIL) + "..." : message);
    }

    /** The longest wait a provider may ask for and be believed: a day. A larger or malformed value is ignored (backoff applies). */
    private static final Duration MAX_RETRY_DELAY = Duration.ofDays(1);
    private static final java.util.regex.Pattern RETRY_DELAY = java.util.regex.Pattern.compile("(\\d{1,9})(?:\\.(\\d{1,9}))?s");

    /**
     * The wait Gemini asks for in the body of a 429: it sends no Retry-After header but a {@code google.rpc.RetryInfo} entry in
     * {@code error.details}, with a protobuf duration such as {@code "27s"} or {@code "27.966937085s"} (rounded up to the millisecond).
     */
    private @Nullable Duration retryDelay(@Nullable String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            for (JsonNode detail : json.readTree(body).path("error").path("details")) {
                if (!detail.path("@type").asString("").endsWith("google.rpc.RetryInfo")) {
                    continue;
                }
                java.util.regex.Matcher delay = RETRY_DELAY.matcher(detail.path("retryDelay").asString(""));
                if (!delay.matches()) {
                    return null;
                }
                String fraction = delay.group(2) == null ? "0" : (delay.group(2) + "00000000").substring(0, 9);
                Duration wait = Duration.ofSeconds(Long.parseLong(delay.group(1)), Long.parseLong(fraction));
                Duration rounded = Duration.ofMillis(wait.toMillis() + (wait.toNanosPart() % 1_000_000 == 0 ? 0 : 1));
                return rounded.compareTo(MAX_RETRY_DELAY) <= 0 ? rounded : null;
            }
        } catch (JacksonException notJson) {
            return null;
        }
        return null;
    }

    private static @Nullable Duration retryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After")
                .map(String::trim)
                .filter(value -> value.matches("\\d{1,6}"))
                .map(seconds -> Duration.ofSeconds(Long.parseLong(seconds)))
                .orElse(null);
    }
}
