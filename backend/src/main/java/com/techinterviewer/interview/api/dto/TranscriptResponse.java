package com.techinterviewer.interview.api.dto;

import com.techinterviewer.interview.domain.InterviewState;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(description = "The questions asked so far, in order, with the answers given.")
public record TranscriptResponse(UUID interviewId, InterviewState state, List<TranscriptEntry> entries) {

    public TranscriptResponse {
        entries = List.copyOf(entries);
    }
}
