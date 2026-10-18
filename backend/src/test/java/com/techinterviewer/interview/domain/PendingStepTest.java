package com.techinterviewer.interview.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PendingStepTest {

    private static final UUID PARENT = UUID.randomUUID();

    @Test
    void aNextTopicStepHasNoFollowUpDetails() {
        PendingStep step = PendingStep.nextTopic(Section.QUESTIONING, "java/jvm", 3);

        assertThat(step.kind()).isEqualTo(PendingStep.Kind.NEXT_TOPIC);
        assertThat(step.parentQuestionId()).isNull();
        assertThat(step.followUpMode()).isNull();
    }

    @Test
    void aNextTopicStepRejectsFollowUpDetails() {
        assertThatThrownBy(() -> new PendingStep(PendingStep.Kind.NEXT_TOPIC, Section.QUESTIONING, "a/b", 3, PARENT, null, null, null))
                .hasMessageContaining("NEXT_TOPIC");
        assertThatThrownBy(() -> new PendingStep(PendingStep.Kind.NEXT_TOPIC, Section.QUESTIONING, "a/b", 3, null, FollowUpMode.DEEPEN, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PendingStep(PendingStep.Kind.NEXT_TOPIC, Section.QUESTIONING, "a/b", 3, null, null, "kp1", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PendingStep(PendingStep.Kind.NEXT_TOPIC, Section.QUESTIONING, "a/b", 3, null, null, null, "claim"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aProbeGapNeedsExactlyAKeyPoint() {
        assertThat(PendingStep.followUp(Section.CODING, "a/b", 3, PARENT, FollowUpMode.PROBE_GAP, "kp1", null).targetKeyPointId())
                .isEqualTo("kp1");
        assertThatThrownBy(() -> PendingStep.followUp(Section.CODING, "a/b", 3, PARENT, FollowUpMode.PROBE_GAP, null, null))
                .hasMessageContaining("targetKeyPointId");
        assertThatThrownBy(() -> PendingStep.followUp(Section.CODING, "a/b", 3, PARENT, FollowUpMode.PROBE_GAP, "kp1", "claim"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aProbeClaimNeedsExactlyAClaim() {
        assertThat(PendingStep.followUp(Section.CODING, "a/b", 3, PARENT, FollowUpMode.PROBE_CLAIM, null, "Built X").targetClaim())
                .isEqualTo("Built X");
        assertThatThrownBy(() -> PendingStep.followUp(Section.CODING, "a/b", 3, PARENT, FollowUpMode.PROBE_CLAIM, null, null))
                .hasMessageContaining("targetClaim");
        assertThatThrownBy(() -> PendingStep.followUp(Section.CODING, "a/b", 3, PARENT, FollowUpMode.PROBE_CLAIM, "kp1", "Built X"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deepenTakesNoTarget() {
        assertThat(PendingStep.followUp(Section.CODING, "a/b", 4, PARENT, FollowUpMode.DEEPEN, null, null).followUpMode())
                .isEqualTo(FollowUpMode.DEEPEN);
        assertThatThrownBy(() -> PendingStep.followUp(Section.CODING, "a/b", 4, PARENT, FollowUpMode.DEEPEN, "kp1", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PendingStep.followUp(Section.CODING, "a/b", 4, PARENT, FollowUpMode.DEEPEN, null, "claim"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFollowUpNeedsItsParentAndMode() {
        assertThatThrownBy(() -> new PendingStep(PendingStep.Kind.FOLLOW_UP, Section.CODING, "a/b", 3, null, FollowUpMode.DEEPEN, null, null))
                .hasMessageContaining("parentQuestionId");
        assertThatThrownBy(() -> new PendingStep(PendingStep.Kind.FOLLOW_UP, Section.CODING, "a/b", 3, PARENT, null, null, null))
                .hasMessageContaining("followUpMode");
    }

    @Test
    void difficultyAndTopicAreChecked() {
        assertThatThrownBy(() -> PendingStep.nextTopic(Section.QUESTIONING, "a/b", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PendingStep.nextTopic(Section.QUESTIONING, "a/b", 6)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PendingStep.nextTopic(Section.QUESTIONING, " ", 3)).isInstanceOf(IllegalArgumentException.class);
        assertThat(PendingStep.nextTopic(Section.CODING, null, 5).topic()).isNull();
    }
}
