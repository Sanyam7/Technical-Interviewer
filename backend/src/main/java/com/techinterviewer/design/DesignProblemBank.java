package com.techinterviewer.design;

import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.interview.domain.ExpectedKeyPoint;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every curated system design problem, loaded once from {@code classpath:design-problems/*.json} and validated. A file that
 * does not validate stops the application at startup, like an invalid prompt template.
 */
public final class DesignProblemBank {

    public static final String LOCATION = "classpath*:design-problems/*.json";

    private static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    /** The file as written; {@link #problem()} validates it. */
    private record ProblemFile(String id, String title, List<Integer> levels, String statement, List<String> facts,
            List<KeyPointFile> keyPoints, Map<Criterion, String> probes) {

        DesignProblem problem() {
            if (levels == null || levels.size() != 2) {
                throw new IllegalArgumentException("levels must be [lowest, highest]");
            }
            return new DesignProblem(id, title, levels.get(0), levels.get(1), statement, facts,
                    keyPoints == null ? List.of() : keyPoints.stream().map(point -> new ExpectedKeyPoint(point.id(), point.text(), List.of())).toList(),
                    probes == null ? Map.of() : probes);
        }
    }

    private record KeyPointFile(String id, String text) {
    }

    private final Map<String, DesignProblem> byId;

    public DesignProblemBank(Collection<DesignProblem> problems) {
        Map<String, DesignProblem> map = new LinkedHashMap<>();
        problems.stream().sorted(Comparator.comparingInt(DesignProblem::minLevel).thenComparing(DesignProblem::id)).forEach(problem -> {
            if (map.put(problem.id(), problem) != null) {
                throw new IllegalStateException("design problem " + problem.id() + " is defined twice");
            }
        });
        this.byId = Map.copyOf(map);
    }

    public static DesignProblemBank load() {
        try {
            List<DesignProblem> problems = new ArrayList<>();
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources(LOCATION)) {
                problems.add(parse(resource.getContentAsString(StandardCharsets.UTF_8), String.valueOf(resource.getFilename())));
            }
            return new DesignProblemBank(problems);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read the design problems", unreadable);
        }
    }

    static DesignProblem parse(String content, String file) {
        DesignProblem problem;
        try {
            problem = JSON.readValue(content, ProblemFile.class).problem();
        } catch (JacksonException | IllegalArgumentException invalid) {
            throw new IllegalStateException("design problem " + file + " is invalid: " + invalid.getMessage(), invalid);
        }
        if (!file.equals(problem.id() + ".json")) {
            throw new IllegalStateException("design problem " + problem.id() + " must be in " + problem.id() + ".json, not " + file);
        }
        return problem;
    }

    public Optional<DesignProblem> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** All problems, the easiest first. */
    public List<DesignProblem> all() {
        return byId.values().stream()
                .sorted(Comparator.comparingInt(DesignProblem::minLevel).thenComparing(DesignProblem::id))
                .toList();
    }

    /**
     * A problem asked at {@code level} that is not in {@code excluded}, or empty. Which one of several is decided by {@code seed}
     * (the interview), so two interviews at the same level do not always get the same problem and a retried request chooses the
     * same one.
     */
    public Optional<DesignProblem> choose(int level, Set<String> excluded, long seed) {
        List<DesignProblem> candidates = all().stream()
                .filter(problem -> problem.fits(level) && !excluded.contains(problem.id()))
                .toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(candidates.get((int) Math.floorMod(seed, (long) candidates.size())));
    }
}
