package com.techinterviewer.ai.routing;

import com.techinterviewer.ai.provider.GenerationOptions;
import com.techinterviewer.ai.provider.LlmProvider;
import com.techinterviewer.common.validation.Require;
import java.time.Duration;

/**
 * A resolved route: which provider and model serve a task, with what settings and within what time.
 *
 * @param task         the task
 * @param provider     the provider
 * @param model        model name at that provider
 * @param options      sampling settings
 * @param budget       the longest the whole operation may take (all calls, retries and repair)
 * @param callTimeout  the longest one call may take (the provider's timeout; the budget may cut it shorter)
 * @param maxRetries   extra calls allowed after a retryable failure
 */
public record ModelRoute(
        AiTask task,
        LlmProvider provider,
        String model,
        GenerationOptions options,
        Duration budget,
        Duration callTimeout,
        int maxRetries) {

    public ModelRoute {
        Require.nonNull(task, "task");
        Require.nonNull(provider, "provider");
        Require.notBlank(model, "model", 80);
        Require.nonNull(options, "options");
        Require.nonNull(budget, "budget");
        Require.nonNull(callTimeout, "callTimeout");
        Require.between(maxRetries, 0, 5, "maxRetries");
    }
}
