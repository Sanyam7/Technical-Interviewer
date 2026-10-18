package com.techinterviewer.evaluation.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.techinterviewer.catalog.TopicInfo;
import com.techinterviewer.evaluation.ReportScores.Assessment;
import com.techinterviewer.evaluation.domain.Recommendation;
import com.techinterviewer.evaluation.domain.ReportOutcome;
import com.techinterviewer.evaluation.domain.UnassessableAnswer;
import com.techinterviewer.interview.domain.EvaluationFailure;
import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.InterviewType;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.TargetRole;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * What a report is when some answers could not be assessed: COMPLETE, PARTIAL or INCONCLUSIVE, decided by the share of the intended
 * assessment weight that could be assessed against a configurable threshold, never by a guess about the unassessed answers.
 */
class ReportComposerOutcomeTest {

    private static final BigDecimal SEVENTY = new BigDecimal("0.70");
    private static final TopicInfo CONCURRENCY = new TopicInfo(6, 1, "java", "Java", "concurrency", "Concurrency");
    private static final TopicInfo TRANSACTIONS = new TopicInfo(12, 2, "spring-boot", "Spring Boot", "transactions", "Transactions");
    private static final UUID INTERVIEW = new UUID(9, 9);

    private static UUID id(int seq) {
        return new UUID(0, seq);
    }

    private static Assessment assessment(int intended, int assessed, int unassessableAnswers) {
        List<UnassessableAnswer> list = new ArrayList<>();
        for (int i = 1; i <= unassessableAnswers; i++) {
            list.add(new UnassessableAnswer(id(i), i, Section.QUESTIONING, EvaluationFailure.PROVIDER_FAILURE));
        }
        return new Assessment(intended, assessed, list);
    }

    // ---- the threshold, exactly ----------------------------------------------------------------------------------

    @ParameterizedTest(name = "{1} of {0} assessed at 0.70 is {2}")
    @CsvSource({
        "10, 10, COMPLETE",      // nothing unassessable is complete whatever the share
        "10, 7, PARTIAL",        // exactly 70%: enough
        "10, 6, INCONCLUSIVE",   // 60%
        "10, 8, PARTIAL",
        "20, 14, PARTIAL",       // exactly 70%
        "20, 13, INCONCLUSIVE",  // 65%
        "21, 15, PARTIAL",       // 71.4%: immediately above
        "21, 14, INCONCLUSIVE",  // 66.7%
        "23, 17, PARTIAL",       // 73.9%
        "23, 16, INCONCLUSIVE",  // 69.57%: immediately below
        "30, 21, PARTIAL",       // exactly 70%
        "30, 20, INCONCLUSIVE",  // 66.7%
        "10, 0, INCONCLUSIVE",   // nothing could be assessed
    })
    void theThresholdIsInclusiveAndExact(int intended, int assessed, ReportOutcome expected) {
        int unassessable = intended == assessed ? 0 : 1;

        assertThat(ReportComposer.outcome(assessment(intended, assessed, unassessable), SEVENTY)).isEqualTo(expected);
    }

    @Test
    void withNothingUnassessableTheReportIsCompleteEvenAtAThresholdOfOne() {
        assertThat(ReportComposer.outcome(assessment(10, 10, 0), BigDecimal.ONE)).isEqualTo(ReportOutcome.COMPLETE);
        assertThat(ReportComposer.outcome(Assessment.COMPLETE, BigDecimal.ONE)).isEqualTo(ReportOutcome.COMPLETE);
    }

    @Test
    void theThresholdIsConfigurable() {
        assertThat(ReportComposer.outcome(assessment(10, 8, 1), new BigDecimal("0.90"))).isEqualTo(ReportOutcome.INCONCLUSIVE);
        assertThat(ReportComposer.outcome(assessment(10, 9, 1), new BigDecimal("0.90"))).isEqualTo(ReportOutcome.PARTIAL);
        assertThat(ReportComposer.outcome(assessment(10, 6, 1), new BigDecimal("0.50"))).isEqualTo(ReportOutcome.PARTIAL);
        assertThat(ReportComposer.outcome(assessment(10, 9, 1), BigDecimal.ONE)).as("a threshold of one tolerates nothing").isEqualTo(ReportOutcome.INCONCLUSIVE);
        assertThat(ReportComposer.outcome(assessment(10, 1, 1), BigDecimal.ZERO)).as("a threshold of zero accepts anything assessed").isEqualTo(ReportOutcome.PARTIAL);
    }

