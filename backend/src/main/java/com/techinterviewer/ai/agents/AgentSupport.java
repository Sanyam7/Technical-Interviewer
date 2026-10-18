package com.techinterviewer.ai.agents;

import com.techinterviewer.ai.agents.guard.InjectionHeuristics;
import com.techinterviewer.ai.agents.output.KeyPointDraft;
import com.techinterviewer.ai.prompt.PromptValue;
import com.techinterviewer.interview.domain.ExpectedKeyPoint;
import com.techinterviewer.rag.context.KnowledgeContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Small helpers the agents share: formatting data for a prompt (never instructions; those live in the prompt files)
 * and narrowing a schema for one request.
 */
final class AgentSupport {

    /** How a list without entries reads in a prompt. */
    static final String NONE = "(none)";

    private AgentSupport() {
    }

    /** One {@code - item} line per entry, each shortened, keeping the last {@code max} entries; {@link #NONE} when empty. */
    static String bullets(List<String> items, int max, int maxLength) {
        if (items.isEmpty()) {
            return NONE;
        }
        List<String> kept = items.size() > max ? items.subList(items.size() - max, items.size()) : items;
        StringBuilder text = new StringBuilder();
        for (String item : kept) {
            text.append("- ").append(shorten(item.strip().replaceAll("\\s+", " "), maxLength)).append('\n');
        }
        return text.toString().stripTrailing();
    }

    static String shorten(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength - 3) + "...";
    }

    /** {@code - kp1: text} lines. */
    static String keyPointLines(List<ExpectedKeyPoint> keyPoints) {
        if (keyPoints.isEmpty()) {
            return NONE;
        }
        StringBuilder text = new StringBuilder();
        keyPoints.forEach(point -> text.append("- ").append(point.id()).append(": ").append(point.text()).append('\n'));
        return text.toString().stripTrailing();
    }

    /** Restricts a string property of an object schema to the given values. */
    static void restrictTo(ObjectNode objectSchema, String property, List<String> values) {
        ObjectNode target = (ObjectNode) objectSchema.path("properties").path(property);
        if (target.isMissingNode()) {
            throw new IllegalStateException("the schema has no property '" + property + "'");
        }
        ArrayNode allowed = target.putArray("enum");
        values.forEach(allowed::add);
    }

    /** Fixes an integer property of an object schema to one value. */
    static void fix(ObjectNode objectSchema, String property, int value) {
        ObjectNode target = (ObjectNode) objectSchema.path("properties").path(property);
        target.put("minimum", value);
        target.put("maximum", value);
    }

    /** The problems with a list of drafted key points: duplicate ids and text that leaks into the question. */
    static List<String> keyPointProblems(String question, List<KeyPointDraft> keyPoints) {
        List<String> problems = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (KeyPointDraft point : keyPoints) {
            if (!ids.add(point.id())) {
                problems.add("expectedKeyPoints: the id " + point.id() + " is used twice; ids must be unique");
            }
            if (InjectionHeuristics.suspected(point.text())) {
                // A key point goes to the evaluator as trusted text, but the model that wrote it may have been shown a candidate's
                // answer (a follow-up): text that addresses a grader does not get that far (SECURITY.md, AI-6).
                problems.add("expectedKeyPoints: " + point.id() + " must describe an idea a good answer states, not give an instruction");
            }
            if (com.techinterviewer.ai.agents.guard.QuestionChecks.leaks(question, point.text())) {
                problems.add("prompt: it contains the text of key point " + point.id() + ", which gives the answer away");
            }
        }
        return problems;
    }

    static List<ExpectedKeyPoint> keyPoints(List<KeyPointDraft> drafts) {
        return keyPoints(drafts, KnowledgeContext.DISABLED);
    }

    /** The key points with the chunks they cite (the cited reference tokens mapped to chunk ids). */
    static List<ExpectedKeyPoint> keyPoints(List<KeyPointDraft> drafts, KnowledgeContext context) {
        return drafts.stream()
                .map(draft -> new ExpectedKeyPoint(draft.id(), draft.text().strip(), chunkIds(draft.sourceTokens(), context)))
                .toList();
    }

    // ---- reference material (RAG) --------------------------------------------------------------------------------

    /** The reference material as a prompt value: retrieved text is data, never instructions, so it is fenced. */
    static PromptValue references(KnowledgeContext context) {
        return PromptValue.untrusted("reference_material", context.text());
    }

    /**
     * Restricts the {@code sources} array of an item schema to the reference tokens of this prompt, or to nothing when
     * the prompt has no reference material: a model can then only cite material it was shown.
     */
    static void restrictSources(ObjectNode itemSchema, KnowledgeContext context) {
        ObjectNode sources = (ObjectNode) itemSchema.path("properties").path("sources");
        if (sources.isMissingNode()) {
            throw new IllegalStateException("the schema has no property 'sources'");
        }
        restrictTokens(sources, context);
    }

    /** Restricts the items of an array schema to the reference tokens, or the array to no items. */
    static void restrictTokens(ObjectNode arraySchema, KnowledgeContext context) {
        ObjectNode items = arraySchema.putObject("items");
        items.put("type", "string");
        if (context.isEmpty()) {
            arraySchema.put("maxItems", 0);
        } else {
            ArrayNode allowed = items.putArray("enum");
            context.tokens().forEach(allowed::add);
        }
    }

    /** The items schema of an array property of an object schema. */
    static ObjectNode itemsOf(ObjectNode objectSchema, String arrayProperty) {
        ObjectNode items = (ObjectNode) objectSchema.path("properties").path(arrayProperty).path("items");
        if (items.isMissingNode()) {
            throw new IllegalStateException("the schema has no array property '" + arrayProperty + "'");
        }
        return items;
    }

    /** The problems with cited reference tokens: each must be one the prompt listed. */
    static List<String> sourceProblems(String field, List<String> tokens, KnowledgeContext context) {
        List<String> problems = new ArrayList<>();
        for (String token : tokens) {
            if (context.chunkOf(token).isEmpty()) {
                problems.add(field + ": " + token + " is not one of the given references "
                        + (context.isEmpty() ? "(there are none: leave it empty)" : context.tokens().toString()));
            }
        }
        return problems;
    }

    /** The chunk ids of cited tokens, in citation order, without repeats; unknown tokens are ignored. */
    static List<UUID> chunkIds(List<String> tokens, KnowledgeContext context) {
        return tokens.stream().map(context::chunkOf).flatMap(java.util.Optional::stream)
                .map(chunk -> chunk.chunkId()).distinct().toList();
    }
}
