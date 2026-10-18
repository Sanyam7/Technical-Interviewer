package com.techinterviewer.interview.engine;

import com.techinterviewer.evaluation.domain.KeyPointAssessment;
import com.techinterviewer.evaluation.domain.KeyPointStatus;
import com.techinterviewer.interview.domain.AnswerSummary;
import com.techinterviewer.interview.domain.Claim;
import com.techinterviewer.interview.domain.DifficultyBand;
import com.techinterviewer.interview.domain.InterviewMemory;
import com.techinterviewer.interview.domain.Misconception;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.SectionProgress;
import com.techinterviewer.interview.domain.TopicStat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Applies the outcome of one answer to the interview memory (AI_ARCHITECTURE.md section 3). Nothing here calls a model:
 * the evaluator already returned the one-line summary and the claims.
 *
 * <ul>
 *   <li><b>Pending step:</b> the answer consumes whatever step was waiting to be generated, so the result has none;
 *       {@link TurnDecider} sets the next one.
 *   <li><b>Section progress:</b> a root question uses one slot of its section; the first answer of a section records
 *       when its first question was asked.
 *   <li><b>Topic stats</b> (questions with a topic): {@code asked} counts root questions only, because the plan's
 *       quotas count root questions; the moving average, level and last score follow every scored answer, root or
 *       follow-up. A skipped root question counts as a score of 0, a skipped follow-up changes nothing. A pending
 *       evaluation counts as asked but carries no evidence: a new topic is seeded at the neutral score and level
 *       and average stay where they were.
 *   <li><b>Doubt:</b> a score from an evaluation in doubt ({@link EvaluationOutcome#reliable()} false) enters the averages but moves
 *       neither the topic's nor the candidate's level.
 *   <li><b>Overall</b> (every technical answer, the introduction excluded): the same moving average and level over all
 *       topics, with the same rules for skips and pending evaluations. A topic's first level is the level its question
 *       was asked at, which for a new topic is the candidate's current level.
 *   <li><b>Summary, claims, flags, demonstrated key points, misconceptions</b> come from a scored answer; a skipped root
 *       question leaves a summary of score 0. Misconceptions are the claims of the factual errors, the latest
 *       {@value InterviewMemory#MAX_MISCONCEPTIONS} kept. A repeated answer is counted under {@value #REPEATED_ANSWER_FLAG}.
 * </ul>
 */
public final class MemoryUpdater {

    static final String SKIPPED_SUMMARY = "Skipped by the candidate.";

    /** The memory flag that counts answers repeating an earlier answer (not an evaluation flag: no model decides it). */
    public static final String REPEATED_ANSWER_FLAG = "REPEATED_ANSWER";

    private MemoryUpdater() {
    }

    public static InterviewMemory apply(
            InterviewMemory memory, AnsweredQuestion answered, EvaluationOutcome outcome, DifficultyBand band) {
        return apply(memory, answered, outcome, band, false);
    }

    public static InterviewMemory apply(
            InterviewMemory memory, AnsweredQuestion answered, EvaluationOutcome outcome, DifficultyBand band, boolean repeatedAnswer) {
        Map<String, TopicStat> topicStats = new LinkedHashMap<>(memory.topicStats());
        List<String> demonstrated = new ArrayList<>(memory.demonstratedKeyPoints());
        List<Claim> claims = new ArrayList<>(memory.candidateClaims());
        List<AnswerSummary> summaries = new ArrayList<>(memory.answerSummaries());
        Map<String, Integer> flags = new LinkedHashMap<>(memory.flags());

        Double effectiveScore = switch (outcome.status()) {
            case SCORED -> outcome.score();
            case SKIPPED -> answered.isRoot() ? Double.valueOf(0) : null;
            case PENDING -> null;
        };
        if (answered.topic() != null) {
            updateTopic(topicStats, answered, effectiveScore, outcome.status() == EvaluationOutcome.Status.PENDING, outcome.reliable(), band);
        }
        TopicStat overall = memory.overall();
        if (answered.kind() != QuestionKind.INTRO && effectiveScore != null) {
            overall = DifficultyAdapter.next(overall, memory.currentLevel(band.start()), effectiveScore,
                    (overall == null ? 0 : overall.asked()) + 1, band, outcome.reliable());
        }
        List<Misconception> misconceptions = new ArrayList<>(memory.misconceptions());
        if (effectiveScore != null) {
            String summary = outcome.summary() == null ? SKIPPED_SUMMARY : outcome.summary();
            summaries.add(new AnswerSummary(answered.questionId(), answered.topic(), summary, effectiveScore));
        }
        if (outcome.status() == EvaluationOutcome.Status.SCORED) {
            addClaims(claims, outcome.newClaims(), answered);
            addDemonstratedKeyPoints(demonstrated, outcome.keyPoints(), answered);
            outcome.flags().forEach(flag -> flags.merge(flag.name(), 1, Integer::sum));
            addMisconceptions(misconceptions, outcome.misconceptions(), answered);
        }
        if (repeatedAnswer) {
            flags.merge(REPEATED_ANSWER_FLAG, 1, Integer::sum);
        }
        return new InterviewMemory(topicStats, demonstrated, claims, summaries, flags, withSectionProgress(memory, answered), null,
                overall, misconceptions, memory.resumeItemsAsked(), memory.stageQuestions());
    }

    /** Marks the first unprobed claim with this text as probed (a follow-up was decided for it). */
    public static InterviewMemory markClaimProbed(InterviewMemory memory, String claimText) {
        List<Claim> claims = new ArrayList<>(memory.candidateClaims());
        for (int i = 0; i < claims.size(); i++) {
            if (!claims.get(i).probed() && claims.get(i).text().equals(claimText)) {
                claims.set(i, claims.get(i).asProbed());
                return memory.withCandidateClaims(claims);
            }
        }
        throw new IllegalArgumentException("no unprobed claim '" + claimText + "' in the memory");
    }

    private static void updateTopic(
            Map<String, TopicStat> topicStats,
            AnsweredQuestion answered,
            @Nullable Double score,
            boolean pending,
            boolean reliable,
            DifficultyBand band) {
        String topic = answered.topic();
        TopicStat previous = topicStats.get(topic);
        int rootCount = answered.isRoot() ? 1 : 0;
        int firstLevel = band.clamp(answered.difficulty());
        if (score != null) {
            topicStats.put(topic, DifficultyAdapter.next(previous, previous == null ? firstLevel : previous.level(), score,
                    (previous == null ? 0 : previous.asked()) + rootCount, band, reliable));
        } else if (pending && answered.isRoot()) {
            topicStats.put(topic, previous == null
                    ? new TopicStat(1, DifficultyAdapter.NEUTRAL_SCORE, firstLevel, DifficultyAdapter.NEUTRAL_SCORE)
                    : new TopicStat(previous.asked() + 1, previous.ema(), previous.level(), previous.lastScore()));
        }
    }

    private static void addClaims(List<Claim> claims, List<String> newClaims, AnsweredQuestion answered) {
        Set<String> known = claims.stream().map(claim -> normalise(claim.text())).collect(Collectors.toCollection(HashSet::new));
        for (String text : newClaims) {
            if (known.add(normalise(text))) {
                claims.add(new Claim(text, false, answered.answerId()));
            }
        }
    }

    private static void addMisconceptions(List<Misconception> misconceptions, List<String> claims, AnsweredQuestion answered) {
        Set<String> known = misconceptions.stream().map(m -> normalise(m.claim())).collect(Collectors.toCollection(HashSet::new));
        for (String claim : claims) {
            String text = claim.strip();
            if (text.length() > Misconception.MAX_LENGTH) {
                text = text.substring(0, Misconception.MAX_LENGTH - 3).strip() + "...";
            }
            if (known.add(normalise(text))) {
                misconceptions.add(new Misconception(text, answered.topic()));
            }
        }
        while (misconceptions.size() > InterviewMemory.MAX_MISCONCEPTIONS) {
            misconceptions.removeFirst();
        }
    }

    private static void addDemonstratedKeyPoints(
            List<String> demonstrated, List<KeyPointAssessment> keyPoints, AnsweredQuestion answered) {
        String scope = answered.topic() == null ? answered.section().name() : answered.topic();
        for (KeyPointAssessment keyPoint : keyPoints) {
            String entry = scope + ":" + keyPoint.keyPointId();
            if (keyPoint.status() == KeyPointStatus.COVERED && !demonstrated.contains(entry)) {
                demonstrated.add(entry);
            }
        }
    }

    private static Map<Section, SectionProgress> withSectionProgress(InterviewMemory memory, AnsweredQuestion answered) {
        Map<Section, SectionProgress> progress = new LinkedHashMap<>(memory.sectionProgress());
        SectionProgress existing = progress.get(answered.section());
        int slotsUsed = (existing == null ? 0 : existing.slotsUsed()) + (answered.isRoot() ? 1 : 0);
        progress.put(answered.section(), new SectionProgress(slotsUsed, existing == null ? answered.askedAt() : existing.startedAt()));
        return progress;
    }

    private static String normalise(String text) {
        return text.strip().toLowerCase(Locale.ROOT);
    }
}