    @Test
    void whenNothingAtAllCouldBeAssessedTheReportIsInconclusiveWhateverTheThreshold() {
        assertThat(ReportComposer.outcome(assessment(10, 0, 3), BigDecimal.ZERO)).isEqualTo(ReportOutcome.INCONCLUSIVE);
        assertThat(ReportComposer.outcome(assessment(0, 0, 1), BigDecimal.ZERO)).as("an unassessable introduction and nothing else").isEqualTo(ReportOutcome.INCONCLUSIVE);
    }

    // ---- what each outcome contains ----------------------------------------------------------------------------------

    private static ReportEntry technical(int seq, TopicInfo topic, int difficulty, double score) {
        return new ReportEntry(id(seq), id(seq), seq, QuestionKind.TECHNICAL, Section.QUESTIONING, topic, difficulty, false,
                new UUID(1, seq), score, "summary " + seq, Set.of());
    }

    private static ReportEntry unassessable(int seq, TopicInfo topic, int difficulty, EvaluationFailure reason) {
        return new ReportEntry(id(seq), id(seq), seq, QuestionKind.TECHNICAL, Section.QUESTIONING, topic, difficulty, false,
                null, null, "", Set.of(), reason);
    }

    private static ReportEntry intro(int seq, double score) {
        return new ReportEntry(id(seq), id(seq), seq, QuestionKind.INTRO, Section.INTRODUCTION, null, 1, false, new UUID(1, seq), score, "intro", Set.of());
    }

    @Test
    void aPartialReportScoresTheAssessableAnswersAndStatesWhatItLeftOut() {
        // difficulty 3 + 3 + 3 + 1 assessed (10), difficulty 3 unassessable: 10 of 13 is 76.9%
        ComposedReport report = ReportComposer.compose(List.of(
                intro(1, 80),
                technical(2, CONCURRENCY, 3, 80),
                technical(3, CONCURRENCY, 3, 70),
                technical(4, TRANSACTIONS, 3, 90),
                unassessable(5, TRANSACTIONS, 3, EvaluationFailure.SAFETY_BLOCK),
                technical(6, TRANSACTIONS, 1, 50)), 30, SEVENTY);

        assertThat(report.outcome()).isEqualTo(ReportOutcome.PARTIAL);
        assertThat(report.overallScore()).as("(80+70+90)*3 + 50*1 over 10").isEqualByComparingTo("77.00");
        assertThat(report.recommendation()).isEqualTo(Recommendation.PROFICIENT);
        assertThat(report.coverage().assessedWeight()).isEqualTo(10);
        assertThat(report.coverage().intendedWeight()).isEqualTo(13);
        assertThat(report.coverage().assessmentCoverage()).isEqualByComparingTo("0.76");
        assertThat(report.coverage().unassessable()).containsExactly(
                new UnassessableAnswer(id(5), 5, Section.QUESTIONING, EvaluationFailure.SAFETY_BLOCK));
        assertThat(report.skills()).extracting(ComposedReport.SkillRow::evidenceCount).as("evidence counts only assessed turns").containsExactly(2, 2);
        assertThat(report.digests()).as("the narrator is only given evaluations that exist").hasSize(5);
        var narrative = report.narrativeRequest(INTERVIEW, TargetRole.BACKEND, ExperienceLevel.MID, InterviewType.TECHNICAL).orElseThrow();
        assertThat(narrative.assessmentCoverage()).isEqualTo(0.76);
        assertThat(narrative.unassessableAnswers()).isEqualTo(1);
    }

    @Test
    void anInconclusiveReportHasNeitherAnOverallScoreNorARecommendationNorANarrativeRequestButKeepsTheEvidenceItHas() {
        ComposedReport report = ReportComposer.compose(List.of(
                intro(1, 80),
                technical(2, CONCURRENCY, 2, 90),
                unassessable(3, CONCURRENCY, 4, EvaluationFailure.PROVIDER_FAILURE),
                unassessable(4, TRANSACTIONS, 4, EvaluationFailure.INVALID_OUTPUT)), 30, SEVENTY);

        assertThat(report.coverage().assessmentCoverage()).isEqualByComparingTo("0.20");
        assertThat(report.outcome()).isEqualTo(ReportOutcome.INCONCLUSIVE);
        assertThat(report.overallScore()).isNull();
        assertThat(report.recommendation()).isNull();
        assertThat(report.narrativeRequest(INTERVIEW, TargetRole.BACKEND, ExperienceLevel.MID, InterviewType.TECHNICAL)).isEmpty();
        assertThat(report.sections()).extracting(section -> section.section()).containsExactly(Section.INTRODUCTION, Section.QUESTIONING);
        assertThat(report.skills()).singleElement().satisfies(skill -> assertThat(skill.evidenceCount()).isEqualTo(1));
        assertThat(report.coverage().unassessable()).extracting(UnassessableAnswer::reason)
                .containsExactly(EvaluationFailure.PROVIDER_FAILURE, EvaluationFailure.INVALID_OUTPUT);
    }

