package com.techinterviewer.evaluation.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.tuple;

import com.techinterviewer.catalog.TopicInfo;
import com.techinterviewer.evaluation.domain.Confidence;
import com.techinterviewer.evaluation.domain.Coverage;
import com.techinterviewer.evaluation.domain.EvaluationFlag;
import com.techinterviewer.evaluation.domain.IntegrityNote;
import com.techinterviewer.evaluation.domain.Recommendation;
import com.techinterviewer.evaluation.domain.SectionScore;
import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.InterviewType;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.TargetRole;
import com.techinterviewer.interview.port.EvaluationDigest;
import com.techinterviewer.interview.port.NarrativeRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The deterministic arithmetic of a report and its mapping to what is stored, on plain values. */
class ReportComposerTest {

    private static final java.math.BigDecimal MIN_COVERAGE = new java.math.BigDecimal("0.70");

    private static ComposedReport compose(List<ReportEntry> entries, int minutesUsed) {
        return ReportComposer.compose(entries, minutesUsed, MIN_COVERAGE);
    }


    private static final TopicInfo CONCURRENCY = new TopicInfo(6, 1, "java", "Java", "concurrency", "Concurrency");
    private static final TopicInfo GENERICS = new TopicInfo(3, 1, "java", "Java", "generics", "Generics");
    private static final TopicInfo TRANSACTIONS = new TopicInfo(12, 2, "spring-boot", "Spring Boot", "transactions", "Transactions");
    private static final TopicInfo INDEXING = new TopicInfo(17, 3, "postgresql", "PostgreSQL", "indexing", "Indexing");
    private static final UUID INTERVIEW = new UUID(9, 9);

    private static UUID id(int sequenceNo) {
        return new UUID(0, sequenceNo);
    }

    private static UUID evaluationId(int sequenceNo) {
        return new UUID(1, sequenceNo);
    }

    private static ReportEntry technical(int seq, TopicInfo topic, int difficulty, double score, EvaluationFlag... flags) {
        return new ReportEntry(id(seq), id(seq), seq, QuestionKind.TECHNICAL, Section.QUESTIONING, topic, difficulty, false,
                evaluationId(seq), score, "summary " + seq, Set.of(flags));
    }

    private static ReportEntry skippedTechnical(int seq, TopicInfo topic, int difficulty) {
        return new ReportEntry(id(seq), id(seq), seq, QuestionKind.TECHNICAL, Section.QUESTIONING, topic, difficulty, true, null, null, "", Set.of());
    }

    private static ReportEntry followUp(int seq, int rootSeq, Section section, @Nullable TopicInfo topic, int difficulty, double score) {
        return new ReportEntry(id(seq), id(rootSeq), seq, QuestionKind.FOLLOW_UP, section, topic, difficulty, false, evaluationId(seq), score,
                "summary " + seq, Set.of());
    }

    private static ReportEntry intro(int seq, double score) {
        return new ReportEntry(id(seq), id(seq), seq, QuestionKind.INTRO, Section.INTRODUCTION, null, 1, false, evaluationId(seq), score,
                "intro summary", Set.of());
    }

    private static ReportEntry coding(int seq, int difficulty, double score) {
        return new ReportEntry(id(seq), id(seq), seq, QuestionKind.CODING, Section.CODING, null, difficulty, false, evaluationId(seq), score,
                "coding summary", Set.of());
    }

    private static ReportEntry design(int seq, int difficulty, double score) {
        return new ReportEntry(id(seq), id(seq), seq, QuestionKind.SYSTEM_DESIGN, Section.SYSTEM_DESIGN, null, difficulty, false,
                evaluationId(seq), score, "design summary", Set.of());
    }

