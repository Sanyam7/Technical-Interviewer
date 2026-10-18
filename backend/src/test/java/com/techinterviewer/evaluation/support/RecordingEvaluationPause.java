package com.techinterviewer.evaluation.support;

import com.techinterviewer.TestClock;
import com.techinterviewer.evaluation.service.EvaluationPause;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stands in for the real wait between evaluation attempts: it records how long the service wanted to wait and moves the
 * test clock by that much instead of sleeping, so a backoff elapses in the clock the stored instants are compared with.
 */
public final class RecordingEvaluationPause implements EvaluationPause {

    private final TestClock clock;
    private final List<Duration> pauses = new CopyOnWriteArrayList<>();

    public RecordingEvaluationPause(TestClock clock) {
        this.clock = clock;
    }

    @Override
    public void sleep(Duration duration) {
        pauses.add(duration);
        clock.advance(duration);
    }

    /** Every wait requested since the last {@link #reset()}, in order. */
    public List<Duration> pauses() {
        return List.copyOf(pauses);
    }

    public void reset() {
        pauses.clear();
    }
}
