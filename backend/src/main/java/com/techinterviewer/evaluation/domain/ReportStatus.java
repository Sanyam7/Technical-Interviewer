package com.techinterviewer.evaluation.domain;

/** Progress of a report. The constants equal the {@code ck_reports_status} check. */
public enum ReportStatus {
    PENDING,
    GENERATING,
    READY,
    FAILED
}
