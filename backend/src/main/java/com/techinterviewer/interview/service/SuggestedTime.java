package com.techinterviewer.interview.service;

import com.techinterviewer.interview.domain.InterviewPlan;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.SectionPlan;

/**
 * How many minutes a candidate is advised to spend on a question. A deterministic function of the plan: a main question
 * gets its section's minutes divided by the section's planned questions (rounded up), a follow-up half of that (rounded
 * up), and never less than one minute. It is advice only; the interview deadline is the sole hard limit.
 */
public final class SuggestedTime {

    private SuggestedTime() {
    }

    /**
     * @param section the section the question belongs to (for a follow-up: the section of its thread)
     * @throws IllegalStateException when the plan has no such section
     */
    public static int minutes(QuestionKind kind, Section section, InterviewPlan plan) {
        SectionPlan sectionPlan = plan.section(section)
                .orElseThrow(() -> new IllegalStateException("the plan has no " + section + " section"));
        int perQuestion = Math.max(1, ceilDiv(sectionPlan.minutes(), Math.max(1, sectionPlan.slots())));
        return kind == QuestionKind.FOLLOW_UP ? Math.max(1, ceilDiv(perQuestion, 2)) : perQuestion;
    }

    private static int ceilDiv(int dividend, int divisor) {
        return (dividend + divisor - 1) / divisor;
    }
}
