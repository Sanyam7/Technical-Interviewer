package com.techinterviewer.interview.service;

import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import com.techinterviewer.config.AppProperties;
import com.techinterviewer.interview.repository.InterviewRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The daily limit of interviews a candidate may create ({@code app.rate-limit.interviews-per-day}), counted in the
 * database from the start of the current UTC day, so it holds across restarts and instances. When the limit is used up
 * the answer is {@code 429 DAILY_INTERVIEW_LIMIT} with a {@code Retry-After} that points to the next UTC midnight.
 */
@Component
class InterviewQuota {

    private final InterviewRepository interviews;
    private final Clock clock;
    private final int perDay;

    InterviewQuota(InterviewRepository interviews, Clock clock, AppProperties properties) {
        this.interviews = interviews;
        this.clock = clock;
        this.perDay = properties.rateLimit().interviewsPerDay();
    }

    /**
     * Must run in the transaction that creates the interview. It first waits for the candidate's creation lock, which
     * the transaction keeps until it ends, so that the count it reads includes every creation that came before and no
     * creation that follows can overlap with it.
     *
     * @throws ApiException {@code 429 DAILY_INTERVIEW_LIMIT} when the candidate has created the day's share already
     */
    void requireAvailable(UUID candidateId) {
        interviews.lockByKey(lockKey(candidateId));
        Instant now = clock.instant();
        long created = interviews.countByCandidateIdAndCreatedAtGreaterThanEqual(candidateId, UtcDay.start(now));
        if (created >= perDay) {
            throw new ApiException(
                    ErrorCode.DAILY_INTERVIEW_LIMIT,
                    "You can create " + perDay + " interviews per day (UTC). Try again after midnight UTC.")
                    .withRetryAfter(UtcDay.untilNext(now));
        }
    }

    /** One lock per candidate. Two candidates whose keys collide only wait for each other briefly. */
    static long lockKey(UUID candidateId) {
        return candidateId.getMostSignificantBits() ^ candidateId.getLeastSignificantBits();
    }
}
