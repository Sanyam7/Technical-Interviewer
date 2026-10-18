package com.techinterviewer.evaluation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.techinterviewer.interview.domain.EvaluationFailure;
import com.techinterviewer.interview.domain.Section;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoverageTest {

    private static final UnassessableAnswer ONE = new UnassessableAnswer(new UUID(0, 1), 1, Section.QUESTIONING, EvaluationFailure.PROVIDER_FAILURE);

    @Test
    void theShareIsRoundedDownSoItNeverDisplaysAsReachingAThresholdItDidNotReach() {
        assertThat(new Coverage(3, 0, 0, 5, 2, 3, List.of(ONE)).assessmentCoverage()).isEqualByComparingTo("0.66");
        assertThat(new Coverage(3, 0, 0, 5, 16, 23, List.of(ONE)).assessmentCoverage()).isEqualByComparingTo("0.69");
        assertThat(new Coverage(3, 0, 0, 5, 7, 10, List.of(ONE)).assessmentCoverage()).isEqualByComparingTo("0.70");
        assertThat(new Coverage(3, 0, 0, 5, 0, 10, List.of(ONE)).assessmentCoverage()).isEqualByComparingTo("0.00");
    }

    @Test
    void nothingToAssessIsFullCoverageUnlessAnAnswerCouldNotBeAssessed() {
        assertThat(new Coverage(0, 0, 0, 0).assessmentCoverage()).isEqualByComparingTo("1.00");
        assertThat(new Coverage(1, 0, 0, 5, 0, 0, List.of()).assessmentCoverage()).isEqualByComparingTo("1.00");
        assertThat(new Coverage(1, 0, 0, 5, 0, 0, List.of(ONE)).assessmentCoverage()).isEqualByComparingTo("0.00");
    }

    @Test
    void theAssessedWeightCannotExceedTheIntendedWeight() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Coverage(1, 0, 0, 1, 5, 4, List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new Coverage(1, 0, 0, 1, -1, 4, List.of()));
    }

    @Test
    void aCoverageStoredBeforeTheAssessmentExistedStillMeansEverythingWasAssessed() {
        Coverage old = new Coverage(8, 1, 2, 43);

        assertThat(old.unassessable()).isEmpty();
        assertThat(old.assessmentCoverage()).isEqualByComparingTo("1.00");
    }

    @Test
    void theUnassessableListIsCopied() {
        List<UnassessableAnswer> mutable = new java.util.ArrayList<>(List.of(ONE));
        Coverage coverage = new Coverage(1, 0, 0, 1, 0, 3, mutable);
        mutable.clear();

        assertThat(coverage.unassessable()).containsExactly(ONE);
    }
}
