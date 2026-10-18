package com.techinterviewer.interview.engine;

import com.techinterviewer.evaluation.domain.EvaluationFlag;
import com.techinterviewer.evaluation.domain.KeyPointAssessment;
import com.techinterviewer.evaluation.domain.KeyPointStatus;
import com.techinterviewer.interview.domain.DifficultyBand;
import com.techinterviewer.interview.domain.InterviewPlan;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.SectionPlan;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Shared builders for the engine tests. Ids are derived from small numbers so tests can name the questions. */
final class EngineFixtures {

    static final Instant T0 = Instant.parse("2026-10-06T10:00:00Z");
    static final DifficultyBand BAND = new DifficultyBand(3, 2, 4);
    static final String CONCURRENCY = "java/concurrency";
    static final String INDEXING = "postgresql/indexing";

    private EngineFixtures() {
    }

    static UUID question(int n) {
        return new UUID(1, n);
    }

    static UUID answer(int n) {
        return new UUID(2, n);
    }

    static Map<String, TopicRange> ranges() {
        return Map.of(CONCURRENCY, new TopicRange(1, 5), INDEXING, new TopicRange(1, 5));
    }

    /** Full loop: introduction 4 min, questioning 24 min and 6 slots, coding 16 min and 2 slots, design 14 min. */
    static InterviewPlan fullLoop() {
        return new InterviewPlan(1, List.of(
                new SectionPlan(Section.INTRODUCTION, 4, 1),
                new SectionPlan(Section.QUESTIONING, 24, 6, Map.of(CONCURRENCY, 3, INDEXING, 3)),
                new SectionPlan(Section.CODING, 16, 2),
                new SectionPlan(Section.SYSTEM_DESIGN, 14, 1)), BAND, 2);
    }

    static InterviewPlan withDesignProbes(InterviewPlan plan, int probes) {
        return new InterviewPlan(plan.version(), plan.sections(), plan.difficulty(), plan.maxFollowUpDepth(), probes);
    }

    static InterviewPlan technicalOnly() {
        return new InterviewPlan(1, List.of(
                new SectionPlan(Section.INTRODUCTION, 4, 1),
                new SectionPlan(Section.QUESTIONING, 24, 3, Map.of(CONCURRENCY, 2, INDEXING, 1))), BAND, 2);
    }

    static InterviewPlan codingOnly() {
        return new InterviewPlan(1, List.of(
                new SectionPlan(Section.INTRODUCTION, 4, 1), new SectionPlan(Section.CODING, 40, 2)), BAND, 2);
    }

    static InterviewPlan designOnly() {
        return new InterviewPlan(1, List.of(
                new SectionPlan(Section.INTRODUCTION, 4, 1), new SectionPlan(Section.SYSTEM_DESIGN, 40, 1)), BAND, 2);
    }

    static AnsweredQuestion intro(Instant askedAt) {
        return new AnsweredQuestion(answer(1), question(1), QuestionKind.INTRO, Section.INTRODUCTION, null, 3, 0, askedAt, List.of());
    }

    static AnsweredQuestion technical(int n, String topic, int difficulty, Instant askedAt, String... keyPointIds) {
        return new AnsweredQuestion(answer(n), question(n), QuestionKind.TECHNICAL, Section.QUESTIONING, topic, difficulty, 0, askedAt,
                List.of(keyPointIds));
    }

    static AnsweredQuestion followUp(int n, Section section, String topic, int difficulty, int depth, Instant askedAt, String... keyPointIds) {
        return new AnsweredQuestion(answer(n), question(n), QuestionKind.FOLLOW_UP, section, topic, difficulty, depth, askedAt,
                List.of(keyPointIds));
    }

    static AnsweredQuestion coding(int n, int difficulty, Instant askedAt, String... keyPointIds) {
        return new AnsweredQuestion(answer(n), question(n), QuestionKind.CODING, Section.CODING, null, difficulty, 0, askedAt,
                List.of(keyPointIds));
    }

    static AnsweredQuestion design(int n, int difficulty, Instant askedAt, String... keyPointIds) {
        return new AnsweredQuestion(answer(n), question(n), QuestionKind.SYSTEM_DESIGN, Section.SYSTEM_DESIGN, null, difficulty, 0, askedAt,
                List.of(keyPointIds));
    }

    static KeyPointAssessment keyPoint(String id, KeyPointStatus status) {
        return new KeyPointAssessment(id, status, status == KeyPointStatus.MISSING ? null : "quote", status != KeyPointStatus.MISSING);
    }

    static EvaluationOutcome scored(double score) {
        return EvaluationOutcome.scored(score, "Summary of the answer.", List.of(), List.of(), Set.of());
    }

    static EvaluationOutcome scored(double score, List<KeyPointAssessment> keyPoints, List<String> claims, Set<EvaluationFlag> flags) {
        return EvaluationOutcome.scored(score, "Summary of the answer.", keyPoints, claims, flags);
    }

    static List<KeyPointAssessment> keyPoints(Object... idsAndStatuses) {
        List<KeyPointAssessment> list = new ArrayList<>();
        for (int i = 0; i < idsAndStatuses.length; i += 2) {
            list.add(keyPoint((String) idsAndStatuses[i], (KeyPointStatus) idsAndStatuses[i + 1]));
        }
        return list;
    }
}
