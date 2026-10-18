package com.techinterviewer.interview.turn;

import com.techinterviewer.evaluation.support.ReportTestPorts;
import com.techinterviewer.evaluation.support.ScriptedQuestionGenerator;
import com.techinterviewer.resume.ScriptedResumeExtractor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** The AI ports as scripted doubles: the generator and the résumé extractor of this lane plus the evaluator and narrator of the report lane. */
@TestConfiguration(proxyBeanMethods = false)
@Import(ReportTestPorts.class)
public class TurnTestPorts {

    @Bean
    ScriptedQuestionGenerator scriptedQuestionGenerator() {
        return new ScriptedQuestionGenerator();
    }

    @Bean
    ScriptedResumeExtractor scriptedResumeExtractor() {
        return new ScriptedResumeExtractor();
    }
}
