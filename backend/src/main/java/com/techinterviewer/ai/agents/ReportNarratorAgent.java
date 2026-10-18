package com.techinterviewer.ai.agents;

import com.techinterviewer.ai.agents.output.NarrativeDraft;
import com.techinterviewer.ai.prompt.PromptRegistry;
import com.techinterviewer.ai.prompt.PromptTemplate;
import com.techinterviewer.ai.prompt.PromptValue;
import com.techinterviewer.ai.routing.AiTask;
import com.techinterviewer.ai.structured.JsonSchemaRegistry;
import com.techinterviewer.ai.structured.StructuredCall;
import com.techinterviewer.ai.structured.StructuredOutputClient;
import com.techinterviewer.ai.structured.StructuredResult;
import com.techinterviewer.interview.port.AiUnavailableException;
import com.techinterviewer.interview.port.EvaluationDigest;
import com.techinterviewer.interview.port.NarrativeItem;
import com.techinterviewer.interview.port.NarrativeRequest;
import com.techinterviewer.interview.port.ReportNarrative;
import com.techinterviewer.interview.port.ReportNarrator;
import com.techinterviewer.interview.port.SkillScoreSummary;
import com.techinterviewer.interview.port.StudyReference;
import com.techinterviewer.rag.KnowledgeRetriever;
import com.techinterviewer.rag.context.KnowledgeContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * <b>Final report generator, written part</b>. Everything measurable in the report (scores per section, skill and
 * topic, the overall score, confidence, the recommendation band, coverage, the outcome) is computed deterministically
 * by {@code ReportComposer}; this agent only writes the summary, strengths, improvement areas and study plan, from the
 * evaluation records and the computed facts, never from a transcript.
 *
 * <p>Each evaluation is shown to the model under a short reference ({@code e1}, {@code e2}, ...); the schema restricts
 * citations to these references and the agent maps them back to evaluation ids, so an item can only cite evaluations of
 * this interview (the backend verifies it again). When part of the interview could not be assessed the prompt says so,
 * and the narrative must not describe that part.
 *
 * <p>Grounded recommendations: each evaluation comes with its knowledge gaps (detected deterministically from the
 * missed key points and factual errors), and reference material is retrieved for the gaps of the weakest answers. The
 * study plan targets those gaps, and {@code furtherReading} may cite the material (r1, r2, ...), which the agent maps to
 * the sections the report lists as further reading.
 *
 * <p>Any failure is {@link AiUnavailableException}: a report without narrative is still a complete report.
 */
public final class ReportNarratorAgent implements ReportNarrator {

    static final String PROMPT_ID = "report-narrative";

    private static final Logger log = LoggerFactory.getLogger(ReportNarratorAgent.class);

    private final StructuredOutputClient client;
    private final PromptRegistry prompts;
    private final JsonSchemaRegistry schemas;
    private final KnowledgeRetriever knowledge;

    public ReportNarratorAgent(StructuredOutputClient client, PromptRegistry prompts, JsonSchemaRegistry schemas, KnowledgeRetriever knowledge) {
        this.client = client;
        this.prompts = prompts;
        this.schemas = schemas;
        this.knowledge = knowledge;
    }

    @Override
    public ReportNarrative narrate(NarrativeRequest request) {
        Map<String, UUID> references = new LinkedHashMap<>();
        int number = 1;
        for (EvaluationDigest evaluation : request.evaluations()) {
            references.put("e" + number++, evaluation.evaluationId());
        }
        PromptTemplate template = prompts.latest(PROMPT_ID);
        ObjectNode schema = schemas.get(template.schema());
        for (String list : List.of("strengths", "improvementAreas")) {
            ObjectNode item = (ObjectNode) schema.path("properties").path(list).path("items");
            ArrayNode allowed = ((ObjectNode) item.path("properties").path("evidence").path("items")).putArray("enum");
            references.keySet().forEach(allowed::add);
        }
        KnowledgeContext context = knowledge.ground(GroundingQueries.report(request));
        if (schema.path("properties").has("furtherReading")) {
            AgentSupport.restrictTokens((ObjectNode) schema.path("properties").path("furtherReading"), context);
        }
        Map<String, PromptValue> values = values(request, references);
        values.put("references", AgentSupport.references(context));
        try {
            StructuredResult<NarrativeDraft> result = client.generate(new StructuredCall<>(
                    AiTask.REPORT_WRITING, template, values, schema, NarrativeDraft.class,
                    draft -> problems(references, draft, context), request.interviewId(), context.recordedChunkIds()));
            NarrativeDraft draft = result.value();
            return new ReportNarrative(
                    draft.summary().strip(),
                    items(draft.strengths(), references),
                    items(draft.improvementAreas(), references),
                    draft.studyPlan().stream().map(String::strip).toList(),
                    reading(draft.readingTokens(), context),
                    result.promptVersion(),
                    result.llmCallId());
        } catch (AiUnavailableException unavailable) {
            throw unavailable;
        } catch (RuntimeException failure) {
            log.warn("The report narrative of interview {} could not be written: {}", request.interviewId(), failure.getMessage());
            throw new AiUnavailableException(AiUnavailableException.DEFAULT_DETAIL, failure);
        }
    }

