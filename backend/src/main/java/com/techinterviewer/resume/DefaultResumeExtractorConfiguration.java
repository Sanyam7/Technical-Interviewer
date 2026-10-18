package com.techinterviewer.resume;

import com.techinterviewer.interview.port.AiUnavailableException;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * The {@link ResumeExtractor} of an application without an AI layer: not a fake, every upload fails with
 * {@link AiUnavailableException} ({@code 503 AI_PROVIDER_UNAVAILABLE}) and nothing is stored. An auto-configuration, like
 * {@code DefaultAiPortsConfiguration}, so the AI layer's implementation always wins.
 */
@AutoConfiguration
public class DefaultResumeExtractorConfiguration {

    @Bean
    @ConditionalOnMissingBean(ResumeExtractor.class)
    ResumeExtractor unavailableResumeExtractor() {
        return (userId, text) -> {
            throw new AiUnavailableException("Reading a résumé is unavailable: no AI provider is configured.");
        };
    }
}
