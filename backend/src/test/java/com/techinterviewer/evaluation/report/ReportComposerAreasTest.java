package com.techinterviewer.evaluation.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.catalog.TopicInfo;
import com.techinterviewer.evaluation.domain.AssessmentArea;
import com.techinterviewer.evaluation.domain.AssessmentAreas;
import com.techinterviewer.evaluation.domain.AssessmentAreas.Subject;
import com.techinterviewer.evaluation.domain.Recommendation;
import com.techinterviewer.interview.domain.EvaluationFailure;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.QuestionType;
import com.techinterviewer.interview.domain.Section;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** What the composed report says about the assessment areas and about what was not assessed (AI_ARCHITECTURE.md section 2.15). */
class ReportComposerAreasTest {

    private static final TopicInfo COLLECTIONS = new TopicInfo(1, 1, "java", "Java", "collections", "Collections");
    private static final TopicInfo HOOKS = new TopicInfo(2, 2, "react", "React", "hooks", "Hooks");
    private static final List<Subject> SKILLS = List.of(new Subject("java", "Java"), new Subject("react", "React"),
            new Subject("typescript", "TypeScript"));
    private static final List<Subject> TOPICS = List.of(new Subject("java/collections", "Java / Collections"),
            new Subject("react/hooks", "React / Hooks"), new Subject("typescript/generics", "TypeScript / Generics"));

    private static UUID id(int seq) {
        return new UUID(0, seq);
    }

    private static ReportEntry answered(int seq, TopicInfo topic, QuestionType type, double score, @Nullable Integer communication) {
        return new ReportEntry(id(seq), id(seq), seq, QuestionKind.TECHNICAL, Section.QUESTIONING, topic, 3, false, new UUID(1, seq), score,
                "Summary.", Set.of(), null, List.of(), List.of(), List.of(), type, false, communication);
    }

    private static ReportEntry unassessable(int seq, TopicInfo topic, QuestionType type) {
        return new ReportEntry(id(seq), id(seq), seq, QuestionKind.TECHNICAL, Section.QUESTIONING, topic, 3, false, null, null, "", Set.of(),
                EvaluationFailure.PROVIDER_FAILURE, List.of(), List.of(), List.of(), type, false, null);
    }

    private static ReportEntry intro() {
        return new ReportEntry(id(1), id(1), 1, QuestionKind.INTRO, Section.INTRODUCTION, null, 3, false, new UUID(1, 1), 80.0, "Summary.",
                Set.of(), null, List.of(), List.of(), List.of(), null, false, 3);
    }

    private static final ReportScope SCOPE = new ReportScope(
            EnumSet.of(AssessmentArea.FUNDAMENTALS, AssessmentArea.PRACTICAL, AssessmentArea.PROJECT, AssessmentArea.COMMUNICATION), SKILLS, TOPICS);

    @Test
    void theReportSaysWhatWasPlannedWhatWasAssessedAndWhatWasNot() {
        ComposedReport report = ReportComposer.compose(List.of(
                intro(),
                answered(2, COLLECTIONS, QuestionType.INTERNALS, 70, 3),
                answered(3, COLLECTIONS, QuestionType.CONCEPT, 80, 4),
                unassessable(4, HOOKS, QuestionType.CONCEPT),
                answered(5, COLLECTIONS, QuestionType.SCENARIO, 60, 3)), 30, new BigDecimal("0.70"), SCOPE);

        AssessmentAreas areas = report.assessmentAreas();
        assertThat(areas.areas()).extracting(AssessmentAreas.AreaScore::area, AssessmentAreas.AreaScore::planned, AssessmentAreas.AreaScore::evidenceCount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(AssessmentArea.FUNDAMENTALS, true, 2),
                        org.assertj.core.groups.Tuple.tuple(AssessmentArea.PRACTICAL, true, 1),
                        org.assertj.core.groups.Tuple.tuple(AssessmentArea.PROBLEM_SOLVING, false, 0),
                        org.assertj.core.groups.Tuple.tuple(AssessmentArea.PROJECT, true, 0),
                        org.assertj.core.groups.Tuple.tuple(AssessmentArea.COMMUNICATION, true, 4));
        assertThat(areas.skillsNotAssessed()).extracting(Subject::key).containsExactly("react", "typescript");
        assertThat(areas.topicsNotAssessed()).extracting(Subject::key).as("hooks was asked, but its answer could not be assessed")
                .containsExactly("react/hooks", "typescript/generics");
        assertThat(areas.recommendationCap()).isNull();
    }

    @Test
    void theCapAndItsReasonAreStored() {
        ComposedReport report = ReportComposer.compose(List.of(
                intro(),
                answered(2, COLLECTIONS, QuestionType.CONCEPT, 30, 3),
                answered(3, COLLECTIONS, QuestionType.INTERNALS, 35, 3),
                answered(4, HOOKS, QuestionType.EXPERIENCE, 100, 4),
                answered(5, HOOKS, QuestionType.EXPERIENCE, 100, 4),
                answered(6, HOOKS, QuestionType.EXPERIENCE, 100, 4),
                answered(7, HOOKS, QuestionType.EXPERIENCE, 100, 4),
                answered(8, HOOKS, QuestionType.EXPERIENCE, 100, 4),
                answered(9, HOOKS, QuestionType.EXPERIENCE, 100, 4)), 45, new BigDecimal("0.70"), SCOPE);

        assertThat(report.overallScore()).isEqualByComparingTo("83.13");
        assertThat(report.recommendation()).isEqualTo(Recommendation.DEVELOPING);
        assertThat(report.assessmentAreas().recommendationCap()).startsWith("Capped at Developing: the fundamentals scored 32.5 over 2 answers");
    }

    @Test
    void withoutAScopeNothingIsReportedAsPlannedOrMissed() {
        ComposedReport report = ReportComposer.compose(List.of(intro(), answered(2, COLLECTIONS, QuestionType.CONCEPT, 70, 3)), 30,
                new BigDecimal("0.70"));

        assertThat(report.assessmentAreas().areas()).noneMatch(AssessmentAreas.AreaScore::planned);
        assertThat(report.assessmentAreas().skillsNotAssessed()).isEmpty();
        assertThat(report.assessmentAreas().topicsNotAssessed()).isEmpty();
    }
}
