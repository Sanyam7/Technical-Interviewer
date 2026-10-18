package com.techinterviewer.evaluation.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techinterviewer.evaluation.domain.Recommendation;
import com.techinterviewer.evaluation.domain.ReportOutcome;
import com.techinterviewer.evaluation.domain.ReportStatus;
import com.techinterviewer.evaluation.service.EvaluationProperties;
import com.techinterviewer.evaluation.service.EvaluationService;
import com.techinterviewer.evaluation.support.Assessments;
import com.techinterviewer.evaluation.support.ReportTestBase;
import com.techinterviewer.evaluation.support.ScenarioBuilder;
import com.techinterviewer.evaluation.support.ScenarioBuilder.Asked;
import com.techinterviewer.interview.domain.EvaluationFailure;
import com.techinterviewer.interview.domain.InterviewEvent;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.interview.domain.InterviewStateTransition;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.port.AnswerBlockedException;
import com.techinterviewer.security.TestAuth;
import com.techinterviewer.user.Role;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Reports of interviews in which some answers could not be assessed: the interview always reaches COMPLETED, the report states
 * its outcome (COMPLETE, PARTIAL or INCONCLUSIVE) from the share of the intended assessment weight that was assessable, and the
 * answers that were left out are disclosed, never scored zero.
 */
class ReportGiveUpIT extends ReportTestBase {

    private static final String[] TOPICS = {
        "java/concurrency", "java/generics", "java/exceptions", "spring-boot/transactions", "spring-boot/data-jpa", "postgresql/indexing"
    };

    @Autowired
    ReportFinalizationService finalizer;

    @Autowired
    EvaluationService evaluationService;

    @Autowired
    EvaluationProperties properties;

    // ---- building interviews -----------------------------------------------------------------------------------

    /** One technical root question per difficulty, each answered; {@code blocked[i]} answers the provider refuses to assess. */
    private record Scenario(ScenarioBuilder.Built built, List<Asked> asked) {
    }

    private Scenario technicalAnswers(int[] difficulties, boolean[] blocked, int rating) {
        ScenarioBuilder builder = newInterview();
        List<Asked> asked = new java.util.ArrayList<>();
        for (int i = 0; i < difficulties.length; i++) {
            asked.add(builder.technical(TOPICS[i], difficulties[i], "answer " + i));
        }
        Scenario scenario = new Scenario(builder.buildFinished(), asked);
        for (int i = 0; i < difficulties.length; i++) {
            if (blocked[i]) {
                blocks(asked.get(i));
            } else {
                evaluator.script(asked.get(i).answerId(), request -> Assessments.uniform(request.evaluator(), rating, request.expectedKeyPoints()));
            }
        }
        return scenario;
    }

    private void blocks(Asked asked) {
        evaluator.script(asked.answerId(), request -> {
            throw new AnswerBlockedException("blocked by the provider");
        });
    }

    private FinalizationResult finalizeInterview(Scenario scenario) {
        return finalizer.finalizeInterview(scenario.built().interviewId(), scenario.built().userId());
    }

    private InterviewReport reportOf(UUID interviewId) {
        return inTransaction(() -> reports.findByInterviewId(interviewId).orElseThrow());
    }

    private List<InterviewStateTransition> transitionsOf(UUID interviewId) {
        return inTransaction(() -> transitions.findByInterviewIdOrderByOccurredAtAscIdAsc(interviewId));
    }

