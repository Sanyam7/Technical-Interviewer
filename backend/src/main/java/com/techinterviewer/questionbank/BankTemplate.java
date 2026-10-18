package com.techinterviewer.questionbank;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import com.techinterviewer.interview.domain.DifficultyBand;
import com.techinterviewer.interview.domain.InterviewStage;
import com.techinterviewer.interview.domain.QuestionType;
import java.util.List;

/**
 * One curated question template (AI_ARCHITECTURE.md section 2.15).
 *
 * @param id        unique in the bank, for example {@code java-hashmap-internals}
 * @param type      CONCEPT, INTERNALS, SCENARIO or DEBUGGING (EXPERIENCE questions are about the candidate's own work and have
 *                  no template)
 * @param subtopic  one of its topic's subtopics
 * @param minLevel  the lowest difficulty it suits, 1 to 5. Levels stand for seniority: 1 and 2 junior, 3 mid-level (about three
 *                  years), 4 and 5 senior
 * @param maxLevel  the highest difficulty it suits
 * @param question  a model question for the angle, which the generator rewords at the decided level
 * @param keyPoints what a strong answer covers: 2 to 6 checkable statements (the evaluation criteria of the angle)
 */
public record BankTemplate(String id, QuestionType type, String subtopic, int minLevel, int maxLevel, String question, List<String> keyPoints) {

    public static final int MAX_KEY_POINTS = 6;

    public BankTemplate {
        Require.notBlank(id, "id", 60);
        if (!id.matches("[a-z0-9]+(-[a-z0-9]+)*")) {
            throw new IllegalArgumentException("template id '" + id + "' is not lower-case words joined by hyphens");
        }
        Require.nonNull(type, "type");
        if (type == QuestionType.EXPERIENCE) {
            throw new IllegalArgumentException("template " + id + ": EXPERIENCE questions are about the candidate's own work, not a template");
        }
        Require.notBlank(subtopic, "subtopic", 120);
        Require.between(minLevel, DifficultyBand.LOWEST, DifficultyBand.HIGHEST, "minLevel of " + id);
        Require.between(maxLevel, minLevel, DifficultyBand.HIGHEST, "maxLevel of " + id);
        Require.notBlank(question, "question of " + id, 600);
        keyPoints = Immutables.list(keyPoints);
        Require.between(keyPoints.size(), 2, MAX_KEY_POINTS, "key points of " + id);
        keyPoints.forEach(point -> Require.notBlank(point, "key point of " + id, 300));
    }

    public InterviewStage stage() {
        return type.stage();
    }

    /** How far {@code level} lies outside the template's levels; 0 when it suits. */
    int distanceTo(int level) {
        return level < minLevel ? minLevel - level : Math.max(0, level - maxLevel);
    }
}
