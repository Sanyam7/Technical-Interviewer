package com.techinterviewer.evaluation;

import com.techinterviewer.common.validation.Require;
import com.techinterviewer.evaluation.ReportScores.Assessment;
import com.techinterviewer.evaluation.ReportScores.Coverage;
import com.techinterviewer.evaluation.ReportScores.IntegrityNote;
import com.techinterviewer.evaluation.ReportScores.SectionScore;
import com.techinterviewer.evaluation.ReportScores.SkillScore;
import com.techinterviewer.evaluation.ReportScores.TopicScore;
import com.techinterviewer.evaluation.domain.AssessmentArea;
import com.techinterviewer.evaluation.domain.Confidence;
import com.techinterviewer.evaluation.domain.EvaluationFlag;
import com.techinterviewer.evaluation.domain.UnassessableAnswer;
import com.techinterviewer.interview.domain.Section;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The deterministic part of the final report (AI_ARCHITECTURE.md section 2.8): overall, section, skill and topic
 * scores, confidence, coverage and integrity notes, computed from the evaluated answers without a model.
 *
 * <h2>Evidence</h2>
 * The scored unit is a <em>turn</em>: one answer with the score of its evaluation and the difficulty of its question.
 * <ul>
 *   <li>A skipped root question is a turn of score 0; a skipped follow-up adds nothing.
 *   <li>A system design thread is one turn: the evaluations of its answers are cumulative (AI_ARCHITECTURE.md 2.7), so
 *       the thread counts with the score of its last answered answer, or 0 when every answer in it was skipped. It
 *       carries the topic and difficulty of its root question.
 *   <li>In every other section, each answered turn, root or follow-up, counts on its own.
 * </ul>
 *
 * <h2>Unassessable answers</h2>
 * An answer that could not be assessed (the evaluator was unavailable, refused it or kept returning unusable output) is
 * <em>not</em> a bad answer: it is left out of every score, never counted as a zero, and its turn is not evidence. The turn
 * still counts in the <em>intended</em> assessment weight (the difficulty levels of the technical turns the candidate was
 * given), so {@link ReportScores.Assessment} says how much of the interview the scores stand on. A system design thread is
 * unassessable when its last answered answer is (the evaluations of a thread are cumulative, so an earlier one is not its
 * score). The mean, the confidence and the sections are computed from the assessable turns only, which is a re-normalisation
 * over the assessed weight. Whether the coverage is enough for a verdict is decided by the caller.
 *
 * <h2>Scores</h2>
 * Every score is the mean of the turns weighted by their difficulty level (a score at level 4 counts twice as much as
 * one at level 2), rounded to two decimals half up. The introduction is rated on communication only: it has a section
 * score but is not technical evidence, so it enters neither the overall, the skill nor the topic scores. When no
 * question except the introduction was answered (an interview ended early, or every question skipped) there is
 * <em>insufficient evidence</em>: no overall score, no skill or topic scores, and the confidence is LOW.
 *
 * <h2>Assessment areas (AI_ARCHITECTURE.md section 2.15)</h2>
 * Every turn but the introduction's counts in the area of its question ({@link ReportAnswer#area()}); a design thread in the area
 * of its root. An area's score is the difficulty-weighted mean of its scored turns, its confidence is computed like a skill's, and
 * its share of the overall is the part of the overall score's weight (the difficulty levels) its turns carry. COMMUNICATION is a
 * rating of every answer that has one, the introduction included: its score is the mean rating times 25, and it has no share.
 *
 * <h2>Confidence</h2>
 * By the number of turns behind a score: at least {@value #HIGH_CONFIDENCE_EVIDENCE} is HIGH, at least
 * {@value #MEDIUM_CONFIDENCE_EVIDENCE} is MEDIUM, one is LOW. When the turns disagree widely (best minus worst score
 * above {@value #WIDE_SPREAD_POINTS} points) the confidence drops one level, because a mean of very different
 * results says little.
 */
public final class ReportAggregator {

    public static final int HIGH_CONFIDENCE_EVIDENCE = 4;
    public static final int MEDIUM_CONFIDENCE_EVIDENCE = 2;
    public static final int WIDE_SPREAD_POINTS = 40;

    private static final BigDecimal SPREAD_LIMIT = BigDecimal.valueOf(WIDE_SPREAD_POINTS);

    private ReportAggregator() {
    }

    /** One scored turn. */
    private record Turn(Section section, @Nullable String topic, int difficulty, @Nullable BigDecimal score, boolean answered,
            @Nullable AssessmentArea area, boolean doubted) {

        @Nullable String skill() {
            return topic == null ? null : topic.substring(0, topic.indexOf('/'));
        }
    }

    /**
     * @param answers every answered or skipped question of the interview, in any order
     * @throws IllegalArgumentException when the answers contradict each other: duplicate question ids or sequence
     *                                  numbers, or a follow-up whose thread root is missing, is itself a follow-up,
     *                                  sits in another section or was asked after it
     */
    public static ReportScores aggregate(List<ReportAnswer> answers) {
        Require.nonNull(answers, "answers");
        List<ReportAnswer> ordered = answers.stream().sorted(Comparator.comparingInt(ReportAnswer::sequenceNo)).toList();
        requireConsistent(ordered);
        List<Turn> allTurns = turns(ordered);
        List<Turn> turns = allTurns.stream().filter(turn -> turn.score() != null).toList();
        List<Turn> technical = turns.stream().filter(turn -> turn.section() != Section.INTRODUCTION).toList();
        boolean sufficient = technical.stream().anyMatch(Turn::answered);

        List<Turn> evidence = sufficient ? technical : List.of();
        Double overall = sufficient ? weightedMean(technical) : null;
        Confidence overallConfidence = sufficient ? confidence(technical) : Confidence.LOW;
        List<Turn> sectionTurns = sufficient ? turns : turns.stream().filter(turn -> turn.section() == Section.INTRODUCTION).toList();
        return new ReportScores(
                overall,
                overallConfidence,
                sectionScores(sectionTurns),
                skillScores(evidence),
                topicScores(evidence),
                coverage(ordered),
                integrityNotes(ordered),
                assessment(allTurns, ordered),
                areas(evidence, allTurns, ordered));
    }

    /** One result per area, in {@link AssessmentArea} order; see the class comment. */
    private static List<ReportScores.AreaResult> areas(List<Turn> evidence, List<Turn> allTurns, List<ReportAnswer> ordered) {
        long totalWeight = evidence.stream().mapToLong(Turn::difficulty).sum();
        List<ReportScores.AreaResult> results = new ArrayList<>();
        for (AssessmentArea area : AssessmentArea.values()) {
            if (area == AssessmentArea.COMMUNICATION) {
                results.add(communication(ordered));
                continue;
            }
            List<Turn> group = evidence.stream().filter(turn -> turn.area() == area).toList();
            int doubted = (int) allTurns.stream().filter(turn -> turn.area() == area && turn.doubted()).count();
            if (group.isEmpty()) {
                results.add(new ReportScores.AreaResult(area, null, 0, null, null, doubted));
                continue;
            }
            long weight = group.stream().mapToLong(Turn::difficulty).sum();
            double share = BigDecimal.valueOf(weight).divide(BigDecimal.valueOf(totalWeight), 2, RoundingMode.HALF_UP).doubleValue();
            results.add(new ReportScores.AreaResult(area, weightedMean(group), group.size(), confidence(group), share, doubted));
        }
        return results;
    }

    /** The mean communication rating of the answers that have one, on the 0 to 100 scale; confidence by their number. */
    private static ReportScores.AreaResult communication(List<ReportAnswer> ordered) {
        List<ReportAnswer> rated = ordered.stream().filter(answer -> answer.communicationRating() != null).toList();
        int doubted = (int) rated.stream().filter(answer -> answer.flags().contains(EvaluationFlag.LOW_EVALUATOR_CONFIDENCE)).count();
        if (rated.isEmpty()) {
            return new ReportScores.AreaResult(AssessmentArea.COMMUNICATION, null, 0, null, null, doubted);
        }
        long ratings = rated.stream().mapToLong(answer -> Objects.requireNonNull(answer.communicationRating())).sum();
        double score = BigDecimal.valueOf(ratings * 25).divide(BigDecimal.valueOf(rated.size()), 2, RoundingMode.HALF_UP).doubleValue();
        Confidence confidence = rated.size() >= HIGH_CONFIDENCE_EVIDENCE
                ? Confidence.HIGH
                : rated.size() >= MEDIUM_CONFIDENCE_EVIDENCE ? Confidence.MEDIUM : Confidence.LOW;
        return new ReportScores.AreaResult(AssessmentArea.COMMUNICATION, score, rated.size(), confidence, null, doubted);
    }

    private static void requireConsistent(List<ReportAnswer> ordered) {
        Map<UUID, ReportAnswer> byQuestion = new HashMap<>();
        Set<Integer> sequenceNumbers = new HashSet<>();
        for (ReportAnswer answer : ordered) {
            if (byQuestion.put(answer.questionId(), answer) != null) {
                throw new IllegalArgumentException("question " + answer.questionId() + " appears twice");
            }
            if (!sequenceNumbers.add(answer.sequenceNo())) {
                throw new IllegalArgumentException("sequence number " + answer.sequenceNo() + " appears twice");
            }
        }
        for (ReportAnswer answer : ordered) {
            if (!answer.isFollowUp()) {
                continue;
            }
            ReportAnswer root = byQuestion.get(answer.threadId());
            if (root == null || root.isFollowUp() || root.section() != answer.section() || root.sequenceNo() >= answer.sequenceNo()) {
                throw new IllegalArgumentException("follow-up " + answer.questionId()
                        + " needs a root question of its section that was asked before it, in the answers");
            }
        }
    }

    /** Turns in asking order, the unassessable ones included (with a null score); see the class comment for what counts. */
    private static List<Turn> turns(List<ReportAnswer> ordered) {
        Map<UUID, ReportAnswer> lastAnswered = new HashMap<>();
        for (ReportAnswer answer : ordered) {
            if (!answer.skipped()) {
                lastAnswered.put(answer.threadId(), answer);
            }
        }
        List<Turn> turns = new ArrayList<>();
        for (ReportAnswer answer : ordered) {
            if (answer.section() == Section.SYSTEM_DESIGN) {
                if (!answer.isFollowUp()) {
                    ReportAnswer last = lastAnswered.get(answer.questionId());
                    turns.add(last == null
                            ? turn(answer, BigDecimal.ZERO, false, false)
                            : turn(answer, last.unassessable() == null ? score(last) : null, true, doubted(last)));
                }
            } else if (!answer.skipped()) {
                turns.add(turn(answer, answer.unassessable() == null ? score(answer) : null, true, doubted(answer)));
            } else if (!answer.isFollowUp()) {
                turns.add(turn(answer, BigDecimal.ZERO, false, false));
            }
        }
        return turns;
    }

    private static Turn turn(ReportAnswer answer, @Nullable BigDecimal score, boolean answered, boolean doubted) {
        return new Turn(answer.section(), answer.topic(), answer.difficulty(), score, answered, answer.area(), doubted);
    }

    private static boolean doubted(ReportAnswer answer) {
        return answer.flags().contains(EvaluationFlag.LOW_EVALUATOR_CONFIDENCE);
    }

    private static BigDecimal score(ReportAnswer answer) {
        return BigDecimal.valueOf(answer.score());
    }

    /** Intended and assessed weight of the technical turns, and the answers that could not be assessed. */
    private static Assessment assessment(List<Turn> allTurns, List<ReportAnswer> ordered) {
        int intended = 0;
        int assessed = 0;
        for (Turn turn : allTurns) {
            if (turn.section() == Section.INTRODUCTION) {
                continue;
            }
            intended += turn.difficulty();
            if (turn.score() != null) {
                assessed += turn.difficulty();
            }
        }
        List<UnassessableAnswer> unassessable = ordered.stream()
                .filter(answer -> answer.unassessable() != null)
                .map(answer -> new UnassessableAnswer(answer.questionId(), answer.sequenceNo(), answer.section(), answer.unassessable()))
                .toList();
        return new Assessment(intended, assessed, unassessable);
    }

    private static List<SectionScore> sectionScores(List<Turn> turns) {
        Map<Section, List<Turn>> bySection = new EnumMap<>(Section.class);
        turns.forEach(turn -> bySection.computeIfAbsent(turn.section(), section -> new ArrayList<>()).add(turn));
        List<SectionScore> scores = new ArrayList<>();
        bySection.forEach((section, group) -> scores.add(new SectionScore(section, weightedMean(group), group.size())));
        return scores;
    }

    private static List<SkillScore> skillScores(List<Turn> turns) {
        Map<String, List<Turn>> bySkill = new TreeMap<>();
        for (Turn turn : turns) {
            if (turn.skill() != null) {
                bySkill.computeIfAbsent(turn.skill(), skill -> new ArrayList<>()).add(turn);
            }
        }
        List<SkillScore> scores = new ArrayList<>();
        bySkill.forEach((skill, group) ->
                scores.add(new SkillScore(skill, weightedMean(group), confidence(group), group.size(), averageDifficulty(group))));
        return scores;
    }

    private static List<TopicScore> topicScores(List<Turn> turns) {
        Map<String, List<Turn>> byTopic = new TreeMap<>();
        for (Turn turn : turns) {
            if (turn.topic() != null) {
                byTopic.computeIfAbsent(turn.topic(), topic -> new ArrayList<>()).add(turn);
            }
        }
        List<TopicScore> scores = new ArrayList<>();
        byTopic.forEach((topic, group) -> scores.add(new TopicScore(topic, weightedMean(group), group.size(), averageDifficulty(group))));
        return scores;
    }

    private static double weightedMean(List<Turn> turns) {
        BigDecimal weighted = BigDecimal.ZERO;
        long weights = 0;
        for (Turn turn : turns) {
            weighted = weighted.add(Objects.requireNonNull(turn.score()).multiply(BigDecimal.valueOf(turn.difficulty())));
            weights += turn.difficulty();
        }
        return weighted.divide(BigDecimal.valueOf(weights), 2, RoundingMode.HALF_UP).doubleValue();
    }

    private static double averageDifficulty(List<Turn> turns) {
        long sum = turns.stream().mapToLong(Turn::difficulty).sum();
        return BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(turns.size()), 2, RoundingMode.HALF_UP).doubleValue();
    }

    private static Confidence confidence(List<Turn> turns) {
        Confidence byCount = turns.size() >= HIGH_CONFIDENCE_EVIDENCE
                ? Confidence.HIGH
                : turns.size() >= MEDIUM_CONFIDENCE_EVIDENCE ? Confidence.MEDIUM : Confidence.LOW;
        BigDecimal best = turns.stream().map(Turn::score).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElseThrow();
        BigDecimal worst = turns.stream().map(Turn::score).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElseThrow();
        if (best.subtract(worst).compareTo(SPREAD_LIMIT) <= 0) {
            return byCount;
        }
        return byCount == Confidence.HIGH ? Confidence.MEDIUM : Confidence.LOW;
    }

    private static Coverage coverage(List<ReportAnswer> ordered) {
        int answered = 0;
        int skipped = 0;
        int followUps = 0;
        Set<String> topics = new HashSet<>();
        Set<String> skills = new HashSet<>();
        for (ReportAnswer answer : ordered) {
            if (answer.skipped()) {
                skipped++;
            } else {
                answered++;
                if (answer.topic() != null) {
                    topics.add(answer.topic());
                    skills.add(answer.skill());
                }
            }
            if (answer.isFollowUp()) {
                followUps++;
            }
        }
        return new Coverage(answered, skipped, followUps, topics.size(), skills.size());
    }

    private static List<IntegrityNote> integrityNotes(List<ReportAnswer> ordered) {
        List<IntegrityNote> notes = new ArrayList<>();
        for (EvaluationFlag flag : EvaluationFlag.values()) {
            List<UUID> flagged = ordered.stream()
                    .filter(answer -> answer.flags().contains(flag))
                    .map(ReportAnswer::questionId)
                    .toList();
            if (!flagged.isEmpty()) {
                notes.add(new IntegrityNote(flag, flagged.size(), flagged, message(flag)));
            }
        }
        return notes;
    }

    private static String message(EvaluationFlag flag) {
        return switch (flag) {
            case INJECTION_SUSPECTED ->
                    "Part of an answer looked like an attempt to instruct the evaluator. Scores come from validated ratings and are not changed by it.";
            case EVAL_EVIDENCE_MISMATCH ->
                    "The automated evaluation quoted evidence that is not in the answer; the affected key points were counted as missing.";
            case OFF_TOPIC -> "An answer did not address the question that was asked.";
            case LANGUAGE_MISMATCH -> "Code was not written in the language chosen for the interview.";
            case LOW_EVALUATOR_CONFIDENCE ->
                    "The automated evaluator was not confident in its judgement of an answer (its own, uncalibrated estimate); "
                            + "a human review of that answer is recommended. The score was not changed.";
        };
    }
}
