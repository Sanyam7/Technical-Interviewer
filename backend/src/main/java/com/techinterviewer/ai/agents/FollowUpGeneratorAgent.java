package com.techinterviewer.ai.agents;

import com.techinterviewer.ai.agents.guard.QuestionChecks;
import com.techinterviewer.ai.agents.output.DesignProbeDraft;
import com.techinterviewer.ai.agents.output.FollowUpDraft;
import com.techinterviewer.ai.prompt.PromptRegistry;
import com.techinterviewer.ai.prompt.PromptTemplate;
import com.techinterviewer.ai.prompt.PromptValue;
import com.techinterviewer.ai.routing.AiTask;
import com.techinterviewer.ai.structured.JsonSchemaRegistry;
import com.techinterviewer.ai.structured.StructuredCall;
import com.techinterviewer.ai.structured.StructuredOutputClient;
import com.techinterviewer.ai.structured.StructuredResult;
import com.techinterviewer.interview.domain.FollowUpMode;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.QuestionOrigin;
import com.techinterviewer.interview.port.DesignProbe;
import com.techinterviewer.interview.port.FollowUpTarget;
import com.techinterviewer.interview.port.GeneratedQuestion;
import com.techinterviewer.interview.port.QuestionRequest;
import com.techinterviewer.interview.port.ThreadTurn;
import com.techinterviewer.rag.KnowledgeRetriever;
import com.techinterviewer.rag.context.KnowledgeContext;
import com.techinterviewer.rag.dedup.QuestionDeduplicator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.node.ObjectNode;

/**
 * <b>Follow-up generator</b>: words the follow-up question the deterministic follow-up policy decided on. The policy
 * chose <i>whether</i> to follow up and <i>what</i> to aim at (a missed key point, a claim, or one step deeper); the
 * model only chooses the words.
 *
 * <p>Validated: the echoed mode equals the decision (also constrained in the schema); a gap probe does not reveal
 * the key point it probes; the follow-up does not repeat the question it follows; its own key point ids are unique and
 * not leaked. Its difficulty is the one the engine requested and its topic that of the thread.
 *
 * <p>Grounded: reference material about the parent question and what the follow-up aims at (the missed key point, or
 * edge cases for a deepening question) is shown to the model, so a gap probe can ask about the situation where the
 * missing idea matters, accurately. The follow-up's key points may cite it.
 *
 * <p>De-duplication: a follow-up is meant to stay close to the question it follows, so it is checked lexically against that
 * question only; it is embedded and stored like every question, so later root questions are compared with it.
 *
 * <p>A probe of a system design thread (PROBE_DIMENSION, AI_ARCHITECTURE.md section 2.12) has its own prompt: the model gets the
 * dimension the engine chose, the scenario to probe it with and the design discussed so far, and applies the scenario to the
 * candidate's own design. It writes no key points (every question of a design thread is judged against the problem's), it must
 * not repeat any question of the discussion, and it is not grounded in the knowledge base (it asks, it does not assert).
 */
public final class FollowUpGeneratorAgent {

    static final String PROMPT_ID = "follow-up-question";
    static final String PROBE_PROMPT_ID = "design-probe-question";

    private final StructuredOutputClient client;
    private final PromptRegistry prompts;
    private final JsonSchemaRegistry schemas;
    private final KnowledgeRetriever knowledge;
    private final QuestionDeduplicator deduplicator;

    public FollowUpGeneratorAgent(StructuredOutputClient client, PromptRegistry prompts, JsonSchemaRegistry schemas, KnowledgeRetriever knowledge) {
        this(client, prompts, schemas, knowledge, QuestionDeduplicator.NONE);
    }

    public FollowUpGeneratorAgent(StructuredOutputClient client, PromptRegistry prompts, JsonSchemaRegistry schemas, KnowledgeRetriever knowledge,
            QuestionDeduplicator deduplicator) {
        this.client = client;
        this.prompts = prompts;
        this.schemas = schemas;
        this.knowledge = knowledge;
        this.deduplicator = deduplicator;
    }

    public GeneratedQuestion generate(QuestionRequest request) {
        if (request.kind() != QuestionKind.FOLLOW_UP || request.followUp() == null) {
            throw new IllegalArgumentException("the follow-up generator only writes FOLLOW_UP questions");
        }
        FollowUpTarget target = request.followUp();
        if (target.mode() == FollowUpMode.PROBE_DIMENSION) {
            return probe(request, target.designProbe());
        }
        PromptTemplate template = prompts.latest(PROMPT_ID);
        ObjectNode schema = schemas.get(template.schema());
        AgentSupport.restrictTo(schema, "mode", List.of(target.mode().name()));
        KnowledgeContext context = knowledge.ground(GroundingQueries.followUp(request));
        AgentSupport.restrictSources(AgentSupport.itemsOf(schema, "expectedKeyPoints"), context);
        Map<String, PromptValue> values = values(request, target);
        values.put("references", AgentSupport.references(context));
        StructuredResult<FollowUpDraft> result = client.generate(new StructuredCall<>(
                AiTask.FOLLOW_UP_GENERATION, template, values, schema, FollowUpDraft.class,
                draft -> problems(target, draft, context), request.interviewId(), context.recordedChunkIds()));
        FollowUpDraft draft = result.value();
        String prompt = draft.prompt().strip();
        return new GeneratedQuestion(
                prompt,
                AgentSupport.keyPoints(draft.expectedKeyPoints(), context),
                null,
                QuestionOrigin.GENERATED,
                request.targetDifficulty(),
                request.topic() == null ? null : request.topic().key(),
                draft.estimatedMinutes(),
                context.chunkIds(),
                result.promptId(),
                result.promptVersion(),
                result.model(),
                result.llmCallId(),
                deduplicator.embed(request.interviewId(), prompt));
    }

