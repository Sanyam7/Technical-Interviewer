package com.techinterviewer.resume;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The beans of the résumé feature: the vocabulary, loaded and validated at startup. */
@Configuration(proxyBeanMethods = false)
public class ResumeConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ResumeConfiguration.class);

    @Bean
    ResumeVocabulary resumeVocabulary() {
        ResumeVocabulary vocabulary = ResumeVocabulary.load();
        log.info("Résumé vocabulary: {} technologies, keywords for {} topics", vocabulary.technologies().size(), vocabulary.keywords().size());
        return vocabulary;
    }
}
