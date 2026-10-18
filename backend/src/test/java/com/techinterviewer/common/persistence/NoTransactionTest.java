package com.techinterviewer.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** The guard of the operations that call an AI port: it refuses an open transaction and nothing else. */
class NoTransactionTest {

    @AfterEach
    void closeWhatTheTestOpened() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void withoutATransactionTheOperationMayRun() {
        assertThatCode(() -> NoTransaction.require("Evaluating")).doesNotThrowAnyException();
    }

    @Test
    void insideATransactionTheOperationIsRefusedAndTheMessageNamesIt() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(() -> NoTransaction.require("Evaluating pending answers"))
                .isInstanceOf(IllegalTransactionStateException.class)
                .hasMessageContaining("Evaluating pending answers")
                .hasMessageContaining("AI port");
    }

    @Test
    void anEmptySynchronizationWithoutARealTransactionIsNotATransaction() {
        TransactionSynchronizationManager.initSynchronization();

        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThatCode(() -> NoTransaction.require("Finalising")).doesNotThrowAnyException();
    }
}
