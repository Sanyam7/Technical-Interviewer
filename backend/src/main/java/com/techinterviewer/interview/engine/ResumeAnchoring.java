package com.techinterviewer.interview.engine;

import com.techinterviewer.interview.domain.ResumePlan;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Picks the résumé experience a questioning question is anchored on (AI_ARCHITECTURE.md section 2.13). The topic and level were
 * chosen first, exactly as without a résumé; the anchor only decides what the question is framed around. The first rule that
 * applies wins:
 *
 * <ol>
 *   <li>no résumé plan, or its anchored questions are used up: no anchor;
 *   <li>the first experience linked to the topic that no question was anchored on yet;
 *   <li>the first experience linked to the topic's skill that no question was anchored on yet;
 *   <li>otherwise no anchor.
 * </ol>
 */
public final class ResumeAnchoring {

    /**
     * @param itemId the experience
     * @param basis  why it was chosen, for the rationale
     */
    public record Anchor(String itemId, String basis) {
    }

    private ResumeAnchoring() {
    }

    public static Optional<Anchor> pick(@Nullable ResumePlan plan, List<String> asked, String topic) {
        if (plan == null || asked.size() >= plan.questions()) {
            return Optional.empty();
        }
        String used = asked.size() + 1 + " of " + plan.questions();
        Optional<Anchor> linkedToTopic = plan.topics().getOrDefault(topic, List.of()).stream()
                .filter(id -> !asked.contains(id))
                .findFirst()
                .map(id -> new Anchor(id, "linked to the topic, résumé question " + used));
        if (linkedToTopic.isPresent()) {
            return linkedToTopic;
        }
        return plan.skills().getOrDefault(TopicSelector.skillOf(topic), List.of()).stream()
                .filter(id -> !asked.contains(id))
                .findFirst()
                .map(id -> new Anchor(id, "linked to the skill, résumé question " + used));
    }
}
