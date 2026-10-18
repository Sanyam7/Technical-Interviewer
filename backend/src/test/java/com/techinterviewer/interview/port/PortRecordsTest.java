package com.techinterviewer.interview.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.evaluation.domain.Confidence;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.EvaluationFlag;
import com.techinterviewer.evaluation.domain.Evaluator;
import com.techinterviewer.evaluation.domain.KeyPointAssessment;
import com.techinterviewer.evaluation.domain.KeyPointStatus;
import com.techinterviewer.evaluation.domain.Recommendation;
import com.techinterviewer.interview.domain.ExpectedKeyPoint;
import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.FollowUpMode;
import com.techinterviewer.interview.domain.InterviewType;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.QuestionOrigin;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.TargetRole;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** What the AI layer may and may not hand over: impossible requests and results cannot be constructed. */
class PortRecordsTest {

    private static final UUID INTERVIEW = UUID.randomUUID();
    private static final TopicRef TOPIC = new TopicRef("java", "Java", "concurrency", "Concurrency");
    private static final ExpectedKeyPoint KEY_POINT = new ExpectedKeyPoint("kp1", "volatile gives visibility", List.of());

    private static QuestionRequest request(Section section, QuestionKind kind, TopicRef topic, String language, FollowUpTarget followUp) {
        return new QuestionRequest(INTERVIEW, section, kind, topic, 3, TargetRole.BACKEND, ExperienceLevel.MID,
                language, List.of("Java"), List.of(new AskedQuestion("java/jvm", "What is a classloader?")), List.of("Used Kafka"), followUp);
    }

    private static FollowUpTarget gapTarget() {
        return new FollowUpTarget(FollowUpMode.PROBE_GAP, "Explain volatile", "It is fast", KEY_POINT, null);
    }

    // ---- QuestionRequest -----------------------------------------------------------------------------------------------

    @Test
    void aTechnicalRequestNeedsATopicAndTheQuestioningSection() {
        QuestionRequest ok = request(Section.QUESTIONING, QuestionKind.TECHNICAL, TOPIC, null, null);

        assertThat(ok.topic().key()).isEqualTo("java/concurrency");
        assertThatThrownBy(() -> request(Section.QUESTIONING, QuestionKind.TECHNICAL, null, null, null)).hasMessageContaining("topic");
        assertThatThrownBy(() -> request(Section.CODING, QuestionKind.TECHNICAL, TOPIC, "JAVA", null)).hasMessageContaining("QUESTIONING");
    }

    @Test
    void aCodingRequestNeedsALanguageButNoTopic() {
        assertThat(request(Section.CODING, QuestionKind.CODING, null, "JAVA", null).codingLanguage()).isEqualTo("JAVA");
        assertThatThrownBy(() -> request(Section.CODING, QuestionKind.CODING, null, null, null)).hasMessageContaining("codingLanguage");
        assertThatThrownBy(() -> request(Section.QUESTIONING, QuestionKind.CODING, null, "JAVA", null)).hasMessageContaining("CODING");
    }

