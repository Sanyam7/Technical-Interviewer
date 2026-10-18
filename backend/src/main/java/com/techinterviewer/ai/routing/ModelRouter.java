package com.techinterviewer.ai.routing;

import com.techinterviewer.ai.provider.GenerationOptions;
import com.techinterviewer.ai.provider.LlmProvider;
import com.techinterviewer.ai.provider.ThinkingLevel;
import com.techinterviewer.ai.provider.gemini.GeminiLlmProvider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps every {@link AiTask} to its provider, model and settings, from configuration only: switching a task from Gemini
 * to Ollama is a configuration change, never a code change.
 *
 * <p>Everything is checked once, at startup, and a wrong configuration stops the application with every problem
 * listed: a task without a route, a route to a provider that is not configured, a setting the provider cannot honour,
 * and an evaluation budget that does not fit in the evaluation attempt lease. The last one matters for correctness:
 * an attempt is claimed for {@code app.evaluation.attempt-lease}; a call that outlived it could be given up by another
 * request while it still runs, and its valid result would be thrown away.
 */
public final class ModelRouter {

    private final Map<AiTask, ModelRoute> routes;

    /**
     * @param attemptLease {@code app.evaluation.attempt-lease}; the evaluation budget must be shorter
     * @throws IllegalStateException listing every configuration problem
     */
    public ModelRouter(AiProperties properties, Collection<LlmProvider> providers, Duration attemptLease) {
        Map<String, LlmProvider> byId = new HashMap<>();
        providers.forEach(provider -> byId.put(provider.id(), provider));
        List<String> problems = new ArrayList<>();
        Map<AiTask, ModelRoute> resolved = new EnumMap<>(AiTask.class);
        for (AiTask task : AiTask.values()) {
            AiProperties.Route route = properties.routes().get(task);
            if (route == null) {
                problems.add("app.ai.routes." + key(task) + " is missing");
                continue;
            }
            LlmProvider provider = byId.get(route.provider());
            if (provider == null) {
                problems.add("app.ai.routes." + key(task) + ".provider is '" + route.provider() + "', which is not configured"
                        + " (configured: " + (byId.isEmpty() ? "none; set GEMINI_API_KEY or OLLAMA_ENABLED" : byId.keySet()) + ")");
                continue;
            }
            if (provider.id().equals(GeminiLlmProvider.ID) && route.thinking() == ThinkingLevel.OFF) {
                problems.add("app.ai.routes." + key(task) + ".thinking is OFF, which Gemini 3 models do not support; use LOW");
                continue;
            }
            resolved.put(task, new ModelRoute(
                    task,
                    provider,
                    route.model(),
                    new GenerationOptions(route.temperature(), route.maxOutputTokens(), route.thinking()),
                    route.budget(),
                    callTimeout(properties, provider.id()),
                    maxRetries(properties, provider.id())));
        }
        ModelRoute evaluation = resolved.get(AiTask.ANSWER_EVALUATION);
        if (evaluation != null && evaluation.budget().compareTo(attemptLease) >= 0) {
            problems.add("app.ai.routes.answer-evaluation.budget (" + evaluation.budget() + ") must be shorter than"
                    + " app.evaluation.attempt-lease (" + attemptLease + "): an evaluation that outlives its claim can be"
                    + " given up while it still runs");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("The AI layer is enabled but misconfigured:\n - " + String.join("\n - ", problems));
        }
        this.routes = Map.copyOf(resolved);
    }

    public ModelRoute route(AiTask task) {
        return routes.get(task);
    }

    private static Duration callTimeout(AiProperties properties, String provider) {
        return provider.equals(GeminiLlmProvider.ID)
                ? properties.providers().gemini().timeout()
                : properties.providers().ollama().timeout();
    }

    private static int maxRetries(AiProperties properties, String provider) {
        return provider.equals(GeminiLlmProvider.ID)
                ? properties.providers().gemini().maxRetries()
                : properties.providers().ollama().maxRetries();
    }

    private static String key(AiTask task) {
        return task.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
}
