package com.techinterviewer.interview.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.common.id.UuidV7;
import com.techinterviewer.interview.domain.CandidateAnswer;
import com.techinterviewer.interview.domain.EvaluationFailure;
import com.techinterviewer.interview.domain.EvaluationStatus;
import com.techinterviewer.interview.domain.InterviewQuestion;
import com.techinterviewer.persistence.Fixtures;
import com.techinterviewer.persistence.PersistenceTestBase;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

class CandidateAnswerRepositoryIT extends PersistenceTestBase {

    private CandidateAnswer load(CandidateAnswer answer) {
        return inTransaction(() -> answers.findById(answer.getId()).orElseThrow());
    }

    private CandidateAnswer saveAnswerAt(Scenario scenario, InterviewQuestion question, Instant at) {
        CandidateAnswer answer = CandidateAnswer.submit(question.getId(), scenario.interviewId(), "an answer", null, null, at);
        inTransaction(() -> answers.saveAndFlush(answer));
        return answer;
    }

    @Test
    void aTextAnswerKeepsEveryFieldAndIsPending() {
        Scenario scenario = newScenario();
        InterviewQuestion question = saveQuestion(scenario, 1);
        CandidateAnswer original = Fixtures.answer(question.getId(), scenario.interviewId());
        inTransaction(() -> answers.saveAndFlush(original));

        CandidateAnswer loaded = load(original);

        assertThat(loaded).isNotSameAs(original).usingRecursiveComparison().isEqualTo(original);
        assertThat(loaded.getQuestionId()).isEqualTo(question.getId());
        assertThat(loaded.getInterviewId()).isEqualTo(scenario.interviewId());
        assertThat(loaded.getContent()).isEqualTo("volatile guarantees visibility.");
        assertThat(loaded.getCodeLanguage()).isNull();
        assertThat(loaded.isSkipped()).isFalse();
        assertThat(loaded.getTimeTakenSeconds()).isEqualTo(95);
        assertThat(loaded.getEvaluationStatus()).isEqualTo(EvaluationStatus.PENDING);
        assertThat(loaded.getSubmittedAt()).isEqualTo(Fixtures.NOW);
        assertThat(jdbc.queryForObject("select evaluation_status from answers where id = ?", String.class, original.getId())).isEqualTo("PENDING");
    }

    @Test
    void aCodeAnswerKeepsItsLanguageAndTheLongestAllowedContent() {
        Scenario scenario = newScenario();
        InterviewQuestion question = saveQuestion(scenario, 1);
        String longest = "x".repeat(CandidateAnswer.MAX_CONTENT_LENGTH);
        CandidateAnswer original = CandidateAnswer.submit(question.getId(), scenario.interviewId(), longest, "java", null, Fixtures.NOW);
        inTransaction(() -> answers.saveAndFlush(original));

        CandidateAnswer loaded = load(original);

        assertThat(loaded).usingRecursiveComparison().isEqualTo(original);
        assertThat(loaded.getContent()).hasSize(CandidateAnswer.MAX_CONTENT_LENGTH);
        assertThat(loaded.getCodeLanguage()).isEqualTo("java");
        assertThat(loaded.getTimeTakenSeconds()).isNull();
    }

    @Test
    void aSkippedAnswerIsStoredEmptyAndAlreadyCompleted() {
        Scenario scenario = newScenario();
        InterviewQuestion question = saveQuestion(scenario, 1);
        CandidateAnswer original = CandidateAnswer.skip(question.getId(), scenario.interviewId(), 12, Fixtures.NOW);
        inTransaction(() -> answers.saveAndFlush(original));

        CandidateAnswer loaded = load(original);

        assertThat(loaded).usingRecursiveComparison().isEqualTo(original);
        assertThat(loaded.isSkipped()).isTrue();
        assertThat(loaded.getContent()).isEmpty();
        assertThat(loaded.getEvaluationStatus()).isEqualTo(EvaluationStatus.COMPLETED);
        assertThat(loaded.getTimeTakenSeconds()).isEqualTo(12);
    }

