package com.techinterviewer.interview.service;

import com.techinterviewer.interview.api.dto.PublicPlan;
import com.techinterviewer.interview.api.dto.PublicSectionPlan;
import com.techinterviewer.interview.domain.InterviewPlan;

/** The candidate's view of a plan: sections with minutes and planned questions, nothing about topics or difficulty. */
public final class PublicPlanMapper {

    private PublicPlanMapper() {
    }

    public static PublicPlan toPublic(InterviewPlan plan) {
        return new PublicPlan(plan.sections().stream()
                .map(section -> new PublicSectionPlan(section.section(), section.minutes(), section.slots()))
                .toList());
    }
}
