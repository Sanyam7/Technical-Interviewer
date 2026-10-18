package com.techinterviewer.interview.engine;

import com.techinterviewer.interview.domain.DifficultyBand;
import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.InterviewDifficulty;
import com.techinterviewer.interview.domain.InterviewPlan;
import com.techinterviewer.interview.domain.InterviewStage;
import com.techinterviewer.interview.domain.InterviewStrategy;
import com.techinterviewer.interview.domain.InterviewType;
import com.techinterviewer.interview.domain.ResumePlan;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.SectionPlan;
import com.techinterviewer.interview.domain.StageMix;
import com.techinterviewer.interview.domain.StagePlan;
import com.techinterviewer.interview.domain.TargetRole;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.ToLongFunction;
import org.jspecify.annotations.Nullable;

/**
 * Builds the {@link InterviewPlan} of an interview (AI_ARCHITECTURE.md section 2.1). Fully deterministic: the same
 * configuration always yields the same plan.
 *
 * <h2>Sections</h2>
 * TECHNICAL: introduction and questioning. CODING: introduction and coding. SYSTEM_DESIGN: introduction and one
 * design. FULL_LOOP: all four, except that the design is dropped for INTERN and JUNIOR candidates (they can still
 * ask for a SYSTEM_DESIGN interview explicitly).
 *
 * <h2>Time budgets</h2>
 * Each shape has reference shares of a 60-minute interview. The FULL_LOOP reference is the document's example:
 * introduction 4, questioning 24, coding 16, design 14 (2 minutes of buffer stay unassigned). Every other shape
 * uses the same 58 assignable minutes ({@code 4 + 54}, or {@code 4 + 32 + 22} for the junior loop). A duration is
 * budgeted by scaling the shares to {@code duration * 58 / 60} whole minutes (largest remainder; ties go to the
 * earlier section), then lifting every section to its minimum (introduction 2, the others 4) by taking minutes
 * from the section with the most to spare. The total never exceeds the duration, the unassigned rest is the buffer.
 *
 * <h2>Question slots</h2>
 * Introduction and design have one slot. Questioning has {@code minutes / 4} root questions (at least one; follow-ups
 * draw from the same time). Coding has one problem per 12 minutes, at least one and at most two.
 *
 * <h2>Topic quotas</h2>
 * Only the topics of the selected skills whose difficulty range overlaps the band take part. The questioning slots
 * are shared among skills (every skill gets one slot while slots last, the rest by role weight: skills the role
 * favours weigh 2, the others 1), then among the skill's topics by topic weight (largest remainder; ties prefer the
 * topic whose range is closest to the starting difficulty, then the slug). Skills and topics are processed in slug
 * order so the plan does not depend on the order of the input.
 *
 * <h2>Stages (AI_ARCHITECTURE.md section 2.15)</h2>
 * The questioning slots are divided between the stages FUNDAMENTALS, PRACTICAL and PROJECT by the strategy's shares (largest
 * remainder, ties to the stage that runs first), and listed in the strategy's order. A preset is adjusted to the candidate's
 * seniority: INTERN and JUNIOR move {@value #EXPERIENCE_SHIFT} points from PROJECT to FUNDAMENTALS (less own work to discuss, the
 * basics matter most), SENIOR and STAFF move {@value #EXPERIENCE_SHIFT} points from FUNDAMENTALS to PRACTICAL (the basics are checked
 * faster, applying them matters more); a CUSTOM mix is taken as given. FUNDAMENTALS_FIRST gives every skill that can be asked one
 * fundamentals question, as far as the slots allow, taking the slots from the last stage that has one.
 *
 * <h2>Résumé (AI_ARCHITECTURE.md sections 2.13 and 2.15)</h2>
 * With a résumé, the input lists its experiences and what the résumé vocabulary links each to. Only the PROJECT stage is anchored
 * on an experience: as many of its slots as there are linkable experiences. The plan lists, per topic with a quota, the experiences
 * linked to it (strongest link first, then projects before achievements before responsibilities, then résumé order), and per skill
 * those linked to the skill or to any of its topics, as a fallback; the engine picks from these lists. Only the RESUME_FOCUSED
 * strategy also lets the résumé shape the topics: there a topic linked to an experience weighs double in its skill's quota.
 *
 * <h2>Difficulty</h2>
 * EASY starts at 2 within the band 1 to 3, MEDIUM at 3 within 2 to 4, HARD at 4 within 3 to 5. Experience shifts the
 * start by one level, clamped to the band: INTERN and JUNIOR one lower, MID unchanged, SENIOR and STAFF one higher.
 */