    private static List<ReportEntry> realistic() {
        return List.of(
                intro(1, 75),
                technical(2, CONCURRENCY, 3, 75),
                followUp(3, 2, Section.QUESTIONING, CONCURRENCY, 3, 50),
                technical(4, TRANSACTIONS, 4, 100),
                skippedTechnical(5, INDEXING, 2),
                technical(6, TRANSACTIONS, 2, 75, EvaluationFlag.INJECTION_SUSPECTED),
                coding(7, 3, 50),
                design(8, 4, 75),
                followUp(9, 8, Section.SYSTEM_DESIGN, null, 4, 100));
    }

    @Test
    void scoresAreWeightedByDifficultyAndAThreadOfADesignCountsAsItsLastAnsweredTurn() {
        ComposedReport report = compose(realistic(), 43);

        // (75*3 + 50*3 + 100*4 + 0*2 + 75*2 + 50*3 + 100*4) / (3+3+4+2+2+3+4) = 1475 / 21
        assertThat(report.overallScore()).isEqualByComparingTo("70.24");
        assertThat(report.recommendation()).isEqualTo(Recommendation.PROFICIENT);
        assertThat(report.sections()).containsExactly(
                new SectionScore(Section.INTRODUCTION, 75.0),
                new SectionScore(Section.QUESTIONING, 66.07),
                new SectionScore(Section.CODING, 50.0),
                new SectionScore(Section.SYSTEM_DESIGN, 100.0));
        assertThat(report.coverage().questionsAnswered()).isEqualTo(8);
        assertThat(report.coverage().skipped()).isEqualTo(1);
        assertThat(report.coverage().followUps()).isEqualTo(2);
        assertThat(report.coverage().minutesUsed()).isEqualTo(43);
        assertThat(report.coverage().intendedWeight()).as("every answer was assessed").isPositive()
                .isEqualTo(report.coverage().assessedWeight());
        assertThat(report.coverage().unassessable()).isEmpty();
        assertThat(report.outcome()).isEqualTo(com.techinterviewer.evaluation.domain.ReportOutcome.COMPLETE);
    }

