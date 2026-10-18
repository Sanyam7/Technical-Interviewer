package com.techinterviewer.interview.engine;

import com.techinterviewer.common.validation.Require;
import com.techinterviewer.interview.domain.DifficultyBand;
import com.techinterviewer.interview.domain.InterviewMemory;
import com.techinterviewer.interview.domain.TopicStat;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Picks the topic of the next questioning question under the plan's topic quotas.
 *
 * <p>Candidates are the topics whose quota is not used up yet (if every quota is used, which only happens when
 * the plan has more slots than quotas, all quota topics qualify). Among them the choice is, in order: the skill
 * with the fewest answers so far (the least confident skill, so skills take turns and a skill nobody has asked about
 * comes first: when time runs short the unanswered areas are the most valuable questions left), the topic asked least,
 * the topic with the most quota left, and finally the smaller key, so the result is deterministic. The quotas cap every
 * topic, so no topic can take the session.
 *
 * <p>A caller may name <i>preferred</i> topics (the project stage prefers topics it has a résumé experience to discuss for,
 * AI_ARCHITECTURE.md section 2.15): when one of the candidates is preferred, the choice is made among the preferred candidates
 * only, by the same order.
 *
 * <p>The question is asked at the topic's current level, or at the candidate's current level when the topic is new
 * ({@link DifficultyAdapter#startLevel}), moved into the topic's own difficulty range.
 */
public final class TopicSelector {

    private TopicSelector() {
    }

    /**
     * @param quotas {@code skill/topic} to the number of root questions the plan gives it; not empty
     * @param ranges difficulty range of every topic in {@code quotas}
     * @param memory the interview memory (asked counts and levels per topic)
     * @param band   the interview's difficulty band
     * @throws IllegalArgumentException when there are no quotas or a quota topic has no range
     */
    public static TopicChoice next(
            Map<String, Integer> quotas, Map<String, TopicRange> ranges, InterviewMemory memory, DifficultyBand band) {
        return next(quotas, ranges, memory, band, topic -> false);
    }

    /** As {@link #next(Map, Map, InterviewMemory, DifficultyBand)}, choosing among the {@code preferred} candidates when there are any. */
    public static TopicChoice next(
            Map<String, Integer> quotas, Map<String, TopicRange> ranges, InterviewMemory memory, DifficultyBand band, Predicate<String> preferred) {
        Require.nonNull(quotas, "quotas");
        if (quotas.isEmpty()) {
            throw new IllegalArgumentException("the plan has no topic quotas to choose from");
        }
        Map<String, TopicStat> stats = memory.topicStats();
        ToIntFunction<String> asked = topic -> stats.containsKey(topic) ? stats.get(topic).asked() : 0;
        ToIntFunction<String> skillAnswers = topic -> stats.entrySet().stream()
                .filter(entry -> skillOf(entry.getKey()).equals(skillOf(topic)))
                .mapToInt(entry -> entry.getValue().asked())
                .sum();
        ToIntFunction<String> remaining = topic -> quotas.get(topic) - asked.applyAsInt(topic);

        boolean anyLeft = quotas.keySet().stream().anyMatch(topic -> remaining.applyAsInt(topic) > 0);
        List<String> candidates = quotas.keySet().stream()
                .filter(topic -> !anyLeft || remaining.applyAsInt(topic) > 0)
                .toList();
        List<String> favoured = candidates.stream().filter(preferred).toList();
        String chosen = (favoured.isEmpty() ? candidates : favoured).stream()
                .min(Comparator.comparingInt(skillAnswers)
                        .thenComparingInt(asked)
                        .thenComparing(Comparator.comparingInt(remaining).reversed())
                        .thenComparing(Comparator.naturalOrder()))
                .orElseThrow();

        TopicRange range = ranges.get(chosen);
        if (range == null) {
            throw new IllegalArgumentException("no difficulty range for topic " + chosen);
        }
        int level = stats.containsKey(chosen) ? stats.get(chosen).level() : DifficultyAdapter.startLevel(memory, band);
        return new TopicChoice(chosen, range.clamp(band.clamp(level)));
    }

    static String skillOf(String topicKey) {
        int slash = topicKey.indexOf('/');
        return slash < 0 ? topicKey : topicKey.substring(0, slash);
    }
}