    @Test
    void aSystemDesignRequestBelongsToTheDesignSection() {
        assertThat(request(Section.SYSTEM_DESIGN, QuestionKind.SYSTEM_DESIGN, null, null, null).kind()).isEqualTo(QuestionKind.SYSTEM_DESIGN);
        assertThatThrownBy(() -> request(Section.CODING, QuestionKind.SYSTEM_DESIGN, null, "JAVA", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theIntroductionIsNeverGenerated() {
        assertThatThrownBy(() -> request(Section.INTRODUCTION, QuestionKind.INTRO, null, null, null)).hasMessageContaining("static");
    }

    @Test
    void aFollowUpRequestCarriesItsTargetAndOnlyAFollowUpDoes() {
        QuestionRequest followUp = request(Section.QUESTIONING, QuestionKind.FOLLOW_UP, TOPIC, null, gapTarget());

        assertThat(followUp.followUp().targetKeyPoint()).isEqualTo(KEY_POINT);
        assertThatThrownBy(() -> request(Section.QUESTIONING, QuestionKind.FOLLOW_UP, TOPIC, null, null)).hasMessageContaining("followUp");
        assertThatThrownBy(() -> request(Section.QUESTIONING, QuestionKind.TECHNICAL, TOPIC, null, gapTarget())).hasMessageContaining("followUp");
        assertThatThrownBy(() -> request(Section.INTRODUCTION, QuestionKind.FOLLOW_UP, TOPIC, null, gapTarget())).hasMessageContaining("introduction");
        // A follow-up inside the coding section still needs the language.
        assertThatThrownBy(() -> request(Section.CODING, QuestionKind.FOLLOW_UP, null, null, gapTarget())).hasMessageContaining("codingLanguage");
        assertThat(request(Section.CODING, QuestionKind.FOLLOW_UP, null, "JAVA", gapTarget()).codingLanguage()).isEqualTo("JAVA");
    }

    @ParameterizedTest(name = "difficulty {0} valid={1}")
    @CsvSource({"1, true", "5, true", "0, false", "6, false", "-3, false"})
    void targetDifficultyIsOneToFive(int difficulty, boolean valid) {
        Supplier<QuestionRequest> build = () -> new QuestionRequest(INTERVIEW, Section.QUESTIONING, QuestionKind.TECHNICAL, TOPIC,
                difficulty, TargetRole.BACKEND, ExperienceLevel.MID, null, null, null, null, null);
        if (valid) {
            assertThat(build.get().targetDifficulty()).isEqualTo(difficulty);
        } else {
            assertThatThrownBy(build::get).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void requestListsAreCopiedAndBounded() {
        List<AskedQuestion> asked = new ArrayList<>(List.of(new AskedQuestion(null, "q1")));
        QuestionRequest request = new QuestionRequest(INTERVIEW, Section.QUESTIONING, QuestionKind.TECHNICAL, TOPIC, 3,
                TargetRole.BACKEND, ExperienceLevel.MID, null, null, asked, null, null);
        asked.clear();

        assertThat(request.askedQuestions()).hasSize(1);
        assertThat(request.focusSkills()).isEmpty();
        List<AskedQuestion> tooMany = java.util.Collections.nCopies(101, new AskedQuestion(null, "q"));
        assertThatThrownBy(() -> new QuestionRequest(INTERVIEW, Section.QUESTIONING, QuestionKind.TECHNICAL, TOPIC, 3,
                TargetRole.BACKEND, ExperienceLevel.MID, null, null, tooMany, null, null)).hasMessageContaining("askedQuestions");
    }

    @Test
    void followUpTargetsNeedTheirMatchingTarget() {
        assertThat(new FollowUpTarget(FollowUpMode.PROBE_CLAIM, "q", "a", null, "Built X").targetClaim()).isEqualTo("Built X");
        assertThat(new FollowUpTarget(FollowUpMode.DEEPEN, "q", "a", null, null).mode()).isEqualTo(FollowUpMode.DEEPEN);
        assertThatThrownBy(() -> new FollowUpTarget(FollowUpMode.PROBE_GAP, "q", "a", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FollowUpTarget(FollowUpMode.PROBE_CLAIM, "q", "a", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FollowUpTarget(FollowUpMode.DEEPEN, "q", "a", KEY_POINT, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FollowUpTarget(FollowUpMode.DEEPEN, "q", "a", null, "claim")).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- GeneratedQuestion -----------------------------------------------------------------------------------------------

    private static GeneratedQuestion generated(QuestionOrigin origin, String promptId, String version, String model, List<ExpectedKeyPoint> keyPoints) {
        return new GeneratedQuestion("Explain the difference between volatile and synchronized.", keyPoints, null, origin, 3,
                "java/concurrency", 5, List.of(), promptId, version, model, null);
    }

    @Test
    void aGeneratedQuestionNeedsItsProvenance() {
        assertThat(generated(QuestionOrigin.GENERATED, "question-v1", "1", "gemini-x", List.of(KEY_POINT)).origin()).isEqualTo(QuestionOrigin.GENERATED);
        assertThatThrownBy(() -> generated(QuestionOrigin.GENERATED, null, "1", "m", List.of())).hasMessageContaining("promptId");
        assertThatThrownBy(() -> generated(QuestionOrigin.GENERATED, "p", null, "m", List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generated(QuestionOrigin.GENERATED, "p", "1", null, List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anExemplarFallbackNeedsNoModel() {
        assertThat(generated(QuestionOrigin.EXEMPLAR_FALLBACK, null, null, null, List.of(KEY_POINT)).model()).isNull();
    }

    @Test
    void aGeneratorCannotClaimTheStaticOrigin() {
        assertThatThrownBy(() -> generated(QuestionOrigin.STATIC, "p", "1", "m", List.of())).hasMessageContaining("STATIC");
    }

    @Test
    void keyPointIdsInAQuestionAreUnique() {
        assertThatThrownBy(() -> generated(QuestionOrigin.GENERATED, "p", "1", "m", List.of(KEY_POINT, KEY_POINT)))
                .hasMessageContaining("duplicate");
        List<ExpectedKeyPoint> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add(new ExpectedKeyPoint("kp" + i, "t", List.of()));
        }
        assertThatThrownBy(() -> generated(QuestionOrigin.GENERATED, "p", "1", "m", eleven)).hasMessageContaining("expectedKeyPoints");
    }

    @ParameterizedTest(name = "minutes {0} difficulty {1} valid={2}")
    @CsvSource({"1, 1, true", "60, 5, true", "0, 3, false", "61, 3, false", "5, 0, false", "5, 6, false"})
    void minutesAndDifficultyAreBounded(int minutes, int difficulty, boolean valid) {
        Supplier<GeneratedQuestion> build = () -> new GeneratedQuestion("q", List.of(), null, QuestionOrigin.EXEMPLAR_FALLBACK,
                difficulty, null, minutes, List.of(), null, null, null, null);
        if (valid) {
            assertThat(build.get().estimatedMinutes()).isEqualTo(minutes);
        } else {
            assertThatThrownBy(build::get).isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ---- AnswerAssessment ----------------------------------------------------------------------------------------------------

    private static AnswerAssessment assessment(Map<Criterion, CriterionRating> ratings, List<KeyPointAssessment> keyPoints, String summary) {
        return new AnswerAssessment(ratings, keyPoints, List.of(), List.of("Used Kafka"), summary, java.util.Set.of(EvaluationFlag.OFF_TOPIC),
                "evaluator-v1", "3", "gemini-x", UUID.randomUUID());
    }

    @Test
    void anAssessmentCarriesRatingsButNoScore() {
        AnswerAssessment assessment = assessment(Map.of(Criterion.CORRECTNESS, new CriterionRating(3, "ok")), List.of(), "Solid answer.");

        assertThat(assessment.ratings().get(Criterion.CORRECTNESS).rating()).isEqualTo(3);
        assertThat(assessment.flags()).containsExactly(EvaluationFlag.OFF_TOPIC);
        assertThat(java.util.Arrays.stream(AnswerAssessment.class.getRecordComponents()).map(c -> c.getName()))
                .as("scores are computed by the server, never supplied by the evaluator")
                .noneMatch(name -> name.toLowerCase().contains("score"));
    }

    @Test
    void ratingsMustExistAndBeBetweenZeroAndFour() {
        assertThatThrownBy(() -> assessment(Map.of(), List.of(), "s")).hasMessageContaining("ratings");
        assertThatThrownBy(() -> new CriterionRating(5, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CriterionRating(-1, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new CriterionRating(0, "").rating()).isZero();
        assertThat(new CriterionRating(4, "x").rating()).isEqualTo(4);
    }

    @Test
    void keyPointAssessmentsAreUniquePerKeyPointAndTheSummaryIsOneNonBlankLine() {
        Map<Criterion, CriterionRating> ratings = new EnumMap<>(Map.of(Criterion.DEPTH, new CriterionRating(2, "")));
        KeyPointAssessment covered = new KeyPointAssessment("kp1", KeyPointStatus.COVERED, "q", true);

        assertThatThrownBy(() -> assessment(ratings, List.of(covered, covered), "s")).hasMessageContaining("duplicate");
        assertThatThrownBy(() -> assessment(ratings, List.of(), " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> assessment(ratings, List.of(), "x".repeat(501))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void assessmentProvenanceIsRequiredAndBoundedByTheColumns() {
        Map<Criterion, CriterionRating> ratings = Map.of(Criterion.DEPTH, new CriterionRating(2, ""));

        assertThatThrownBy(() -> new AnswerAssessment(ratings, null, null, null, "s", null, " ", "1", "m", null)).hasMessageContaining("promptId");
        assertThatThrownBy(() -> new AnswerAssessment(ratings, null, null, null, "s", null, "p", "v".repeat(21), "m", null)).hasMessageContaining("promptVersion");
        assertThatThrownBy(() -> new AnswerAssessment(ratings, null, null, null, "s", null, "p", "1", "m".repeat(81), null)).hasMessageContaining("model");
        assertThat(new AnswerAssessment(ratings, null, null, null, "s", null, "p".repeat(60), "1", "m", null).flags()).isEmpty();
    }

    // ---- EvaluationRequest ---------------------------------------------------------------------------------------------------------

    private static EvaluationRequest evaluation(Evaluator evaluator, String answer, String language) {
        return new EvaluationRequest(INTERVIEW, UUID.randomUUID(), evaluator, TOPIC, 3, "Explain volatile", List.of(KEY_POINT), null,
                answer, language, List.of(new ThreadTurn("earlier question", "earlier answer", false)), "");
    }

    @Test
    void anEvaluationRequestNeedsAnAnswerAndCodingNeedsItsLanguage() {
        assertThat(evaluation(Evaluator.TECHNICAL, "volatile is about visibility", null).evaluator()).isEqualTo(Evaluator.TECHNICAL);
        assertThat(evaluation(Evaluator.CODING, "int x;", "JAVA").codingLanguage()).isEqualTo("JAVA");
        assertThatThrownBy(() -> evaluation(Evaluator.CODING, "int x;", null)).hasMessageContaining("codingLanguage");
        assertThatThrownBy(() -> evaluation(Evaluator.TECHNICAL, " ", null)).hasMessageContaining("answerText");
        assertThatThrownBy(() -> evaluation(Evaluator.TECHNICAL, "x".repeat(20_001), null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(evaluation(Evaluator.TECHNICAL, "x".repeat(20_000), null).answerText()).hasSize(20_000);
    }

    @Test
    void anEvaluationRequestKeepsTheEarlierTurnsOfItsThread() {
        EvaluationRequest request = evaluation(Evaluator.SYSTEM_DESIGN, "My design", null);

        assertThat(request.thread()).extracting(ThreadTurn::answerText).containsExactly("earlier answer");
        assertThatThrownBy(() -> new EvaluationRequest(INTERVIEW, UUID.randomUUID(), Evaluator.SYSTEM_DESIGN, null, 3, "q", null, null, "a",
                null, java.util.Collections.nCopies(11, new ThreadTurn("q", "a", false)), "")).hasMessageContaining("thread");
    }

    // ---- narrative ---------------------------------------------------------------------------------------------------------------------

    private static EvaluationDigest digest() {
        return new EvaluationDigest(UUID.randomUUID(), Section.QUESTIONING, "java/concurrency", 3, 71.5, "Solid.", java.util.Set.of());
    }

    @Test
    void aNarrativeRequestNeedsEvidenceToNarrate() {
        NarrativeRequest request = new NarrativeRequest(INTERVIEW, TargetRole.BACKEND, ExperienceLevel.MID, InterviewType.TECHNICAL, 70,
                Recommendation.PROFICIENT, List.of(new SkillScoreSummary("java", "Java", 70, Confidence.MEDIUM, 3)), List.of(digest()));

        assertThat(request.evaluations()).hasSize(1);
        assertThatThrownBy(() -> new NarrativeRequest(INTERVIEW, TargetRole.BACKEND, ExperienceLevel.MID, InterviewType.TECHNICAL, 70,
                Recommendation.PROFICIENT, List.of(), List.of())).hasMessageContaining("evaluations");
        assertThatThrownBy(() -> new NarrativeRequest(INTERVIEW, TargetRole.BACKEND, ExperienceLevel.MID, InterviewType.TECHNICAL, 101,
                Recommendation.PROFICIENT, List.of(), List.of(digest()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SkillScoreSummary("java", "Java", -1, Confidence.LOW, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvaluationDigest(UUID.randomUUID(), Section.CODING, null, 3, 101, "s", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void narrativeItemsCiteEvaluationsAndTheNarrativeIsBounded() {
        UUID cited = UUID.randomUUID();
        ReportNarrative narrative = new ReportNarrative("Good.", List.of(new NarrativeItem("Strong on JVM", List.of(cited))),
                List.of(new NarrativeItem("Indexes", List.of())), List.of("Read the Postgres docs"), "report-v1", null);

        assertThat(narrative.strengths().get(0).evaluationIds()).containsExactly(cited);
        assertThat(narrative.improvementAreas().get(0).evaluationIds()).as("verification, not construction, drops uncited items").isEmpty();
        assertThatThrownBy(() -> new ReportNarrative(" ", null, null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReportNarrative("s", java.util.Collections.nCopies(11, new NarrativeItem("t", null)), null, null, null, null))
                .hasMessageContaining("strengths");
        assertThatThrownBy(() -> new NarrativeItem(" ", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aModelResultWithAnUnstorableCharacterIsRejectedWhenItIsBuilt() {
        Map<Criterion, CriterionRating> ratings = Map.of(Criterion.DEPTH, new CriterionRating(2, ""));

        assertThatThrownBy(() -> assessment(ratings, List.of(), "fine\u0000 summary")).hasMessageContaining("control characters");
        assertThatThrownBy(() -> new CriterionRating(2, "why\u0000")).hasMessageContaining("control characters");
        assertThatThrownBy(() -> new KeyPointAssessment("kp1", KeyPointStatus.COVERED, "quote\u0000", true))
                .hasMessageContaining("control characters");
        assertThatThrownBy(() -> new GeneratedQuestion("Explain\u0000 volatile.", List.of(KEY_POINT), null, QuestionOrigin.GENERATED, 3,
                "java/concurrency", 5, List.of(), "question-v1", "1", "gemini-x", null)).hasMessageContaining("control characters");
        assertThat(assessment(ratings, List.of(), "two\nlines are fine").summary()).contains("\n");
    }

    @Test
    void aNarrativeRequestStatesHowMuchOfTheInterviewWasAssessed() {
        EvaluationDigest evidence = digest();
        NarrativeRequest full = new NarrativeRequest(INTERVIEW, TargetRole.BACKEND, ExperienceLevel.MID, InterviewType.TECHNICAL, 70,
                Recommendation.PROFICIENT, List.of(), List.of(evidence));
        NarrativeRequest partial = new NarrativeRequest(INTERVIEW, TargetRole.BACKEND, ExperienceLevel.MID, InterviewType.TECHNICAL, 70,
                Recommendation.PROFICIENT, List.of(), List.of(evidence), 0.75, 2);

        assertThat(full.assessmentCoverage()).isEqualTo(1.0);
        assertThat(full.unassessableAnswers()).isZero();
        assertThat(partial.assessmentCoverage()).isEqualTo(0.75);
        assertThat(partial.unassessableAnswers()).isEqualTo(2);
        assertThatThrownBy(() -> new NarrativeRequest(INTERVIEW, TargetRole.BACKEND, ExperienceLevel.MID, InterviewType.TECHNICAL, 70,
                Recommendation.PROFICIENT, List.of(), List.of(evidence), 1.01, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NarrativeRequest(INTERVIEW, TargetRole.BACKEND, ExperienceLevel.MID, InterviewType.TECHNICAL, 70,
                Recommendation.PROFICIENT, List.of(), List.of(evidence), 0.5, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
