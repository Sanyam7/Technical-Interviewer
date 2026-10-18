package com.techinterviewer.evaluation;

import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.Evaluator;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.Section;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The criteria every evaluator rates, and their built-in default weights (AI_ARCHITECTURE.md section 2.10).
 *
 * <p>The <em>criteria</em> of an evaluator are a contract with its prompt and schema and are fixed here; the rubric id
 * names that contract and is stored with every question ({@code questions.rubric_id}). The <em>weights</em> below are
 * only defaults: {@link ScoringPolicy} applies the configured weights ({@code app.evaluation.scoring}), which may
 * override them per evaluator and per skill.
 *
 * <ul>
 *   <li>TECHNICAL ({@code technical.v2}): correctness .30, depth .20, reasoning .15, practical understanding .15,
 *       completeness .10, communication .10.
 *   <li>INTRO: communication only (the introduction is not a technical question).
 *   <li>CODING ({@code coding.v2}, AI_ARCHITECTURE.md section 2.11): correctness .35, algorithm .15, time complexity .15,
 *       space complexity .05, edge cases .10, readability .10, maintainability .10. When tests ran, correctness is the
 *       test result, not the model's rating.
 *   <li>SYSTEM_DESIGN ({@code system-design.v2}, AI_ARCHITECTURE.md section 2.12): requirements .10, architecture .12, API
 *       design .06, data model .10, caching .07, queues .06, scalability .12, availability .07, reliability .07, failure
 *       handling .07, security .05, observability .04, trade-offs .07. Every dimension is rated on the whole design thread,
 *       and a rating above 1 needs a quote of the candidate's own words.
 * </ul>
 *
 * Correctness weighs most because a fluent wrong answer must not pass; communication least because the interview
 * assesses engineering, and length or polish must not buy points.
 */
public final class RubricRegistry {

    private static final Map<Evaluator, Rubric> RUBRICS = build();

    private RubricRegistry() {
    }

    /** The rubric of {@code evaluator} with its default weights. */
    public static Rubric forEvaluator(Evaluator evaluator) {
        return RUBRICS.get(evaluator);
    }

    /** The criteria {@code evaluator} rates: exactly these, whatever the configured weights. */
    public static Set<Criterion> criteria(Evaluator evaluator) {
        return RUBRICS.get(evaluator).criteria();
    }

    /**
     * The evaluator for a question. A follow-up is evaluated by the evaluator of the section its thread belongs to.
     *
     * @throws IllegalArgumentException for a follow-up in the introduction, which has none
     */
    public static Evaluator evaluatorFor(QuestionKind kind, Section section) {
        return switch (kind) {
            case INTRO -> Evaluator.INTRO;
            case TECHNICAL -> Evaluator.TECHNICAL;
            case CODING -> Evaluator.CODING;
            case SYSTEM_DESIGN -> Evaluator.SYSTEM_DESIGN;
            case FOLLOW_UP -> switch (section) {
                case QUESTIONING -> Evaluator.TECHNICAL;
                case CODING -> Evaluator.CODING;
                case SYSTEM_DESIGN -> Evaluator.SYSTEM_DESIGN;
                case INTRODUCTION -> throw new IllegalArgumentException("the introduction has no follow-up questions");
            };
        };
    }

    private static Map<Evaluator, Rubric> build() {
        Map<Evaluator, Rubric> rubrics = new EnumMap<>(Evaluator.class);
        rubrics.put(Evaluator.INTRO, new Rubric("intro.v1", Evaluator.INTRO, List.of(
                CriterionWeight.of(Criterion.COMMUNICATION, "1.00"))));
        rubrics.put(Evaluator.TECHNICAL, new Rubric("technical.v2", Evaluator.TECHNICAL, List.of(
                CriterionWeight.of(Criterion.CORRECTNESS, "0.30"),
                CriterionWeight.of(Criterion.COMPLETENESS, "0.10"),
                CriterionWeight.of(Criterion.DEPTH, "0.20"),
                CriterionWeight.of(Criterion.PRACTICAL_UNDERSTANDING, "0.15"),
                CriterionWeight.of(Criterion.REASONING, "0.15"),
                CriterionWeight.of(Criterion.COMMUNICATION, "0.10"))));
        rubrics.put(Evaluator.CODING, new Rubric("coding.v2", Evaluator.CODING, List.of(
                CriterionWeight.of(Criterion.CORRECTNESS, "0.35"),
                CriterionWeight.of(Criterion.ALGORITHM, "0.15"),
                CriterionWeight.of(Criterion.TIME_COMPLEXITY, "0.15"),
                CriterionWeight.of(Criterion.SPACE_COMPLEXITY, "0.05"),
                CriterionWeight.of(Criterion.EDGE_CASES, "0.10"),
                CriterionWeight.of(Criterion.READABILITY, "0.10"),
                CriterionWeight.of(Criterion.MAINTAINABILITY, "0.10"))));
        rubrics.put(Evaluator.SYSTEM_DESIGN, new Rubric("system-design.v2", Evaluator.SYSTEM_DESIGN, List.of(
                CriterionWeight.of(Criterion.REQUIREMENTS, "0.10"),
                CriterionWeight.of(Criterion.HIGH_LEVEL_DESIGN, "0.12"),
                CriterionWeight.of(Criterion.API_DESIGN, "0.06"),
                CriterionWeight.of(Criterion.DATA_MODEL, "0.10"),
                CriterionWeight.of(Criterion.CACHING, "0.07"),
                CriterionWeight.of(Criterion.MESSAGING, "0.06"),
                CriterionWeight.of(Criterion.SCALABILITY, "0.12"),
                CriterionWeight.of(Criterion.AVAILABILITY, "0.07"),
                CriterionWeight.of(Criterion.RELIABILITY, "0.07"),
                CriterionWeight.of(Criterion.FAILURE_HANDLING, "0.07"),
                CriterionWeight.of(Criterion.SECURITY, "0.05"),
                CriterionWeight.of(Criterion.OBSERVABILITY, "0.04"),
                CriterionWeight.of(Criterion.TRADE_OFFS, "0.07"))));
        return rubrics;
    }
}
