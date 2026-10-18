package com.techinterviewer.interview.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Budget of one interview section.
 *
 * @param section     the section
 * @param minutes     soft time budget; the question in progress always finishes
 * @param slots       how many root questions the section asks (follow-ups draw from the same time budget, not from the slots)
 * @param topicQuotas how many questions each topic ({@code skill/topic} key) should get; only the questioning section has them
 * @param stages      the questioning section only, in the order they run: how many of the slots each stage asks (they add up to
 *                    {@code slots}). Empty in a plan stored before stages existed: its questions are asked without stages
 */
public record SectionPlan(
        Section section,
        int minutes,
        int slots,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Integer> topicQuotas,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<StagePlan> stages) {

    public static final int MAX_MINUTES = 240;
    public static final int MAX_SLOTS = 50;

    public SectionPlan {
        Require.nonNull(section, "section");
        Require.between(minutes, 0, MAX_MINUTES, "minutes");
        Require.between(slots, 0, MAX_SLOTS, "slots");
        topicQuotas = Immutables.map(topicQuotas);
        topicQuotas.forEach((topic, quota) -> {
            Require.notBlank(topic, "topic quota key");
            Require.between(quota, 1, MAX_SLOTS, "quota of " + topic);
        });
        stages = Immutables.list(stages);
        if (!stages.isEmpty()) {
            if (section != Section.QUESTIONING) {
                throw new IllegalArgumentException("only the questioning section has stages");
            }
            Set<InterviewStage> seen = EnumSet.noneOf(InterviewStage.class);
            int total = 0;
            for (StagePlan stage : stages) {
                if (!seen.add(stage.stage())) {
                    throw new IllegalArgumentException("stage " + stage.stage() + " is planned twice");
                }
                total += stage.slots();
            }
            if (total != slots) {
                throw new IllegalArgumentException("the stages ask " + total + " questions but the section has " + slots + " slots");
            }
        }
    }

    public SectionPlan(Section section, int minutes, int slots, Map<String, Integer> topicQuotas) {
        this(section, minutes, slots, topicQuotas, List.of());
    }

    public SectionPlan(Section section, int minutes, int slots) {
        this(section, minutes, slots, Map.of(), List.of());
    }

    /** True when the section asks its questions in stages. */
    public boolean staged() {
        return !stages.isEmpty();
    }

    /** The plan of {@code stage}; empty when the section has no stages or does not plan that stage. */
    public Optional<StagePlan> stage(InterviewStage stage) {
        return stages.stream().filter(plan -> plan.stage() == stage).findFirst();
    }

    /** The main questions planned for {@code stage}; 0 when it is not planned. */
    public int slotsOf(InterviewStage stage) {
        return stage(stage).map(StagePlan::slots).orElse(0);
    }
}
