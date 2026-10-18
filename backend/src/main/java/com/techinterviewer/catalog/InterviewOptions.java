package com.techinterviewer.catalog;

import com.techinterviewer.interview.domain.InterviewType;
import com.techinterviewer.interview.domain.Section;
import java.util.List;

/**
 * What an interview configuration may choose from, beyond the enums: the allowed durations, the coding languages
 * and the sections each interview type runs. It is the one source of these values: the catalog publishes them, and
 * the lanes that validate or plan an interview use the same constants.
 */
public final class InterviewOptions {

    /** Allowed interview durations. They equal the {@code ck_interview_configurations_duration} check. */
    public static final List<Integer> DURATIONS_MINUTES = List.of(15, 30, 45, 60, 90);

    /**
     * Languages a coding round can be done in. The database does not constrain them, so a new one needs no migration. Java,
     * JavaScript and TypeScript solutions are run against tests (when execution is on); Python and Go are reviewed by the AI only.
     */
    public static final List<String> CODING_LANGUAGES = List.of("java", "javascript", "typescript", "python", "go");

    private InterviewOptions() {
    }

    public static boolean isSupportedDuration(int minutes) {
        return DURATIONS_MINUTES.contains(minutes);
    }

    /** Exact, case-sensitive match against {@link #CODING_LANGUAGES}. */
    public static boolean isSupportedCodingLanguage(String language) {
        return CODING_LANGUAGES.contains(language);
    }

    /** The sections an interview of this type runs, in the order they run. */
    public static List<Section> sectionsOf(InterviewType type) {
        return switch (type) {
            case TECHNICAL -> List.of(Section.INTRODUCTION, Section.QUESTIONING);
            case CODING -> List.of(Section.INTRODUCTION, Section.CODING);
            case SYSTEM_DESIGN -> List.of(Section.INTRODUCTION, Section.SYSTEM_DESIGN);
            case FULL_LOOP -> List.of(Section.INTRODUCTION, Section.QUESTIONING, Section.CODING, Section.SYSTEM_DESIGN);
        };
    }
}
