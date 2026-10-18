package com.techinterviewer.evaluation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.techinterviewer.evaluation.RubricRegistry;
import com.techinterviewer.evaluation.domain.Confidence;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.CriterionScore;
import com.techinterviewer.evaluation.domain.Evaluation;
import com.techinterviewer.evaluation.domain.EvaluationFlag;
import com.techinterviewer.evaluation.domain.Evaluator;
import com.techinterviewer.evaluation.domain.EvaluatorConfidence;
import com.techinterviewer.evaluation.domain.FactualError;
import com.techinterviewer.evaluation.domain.KeyPointStatus;
import com.techinterviewer.evaluation.support.Assessments;
import com.techinterviewer.evaluation.support.ReportTestBase;
import com.techinterviewer.evaluation.support.ScenarioBuilder;
import com.techinterviewer.evaluation.support.ScenarioBuilder.Asked;
import com.techinterviewer.interview.domain.CandidateAnswer;
import com.techinterviewer.interview.domain.EvaluationStatus;
import com.techinterviewer.interview.domain.InterviewQuestion;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.port.AnswerAssessment;
import com.techinterviewer.interview.port.CriterionRating;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;

/** Validation, scoring and idempotency of stored evaluations, against the real database. */
class EvaluationServiceIT extends ReportTestBase {

    @Autowired
    EvaluationService service;

    /** One answer per kind of question, all still pending. */
    private record Kinds(
            UUID interviewId, Asked intro, Asked technical, Asked technicalFollowUp, Asked coding, Asked codingFollowUp,
            Asked design, Asked designFollowUp) {
    }

    private Kinds allKinds() {
        ScenarioBuilder builder = newInterview();
        Asked intro = builder.intro("I build backends.");
        Asked technical = builder.technical("java/concurrency", 3, "volatile gives visibility.");
        Asked technicalFollowUp = builder.followUp(technical, Section.QUESTIONING, "java/concurrency", 3, "It does not give atomicity.");
        Asked coding = builder.coding(3, "int solve(int n) { return n; }");
        Asked codingFollowUp = builder.followUp(coding, Section.CODING, null, 3, "It is linear.");
        Asked design = builder.design(4, "Use a queue and workers.");
        Asked designFollowUp = builder.followUp(design, Section.SYSTEM_DESIGN, null, 4, "Add a dead letter queue.");
        ScenarioBuilder.Built built = builder.buildFinished();
        return new Kinds(built.interviewId(), intro, technical, technicalFollowUp, coding, codingFollowUp, design, designFollowUp);
    }

    private Evaluation record(Asked asked, AnswerAssessment assessment) {
        return inTransaction(() -> {
            CandidateAnswer answer = answers.findById(asked.answerId()).orElseThrow();
            InterviewQuestion question = questions.findById(asked.questionId()).orElseThrow();
            return service.record(answer, question, assessment);
        });
    }

    private EvaluationStatus statusOf(Asked asked) {
        return EvaluationStatus.valueOf(jdbc.queryForObject("select evaluation_status from answers where id = ?", String.class, asked.answerId()));
    }

    private int evaluationRows(Asked asked) {
        return count("evaluations", "answer_id", asked.answerId());
    }

    // ---- the evaluator kind follows from the question, the score from the ratings --------------------------------

