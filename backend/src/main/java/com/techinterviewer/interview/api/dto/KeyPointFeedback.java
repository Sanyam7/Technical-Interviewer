package com.techinterviewer.interview.api.dto;

import com.techinterviewer.evaluation.domain.KeyPointStatus;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Whether the answer covered one point a good answer contains.")
public record KeyPointFeedback(String text, KeyPointStatus status) {
}
