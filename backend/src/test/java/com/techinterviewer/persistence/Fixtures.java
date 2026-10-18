package com.techinterviewer.persistence;

import com.techinterviewer.evaluation.domain.Coverage;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.CriterionScore;
import com.techinterviewer.evaluation.domain.Evaluation;
import com.techinterviewer.evaluation.domain.EvaluationFindings;
import com.techinterviewer.evaluation.domain.EvaluationFlag;
import com.techinterviewer.evaluation.domain.Evaluator;
import com.techinterviewer.evaluation.domain.FactualError;
import com.techinterviewer.evaluation.domain.IntegrityNote;
import com.techinterviewer.evaluation.domain.KeyPointAssessment;
import com.techinterviewer.evaluation.domain.KeyPointStatus;
import com.techinterviewer.evaluation.domain.ModelProvenance;
import com.techinterviewer.evaluation.domain.NarrativeEntry;
import com.techinterviewer.evaluation.domain.ReportNarrativeDocument;
import com.techinterviewer.interview.domain.AnswerSummary;
import com.techinterviewer.interview.domain.CandidateAnswer;
import com.techinterviewer.interview.domain.Claim;
import com.techinterviewer.interview.domain.CodingExample;
import com.techinterviewer.interview.domain.DifficultyBand;
import com.techinterviewer.interview.domain.ExpectedKeyPoint;
import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.FollowUpMode;
import com.techinterviewer.interview.domain.Interview;
import com.techinterviewer.interview.domain.InterviewConfiguration;
import com.techinterviewer.interview.domain.InterviewDifficulty;
import com.techinterviewer.interview.domain.InterviewMemory;
import com.techinterviewer.interview.domain.InterviewPlan;
import com.techinterviewer.interview.domain.InterviewQuestion;
import com.techinterviewer.interview.domain.InterviewType;
import com.techinterviewer.interview.domain.PendingStep;
import com.techinterviewer.interview.domain.QuestionContent;
import com.techinterviewer.interview.domain.QuestionDetails;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.QuestionOrigin;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.SectionPlan;
import com.techinterviewer.interview.domain.SectionProgress;
import com.techinterviewer.interview.domain.TargetRole;
import com.techinterviewer.interview.domain.TopicStat;
import com.techinterviewer.rag.domain.DocumentContent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Valid sample objects for the persistence tests. Every instant is a whole second, so it survives the microsecond
 * precision of {@code timestamptz} unchanged.
 */
public final class Fixtures {

    public static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    /** Model calls the fixtures refer to; the persistence tests insert a llm_calls row for each (they are foreign keys). */
    public static final UUID QUESTION_LLM_CALL = UUID.fromString("0192f1c2-7a3b-7c11-9d4e-5f6a7b8c9d02");
    public static final UUID EVALUATION_LLM_CALL = UUID.fromString("0192f1c2-7a3b-7c11-9d4e-5f6a7b8c9d03");
    public static final UUID REPORT_LLM_CALL = UUID.fromString("0192f1c2-7a3b-7c11-9d4e-5f6a7b8c9d04");

    private Fixtures() {
    }

    public static InterviewPlan plan() {
        return new InterviewPlan(
                InterviewPlan.CURRENT_VERSION,
                List.of(
                        new SectionPlan(Section.INTRODUCTION, 4, 1),
                        new SectionPlan(Section.QUESTIONING, 24, 6, Map.of("java/concurrency", 3, "postgresql/indexing", 3)),
                        new SectionPlan(Section.CODING, 16, 1),
                        new SectionPlan(Section.SYSTEM_DESIGN, 14, 1)),
                new DifficultyBand(3, 2, 4),
                2);
    }

    public static InterviewMemory memory() {
        UUID question = UUID.fromString("0192f1c2-7a3b-7c11-9d4e-5f6a7b8c9d0f");
        return new InterviewMemory(
                Map.of("java/concurrency", new TopicStat(2, 71.5, 3, 78)),
                List.of("java/concurrency:happens-before"),
                List.of(new Claim("Migrated a monolith to Kafka", false, UUID.fromString("0192f1c2-7a3b-7c11-9d4e-5f6a7b8c9d0e"))),
                List.of(new AnswerSummary(question, "java/concurrency", "Explained volatile.", 78)),
                Map.of("OFF_TOPIC", 1),
                Map.of(Section.QUESTIONING, new SectionProgress(3, NOW)),
                PendingStep.followUp(Section.QUESTIONING, "java/concurrency", 4, question, FollowUpMode.PROBE_GAP, "kp1", null));
    }

    public static InterviewConfiguration configuration(UUID owner, Set<Long> skillIds) {
        return InterviewConfiguration.create(
                owner, TargetRole.BACKEND, ExperienceLevel.SENIOR, InterviewDifficulty.HARD, 60, InterviewType.FULL_LOOP,
                "java", true, skillIds, NOW);
    }

    public static InterviewConfiguration technicalConfiguration(UUID owner, Set<Long> skillIds) {
        return InterviewConfiguration.create(
                owner, TargetRole.FULL_STACK, ExperienceLevel.JUNIOR, InterviewDifficulty.EASY, 30, InterviewType.TECHNICAL,
                null, false, skillIds, NOW);
    }

