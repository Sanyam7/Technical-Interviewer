package com.techinterviewer.evaluation.support;

import com.techinterviewer.TestClock;
import com.techinterviewer.TestClockConfiguration;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.persistence.PersistenceTestBase;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base of the evaluation and report integration tests: the whole application against a real PostgreSQL, a clock the
 * test moves, and scripted doubles in place of the AI ports. All subclasses share one application context.
 */
@AutoConfigureMockMvc
@Import({ReportTestPorts.class, TestClockConfiguration.class})
public abstract class ReportTestBase extends PersistenceTestBase {

    /** The clock starts after the interviews of {@link ScenarioBuilder} ended, so what the report writes is later than what the interview wrote. */
    protected static final Instant AFTER_THE_INTERVIEW = ScenarioBuilder.START.plus(ScenarioBuilder.RUNTIME).plusSeconds(90);

    @Autowired
    protected ScriptedAnswerEvaluator evaluator;

    @Autowired
    protected ScriptedReportNarrator narrator;

    @Autowired
    protected RecordingEvaluationPause pause;

    @Autowired
    protected TestClock clock;

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ApplicationContext context;

    @BeforeEach
    void resetDoubles() {
        evaluator.reset();
        narrator.reset();
        pause.reset();
        clock.set(AFTER_THE_INTERVIEW);
    }

    /** Starts describing an interview of a new candidate. */
    protected ScenarioBuilder interviewOf(UUID user) {
        return new ScenarioBuilder(user, jdbc, transaction, interviews, configurations, questions, answers, transitions);
    }

    protected ScenarioBuilder newInterview() {
        return interviewOf(newUser());
    }

    protected InterviewState stateOf(UUID interviewId) {
        return InterviewState.valueOf(jdbc.queryForObject("select state from interviews where id = ?", String.class, interviewId));
    }
}
