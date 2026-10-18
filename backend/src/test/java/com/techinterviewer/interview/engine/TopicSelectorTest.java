package com.techinterviewer.interview.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.interview.domain.DifficultyBand;
import com.techinterviewer.interview.domain.InterviewMemory;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.SectionProgress;
import com.techinterviewer.interview.domain.TopicStat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TopicSelectorTest {

    private static final DifficultyBand BAND = new DifficultyBand(3, 2, 4);

    private static Map<String, TopicRange> ranges(String... keys) {
        Map<String, TopicRange> ranges = new HashMap<>();
        for (String key : keys) {
            ranges.put(key, new TopicRange(1, 5));
        }
        return ranges;
    }

    private static InterviewMemory memory(Map<String, TopicStat> stats) {
        return new InterviewMemory(stats, List.of(), List.of(), List.of(), Map.of(),
                Map.of(Section.QUESTIONING, new SectionProgress(0, Instant.parse("2026-10-06T10:00:00Z"))), null);
    }

    private static TopicStat stat(int asked, int level) {
        return new TopicStat(asked, 50, level, 50);
    }

    @Test
    void firstChoiceIsTheTopicWithTheMostQuotaLeftAtTheStartingLevel() {
        Map<String, Integer> quotas = Map.of("java/a", 2, "java/b", 1, "sql/c", 1);

        TopicChoice choice = TopicSelector.next(quotas, ranges("java/a", "java/b", "sql/c"), InterviewMemory.empty(), BAND);

        assertThat(choice).isEqualTo(new TopicChoice("java/a", 3));
    }

    @Test
    void theSkillWithTheFewestAnswersGoesNext() {
        Map<String, Integer> quotas = Map.of("java/a", 2, "java/b", 1, "sql/c", 1);
        InterviewMemory afterJava = memory(Map.of("java/a", stat(1, 3)));

        assertThat(TopicSelector.next(quotas, ranges("java/a", "java/b", "sql/c"), afterJava, BAND).topic()).isEqualTo("sql/c");
    }

    @Test
    void withinASkillTheLeastAskedTopicGoesFirstThenTheOneWithMostQuotaLeft() {
        Map<String, Integer> quotas = Map.of("java/a", 3, "java/b", 3);

        assertThat(TopicSelector.next(quotas, ranges("java/a", "java/b"), memory(Map.of("java/a", stat(1, 3))), BAND).topic())
                .as("b was asked less").isEqualTo("java/b");

        Map<String, Integer> uneven = Map.of("java/a", 2, "java/b", 3);
        assertThat(TopicSelector.next(uneven, ranges("java/a", "java/b"),
                memory(Map.of("java/a", stat(1, 3), "java/b", stat(1, 3))), BAND).topic())
                .as("same asked count: b has 2 left, a has 1").isEqualTo("java/b");
    }

    @Test
    void theLeastAskedTopicOfASkillWinsEvenWhenAnotherHasMoreQuotaLeft() {
        Map<String, Integer> quotas = Map.of("java/a", 3, "java/b", 1);

        // a has 2 left, b has 1 left, but b has not been asked yet
        assertThat(TopicSelector.next(quotas, ranges("java/a", "java/b"), memory(Map.of("java/a", stat(1, 3))), BAND).topic())
                .isEqualTo("java/b");
    }

    @Test
    void lastTieBreakIsTheSmallerKeyWhateverTheMapOrder() {
        Map<String, Integer> forward = new LinkedHashMap<>();
        forward.put("java/a", 1);
        forward.put("java/b", 1);
        Map<String, Integer> backward = new LinkedHashMap<>();
        backward.put("java/b", 1);
        backward.put("java/a", 1);

        assertThat(TopicSelector.next(forward, ranges("java/a", "java/b"), InterviewMemory.empty(), BAND).topic()).isEqualTo("java/a");
        assertThat(TopicSelector.next(backward, ranges("java/a", "java/b"), InterviewMemory.empty(), BAND).topic()).isEqualTo("java/a");
    }

    @Test
    void aTopicWhoseQuotaIsUsedUpIsSkippedWhileOthersHaveQuotaLeft() {
        Map<String, Integer> quotas = Map.of("java/a", 1, "sql/b", 2);
        InterviewMemory memory = memory(Map.of("java/a", stat(1, 3)));

        assertThat(TopicSelector.next(quotas, ranges("java/a", "sql/b"), memory, BAND).topic()).isEqualTo("sql/b");

        // sql/b has been asked once, java/a is used up: java/a must not come back although its skill has fewer answers
        InterviewMemory afterSql = memory(Map.of("java/a", stat(1, 3), "sql/b", stat(1, 3)));
        assertThat(TopicSelector.next(quotas, ranges("java/a", "sql/b"), afterSql, BAND).topic()).isEqualTo("sql/b");
    }

    @Test
    void runningTheSelectorThroughAWholeQuestioningSectionAsksEveryTopicExactlyItsQuota() {
        Map<String, Integer> quotas = new LinkedHashMap<>();
        quotas.put("java/concurrency", 3);
        quotas.put("java/collections", 1);
        quotas.put("postgresql/indexing", 2);
        quotas.put("spring-boot/di", 2);
        Map<String, TopicRange> ranges = ranges("java/concurrency", "java/collections", "postgresql/indexing", "spring-boot/di");
        Map<String, TopicStat> stats = new HashMap<>();
        List<String> sequence = new ArrayList<>();

        for (int slot = 0; slot < 8; slot++) {
            String topic = TopicSelector.next(quotas, ranges, memory(stats), BAND).topic();
            sequence.add(topic);
            TopicStat previous = stats.get(topic);
            stats.put(topic, stat(previous == null ? 1 : previous.asked() + 1, 3));
        }

        assertThat(stats).hasSize(4);
        quotas.forEach((topic, quota) -> assertThat(stats.get(topic).asked()).as(topic).isEqualTo(quota));
        assertThat(sequence.subList(0, 3)).as("the first three questions come from three different skills")
                .extracting(topic -> topic.substring(0, topic.indexOf('/'))).doesNotHaveDuplicates();
    }

    @Test
    void whenEveryQuotaIsUsedUpTheSelectorStillAnswersWithTheLeastAnsweredSkill() {
        Map<String, Integer> quotas = Map.of("java/a", 1, "sql/b", 1);
        InterviewMemory memory = memory(Map.of("java/a", stat(2, 3), "sql/b", stat(1, 3)));

        assertThat(TopicSelector.next(quotas, ranges("java/a", "sql/b"), memory, BAND).topic()).isEqualTo("sql/b");
    }

    @Test
    void theQuestionIsAskedAtTheTopicsCurrentLevelWithinBandAndRange() {
        Map<String, Integer> quotas = Map.of("java/a", 1);

        assertThat(TopicSelector.next(quotas, ranges("java/a"), memory(Map.of("java/a", stat(0, 4))), BAND).difficulty()).isEqualTo(4);
        assertThat(TopicSelector.next(quotas, ranges("java/a"), memory(Map.of("java/a", stat(0, 5))), BAND).difficulty())
                .as("above the band").isEqualTo(4);
        assertThat(TopicSelector.next(quotas, ranges("java/a"), memory(Map.of("java/a", stat(0, 1))), BAND).difficulty())
                .as("below the band").isEqualTo(2);
        assertThat(TopicSelector.next(quotas, Map.of("java/a", new TopicRange(1, 3)), memory(Map.of("java/a", stat(0, 4))), BAND).difficulty())
                .as("above the topic's range").isEqualTo(3);
        assertThat(TopicSelector.next(quotas, Map.of("java/a", new TopicRange(4, 5)), InterviewMemory.empty(), BAND).difficulty())
                .as("a fresh topic starts at the band start moved into its range").isEqualTo(4);
        assertThat(TopicSelector.next(quotas, Map.of("java/a", new TopicRange(1, 2)), InterviewMemory.empty(), BAND).difficulty())
                .isEqualTo(2);
    }

    @Test
    void aFreshTopicStartsAtTheBandStartAndAnAskedTopicAtItsOwnLevel() {
        Map<String, Integer> quotas = Map.of("java/a", 2, "sql/b", 2);
        InterviewMemory memory = memory(Map.of("java/a", stat(1, 4)));

        TopicChoice choice = TopicSelector.next(quotas, ranges("java/a", "sql/b"), memory, BAND);

        assertThat(choice).isEqualTo(new TopicChoice("sql/b", 3));
    }

    @Test
    void invalidInputsAreRejected() {
        assertThatThrownBy(() -> TopicSelector.next(Map.of(), Map.of(), InterviewMemory.empty(), BAND))
                .hasMessageContaining("no topic quotas");
        assertThatThrownBy(() -> TopicSelector.next(Map.of("java/a", 1), Map.of(), InterviewMemory.empty(), BAND))
                .hasMessageContaining("java/a");
        assertThatThrownBy(() -> TopicSelector.next(null, Map.of(), InterviewMemory.empty(), BAND))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void topicChoiceIsValidated() {
        assertThatThrownBy(() -> new TopicChoice(" ", 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TopicChoice("java/a", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TopicChoice("java/a", 6)).isInstanceOf(IllegalArgumentException.class);
    }
}
