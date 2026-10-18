package com.techinterviewer.interview.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.common.id.UuidV7;
import com.techinterviewer.interview.domain.InterviewQuestion;
import com.techinterviewer.interview.domain.QuestionContent;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.QuestionOrigin;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.persistence.Fixtures;
import com.techinterviewer.persistence.PersistenceTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class InterviewQuestionRepositoryIT extends PersistenceTestBase {

    private InterviewQuestion load(InterviewQuestion question) {
        return inTransaction(() -> questions.findById(question.getId()).orElseThrow());
    }

    @Test
    void aGeneratedTechnicalQuestionKeepsEveryFieldIncludingItsJsonKeyPoints() {
        Scenario scenario = newScenario();
        long topic = topicId();
        InterviewQuestion original = Fixtures.technicalQuestion(scenario.interviewId(), 1, topic);
        inTransaction(() -> questions.saveAndFlush(original));

        InterviewQuestion loaded = load(original);

        assertThat(loaded).isNotSameAs(original).usingRecursiveComparison().isEqualTo(original);
        assertThat(loaded.getInterviewId()).isEqualTo(scenario.interviewId());
        assertThat(loaded.getSequenceNo()).isEqualTo(1);
        assertThat(loaded.getSection()).isEqualTo(Section.QUESTIONING);
        assertThat(loaded.getKind()).isEqualTo(QuestionKind.TECHNICAL);
        assertThat(loaded.getTopicId()).isEqualTo(topic);
        assertThat(loaded.getDifficulty()).isEqualTo(3);
        assertThat(loaded.getFollowUpDepth()).isZero();
        assertThat(loaded.getParentQuestionId()).isNull();
        assertThat(loaded.getPrompt()).isEqualTo(Fixtures.content().prompt());
        assertThat(loaded.getDetails()).isNull();
        assertThat(loaded.getExpectedKeyPoints()).isEqualTo(Fixtures.content().expectedKeyPoints());
        assertThat(loaded.getRubricId()).isEqualTo("technical.v1");
        assertThat(loaded.getOrigin()).isEqualTo(QuestionOrigin.GENERATED);
        assertThat(loaded.getGenerationLlmCallId()).isEqualTo(Fixtures.QUESTION_LLM_CALL);
        assertThat(loaded.getAskedAt()).isEqualTo(Fixtures.NOW);
        assertThat(jdbc.queryForObject("select jsonb_array_length(expected_key_points) from questions where id = ?", Integer.class, original.getId()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("select expected_key_points -> 0 ->> 'id' from questions where id = ?", String.class, original.getId()))
                .isEqualTo("kp1");
        assertThat(jdbc.queryForObject("select source from questions where id = ?", String.class, original.getId())).isEqualTo("GENERATED");
        assertThat(jdbc.queryForObject("select kind from questions where id = ?", String.class, original.getId())).isEqualTo("TECHNICAL");
    }

    @Test
    void aCodingQuestionKeepsItsDetailsDocument() {
        Scenario scenario = newScenario();
        InterviewQuestion original = InterviewQuestion.root(
                scenario.interviewId(), 1, QuestionKind.CODING, null, 4,
                new QuestionContent(
                        "Sum the values.", Fixtures.codingDetails(), List.of(), "coding.v1", QuestionOrigin.EXEMPLAR_FALLBACK, null),
                Fixtures.NOW);
        inTransaction(() -> questions.saveAndFlush(original));

        InterviewQuestion loaded = load(original);

        assertThat(loaded).usingRecursiveComparison().isEqualTo(original);
        assertThat(loaded.getDetails()).isEqualTo(Fixtures.codingDetails());
        assertThat(loaded.getSection()).isEqualTo(Section.CODING);
        assertThat(loaded.getOrigin()).isEqualTo(QuestionOrigin.EXEMPLAR_FALLBACK);
        assertThat(loaded.getGenerationLlmCallId()).isNull();
        assertThat(loaded.getExpectedKeyPoints()).isEmpty();
        assertThat(jdbc.queryForObject("select details ->> 'language' from questions where id = ?", String.class, original.getId()))
                .isEqualTo("java");
    }

    @Test
    void theStaticIntroductionQuestionHasNoTopicDetailsOrModelCall() {
        Scenario scenario = newScenario();
        InterviewQuestion original = Fixtures.introQuestion(scenario.interviewId());
        inTransaction(() -> questions.saveAndFlush(original));

        InterviewQuestion loaded = load(original);

        assertThat(loaded).usingRecursiveComparison().isEqualTo(original);
        assertThat(loaded.getKind()).isEqualTo(QuestionKind.INTRO);
        assertThat(loaded.getSection()).isEqualTo(Section.INTRODUCTION);
        assertThat(loaded.getOrigin()).isEqualTo(QuestionOrigin.STATIC);
        assertThat(loaded.getTopicId()).isNull();
        assertThat(loaded.getGenerationLlmCallId()).isNull();
        assertThat(jdbc.queryForObject("select details is null from questions where id = ?", Boolean.class, original.getId())).isTrue();
    }

    @Test
    void aFollowUpKeepsItsParentAndDepth() {
        Scenario scenario = newScenario();
        InterviewQuestion parent = saveQuestion(scenario, 1);
        InterviewQuestion followUp = InterviewQuestion.followUp(
                scenario.interviewId(), 2, Section.QUESTIONING, null, 4, parent.getId(), 2, Fixtures.content(), Fixtures.NOW);
        inTransaction(() -> questions.saveAndFlush(followUp));

        InterviewQuestion loaded = load(followUp);

        assertThat(loaded).usingRecursiveComparison().isEqualTo(followUp);
        assertThat(loaded.getKind()).isEqualTo(QuestionKind.FOLLOW_UP);
        assertThat(loaded.isFollowUp()).isTrue();
        assertThat(loaded.getParentQuestionId()).isEqualTo(parent.getId());
        assertThat(loaded.getFollowUpDepth()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select followup_depth from questions where id = ?", Integer.class, followUp.getId())).isEqualTo(2);
    }

    @Test
    void questionsComeBackInTheOrderTheyWereAskedAndOnlyForTheirInterview() {
        Scenario scenario = newScenario();
        Scenario other = newScenario();
        InterviewQuestion third = saveQuestion(scenario, 3);
        InterviewQuestion first = saveQuestion(scenario, 1);
        InterviewQuestion second = saveQuestion(scenario, 2);
        saveQuestion(other, 1);

        assertThat(questions.findByInterviewIdOrderBySequenceNoAsc(scenario.interviewId()))
                .extracting(InterviewQuestion::getId).containsExactly(first.getId(), second.getId(), third.getId());
        assertThat(questions.findFirstByInterviewIdOrderBySequenceNoDesc(scenario.interviewId()))
                .get().extracting(InterviewQuestion::getId).isEqualTo(third.getId());
        assertThat(questions.findByInterviewIdOrderBySequenceNoAsc(UuidV7.generate())).isEmpty();
        assertThat(questions.findFirstByInterviewIdOrderBySequenceNoDesc(UuidV7.generate())).isEmpty();
    }

    @Test
    void theLatestSequenceNumberIsZeroWithoutQuestionsAndTheHighestOtherwise() {
        Scenario scenario = newScenario();
        Scenario other = newScenario();
        assertThat(questions.findLatestSequenceNo(scenario.interviewId())).isZero();

        saveQuestion(scenario, 1);
        saveQuestion(scenario, 2);
        saveQuestion(other, 7);

        assertThat(questions.findLatestSequenceNo(scenario.interviewId())).isEqualTo(2);
        assertThat(questions.findLatestSequenceNo(other.interviewId())).isEqualTo(7);
    }

    @Test
    void aQuestionIsFoundOnlyThroughItsOwnInterview() {
        Scenario scenario = newScenario();
        Scenario other = newScenario();
        InterviewQuestion question = saveQuestion(scenario, 1);

        assertThat(questions.findByIdAndInterviewId(question.getId(), scenario.interviewId())).isPresent();
        assertThat(questions.findByIdAndInterviewId(question.getId(), other.interviewId())).isEmpty();
        assertThat(questions.findByIdAndInterviewId(UuidV7.generate(), scenario.interviewId())).isEmpty();
    }

    @Test
    void twoQuestionsOfOneInterviewCannotShareASequenceNumber() {
        Scenario scenario = newScenario();
        Scenario other = newScenario();
        saveQuestion(scenario, 1);

        assertThatThrownBy(() -> saveQuestion(scenario, 1)).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(saveQuestion(other, 1).getSequenceNo()).as("another interview may use the same number").isEqualTo(1);
        assertThat(count("questions", "interview_id", scenario.interviewId())).isOne();
    }

    @Test
    void aQuestionNeedsARealInterviewTopicAndParent() {
        Scenario scenario = newScenario();
        assertThatThrownBy(() -> inTransaction(() -> questions.saveAndFlush(Fixtures.technicalQuestion(UuidV7.generate(), 1, null))))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> inTransaction(() -> questions.saveAndFlush(
                Fixtures.technicalQuestion(scenario.interviewId(), 1, Long.MAX_VALUE))))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> inTransaction(() -> questions.saveAndFlush(InterviewQuestion.followUp(
                scenario.interviewId(), 1, Section.QUESTIONING, null, 3, UuidV7.generate(), 1, Fixtures.content(), Fixtures.NOW))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aQuestionCannotNameAModelCallThatWasNeverRecorded() {
        Scenario scenario = newScenario();
        InterviewQuestion question = InterviewQuestion.root(
                scenario.interviewId(), 1, QuestionKind.TECHNICAL, null, 3,
                new QuestionContent(
                        "Why?", null, List.of(), "technical.v1", QuestionOrigin.GENERATED, UuidV7.generate()),
                Fixtures.NOW);

        assertThatThrownBy(() -> inTransaction(() -> questions.saveAndFlush(question)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingAQuestionDeletesItsFollowUps() {
        Scenario scenario = newScenario();
        InterviewQuestion parent = saveQuestion(scenario, 1);
        InterviewQuestion followUp = InterviewQuestion.followUp(
                scenario.interviewId(), 2, Section.QUESTIONING, null, 3, parent.getId(), 1, Fixtures.content(), Fixtures.NOW);
        inTransaction(() -> questions.saveAndFlush(followUp));

        jdbc.update("delete from questions where id = ?", parent.getId());

        assertThat(count("questions", "interview_id", scenario.interviewId())).isZero();
    }

    @Test
    void theKeyPointsOfAQuestionReadFromTheDatabaseCannotBeModifiedThroughTheGetter() {
        Scenario scenario = newScenario();
        InterviewQuestion loaded = load(saveQuestion(scenario, 1));

        assertThatThrownBy(() -> loaded.getExpectedKeyPoints().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
