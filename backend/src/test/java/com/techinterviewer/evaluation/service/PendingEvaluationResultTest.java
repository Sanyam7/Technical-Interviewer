package com.techinterviewer.evaluation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class PendingEvaluationResultTest {

    @Test
    void aRunIsCompleteWhenNothingIsStillPending() {
        assertThat(new PendingEvaluationResult(3, 0).complete()).isTrue();
        assertThat(new PendingEvaluationResult(0, 0).complete()).isTrue();
        assertThat(new PendingEvaluationResult(3, 1).complete()).isFalse();
    }

    @Test
    void countsCannotBeNegative() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PendingEvaluationResult(-1, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new PendingEvaluationResult(0, -1));
    }
}
