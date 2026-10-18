package com.techinterviewer.interview.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InterviewMemoryTest {

    private static final PendingStep STEP = PendingStep.nextTopic(Section.QUESTIONING, "java/jvm", 3);

    @Test
    void emptyMemoryHasNothingAndIsNotWaitingForAQuestion() {
        InterviewMemory memory = InterviewMemory.empty();

        assertThat(memory.topicStats()).isEmpty();
        assertThat(memory.answerSummaries()).isEmpty();
        assertThat(memory.sectionProgress()).isEmpty();
        assertThat(memory.pendingStep()).isNull();
        assertThat(memory.awaitingNextQuestion()).isFalse();
    }

    @Test
    void pendingStepCanBeSetAndClearedWithoutTouchingTheRest() {
        InterviewMemory base = new InterviewMemory(
                Map.of("a/b", new TopicStat(1, 50, 3, 50)), List.of("a/b:kp1"), List.of(), List.of(), Map.of("OFF_TOPIC", 1), Map.of(), null);

        InterviewMemory waiting = base.withPendingStep(STEP);
        InterviewMemory cleared = waiting.withPendingStep(null);

        assertThat(waiting.awaitingNextQuestion()).isTrue();
        assertThat(waiting.pendingStep()).isEqualTo(STEP);
        assertThat(waiting.topicStats()).isEqualTo(base.topicStats());
        assertThat(waiting.flags()).isEqualTo(base.flags());
        assertThat(cleared).isEqualTo(base);
        assertThat(base.pendingStep()).as("the original is unchanged").isNull();
    }

    @Test
    void collectionsAreCopiedAndImmutable() {
        Map<String, TopicStat> stats = new HashMap<>(Map.of("a/b", new TopicStat(1, 50, 3, 50)));
        List<String> keyPoints = new ArrayList<>(List.of("a/b:kp1"));
        Map<Section, SectionProgress> progress = new HashMap<>(Map.of(Section.CODING, new SectionProgress(0, Instant.EPOCH)));
        InterviewMemory memory = new InterviewMemory(stats, keyPoints, null, null, null, progress, null);

        stats.clear();
        keyPoints.clear();
        progress.clear();

        assertThat(memory.topicStats()).hasSize(1);
        assertThat(memory.demonstratedKeyPoints()).containsExactly("a/b:kp1");
        assertThat(memory.sectionProgress()).containsOnlyKeys(Section.CODING);
        assertThat(memory.candidateClaims()).isEmpty();
        assertThatThrownBy(() -> memory.topicStats().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> memory.demonstratedKeyPoints().add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> memory.sectionProgress().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void impossibleEntriesAreRejected() {
        assertThatThrownBy(() -> new InterviewMemory(Map.of(" ", new TopicStat(1, 50, 3, 50)), null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InterviewMemory(null, List.of(" "), null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InterviewMemory(null, null, null, null, Map.of("OFF_TOPIC", -1), null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aProbedClaimKeepsItsTextAndSource() {
        UUID answer = UUID.randomUUID();
        Claim claim = new Claim("Used Kafka", false, answer);

        Claim probed = claim.asProbed();

        assertThat(claim.probed()).isFalse();
        assertThat(probed.probed()).isTrue();
        assertThat(probed.text()).isEqualTo("Used Kafka");
        assertThat(probed.sourceAnswerId()).isEqualTo(answer);
    }
}
