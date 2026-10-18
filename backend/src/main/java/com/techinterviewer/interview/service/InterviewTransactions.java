package com.techinterviewer.interview.service;

import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The two kinds of transaction the interview use cases run in: a read-only one for everything that only looks, and a
 * short read-write one for every change. A use case that calls a port (an AI model) must never do it inside either:
 * it runs its transactions one after the other around the call.
 */
@Component
public class InterviewTransactions {

    private final TransactionTemplate write;
    private final TransactionTemplate read;

    InterviewTransactions(PlatformTransactionManager transactionManager) {
        this.write = new TransactionTemplate(transactionManager);
        this.read = new TransactionTemplate(transactionManager);
        this.read.setReadOnly(true);
    }

    public <T> T read(Supplier<T> work) {
        return read.execute(status -> work.get());
    }

    public <T> T write(Supplier<T> work) {
        return write.execute(status -> work.get());
    }

    public void execute(Runnable work) {
        write.executeWithoutResult(status -> work.run());
    }
}
