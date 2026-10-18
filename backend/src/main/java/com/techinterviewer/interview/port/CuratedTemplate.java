package com.techinterviewer.interview.port;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import com.techinterviewer.interview.domain.QuestionType;
import java.util.List;

/**
 * A curated question template of the question bank, as the question generator receives it (AI_ARCHITECTURE.md section 2.15): the
 * angle a question should take and what a strong answer covers. The generator words its own question from it, at the decided
 * level; the template is server-written text, never the candidate's.
 *
 * @param id             the template's id in the bank
 * @param type           the question type it is written for
 * @param subtopic       the part of the topic it is about, for example "HashMap internals"
 * @param question       a model question for the angle
 * @param keyPoints      what a strong answer covers, as checkable statements
 * @param misconceptions common misconceptions about the topic, which a good question can give the candidate room to avoid
 */
public record CuratedTemplate(String id, QuestionType type, String subtopic, String question, List<String> keyPoints,
        List<String> misconceptions) {

    public CuratedTemplate {
        Require.notBlank(id, "id", 60);
        Require.nonNull(type, "type");
        Require.notBlank(subtopic, "subtopic", 120);
        Require.notBlank(question, "question", 600);
        keyPoints = Immutables.list(keyPoints);
        misconceptions = Immutables.list(misconceptions);
        if (keyPoints.isEmpty()) {
            throw new IllegalArgumentException("a template states what a strong answer covers");
        }
    }
}