public final class InterviewPlanner {

    /** The interview length the reference shares describe, and the minutes of it that they assign (the rest is buffer). */
    private static final int REFERENCE_MINUTES = 60;
    private static final int REFERENCE_ASSIGNED_MINUTES = 58;

    static final int MINUTES_PER_ROOT_QUESTION = 4;
    static final int MINUTES_PER_CODING_PROBLEM = 12;
    static final int MAX_CODING_PROBLEMS = 2;
    static final int FAVOURED_SKILL_WEIGHT = 2;
    static final int OTHER_SKILL_WEIGHT = 1;

    private static final Map<Section, Integer> MINIMUM_MINUTES = Map.of(
            Section.INTRODUCTION, 2, Section.QUESTIONING, 4, Section.CODING, 4, Section.SYSTEM_DESIGN, 4);

    /** Skills a role favours (slugs of the seeded catalog, DATABASE_SCHEMA.md section 3.3); FULL_STACK favours none. */
    private static final Map<TargetRole, Set<String>> FAVOURED_SKILLS = Map.of(
            TargetRole.BACKEND, Set.of("java", "spring-boot", "postgresql", "system-design"),
            TargetRole.FRONTEND, Set.of("react", "typescript"),
            TargetRole.FULL_STACK, Set.of());

    private static final long WEIGHT_SCALE = 10_000;
    static final int RESUME_TOPIC_FACTOR = 2;
    static final int EXPERIENCE_SHIFT = 10;

    private InterviewPlanner() {
    }

    /**
     * @throws IllegalArgumentException when the interview asks topic questions but no selected skill has a topic
     *                                  that fits the difficulty band
     */
    public static InterviewPlan plan(PlannerInput input) {
        DifficultyBand band = difficultyBand(input.difficulty(), input.experience());
        Map<Section, Integer> minutes = budgetMinutes(input.durationMinutes(), referenceShares(input.type(), input.experience()));
        List<SectionPlan> sections = new ArrayList<>();
        for (Map.Entry<Section, Integer> budget : minutes.entrySet()) {
            Section section = budget.getKey();
            int sectionMinutes = budget.getValue();
            sections.add(switch (section) {
                case INTRODUCTION -> new SectionPlan(section, sectionMinutes, 1);
                case QUESTIONING -> questioning(input, band, sectionMinutes);
                case CODING -> new SectionPlan(section, sectionMinutes, codingProblems(sectionMinutes));
                case SYSTEM_DESIGN -> new SectionPlan(section, sectionMinutes, 1);
            });
        }
        return new InterviewPlan(InterviewPlan.CURRENT_VERSION, sections, band, input.maxFollowUpDepth(), input.maxDesignProbes(),
                resumePlan(input, sections));
    }

    /** The band and starting level for a requested difficulty and the candidate's seniority. */
    static DifficultyBand difficultyBand(InterviewDifficulty difficulty, ExperienceLevel experience) {
        DifficultyBand base = switch (difficulty) {
            case EASY -> new DifficultyBand(2, 1, 3);
            case MEDIUM -> new DifficultyBand(3, 2, 4);
            case HARD -> new DifficultyBand(4, 3, 5);
        };
        int shift = switch (experience) {
            case INTERN, JUNIOR -> -1;
            case MID -> 0;
            case SENIOR, STAFF -> 1;
        };
        return new DifficultyBand(base.clamp(base.start() + shift), base.min(), base.max());
    }

