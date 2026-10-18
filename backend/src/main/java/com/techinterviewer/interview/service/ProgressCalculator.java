package com.techinterviewer.interview.service;

import com.techinterviewer.interview.api.dto.ProgressResponse;
import com.techinterviewer.interview.domain.InterviewMemory;
import com.techinterviewer.interview.domain.InterviewPlan;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.SectionPlan;
import com.techinterviewer.interview.engine.DeadlinePolicy;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The arithmetic behind the progress of an interview, kept apart from the queries that feed it so it can be tested
 * on plain values: which section the interview is in, how much time is left and how the counts of that section read.
 */
public final class ProgressCalculator {

    private ProgressCalculator() {
    }

    /**
     * The section the interview is in: the section of the state, for a follow-up the section its thread belongs to
     * ({@code resumeState}), and once the question phase is over the last section whose questions were started.
     * Empty before the interview was started (INIT, or ABANDONED without ever having started).
     *
     * @throws IllegalStateException for a FOLLOW_UP without a resume state
     */
    public static Optional<Section> sectionOf(InterviewState state, @Nullable InterviewState resumeState, InterviewMemory memory) {
        return switch (state) {
            case INIT -> Optional.empty();
            case INTRODUCTION, QUESTIONING, CODING, SYSTEM_DESIGN -> Optional.of(sectionOfState(state));
            case FOLLOW_UP -> {
                if (resumeState == null) {
                    throw new IllegalStateException("a follow-up has no section to return to");
                }
                yield Optional.of(sectionOfState(resumeState));
            }
            case FINAL_EVALUATION, COMPLETED, ABANDONED -> memory.sectionProgress().keySet().stream().max(Comparator.naturalOrder());
        };
    }

    /**
     * Seconds left until the deadline while questions are asked (never negative), the whole duration before the start
     * and 0 once the question phase is over.
     *
     * @throws IllegalStateException when an interview that asks questions has no deadline
     */
    public static long remainingSeconds(InterviewState state, @Nullable Instant deadlineAt, int durationMinutes, Instant now) {
        if (state == InterviewState.INIT) {
            return durationMinutes * 60L;
        }
        if (!state.acceptsAnswers()) {
            return 0;
        }
        if (deadlineAt == null) {
            throw new IllegalStateException("an interview in state " + state + " has no deadline");
        }
        return DeadlinePolicy.remaining(now, deadlineAt).toSeconds();
    }

    /**
     * @param section       the section from {@link #sectionOf}, if any
     * @param answeredRoots how many main questions of that section were answered or skipped
     * @throws IllegalStateException when the section is not part of the plan
     */
    public static ProgressResponse progress(
            Optional<Section> section, InterviewPlan plan, int answeredRoots, long remainingSeconds) {
        List<SectionPlan> sections = plan.sections();
        if (section.isEmpty()) {
            return new ProgressResponse(null, null, sections.size(), 0, null, remainingSeconds);
        }
        SectionPlan sectionPlan = plan.section(section.get())
                .orElseThrow(() -> new IllegalStateException("the plan has no " + section.get() + " section"));
        return new ProgressResponse(
                section.get(), sections.indexOf(sectionPlan), sections.size(), answeredRoots, sectionPlan.slots(), remainingSeconds);
    }

    private static Section sectionOfState(InterviewState state) {
        return Arrays.stream(Section.values())
                .filter(section -> section.state() == state)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(state + " is not the state of a section"));
    }
}