    @Test
    void theEvaluationStatusChangesArePersisted() {
        Scenario scenario = newScenario();
        InterviewQuestion question = saveQuestion(scenario, 1);
        CandidateAnswer answer = saveAnswer(scenario, question);

        inTransaction(() -> {
            CandidateAnswer managed = answers.findById(answer.getId()).orElseThrow();
            managed.failAttempt(managed.startAttempt(Fixtures.NOW, Fixtures.NOW.plusSeconds(90)), EvaluationFailure.INVALID_OUTPUT, Fixtures.NOW.plusSeconds(2));
        });
        assertThat(load(answer).getEvaluationStatus()).isEqualTo(EvaluationStatus.PENDING);
        assertThat(load(answer).getEvaluationAttempts()).isEqualTo(1);
        assertThat(load(answer).getEvaluationFailure()).isEqualTo(EvaluationFailure.INVALID_OUTPUT);
        assertThat(load(answer).getNextEvaluationAt()).isEqualTo(Fixtures.NOW.plusSeconds(2));

        inTransaction(() -> answers.findById(answer.getId()).orElseThrow().markEvaluated());
        assertThat(load(answer).getEvaluationStatus()).isEqualTo(EvaluationStatus.COMPLETED);
        assertThat(jdbc.queryForObject("select evaluation_status from answers where id = ?", String.class, answer.getId())).isEqualTo("COMPLETED");
    }

    @Test
    void theContentOfAnAnswerIsNeverUpdated() {
        Scenario scenario = newScenario();
        InterviewQuestion question = saveQuestion(scenario, 1);
        CandidateAnswer answer = saveAnswer(scenario, question);

        inTransaction(() -> {
            CandidateAnswer managed = answers.findById(answer.getId()).orElseThrow();
            ReflectionTestUtils.setField(managed, "content", "rewritten");
            managed.giveUp(EvaluationFailure.PROVIDER_FAILURE);
        });

        assertThat(load(answer).getEvaluationStatus()).isEqualTo(EvaluationStatus.UNASSESSABLE);
        assertThat(load(answer).getContent()).isEqualTo("volatile guarantees visibility.");
    }

