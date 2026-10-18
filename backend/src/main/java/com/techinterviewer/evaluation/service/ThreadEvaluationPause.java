package com.techinterviewer.evaluation.service;

import java.time.Duration;
import org.springframework.stereotype.Component;

/** The real wait: the calling (virtual) thread sleeps. */
@Component
class ThreadEvaluationPause implements EvaluationPause {

    @Override
    public void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the next evaluation attempt", interrupted);
        }
    }
}
