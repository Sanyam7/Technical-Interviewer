package com.techinterviewer.interview.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.common.id.UuidV7;
import com.techinterviewer.persistence.Fixtures;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class InterviewQuestionTest {

    private static final UUID INTERVIEW = UuidV7.generate();
    private static final UUID PARENT = UuidV7.generate();

    private static InterviewQuestion root(QuestionKind kind, int sequenceNo, int difficulty) {
        return InterviewQuestion.root(INTERVIEW, sequenceNo, kind, 12L, difficulty, Fixtures.content(), Fixtures.NOW);
    }

    private static InterviewQuestion followUp(Section section, int depth) {
        return InterviewQuestion.followUp(INTERVIEW, 3, section, 12L, 4, PARENT, depth, Fixtures.content(), Fixtures.NOW);
    }

    @ParameterizedTest(name = "a {0} root question belongs to the {1} section")
    @CsvSource({"INTRO, INTRODUCTION", "TECHNICAL, QUESTIONING", "CODING, CODING", "SYSTEM_DESIGN, SYSTEM_DESIGN"})
    void aRootQuestionsSectionFollowsFromItsKind(QuestionKind kind, Section section) {
        InterviewQuestion question = root(kind, 1, 3);

        assertThat(question.getKind()).isEqualTo(kind);
        assertThat(question.getSection()).isEqualTo(section);
        assertThat(question.getParentQuestionId()).isNull();
        assertThat(question.getFollowUpDepth()).isZero();
        assertThat(question.isFollowUp()).isFalse();
    }

    @Test
    void aRootQuestionHoldsEverythingItWasCreatedWith() {
        InterviewQuestion question = InterviewQuestion.root(
                INTERVIEW, 4, QuestionKind.CODING, 99L, 5,
                new QuestionContent("Write sum.", Fixtures.codingDetails(), Fixtures.content().expectedKeyPoints(), "coding.v1",
                        QuestionOrigin.EXEMPLAR_FALLBACK, null),
                Fixtures.NOW);

        assertThat(question.getInterviewId()).isEqualTo(INTERVIEW);
        assertThat(question.getSequenceNo()).isEqualTo(4);
        assertThat(question.getTopicId()).isEqualTo(99L);
        assertThat(question.getDifficulty()).isEqualTo(5);
        assertThat(question.getPrompt()).isEqualTo("Write sum.");
        assertThat(question.getDetails()).isEqualTo(Fixtures.codingDetails());
        assertThat(question.getExpectedKeyPoints()).isEqualTo(Fixtures.content().expectedKeyPoints());
        assertThat(question.getRubricId()).isEqualTo("coding.v1");
        assertThat(question.getOrigin()).isEqualTo(QuestionOrigin.EXEMPLAR_FALLBACK);
        assertThat(question.getGenerationLlmCallId()).isNull();
        assertThat(question.getAskedAt()).isEqualTo(Fixtures.NOW);
        assertThat(UuidV7.isV7(question.getId())).isTrue();
    }

    @Test
    void aFollowUpHasAParentADepthAndTheFollowUpKind() {
        InterviewQuestion question = followUp(Section.CODING, 2);

        assertThat(question.getKind()).isEqualTo(QuestionKind.FOLLOW_UP);
        assertThat(question.isFollowUp()).isTrue();
        assertThat(question.getSection()).isEqualTo(Section.CODING);
        assertThat(question.getParentQuestionId()).isEqualTo(PARENT);
        assertThat(question.getFollowUpDepth()).isEqualTo(2);
        assertThat(question.getSequenceNo()).isEqualTo(3);
    }

    @Test
    void aRootQuestionCannotBeAFollowUpKind() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> root(QuestionKind.FOLLOW_UP, 1, 3))
                .withMessageContaining("followUp()");
    }

    @Test
    void theIntroductionHasNoFollowUps() {
        assertThatIllegalArgumentException().isThrownBy(() -> followUp(Section.INTRODUCTION, 1)).withMessageContaining("introduction");
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 3, 4})
    void aFollowUpDepthMustBeOneOrTwo(int depth) {
        assertThatIllegalArgumentException().isThrownBy(() -> followUp(Section.QUESTIONING, depth)).withMessageContaining("followUpDepth");
    }

    @Test
    void aFollowUpNeedsAParentAndASection() {
        assertThatIllegalArgumentException().isThrownBy(() -> InterviewQuestion.followUp(
                INTERVIEW, 3, Section.QUESTIONING, null, 3, null, 1, Fixtures.content(), Fixtures.NOW)).withMessageContaining("parentQuestionId");
        assertThatIllegalArgumentException().isThrownBy(() -> InterviewQuestion.followUp(
                INTERVIEW, 3, null, null, 3, PARENT, 1, Fixtures.content(), Fixtures.NOW)).withMessageContaining("section");
    }

    @ParameterizedTest
    @ValueSource(ints = {-3, 0, 6, 10})
    void theDifficultyMustBeBetweenOneAndFive(int difficulty) {
        assertThatIllegalArgumentException().isThrownBy(() -> root(QuestionKind.TECHNICAL, 1, difficulty)).withMessageContaining("difficulty");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void everyDifficultyFromOneToFiveIsAccepted(int difficulty) {
        assertThat(root(QuestionKind.TECHNICAL, 1, difficulty).getDifficulty()).isEqualTo(difficulty);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0})
    void theSequenceNumberStartsAtOne(int sequenceNo) {
        assertThatIllegalArgumentException().isThrownBy(() -> root(QuestionKind.TECHNICAL, sequenceNo, 3)).withMessageContaining("sequenceNo");
        assertThat(root(QuestionKind.TECHNICAL, 1, 3).getSequenceNo()).isEqualTo(1);
    }

    @Test
    void aQuestionWithoutATopicIsAllowedAndANonPositiveTopicIsNot() {
        assertThat(InterviewQuestion.root(INTERVIEW, 1, QuestionKind.SYSTEM_DESIGN, null, 3, Fixtures.content(), Fixtures.NOW).getTopicId()).isNull();
        assertThatIllegalArgumentException().isThrownBy(() -> InterviewQuestion.root(
                INTERVIEW, 1, QuestionKind.TECHNICAL, 0L, 3, Fixtures.content(), Fixtures.NOW)).withMessageContaining("topicId");
    }

    @Test
    void requiredValuesAreChecked() {
        assertThatIllegalArgumentException().isThrownBy(() -> InterviewQuestion.root(
                null, 1, QuestionKind.TECHNICAL, 1L, 3, Fixtures.content(), Fixtures.NOW)).withMessageContaining("interviewId");
        assertThatIllegalArgumentException().isThrownBy(() -> InterviewQuestion.root(
                INTERVIEW, 1, null, 1L, 3, Fixtures.content(), Fixtures.NOW)).withMessageContaining("kind");
        assertThatIllegalArgumentException().isThrownBy(() -> InterviewQuestion.root(
                INTERVIEW, 1, QuestionKind.TECHNICAL, 1L, 3, null, Fixtures.NOW)).withMessageContaining("content");
        assertThatIllegalArgumentException().isThrownBy(() -> InterviewQuestion.root(
                INTERVIEW, 1, QuestionKind.TECHNICAL, 1L, 3, Fixtures.content(), null)).withMessageContaining("askedAt");
    }

    @Test
    void theExpectedKeyPointsCannotBeModifiedThroughTheGetter() {
        InterviewQuestion question = root(QuestionKind.TECHNICAL, 1, 3);

        assertThatThrownBy(() -> question.getExpectedKeyPoints().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
