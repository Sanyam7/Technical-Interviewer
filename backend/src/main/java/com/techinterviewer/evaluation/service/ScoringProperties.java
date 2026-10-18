package com.techinterviewer.evaluation.service;

import com.techinterviewer.evaluation.ScoringPolicy;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.Evaluator;
import java.math.BigDecimal;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The configurable part of scoring (AI_ARCHITECTURE.md section 2.10): weight tables and skill profiles. Everything is
 * optional; without configuration the built-in weights of {@code RubricRegistry} apply and no skill has a profile.
 * The tables are validated when the {@link ScoringPolicy} is built, at startup.
 *
 * <pre>
 * app.evaluation.scoring:
 *   weights:                      # replaces an evaluator's base table (all of its criteria, adding up to 1)
 *     technical: {correctness: 0.30, completeness: 0.10, depth: 0.20, practical-understanding: 0.15, reasoning: 0.15, communication: 0.10}
 *   skills:
 *     react:                      # a catalog skill slug, or a coding language for coding answers
 *       focus: [component architecture and composition, ...]
 *       weights:
 *         technical: {...}
 * </pre>
 *
 * @param weights base weight tables by evaluator, each replacing the built-in one
 * @param skills  skill profiles by key
 */
@ConfigurationProperties("app.evaluation.scoring")
public record ScoringProperties(
        @Nullable Map<Evaluator, Map<Criterion, BigDecimal>> weights,
        @Nullable Map<String, ScoringPolicy.SkillSpec> skills) {

    /** The validated policy. */
    public ScoringPolicy policy() {
        return ScoringPolicy.of(weights == null ? Map.of() : weights, skills == null ? Map.of() : skills);
    }
}
