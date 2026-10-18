package com.techinterviewer.coding.sandbox;

/**
 * What a container run produced.
 *
 * @param exitCode        exit status of the container's command ({@code -1} when it was killed)
 * @param stdout          standard output, at most {@code app.execution.max-output-bytes}
 * @param stderr          standard error, at most 8 KB
 * @param timedOut        the run hit {@code app.execution.run-timeout} and was killed
 * @param outputTruncated the output limit was reached and the run was killed
 * @param durationMs      wall-clock time of the container, start included
 */
public record SandboxResult(int exitCode, String stdout, String stderr, boolean timedOut, boolean outputTruncated, long durationMs) {

    /** Docker's own exit status for "the container ran out of memory or was killed". */
    public static final int KILLED = 137;

    public boolean killedForMemory() {
        return exitCode == KILLED && !timedOut && !outputTruncated;
    }
}
