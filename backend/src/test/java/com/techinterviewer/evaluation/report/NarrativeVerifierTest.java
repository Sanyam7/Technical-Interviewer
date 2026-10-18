package com.techinterviewer.evaluation.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.evaluation.domain.NarrativeEntry;
import com.techinterviewer.interview.port.NarrativeItem;
import com.techinterviewer.interview.port.ReportNarrative;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NarrativeVerifierTest {

    private static final UUID A = new UUID(1, 1);
    private static final UUID B = new UUID(1, 2);
    private static final UUID FOREIGN = new UUID(2, 1);
    private static final Set<UUID> MINE = Set.of(A, B);

    private static ReportNarrative narrative(List<NarrativeItem> strengths, List<NarrativeItem> improvements) {
        return new ReportNarrative("The summary.", strengths, improvements, List.of("Step"), "report-1.0", null);
    }

    @Test
    void itemsThatCiteOnlyEvaluationsOfTheInterviewAreKeptInOrder() {
        NarrativeVerifier.Result result = NarrativeVerifier.verify(
                narrative(List.of(new NarrativeItem("first", List.of(B)), new NarrativeItem("second", List.of(A, B))),
                        List.of(new NarrativeItem("improve", List.of(A)))),
                MINE);

        assertThat(result.dropped()).isZero();
        assertThat(result.document().strengths())
                .containsExactly(new NarrativeEntry("first", List.of(B)), new NarrativeEntry("second", List.of(A, B)));
        assertThat(result.document().improvementAreas()).containsExactly(new NarrativeEntry("improve", List.of(A)));
    }

    @Test
    void theFurtherReadingIsKeptWithTheSectionsItNames() {
        UUID chunk = UUID.randomUUID();
        ReportNarrative narrative = new ReportNarrative("The summary.", List.of(), List.of(), List.of("Step"),
                List.of(new com.techinterviewer.interview.port.StudyReference(chunk, "Caching", "Caching › Cache stampede and hot keys",
                        "system-design/caching.md")), "v2", null);

        assertThat(NarrativeVerifier.verify(narrative, MINE).document().furtherReading()).containsExactly(
                new com.techinterviewer.evaluation.domain.ReadingEntry(chunk, "Caching", "Caching › Cache stampede and hot keys",
                        "system-design/caching.md"));
    }

    @Test
    void anItemThatCitesNothingIsDropped() {
        NarrativeVerifier.Result result = NarrativeVerifier.verify(
                narrative(List.of(new NarrativeItem("unbacked", List.of())), List.of(new NarrativeItem("also unbacked", List.of()))), MINE);

        assertThat(result.dropped()).isEqualTo(2);
        assertThat(result.document().strengths()).isEmpty();
        assertThat(result.document().improvementAreas()).isEmpty();
    }

    @Test
    void anItemThatCitesAnEvaluationOfAnotherInterviewIsDroppedWholeEvenIfItAlsoCitesAGoodOne() {
        NarrativeVerifier.Result result = NarrativeVerifier.verify(
                narrative(List.of(new NarrativeItem("foreign", List.of(FOREIGN)), new NarrativeItem("mixed", List.of(A, FOREIGN))),
                        List.of(new NarrativeItem("mixed too", List.of(FOREIGN, B)))),
                MINE);

        assertThat(result.dropped()).isEqualTo(3);
        assertThat(result.document().strengths()).isEmpty();
        assertThat(result.document().improvementAreas()).isEmpty();
    }

    @Test
    void theSummaryAndTheStudyPlanPassThroughUntouched() {
        NarrativeVerifier.Result result = NarrativeVerifier.verify(narrative(List.of(), List.of()), MINE);

        assertThat(result.document().summary()).isEqualTo("The summary.");
        assertThat(result.document().studyPlan()).containsExactly("Step");
        assertThat(result.dropped()).isZero();
    }

    @Test
    void whenTheInterviewHasNoEvaluationsNothingCanBeCited() {
        NarrativeVerifier.Result result = NarrativeVerifier.verify(narrative(List.of(new NarrativeItem("x", List.of(A))), List.of()), Set.of());

        assertThat(result.dropped()).isEqualTo(1);
        assertThat(result.document().strengths()).isEmpty();
    }
}
