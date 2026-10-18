package com.techinterviewer.coding.support;

import com.techinterviewer.coding.problem.ProblemBank;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the sandbox with {@link ScriptedSandbox} in an integration test. */
@TestConfiguration(proxyBeanMethods = false)
public class ScriptedSandboxConfiguration {

    @Bean
    @Primary
    ScriptedSandbox scriptedSandbox(ProblemBank bank) {
        return new ScriptedSandbox(bank);
    }
}
