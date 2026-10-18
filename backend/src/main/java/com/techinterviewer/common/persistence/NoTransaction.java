package com.techinterviewer.common.persistence;

import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The guard of an operation that calls an AI port and therefore must never run inside a database transaction (decision
 * D9). It is checked in code instead of declaring {@code @Transactional(propagation = NEVER)}: that annotation makes Spring
 * open an "empty" transaction with synchronization switched on, so {@code TransactionSynchronizationManager} would report
 * a synchronization in progress for the whole call, including the port call, which hides a real transaction from tests that
 * watch for one.
 */
public final class NoTransaction {

    private NoTransaction() {
    }

    /**
     * @param operation what is about to run, for the message
     * @throws IllegalTransactionStateException when a transaction is open
     */
    public static void require(String operation) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalTransactionStateException(operation + " calls an AI port and must not run inside a transaction");
        }
    }
}
