package com.techinterviewer.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.techinterviewer.TestAppProperties;
import com.techinterviewer.TestClock;
import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import com.techinterviewer.interview.repository.InterviewRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class InterviewQuotaTest {

    private static final UUID CANDIDATE = UUID.fromString("0192f1c2-7a3b-7c11-9d4e-5f6a7b8c9d12");

    private final TestClock clock = new TestClock(Instant.parse("2026-10-05T18:30:00Z"));
    private final InterviewRepository repository = mock(InterviewRepository.class);
    private final InterviewQuota quota = new InterviewQuota(
            repository, clock, TestAppProperties.withJwt("x", false, "tech-interviewer", Duration.ofMinutes(15)));

    @ParameterizedTest(name = "{0} created today: allowed={1}")
    @CsvSource({"0,true", "1,true", "9,true", "10,false", "11,false", "250,false"})
    void theTenthInterviewOfTheDayIsTheLastOne(long createdToday, boolean allowed) {
        when(repository.countByCandidateIdAndCreatedAtGreaterThanEqual(eq(CANDIDATE), any())).thenReturn(createdToday);

        if (allowed) {
            assertThatCode(() -> quota.requireAvailable(CANDIDATE)).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> quota.requireAvailable(CANDIDATE)).isInstanceOf(ApiException.class);
        }
    }

    @Test
    void theRefusalIs429WithARetryAfterToTheNextUtcMidnight() {
        when(repository.countByCandidateIdAndCreatedAtGreaterThanEqual(eq(CANDIDATE), any())).thenReturn(10L);

        assertThatThrownBy(() -> quota.requireAvailable(CANDIDATE)).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.errorCode()).isEqualTo(ErrorCode.DAILY_INTERVIEW_LIMIT);
            assertThat(ex.headers()).containsEntry("Retry-After", String.valueOf(Duration.ofHours(5).plusMinutes(30).toSeconds()));
            assertThat(ex.getMessage()).contains("10");
        });
    }

    @Test
    void theDayIsCountedFromMidnightUtcOfTheClock() {
        List<Instant> asked = new ArrayList<>();
        when(repository.countByCandidateIdAndCreatedAtGreaterThanEqual(any(), any())).thenAnswer(call -> {
            asked.add(call.getArgument(1));
            return 0L;
        });

        quota.requireAvailable(CANDIDATE);
        clock.set(Instant.parse("2026-10-06T00:00:00Z"));
        quota.requireAvailable(CANDIDATE);

        assertThat(asked).containsExactly(Instant.parse("2026-10-05T00:00:00Z"), Instant.parse("2026-10-06T00:00:00Z"));
    }

    @Test
    void theCreationLockOfACandidateIsTakenBeforeTheCount() {
        List<String> calls = new ArrayList<>();
        when(repository.lockByKey(anyLong())).thenAnswer(call -> {
            calls.add("lock " + call.getArgument(0));
            return 1L;
        });
        when(repository.countByCandidateIdAndCreatedAtGreaterThanEqual(any(), any())).thenAnswer(call -> {
            calls.add("count");
            return 0L;
        });

        quota.requireAvailable(CANDIDATE);

        assertThat(calls).containsExactly("lock " + InterviewQuota.lockKey(CANDIDATE), "count");
    }

    @Test
    void theLockKeyDependsOnTheWholeUuidAndNotOnOneHalf() {
        UUID a = new UUID(1L, 2L);
        assertThat(InterviewQuota.lockKey(a)).isEqualTo(3L);
        assertThat(InterviewQuota.lockKey(new UUID(1L, 4L))).isNotEqualTo(InterviewQuota.lockKey(a));
        assertThat(InterviewQuota.lockKey(new UUID(8L, 2L))).isNotEqualTo(InterviewQuota.lockKey(a));
        assertThat(InterviewQuota.lockKey(a)).isEqualTo(InterviewQuota.lockKey(new UUID(1L, 2L)));
    }
}
