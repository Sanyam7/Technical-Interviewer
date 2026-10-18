package com.techinterviewer.interview.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.techinterviewer.common.id.UuidV7;
import com.techinterviewer.persistence.Fixtures;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CandidateAnswerTest {

    private static final UUID QUESTION = UuidV7.generate();
    private static final UUID INTERVIEW = UuidV7.generate();

    private static CandidateAnswer submitted() {
        return CandidateAnswer.submit(QUESTION, INTERVIEW, "My answer", "java", 42, Fixtures.NOW);
    }

    @Test
    void aSubmittedAnswerAwaitsEvaluationAndKeepsWhatWasSubmitted() {
        CandidateAnswer answer = submitted();

        assertThat(answer.getQuestionId()).isEqualTo(QUESTION);
        assertThat(answer.getInterviewId()).isEqualTo(INTERVIEW);
        assertThat(answer.getContent()).isEqualTo("My answer");
        assertThat(answer.getCodeLanguage()).isEqualTo("java");
        assertThat(answer.getTimeTakenSeconds()).isEqualTo(42);
        assertThat(answer.isSkipped()).isFalse();
        assertThat(answer.getEvaluationStatus()).isEqualTo(EvaluationStatus.PENDING);
        assertThat(answer.getSubmittedAt()).isEqualTo(Fixtures.NOW);
        assertThat(UuidV7.isV7(answer.getId())).isTrue();
    }

    @Test
    void aSkippedAnswerHasEmptyContentAndNeedsNoEvaluation() {
        CandidateAnswer answer = CandidateAnswer.skip(QUESTION, INTERVIEW, 7, Fixtures.NOW);

        assertThat(answer.isSkipped()).isTrue();
        assertThat(answer.getContent()).isEmpty();
        assertThat(answer.getCodeLanguage()).isNull();
        assertThat(answer.getTimeTakenSeconds()).isEqualTo(7);
        assertThat(answer.getEvaluationStatus()).isEqualTo(EvaluationStatus.COMPLETED);
    }

    @Test
    void timeTakenAndLanguageAreOptional() {
        CandidateAnswer answer = CandidateAnswer.submit(QUESTION, INTERVIEW, "text", null, null, Fixtures.NOW);

        assertThat(answer.getTimeTakenSeconds()).isNull();
        assertThat(answer.getCodeLanguage()).isNull();
        assertThat(CandidateAnswer.skip(QUESTION, INTERVIEW, null, Fixtures.NOW).getTimeTakenSeconds()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\n\t "})
    void anAnswerToBeEvaluatedMustHaveContent(String blank) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CandidateAnswer.submit(QUESTION, INTERVIEW, blank, null, null, Fixtures.NOW))
                .withMessageContaining("skip()");
    }

    @Test
    void theContentIsBoundedLikeTheColumnCheck() {
        assertThat(CandidateAnswer.submit(QUESTION, INTERVIEW, "x".repeat(20_000), null, null, Fixtures.NOW).getContent()).hasSize(20_000);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CandidateAnswer.submit(QUESTION, INTERVIEW, "x".repeat(20_001), null, null, Fixtures.NOW))
                .withMessageContaining("20000");
    }

    @Test
    void timeTakenCannotBeNegative() {
        assertThatIllegalArgumentException().isThrownBy(() -> CandidateAnswer.submit(QUESTION, INTERVIEW, "a", null, -1, Fixtures.NOW)).withMessageContaining("timeTakenSeconds");
        assertThatIllegalArgumentException().isThrownBy(() -> CandidateAnswer.skip(QUESTION, INTERVIEW, -1, Fixtures.NOW)).withMessageContaining("timeTakenSeconds");
        assertThat(CandidateAnswer.submit(QUESTION, INTERVIEW, "a", null, 0, Fixtures.NOW).getTimeTakenSeconds()).isZero();
    }

    @Test
    void theCodeLanguageMustNotBeBlankOrLongerThanTheColumn() {
        assertThatIllegalArgumentException().isThrownBy(() -> CandidateAnswer.submit(QUESTION, INTERVIEW, "a", " ", null, Fixtures.NOW));
        assertThatIllegalArgumentException().isThrownBy(() -> CandidateAnswer.submit(QUESTION, INTERVIEW, "a", "l".repeat(21), null, Fixtures.NOW));
        assertThat(CandidateAnswer.submit(QUESTION, INTERVIEW, "a", "l".repeat(20), null, Fixtures.NOW).getCodeLanguage()).hasSize(20);
    }

    @Test
    void requiredValuesAreChecked() {
        assertThatIllegalArgumentException().isThrownBy(() -> CandidateAnswer.submit(null, INTERVIEW, "a", null, null, Fixtures.NOW)).withMessageContaining("questionId");
        assertThatIllegalArgumentException().isThrownBy(() -> CandidateAnswer.submit(QUESTION, null, "a", null, null, Fixtures.NOW)).withMessageContaining("interviewId");
        assertThatIllegalArgumentException().isThrownBy(() -> CandidateAnswer.submit(QUESTION, INTERVIEW, null, null, null, Fixtures.NOW)).withMessageContaining("content");
        assertThatIllegalArgumentException().isThrownBy(() -> CandidateAnswer.submit(QUESTION, INTERVIEW, "a", null, null, null)).withMessageContaining("now");
        assertThatIllegalArgumentException().isThrownBy(() -> CandidateAnswer.skip(null, INTERVIEW, null, Fixtures.NOW));
    }

    @Test
    void anEvaluatedAnswerIsCompletedAndIsNeverEvaluatedTwice() {
        CandidateAnswer answer = submitted();

        answer.markEvaluated();

        assertThat(answer.getEvaluationStatus()).isEqualTo(EvaluationStatus.COMPLETED);
        assertThatIllegalStateException().isThrownBy(answer::markEvaluated).withMessageContaining("COMPLETED");
        assertThatIllegalStateException().isThrownBy(() -> answer.giveUp(EvaluationFailure.PROVIDER_FAILURE));
        assertThat(answer.getEvaluationStatus()).isEqualTo(EvaluationStatus.COMPLETED);
    }

    private static final Instant T0 = Fixtures.NOW;

    @Test
    void anAttemptIsClaimedWithALeaseThatBlocksTheNextOneUntilItEnds() {
        CandidateAnswer answer = submitted();
        assertThat(answer.canStartAttempt(T0)).isTrue();

        int first = answer.startAttempt(T0, T0.plusSeconds(90));

        assertThat(first).isEqualTo(1);
        assertThat(answer.getEvaluationAttempts()).isEqualTo(1);
        assertThat(answer.getNextEvaluationAt()).isEqualTo(T0.plusSeconds(90));
        assertThat(answer.canStartAttempt(T0.plusSeconds(89))).isFalse();
        assertThat(answer.canStartAttempt(T0.plusSeconds(90))).as("the lease ends exactly at its instant").isTrue();
        assertThatIllegalStateException().isThrownBy(() -> answer.startAttempt(T0.plusSeconds(10), T0.plusSeconds(100)))
                .withMessageContaining("not due");
        assertThatIllegalArgumentException().isThrownBy(() -> submitted().startAttempt(T0, T0));
    }

    @Test
    void aFailedAttemptSchedulesTheNextOneAndOnlyTheLatestAttemptCanBeReported() {
        CandidateAnswer answer = submitted();
        int attempt = answer.startAttempt(T0, T0.plusSeconds(90));

        assertThatIllegalStateException().isThrownBy(() -> answer.failAttempt(attempt + 1, EvaluationFailure.INVALID_OUTPUT, T0))
                .withMessageContaining("not 2");
        assertThat(answer.attemptInProgress()).isTrue();
        answer.failAttempt(attempt, EvaluationFailure.INVALID_OUTPUT, T0.plusSeconds(2));

        assertThat(answer.attemptInProgress()).isFalse();
        assertThatIllegalStateException().as("an attempt is reported once").isThrownBy(() -> answer.failAttempt(attempt, EvaluationFailure.PROVIDER_FAILURE, T0.plusSeconds(9)))
                .withMessageContaining("reported already");
        assertThat(answer.getEvaluationFailure()).isEqualTo(EvaluationFailure.INVALID_OUTPUT);
        assertThat(answer.getEvaluationStatus()).isEqualTo(EvaluationStatus.PENDING);
        assertThat(answer.getEvaluationFailure()).isEqualTo(EvaluationFailure.INVALID_OUTPUT);
        assertThat(answer.canStartAttempt(T0.plusSeconds(1))).isFalse();
        assertThat(answer.startAttempt(T0.plusSeconds(2), T0.plusSeconds(92))).isEqualTo(2);
        assertThat(answer.getEvaluationFailure()).as("the failure of attempt 1 is gone: attempt 2 is in progress").isNull();
        assertThat(answer.attemptInProgress()).isTrue();
    }

    @Test
    void aGivenUpAnswerIsUnassessableForGoodAndKeepsItsReason() {
        CandidateAnswer answer = submitted();
        answer.startAttempt(T0, T0.plusSeconds(90));

        answer.giveUp(EvaluationFailure.SAFETY_BLOCK);

        assertThat(answer.getEvaluationStatus()).isEqualTo(EvaluationStatus.UNASSESSABLE);
        assertThat(answer.getEvaluationFailure()).isEqualTo(EvaluationFailure.SAFETY_BLOCK);
        assertThat(answer.getNextEvaluationAt()).isNull();
        assertThat(answer.canStartAttempt(T0.plusSeconds(1000))).isFalse();
        assertThatIllegalStateException().isThrownBy(answer::markEvaluated);
        assertThatIllegalStateException().isThrownBy(() -> answer.startAttempt(T0, T0.plusSeconds(1)));
        assertThatIllegalStateException().isThrownBy(() -> answer.failAttempt(1, EvaluationFailure.PROVIDER_FAILURE, T0));
        assertThatIllegalStateException().isThrownBy(() -> answer.giveUp(EvaluationFailure.PROVIDER_FAILURE));
        assertThat(answer.getEvaluationFailure()).isEqualTo(EvaluationFailure.SAFETY_BLOCK);
    }

    @Test
    void anEvaluationAfterFailedAttemptsClearsTheFailure() {
        CandidateAnswer answer = submitted();
        answer.failAttempt(answer.startAttempt(T0, T0.plusSeconds(90)), EvaluationFailure.PROVIDER_FAILURE, T0.plusSeconds(2));

        answer.markEvaluated();

        assertThat(answer.getEvaluationStatus()).isEqualTo(EvaluationStatus.COMPLETED);
        assertThat(answer.getEvaluationFailure()).isNull();
        assertThat(answer.getNextEvaluationAt()).isNull();
        assertThat(answer.getEvaluationAttempts()).as("the attempts stay as history").isEqualTo(1);
    }

    @Test
    void aSkippedAnswerCannotBeEvaluatedOrGivenUpOrAttempted() {
        CandidateAnswer skipped = CandidateAnswer.skip(QUESTION, INTERVIEW, null, Fixtures.NOW);

        assertThatIllegalStateException().isThrownBy(skipped::markEvaluated);
        assertThatIllegalStateException().isThrownBy(() -> skipped.giveUp(EvaluationFailure.PROVIDER_FAILURE));
        assertThatIllegalStateException().isThrownBy(() -> skipped.startAttempt(T0, T0.plusSeconds(1)));
        assertThat(skipped.canStartAttempt(T0)).isFalse();
    }

    @Test
    void onlyASafetyBlockIsNotRetryable() {
        assertThat(EvaluationFailure.PROVIDER_FAILURE.retryable()).isTrue();
        assertThat(EvaluationFailure.INVALID_OUTPUT.retryable()).isTrue();
        assertThat(EvaluationFailure.SAFETY_BLOCK.retryable()).isFalse();
    }
}
