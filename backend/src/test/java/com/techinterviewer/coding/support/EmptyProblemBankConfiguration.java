package com.techinterviewer.coding.support;

import com.techinterviewer.coding.problem.ProblemBank;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** A problem bank without problems, so every coding problem is written by the question generator. */
@TestConfiguration(proxyBeanMethods = false)
public class EmptyProblemBankConfiguration {

    @Bean
    @Primary
    ProblemBank emptyProblemBank() {
        return new ProblemBank(List.of());
    }
}
