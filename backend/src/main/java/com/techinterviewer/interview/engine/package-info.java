/**
 * The interview engine: every decision about what happens next in an interview, as pure deterministic Java.
 *
 * <p>Nothing here depends on Spring, persistence or the AI layer, and nothing reads the clock: time is always an
 * argument. All inputs and results are immutable records, so each rule can be tested with plain values. The
 * services in {@code interview.service} supply the facts (state, plan, memory, the evaluated answer, the time)
 * and persist what the engine decides; the AI ports only word questions and rate answers, they never choose a
 * transition.
 */
package com.techinterviewer.interview.engine;
