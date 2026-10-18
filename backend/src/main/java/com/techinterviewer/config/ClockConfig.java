package com.techinterviewer.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ClockConfig {

    /** The single time source of the application; inject it instead of calling {@code Instant.now()}. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