    private GeneratedQuestion probe(QuestionRequest request, DesignProbe probe) {
        PromptTemplate template = prompts.latest(PROBE_PROMPT_ID);
        ObjectNode schema = schemas.get(template.schema());
        AgentSupport.restrictTo(schema, "criterion", List.of(probe.criterion().name()));
        Map<String, PromptValue> values = new HashMap<>();
        values.put("difficulty", PromptValue.trusted(Integer.toString(request.targetDifficulty())));
        values.put("criterion", PromptValue.trusted(probe.criterion().name()));
        values.put("meaning", PromptValue.trusted(probe.meaning()));
        values.put("scenario", PromptValue.trusted(probe.scenario()));
        values.put("problem", PromptValue.trusted(probe.problem()));
        values.put("discussion", PromptValue.untrusted("design_discussion", discussion(probe.discussion())));
        values.put("architecture", PromptValue.untrusted("architecture_diagram",
                probe.architecture() == null ? "(the candidate drew no diagram)" : probe.architecture()));
        StructuredResult<DesignProbeDraft> result = client.generate(new StructuredCall<>(
                AiTask.FOLLOW_UP_GENERATION, template, values, schema, DesignProbeDraft.class,
                draft -> probeProblems(probe, draft), request.interviewId(), null));
        String prompt = result.value().prompt().strip();
        return new GeneratedQuestion(
                prompt,
                List.of(),
                null,
                QuestionOrigin.GENERATED,
                request.targetDifficulty(),
                null,
                result.value().estimatedMinutes(),
                List.of(),
                result.promptId(),
                result.promptVersion(),
                result.model(),
                result.llmCallId(),
                deduplicator.embed(request.interviewId(), prompt));
    }

    /** The questions and answers of a design thread as the probe writer reads them; the candidate's words are fenced by the caller. */
    private static String discussion(List<ThreadTurn> turns) {
        StringBuilder text = new StringBuilder();
        int number = 1;
        for (ThreadTurn turn : turns) {
            text.append("Question ").append(number).append(": ").append(turn.questionPrompt()).append('\n')
                    .append("Answer ").append(number).append(": ").append(turn.skipped() ? "(skipped)" : turn.answerText()).append("\n\n");
            number++;
        }
        return text.toString().stripTrailing();
    }

    static List<String> probeProblems(DesignProbe probe, DesignProbeDraft draft) {
        List<String> problems = new ArrayList<>();
        if (draft.mode() != FollowUpMode.PROBE_DIMENSION) {
            problems.add("mode: must be PROBE_DIMENSION but was " + draft.mode());
        }
        if (draft.criterion() != probe.criterion()) {
            problems.add("criterion: must be " + probe.criterion() + " but was " + draft.criterion());
        }
        if (draft.prompt().strip().length() > 400) {
            problems.add("prompt: keep it to one or two sentences, at most 400 characters");
        }
        for (ThreadTurn turn : probe.discussion()) {
            if (QuestionChecks.nearDuplicate(draft.prompt(), turn.questionPrompt())) {
                problems.add("prompt: it repeats a question already asked in this discussion; ask about the scenario instead");
                break;
            }
        }
        return problems;
    }

    private static Map<String, PromptValue> values(QuestionRequest request, FollowUpTarget target) {
        Map<String, PromptValue> values = new HashMap<>();
        values.put("section", PromptValue.trusted(request.section().name()));
        values.put("topicName", PromptValue.trusted(request.topic() == null ? "the same subject as the question" : request.topic().topicName()));
        values.put("codingLanguage", PromptValue.trusted(request.codingLanguage() == null ? "not applicable" : request.codingLanguage()));
        values.put("difficulty", PromptValue.trusted(Integer.toString(request.targetDifficulty())));
        values.put("mode", PromptValue.trusted(target.mode().name()));
        values.put("parentQuestion", PromptValue.trusted(target.parentQuestionPrompt()));
        values.put("candidateAnswer", PromptValue.untrusted("candidate_answer", target.candidateAnswer()));
        values.put("targetKeyPoint", PromptValue.trusted(target.targetKeyPoint() == null ? "not applicable" : target.targetKeyPoint().text()));
        values.put("targetClaim", PromptValue.untrusted("candidate_claim", target.targetClaim() == null ? "" : target.targetClaim()));
        return values;
    }

    static List<String> problems(FollowUpTarget target, FollowUpDraft draft) {
        return problems(target, draft, KnowledgeContext.DISABLED);
    }

    static List<String> problems(FollowUpTarget target, FollowUpDraft draft, KnowledgeContext context) {
        List<String> problems = new ArrayList<>();
        for (var point : draft.expectedKeyPoints()) {
            problems.addAll(AgentSupport.sourceProblems("expectedKeyPoints." + point.id() + ".sources", point.sourceTokens(), context));
        }
        if (draft.mode() != target.mode()) {
            problems.add("mode: must be " + target.mode() + " but was " + draft.mode());
        }
        if (target.mode() == FollowUpMode.PROBE_GAP && target.targetKeyPoint() != null
                && QuestionChecks.leaks(draft.prompt(), target.targetKeyPoint().text())) {
            problems.add("prompt: it states the key point it should probe; ask about the situation instead of giving the answer");
        }
        if (QuestionChecks.nearDuplicate(draft.prompt(), target.parentQuestionPrompt())) {
            problems.add("prompt: it repeats the question that was just asked; ask a real follow-up");
        }
        problems.addAll(AgentSupport.keyPointProblems(draft.prompt(), draft.expectedKeyPoints()));
        return problems;
    }
}