    @Test
    void anInconclusiveReportWithPlentyOfEvidenceStillStatesNoRecommendation() {
        // four assessed turns would be HIGH confidence and a STRONG band, but they are only 12 of 32 intended weight
        ComposedReport report = ReportComposer.compose(List.of(
                technical(1, CONCURRENCY, 3, 95), technical(2, CONCURRENCY, 3, 90), technical(3, TRANSACTIONS, 3, 92), technical(4, TRANSACTIONS, 3, 94),
                unassessable(5, CONCURRENCY, 5, EvaluationFailure.PROVIDER_FAILURE), unassessable(6, CONCURRENCY, 5, EvaluationFailure.PROVIDER_FAILURE),
                unassessable(7, TRANSACTIONS, 5, EvaluationFailure.PROVIDER_FAILURE), unassessable(8, TRANSACTIONS, 5, EvaluationFailure.PROVIDER_FAILURE)),
                30, SEVENTY);

        assertThat(report.coverage().assessmentCoverage()).isEqualByComparingTo("0.37");
        assertThat(report.outcome()).isEqualTo(ReportOutcome.INCONCLUSIVE);
        assertThat(report.overallScore()).isNull();
        assertThat(report.recommendation()).isNull();
        assertThat(report.skills()).extracting(ComposedReport.SkillRow::evidenceCount).containsExactly(2, 2);
    }

    @Test
    void whenNothingCouldBeAssessedTheReportIsInconclusiveWithNoSectionsExceptTheIntroduction() {
        ComposedReport report = ReportComposer.compose(List.of(
                unassessable(1, CONCURRENCY, 3, EvaluationFailure.PROVIDER_FAILURE),
                unassessable(2, TRANSACTIONS, 3, EvaluationFailure.PROVIDER_FAILURE)), 12, SEVENTY);

        assertThat(report.outcome()).isEqualTo(ReportOutcome.INCONCLUSIVE);
        assertThat(report.overallScore()).isNull();
        assertThat(report.sections()).isEmpty();
        assertThat(report.skills()).isEmpty();
        assertThat(report.digests()).isEmpty();
        assertThat(report.coverage().assessedWeight()).isZero();
        assertThat(report.coverage().assessmentCoverage()).isEqualByComparingTo("0.00");
        assertThat(report.coverage().questionsAnswered()).as("they were answered").isEqualTo(2);
    }

    @Test
    void aReportWithNothingUnassessableIsCompleteAndByteForByteWhatItWasBefore() {
        ComposedReport report = ReportComposer.compose(List.of(intro(1, 80), technical(2, CONCURRENCY, 3, 80), technical(3, TRANSACTIONS, 3, 60)), 30, SEVENTY);

        assertThat(report.outcome()).isEqualTo(ReportOutcome.COMPLETE);
        assertThat(report.overallScore()).isEqualByComparingTo("70.00");
        assertThat(report.coverage().unassessable()).isEmpty();
        assertThat(report.coverage().assessmentCoverage()).isEqualByComparingTo("1.00");
        assertThat(report.narrativeRequest(INTERVIEW, TargetRole.BACKEND, ExperienceLevel.MID, InterviewType.TECHNICAL).orElseThrow().assessmentCoverage()).isEqualTo(1.0);
    }

    @Test
    void anUnassessableAnswerDoesNotHoldTheReportBackButAWaitingOneStill() {
        assertThatNoException().isThrownBy(() -> ReportComposer.compose(List.of(unassessable(1, CONCURRENCY, 3, EvaluationFailure.PROVIDER_FAILURE)), 1, SEVENTY));
        ReportEntry waiting = new ReportEntry(id(1), id(1), 1, QuestionKind.TECHNICAL, Section.QUESTIONING, CONCURRENCY, 3, false, null, null, "", Set.of());

        assertThat(waiting.awaitsEvaluation()).isTrue();
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy(() -> ReportComposer.compose(List.of(waiting), 1, SEVENTY))
                .withMessageContaining("not evaluated yet");
    }
}
