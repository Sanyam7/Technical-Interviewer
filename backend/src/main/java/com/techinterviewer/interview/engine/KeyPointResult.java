package com.techinterviewer.interview.engine;

import com.techinterviewer.common.validation.Require;
import com.techinterviewer.evaluation.domain.KeyPointStatus;

/** How one expected key point of the answered question fared. */
public record KeyPointResult(String keyPointId, KeyPointStatus status) {

    public KeyPointResult {
        Require.notBlank(keyPointId, "keyPointId", 40);
        Require.nonNull(status, "status");
    }
}
