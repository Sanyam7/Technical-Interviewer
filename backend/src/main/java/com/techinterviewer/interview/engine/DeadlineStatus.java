package com.techinterviewer.interview.engine;

/** Where "now" lies relative to an interview's deadline. */
public enum DeadlineStatus {
    /** Up to and including the deadline. */
    RUNNING,
    /** After the deadline but within the grace window: an answer is still accepted. */
    WITHIN_GRACE,
    /** Past the grace window: the interview is over. */
    EXPIRED
}
