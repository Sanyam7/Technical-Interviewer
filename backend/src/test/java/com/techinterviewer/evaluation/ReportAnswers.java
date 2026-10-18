package com.techinterviewer.evaluation;

import com.techinterviewer.evaluation.domain.EvaluationFlag;
import com.techinterviewer.interview.domain.EvaluationFailure;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.Section;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Builders for report test data; question ids are derived from the sequence number so tests can name them. */
final class ReportAnswers {

    private ReportAnswers() {
    }

    static UUID id(int sequenceNo) {
        return new UUID(0, sequenceNo);
    }

    static ReportAnswer intro(int seq, double score) {
        return new ReportAnswer(id(seq), id(seq), seq, QuestionKind.INTRO, Section.INTRODUCTION, null, 3, false, score, Set.of());
    }

    static ReportAnswer skippedIntro(int seq) {
        return new ReportAnswer(id(seq), id(seq), seq, QuestionKind.INTRO, Section.INTRODUCTION, null, 3, true, null, Set.of());
    }

    static ReportAnswer technical(int seq, String topic, int difficulty, double score, EvaluationFlag... flags) {
        return new ReportAnswer(id(seq), id(seq), seq, QuestionKind.TECHNICAL, Section.QUESTIONING, topic, difficulty, false, score,
                flagSet(flags));
    }

    static ReportAnswer skippedTechnical(int seq, String topic, int difficulty) {
        return new ReportAnswer(id(seq), id(seq), seq, QuestionKind.TECHNICAL, Section.QUESTIONING, topic, difficulty, true, null, Set.of());
    }

    static ReportAnswer followUp(int seq, int rootSeq, Section section, String topic, int difficulty, double score, EvaluationFlag... flags) {
        return new ReportAnswer(id(seq), id(rootSeq), seq, QuestionKind.FOLLOW_UP, section, topic, difficulty, false, score, flagSet(flags));
    }

    static ReportAnswer skippedFollowUp(int seq, int rootSeq, Section section, String topic, int difficulty) {
        return new ReportAnswer(id(seq), id(rootSeq), seq, QuestionKind.FOLLOW_UP, section, topic, difficulty, true, null, Set.of());
    }

    static ReportAnswer coding(int seq, int difficulty, double score, EvaluationFlag... flags) {
        return new ReportAnswer(id(seq), id(seq), seq, QuestionKind.CODING, Section.CODING, null, difficulty, false, score, flagSet(flags));
    }

    static ReportAnswer skippedCoding(int seq, int difficulty) {
        return new ReportAnswer(id(seq), id(seq), seq, QuestionKind.CODING, Section.CODING, null, difficulty, true, null, Set.of());
    }

    static ReportAnswer design(int seq, int difficulty, double score) {
        return new ReportAnswer(id(seq), id(seq), seq, QuestionKind.SYSTEM_DESIGN, Section.SYSTEM_DESIGN, null, difficulty, false, score,
                Set.of());
    }

    static ReportAnswer skippedDesign(int seq, int difficulty) {
        return new ReportAnswer(id(seq), id(seq), seq, QuestionKind.SYSTEM_DESIGN, Section.SYSTEM_DESIGN, null, difficulty, true, null,
                Set.of());
    }

    private static Set<EvaluationFlag> flagSet(EvaluationFlag[] flags) {
        Set<EvaluationFlag> set = EnumSet.noneOf(EvaluationFlag.class);
        set.addAll(List.of(flags));
        return set;
    }

    // ---- answers that could not be assessed -----------------------------------------------------------------------------

    static ReportAnswer unassessableIntro(int seq) {
        return new ReportAnswer(id(seq), id(seq), seq, QuestionKind.INTRO, Section.INTRODUCTION, null, 3, false, null, Set.of(),
                EvaluationFailure.PROVIDER_FAILURE);
    }

    static ReportAnswer unassessableTechnical(int seq, String topic, int difficulty) {
        return unassessableTechnical(seq, topic, difficulty, EvaluationFailure.PROVIDER_FAILURE);
    }

    static ReportAnswer unassessableTechnical(int seq, String topic, int difficulty, EvaluationFailure reason) {
        return new ReportAnswer(id(seq), id(seq), seq, QuestionKind.TECHNICAL, Section.QUESTIONING, topic, difficulty, false, null, Set.of(), reason);
    }

    static ReportAnswer unassessableFollowUp(int seq, int rootSeq, Section section, String topic, int difficulty) {
        return new ReportAnswer(id(seq), id(rootSeq), seq, QuestionKind.FOLLOW_UP, section, topic, difficulty, false, null, Set.of(),
                EvaluationFailure.INVALID_OUTPUT);
    }

    static ReportAnswer unassessableCoding(int seq, int difficulty) {
        return new ReportAnswer(id(seq), id(seq), seq, QuestionKind.CODING, Section.CODING, null, difficulty, false, null, Set.of(),
                EvaluationFailure.SAFETY_BLOCK);
    }

    static ReportAnswer unassessableDesign(int seq, int difficulty) {
        return new ReportAnswer(id(seq), id(seq), seq, QuestionKind.SYSTEM_DESIGN, Section.SYSTEM_DESIGN, null, difficulty, false, null, Set.of(),
                EvaluationFailure.PROVIDER_FAILURE);
    }
}