    @Test
    void skillRowsCarryTheCatalogIdsNamesConfidenceEvidenceAndAverageDifficulty() {
        ComposedReport report = compose(realistic(), 43);

        assertThat(report.skills()).extracting(ComposedReport.SkillRow::slug).containsExactly("java", "postgresql", "spring-boot");
        ComposedReport.SkillRow java = report.skills().get(0);
        assertThat(java.skillId()).isEqualTo(1);
        assertThat(java.name()).isEqualTo("Java");
        assertThat(java.score()).isEqualByComparingTo("62.50");
        assertThat(java.confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(java.evidenceCount()).isEqualTo(2);
        assertThat(java.averageDifficulty()).isEqualByComparingTo("3.00");
        ComposedReport.SkillRow postgres = report.skills().get(1);
        assertThat(postgres.skillId()).isEqualTo(3);
        assertThat(postgres.score()).as("a skipped root counts as zero").isEqualByComparingTo("0");
        assertThat(postgres.confidence()).isEqualTo(Confidence.LOW);
        assertThat(postgres.evidenceCount()).isEqualTo(1);
        ComposedReport.SkillRow spring = report.skills().get(2);
        assertThat(spring.skillId()).isEqualTo(2);
        assertThat(spring.score()).isEqualByComparingTo("91.67");
        assertThat(spring.averageDifficulty()).isEqualByComparingTo("3.00");
    }

    @Test
    void anIntegrityNoteIsStoredPerFlaggedEvaluationInFlagOrder() {
        List<ReportEntry> entries = new ArrayList<>(realistic());
        entries.add(technical(10, GENERICS, 2, 60, EvaluationFlag.OFF_TOPIC, EvaluationFlag.INJECTION_SUSPECTED));

        ComposedReport report = compose(entries, 10);

        assertThat(report.integrityNotes()).extracting(IntegrityNote::flag, IntegrityNote::evaluationId).containsExactly(
                tuple(EvaluationFlag.INJECTION_SUSPECTED, evaluationId(6)),
                tuple(EvaluationFlag.INJECTION_SUSPECTED, evaluationId(10)),
                tuple(EvaluationFlag.OFF_TOPIC, evaluationId(10)));
        assertThat(report.integrityNotes()).allSatisfy(note -> assertThat(note.note()).isNotBlank());
    }

    @Test
    void theMinutesUsedAreTakenAsGiven() {
        assertThat(compose(realistic(), 0).coverage().minutesUsed()).isZero();
        assertThat(compose(realistic(), 87).coverage().minutesUsed()).isEqualTo(87);
    }

    @Test
    void anInterviewWithoutTechnicalEvidenceHasNoScoreNoSkillsAndNoRecommendation() {
        ComposedReport report = compose(List.of(intro(1, 100), skippedTechnical(2, CONCURRENCY, 3)), 5);

        assertThat(report.overallScore()).isNull();
        assertThat(report.recommendation()).isNull();
        assertThat(report.skills()).isEmpty();
        assertThat(report.sections()).containsExactly(new SectionScore(Section.INTRODUCTION, 100.0));
        assertThat(report.coverage()).as("the skipped question is assessed (it scores 0)").isEqualTo(new Coverage(1, 1, 0, 5, 3, 3, List.of()));
        assertThat(report.narrativeRequest(INTERVIEW, TargetRole.BACKEND, ExperienceLevel.MID, InterviewType.TECHNICAL)).isEmpty();
    }

    @Test
    void anEmptyInterviewComposesAnEmptyReport() {
        ComposedReport report = compose(List.of(), 0);

        assertThat(report.overallScore()).isNull();
        assertThat(report.sections()).isEmpty();
        assertThat(report.digests()).isEmpty();
        assertThat(report.evaluationIds()).isEmpty();
    }

    @Test
    void aSingleTurnGivesAScoreWithoutARecommendationBecauseTheConfidenceIsLow() {
        ComposedReport report = compose(List.of(intro(1, 100), technical(2, CONCURRENCY, 3, 90)), 5);

        assertThat(report.overallScore()).isEqualByComparingTo("90");
        assertThat(report.recommendation()).isNull();
        assertThat(report.narrativeRequest(INTERVIEW, TargetRole.BACKEND, ExperienceLevel.MID, InterviewType.TECHNICAL)).isEmpty();
    }

    @Test
    void theRecommendationBandFollowsTheOverallScore() {
        assertThat(bandOf(90, 85)).isEqualTo(Recommendation.STRONG);
        assertThat(bandOf(70, 66)).isEqualTo(Recommendation.PROFICIENT);
        assertThat(bandOf(50, 46)).isEqualTo(Recommendation.DEVELOPING);
        assertThat(bandOf(30, 20)).isEqualTo(Recommendation.NOT_YET);
    }

    private static Recommendation bandOf(double first, double second) {
        return compose(List.of(technical(1, CONCURRENCY, 3, first), technical(2, CONCURRENCY, 3, second)), 1).recommendation();
    }

    @Test
    void anAnswerThatStillAwaitsItsEvaluationCannotBeComposed() {
        ReportEntry waiting = new ReportEntry(id(2), id(2), 2, QuestionKind.TECHNICAL, Section.QUESTIONING, CONCURRENCY, 3, false, null, null, "",
                Set.of());

        assertThatIllegalArgumentException()
                .isThrownBy(() -> compose(List.of(intro(1, 80), waiting), 3))
                .withMessageContaining(id(2).toString());
    }

    @Test
    void theDigestsListEveryEvaluatedAnswerInAskingOrderWithItsTopicDifficultyScoreAndFlags() {
        List<EvaluationDigest> digests = compose(realistic(), 43).digests();

        assertThat(digests).extracting(EvaluationDigest::evaluationId).containsExactly(
                evaluationId(1), evaluationId(2), evaluationId(3), evaluationId(4), evaluationId(6), evaluationId(7), evaluationId(8), evaluationId(9));
        EvaluationDigest flagged = digests.get(4);
        assertThat(flagged.section()).isEqualTo(Section.QUESTIONING);
        assertThat(flagged.topic()).isEqualTo("spring-boot/transactions");
        assertThat(flagged.difficulty()).isEqualTo(2);
        assertThat(flagged.score()).isEqualTo(75.0);
        assertThat(flagged.summary()).isEqualTo("summary 6");
        assertThat(flagged.flags()).containsExactly(EvaluationFlag.INJECTION_SUSPECTED);
        assertThat(digests.get(0).topic()).as("the introduction has no topic").isNull();
        assertThat(digests.get(5).topic()).as("a coding question has no topic").isNull();
    }

    @Test
    void aLongSummaryIsShortenedToWhatTheNarratorAccepts() {
        ReportEntry longSummary = new ReportEntry(id(1), id(1), 1, QuestionKind.TECHNICAL, Section.QUESTIONING, CONCURRENCY, 3, false,
                evaluationId(1), 80.0, "x".repeat(900), Set.of());

        EvaluationDigest digest = compose(List.of(longSummary), 1).digests().get(0);

        assertThat(digest.summary()).hasSize(500).endsWith("...");
    }

    @Test
    void aSummaryOfExactlyTheLimitIsKept() {
        String exact = "y".repeat(500);
        ReportEntry entry = new ReportEntry(id(1), id(1), 1, QuestionKind.TECHNICAL, Section.QUESTIONING, CONCURRENCY, 3, false,
                evaluationId(1), 80.0, exact, Set.of());

        assertThat(compose(List.of(entry), 1).digests().get(0).summary()).isEqualTo(exact);
    }

    @Test
    void theNarratorRequestCarriesTheComputedNumbersTheInterviewFactsAndTheEvaluationIds() {
        ComposedReport report = compose(realistic(), 43);

        Optional<NarrativeRequest> request = report.narrativeRequest(INTERVIEW, TargetRole.FULL_STACK, ExperienceLevel.JUNIOR, InterviewType.FULL_LOOP);

        assertThat(request).isPresent();
        NarrativeRequest asked = request.get();
        assertThat(asked.interviewId()).isEqualTo(INTERVIEW);
        assertThat(asked.role()).isEqualTo(TargetRole.FULL_STACK);
        assertThat(asked.experience()).isEqualTo(ExperienceLevel.JUNIOR);
        assertThat(asked.type()).isEqualTo(InterviewType.FULL_LOOP);
        assertThat(asked.overallScore()).isEqualTo(70.24);
        assertThat(asked.recommendation()).isEqualTo(Recommendation.PROFICIENT);
        assertThat(asked.skillScores()).extracting(s -> s.skillSlug() + "|" + s.skillName() + "|" + s.score() + "|" + s.confidence() + "|" + s.evidenceCount())
                .containsExactly("java|Java|62.5|MEDIUM|2", "postgresql|PostgreSQL|0.0|LOW|1", "spring-boot|Spring Boot|91.67|MEDIUM|2");
        assertThat(asked.evaluations()).isEqualTo(report.digests());
        assertThat(report.evaluationIds()).containsExactlyInAnyOrder(
                evaluationId(1), evaluationId(2), evaluationId(3), evaluationId(4), evaluationId(6), evaluationId(7), evaluationId(8), evaluationId(9));
    }

    @Test
    void theCompositionIsDeterministicWhateverOrderTheEntriesArriveIn() {
        List<ReportEntry> shuffled = new ArrayList<>(realistic());
        Collections.reverse(shuffled);

        assertThat(compose(shuffled, 43)).isEqualTo(compose(realistic(), 43));
    }

    @Test
    void scoresAreExactDecimalsNotBinaryApproximations() {
        ComposedReport report = compose(List.of(
                technical(1, CONCURRENCY, 1, 33.33), technical(2, CONCURRENCY, 2, 66.67), technical(3, CONCURRENCY, 3, 10.0)), 1);

        // (33.33*1 + 66.67*2 + 10*3) / 6 = 32.77833...
        assertThat(report.overallScore()).isEqualTo(new BigDecimal("32.78"));
    }
}
