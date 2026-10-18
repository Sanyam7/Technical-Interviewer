package com.techinterviewer.interview.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.techinterviewer.interview.domain.DifficultyBand;
import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.InterviewDifficulty;
import com.techinterviewer.interview.domain.InterviewPlan;
import com.techinterviewer.interview.domain.InterviewType;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.SectionPlan;
import com.techinterviewer.interview.domain.TargetRole;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class InterviewPlannerTest {

    private static final int[] DURATIONS = {15, 30, 45, 60, 90};

    private static PlannerTopic topic(String slug, int min, int max, double weight) {
        return new PlannerTopic(slug, min, max, weight);
    }

    private static PlannerSkill skill(String slug, PlannerTopic... topics) {
        return new PlannerSkill(slug, List.of(topics));
    }

    private static final PlannerSkill JAVA = skill("java", topic("concurrency", 2, 5, 3), topic("collections", 1, 4, 1));
    private static final PlannerSkill POSTGRES = skill("postgresql", topic("indexing", 2, 5, 1));
    private static final PlannerSkill REACT = skill("react", topic("hooks", 1, 5, 1));

    private static PlannerInput input(
            TargetRole role, ExperienceLevel experience, InterviewDifficulty difficulty, int minutes, InterviewType type,
            PlannerSkill... skills) {
        return new PlannerInput(role, experience, difficulty, minutes, type, List.of(skills), 2);
    }

    /** Several skill sets: one skill, favoured and unfavoured mixes, many skills (more skills than slots), a topic-less skill. */
    private static List<List<PlannerSkill>> skillSets() {
        List<PlannerSkill> many = new ArrayList<>();
        for (String slug : List.of("docker", "java", "kafka", "postgresql", "react", "redis", "spring-boot", "typescript")) {
            many.add(skill(slug, topic("core", 1, 5, 1), topic("advanced", 3, 5, 2)));
        }
        return List.of(
                List.of(JAVA),
                List.of(JAVA, POSTGRES),
                List.of(REACT, JAVA, POSTGRES),
                List.of(skill("java", topic("concurrency", 4, 5, 2), topic("collections", 1, 2, 1)), skill("spring-boot", topic("di", 2, 3, 5))),
                many,
                List.of(JAVA, skill("system-design"), skill("go", topic("channels", 3, 4, 99.99))));
    }

    private static Set<Section> expectedSections(InterviewType type, ExperienceLevel experience) {
        boolean junior = experience == ExperienceLevel.INTERN || experience == ExperienceLevel.JUNIOR;
        return switch (type) {
            case TECHNICAL -> EnumSet.of(Section.INTRODUCTION, Section.QUESTIONING);
            case CODING -> EnumSet.of(Section.INTRODUCTION, Section.CODING);
            case SYSTEM_DESIGN -> EnumSet.of(Section.INTRODUCTION, Section.SYSTEM_DESIGN);
            case FULL_LOOP -> junior
                    ? EnumSet.of(Section.INTRODUCTION, Section.QUESTIONING, Section.CODING)
                    : EnumSet.of(Section.INTRODUCTION, Section.QUESTIONING, Section.CODING, Section.SYSTEM_DESIGN);
        };
    }

    private static int expectedStart(InterviewDifficulty difficulty, ExperienceLevel experience) {
        int base = switch (difficulty) {
            case EASY -> 2;
            case MEDIUM -> 3;
            case HARD -> 4;
        };
        return base + switch (experience) {
            case INTERN, JUNIOR -> -1;
            case MID -> 0;
            case SENIOR, STAFF -> 1;
        };
    }

    @Test
    void fullLoopOfSixtyMinutesMatchesTheDocumentExample() {
        InterviewPlan plan = InterviewPlanner.plan(input(TargetRole.BACKEND, ExperienceLevel.MID, InterviewDifficulty.MEDIUM, 60,
                InterviewType.FULL_LOOP, JAVA, POSTGRES));

        assertThat(plan.sections()).extracting(SectionPlan::section, SectionPlan::minutes, SectionPlan::slots).containsExactly(
                tuple(Section.INTRODUCTION, 4, 1),
                tuple(Section.QUESTIONING, 24, 6),
                tuple(Section.CODING, 16, 1),
                tuple(Section.SYSTEM_DESIGN, 14, 1));
        assertThat(plan.totalMinutes()).as("2 minutes of buffer remain").isEqualTo(58);
        assertThat(plan.difficulty()).isEqualTo(new DifficultyBand(3, 2, 4));
        assertThat(plan.maxFollowUpDepth()).isEqualTo(2);
        assertThat(plan.version()).isEqualTo(InterviewPlan.CURRENT_VERSION);
    }

    @Test
    void goldenTopicQuotasShareSlotsBySkillThenByTopicWeight() {
        InterviewPlan plan = InterviewPlanner.plan(input(TargetRole.BACKEND, ExperienceLevel.MID, InterviewDifficulty.MEDIUM, 60,
                InterviewType.FULL_LOOP, JAVA, POSTGRES));

        // 6 slots: each skill gets 1, the 4 left go 2 and 2 (both are favoured by BACKEND): java 3, postgresql 3;
        // java's 3 seats split by topic weight 3:1 -> concurrency 2, collections 1
        assertThat(plan.section(Section.QUESTIONING).orElseThrow().topicQuotas()).containsExactly(
                Map.entry("java/collections", 1), Map.entry("java/concurrency", 2), Map.entry("postgresql/indexing", 3));
    }

    @Test
    void theRoleDecidesWhichSkillsGetTheSurplusSlots() {
        PlannerInput backend = input(TargetRole.BACKEND, ExperienceLevel.MID, InterviewDifficulty.MEDIUM, 60,
                InterviewType.TECHNICAL, JAVA, REACT);
        PlannerInput fullStack = input(TargetRole.FULL_STACK, ExperienceLevel.MID, InterviewDifficulty.MEDIUM, 60,
                InterviewType.TECHNICAL, JAVA, REACT);
        PlannerInput frontend = input(TargetRole.FRONTEND, ExperienceLevel.MID, InterviewDifficulty.MEDIUM, 60,
                InterviewType.TECHNICAL, JAVA, REACT);

        // 13 slots, one per skill, 11 surplus: backend favours java 2:1 (7.33 / 3.67 -> 7 / 4 by remainder),
        // full stack favours nobody (5.5 / 5.5, tie -> java first), frontend favours react
        assertThat(skillSlots(InterviewPlanner.plan(backend))).containsExactly(Map.entry("java", 8), Map.entry("react", 5));
        assertThat(skillSlots(InterviewPlanner.plan(fullStack))).containsExactly(Map.entry("java", 7), Map.entry("react", 6));
        assertThat(skillSlots(InterviewPlanner.plan(frontend))).containsExactly(Map.entry("java", 5), Map.entry("react", 8));
    }

    private static Map<String, Integer> skillSlots(InterviewPlan plan) {
        return plan.section(Section.QUESTIONING).orElseThrow().topicQuotas().entrySet().stream()
                .collect(Collectors.toMap(entry -> entry.getKey().substring(0, entry.getKey().indexOf('/')), Map.Entry::getValue,
                        Integer::sum, TreeMap::new));
    }

    @Test
    void withFewerSlotsThanSkillsTheFavouredSkillsComeFirstThenAlphabetically() {
        List<PlannerSkill> skills = new ArrayList<>();
        for (String slug : List.of("docker", "java", "postgresql", "react", "redis", "spring-boot")) {
            skills.add(skill(slug, topic("core", 1, 5, 1)));
        }
        PlannerInput backend = new PlannerInput(TargetRole.BACKEND, ExperienceLevel.MID, InterviewDifficulty.MEDIUM, 15,
                InterviewType.TECHNICAL, skills, 2);

        InterviewPlan plan = InterviewPlanner.plan(backend);

        assertThat(plan.section(Section.QUESTIONING).orElseThrow().slots()).isEqualTo(3);
        assertThat(plan.section(Section.QUESTIONING).orElseThrow().topicQuotas())
                .containsExactly(Map.entry("java/core", 1), Map.entry("postgresql/core", 1), Map.entry("spring-boot/core", 1));

        PlannerInput fullStack = new PlannerInput(TargetRole.FULL_STACK, ExperienceLevel.MID, InterviewDifficulty.MEDIUM, 15,
                InterviewType.TECHNICAL, skills, 2);
        assertThat(InterviewPlanner.plan(fullStack).section(Section.QUESTIONING).orElseThrow().topicQuotas())
                .containsExactly(Map.entry("docker/core", 1), Map.entry("java/core", 1), Map.entry("postgresql/core", 1));
    }

    @ParameterizedTest(name = "{0} {1} {2} -> start {3} in {4}..{5}")
    @CsvSource({
        "EASY, INTERN, 1, 1, 3", "EASY, JUNIOR, 1, 1, 3", "EASY, MID, 2, 1, 3", "EASY, SENIOR, 3, 1, 3", "EASY, STAFF, 3, 1, 3",
        "MEDIUM, INTERN, 2, 2, 4", "MEDIUM, JUNIOR, 2, 2, 4", "MEDIUM, MID, 3, 2, 4", "MEDIUM, SENIOR, 4, 2, 4", "MEDIUM, STAFF, 4, 2, 4",
        "HARD, INTERN, 3, 3, 5", "HARD, JUNIOR, 3, 3, 5", "HARD, MID, 4, 3, 5", "HARD, SENIOR, 5, 3, 5", "HARD, STAFF, 5, 3, 5"
    })
    void difficultyBandAndStartByDifficultyAndExperience(
            InterviewDifficulty difficulty, ExperienceLevel experience, int start, int min, int max) {
        assertThat(InterviewPlanner.difficultyBand(difficulty, experience)).isEqualTo(new DifficultyBand(start, min, max));
        assertThat(InterviewPlanner.plan(input(TargetRole.BACKEND, experience, difficulty, 30, InterviewType.TECHNICAL, JAVA)).difficulty())
                .isEqualTo(new DifficultyBand(start, min, max));
    }

    @ParameterizedTest(name = "{0} min {1} {2}")
    @CsvSource({
        // duration, type, experience, intro, questioning, coding, design
        "15, FULL_LOOP, MID, 2, 4, 4, 4",
        "30, FULL_LOOP, MID, 2, 12, 8, 7",
        "45, FULL_LOOP, MID, 3, 18, 12, 10",
        "60, FULL_LOOP, MID, 4, 24, 16, 14",
        "90, FULL_LOOP, MID, 6, 36, 24, 21",
        "60, FULL_LOOP, JUNIOR, 4, 32, 22, 0",
        "60, FULL_LOOP, INTERN, 4, 32, 22, 0",
        "60, FULL_LOOP, SENIOR, 4, 24, 16, 14",
        "15, FULL_LOOP, JUNIOR, 2, 7, 5, 0",
        "60, TECHNICAL, MID, 4, 54, 0, 0",
        "15, TECHNICAL, MID, 2, 12, 0, 0",
        "60, CODING, MID, 4, 0, 54, 0",
        "60, SYSTEM_DESIGN, MID, 4, 0, 0, 54",
        "60, SYSTEM_DESIGN, JUNIOR, 4, 0, 0, 54"
    })
    void goldenTimeBudgets(int duration, InterviewType type, ExperienceLevel experience, int intro, int questioning, int coding, int design) {
        InterviewPlan plan = InterviewPlanner.plan(input(TargetRole.FULL_STACK, experience, InterviewDifficulty.MEDIUM, duration, type,
                JAVA, REACT));

        assertThat(minutes(plan, Section.INTRODUCTION)).isEqualTo(intro);
        assertThat(minutes(plan, Section.QUESTIONING)).isEqualTo(questioning);
        assertThat(minutes(plan, Section.CODING)).isEqualTo(coding);
        assertThat(minutes(plan, Section.SYSTEM_DESIGN)).isEqualTo(design);
    }

    private static int minutes(InterviewPlan plan, Section section) {
        return plan.section(section).map(SectionPlan::minutes).orElse(0);
    }

    @ParameterizedTest
    @CsvSource({
        // type, minutes of the section, expected slots
        "TECHNICAL, 15, 3", "TECHNICAL, 30, 6", "TECHNICAL, 45, 10", "TECHNICAL, 60, 13", "TECHNICAL, 90, 20",
        "CODING, 15, 1", "CODING, 30, 2", "CODING, 45, 2", "CODING, 60, 2", "CODING, 90, 2",
        "SYSTEM_DESIGN, 15, 1", "SYSTEM_DESIGN, 60, 1", "SYSTEM_DESIGN, 90, 1"
    })
    void slotsFollowTheSectionTimeAndShape(InterviewType type, int duration, int slots) {
        InterviewPlan plan = InterviewPlanner.plan(input(TargetRole.BACKEND, ExperienceLevel.MID, InterviewDifficulty.MEDIUM, duration, type, JAVA));
        Section section = switch (type) {
            case TECHNICAL -> Section.QUESTIONING;
            case CODING -> Section.CODING;
            default -> Section.SYSTEM_DESIGN;
        };

        assertThat(plan.section(section).orElseThrow().slots()).isEqualTo(slots);
        assertThat(plan.section(Section.INTRODUCTION).orElseThrow().slots()).isEqualTo(1);
    }

    @Test
    void oneCodingProblemPerTwelveMinutesUpToTwo() {
        // 22 minutes (junior loop of 60) is one problem, 24 minutes (loop of 90) is two
        assertThat(InterviewPlanner.plan(input(TargetRole.BACKEND, ExperienceLevel.JUNIOR, InterviewDifficulty.EASY, 60,
                InterviewType.FULL_LOOP, JAVA)).section(Section.CODING).orElseThrow())
                .extracting(SectionPlan::minutes, SectionPlan::slots).containsExactly(22, 1);
        assertThat(InterviewPlanner.plan(input(TargetRole.BACKEND, ExperienceLevel.MID, InterviewDifficulty.EASY, 90,
                InterviewType.FULL_LOOP, JAVA)).section(Section.CODING).orElseThrow())
                .extracting(SectionPlan::minutes, SectionPlan::slots).containsExactly(24, 2);
    }

    @Test
    void roughlyFourMinutesPerRootQuestionInTheQuestioningSection() {
        for (int duration : DURATIONS) {
            SectionPlan questioning = InterviewPlanner.plan(input(TargetRole.BACKEND, ExperienceLevel.MID, InterviewDifficulty.MEDIUM,
                    duration, InterviewType.TECHNICAL, JAVA)).section(Section.QUESTIONING).orElseThrow();

            assertThat(questioning.slots()).isEqualTo(questioning.minutes() / 4);
        }
    }

    @Test
    void everyDurationTypeExperienceDifficultyRoleAndSkillSetYieldsAConsistentPlan() {
        int plans = 0;
        for (int duration : DURATIONS) {
            for (InterviewType type : InterviewType.values()) {
                for (ExperienceLevel experience : ExperienceLevel.values()) {
                    for (InterviewDifficulty difficulty : InterviewDifficulty.values()) {
                        for (TargetRole role : TargetRole.values()) {
                            for (List<PlannerSkill> skills : skillSets()) {
                                for (int depth : new int[] {0, 1, 2}) {
                                    PlannerInput in = new PlannerInput(role, experience, difficulty, duration, type, skills, depth);
                                    DifficultyBand band = InterviewPlanner.difficultyBand(difficulty, experience);
                                    boolean asksTopics = expectedSections(type, experience).contains(Section.QUESTIONING);
                                    boolean anyFits = skills.stream().anyMatch(s -> s.topics().stream().anyMatch(t -> t.range().overlaps(band)));
                                    if (asksTopics && !anyFits) {
                                        assertThatThrownBy(() -> InterviewPlanner.plan(in))
                                                .as(in.toString()).isInstanceOf(IllegalArgumentException.class);
                                        continue;
                                    }
                                    assertPlanInvariants(in, InterviewPlanner.plan(in));
                                    plans++;
                                }
                            }
                        }
                    }
                }
            }
        }
        assertThat(plans).isGreaterThan(5000);
    }

    private static void assertPlanInvariants(PlannerInput in, InterviewPlan plan) {
        String context = in.role() + " " + in.experience() + " " + in.difficulty() + " " + in.durationMinutes() + " " + in.type()
                + " " + in.skills().stream().map(PlannerSkill::slug).toList() + " depth " + in.maxFollowUpDepth();

        // shape
        assertThat(plan.sections().stream().map(SectionPlan::section).collect(Collectors.toCollection(() -> EnumSet.noneOf(Section.class))))
                .as(context).isEqualTo(expectedSections(in.type(), in.experience()));
        assertThat(plan.firstSection()).as(context).isEqualTo(Section.INTRODUCTION);
        assertThat(plan.maxFollowUpDepth()).as(context).isEqualTo(in.maxFollowUpDepth());

        // time
        assertThat(plan.totalMinutes()).as("never more than the interview lasts: " + context).isLessThanOrEqualTo(in.durationMinutes());
        assertThat(plan.totalMinutes()).as("all but the buffer is assigned: " + context)
                .isEqualTo(in.durationMinutes() * 58 / 60);
        for (SectionPlan section : plan.sections()) {
            int minimum = section.section() == Section.INTRODUCTION ? 2 : 4;
            assertThat(section.minutes()).as(section.section() + " minutes " + context).isGreaterThanOrEqualTo(minimum);
            assertThat(section.slots()).as(section.section() + " slots " + context).isGreaterThanOrEqualTo(1);
        }
        assertThat(plan.section(Section.INTRODUCTION).orElseThrow().slots()).as(context).isEqualTo(1);
        plan.section(Section.SYSTEM_DESIGN).ifPresent(design -> assertThat(design.slots()).as(context).isEqualTo(1));
        plan.section(Section.CODING).ifPresent(coding -> assertThat(coding.slots()).as(context).isBetween(1, 2));

        // difficulty
        DifficultyBand band = plan.difficulty();
        assertThat(band.start()).as(context).isEqualTo(expectedStart(in.difficulty(), in.experience()));
        assertThat(band.min()).as(context).isLessThanOrEqualTo(band.start());
        assertThat(band.max()).as(context).isGreaterThanOrEqualTo(band.start());
        assertThat(band.min()).as(context).isGreaterThanOrEqualTo(1);
        assertThat(band.max()).as(context).isLessThanOrEqualTo(5);

        // topic quotas
        for (SectionPlan section : plan.sections()) {
            if (section.section() != Section.QUESTIONING) {
                assertThat(section.topicQuotas()).as("only questioning has quotas " + context).isEmpty();
                continue;
            }
            Map<String, Integer> quotas = section.topicQuotas();
            assertThat(section.slots()).as(context).isEqualTo(section.minutes() / 4);
            assertThat(quotas.values().stream().mapToInt(Integer::intValue).sum()).as("quotas fill the slots " + context)
                    .isEqualTo(section.slots());
            assertThat(quotas.values()).as(context).allMatch(quota -> quota >= 1);
            Map<String, TopicRange> ranges = in.topicRanges();
            assertThat(ranges.keySet()).as("only selected skills' topics " + context).containsAll(quotas.keySet());
            quotas.keySet().forEach(key -> assertThat(ranges.get(key).overlaps(band)).as(key + " fits the band " + context).isTrue());
            Set<String> skillsWithQuota = quotas.keySet().stream().map(key -> key.substring(0, key.indexOf('/'))).collect(Collectors.toSet());
            long fittingSkills = in.skills().stream().filter(s -> s.topics().stream().anyMatch(t -> t.range().overlaps(band))).count();
            assertThat(skillsWithQuota.size()).as("every fitting skill is asked while slots last " + context)
                    .isEqualTo((int) Math.min(fittingSkills, section.slots()));
        }

        // determinism, and independence of the order of the skills
        assertThat(InterviewPlanner.plan(in)).as(context).isEqualTo(plan);
        List<PlannerSkill> shuffled = new ArrayList<>(in.skills());
        Collections.shuffle(shuffled, new Random(11));
        assertThat(InterviewPlanner.plan(new PlannerInput(in.role(), in.experience(), in.difficulty(), in.durationMinutes(), in.type(),
                shuffled, in.maxFollowUpDepth()))).as("order of skills " + context).isEqualTo(plan);
    }

    @Test
    void juniorsDropSystemDesignFromTheFullLoopButMayAskForItExplicitly() {
        for (ExperienceLevel level : List.of(ExperienceLevel.INTERN, ExperienceLevel.JUNIOR)) {
            assertThat(InterviewPlanner.plan(input(TargetRole.BACKEND, level, InterviewDifficulty.EASY, 60, InterviewType.FULL_LOOP, JAVA))
                    .hasSection(Section.SYSTEM_DESIGN)).as(level + " full loop").isFalse();
            assertThat(InterviewPlanner.plan(input(TargetRole.BACKEND, level, InterviewDifficulty.EASY, 60, InterviewType.SYSTEM_DESIGN, JAVA))
                    .hasSection(Section.SYSTEM_DESIGN)).as(level + " explicit design").isTrue();
        }
        for (ExperienceLevel level : List.of(ExperienceLevel.MID, ExperienceLevel.SENIOR, ExperienceLevel.STAFF)) {
            assertThat(InterviewPlanner.plan(input(TargetRole.BACKEND, level, InterviewDifficulty.EASY, 60, InterviewType.FULL_LOOP, JAVA))
                    .hasSection(Section.SYSTEM_DESIGN)).as(level + " full loop").isTrue();
        }
    }

    @Test
    void topicsOutsideTheDifficultyBandAreNotAskedAndTheirSkillGetsNoSlot() {
        PlannerSkill hardOnly = skill("kafka", topic("exactly-once", 5, 5, 1));
        PlannerSkill mixed = skill("java", topic("basics", 1, 1, 5), topic("generics", 2, 3, 1));

        // EASY MID: band 1..3
        InterviewPlan plan = InterviewPlanner.plan(input(TargetRole.FULL_STACK, ExperienceLevel.MID, InterviewDifficulty.EASY, 30,
                InterviewType.TECHNICAL, hardOnly, mixed));

        assertThat(plan.section(Section.QUESTIONING).orElseThrow().topicQuotas()).containsOnlyKeys("java/basics", "java/generics");
    }

    @Test
    void aTopicsRangeMustOverlapTheBandNotJustTouchItsStart() {
        PlannerSkill java = skill("java", topic("low", 1, 1, 1), topic("high", 5, 5, 1), topic("band", 3, 3, 1));

        InterviewPlan plan = InterviewPlanner.plan(input(TargetRole.BACKEND, ExperienceLevel.MID, InterviewDifficulty.MEDIUM, 30,
                InterviewType.TECHNICAL, java));

        assertThat(plan.section(Section.QUESTIONING).orElseThrow().topicQuotas()).containsExactly(Map.entry("java/band", 6));
    }

    @Test
    void topicSeatsTieBreakByClosenessToTheStartingLevelThenSlug() {
        // MEDIUM MID: band 2..4, start 3; two equal-weight topics share the 3 slots of a 15-minute interview
        PlannerSkill closer = skill("java", topic("a-far", 2, 2, 1), topic("b-near", 3, 4, 1));
        InterviewPlan byDistance = InterviewPlanner.plan(input(TargetRole.BACKEND, ExperienceLevel.MID, InterviewDifficulty.MEDIUM, 15,
                InterviewType.TECHNICAL, closer));
        // 3 seats over 2 equal topics: 1.5 each, the extra seat goes to the first in priority order: b-near (distance 0)
        assertThat(byDistance.section(Section.QUESTIONING).orElseThrow().topicQuotas())
                .containsExactly(Map.entry("java/a-far", 1), Map.entry("java/b-near", 2));

        PlannerSkill sameDistance = skill("java", topic("zeta", 2, 4, 1), topic("alpha", 2, 4, 1));
        InterviewPlan bySlug = InterviewPlanner.plan(input(TargetRole.BACKEND, ExperienceLevel.MID, InterviewDifficulty.MEDIUM, 15,
                InterviewType.TECHNICAL, sameDistance));
        assertThat(bySlug.section(Section.QUESTIONING).orElseThrow().topicQuotas())
                .containsExactly(Map.entry("java/alpha", 2), Map.entry("java/zeta", 1));
    }

    @Test
    void heavierTopicsOutrankLighterOnesWhenSeatsAreScarce() {
        PlannerSkill java = skill("java", topic("light", 1, 5, 1), topic("heavy", 1, 5, 2));

        InterviewPlan plan = InterviewPlanner.plan(input(TargetRole.BACKEND, ExperienceLevel.MID, InterviewDifficulty.MEDIUM, 15,
                InterviewType.TECHNICAL, java));

        assertThat(plan.section(Section.QUESTIONING).orElseThrow().topicQuotas())
                .containsExactly(Map.entry("java/heavy", 2), Map.entry("java/light", 1));
    }

    @Test
    void fractionalTopicWeightsAreHonouredNotRoundedToWholeNumbers() {
        PlannerSkill java = skill("java", topic("a", 1, 5, 1.4), topic("b", 1, 5, 0.6));

        InterviewPlan plan = InterviewPlanner.plan(input(TargetRole.BACKEND, ExperienceLevel.MID, InterviewDifficulty.MEDIUM, 30,
                InterviewType.TECHNICAL, java));

        // 6 slots split 1.4 : 0.6 = 4.2 : 1.8 -> 4 and 2 (rounded to 1 : 1 the split would be 3 and 3)
        assertThat(plan.section(Section.QUESTIONING).orElseThrow().topicQuotas()).containsExactly(Map.entry("java/a", 4), Map.entry("java/b", 2));
    }

    @Test
    void whenRemaindersTieTheHeavierTopicGetsTheExtraSeat() {
        PlannerSkill java = skill("java", topic("a-light", 1, 5, 0.5), topic("b-heavy", 1, 5, 1.5));

        InterviewPlan plan = InterviewPlanner.plan(input(TargetRole.BACKEND, ExperienceLevel.MID, InterviewDifficulty.MEDIUM, 30,
                InterviewType.TECHNICAL, java));

        // 6 slots split 0.5 : 1.5 = 1.5 : 4.5, both remainders are one half
        assertThat(plan.section(Section.QUESTIONING).orElseThrow().topicQuotas())
                .containsExactly(Map.entry("java/a-light", 1), Map.entry("java/b-heavy", 5));
    }

    @Test
    void codingAndDesignInterviewsNeedNoTopics() {
        PlannerSkill noTopics = skill("system-design");

        InterviewPlan coding = InterviewPlanner.plan(input(TargetRole.BACKEND, ExperienceLevel.MID, InterviewDifficulty.MEDIUM, 30,
                InterviewType.CODING, noTopics));
        InterviewPlan design = InterviewPlanner.plan(input(TargetRole.BACKEND, ExperienceLevel.MID, InterviewDifficulty.MEDIUM, 30,
                InterviewType.SYSTEM_DESIGN, noTopics));

        assertThat(coding.hasSection(Section.QUESTIONING)).isFalse();
        assertThat(design.hasSection(Section.QUESTIONING)).isFalse();
    }

    @Test
    void aTechnicalInterviewWithoutAnyTopicThatFitsTheBandIsRejected() {
        PlannerSkill hard = skill("kafka", topic("exactly-once", 5, 5, 1));

        assertThatThrownBy(() -> InterviewPlanner.plan(input(TargetRole.BACKEND, ExperienceLevel.JUNIOR, InterviewDifficulty.EASY, 30,
                InterviewType.TECHNICAL, hard)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 to 3");
        assertThatThrownBy(() -> InterviewPlanner.plan(input(TargetRole.BACKEND, ExperienceLevel.JUNIOR, InterviewDifficulty.EASY, 30,
                InterviewType.TECHNICAL, skill("java"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void apportionDistributesAllSeatsByLargestRemainderAndTiesGoToTheEarlierEntry() {
        assertThat(InterviewPlanner.apportion(new long[] {1, 1, 1}, 3)).containsExactly(1, 1, 1);
        assertThat(InterviewPlanner.apportion(new long[] {1, 1, 1}, 4)).containsExactly(2, 1, 1);
        assertThat(InterviewPlanner.apportion(new long[] {1, 1, 1}, 5)).containsExactly(2, 2, 1);
        assertThat(InterviewPlanner.apportion(new long[] {3, 1}, 3)).containsExactly(2, 1);
        assertThat(InterviewPlanner.apportion(new long[] {5, 3, 2}, 10)).containsExactly(5, 3, 2);
        assertThat(InterviewPlanner.apportion(new long[] {7, 1, 1}, 0)).containsExactly(0, 0, 0);
        assertThat(InterviewPlanner.apportion(new long[] {10}, 7)).containsExactly(7);
        assertThat(InterviewPlanner.apportion(new long[] {1, 10}, 1)).containsExactly(0, 1);
    }

    @Test
    void apportionAlwaysHandsOutExactlyTheSeatsAndStaysWithinOneOfTheExactShare() {
        Random random = new Random(3);
        for (int round = 0; round < 500; round++) {
            long[] weights = new long[1 + random.nextInt(6)];
            for (int i = 0; i < weights.length; i++) {
                weights[i] = 1 + random.nextInt(1000);
            }
            int seats = random.nextInt(40);
            long total = 0;
            for (long weight : weights) {
                total += weight;
            }

            int[] shares = InterviewPlanner.apportion(weights, seats);

            assertThat(Arrays.stream(shares).sum()).isEqualTo(seats);
            for (int i = 0; i < weights.length; i++) {
                double exact = (double) weights[i] * seats / total;
                assertThat((double) shares[i]).isBetween(Math.floor(exact), Math.ceil(exact));
            }
        }
    }

    @Test
    void inputIsValidated() {
        assertThatThrownBy(() -> new PlannerInput(TargetRole.BACKEND, ExperienceLevel.MID, InterviewDifficulty.EASY, 14,
                InterviewType.TECHNICAL, List.of(JAVA), 2)).hasMessageContaining("durationMinutes");
        assertThatThrownBy(() -> new PlannerInput(TargetRole.BACKEND, ExperienceLevel.MID, InterviewDifficulty.EASY, 181,
                InterviewType.TECHNICAL, List.of(JAVA), 2)).hasMessageContaining("durationMinutes");
        assertThatThrownBy(() -> new PlannerInput(TargetRole.BACKEND, ExperienceLevel.MID, InterviewDifficulty.EASY, 30,
                InterviewType.TECHNICAL, List.of(), 2)).hasMessageContaining("skills");
        assertThatThrownBy(() -> new PlannerInput(TargetRole.BACKEND, ExperienceLevel.MID, InterviewDifficulty.EASY, 30,
                InterviewType.TECHNICAL, List.of(JAVA, JAVA), 2)).hasMessageContaining("duplicate skill");
        assertThatThrownBy(() -> new PlannerInput(TargetRole.BACKEND, ExperienceLevel.MID, InterviewDifficulty.EASY, 30,
                InterviewType.TECHNICAL, List.of(JAVA), 3)).hasMessageContaining("maxFollowUpDepth");
        assertThatThrownBy(() -> new PlannerInput(TargetRole.BACKEND, ExperienceLevel.MID, InterviewDifficulty.EASY, 30,
                InterviewType.TECHNICAL, List.of(JAVA), -1)).hasMessageContaining("maxFollowUpDepth");
        assertThatThrownBy(() -> new PlannerInput(null, ExperienceLevel.MID, InterviewDifficulty.EASY, 30,
                InterviewType.TECHNICAL, List.of(JAVA), 0)).hasMessageContaining("role");
        assertThatThrownBy(() -> new PlannerInput(TargetRole.BACKEND, null, InterviewDifficulty.EASY, 30,
                InterviewType.TECHNICAL, List.of(JAVA), 0)).hasMessageContaining("experience");
        assertThatThrownBy(() -> new PlannerInput(TargetRole.BACKEND, ExperienceLevel.MID, null, 30,
                InterviewType.TECHNICAL, List.of(JAVA), 0)).hasMessageContaining("difficulty");
        assertThatThrownBy(() -> new PlannerInput(TargetRole.BACKEND, ExperienceLevel.MID, InterviewDifficulty.EASY, 30,
                null, List.of(JAVA), 0)).hasMessageContaining("type");
        assertThat(new PlannerInput(TargetRole.BACKEND, ExperienceLevel.MID, InterviewDifficulty.EASY, 15, InterviewType.TECHNICAL,
                List.of(JAVA), 0).durationMinutes()).isEqualTo(15);
        assertThat(new PlannerInput(TargetRole.BACKEND, ExperienceLevel.MID, InterviewDifficulty.EASY, 180, InterviewType.TECHNICAL,
                List.of(JAVA), 0).durationMinutes()).isEqualTo(180);
    }

    @Test
    void skillAndTopicValueTypesAreValidated() {
        assertThatThrownBy(() -> new PlannerSkill(" ", List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlannerSkill("a/b", List.of())).hasMessageContaining("'/'");
        assertThatThrownBy(() -> new PlannerSkill("java", List.of(topic("x", 1, 2, 1), topic("x", 1, 2, 1))))
                .hasMessageContaining("duplicate topic java/x");
        assertThatThrownBy(() -> topic("a/b", 1, 2, 1)).hasMessageContaining("'/'");
        assertThatThrownBy(() -> topic("x", 3, 2, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> topic("x", 0, 2, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> topic("x", 1, 6, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> topic("x", 1, 2, 0.009)).hasMessageContaining("weight");
        assertThatThrownBy(() -> topic("x", 1, 2, 100)).hasMessageContaining("weight");
        assertThat(topic("x", 1, 5, 0.01).weight()).isEqualTo(0.01);
        assertThat(topic("x", 1, 5, 99.99).weight()).isEqualTo(99.99);
        assertThat(new PlannerSkill("java", List.of(topic("x", 2, 4, 1))).topicKey(topic("x", 2, 4, 1))).isEqualTo("java/x");
    }

    @Test
    void topicRangeBehaviour() {
        TopicRange range = new TopicRange(2, 4);

        assertThat(range.clamp(1)).isEqualTo(2);
        assertThat(range.clamp(3)).isEqualTo(3);
        assertThat(range.clamp(5)).isEqualTo(4);
        assertThat(range.distanceTo(1)).isEqualTo(1);
        assertThat(range.distanceTo(2)).isZero();
        assertThat(range.distanceTo(4)).isZero();
        assertThat(range.distanceTo(5)).isEqualTo(1);
        assertThat(new TopicRange(5, 5).distanceTo(2)).isEqualTo(3);
        assertThat(range.overlaps(new DifficultyBand(3, 1, 3))).isTrue();
        assertThat(range.overlaps(new DifficultyBand(1, 1, 1))).isFalse();
        assertThat(range.overlaps(new DifficultyBand(5, 5, 5))).isFalse();
        assertThat(range.overlaps(new DifficultyBand(4, 4, 5))).isTrue();
        assertThat(range.overlaps(new DifficultyBand(1, 1, 2))).isTrue();
    }
}
