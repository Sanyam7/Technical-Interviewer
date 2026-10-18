package com.techinterviewer.interview.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.interview.domain.InterviewMemory;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.SectionProgress;
import com.techinterviewer.persistence.Fixtures;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InterviewMemoriesTest {

    private static final Instant T0 = Instant.parse("2026-10-05T12:00:00Z");

    @Test
    void aNewSectionIsStartedWithNothingAnsweredAtTheGivenTime() {
        InterviewMemory started = InterviewMemories.withSectionStarted(InterviewMemory.empty(), Section.INTRODUCTION, T0);

        assertThat(started.sectionProgress()).containsExactly(Map.entry(Section.INTRODUCTION, new SectionProgress(0, T0)));
    }

    @Test
    void everythingElseInTheMemoryIsKept() {
        InterviewMemory memory = Fixtures.memory();

        InterviewMemory started = InterviewMemories.withSectionStarted(memory, Section.CODING, T0.plusSeconds(60));

        assertThat(started.sectionProgress()).containsEntry(Section.CODING, new SectionProgress(0, T0.plusSeconds(60)));
        assertThat(started.sectionProgress()).containsEntry(Section.QUESTIONING, memory.sectionProgress().get(Section.QUESTIONING));
        assertThat(started).usingRecursiveComparison().ignoringFields("sectionProgress").isEqualTo(memory);
        assertThat(started.pendingStep()).isNotNull().isEqualTo(memory.pendingStep());
    }

    @Test
    void startingASectionAgainNeverResetsItsClock() {
        InterviewMemory once = InterviewMemories.withSectionStarted(InterviewMemory.empty(), Section.QUESTIONING, T0);

        InterviewMemory twice = InterviewMemories.withSectionStarted(once, Section.QUESTIONING, T0.plusSeconds(500));

        assertThat(twice).isSameAs(once);
        assertThat(twice.sectionProgress().get(Section.QUESTIONING).startedAt()).isEqualTo(T0);
    }

    @Test
    void theInputMemoryIsNotChanged() {
        InterviewMemory empty = InterviewMemory.empty();

        InterviewMemories.withSectionStarted(empty, Section.INTRODUCTION, T0);

        assertThat(empty.sectionProgress()).isEmpty();
        assertThat(empty.pendingStep()).isNull();
    }
}