    public static Interview interview(UUID candidate, UUID configuration) {
        return Interview.create(candidate, configuration, plan(), NOW);
    }

    /** A started interview in QUESTIONING (its deadline is one hour after NOW). */
    public static Interview interviewInQuestioning(UUID candidate, UUID configuration) {
        Interview interview = interview(candidate, configuration);
        interview.start(NOW, 60);
        interview.applyTransition(com.techinterviewer.interview.domain.InterviewState.INIT,
                com.techinterviewer.interview.domain.InterviewState.INTRODUCTION,
                com.techinterviewer.interview.domain.InterviewEvent.START, NOW);
        interview.applyTransition(com.techinterviewer.interview.domain.InterviewState.INTRODUCTION,
                com.techinterviewer.interview.domain.InterviewState.QUESTIONING,
                com.techinterviewer.interview.domain.InterviewEvent.INTRO_ANSWERED, NOW);
        return interview;
    }

    public static QuestionContent content() {
        return new QuestionContent(
                "Explain how volatile differs from synchronized.",
                null,
                List.of(new ExpectedKeyPoint("kp1", "volatile guarantees visibility", List.of(UUID.fromString("0192f1c2-7a3b-7c11-9d4e-5f6a7b8c9d01"))),
                        new ExpectedKeyPoint("kp2", "synchronized adds mutual exclusion", List.of())),
                "technical.v1",
                QuestionOrigin.GENERATED,
                QUESTION_LLM_CALL);
    }

    public static QuestionContent staticContent() {
        return new QuestionContent("Please introduce yourself.", null, List.of(), "intro.v1", QuestionOrigin.STATIC, null);
    }

    public static QuestionDetails codingDetails() {
        return new QuestionDetails(
                List.of("1 <= n <= 10^5"),
                List.of(new CodingExample("[1,2,3]", "6", "sum")),
                "int sum(int[] values)",
                "java");
    }

    public static InterviewQuestion technicalQuestion(UUID interviewId, int sequenceNo, Long topicId) {
        return InterviewQuestion.root(interviewId, sequenceNo, QuestionKind.TECHNICAL, topicId, 3, content(), NOW);
    }

    public static InterviewQuestion introQuestion(UUID interviewId) {
        return InterviewQuestion.root(interviewId, 1, QuestionKind.INTRO, null, 1, staticContent(), NOW);
    }

    public static CandidateAnswer answer(UUID questionId, UUID interviewId) {
        return CandidateAnswer.submit(questionId, interviewId, "volatile guarantees visibility.", null, 95, NOW);
    }

    public static EvaluationFindings findings() {
        return new EvaluationFindings(
                List.of(new CriterionScore(Criterion.CORRECTNESS, 3, 0.4, "mostly right"),
                        new CriterionScore(Criterion.DEPTH, 2, 0.6, "")),
                List.of(new KeyPointAssessment("kp1", KeyPointStatus.COVERED, "volatile guarantees visibility", true),
                        new KeyPointAssessment("kp2", KeyPointStatus.MISSING, null, false)),
                List.of(new FactualError("volatile makes ++ atomic", "it does not", List.of(UUID.fromString("0192f1c2-7a3b-7c11-9d4e-5f6a7b8c9d01")))),
                List.of("Migrated a monolith to Kafka"),
                "Solid on visibility, missed mutual exclusion.",
                Set.of(EvaluationFlag.OFF_TOPIC, EvaluationFlag.INJECTION_SUSPECTED));
    }

    public static ModelProvenance provenance() {
        return new ModelProvenance("evaluate-technical", "1.2", "gemini-2.5-flash", EVALUATION_LLM_CALL);
    }

    public static Evaluation evaluation(UUID answerId) {
        return Evaluation.create(answerId, Evaluator.TECHNICAL, new BigDecimal("57.50"), findings(), provenance(), NOW);
    }

    public static ReportNarrativeDocument narrative(UUID evaluationId) {
        return new ReportNarrativeDocument(
                "A solid mid-level performance.",
                List.of(new NarrativeEntry("Clear on visibility", List.of(evaluationId))),
                List.of(new NarrativeEntry("Locking semantics", List.of(evaluationId))),
                List.of("Read JCIP chapter 3"),
                List.of(new com.techinterviewer.evaluation.domain.ReadingEntry(UUID.fromString("00000000-0000-0000-0000-00000000c4c4"),
                        "Java concurrency", "Java concurrency › The Java memory model and volatile", "java/concurrency.md")));
    }

    public static List<IntegrityNote> integrityNotes(UUID evaluationId) {
        return List.of(new IntegrityNote(EvaluationFlag.INJECTION_SUSPECTED, evaluationId, "Answer contained instructions to the evaluator."));
    }

    public static Coverage coverage() {
        return new Coverage(9, 1, 3, 43);
    }

    public static DocumentContent documentContent(String sha) {
        return new DocumentContent("Java concurrency notes", "notes.md", "text/markdown", 1234, sha, "# Concurrency\nvolatile ...");
    }

    /** A valid lower-case SHA-256 hex string derived from a seed character. */
    public static String sha(char seed) {
        return String.valueOf(seed).repeat(64);
    }
}
