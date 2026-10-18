package com.techinterviewer.evaluation.support;

import com.techinterviewer.TestClock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the default AI ports (which are unavailable) by the scripted doubles, and the real wait between evaluation attempts by one
 * that moves the test clock; autowire them to script a test.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ReportTestPorts {

    @Bean
    ScriptedAnswerEvaluator scriptedAnswerEvaluator() {
        return new ScriptedAnswerEvaluator();
    }

    @Bean
    ScriptedReportNarrator scriptedReportNarrator() {
        return new ScriptedReportNarrator();
    }

    @Bean
    @Primary
    RecordingEvaluationPause recordingEvaluationPause(TestClock clock) {
        return new RecordingEvaluationPause(clock);
    }
}
