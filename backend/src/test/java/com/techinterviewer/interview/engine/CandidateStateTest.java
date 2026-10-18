package com.techinterviewer.interview.engine;

import static com.techinterviewer.interview.engine.EngineFixtures.CONCURRENCY;
import static com.techinterviewer.interview.engine.EngineFixtures.INDEXING;
import static com.techinterviewer.interview.engine.EngineFixtures.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.interview.domain.InterviewDifficulty;
import com.techinterviewer.interview.domain.InterviewMemory;
import com.techinterviewer.interview.domain.InterviewPlan;
import com.techinterviewer.interview.domain.Misconception;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.SectionProgress;
import com.techinterviewer.interview.domain.TopicStat;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CandidateStateTest {

    private static final InterviewPlan PLAN = EngineFixtures.fullLoop();
    private static final String COLLECTIONS = "java/collections";

    @Test
    void beforeAnyAnswerEverythingIsUntestedAndTheLevelIsTheBandStart() {
        CandidateState state = CandidateState.of(InterviewMemory.empty(), PLAN, T0, T0.plus(Duration.ofMinutes(60)));

        assertThat(state.currentLevel()).isEqualTo(3);
        assertThat(state.currentDifficulty()).isEqualTo(InterviewDifficulty.MEDIUM);
        assertThat(state.overall()).isEqualTo(TopicPerformance.UNTESTED);
        assertThat(state.unansweredTopics()).containsExactly(CONCURRENCY, INDEXING);
        assertThat(state.sectionsNotStarted()).containsExactly(Section.QUESTIONING, Section.CODING, Section.SYSTEM_DESIGN);
        assertThat(state.answersScored()).isZero();
        assertThat(state.remaining()).isEqualTo(Duration.ofMinutes(60));
        assertThat(state.closing()).isFalse();
        assertThat(state.performance(CONCURRENCY)).isEqualTo(TopicPerformance.UNTESTED);
    }

    @Test
    void strengthsAndWeaknessesUseTheThresholdsThatMoveTheDifficulty() {
        Map<String, TopicStat> stats = new LinkedHashMap<>();
        stats.put(CONCURRENCY, new TopicStat(2, 80, 4, 85));
        stats.put(INDEXING, new TopicStat(1, 30, 2, 30));
        stats.put(COLLECTIONS, new TopicStat(1, 75, 4, 75));
        stats.put("react/hooks", new TopicStat(1, 44.99, 2, 44.99));
        stats.put("react/state", new TopicStat(1, 60, 3, 60));
        List<Misconception> misconceptions = List.of(new Misconception("volatile makes count++ atomic", CONCURRENCY));
        InterviewMemory memory = new InterviewMemory(stats, List.of(), List.of(), List.of(), Map.of(),
                Map.of(Section.QUESTIONING, new SectionProgress(5, T0)), null, new TopicStat(5, 62, 4, 60), misconceptions);

        CandidateState state = CandidateState.of(memory, PLAN, T0.plus(Duration.ofMinutes(55)), T0.plus(Duration.ofMinutes(60)));

        assertThat(state.strengths()).as("best first").containsExactly(CONCURRENCY, COLLECTIONS);
        assertThat(state.weaknesses()).as("weakest first").containsExactly(INDEXING, "react/hooks");
        assertThat(state.performance("react/state")).isEqualTo(TopicPerformance.MIXED);
        assertThat(state.overall()).isEqualTo(TopicPerformance.MIXED);
        assertThat(state.currentLevel()).as("the overall level, inside the band").isEqualTo(4);
        assertThat(state.currentDifficulty()).isEqualTo(InterviewDifficulty.HARD);
        assertThat(state.unansweredTopics()).isEmpty();
        assertThat(state.sectionsNotStarted()).containsExactly(Section.CODING, Section.SYSTEM_DESIGN);
        assertThat(state.misconceptions()).isEqualTo(misconceptions);
        assertThat(state.answersScored()).isEqualTo(5);
        assertThat(state.remaining()).isEqualTo(Duration.ofMinutes(5));
        assertThat(state.closing()).isTrue();
    }

    @Test
    void levelsAreNamedEasyMediumAndHard() {
        assertThat(List.of(1, 2, 3, 4, 5)).extracting(InterviewDifficulty::ofLevel).containsExactly(
                InterviewDifficulty.EASY, InterviewDifficulty.EASY, InterviewDifficulty.MEDIUM, InterviewDifficulty.HARD, InterviewDifficulty.HARD);
        assertThatThrownBy(() -> InterviewDifficulty.ofLevel(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InterviewDifficulty.ofLevel(6)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void performanceBoundariesAreThoseOfTheDifficultyAdapter() {
        assertThat(TopicPerformance.of(null)).isEqualTo(TopicPerformance.UNTESTED);
        assertThat(TopicPerformance.of(new TopicStat(1, 75, 3, 75))).isEqualTo(TopicPerformance.STRONG);
        assertThat(TopicPerformance.of(new TopicStat(1, 74.99, 3, 75))).isEqualTo(TopicPerformance.MIXED);
        assertThat(TopicPerformance.of(new TopicStat(1, 45, 3, 45))).isEqualTo(TopicPerformance.MIXED);
        assertThat(TopicPerformance.of(new TopicStat(1, 44.99, 3, 45))).isEqualTo(TopicPerformance.WEAK);
    }
}
