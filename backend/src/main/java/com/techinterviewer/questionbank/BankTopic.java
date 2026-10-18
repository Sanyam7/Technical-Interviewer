package com.techinterviewer.questionbank;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import com.techinterviewer.interview.domain.InterviewStage;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The curated material of one catalog topic (AI_ARCHITECTURE.md section 2.15).
 *
 * @param skill          catalog skill slug
 * @param topic          catalog topic slug within the skill
 * @param subtopics      the parts of the topic an interviewer asks about, for example "HashMap internals"
 * @param misconceptions common misconceptions about the topic, stated as the wrong belief
 * @param references     knowledge-base documents that support the templates ({@code knowledge-base/<path>}); a test checks they exist
 * @param templates      at least one fundamentals (CONCEPT or INTERNALS) and one practical (SCENARIO or DEBUGGING) template
 */
public record BankTopic(String skill, String topic, List<String> subtopics, List<String> misconceptions, List<String> references,
        List<BankTemplate> templates) {

    /** The most misconceptions a topic lists; the question generator is shown all of them. */
    public static final int MAX_MISCONCEPTIONS = 6;

    public BankTopic {
        Require.notBlank(skill, "skill", 60);
        Require.notBlank(topic, "topic", 80);
        subtopics = Immutables.list(subtopics);
        misconceptions = Immutables.list(misconceptions);
        references = Immutables.list(references);
        templates = Immutables.list(templates);
        if (subtopics.isEmpty()) {
            throw new IllegalArgumentException(skill + "/" + topic + " lists no subtopics");
        }
        Require.between(misconceptions.size(), 0, MAX_MISCONCEPTIONS, "misconceptions of " + skill + "/" + topic);
        misconceptions.forEach(text -> Require.notBlank(text, "misconception of " + skill + "/" + topic, 300));
        references.forEach(path -> Require.notBlank(path, "reference of " + skill + "/" + topic, 120));
        Set<String> seen = new HashSet<>();
        for (BankTemplate template : templates) {
            if (!seen.add(template.id())) {
                throw new IllegalArgumentException("template " + template.id() + " appears twice in " + skill + "/" + topic);
            }
            if (!subtopics.contains(template.subtopic())) {
                throw new IllegalArgumentException("template " + template.id() + " names subtopic '" + template.subtopic()
                        + "', which " + skill + "/" + topic + " does not list");
            }
        }
        for (InterviewStage stage : List.of(InterviewStage.FUNDAMENTALS, InterviewStage.PRACTICAL)) {
            if (templates.stream().noneMatch(template -> template.stage() == stage)) {
                throw new IllegalArgumentException(skill + "/" + topic + " has no " + stage + " template");
            }
        }
    }

    /** The {@code skill/topic} key of the catalog. */
    public String key() {
        return skill + "/" + topic;
    }
}
