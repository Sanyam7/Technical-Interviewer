package com.techinterviewer.interview.engine;

import com.techinterviewer.common.validation.Require;
import com.techinterviewer.interview.domain.DifficultyBand;

/** The topic the next questioning question is about and the level to ask it at. */
public record TopicChoice(String topic, int difficulty) {

    public TopicChoice {
        Require.notBlank(topic, "topic", 160);
        Require.between(difficulty, DifficultyBand.LOWEST, DifficultyBand.HIGHEST, "difficulty");
    }
}