    private ReportFinalizationService serviceWith(String minCoverage) {
        return new ReportFinalizationService(
                context.getBean(com.techinterviewer.evaluation.repository.InterviewLockRepository.class),
                context.getBean(com.techinterviewer.interview.repository.InterviewRepository.class),
                context.getBean(com.techinterviewer.interview.repository.InterviewConfigurationRepository.class),
                context.getBean(com.techinterviewer.interview.repository.InterviewQuestionRepository.class),
                context.getBean(com.techinterviewer.interview.repository.CandidateAnswerRepository.class),
                context.getBean(com.techinterviewer.evaluation.repository.EvaluationRepository.class),
                context.getBean(com.techinterviewer.evaluation.repository.InterviewReportRepository.class),
                context.getBean(com.techinterviewer.evaluation.repository.ReportSkillScoreRepository.class),
                context.getBean(com.techinterviewer.interview.repository.InterviewStateTransitionRepository.class),
                context.getBean(com.techinterviewer.catalog.CatalogService.class),
                evaluationService,
                new EvaluationProperties(properties.maxAttempts(), properties.backoffInitial(), properties.backoffMax(), properties.attemptLease(),
                        new BigDecimal(minCoverage)),
                narrator,
                clock,
                context.getBean(PlatformTransactionManager.class));
    }

    private ResultActions getReport(Scenario scenario) throws Exception {
        return mockMvc.perform(get("/api/v1/interviews/" + scenario.built().interviewId() + "/report")
                .with(TestAuth.forContext(context).asUser(scenario.built().userId(), Role.CANDIDATE)));
    }

    // ---- the outcomes -------------------------------------------------------------------------------------------

