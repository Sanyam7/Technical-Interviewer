package com.techinterviewer.design;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The beans of the system design mode: the problem bank, loaded and validated at startup. */
@Configuration(proxyBeanMethods = false)
public class DesignConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DesignConfiguration.class);

    @Bean
    DesignProblemBank designProblemBank() {
        DesignProblemBank bank = DesignProblemBank.load();
        log.info("System design problem bank: {} problems", bank.all().size());
        return bank;
    }
}