    record KindCase(String name, Function<Kinds, Asked> question, Evaluator expected, Map<Criterion, Integer> ratings, String overall) {

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * The scores use the weights of the shipped skill profiles (application.yml): the technical questions are on
     * java/concurrency, the code is Java, and a design problem uses the system-design profile.
     */
    static Stream<KindCase> kindCases() {
        return Stream.of(
                new KindCase("introduction", Kinds::intro, Evaluator.INTRO, Map.of(Criterion.COMMUNICATION, 3), "75.00"),
                // java: 0.35*4 + 0.10*3 + 0.25*2 + 0.15*2 + 0.10*3 + 0.05*1 = 2.85 of 4
                new KindCase("technical question", Kinds::technical, Evaluator.TECHNICAL, technicalRatings(), "71.25"),
                new KindCase("follow-up in the questioning section", Kinds::technicalFollowUp, Evaluator.TECHNICAL, technicalRatings(), "71.25"),
                // java coding, an untested solution (no problem from the bank), so correctness is capped at 3 (NO_TEST_EVIDENCE):
                // 0.35*3 + 0.15*3 + 0.15*2 + 0.05*1 + 0.10*2 + 0.08*1 + 0.12*0 = 2.13 of 4
                new KindCase("coding problem", Kinds::coding, Evaluator.CODING, codingRatings(), "53.25"),
                // a follow-up is a discussion, not a solution: not capped. 0.35*4 + ... = 2.48 of 4
                new KindCase("follow-up in the coding section", Kinds::codingFollowUp, Evaluator.CODING, codingRatings(), "62.00"),
                // system-design.v2 (every rating rests on a quote): 0.10*4 + 0.12*3 + 0.06*2 + 0.10*2 + 0.07*1 + 0.06*0 + 0.12*1
                // + 0.07*3 + 0.07*2 + 0.07*2 + 0.05*4 + 0.04*0 + 0.07*3 = 2.17 of 4
                new KindCase("system design", Kinds::design, Evaluator.SYSTEM_DESIGN, designRatings(), "54.25"),
                new KindCase("follow-up in the system design section", Kinds::designFollowUp, Evaluator.SYSTEM_DESIGN, designRatings(), "54.25"));
    }

    private static Map<Criterion, Integer> technicalRatings() {
        Map<Criterion, Integer> ratings = new EnumMap<>(Criterion.class);
        ratings.put(Criterion.CORRECTNESS, 4);
        ratings.put(Criterion.COMPLETENESS, 3);
        ratings.put(Criterion.DEPTH, 2);
        ratings.put(Criterion.PRACTICAL_UNDERSTANDING, 2);
        ratings.put(Criterion.REASONING, 3);
        ratings.put(Criterion.COMMUNICATION, 1);
        return ratings;
    }

    private static Map<Criterion, Integer> codingRatings() {
        Map<Criterion, Integer> ratings = new EnumMap<>(Criterion.class);
        ratings.put(Criterion.CORRECTNESS, 4);
        ratings.put(Criterion.ALGORITHM, 3);
        ratings.put(Criterion.TIME_COMPLEXITY, 2);
        ratings.put(Criterion.SPACE_COMPLEXITY, 1);
        ratings.put(Criterion.EDGE_CASES, 2);
        ratings.put(Criterion.READABILITY, 1);
        ratings.put(Criterion.MAINTAINABILITY, 0);
        return ratings;
    }

    private static Map<Criterion, Integer> designRatings() {
        Map<Criterion, Integer> ratings = new EnumMap<>(Criterion.class);
        ratings.put(Criterion.REQUIREMENTS, 4);
        ratings.put(Criterion.HIGH_LEVEL_DESIGN, 3);
        ratings.put(Criterion.API_DESIGN, 2);
        ratings.put(Criterion.DATA_MODEL, 2);
        ratings.put(Criterion.CACHING, 1);
        ratings.put(Criterion.MESSAGING, 0);
        ratings.put(Criterion.SCALABILITY, 1);
        ratings.put(Criterion.AVAILABILITY, 3);
        ratings.put(Criterion.RELIABILITY, 2);
        ratings.put(Criterion.FAILURE_HANDLING, 2);
        ratings.put(Criterion.SECURITY, 4);
        ratings.put(Criterion.OBSERVABILITY, 0);
        ratings.put(Criterion.TRADE_OFFS, 3);
        return ratings;
    }

