package com.techinterviewer.evaluation;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.Evaluator;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Which weights score an answer, and what the evaluator should pay most attention to (AI_ARCHITECTURE.md section 2.10).
 *
 * <p>Every evaluator has <em>base weights</em>: the defaults of {@link RubricRegistry}, each evaluator's table replaceable
 * by configuration. A <em>skill profile</em> adapts the evaluation to what the question is about. It may replace the
 * weights of any evaluator but the introduction for questions on its skill, and it names <em>focus areas</em> that the
 * evaluator is told to judge depth, practical understanding and reasoning by (for React: component architecture, state
 * management, rendering performance). The profile of a question is its catalog skill. Coding and design problems have no
 * topic: a coding answer uses the profile of the language it is written in (a Java coding answer uses {@code java}), a
 * design problem the {@value #DESIGN_PROFILE} profile.
 *
 * <p>The criteria never change: they are the evaluator's contract with its prompt. Every weight table must name exactly
 * the evaluator's criteria and add up to exactly 1, checked when the policy is built, so a wrong configuration stops the
 * application at startup instead of scoring answers with weights nobody meant. The model is never shown the weights.
 */
public final class ScoringPolicy {

    /** The profile of a system design problem, which has no catalog topic: the catalog skill of the same name. */
    public static final String DESIGN_PROFILE = "system-design";
    public static final int MAX_FOCUS_AREAS = 8;
    public static final int MAX_FOCUS_LENGTH = 120;
    private static final Pattern PROFILE_KEY = Pattern.compile("[a-z0-9][a-z0-9-]{0,19}");

    /**
     * A skill profile as configured.
     *
     * @param focus   what matters most for the skill, in a few words each; may be empty
     * @param weights weight tables that replace the base weights for questions on this skill, per evaluator; may be empty
     */
    public record SkillSpec(@Nullable List<String> focus, @Nullable Map<Evaluator, Map<Criterion, BigDecimal>> weights) {
    }

    /** A validated skill profile: its focus areas and the rubric of every evaluator it changes. */
    public record SkillProfile(String key, List<String> focus, Map<Evaluator, Rubric> rubrics) {

        public SkillProfile {
            focus = Immutables.list(focus);
            rubrics = Immutables.enumMap(rubrics, Evaluator.class);
        }
    }

    private final Map<Evaluator, Rubric> base;
    private final Map<String, SkillProfile> profiles;

    private ScoringPolicy(Map<Evaluator, Rubric> base, Map<String, SkillProfile> profiles) {
        this.base = base;
        this.profiles = profiles;
    }

    /** The built-in weights and no skill profiles. */
    public static ScoringPolicy defaults() {
        return of(Map.of(), Map.of());
    }

    /**
     * @param weights base weight tables that replace the defaults, per evaluator; evaluators not named keep theirs
     * @param skills  skill profiles by key (a catalog skill slug, or a coding language in lower case)
     * @throws IllegalArgumentException naming the offending table when a table does not weigh exactly its evaluator's
     *                                  criteria, does not add up to 1, or a profile is malformed
     */
    public static ScoringPolicy of(Map<Evaluator, Map<Criterion, BigDecimal>> weights, Map<String, SkillSpec> skills) {
        Map<Evaluator, Rubric> base = new EnumMap<>(Evaluator.class);
        for (Evaluator evaluator : Evaluator.values()) {
            Rubric defaults = RubricRegistry.forEvaluator(evaluator);
            Map<Criterion, BigDecimal> table = weights == null ? null : weights.get(evaluator);
            base.put(evaluator, table == null ? defaults
                    : rubric(defaults.id(), evaluator, table, "app.evaluation.scoring.weights." + name(evaluator)));
        }
        Map<String, SkillProfile> profiles = new TreeMap<>();
        if (skills != null) {
            skills.forEach((key, spec) -> profiles.put(key, profile(key, spec, base)));
        }
        return new ScoringPolicy(base, profiles);
    }

    private static SkillProfile profile(String key, SkillSpec spec, Map<Evaluator, Rubric> base) {
        String path = "app.evaluation.scoring.skills." + key;
        if (key == null || !PROFILE_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException(path + ": a profile key is a skill slug or a language in lower case, 1 to 20 "
                    + "letters, digits or '-'");
        }
        if (spec == null) {
            throw new IllegalArgumentException(path + ": the profile is empty");
        }
        List<String> focus = spec.focus() == null ? List.of() : spec.focus().stream().map(String::strip).toList();
        if (focus.size() > MAX_FOCUS_AREAS || focus.stream().anyMatch(area -> area.isEmpty() || area.length() > MAX_FOCUS_LENGTH)) {
            throw new IllegalArgumentException(path + ".focus: at most " + MAX_FOCUS_AREAS + " areas of 1 to " + MAX_FOCUS_LENGTH
                    + " characters each");
        }
        Map<Evaluator, Rubric> rubrics = new EnumMap<>(Evaluator.class);
        if (spec.weights() != null) {
            spec.weights().forEach((evaluator, table) -> {
                String tablePath = path + ".weights." + name(evaluator);
                if (evaluator == Evaluator.INTRO) {
                    throw new IllegalArgumentException(tablePath + ": the introduction is not technical and has no skill profile");
                }
                rubrics.put(evaluator, rubric(base.get(evaluator).id() + "+" + key, evaluator, table, tablePath));
            });
        }
        return new SkillProfile(key, focus, rubrics);
    }

    private static Rubric rubric(String id, Evaluator evaluator, Map<Criterion, BigDecimal> table, String path) {
        Set<Criterion> expected = RubricRegistry.criteria(evaluator);
        Set<Criterion> given = table == null || table.isEmpty() ? EnumSet.noneOf(Criterion.class) : EnumSet.copyOf(table.keySet());
        if (!given.equals(expected)) {
            Set<Criterion> missing = EnumSet.copyOf(expected);
            missing.removeAll(given);
            Set<Criterion> unknown = EnumSet.noneOf(Criterion.class);
            unknown.addAll(given);
            unknown.removeAll(expected);
            throw new IllegalArgumentException(path + ": must weigh exactly the criteria " + expected + "; missing " + missing
                    + ", not rated by this evaluator " + unknown);
        }
        List<CriterionWeight> weights = new ArrayList<>();
        try {
            // Rubric order: the order of the default rubric, so every table of one evaluator lists its criteria alike.
            for (CriterionWeight criterion : RubricRegistry.forEvaluator(evaluator).weights()) {
                weights.add(new CriterionWeight(criterion.criterion(), table.get(criterion.criterion())));
            }
            return new Rubric(id, evaluator, weights);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(path + ": " + invalid.getMessage(), invalid);
        }
    }

    private static String name(Evaluator evaluator) {
        return evaluator.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * The profile key of a question: its language for a coding answer, its skill otherwise, and {@value #DESIGN_PROFILE}
     * for a system design problem without a topic; null for the introduction.
     *
     * @param skillSlug      slug of the question's catalog skill, null when it has no topic
     * @param codingLanguage language of a code answer, null when there is none
     */
    public static @Nullable String profileKey(Evaluator evaluator, @Nullable String skillSlug, @Nullable String codingLanguage) {
        return switch (evaluator) {
            case INTRO -> null;
            case CODING -> codingLanguage != null ? codingLanguage.strip().toLowerCase(Locale.ROOT) : skillSlug;
            case TECHNICAL -> skillSlug;
            case SYSTEM_DESIGN -> skillSlug != null ? skillSlug : DESIGN_PROFILE;
        };
    }

    /** The rubric that scores an answer: the profile's weights for the evaluator when it has some, the base weights otherwise. */
    public Rubric rubric(Evaluator evaluator, @Nullable String profileKey) {
        return profile(profileKey).map(profile -> profile.rubrics().get(evaluator)).orElse(base.get(evaluator));
    }

    /** The focus areas the evaluator is given for a question: the profile's, or none. Never for the introduction. */
    public List<String> focus(Evaluator evaluator, @Nullable String profileKey) {
        return evaluator == Evaluator.INTRO ? List.of() : profile(profileKey).map(SkillProfile::focus).orElse(List.of());
    }

    public Optional<SkillProfile> profile(@Nullable String profileKey) {
        return profileKey == null ? Optional.empty() : Optional.ofNullable(profiles.get(profileKey));
    }

    /** The base rubric of every evaluator. */
    public Map<Evaluator, Rubric> base() {
        return Immutables.enumMap(base, Evaluator.class);
    }

    public Map<String, SkillProfile> profiles() {
        return Map.copyOf(profiles);
    }

    /** One line per weight table, for the startup log: which weights this process scores with. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        base.values().forEach(rubric -> lines.add(line(rubric)));
        profiles.values().forEach(profile -> {
            profile.rubrics().values().forEach(rubric -> lines.add(line(rubric)));
            if (!profile.focus().isEmpty()) {
                lines.add(profile.key() + " focus: " + String.join("; ", profile.focus()));
            }
        });
        return lines;
    }

    private static String line(Rubric rubric) {
        StringBuilder text = new StringBuilder(rubric.id()).append(':');
        rubric.weights().forEach(weight -> text.append(' ').append(weight.criterion()).append('=').append(weight.weight().toPlainString()));
        return text.toString();
    }
}
