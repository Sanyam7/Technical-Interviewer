package com.techinterviewer.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.interview.domain.DifficultyBand;
import com.techinterviewer.interview.domain.InterviewPlan;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.SectionPlan;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SuggestedTimeTest {

    private static InterviewPlan plan(SectionPlan... sections) {
        return new InterviewPlan(1, List.of(sections), new DifficultyBand(3, 2, 4), 2);
    }

    @ParameterizedTest(name = "{0} minutes over {1} slots: a main question {2}, a follow-up {3}")
    @CsvSource({
            "24, 6, 4, 2",
            "10, 3, 4, 2",
            "11, 3, 4, 2",
            "12, 1, 12, 6",
            "14, 1, 14, 7",
            "4, 1, 4, 2",
            "2, 1, 2, 1",
            "1, 1, 1, 1",
            "0, 1, 1, 1",
            "3, 5, 1, 1",
            "5, 0, 5, 3"})
    void aMainQuestionGetsItsSectionsMinutesPerSlotAndAFollowUpHalfOfThatRoundedUp(int minutes, int slots, int main, int followUp) {
        InterviewPlan plan = plan(new SectionPlan(Section.QUESTIONING, minutes, slots));

        assertThat(SuggestedTime.minutes(QuestionKind.TECHNICAL, Section.QUESTIONING, plan)).isEqualTo(main);
        assertThat(SuggestedTime.minutes(QuestionKind.FOLLOW_UP, Section.QUESTIONING, plan)).isEqualTo(followUp);
    }

    @Test
    void everyRootKindUsesThePlanOfItsOwnSection() {
        InterviewPlan plan = plan(
                new SectionPlan(Section.INTRODUCTION, 3, 1),
                new SectionPlan(Section.QUESTIONING, 24, 6),
                new SectionPlan(Section.CODING, 20, 2),
                new SectionPlan(Section.SYSTEM_DESIGN, 14, 1));

        assertThat(SuggestedTime.minutes(QuestionKind.INTRO, Section.INTRODUCTION, plan)).isEqualTo(3);
        assertThat(SuggestedTime.minutes(QuestionKind.TECHNICAL, Section.QUESTIONING, plan)).isEqualTo(4);
        assertThat(SuggestedTime.minutes(QuestionKind.CODING, Section.CODING, plan)).isEqualTo(10);
        assertThat(SuggestedTime.minutes(QuestionKind.SYSTEM_DESIGN, Section.SYSTEM_DESIGN, plan)).isEqualTo(14);
        assertThat(SuggestedTime.minutes(QuestionKind.FOLLOW_UP, Section.CODING, plan)).isEqualTo(5);
        assertThat(SuggestedTime.minutes(QuestionKind.FOLLOW_UP, Section.SYSTEM_DESIGN, plan)).isEqualTo(7);
    }

    @Test
    void aSectionThatIsNotInThePlanIsABug() {
        InterviewPlan plan = plan(new SectionPlan(Section.INTRODUCTION, 3, 1));

        assertThatThrownBy(() -> SuggestedTime.minutes(QuestionKind.CODING, Section.CODING, plan))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CODING");
    }
}
