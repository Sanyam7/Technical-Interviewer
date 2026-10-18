package com.techinterviewer.ai.structured;

import java.time.Duration;

/** Waits between two attempts of a provider call. A seam so tests do not sleep. */
@FunctionalInterface
public interface Sleeper {

    Sleeper THREAD = duration -> Thread.sleep(duration);

    void sleep(Duration duration) throws InterruptedException;
}
