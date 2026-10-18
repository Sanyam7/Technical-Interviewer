package com.techinterviewer.design;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.interview.domain.DifficultyBand;
import com.techinterviewer.interview.domain.ExpectedKeyPoint;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One curated system design problem ({@code resources/design-problems/<id>.json}).
 *
 * @param id        stable identifier, stored with the question ({@code questions.design_problem_id})
 * @param title     short name shown above the problem, for example "Design a URL shortener"
 * @param minLevel  lowest difficulty the problem is asked at
 * @param maxLevel  highest difficulty the problem is asked at; the evaluator is told the level, so the same problem asks for more depth
 *                  at a higher level
 * @param statement the product brief the candidate reads (Markdown)
 * @param facts     what the interviewer tells up front: scale, latency, constraints; shown with the statement
 * @param keyPoints what a strong design covers, 4 to {@value #MAX_KEY_POINTS}; server-only, judged cumulatively over the thread
 * @param probes    the realistic scenario to probe a dimension with, for the dimensions this problem makes interesting; the others use
 *                  {@link DesignProbes#generic}
 */
public record DesignProblem(
        String id,
        String title,
        int minLevel,
        int maxLevel,
        String statement,
        List<String> facts,
        List<ExpectedKeyPoint> keyPoints,
        Map<Criterion, String> probes) {

    /** The evaluator's schema judges at most ten key points. */
    public static final int MAX_KEY_POINTS = 10;
    public static final int MAX_FACTS = 12;
    private static final Pattern ID = Pattern.compile("[a-z0-9-]{1,60}");

    public DesignProblem {
        Require.notBlank(id, "id");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("id must be 1 to 60 lower-case letters, digits or '-' but was '" + id + "'");
        }
        Require.notBlank(title, "title", 120);
        Require.between(minLevel, DifficultyBand.LOWEST, DifficultyBand.HIGHEST, "minLevel");
        Require.between(maxLevel, minLevel, DifficultyBand.HIGHEST, "maxLevel");
        Require.notBlank(statement, "statement", 4000);
        if (statement.length() < 80) {
            throw new IllegalArgumentException("the statement of " + id + " is too short to describe a product");
        }
        facts = Immutables.list(facts);
        Require.between(facts.size(), 1, MAX_FACTS, "number of facts");
        facts.forEach(fact -> Require.notBlank(fact, "fact", 300));
        keyPoints = Immutables.list(keyPoints);
        Require.between(keyPoints.size(), 4, MAX_KEY_POINTS, "number of key points");
        Set<String> ids = new HashSet<>();
        keyPoints.forEach(point -> {
            if (!ids.add(point.id())) {
                throw new IllegalArgumentException("key point " + point.id() + " of " + id + " is defined twice");
            }
        });
        Map<Criterion, String> checked = new EnumMap<>(Criterion.class);
        Require.nonNull(probes, "probes").forEach((criterion, probe) -> {
            if (!DesignProbes.DIMENSIONS.contains(criterion)) {
                throw new IllegalArgumentException(criterion + " is not a dimension of the system design rubric");
            }
            Require.notBlank(probe, "probe of " + criterion, 400);
            if (!probe.strip().endsWith("?")) {
                throw new IllegalArgumentException("the probe of " + criterion + " in " + id + " must be a question");
            }
            checked.put(criterion, probe.strip());
        });
        probes = Map.copyOf(checked);
    }

    /** Whether the problem is asked at {@code level}. */
    public boolean fits(int level) {
        return level >= minLevel && level <= maxLevel;
    }

    /** The scenario to probe {@code criterion} with: this problem's own, or the generic one. */
    public String probe(Criterion criterion) {
        return probes.getOrDefault(criterion, DesignProbes.generic(criterion));
    }
}
