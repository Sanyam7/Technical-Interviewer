package com.techinterviewer.interview.engine;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import com.techinterviewer.interview.domain.InterviewDifficulty;
import com.techinterviewer.interview.domain.InterviewMemory;
import com.techinterviewer.interview.domain.InterviewPlan;
import com.techinterviewer.interview.domain.Misconception;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.SectionPlan;
import com.techinterviewer.interview.domain.TopicStat;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * What the interviewer knows about the candidate at a given moment, in one place: derived from the memory, the plan and
 * the clock, never stored (the memory is the single source). The question and answer history behind it are the rows of
 * the interview; the memory holds their one-line summaries and the per-topic statistics.
 *
 * @param currentLevel       the candidate's current level, 1 to 5 (the band's start before any technical answer)
 * @param currentDifficulty  that level by name
 * @param overall            performance over all technical answers
 * @param topics             statistics of every topic asked so far (topics covered)
 * @param strengths          topics performing STRONG, best first
 * @param weaknesses         topics performing WEAK, weakest first
 * @param unansweredTopics   topics of the plan not asked yet
 * @param sectionsNotStarted question sections of the plan that have not asked anything yet
 * @param misconceptions     factual errors the candidate made, oldest first
 * @param answersScored      technical answers with evidence (scored, or a skipped main question at 0)
 * @param remaining          time until the deadline
 * @param closing            whether the interview is in its closing window
 */
public record CandidateState(
        int currentLevel,
        InterviewDifficulty currentDifficulty,
        TopicPerformance overall,
        Map<String, TopicStat> topics,
        List<String> strengths,
        List<String> weaknesses,
        List<String> unansweredTopics,
        List<Section> sectionsNotStarted,
        List<Misconception> misconceptions,
        int answersScored,
        Duration remaining,
        boolean closing) {

    public CandidateState {
        Require.nonNull(currentDifficulty, "currentDifficulty");
        Require.nonNull(overall, "overall");
        topics = Immutables.map(topics);
        strengths = Immutables.list(strengths);
        weaknesses = Immutables.list(weaknesses);
        unansweredTopics = Immutables.list(unansweredTopics);
        sectionsNotStarted = Immutables.list(sectionsNotStarted);
        misconceptions = Immutables.list(misconceptions);
        Require.nonNull(remaining, "remaining");
    }

    public static CandidateState of(InterviewMemory memory, InterviewPlan plan, Instant now, Instant deadline) {
        int level = DifficultyAdapter.startLevel(memory, plan.difficulty());
        Map<String, TopicStat> stats = memory.topicStats();
        Map<String, Integer> quotas = plan.section(Section.QUESTIONING).map(SectionPlan::topicQuotas).orElse(Map.of());
        return new CandidateState(
                level,
                InterviewDifficulty.ofLevel(level),
                TopicPerformance.of(memory.overall()),
                stats,
                stats.entrySet().stream()
                        .filter(entry -> TopicPerformance.of(entry.getValue()) == TopicPerformance.STRONG)
                        .sorted(Comparator.comparingDouble((Map.Entry<String, TopicStat> entry) -> entry.getValue().ema()).reversed()
                                .thenComparing(Map.Entry::getKey))
                        .map(Map.Entry::getKey)
                        .toList(),
                stats.entrySet().stream()
                        .filter(entry -> TopicPerformance.of(entry.getValue()) == TopicPerformance.WEAK)
                        .sorted(Comparator.comparingDouble((Map.Entry<String, TopicStat> entry) -> entry.getValue().ema())
                                .thenComparing(Map.Entry::getKey))
                        .map(Map.Entry::getKey)
                        .toList(),
                quotas.keySet().stream().filter(topic -> !stats.containsKey(topic)).sorted().toList(),
                plan.sections().stream()
                        .map(SectionPlan::section)
                        .filter(section -> section != Section.INTRODUCTION && !memory.sectionProgress().containsKey(section))
                        .toList(),
                memory.misconceptions(),
                memory.overall() == null ? 0 : memory.overall().asked(),
                DeadlinePolicy.remaining(now, deadline),
                DeadlinePolicy.isClosing(plan, now, deadline));
    }

    /** How the candidate is doing on a topic; UNTESTED when it was not asked. */
    public TopicPerformance performance(String topic) {
        return TopicPerformance.of(topics.get(topic));
    }
}