    /** The share of a 60-minute interview each section of the shape gets (the shares add up to 58). */
    private static Map<Section, Integer> referenceShares(InterviewType type, ExperienceLevel experience) {
        Map<Section, Integer> shares = new EnumMap<>(Section.class);
        shares.put(Section.INTRODUCTION, 4);
        switch (type) {
            case TECHNICAL -> shares.put(Section.QUESTIONING, 54);
            case CODING -> shares.put(Section.CODING, 54);
            case SYSTEM_DESIGN -> shares.put(Section.SYSTEM_DESIGN, 54);
            case FULL_LOOP -> {
                boolean junior = experience == ExperienceLevel.INTERN || experience == ExperienceLevel.JUNIOR;
                shares.put(Section.QUESTIONING, junior ? 32 : 24);
                shares.put(Section.CODING, junior ? 22 : 16);
                if (!junior) {
                    shares.put(Section.SYSTEM_DESIGN, 14);
                }
            }
        }
        return shares;
    }

    private static Map<Section, Integer> budgetMinutes(int durationMinutes, Map<Section, Integer> shares) {
        List<Section> sections = List.copyOf(shares.keySet());
        long[] weights = new long[sections.size()];
        for (int i = 0; i < weights.length; i++) {
            weights[i] = shares.get(sections.get(i));
        }
        int assignable = durationMinutes * REFERENCE_ASSIGNED_MINUTES / REFERENCE_MINUTES;
        int[] minutes = apportion(weights, assignable);
        liftToMinimums(sections, minutes);
        Map<Section, Integer> budget = new EnumMap<>(Section.class);
        for (int i = 0; i < minutes.length; i++) {
            budget.put(sections.get(i), minutes[i]);
        }
        return budget;
    }

    /** Raises every section to its minimum, paying with the section that has the most minutes above its own minimum. */
    private static void liftToMinimums(List<Section> sections, int[] minutes) {
        for (int i = 0; i < minutes.length; i++) {
            while (minutes[i] < MINIMUM_MINUTES.get(sections.get(i))) {
                int donor = -1;
                int bestSpare = 0;
                for (int j = 0; j < minutes.length; j++) {
                    int spare = minutes[j] - MINIMUM_MINUTES.get(sections.get(j));
                    if (spare > bestSpare) {
                        bestSpare = spare;
                        donor = j;
                    }
                }
                if (donor < 0) {
                    throw new IllegalStateException("the minimum section budgets exceed the interview duration");
                }
                minutes[donor]--;
                minutes[i]++;
            }
        }
    }

    private static int codingProblems(int minutes) {
        return Math.max(1, Math.min(MAX_CODING_PROBLEMS, minutes / MINUTES_PER_CODING_PROBLEM));
    }

    private static SectionPlan questioning(PlannerInput input, DifficultyBand band, int minutes) {
        int slots = Math.max(1, minutes / MINUTES_PER_ROOT_QUESTION);
        Map<String, Integer> quotas = topicQuotas(input, band, slots);
        long skills = quotas.keySet().stream().map(TopicSelector::skillOf).distinct().count();
        return new SectionPlan(Section.QUESTIONING, minutes, slots, quotas, stages(input, slots, (int) skills));
    }

    /** The slots of each stage, in the strategy's order; see the class comment. */
    static List<StagePlan> stages(PlannerInput input, int slots, int skills) {
        InterviewStrategy strategy = input.strategy();
        StageMix mix = adjusted(strategy, input.mix(), input.experience());
        List<InterviewStage> order = strategy.order();
        long[] weights = new long[order.size()];
        for (int i = 0; i < weights.length; i++) {
            weights[i] = mix.shareOf(order.get(i));
        }
        int[] seats = apportion(weights, slots);
        int fundamentals = order.indexOf(InterviewStage.FUNDAMENTALS);
        if (strategy == InterviewStrategy.FUNDAMENTALS_FIRST) {
            int floor = Math.min(skills, slots);
            for (int donor = seats.length - 1; donor >= 0 && seats[fundamentals] < floor; donor--) {
                while (donor != fundamentals && seats[donor] > 0 && seats[fundamentals] < floor) {
                    seats[donor]--;
                    seats[fundamentals]++;
                }
            }
        }
        List<StagePlan> stages = new ArrayList<>();
        for (int i = 0; i < seats.length; i++) {
            stages.add(new StagePlan(order.get(i), seats[i]));
        }
        return stages;
    }

