package com.techinterviewer.interview.turn;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import com.techinterviewer.common.error.FieldValidationException;
import com.techinterviewer.evaluation.report.ReportService;
import com.techinterviewer.evaluation.report.dto.ReportResponse;
import com.techinterviewer.evaluation.domain.ReportStatus;
import com.techinterviewer.evaluation.support.ScriptedAnswerEvaluator.Quality;
import com.techinterviewer.interview.ForbiddenFields;
import com.techinterviewer.interview.api.dto.AnswerRequest;
import com.techinterviewer.interview.api.dto.AnswerResponse;
import com.techinterviewer.interview.api.dto.ConfigurationRequest;
import com.techinterviewer.interview.api.dto.CreateInterviewRequest;
import com.techinterviewer.interview.api.dto.FinishReason;
import com.techinterviewer.interview.api.dto.FinishResponse;
import com.techinterviewer.interview.api.dto.QuestionResponse;
import com.techinterviewer.interview.api.dto.SessionResponse;
import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.InterviewDifficulty;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.interview.domain.InterviewType;
import com.techinterviewer.interview.domain.TargetRole;
import com.techinterviewer.interview.port.AiUnavailableException;
import com.techinterviewer.interview.service.FinishService;
import com.techinterviewer.interview.service.InterviewCreationService;
import com.techinterviewer.interview.service.InterviewLifecycleService;
import com.techinterviewer.interview.service.InterviewQueryService;
import com.techinterviewer.interview.service.NextQuestionService;
import com.techinterviewer.interview.service.TurnService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * A seeded, deterministic simulation: complete interviews of every type and duration, with random answer quality, skips,
 * resubmissions, outages of the evaluator and of the generator, early endings and runs out of time, driven through the real
 * services against the real database. After every step the invariants of {@link TurnInvariants} are checked, and at the
 * end the interview must have reached its final evaluation, the report must be READY (after the outages ended) and its
 * score the recomputation of the stored evaluations. A failure names the case and its seed: run that case again to replay
 * it exactly (the clock, the ports and the random choices are all functions of the seed).
 */
@Tag("simulation")
class TurnSimulationIT extends TurnApiTestBase {

    private static final Duration GRACE = Duration.ofSeconds(30);
    private static final int MAX_STEPS = 400;
    private static final List<String> SKILLS = List.of("java", "spring-boot", "postgresql", "react", "typescript");
    private static final List<String> LANGUAGES = List.of("java", "python", "typescript", "go");
    private static final int[] DURATIONS = {15, 30, 45, 60, 90};

    /** What the simulated interviews went through, so that the simulation cannot pass by never meeting what it claims to test. */
    private static final Map<String, AtomicInteger> SEEN = new ConcurrentHashMap<>();

    private static void saw(String what) {
        SEEN.computeIfAbsent(what, key -> new AtomicInteger()).incrementAndGet();
    }

    @AfterAll
    static void theSimulationMetWhatItClaimsToTest() {
        System.out.println("SIMULATION " + new TreeMap<>(SEEN));
        assertThat(SEEN.getOrDefault("interviews", new AtomicInteger()).get()).isGreaterThanOrEqualTo(120);
        for (String scenario : List.of("end.TIME_UP", "end.CANDIDATE_ENDED", "end.PLAN_COMPLETE", "answers.skipped", "followups", "coding", "design",
                "awaiting.step", "next.unavailable", "evaluator.pending", "report.givenUp", "retry.resubmitted", "expired.refused",
                "quality.injection", "claims")) {
            assertThat(SEEN.getOrDefault(scenario, new AtomicInteger()).get()).as("interviews that met '%s'", scenario).isGreaterThanOrEqualTo(3);
        }
    }

    @Autowired
    private InterviewCreationService creation;

    @Autowired
    private InterviewLifecycleService lifecycle;

    @Autowired
    private TurnService turns;

    @Autowired
    private NextQuestionService nextQuestions;

    @Autowired
    private FinishService finishes;

    @Autowired
    private InterviewQueryService queries;

    @Autowired
    private ReportService reports;

    /** One simulated interview. */
    record Case(long seed, InterviewType type, int minutes, ExperienceLevel experience, InterviewDifficulty difficulty) {

        @Override
        public String toString() {
            return "seed=" + seed + " " + type + " " + minutes + "min " + experience + " " + difficulty;
        }
    }

