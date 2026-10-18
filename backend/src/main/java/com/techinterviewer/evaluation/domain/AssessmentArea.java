package com.techinterviewer.evaluation.domain;

import com.techinterviewer.interview.domain.QuestionType;
import com.techinterviewer.interview.domain.Section;
import org.jspecify.annotations.Nullable;

/**
 * What the report assesses separately (AI_ARCHITECTURE.md section 2.15), so that strong project talk cannot hide weak
 * fundamentals and the reader can see what each part of the score rests on.
 *
 * <ul>
 *   <li>FUNDAMENTALS: CONCEPT and INTERNALS questions and their follow-ups;
 *   <li>PRACTICAL: SCENARIO questions;
 *   <li>PROBLEM_SOLVING: DEBUGGING questions, coding problems and system design;
 *   <li>PROJECT: EXPERIENCE questions (from the résumé or from what the candidate said) and claim probes;
 *   <li>COMMUNICATION: not a kind of question but the communication rating of every answer that has one.
 * </ul>
 */
public enum AssessmentArea {
    FUNDAMENTALS,
    PRACTICAL,
    PROBLEM_SOLVING,
    PROJECT,
    COMMUNICATION;

    /**
     * The area an answered question counts in; null for the introduction, which only counts in COMMUNICATION. A questioning question
     * asked before types existed counts as FUNDAMENTALS, or PROJECT when it was anchored on the résumé.
     *
     * @param questionType   the question's type (a follow-up's is its thread's, EXPERIENCE for a claim probe); null before types existed
     * @param resumeAnchored the question was anchored on a résumé experience
     */
    public static @Nullable AssessmentArea of(Section section, @Nullable QuestionType questionType, boolean resumeAnchored) {
        return switch (section) {
            case INTRODUCTION -> null;
            case CODING, SYSTEM_DESIGN -> PROBLEM_SOLVING;
            case QUESTIONING -> {
                if (questionType == null) {
                    yield resumeAnchored ? PROJECT : FUNDAMENTALS;
                }
                yield switch (questionType) {
                    case CONCEPT, INTERNALS -> FUNDAMENTALS;
                    case SCENARIO -> PRACTICAL;
                    case DEBUGGING -> PROBLEM_SOLVING;
                    case EXPERIENCE -> PROJECT;
                };
            }
        };
    }
}