    @Test
    void aQuestionHasAtMostOneAnswer() {
        Scenario scenario = newScenario();
        InterviewQuestion question = saveQuestion(scenario, 1);
        saveAnswer(scenario, question);
        CandidateAnswer second = Fixtures.answer(question.getId(), scenario.interviewId());

        assertThatThrownBy(() -> inTransaction(() -> answers.saveAndFlush(second)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(count("answers", "question_id", question.getId())).isOne();
    }

    @Test
    void anAnswerNeedsARealQuestionAndInterview() {
        Scenario scenario = newScenario();
        InterviewQuestion question = saveQuestion(scenario, 1);

        assertThatThrownBy(() -> inTransaction(() -> answers.saveAndFlush(Fixtures.answer(UuidV7.generate(), scenario.interviewId()))))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> inTransaction(() -> answers.saveAndFlush(Fixtures.answer(question.getId(), UuidV7.generate()))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anAnswerIsFoundOnlyWithItsOwnInterview() {
        Scenario scenario = newScenario();
        Scenario other = newScenario();
        InterviewQuestion question = saveQuestion(scenario, 1);
        CandidateAnswer answer = saveAnswer(scenario, question);

        assertThat(answers.findByQuestionIdAndInterviewId(question.getId(), scenario.interviewId())).isPresent()
                .get().extracting(CandidateAnswer::getId).isEqualTo(answer.getId());
        assertThat(answers.findByQuestionIdAndInterviewId(question.getId(), other.interviewId())).isEmpty();
        assertThat(answers.findByIdAndInterviewId(answer.getId(), scenario.interviewId())).isPresent();
        assertThat(answers.findByIdAndInterviewId(answer.getId(), other.interviewId())).isEmpty();
        assertThat(answers.findByIdAndInterviewId(UuidV7.generate(), scenario.interviewId())).isEmpty();
    }

    @Test
    void theAnswersOfAnInterviewComeInSubmissionOrderAndAreCounted() {
        Scenario scenario = newScenario();
        Scenario other = newScenario();
        InterviewQuestion q1 = saveQuestion(scenario, 1);
        InterviewQuestion q2 = saveQuestion(scenario, 2);
        InterviewQuestion q3 = saveQuestion(scenario, 3);
        CandidateAnswer late = saveAnswerAt(scenario, q1, Fixtures.NOW.plusSeconds(30));
        CandidateAnswer early = saveAnswerAt(scenario, q2, Fixtures.NOW.plusSeconds(10));
        CandidateAnswer middle = saveAnswerAt(scenario, q3, Fixtures.NOW.plusSeconds(20));
        saveAnswer(other, saveQuestion(other, 1));

        assertThat(answers.findByInterviewIdOrderBySubmittedAtAscIdAsc(scenario.interviewId()))
                .extracting(CandidateAnswer::getId).containsExactly(early.getId(), middle.getId(), late.getId());
        assertThat(answers.countByInterviewId(scenario.interviewId())).isEqualTo(3);
        assertThat(answers.countByInterviewId(UuidV7.generate())).isZero();
    }

    @Test
    void theAnswersStillToEvaluateAreOnlyThePendingOnes() {
        Scenario scenario = newScenario();
        Scenario other = newScenario();
        InterviewQuestion q1 = saveQuestion(scenario, 1);
        InterviewQuestion q2 = saveQuestion(scenario, 2);
        InterviewQuestion q3 = saveQuestion(scenario, 3);
        InterviewQuestion q4 = saveQuestion(scenario, 4);
        InterviewQuestion q5 = saveQuestion(scenario, 5);
        CandidateAnswer failed = saveAnswerAt(scenario, q1, Fixtures.NOW.plusSeconds(1));
        CandidateAnswer pending = saveAnswerAt(scenario, q2, Fixtures.NOW.plusSeconds(2));
        CandidateAnswer evaluated = saveAnswerAt(scenario, q3, Fixtures.NOW.plusSeconds(3));
        inTransaction(() -> answers.saveAndFlush(CandidateAnswer.skip(q4.getId(), scenario.interviewId(), null, Fixtures.NOW.plusSeconds(4))));
        CandidateAnswer pendingLast = saveAnswerAt(scenario, q5, Fixtures.NOW.plusSeconds(5));
        inTransaction(() -> answers.findById(failed.getId()).orElseThrow().giveUp(EvaluationFailure.SAFETY_BLOCK));
        inTransaction(() -> answers.findById(evaluated.getId()).orElseThrow().markEvaluated());
        saveAnswer(other, saveQuestion(other, 1));

        assertThat(answers.findPendingIdsByInterviewId(scenario.interviewId()))
                .as("the given-up answer, the evaluated one and the skipped one are not waiting")
                .containsExactly(pending.getId(), pendingLast.getId());
        assertThat(answers.findPendingIdsByInterviewId(other.interviewId())).hasSize(1);
        assertThat(answers.findPendingIdsByInterviewId(UuidV7.generate())).isEmpty();
    }

    @Test
    void aSkippedAnswerThatSomeoneLeftPendingIsNeverReportedAsStillToEvaluate() {
        Scenario scenario = newScenario();
        InterviewQuestion question = saveQuestion(scenario, 1);
        UUID id = UuidV7.generate();
        jdbc.update("insert into answers (id, question_id, interview_id, content, skipped, evaluation_status) values (?, ?, ?, '', true, 'PENDING')",
                id, question.getId(), scenario.interviewId());

        assertThat(answers.findPendingIdsByInterviewId(scenario.interviewId())).isEmpty();
    }

    @Test
    void deletingAQuestionDeletesItsAnswer() {
        Scenario scenario = newScenario();
        InterviewQuestion question = saveQuestion(scenario, 1);
        CandidateAnswer answer = saveAnswer(scenario, question);

        jdbc.update("delete from questions where id = ?", question.getId());

        assertThat(count("answers", "id", answer.getId())).isZero();
    }
}
