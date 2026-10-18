package com.techinterviewer.questionbank;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The curated question bank, loaded and validated at startup (AI_ARCHITECTURE.md section 2.15). */
@Configuration(proxyBeanMethods = false)
public class QuestionBankConfiguration {

    private static final Logger log = LoggerFactory.getLogger(QuestionBankConfiguration.class);

    @Bean
    QuestionBank questionBank() {
        QuestionBank bank = QuestionBank.load();
        log.info("Question bank: {} topics, {} templates", bank.topics().size(), bank.templateCount());
        return bank;
    }
}