    /** A preset's mix adjusted to the candidate's seniority; a CUSTOM mix unchanged. */
    static StageMix adjusted(InterviewStrategy strategy, StageMix mix, ExperienceLevel experience) {
        if (strategy == InterviewStrategy.CUSTOM) {
            return mix;
        }
        return switch (experience) {
            case INTERN, JUNIOR -> {
                int moved = Math.min(EXPERIENCE_SHIFT, mix.project());
                yield new StageMix(mix.fundamentals() + moved, mix.practical(), mix.project() - moved);
            }
            case MID -> mix;
            case SENIOR, STAFF -> {
                int moved = Math.min(EXPERIENCE_SHIFT, mix.fundamentals());
                yield new StageMix(mix.fundamentals() - moved, mix.practical() + moved, mix.project());
            }
        };
    }

    /** How many questions each topic gets: {@code skill/topic} to a quota of at least 1; the quotas add up to {@code slots}. */
    private static Map<String, Integer> topicQuotas(PlannerInput input, DifficultyBand band, int slots) {
        Set<String> favoured = FAVOURED_SKILLS.get(input.role());
        List<PlannerSkill> skills = input.skills().stream()
                .filter(skill -> skill.topics().stream().anyMatch(topic -> topic.range().overlaps(band)))
                .sorted(Comparator.comparing(PlannerSkill::slug))
                .toList();
        if (skills.isEmpty()) {
            throw new IllegalArgumentException("no selected skill has a topic that fits the difficulty band "
                    + band.min() + " to " + band.max());
        }
        int[] seats = skillSeats(skills, favoured, slots);
        Set<String> linked = new HashSet<>();
        if (input.strategy() == InterviewStrategy.RESUME_FOCUSED) {
            input.experiences().forEach(experience -> linked.addAll(experience.topics().keySet()));
        }
        Map<String, Integer> quotas = new TreeMap<>();
        for (int i = 0; i < skills.size(); i++) {
            if (seats[i] == 0) {
                continue;
            }
            PlannerSkill skill = skills.get(i);
            ToLongFunction<PlannerTopic> weight = topic -> Math.round(topic.weight() * WEIGHT_SCALE)
                    * (linked.contains(skill.topicKey(topic)) ? RESUME_TOPIC_FACTOR : 1);
            List<PlannerTopic> topics = skill.topics().stream()
                    .filter(topic -> topic.range().overlaps(band))
                    .sorted(Comparator.comparingLong(weight).reversed()
                            .thenComparingInt((PlannerTopic topic) -> topic.range().distanceTo(band.start()))
                            .thenComparing(PlannerTopic::slug))
                    .toList();
            long[] weights = new long[topics.size()];
            for (int t = 0; t < weights.length; t++) {
                weights[t] = weight.applyAsLong(topics.get(t));
            }
            int[] topicSeats = apportion(weights, seats[i]);
            for (int t = 0; t < topicSeats.length; t++) {
                if (topicSeats[t] > 0) {
                    quotas.put(skill.topicKey(topics.get(t)), topicSeats[t]);
                }
            }
        }
        return quotas;
    }

