package com.techinterviewer.interview.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "The shape of the interview, in the order its sections run.")
public record PublicPlan(List<PublicSectionPlan> sections) {

    public PublicPlan {
        sections = List.copyOf(sections);
    }
}
