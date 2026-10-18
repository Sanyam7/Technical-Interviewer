package com.techinterviewer.interview.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AnswerRepetitionTest {

    private static final String ANSWER = "I would put the counter behind an AtomicInteger because count plus plus is a read, an add and a write.";

    @Test
    void theSameAnswerAgainIsARepeatWhateverItsCaseAndPunctuation() {
        assertThat(AnswerRepetition.repeats(ANSWER, List.of(ANSWER))).isTrue();
        assertThat(AnswerRepetition.repeats(ANSWER.toUpperCase().replace(",", ""), List.of("Earlier.", ANSWER))).isTrue();
    }

    @Test
    void aPastedAnswerWithALineAddedIsStillARepeat() {
        assertThat(AnswerRepetition.repeats(ANSWER + " Also locks.", List.of(ANSWER))).isTrue();
    }

    @Test
    void aFollowUpAnswerThatReusesTheVocabularyButSaysSomethingNewIsNot() {
        String followUp = "The read of count and the write back can interleave, so one thread's add is lost; an AtomicInteger"
                + " makes the read, the add and the write one compare-and-set.";

        assertThat(AnswerRepetition.repeats(followUp, List.of(ANSWER))).isFalse();
    }

    @Test
    void shortAnswersAreNeverRepeatsAndNothingEarlierMeansNoRepeat() {
        assertThat(AnswerRepetition.repeats("I don't know.", List.of("I don't know."))).as("too short to judge").isFalse();
        assertThat(AnswerRepetition.repeats(ANSWER, List.of())).isFalse();
    }

    @Test
    void theThresholdIsEightyPercentOfTheNewAnswersTrigrams() {
        String earlier = "one two three four five six seven eight nine ten eleven twelve";
        // 10 trigrams in the new answer; the first 8 also appear in the earlier answer
        String eightOfTen = "one two three four five six seven eight nine ten x y";
        String sevenOfTen = "one two three four five six seven eight nine x y z";

        assertThat(AnswerRepetition.repeats(eightOfTen, List.of(earlier))).isTrue();
        assertThat(AnswerRepetition.repeats(sevenOfTen, List.of(earlier))).isFalse();
    }
}
