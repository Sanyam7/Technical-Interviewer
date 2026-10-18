package com.techinterviewer.interview.engine;

import com.techinterviewer.common.validation.Require;
import com.techinterviewer.interview.domain.InterviewMemory;
import com.techinterviewer.interview.domain.InterviewPlan;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.SectionPlan;
import com.techinterviewer.interview.domain.SectionProgress;
import java.time.Duration;
import java.time.Instant;

/**
 * Time rules (ARCHITECTURE.md sections 4.4 and 5, decision D8). The deadline is checked lazily against the clock the
 * caller passes in. Section time budgets are soft: a question in progress always finishes, a budget only decides
 * when the section stops asking <em>new</em> questions. A new question is only started when it can still be answered
 * ({@link #timeToStart}), and a section never takes the time planned for the sections after it.
 */
public final class DeadlinePolicy {

    /** A technical question needs at least this long to be answered. */
    public static final Duration MIN_TIME_FOR_QUESTION = Duration.ofMinutes(2);
    /** A coding or design problem needs at least this long. */
    public static final Duration MIN_TIME_FOR_PROBLEM = Duration.ofMinutes(8);
    public static final Duration MIN_CLOSING_WINDOW = Duration.ofMinutes(5);

    private DeadlinePolicy() {
    }

    /** The moment the interview ends: its start plus the configured duration. */
    public static Instant deadline(Instant startedAt, int durationMinutes) {
        Require.nonNull(startedAt, "startedAt");
        Require.between(durationMinutes, 1, Integer.MAX_VALUE, "durationMinutes");
        return startedAt.plus(Duration.ofMinutes(durationMinutes));
    }

    /** The interview has not expired at the deadline instant itself, only after it. */
    public static boolean isExpired(Instant now, Instant deadline) {
        return now.isAfter(deadline);
    }

    /** RUNNING up to the deadline, WITHIN_GRACE up to and including {@code deadline + grace}, EXPIRED afterwards. */
    public static DeadlineStatus status(Instant now, Instant deadline, Duration grace) {
        Require.nonNull(grace, "grace");
        if (grace.isNegative()) {
            throw new IllegalArgumentException("grace must not be negative");
        }
        if (!isExpired(now, deadline)) {
            return DeadlineStatus.RUNNING;
        }
        return now.isAfter(deadline.plus(grace)) ? DeadlineStatus.EXPIRED : DeadlineStatus.WITHIN_GRACE;
    }

    /** Time until the deadline; zero once it has passed. */
    public static Duration remaining(Instant now, Instant deadline) {
        Duration left = Duration.between(now, deadline);
        return left.isNegative() ? Duration.ZERO : left;
    }

    /**
     * Time the section has left for new questions: its remaining soft budget (the whole budget before its first question),
     * but never the time planned for the sections after it, which stays reserved for them. Zero at the earliest.
     *
     * <p>On schedule the reserve changes nothing (the plan's budgets add up to less than the duration). When an earlier
     * section ran over, the section that is running pays for it, not the ones after it: a real interviewer cuts the
     * questions short to keep the coding exercise, not the other way round.
     */
    public static Duration timeLeftInSection(InterviewMemory memory, InterviewPlan plan, Section section, Instant now, Instant deadline) {
        SectionPlan sectionPlan = plan.section(section)
                .orElseThrow(() -> new IllegalArgumentException("the plan has no " + section + " section"));
        SectionProgress progress = memory.sectionProgress().get(section);
        Duration budget = progress == null
                ? Duration.ofMinutes(sectionPlan.minutes())
                : Duration.between(now, progress.startedAt().plus(Duration.ofMinutes(sectionPlan.minutes())));
        Duration available = remaining(now, deadline).minus(reservedAfter(plan, section));
        Duration left = budget.compareTo(available) <= 0 ? budget : available;
        return left.isNegative() ? Duration.ZERO : left;
    }

    /**
     * The time a new root question of the section must still have to be worth asking: 2 minutes for a technical question,
     * 8 for a coding or design problem, or the section's whole budget when the plan gave it less.
     */
    public static Duration timeToStart(InterviewPlan plan, Section section) {
        Duration needed = switch (section) {
            case QUESTIONING -> MIN_TIME_FOR_QUESTION;
            case CODING, SYSTEM_DESIGN -> MIN_TIME_FOR_PROBLEM;
            case INTRODUCTION -> Duration.ZERO;
        };
        Duration budget = Duration.ofMinutes(plan.section(section).map(SectionPlan::minutes).orElse(0));
        return needed.compareTo(budget) <= 0 ? needed : budget;
    }

    /** Whether the section can still start a root question now (its first, or another one). */
    public static boolean canStartQuestion(InterviewMemory memory, InterviewPlan plan, Section section, Instant now, Instant deadline) {
        return timeLeftInSection(memory, plan, section, now, deadline).compareTo(timeToStart(plan, section)) >= 0;
    }

    /** The last stretch of the interview: 10 % of the planned minutes, at least {@link #MIN_CLOSING_WINDOW}. */
    public static Duration closingWindow(InterviewPlan plan) {
        Duration tenth = Duration.ofMinutes(plan.totalMinutes()).dividedBy(10);
        return tenth.compareTo(MIN_CLOSING_WINDOW) >= 0 ? tenth : MIN_CLOSING_WINDOW;
    }

    /** True in the closing window: only the follow-ups that close a gap are still asked. */
    public static boolean isClosing(InterviewPlan plan, Instant now, Instant deadline) {
        return remaining(now, deadline).compareTo(closingWindow(plan)) <= 0;
    }

    private static Duration reservedAfter(InterviewPlan plan, Section section) {
        int minutes = plan.sections().stream()
                .filter(later -> later.section().ordinal() > section.ordinal())
                .mapToInt(SectionPlan::minutes)
                .sum();
        return Duration.ofMinutes(minutes);
    }
}
