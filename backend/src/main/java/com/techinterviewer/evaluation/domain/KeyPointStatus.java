package com.techinterviewer.evaluation.domain;

/** How well an answer covered one expected key point. Stored as JSON text inside {@code key_points}. */
public enum KeyPointStatus {
    COVERED,
    PARTIAL,
    MISSING
}