    @Test
    void whenEveryAnswerIsAssessedTheReportIsCompleteAndSaysSo() throws Exception {
        Scenario scenario = technicalAnswers(new int[] {3, 3, 3}, new boolean[] {false, false, false}, 3);

        assertThat(finalizeInterview(scenario)).isEqualTo(new FinalizationResult(InterviewState.COMPLETED, ReportStatus.READY, false));

        assertThat(reportOf(scenario.built().interviewId()).getOutcome()).isEqualTo(ReportOutcome.COMPLETE);
        getReport(scenario).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("COMPLETE"))
                .andExpect(jsonPath("$.assessment.assessedShare").value(1.0))
                .andExpect(jsonPath("$.assessment.assessedWeight").value(9))
                .andExpect(jsonPath("$.assessment.intendedWeight").value(9))
                .andExpect(jsonPath("$.assessment.unassessableAnswers", hasSize(0)));
    }

    @Test
    void aPartialReportScoresTheAssessableAnswersDisclosesTheOthersAndStillGetsANarrative() throws Exception {
        Scenario scenario = technicalAnswers(new int[] {3, 3, 3, 3, 3, 3}, new boolean[] {false, false, true, false, false, false}, 3);

        assertThat(finalizeInterview(scenario)).isEqualTo(new FinalizationResult(InterviewState.COMPLETED, ReportStatus.READY, false));

        InterviewReport report = reportOf(scenario.built().interviewId());
        assertThat(report.getOutcome()).isEqualTo(ReportOutcome.PARTIAL);
        assertThat(report.getOverallScore()).as("rating 3 of 4 is 75 on every assessed answer; the refused one is not a zero").isEqualByComparingTo("75.00");
        assertThat(report.getRecommendation()).isEqualTo(Recommendation.PROFICIENT);
        assertThat(narrator.calls()).isEqualTo(1);
        assertThat(narrator.requests().get(0).assessmentCoverage()).isEqualTo(0.83);
        assertThat(narrator.requests().get(0).unassessableAnswers()).isEqualTo(1);
        getReport(scenario).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("PARTIAL"))
                .andExpect(jsonPath("$.overallScore").value(75.0))
                .andExpect(jsonPath("$.recommendation").value("PROFICIENT"))
                .andExpect(jsonPath("$.assessment.assessedShare").value(0.83))
                .andExpect(jsonPath("$.assessment.assessedWeight").value(15))
                .andExpect(jsonPath("$.assessment.intendedWeight").value(18))
                .andExpect(jsonPath("$.assessment.unassessableAnswers", hasSize(1)))
                .andExpect(jsonPath("$.assessment.unassessableAnswers[0].questionId").value(scenario.asked().get(2).questionId().toString()))
                .andExpect(jsonPath("$.assessment.unassessableAnswers[0].sequenceNo").value(scenario.asked().get(2).sequenceNo()))
                .andExpect(jsonPath("$.assessment.unassessableAnswers[0].section").value("QUESTIONING"))
                .andExpect(jsonPath("$.assessment.unassessableAnswers[0].reason").value("SAFETY_BLOCK"))
                .andExpect(jsonPath("$.coverage.questionsAnswered").value(6));
        assertThat(count("evaluations")).isEqualTo(5);
    }

    @Test
    void anInconclusiveReportHasNoDefinitiveVerdictButKeepsTheEvidenceAndTheDisclosure() throws Exception {
        Scenario scenario = technicalAnswers(new int[] {3, 3, 3, 3}, new boolean[] {false, true, true, true}, 4);

        assertThat(finalizeInterview(scenario)).isEqualTo(new FinalizationResult(InterviewState.COMPLETED, ReportStatus.READY, false));

        InterviewReport report = reportOf(scenario.built().interviewId());
        assertThat(report.getStatus()).isEqualTo(ReportStatus.READY);
        assertThat(report.getOutcome()).isEqualTo(ReportOutcome.INCONCLUSIVE);
        assertThat(report.getOverallScore()).isNull();
        assertThat(report.getRecommendation()).isNull();
        assertThat(report.getNarrative()).isNull();
        assertThat(narrator.calls()).as("nothing definitive to narrate").isZero();
        assertThat(count("report_skill_scores")).as("the evidence of the assessed answer is kept, with its evidence count").isEqualTo(1);
        getReport(scenario).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.outcome").value("INCONCLUSIVE"))
                .andExpect(jsonPath("$.overallScore").value((Object) null))
                .andExpect(jsonPath("$.recommendation").value((Object) null))
                .andExpect(jsonPath("$.assessment.assessedShare").value(0.25))
                .andExpect(jsonPath("$.assessment.unassessableAnswers", hasSize(3)))
                .andExpect(jsonPath("$.skills", hasSize(1)));
        assertThat(stateOf(scenario.built().interviewId())).isEqualTo(InterviewState.COMPLETED);
    }

    @Test
    void whenNothingCanBeAssessedTheInterviewStillCompletesWithAnInconclusiveReport() throws Exception {
        Scenario scenario = technicalAnswers(new int[] {3, 4}, new boolean[] {true, true}, 3);

        FinalizationResult result = finalizeInterview(scenario);

        assertThat(result).isEqualTo(new FinalizationResult(InterviewState.COMPLETED, ReportStatus.READY, false));
        InterviewReport report = reportOf(scenario.built().interviewId());
        assertThat(report.getOutcome()).isEqualTo(ReportOutcome.INCONCLUSIVE);
        assertThat(report.getCoverage().assessedWeight()).isZero();
        assertThat(report.getCoverage().intendedWeight()).isEqualTo(7);
        assertThat(report.getCoverage().unassessable()).hasSize(2).allSatisfy(answer -> assertThat(answer.reason()).isEqualTo(EvaluationFailure.SAFETY_BLOCK));
        assertThat(report.getSectionScores()).isEmpty();
        assertThat(count("evaluations")).isZero();
        assertThat(count("report_skill_scores")).isZero();
    }

    @Test
    void anUnassessableIntroductionOnItsOwnIsInconclusive() {
        ScenarioBuilder builder = newInterview();
        Asked intro = builder.intro("Hello, I am a developer.");
        ScenarioBuilder.Built built = builder.buildFinished();
        blocks(intro);

        FinalizationResult result = finalizer.finalizeInterview(built.interviewId(), built.userId());

        assertThat(result.state()).isEqualTo(InterviewState.COMPLETED);
        InterviewReport report = reportOf(built.interviewId());
        assertThat(report.getOutcome()).isEqualTo(ReportOutcome.INCONCLUSIVE);
        assertThat(report.getCoverage().unassessable()).singleElement().satisfies(answer -> assertThat(answer.section()).isEqualTo(Section.INTRODUCTION));
        assertThat(report.getSectionScores()).isEmpty();
    }

    // ---- the threshold, with real answers -------------------------------------------------------------------------

    @Test
    void exactlySeventyPercentOfTheWeightIsEnough() {
        // assessed 3 + 2 + 2 = 7, refused 3: 7 of 10
        Scenario scenario = technicalAnswers(new int[] {3, 2, 2, 3}, new boolean[] {false, false, false, true}, 3);

        finalizeInterview(scenario);

        InterviewReport report = reportOf(scenario.built().interviewId());
        assertThat(report.getCoverage().assessedWeight()).isEqualTo(7);
        assertThat(report.getCoverage().intendedWeight()).isEqualTo(10);
        assertThat(report.getOutcome()).isEqualTo(ReportOutcome.PARTIAL);
        assertThat(report.getOverallScore()).isNotNull();
    }

    @Test
    void justBelowSeventyPercentIsInconclusive() {
        // assessed 5 + 5 + 5 + 1 = 16, refused 5 + 2: 16 of 23 is 69.6%
        Scenario scenario = technicalAnswers(new int[] {5, 5, 5, 1, 5, 2}, new boolean[] {false, false, false, false, true, true}, 3);

        finalizeInterview(scenario);

        InterviewReport report = reportOf(scenario.built().interviewId());
        assertThat(report.getCoverage().assessedWeight()).isEqualTo(16);
        assertThat(report.getCoverage().intendedWeight()).isEqualTo(23);
        assertThat(report.getOutcome()).isEqualTo(ReportOutcome.INCONCLUSIVE);
        assertThat(report.getOverallScore()).isNull();
        assertThat(report.getRecommendation()).isNull();
    }

    @Test
    void justAboveSeventyPercentIsPartial() {
        // assessed 5 + 5 + 5 + 2 = 17, refused 5 + 1: 17 of 23 is 73.9%
        Scenario scenario = technicalAnswers(new int[] {5, 5, 5, 2, 5, 1}, new boolean[] {false, false, false, false, true, true}, 3);

        finalizeInterview(scenario);

        InterviewReport report = reportOf(scenario.built().interviewId());
        assertThat(report.getCoverage().assessedWeight()).isEqualTo(17);
        assertThat(report.getOutcome()).isEqualTo(ReportOutcome.PARTIAL);
        assertThat(report.getOverallScore()).isEqualByComparingTo("75.00");
    }

    @Test
    void theThresholdIsConfigurable() {
        // 8 of 10 assessed
        Scenario strict = technicalAnswers(new int[] {4, 4, 2}, new boolean[] {false, false, true}, 3);
        Scenario lenient = technicalAnswers(new int[] {2, 2, 2, 2, 2}, new boolean[] {false, false, true, true, true}, 3);

        serviceWith("0.90").finalizeInterview(strict.built().interviewId(), strict.built().userId());
        serviceWith("0.30").finalizeInterview(lenient.built().interviewId(), lenient.built().userId());

        assertThat(reportOf(strict.built().interviewId()).getOutcome()).as("8 of 10 is below 90%").isEqualTo(ReportOutcome.INCONCLUSIVE);
        assertThat(reportOf(lenient.built().interviewId()).getOutcome()).as("4 of 10 is above 30%").isEqualTo(ReportOutcome.PARTIAL);
    }

    // ---- never stuck ----------------------------------------------------------------------------------------------

    @Test
    void anEvaluatorThatStaysDownCannotKeepTheInterviewInTheFinalEvaluation() throws Exception {
        Scenario scenario = technicalAnswers(new int[] {3, 3, 3}, new boolean[] {false, false, false}, 3);
        evaluator.unavailable();

        FinalizationResult result = finalizeInterview(scenario);

        assertThat(result).isEqualTo(new FinalizationResult(InterviewState.COMPLETED, ReportStatus.READY, false));
        assertThat(evaluator.calls()).isEqualTo(9);
        assertThat(reportOf(scenario.built().interviewId()).getOutcome()).isEqualTo(ReportOutcome.INCONCLUSIVE);
        assertThat(reportOf(scenario.built().interviewId()).getCoverage().unassessable())
                .extracting(answer -> answer.reason()).containsOnly(EvaluationFailure.PROVIDER_FAILURE);
        getReport(scenario).andExpect(status().isOk()).andExpect(jsonPath("$.assessment.unassessableAnswers", hasSize(3)));
        mockMvcRetry(scenario).andExpect(status().isConflict());
    }

    private ResultActions mockMvcRetry(Scenario scenario) throws Exception {
        return mockMvc.perform(post("/api/v1/interviews/" + scenario.built().interviewId() + "/report/retry")
                .with(TestAuth.forContext(context).asUser(scenario.built().userId(), Role.CANDIDATE)));
    }

    @Test
    void finalisingAgainAfterwardsChangesNothingAndAsksNoOne() {
        Scenario scenario = technicalAnswers(new int[] {3, 3}, new boolean[] {false, true}, 3);
        finalizeInterview(scenario);
        int calls = evaluator.calls();
        int rows = transitionsOf(scenario.built().interviewId()).size();
        InterviewReport before = reportOf(scenario.built().interviewId());

        FinalizationResult again = finalizeInterview(scenario);

        assertThat(again).isEqualTo(new FinalizationResult(InterviewState.COMPLETED, ReportStatus.READY, false));
        assertThat(evaluator.calls()).isEqualTo(calls);
        assertThat(transitionsOf(scenario.built().interviewId())).hasSize(rows);
        InterviewReport after = reportOf(scenario.built().interviewId());
        assertThat(after.getAttempts()).isEqualTo(before.getAttempts());
        assertThat(after.getOutcome()).isEqualTo(before.getOutcome());
        assertThat(after.getCoverage()).isEqualTo(before.getCoverage());
        assertThat(count("reports", "interview_id", scenario.built().interviewId())).isEqualTo(1);
    }

    @Test
    void twoSimultaneousFinishesOverAnOutageGiveTheSameResultAndNeverExceedTheAttemptLimit() throws Exception {
        Scenario scenario = technicalAnswers(new int[] {3, 3, 3}, new boolean[] {false, false, false}, 3);
        evaluator.unavailable();
        ReportFinalizationService patient = serviceWith("0.70");
        CyclicBarrier lineUp = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<FinalizationResult>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    lineUp.await(30, TimeUnit.SECONDS);
                    return patient.finalizeInterview(scenario.built().interviewId(), scenario.built().userId());
                }));
            }
            for (Future<FinalizationResult> future : futures) {
                assertThat(future.get(120, TimeUnit.SECONDS)).isEqualTo(new FinalizationResult(InterviewState.COMPLETED, ReportStatus.READY, false));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(evaluator.calls()).as("three attempts per answer, shared by both callers").isEqualTo(9);
        assertThat(count("reports", "interview_id", scenario.built().interviewId())).isEqualTo(1);
        assertThat(transitionsOf(scenario.built().interviewId())).filteredOn(t -> t.getEvent() == InterviewEvent.REPORT_READY).hasSize(1);
        assertThat(jdbc.queryForList("select evaluation_attempts from answers where interview_id = ? and not skipped", Integer.class,
                scenario.built().interviewId())).containsOnly(3);
    }

    @Test
    void afterARestartTheFinishPicksUpWhereTheAttemptsStoppedAndCompletes() {
        Scenario scenario = technicalAnswers(new int[] {3, 3}, new boolean[] {false, false}, 3);
        evaluator.unavailable();
        // A process died after two attempts per answer, and after it had started a report.
        jdbc.update("update answers set evaluation_attempts = 2, evaluation_failure = 'PROVIDER_FAILURE', next_evaluation_at = ? where interview_id = ? and not skipped",
                Timestamp.from(clock.instant().minusSeconds(30)), scenario.built().interviewId());
        inTransaction(() -> {
            InterviewReport stuck = InterviewReport.pending(scenario.built().interviewId(), clock.instant());
            stuck.markGenerating();
            reports.saveAndFlush(stuck);
        });
        ReportFinalizationService impatient = new ReportFinalizationService(
                context.getBean(com.techinterviewer.evaluation.repository.InterviewLockRepository.class),
                context.getBean(com.techinterviewer.interview.repository.InterviewRepository.class),
                context.getBean(com.techinterviewer.interview.repository.InterviewConfigurationRepository.class),
                context.getBean(com.techinterviewer.interview.repository.InterviewQuestionRepository.class),
                context.getBean(com.techinterviewer.interview.repository.CandidateAnswerRepository.class),
                context.getBean(com.techinterviewer.evaluation.repository.EvaluationRepository.class),
                context.getBean(com.techinterviewer.evaluation.repository.InterviewReportRepository.class),
                context.getBean(com.techinterviewer.evaluation.repository.ReportSkillScoreRepository.class),
                context.getBean(com.techinterviewer.interview.repository.InterviewStateTransitionRepository.class),
                context.getBean(com.techinterviewer.catalog.CatalogService.class),
                evaluationService, properties, narrator, clock, context.getBean(PlatformTransactionManager.class),
                Duration.ofMillis(5), Duration.ofMillis(5));

        FinalizationResult result = impatient.finalizeInterview(scenario.built().interviewId(), scenario.built().userId());

        assertThat(result).isEqualTo(new FinalizationResult(InterviewState.COMPLETED, ReportStatus.READY, false));
        assertThat(evaluator.calls()).as("exactly the one attempt each that was left").isEqualTo(2);
        assertThat(reportOf(scenario.built().interviewId()).getOutcome()).isEqualTo(ReportOutcome.INCONCLUSIVE);
        assertThat(jdbc.queryForList("select evaluation_attempts from answers where interview_id = ? and not skipped", Integer.class,
                scenario.built().interviewId())).containsOnly(3);
        assertThat(stateOf(scenario.built().interviewId())).isEqualTo(InterviewState.COMPLETED);
    }

    @Test
    void anAnswerBeingEvaluatedElsewhereHoldsTheReportBackUntilItsLeaseEndsAndThenItIsSettled() {
        Scenario scenario = technicalAnswers(new int[] {3, 3}, new boolean[] {false, false}, 3);
        evaluator.unavailable();
        jdbc.update("update answers set evaluation_attempts = 3, next_evaluation_at = ? where id = ?",
                Timestamp.from(clock.instant().plusSeconds(60)), scenario.asked().get(0).answerId());

        FinalizationResult held = finalizeInterview(scenario);

        assertThat(held).isEqualTo(new FinalizationResult(InterviewState.FINAL_EVALUATION, ReportStatus.FAILED, true));
        assertThat(reportOf(scenario.built().interviewId()).getErrorMessage()).isEqualTo("1 answer still awaits evaluation. Try again shortly.");

        clock.advance(Duration.ofSeconds(61));
        FinalizationResult settled = finalizeInterview(scenario);

        assertThat(settled).isEqualTo(new FinalizationResult(InterviewState.COMPLETED, ReportStatus.READY, false));
        assertThat(reportOf(scenario.built().interviewId()).getOutcome()).isEqualTo(ReportOutcome.INCONCLUSIVE);
        assertThat(transitionsOf(scenario.built().interviewId())).extracting(InterviewStateTransition::getEvent)
                .endsWith(InterviewEvent.REPORT_FAILED, InterviewEvent.REPORT_READY);
    }
}
