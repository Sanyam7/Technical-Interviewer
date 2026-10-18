package com.techinterviewer;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the application's clock by a {@link TestClock} that the test can move; autowire {@code TestClock}. */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfiguration {

    @Bean
    @Primary
    TestClock testClock() {
        return new TestClock();
    }
}