    /**
     * How the questioning section uses the résumé; null without experiences, without a questioning section or without a PROJECT
     * stage.
     */
    private static @Nullable ResumePlan resumePlan(PlannerInput input, List<SectionPlan> sections) {
        SectionPlan questioning = sections.stream().filter(plan -> plan.section() == Section.QUESTIONING).findFirst().orElse(null);
        if (input.experiences().isEmpty() || questioning == null || questioning.slotsOf(InterviewStage.PROJECT) == 0) {
            return null;
        }
        List<PlannerExperience> experiences = input.experiences();
        Map<String, List<String>> topics = new TreeMap<>();
        Map<String, List<String>> skills = new TreeMap<>();
        Set<String> linkable = new LinkedHashSet<>();
        for (String topic : questioning.topicQuotas().keySet()) {
            List<String> ids = experiences.stream()
                    .filter(experience -> experience.topics().containsKey(topic))
                    .sorted(Comparator.comparingInt((PlannerExperience experience) -> experience.topics().get(topic)).reversed()
                            .thenComparingInt(PlannerExperience::priority)
                            .thenComparingInt(experiences::indexOf))
                    .map(PlannerExperience::id)
                    .toList();
            if (!ids.isEmpty()) {
                topics.put(topic, ids);
                linkable.addAll(ids);
            }
        }
        questioning.topicQuotas().keySet().stream().map(TopicSelector::skillOf).distinct().forEach(skill -> {
            List<String> ids = experiences.stream()
                    .filter(experience -> experience.skills().contains(skill)
                            || experience.topics().keySet().stream().anyMatch(topic -> TopicSelector.skillOf(topic).equals(skill)))
                    .sorted(Comparator.comparingInt(PlannerExperience::priority).thenComparingInt(experiences::indexOf))
                    .map(PlannerExperience::id)
                    .toList();
            if (!ids.isEmpty()) {
                skills.put(skill, ids);
                linkable.addAll(ids);
            }
        });
        if (linkable.isEmpty()) {
            return null;
        }
        int questions = Math.min(linkable.size(), questioning.slotsOf(InterviewStage.PROJECT));
        return new ResumePlan(questions, topics, skills);
    }

    /**
     * Slots per skill (skills are in slug order): one each while slots last, the surplus by role weight. When there are
     * fewer slots than skills, the heaviest skills (then the alphabetically first) get one each.
     */
    private static int[] skillSeats(List<PlannerSkill> skills, Set<String> favoured, int slots) {
        long[] weights = new long[skills.size()];
        for (int i = 0; i < weights.length; i++) {
            weights[i] = favoured.contains(skills.get(i).slug()) ? FAVOURED_SKILL_WEIGHT : OTHER_SKILL_WEIGHT;
        }
        int[] seats = new int[skills.size()];
        if (slots < skills.size()) {
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < skills.size(); i++) {
                order.add(i);
            }
            order.sort(Comparator.comparingLong((Integer i) -> weights[i]).reversed().thenComparingInt(i -> i));
            for (int rank = 0; rank < slots; rank++) {
                seats[order.get(rank)] = 1;
            }
            return seats;
        }
        int[] surplus = apportion(weights, slots - skills.size());
        for (int i = 0; i < seats.length; i++) {
            seats[i] = 1 + surplus[i];
        }
        return seats;
    }

    /**
     * Largest-remainder apportionment of {@code seats} over positive integer weights, in exact integer arithmetic.
     * Entries with equal remainders are served in index order, so callers order the weights by priority.
     */
    static int[] apportion(long[] weights, int seats) {
        long total = 0;
        for (long weight : weights) {
            total += weight;
        }
        int[] shares = new int[weights.length];
        long[] remainders = new long[weights.length];
        int assigned = 0;
        for (int i = 0; i < weights.length; i++) {
            long scaled = weights[i] * seats;
            shares[i] = (int) (scaled / total);
            remainders[i] = scaled % total;
            assigned += shares[i];
        }
        List<Integer> byRemainder = new ArrayList<>();
        for (int i = 0; i < weights.length; i++) {
            byRemainder.add(i);
        }
        byRemainder.sort(Comparator.comparingLong((Integer i) -> remainders[i]).reversed().thenComparingInt(i -> i));
        for (int k = 0; k < seats - assigned; k++) {
            shares[byRemainder.get(k)]++;
        }
        return shares;
    }
}