    private static AnswerAssessment assessmentOf(KindCase kind, Asked asked) {
        Map<Criterion, CriterionRating> ratings = new EnumMap<>(Criterion.class);
        kind.ratings().forEach((criterion, rating) -> ratings.put(criterion, new CriterionRating(rating, "why " + criterion,
                kind.expected() == Evaluator.SYSTEM_DESIGN && rating > 0 ? List.of("a quote") : List.of())));
        return Assessments.builder(kind.expected())
                .ratings(ratings)
                .keyPointsCovered(asked.expectedKeyPoints())
                .claims(List.of("Led a migration"))
                .summary("A summary.")
                .flags(EvaluationFlag.LANGUAGE_MISMATCH)
                .model("model-x")
                .build();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("kindCases")
    void everyKindOfQuestionIsJudgedByItsOwnRubricAndScoredFromTheRatings(KindCase kind) {
        Kinds kinds = allKinds();
        Asked asked = kind.question().apply(kinds);

        Evaluation stored = record(asked, assessmentOf(kind, asked));

        Evaluation reloaded = inTransaction(() -> evaluations.findByAnswerId(asked.answerId()).orElseThrow());
        assertThat(reloaded.getId()).isEqualTo(stored.getId());
        assertThat(reloaded.getEvaluator()).isEqualTo(kind.expected());
        assertThat(reloaded.getOverallScore()).isEqualByComparingTo(new BigDecimal(kind.overall()));
        assertThat(reloaded.getCriterionScores()).extracting(CriterionScore::criterion)
                .containsExactlyInAnyOrderElementsOf(kind.ratings().keySet());
        reloaded.getCriterionScores().forEach(score -> {
            // A rating a rule lowered keeps the model's own in modelRating: only an untested coding solution's correctness here.
            int given = score.capped() ? score.modelRating() : score.rating();
            assertThat(given).isEqualTo(kind.ratings().get(score.criterion()));
            assertThat(score.capped()).isEqualTo(kind.name().equals("coding problem") && score.criterion() == Criterion.CORRECTNESS);
            assertThat(score.rationale()).isEqualTo("why " + score.criterion());
        });
        assertThat(reloaded.getCriterionScores().stream().mapToDouble(CriterionScore::weight).sum()).isCloseTo(1.0, within(1e-9));
        assertThat(reloaded.getKeyPoints()).hasSize(asked.expectedKeyPoints().size());
        assertThat(reloaded.getCandidateClaims()).containsExactly("Led a migration");
        assertThat(reloaded.getSummary()).isEqualTo("A summary.");
        assertThat(reloaded.getFlags()).containsExactly(EvaluationFlag.LANGUAGE_MISMATCH);
        assertThat(reloaded.getPromptId()).isEqualTo("evaluate-answer");
        assertThat(reloaded.getPromptVersion()).isEqualTo("1.0");
        assertThat(reloaded.getModel()).isEqualTo("model-x");
        assertThat(statusOf(asked)).isEqualTo(EvaluationStatus.COMPLETED);
    }

    @Test
    void theWeightsOfTheQuestionsSkillProfileNotTheEvaluatorDecideTheScore() {
        Kinds kinds = allKinds();
        Asked technical = kinds.technical();
        Map<Criterion, CriterionRating> ratings = new EnumMap<>(Criterion.class);
        RubricRegistry.criteria(Evaluator.TECHNICAL).forEach(criterion -> ratings.put(criterion, new CriterionRating(0, "")));
        ratings.put(Criterion.CORRECTNESS, new CriterionRating(4, ""));

        Evaluation stored = record(technical, Assessments.builder(Evaluator.TECHNICAL).ratings(ratings).build());

        assertThat(stored.getOverallScore()).as("java weighs correctness 0.35 (the base rubric 0.30)").isEqualByComparingTo("35.00");
        assertThat(stored.getCriterionScores()).filteredOn(score -> score.criterion() == Criterion.CORRECTNESS)
                .singleElement().extracting(CriterionScore::weight).isEqualTo(0.35);
    }

    // ---- the backend decides: ratings that contradict the findings, and the evaluator's confidence -----------------

    @Test
    void aRatingThatContradictsTheModelsOwnFindingsIsStoredLoweredNextToTheModelsRating() {
        Kinds kinds = allKinds();
        Asked technical = kinds.technical();
        AnswerAssessment generous = Assessments.builder(Evaluator.TECHNICAL).rating(4)
                .keyPoint(technical.expectedKeyPoints().getFirst().id(), KeyPointStatus.MISSING)
                .errors(new FactualError("volatile makes count++ atomic", "it does not", List.of()),
                        new FactualError("volatile locks the field", "it does not lock", List.of()))
                .build();

        Evaluation stored = record(technical, generous);

        CriterionScore correctness = scoreOf(stored, Criterion.CORRECTNESS);
        assertThat(correctness.rating()).isEqualTo(2);
        assertThat(correctness.modelRating()).isEqualTo(4);
        assertThat(correctness.cappedBy()).isEqualTo("FACTUAL_ERRORS");
        assertThat(scoreOf(stored, Criterion.COMPLETENESS).cappedBy()).isEqualTo("KEY_POINT_COVERAGE");
        assertThat(scoreOf(stored, Criterion.DEPTH).capped()).isFalse();
        assertThat(stored.getOverallScore()).isLessThan(new BigDecimal("100"));
        String json = jdbc.queryForObject("select criterion_scores::text from evaluations where id = ?", String.class, stored.getId());
        assertThat(json).contains("\"modelRating\": 4", "\"cappedBy\": \"FACTUAL_ERRORS\"");
    }

    private static CriterionScore scoreOf(Evaluation evaluation, Criterion criterion) {
        return evaluation.getCriterionScores().stream().filter(score -> score.criterion() == criterion).findFirst().orElseThrow();
    }

    @Test
    void theEvaluatorsConfidenceIsStoredAndALowOneMarksTheAnswerForReviewWithoutChangingTheScore() {
        Kinds kinds = allKinds();
        AnswerAssessment sure = Assessments.builder(Evaluator.TECHNICAL).keyPointsCovered(kinds.technical().expectedKeyPoints())
                .confidence(Confidence.HIGH, "Clear and complete.").build();
        AnswerAssessment unsure = Assessments.builder(Evaluator.TECHNICAL).keyPointsCovered(kinds.technicalFollowUp().expectedKeyPoints())
                .confidence(Confidence.LOW, "The answer is cut off.").build();

        Evaluation high = record(kinds.technical(), sure);
        Evaluation low = record(kinds.technicalFollowUp(), unsure);

        assertThat(inTransaction(() -> evaluations.findById(high.getId()).orElseThrow().getEvaluatorConfidence()))
                .isEqualTo(new EvaluatorConfidence(Confidence.HIGH, "Clear and complete."));
        assertThat(jdbc.queryForObject("select evaluator_confidence from evaluations where id = ?", String.class, low.getId())).isEqualTo("LOW");
        assertThat(low.getFlags()).containsExactly(EvaluationFlag.LOW_EVALUATOR_CONFIDENCE);
        assertThat(low.getOverallScore()).isEqualByComparingTo(high.getOverallScore());
        Evaluation intro = record(kinds.intro(), Assessments.builder(Evaluator.INTRO).build());
        assertThat(jdbc.queryForObject("select evaluator_confidence_reason from evaluations where id = ?", String.class, intro.getId()))
                .as("the introduction states none").isNull();
    }

    // ---- an assessment that does not fit its question is never stored ---------------------------------------------

    record InvalidCase(String name, Function<Kinds, Asked> question, Function<Asked, AnswerAssessment> assessment, String message) {

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<InvalidCase> invalidCases() {
        return Stream.of(
                new InvalidCase("a criterion of the rubric is missing", Kinds::technical,
                        asked -> Assessments.builder(Evaluator.TECHNICAL).without(Criterion.DEPTH).build(), "DEPTH"),
                new InvalidCase("a criterion that is not in the rubric is rated", Kinds::technical,
                        asked -> Assessments.builder(Evaluator.TECHNICAL).rate(Criterion.DATA_MODEL, 2).build(), "DATA_MODEL"),
                new InvalidCase("the technical criteria rate a coding problem", Kinds::coding,
                        asked -> Assessments.builder(Evaluator.TECHNICAL).build(), "TIME_COMPLEXITY"),
                new InvalidCase("the coding criteria rate a technical question", Kinds::technical,
                        asked -> Assessments.builder(Evaluator.CODING).build(), "MAINTAINABILITY"),
                new InvalidCase("the design criteria rate a follow-up of the questioning section", Kinds::technicalFollowUp,
                        asked -> Assessments.builder(Evaluator.SYSTEM_DESIGN).build(), "REQUIREMENTS"),
                new InvalidCase("the technical criteria rate the introduction", Kinds::intro,
                        asked -> Assessments.builder(Evaluator.TECHNICAL).build(), "CORRECTNESS"),
                new InvalidCase("the introduction rating rates a design", Kinds::design,
                        asked -> Assessments.builder(Evaluator.INTRO).build(), "HIGH_LEVEL_DESIGN"),
                new InvalidCase("a key point the question does not have is cited", Kinds::technical,
                        asked -> Assessments.builder(Evaluator.TECHNICAL).keyPoint("kp99", KeyPointStatus.COVERED).build(), "kp99"),
                new InvalidCase("a key point is cited for the introduction, which has none", Kinds::intro,
                        asked -> Assessments.builder(Evaluator.INTRO).keyPoint("kp1", KeyPointStatus.COVERED).build(), "kp1"),
                new InvalidCase("more claims than are accepted", Kinds::technical,
                        asked -> Assessments.builder(Evaluator.TECHNICAL).claims(claims(EvaluationService.MAX_CANDIDATE_CLAIMS + 1)).build(),
                        "candidate claims"),
                new InvalidCase("more factual errors than are accepted", Kinds::technical,
                        asked -> withFactualErrors(EvaluationService.MAX_FACTUAL_ERRORS + 1), "factual errors"));
    }

    private static List<String> claims(int count) {
        List<String> claims = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            claims.add("claim " + i);
        }
        return claims;
    }

    private static AnswerAssessment withFactualErrors(int count) {
        AnswerAssessment base = Assessments.builder(Evaluator.TECHNICAL).build();
        List<FactualError> errors = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            errors.add(new FactualError("claim " + i, "correction " + i, List.of()));
        }
        return new AnswerAssessment(base.ratings(), base.keyPoints(), errors, base.candidateClaims(), base.summary(), base.flags(),
                base.promptId(), base.promptVersion(), base.model(), null);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCases")
    void anAssessmentThatDoesNotFitTheQuestionIsRejectedAndNothingIsStored(InvalidCase invalid) {
        Kinds kinds = allKinds();
        Asked asked = invalid.question().apply(kinds);

        assertThatThrownBy(() -> record(asked, invalid.assessment().apply(asked)))
                .isInstanceOf(InvalidAssessmentException.class)
                .hasMessageContaining(invalid.message());

        assertThat(evaluationRows(asked)).isZero();
        assertThat(statusOf(asked)).as("the answer stays pending").isEqualTo(EvaluationStatus.PENDING);
    }

    @Test
    void exactlyTheMostClaimsAndFactualErrorsThatAreAcceptedAreStored() {
        Asked claims = allKinds().technical();
        Asked errors = allKinds().technical();

        Evaluation withClaims = record(claims,
                Assessments.builder(Evaluator.TECHNICAL).claims(claims(EvaluationService.MAX_CANDIDATE_CLAIMS)).build());
        Evaluation withErrors = record(errors, withFactualErrors(EvaluationService.MAX_FACTUAL_ERRORS));

        assertThat(withClaims.getCandidateClaims()).hasSize(EvaluationService.MAX_CANDIDATE_CLAIMS);
        assertThat(withErrors.getFactualErrors()).hasSize(EvaluationService.MAX_FACTUAL_ERRORS);
    }

    @Test
    void aRatingOutsideZeroToFourCannotEvenBeBuilt() {
        assertThatThrownBy(() -> new CriterionRating(5, "too high")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CriterionRating(-1, "too low")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anAnswerThatBelongsToAnotherQuestionOrWasSkippedIsAProgrammingError() {
        ScenarioBuilder builder = newInterview();
        Asked first = builder.technical("java/concurrency", 3, "an answer");
        Asked second = builder.technical("java/generics", 3, "another answer");
        Asked skipped = builder.technical("java/exceptions", 2, null);
        builder.buildFinished();
        AnswerAssessment assessment = Assessments.builder(Evaluator.TECHNICAL).build();

        assertThatThrownBy(() -> inTransaction(() -> service.record(
                answers.findById(first.answerId()).orElseThrow(), questions.findById(second.questionId()).orElseThrow(), assessment)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not an answer to question");
        assertThatThrownBy(() -> record(skipped, assessment))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("skipped");
        assertThat(count("evaluations")).isZero();
    }

    // ---- idempotency -------------------------------------------------------------------------------------------

    @Test
    void recordingTheSameAssessmentAgainReturnsTheStoredEvaluationAndWritesNothing() {
        Asked technical = allKinds().technical();
        AnswerAssessment assessment = Assessments.uniform(Evaluator.TECHNICAL, 3, technical.expectedKeyPoints(), EvaluationFlag.OFF_TOPIC);

        Evaluation first = record(technical, assessment);
        Evaluation second = record(technical, assessment);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(evaluationRows(technical)).isEqualTo(1);
        assertThat(statusOf(technical)).isEqualTo(EvaluationStatus.COMPLETED);
    }

    @Test
    void aDifferentAssessmentForAnEvaluatedAnswerIsRefusedAndTheStoredOneIsKept() {
        Asked technical = allKinds().technical();
        Evaluation first = record(technical, Assessments.uniform(Evaluator.TECHNICAL, 3, technical.expectedKeyPoints()));

        assertThatThrownBy(() -> record(technical, Assessments.uniform(Evaluator.TECHNICAL, 4, technical.expectedKeyPoints())))
                .isInstanceOf(AnswerAlreadyEvaluatedException.class);
        assertThatThrownBy(() -> record(technical, Assessments.uniform(Evaluator.TECHNICAL, 3, technical.expectedKeyPoints(), EvaluationFlag.OFF_TOPIC)))
                .isInstanceOf(AnswerAlreadyEvaluatedException.class);
        assertThatThrownBy(() -> record(technical, Assessments.builder(Evaluator.TECHNICAL).keyPointsCovered(technical.expectedKeyPoints()).summary("Another").build()))
                .isInstanceOf(AnswerAlreadyEvaluatedException.class);
        assertThatThrownBy(() -> record(technical, Assessments.builder(Evaluator.TECHNICAL).keyPointsCovered(technical.expectedKeyPoints()).model("other-model").build()))
                .isInstanceOf(AnswerAlreadyEvaluatedException.class);
        assertThatThrownBy(() -> record(technical, Assessments.builder(Evaluator.TECHNICAL)
                .keyPoint("kp1", KeyPointStatus.PARTIAL).keyPoint("kp2", KeyPointStatus.COVERED).build()))
                .isInstanceOf(AnswerAlreadyEvaluatedException.class);

        assertThat(evaluationRows(technical)).isEqualTo(1);
        Evaluation kept = inTransaction(() -> evaluations.findByAnswerId(technical.answerId()).orElseThrow());
        assertThat(kept.getId()).isEqualTo(first.getId());
        assertThat(kept.getOverallScore()).isEqualByComparingTo("75.00");
    }

    @Test
    void anInvalidAssessmentIsRejectedEvenWhenTheAnswerHasAnEvaluationAlready() {
        Asked technical = allKinds().technical();
        record(technical, Assessments.uniform(Evaluator.TECHNICAL, 3, technical.expectedKeyPoints()));

        assertThatThrownBy(() -> record(technical, Assessments.builder(Evaluator.CODING).build()))
                .isInstanceOf(InvalidAssessmentException.class);
    }

    // ---- transactions -----------------------------------------------------------------------------------------

    @Test
    void recordingWithoutATransactionFailsLoudlyAndStoresNothing() {
        Asked technical = allKinds().technical();
        CandidateAnswer answer = answers.findById(technical.answerId()).orElseThrow();
        InterviewQuestion question = questions.findById(technical.questionId()).orElseThrow();

        assertThatThrownBy(() -> service.record(answer, question, Assessments.uniform(Evaluator.TECHNICAL, 3, technical.expectedKeyPoints())))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(evaluationRows(technical)).isZero();
    }

    @Test
    void anEvaluationRecordedInTheCallersTransactionIsRolledBackWithIt() {
        Asked technical = allKinds().technical();

        assertThatThrownBy(() -> inTransaction(() -> {
            service.record(answers.findById(technical.answerId()).orElseThrow(), questions.findById(technical.questionId()).orElseThrow(),
                    Assessments.uniform(Evaluator.TECHNICAL, 3, technical.expectedKeyPoints()));
            throw new IllegalStateException("the turn fails later");
        })).hasMessage("the turn fails later");

        assertThat(evaluationRows(technical)).isZero();
        assertThat(statusOf(technical)).isEqualTo(EvaluationStatus.PENDING);
    }

    @Test
    void aRefusedAssessmentDoesNotPoisonTheCallersTransactionSoTheRestOfTheTurnStillCommits() {
        ScenarioBuilder builder = newInterview();
        Asked bad = builder.technical("java/concurrency", 3, "an answer");
        Asked good = builder.technical("java/generics", 3, "another answer");
        Asked evaluated = builder.technical("java/exceptions", 2, "a third answer");
        builder.buildFinished();
        record(evaluated, Assessments.uniform(Evaluator.TECHNICAL, 3, evaluated.expectedKeyPoints()));

        inTransaction(() -> {
            assertThatThrownBy(() -> service.record(answers.findById(bad.answerId()).orElseThrow(),
                    questions.findById(bad.questionId()).orElseThrow(), Assessments.builder(Evaluator.CODING).build()))
                    .isInstanceOf(InvalidAssessmentException.class);
            assertThatThrownBy(() -> service.record(answers.findById(evaluated.answerId()).orElseThrow(),
                    questions.findById(evaluated.questionId()).orElseThrow(), Assessments.uniform(Evaluator.TECHNICAL, 4, evaluated.expectedKeyPoints())))
                    .isInstanceOf(AnswerAlreadyEvaluatedException.class);
            service.record(answers.findById(good.answerId()).orElseThrow(), questions.findById(good.questionId()).orElseThrow(),
                    Assessments.uniform(Evaluator.TECHNICAL, 2, good.expectedKeyPoints()));
        });

        assertThat(evaluationRows(bad)).isZero();
        assertThat(statusOf(bad)).isEqualTo(EvaluationStatus.PENDING);
        assertThat(evaluationRows(good)).isEqualTo(1);
        assertThat(statusOf(good)).isEqualTo(EvaluationStatus.COMPLETED);
    }

    @Test
    void evaluatingPendingAnswersInsideATransactionIsRefusedBecauseThePortMustNeverRunInOne() {
        Kinds kinds = allKinds();

        assertThatThrownBy(() -> inTransaction(() -> service.evaluatePending(kinds.interviewId())))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(evaluator.calls()).isZero();
    }
}
