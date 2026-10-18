package com.techinterviewer.coding.problem;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Every curated coding problem, loaded once from {@code classpath:coding-problems/*.json} and validated. A file that
 * does not validate stops the application at startup, like an invalid prompt template.
 */
public final class ProblemBank {

    public static final String LOCATION = "classpath*:coding-problems/*.json";

    private final Map<String, CodingProblem> byId;

    public ProblemBank(Collection<CodingProblem> problems) {
        Map<String, CodingProblem> map = new LinkedHashMap<>();
        problems.stream().sorted(Comparator.comparingInt(CodingProblem::difficulty).thenComparing(CodingProblem::id)).forEach(problem -> {
            if (map.put(problem.id(), problem) != null) {
                throw new IllegalStateException("coding problem " + problem.id() + " is defined twice");
            }
        });
        this.byId = Map.copyOf(map);
    }

    public static ProblemBank load() {
        try {
            List<CodingProblem> problems = new ArrayList<>();
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources(LOCATION)) {
                String file = resource.getFilename();
                CodingProblem problem = ProblemParser.parse(resource.getContentAsString(StandardCharsets.UTF_8), String.valueOf(file));
                if (file == null || !file.equals(problem.id() + ".json")) {
                    throw new IllegalStateException("coding problem " + problem.id() + " must be in " + problem.id() + ".json, not " + file);
                }
                problems.add(problem);
            }
            return new ProblemBank(problems);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read the coding problems", unreadable);
        }
    }

    public Optional<CodingProblem> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** All problems, easiest first. */
    public List<CodingProblem> all() {
        return byId.values().stream()
                .sorted(Comparator.comparingInt(CodingProblem::difficulty).thenComparing(CodingProblem::id))
                .toList();
    }

    /**
     * A problem at exactly {@code difficulty} that is not in {@code excluded}, or empty. Which one of several is decided by
     * {@code seed} (the interview), so two interviews at the same level do not always get the same problem, and the same
     * interview always gets the same one (a retried request chooses the same problem).
     */
    public Optional<CodingProblem> choose(int difficulty, Set<String> excluded, long seed) {
        List<CodingProblem> candidates = all().stream()
                .filter(problem -> problem.difficulty() == difficulty && !excluded.contains(problem.id()))
                .toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(candidates.get((int) Math.floorMod(seed, (long) candidates.size())));
    }
}
