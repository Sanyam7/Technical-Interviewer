package com.techinterviewer.interview.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import com.techinterviewer.interview.InterviewApiTestBase;
import com.techinterviewer.interview.domain.Interview;
import com.techinterviewer.interview.domain.InterviewEvent;
import com.techinterviewer.interview.domain.InterviewMemory;
import com.techinterviewer.interview.domain.PendingStep;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.interview.service.InterviewWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Two requests that change one interview at the same time: exactly one wins (D7). */
class InterviewConcurrencyApiIT extends InterviewApiTestBase {

    @Autowired
    private InterviewWriter writer;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private record Outcome(int status, String code) {
    }

    @Test
    void ofManySimultaneousStartsExactlyOneWinsAndTheOthersAreToldTheYAreTooLate() throws Exception {
        UUID ada = newUser();
        UUID id = createInterview(ada);
        int threads = 6;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Outcome> outcomes = new ArrayList<>();
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    var response = postAs(ada, "/interviews/" + id + "/start").andReturn().getResponse();
                    JsonNode body = json.readTree(response.getContentAsString());
                    return new Outcome(response.getStatus(), body.path("code").asString(""));
                }));
            }
            go.countDown();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(outcomes.stream().filter(o -> o.status() == 200)).hasSize(1);
        assertThat(outcomes.stream().filter(o -> o.status() != 200))
                .hasSize(threads - 1)
                .allSatisfy(o -> {
                    assertThat(o.status()).isEqualTo(409);
                    assertThat(o.code()).isIn("CONCURRENT_MODIFICATION", "ILLEGAL_STATE_TRANSITION");
                });
        assertThat(questions.findByInterviewIdOrderBySequenceNoAsc(id)).hasSize(1);
        assertThat(transitions.findByInterviewIdOrderByOccurredAtAscIdAsc(id)).hasSize(2);
        assertThat(fixture.reload(id).getState()).isEqualTo(InterviewState.INTRODUCTION);
        assertThat(fixture.reload(id).getCurrentQuestionId()).isNotNull();
    }

    @Test
    void aChangeThatLostTheRaceIsRolledBackAndAnswers409WithTheStateTheInterviewHasNow() {
        UUID ada = newUser();
        Interview interview = fixture.inState(ada, InterviewState.INIT, clock.instant());
        UUID id = interview.getId();
        AtomicBoolean reachedTheWrite = new AtomicBoolean();

        assertThatThrownBy(() -> writer.write(id, ada, loaded -> {
            // Somebody else starts the interview and commits while this request is still working on its copy.
            new TransactionTemplate(transactionManager, newTransaction()).executeWithoutResult(status -> {
                Interview other = interviews.findById(id).orElseThrow();
                transitions.save(other.applyTransition(
                        InterviewState.INIT, InterviewState.ABANDONED, InterviewEvent.ABANDON, clock.instant()));
                interviews.saveAndFlush(other);
            });
            loaded.replaceMemory(
                    InterviewMemory.empty().withPendingStep(PendingStep.nextTopic(Section.QUESTIONING, null, 3)), clock.instant());
            reachedTheWrite.set(true);
            return interviews.saveAndFlush(loaded);
        }))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.CONCURRENT_MODIFICATION);
                    assertThat(ex.properties()).containsEntry("currentState", "ABANDONED");
                });

        assertThat(reachedTheWrite).isTrue();
        assertThat(fixture.reload(id).getState()).isEqualTo(InterviewState.ABANDONED);
        assertThat(transitions.findByInterviewIdOrderByOccurredAtAscIdAsc(id)).hasSize(2);
    }

    private static TransactionDefinition newTransaction() {
        return new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
}
