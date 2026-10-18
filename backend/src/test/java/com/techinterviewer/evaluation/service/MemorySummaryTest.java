package com.techinterviewer.evaluation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.interview.domain.AnswerSummary;
import com.techinterviewer.interview.domain.Claim;
import com.techinterviewer.interview.domain.InterviewMemory;
import com.techinterviewer.interview.port.EvaluationRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class MemorySummaryTest {

    private static final UUID ANSWER = new UUID(0, 1);

    private static InterviewMemory memory(List<Claim> claims, List<AnswerSummary> summaries) {
        return new InterviewMemory(Map.of(), List.of(), claims, summaries, Map.of(), Map.of(), null);
    }

    @Test
    void anEmptyMemoryHasAnEmptySummary() {
        assertThat(MemorySummary.of(InterviewMemory.empty())).isEmpty();
    }

    @Test
    void claimsAndEarlierAnswersAreListed() {
        String text = MemorySummary.of(memory(
                List.of(new Claim("Ran Kafka in production", false, ANSWER), new Claim("Led a team of five", true, ANSWER)),
                List.of(new AnswerSummary(ANSWER, "java/concurrency", "Knows volatile.", 82.4), new AnswerSummary(ANSWER, null, "Introduced themselves.", 60))));

        assertThat(text).isEqualTo("""
                Candidate claims: Ran Kafka in production; Led a team of five
                Earlier answers:
                - java/concurrency (score 82): Knows volatile.
                - introduction (score 60): Introduced themselves.""");
    }

    @Test
    void aSummaryWithOnlyAnswersHasNoClaimsLine() {
        String text = MemorySummary.of(memory(List.of(), List.of(new AnswerSummary(ANSWER, "java/generics", "Fine.", 50))));

        assertThat(text).doesNotContain("claims").startsWith("Earlier answers:");
    }

    @Test
    void aVeryLongMemoryIsCutToTheLimitOfTheRequest() {
        List<AnswerSummary> many = IntStream.range(0, 400)
                .mapToObj(i -> new AnswerSummary(ANSWER, "java/topic-" + i, "s".repeat(100), 50))
                .toList();

        String text = MemorySummary.of(memory(List.of(), many));

        assertThat(text).hasSize(EvaluationRequest.MAX_MEMORY_SUMMARY_LENGTH).endsWith("...");
    }

    /** A memory whose summary is exactly {@code length} characters long: "Candidate claims: " plus claims of 300 characters, joined by "; ". */
    private static InterviewMemory memoryWithSummaryOfLength(int length) {
        List<Claim> claims = new ArrayList<>();
        int remaining = length - "Candidate claims: ".length();
        while (remaining > 0) {
            int text = Math.min(300, remaining);
            claims.add(new Claim("c".repeat(text), false, ANSWER));
            remaining -= text + 2;
        }
        return memory(claims, List.of());
    }

    @Test
    void aSummaryOfExactlyTheLimitIsKeptWhole() {
        String text = MemorySummary.of(memoryWithSummaryOfLength(EvaluationRequest.MAX_MEMORY_SUMMARY_LENGTH));

        assertThat(text).hasSize(EvaluationRequest.MAX_MEMORY_SUMMARY_LENGTH).doesNotEndWith("...");
    }

    @Test
    void aSummaryOneCharacterOverTheLimitIsCut() {
        String text = MemorySummary.of(memoryWithSummaryOfLength(EvaluationRequest.MAX_MEMORY_SUMMARY_LENGTH + 1));

        assertThat(text).hasSize(EvaluationRequest.MAX_MEMORY_SUMMARY_LENGTH).endsWith("...");
    }
}
