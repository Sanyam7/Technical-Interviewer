package com.techinterviewer.evaluation.service;

import com.techinterviewer.evaluation.ScoringPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The scoring policy of this process, built once from {@link ScoringProperties}; an invalid table stops the startup. */
@Configuration(proxyBeanMethods = false)
class ScoringConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ScoringConfiguration.class);

    @Bean
    ScoringPolicy scoringPolicy(ScoringProperties properties) {
        ScoringPolicy policy = properties.policy();
        log.info("Scoring weights:\n  {}", String.join("\n  ", policy.describe()));
        return policy;
    }
}