    private static Map<String, PromptValue> values(NarrativeRequest request, Map<String, UUID> references) {
        Map<String, PromptValue> values = new HashMap<>();
        values.put("role", PromptValue.trusted(request.role().name()));
        values.put("experience", PromptValue.trusted(request.experience().name()));
        values.put("interviewType", PromptValue.trusted(request.type().name()));
        values.put("recommendation", PromptValue.trusted(request.recommendation().name()));
        values.put("overallScore", PromptValue.trusted(String.format(Locale.ROOT, "%.1f", request.overallScore())));
        values.put("coveragePercent", PromptValue.trusted(Integer.toString((int) Math.floor(request.assessmentCoverage() * 100))));
        values.put("unassessableAnswers", PromptValue.trusted(Integer.toString(request.unassessableAnswers())));
        List<String> skills = new ArrayList<>();
        for (SkillScoreSummary skill : request.skillScores()) {
            skills.add(String.format(Locale.ROOT, "%s: %.1f (confidence %s, %d answer(s))",
                    skill.skillName(), skill.score(), skill.confidence(), skill.evidenceCount()));
        }
        values.put("skillScores", PromptValue.trusted(AgentSupport.bullets(skills, 20, 200)));
        StringBuilder evaluations = new StringBuilder();
        int index = 0;
        List<String> keys = List.copyOf(references.keySet());
        for (EvaluationDigest evaluation : request.evaluations()) {
            evaluations.append(String.format(Locale.ROOT, "%s | %s | %s | difficulty %d | score %.1f | %s%n",
                    keys.get(index++), evaluation.section(), evaluation.topic() == null ? "-" : evaluation.topic(),
                    evaluation.difficulty(), evaluation.score(), evaluation.summary().replaceAll("\\s+", " ")));
            for (String gap : evaluation.gaps()) {
                evaluations.append("    gap: ").append(gap.replaceAll("\\s+", " ")).append('\n');
            }
        }
        // The summaries were written by the evaluator about the candidate's words: data, not instructions.
        values.put("evaluations", PromptValue.untrusted("evaluations", evaluations.toString()));
        return values;
    }

    static List<String> problems(Map<String, UUID> references, NarrativeDraft draft) {
        return problems(references, draft, KnowledgeContext.DISABLED);
    }

    /** The cited material as further reading: each section once, in citation order. */
    private static List<StudyReference> reading(List<String> tokens, KnowledgeContext context) {
        return tokens.stream().distinct().map(context::chunkOf).flatMap(java.util.Optional::stream)
                .map(chunk -> new StudyReference(chunk.chunkId(), chunk.title(), chunk.headingPath(), chunk.sourcePath()))
                .toList();
    }

    static List<String> problems(Map<String, UUID> references, NarrativeDraft draft, KnowledgeContext context) {
        List<String> problems = new ArrayList<>();
        List<NarrativeDraft.Item> items = new ArrayList<>(draft.strengths());
        items.addAll(draft.improvementAreas());
        for (NarrativeDraft.Item item : items) {
            for (String reference : item.evidence()) {
                if (!references.containsKey(reference)) {
                    problems.add("evidence: " + reference + " is not one of the given references " + references.keySet());
                }
            }
        }
        problems.addAll(AgentSupport.sourceProblems("furtherReading", draft.readingTokens(), context));
        return problems;
    }

    private static List<NarrativeItem> items(List<NarrativeDraft.Item> drafts, Map<String, UUID> references) {
        return drafts.stream()
                .map(item -> new NarrativeItem(item.text().strip(),
                        new LinkedHashSet<>(item.evidence()).stream().map(references::get).toList()))
                .toList();
    }
}
