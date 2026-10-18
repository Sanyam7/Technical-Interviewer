package com.techinterviewer.evaluation.service;

import java.time.Duration;

/** Waits between evaluation attempts. A port of its own so tests do not sleep. */
@FunctionalInterface
public interface EvaluationPause {

    void sleep(Duration duration);
}
