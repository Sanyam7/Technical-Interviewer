package com.techinterviewer.coding;

import com.techinterviewer.coding.problem.ProblemBank;
import com.techinterviewer.coding.sandbox.CodeSandbox;
import com.techinterviewer.coding.sandbox.DockerCodeSandbox;
import com.techinterviewer.coding.sandbox.SandboxProperties;
import com.techinterviewer.coding.sandbox.SandboxWarmup;
import com.techinterviewer.coding.testing.Harnesses;
import com.techinterviewer.coding.testing.TestRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The beans of the coding module. The problem bank is always loaded (it also serves AI-reviewed languages); the Docker
 * sandbox only when {@code app.execution.enabled}, otherwise every run is refused as unavailable.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SandboxProperties.class)
public class CodingConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CodingConfiguration.class);

    @Bean
    ProblemBank problemBank() {
        ProblemBank bank = ProblemBank.load();
        log.info("Coding problem bank: {} problems", bank.all().size());
        return bank;
    }

    @Bean
    CodeSandbox codeSandbox(SandboxProperties properties) {
        if (!properties.enabled()) {
            log.info("Code execution is off (app.execution.enabled=false): coding answers are reviewed by the AI evaluator only");
            return CodeSandbox.DISABLED;
        }
        log.info("Code execution is on: Docker sandbox ({} concurrent, {} per run, {} MB, runtime {})", properties.maxConcurrent(),
                properties.runTimeout(), properties.memoryMb(), properties.runtime().isBlank() ? "default" : properties.runtime());
        return new DockerCodeSandbox(properties);
    }

    @Bean
    SandboxWarmup sandboxWarmup(SandboxProperties properties) {
        return new SandboxWarmup(properties);
    }

    @Bean
    TestRunner testRunner(CodeSandbox sandbox, SandboxProperties properties) {
        return new TestRunner(sandbox, new Harnesses(properties.images()));
    }
}
