package com.techinterviewer.evaluation.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.design.DesignProbes;
import com.techinterviewer.design.DesignProblemBank;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The design evaluation dataset stays well formed and points at problems and dimensions that exist. */
class DesignEvaluationDatasetTest {

    @Test
    void everyCaseNamesABankProblemRubricDimensionsAndCoherentExpectations() {
        List<DesignEvaluationCase> cases = DesignEvaluationCase.load();
        DesignProblemBank bank = DesignProblemBank.load();
        Set<String> ids = cases.stream().map(DesignEvaluationCase::id).collect(Collectors.toSet());

        assertThat(cases).hasSizeGreaterThanOrEqualTo(7);
        assertThat(ids).hasSize(cases.size());
        for (DesignEvaluationCase testCase : cases) {
            assertThat(bank.find(testCase.problem())).as(testCase.id()).isPresent();
            DesignEvaluationCase.Expected expected = testCase.expected();
            assertThat(expected.scoreBand().get(0)).as(testCase.id()).isLessThanOrEqualTo(expected.scoreBand().get(1));
            assertThat(expected.factualErrors().get(0)).isLessThanOrEqualTo(expected.factualErrors().get(1));
            assertThat(DesignProbes.DIMENSIONS).as(testCase.id()).containsAll(expected.minRatings().keySet()).containsAll(expected.maxRatings().keySet());
            if (testCase.probe() != null) {
                assertThat(DesignProbes.DIMENSIONS).contains(testCase.probe());
                assertThat(testCase.question()).as("a probe is the latest question").isNotBlank();
                assertThat(testCase.thread()).isNotEmpty();
            }
            if (expected.invarianceOf() != null) {
                assertThat(ids).contains(expected.invarianceOf());
            }
        }
    }
}