    /** Every type with every duration at least once, then a seeded sample of the whole cross product. */
    static Stream<Case> cases() {
        List<Case> cases = new ArrayList<>();
        long seed = 1;
        for (InterviewType type : InterviewType.values()) {
            for (int minutes : DURATIONS) {
                Random choice = new Random(seed);
                cases.add(new Case(seed++, type, minutes,
                        ExperienceLevel.values()[choice.nextInt(ExperienceLevel.values().length)],
                        InterviewDifficulty.values()[choice.nextInt(InterviewDifficulty.values().length)]));
            }
        }
        Random sampler = new Random(20261006);
        for (int i = 0; i < 120; i++) {
            cases.add(new Case(seed++,
                    InterviewType.values()[sampler.nextInt(InterviewType.values().length)],
                    DURATIONS[sampler.nextInt(DURATIONS.length)],
                    ExperienceLevel.values()[sampler.nextInt(ExperienceLevel.values().length)],
                    InterviewDifficulty.values()[sampler.nextInt(InterviewDifficulty.values().length)]));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void everySimulatedInterviewKeepsItsInvariantsAndEndsWithAReport(Case simulated) throws Exception {
        try {
            simulate(simulated);
        } catch (Throwable failure) {
            throw new AssertionError("simulation failed, replay with " + simulated + ": " + failure.getMessage(), failure);
        }
    }

    // ---- the simulation ---------------------------------------------------------------------------------------

    private void simulate(Case simulated) throws Exception {
        Random random = new Random(simulated.seed());
        TurnInvariants invariants = new TurnInvariants(jdbc, json, evaluations, GRACE);
        UUID user = newUser();
        UUID id = create(user, simulated, random);
        if (id == null) {
            return;
        }
        saw("interviews");
        lifecycle.start(user, id);
        invariants.check(id);

        int evaluatorOutage = 0;
        int generatorOutage = 0;
        boolean timeJumped = false;
        int answers = 0;
        int endAfter = random.nextInt(100) < 30 ? 1 + random.nextInt(10) : Integer.MAX_VALUE;
        boolean ended = false;

        for (int step = 0; step < MAX_STEPS; step++) {
            SessionResponse session = queries.session(user, id);
            leakCheck(session, "session");
            invariants.check(id);
            if (!session.state().acceptsAnswers()) {
                break;
            }
            // Outages come and go: every step shortens the ones that are on.
            evaluatorOutage = outage(random, evaluatorOutage, 4, 5, value -> {
                if (value > 0) {
                    evaluator.unavailable();
                } else {
                    evaluator.available();
                }
            });
            generatorOutage = outage(random, generatorOutage, 5, 6, value -> {
                if (value > 0) {
                    generator.unavailable();
                } else {
                    generator.available();
                }
            });
            clock.advance(Duration.ofSeconds(5 + random.nextInt(110)));

            if (session.awaitingNextQuestion()) {
                saw("awaiting.step");
                try {
                    SessionResponse recovered = nextQuestions.next(user, id);
                    leakCheck(recovered, "recovered session");
                } catch (AiUnavailableException expected) {
                    saw("next.unavailable");
                    assertThat(generatorOutage).as("the generator is only unavailable during an outage").isPositive();
                } catch (ApiException expired) {
                    assertThat(expired.errorCode()).isIn(ErrorCode.INTERVIEW_EXPIRED);
                }
                continue;
            }
            QuestionResponse current = session.currentQuestion();
            assertThat(current).as("an interview that asks questions and is not waiting has an open question").isNotNull();

            if (!timeJumped && random.nextInt(100) < 2) {
                timeJumped = true;
                clock.set(session.deadlineAt().plusSeconds(1 + random.nextInt(45)));
            }
            if (answers >= endAfter && !ended) {
                ended = true;
                finishes.finish(user, id, FinishReason.CANDIDATE_ENDED);
                invariants.check(id);
                break;
            }

            evaluator.quality(randomQuality(random));
            if (random.nextInt(100) < 3) {
                saw("quality.injection");
                evaluator.flagNextAsInjection(randomQuality(random));
            } else if (random.nextInt(100) < 5) {
                saw("claims");
                evaluator.claimNext(randomQuality(random), List.of("Claim " + step + " about my own experience"));
            }
            try {
                AnswerResponse response;
                if (random.nextInt(100) < 8) {
                    response = turns.skip(user, id, current.id(), random.nextBoolean() ? null : random.nextInt(300));
                } else {
                    response = turns.answer(user, id, answerTo(current, session, random));
                }
                answers++;
                leakCheck(response, "answer response");
                assertThat(response.accepted()).isTrue();
                if (random.nextInt(100) < 6) {
                    AnswerResponse again = turns.answer(user, id, new AnswerRequest(current.id(), "a retry", current.answerFormat().name().equals("CODE") ? codeLanguageOf(session) : null, null));
                    assertThat(again.state()).as("a retry answers with the state the interview is in now").isNotNull();
                    saw("retry.resubmitted");
                    leakCheck(again, "retried answer response");
                }
            } catch (ApiException refused) {
                assertThat(refused.errorCode()).as("only time can refuse a well formed answer to the open question").isEqualTo(ErrorCode.INTERVIEW_EXPIRED);
                assertThat(clock.instant()).isAfter(session.deadlineAt().plus(GRACE));
                saw("expired.refused");
            }
        }

        evaluator.available();
        generator.available();
        SessionResponse after = queries.session(user, id);
        for (int attempts = 0; after.awaitingNextQuestion() && attempts < 5; attempts++) {
            nextQuestions.next(user, id);
            after = queries.session(user, id);
        }
        // Whatever happened, the question phase is over or can be ended now: the candidate finishes.
        if (after.state().acceptsAnswers()) {
            finishes.finish(user, id, FinishReason.CANDIDATE_ENDED);
        }
        invariants.check(id);
        assertThat(queries.session(user, id).interviewComplete()).as("the question phase ended").isTrue();

        // The report: possibly failed first because the evaluator is down, READY once it is back.
        boolean pending = jdbc.sql("SELECT count(*) FROM answers WHERE interview_id = :id AND evaluation_status = 'PENDING'").param("id", id).query(Long.class).single() > 0;
        if (pending) {
            saw("evaluator.pending");
        }
        if (pending && random.nextBoolean()) {
            saw("report.givenUp");
            evaluator.unavailable();
            long waiting = jdbc.sql("SELECT count(*) FROM answers WHERE interview_id = :id AND evaluation_status = 'PENDING'").param("id", id).query(Long.class).single();
            FinishResponse finished = finishes.finish(user, id, FinishReason.ACKNOWLEDGE_COMPLETE);
            // The evaluator stays down: every answer that waited is tried until its attempts are used up, then given up, and the
            // interview is completed with a report that discloses them instead of staying in FINAL_EVALUATION.
            assertThat(finished.reportStatus()).isEqualTo(ReportStatus.READY);
            assertThat(finished.state()).isEqualTo(InterviewState.COMPLETED);
            assertThat(reports.get(user, id).assessment().unassessableAnswers()).hasSizeGreaterThanOrEqualTo((int) waiting);
        } else {
            FinishResponse finished = finishes.finish(user, id, FinishReason.ACKNOWLEDGE_COMPLETE);
            assertThat(finished.reportStatus()).isEqualTo(ReportStatus.READY);
            assertThat(finished.state()).isEqualTo(InterviewState.COMPLETED);
            assertThat(finished.retryable()).isFalse();
        }
        invariants.check(id);
        saw("end." + jdbc.sql("SELECT end_reason FROM interviews WHERE id = :id").param("id", id).query(String.class).single());
        if (jdbc.sql("SELECT count(*) FROM answers WHERE interview_id = :id AND skipped").param("id", id).query(Long.class).single() > 0) {
            saw("answers.skipped");
        }
        if (jdbc.sql("SELECT count(*) FROM questions WHERE interview_id = :id AND kind = 'FOLLOW_UP'").param("id", id).query(Long.class).single() > 0) {
            saw("followups");
        }
        if (jdbc.sql("SELECT count(*) FROM questions WHERE interview_id = :id AND kind = 'CODING'").param("id", id).query(Long.class).single() > 0) {
            saw("coding");
        }
        if (jdbc.sql("SELECT count(*) FROM questions WHERE interview_id = :id AND kind = 'SYSTEM_DESIGN'").param("id", id).query(Long.class).single() > 0) {
            saw("design");
        }
        assertThat(stateOf(id)).isEqualTo("COMPLETED");
        assertThat(jdbc.sql("SELECT count(*) FROM answers WHERE interview_id = :id AND evaluation_status = 'PENDING'").param("id", id).query(Long.class).single())
                .as("no answer waits once the report is ready").isZero();
        long unassessable = jdbc.sql("SELECT count(*) FROM answers WHERE interview_id = :id AND evaluation_status = 'UNASSESSABLE'").param("id", id).query(Long.class).single();
        assertThat(reports.get(user, id).assessment().unassessableAnswers()).as("the report discloses exactly the answers that were given up")
                .hasSize((int) unassessable);

        ReportResponse report = reports.get(user, id);
        assertThat(report.status()).isEqualTo(ReportStatus.READY);
        if (report.overallScore() != null) {
            assertThat(report.overallScore()).isBetween(0.0, 100.0);
        }
        Double expected = invariants.expectedOverallScore(id);
        if (report.outcome() == com.techinterviewer.evaluation.domain.ReportOutcome.INCONCLUSIVE) {
            assertThat(report.overallScore()).as("an inconclusive report states no score").isNull();
            assertThat(report.recommendation()).isNull();
        } else if (expected == null) {
            assertThat(report.overallScore()).isNull();
        } else {
            assertThat(report.overallScore()).as("the report's score is the aggregation of the stored evaluations").isEqualTo(expected);
        }
        long skipped = jdbc.sql("SELECT count(*) FROM answers WHERE interview_id = :id AND skipped").param("id", id).query(Long.class).single();
        assertThat(report.coverage().skipped()).isEqualTo((int) skipped);
        assertThat(report.coverage().questionsAnswered()).as("answered, skipped ones excluded").isEqualTo((int) (count("answers", id) - skipped));
        report.sections().forEach(section -> assertThat(section.score()).isBetween(0.0, 100.0));
        JsonNode transcript = json.valueToTree(queries.transcript(user, id));
        assertThat(ForbiddenFields.find(transcript, true)).isEmpty();
        assertThat(invariants.log(id).getLast()).isEqualTo("FINAL_EVALUATION:COMPLETED:REPORT_READY");
    }

    private UUID create(UUID user, Case simulated, Random random) {
        boolean coding = simulated.type() == InterviewType.CODING || simulated.type() == InterviewType.FULL_LOOP;
        List<String> pool = new ArrayList<>(SKILLS);
        Collections.shuffle(pool, random);
        List<String> skills = pool.subList(0, 1 + random.nextInt(3));
        ConfigurationRequest configuration = new ConfigurationRequest(
                TargetRole.values()[random.nextInt(TargetRole.values().length)],
                simulated.experience(),
                simulated.difficulty(),
                simulated.minutes(),
                simulated.type(),
                skills,
                coding ? LANGUAGES.get(random.nextInt(LANGUAGES.size())) : null,
                false);
        try {
            return creation.create(user, new CreateInterviewRequest(null, configuration)).id();
        } catch (FieldValidationException noTopicFits) {
            // The planner refuses a technical interview when no topic of the chosen skills fits the difficulty.
            assertThat(simulated.type()).isIn(InterviewType.TECHNICAL, InterviewType.FULL_LOOP);
            return null;
        }
    }

    private interface Toggle {
        void apply(int remaining);
    }

    /** One step of an outage clock: a running outage gets shorter, otherwise one may start. */
    private static int outage(Random random, int remaining, int maxLength, int chancePercent, Toggle toggle) {
        int next = remaining > 0 ? remaining - 1 : (random.nextInt(100) < chancePercent ? 1 + random.nextInt(maxLength) : 0);
        toggle.apply(next);
        return next;
    }

    private static Quality randomQuality(Random random) {
        int roll = random.nextInt(100);
        if (roll < 15) {
            return Quality.EXCELLENT;
        }
        if (roll < 50) {
            return Quality.AVERAGE;
        }
        if (roll < 80) {
            return Quality.PARTIAL;
        }
        return Quality.INCORRECT;
    }

    private String codeLanguageOf(SessionResponse session) {
        return session.configuration().codingLanguage();
    }

    private AnswerRequest answerTo(QuestionResponse question, SessionResponse session, Random random) {
        String content = "Answer " + random.nextInt(1_000_000) + (random.nextInt(100) < 5 ? " " + "long ".repeat(1_000) : "");
        boolean code = question.answerFormat().name().equals("CODE");
        return new AnswerRequest(question.id(), content, code ? codeLanguageOf(session) : null, random.nextBoolean() ? null : random.nextInt(600));
    }

    private void leakCheck(Object response, String what) {
        assertThat(ForbiddenFields.find(json.valueToTree(response), false)).as("candidate-facing %s", what).isEmpty();
    }
}
