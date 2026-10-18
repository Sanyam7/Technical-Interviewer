package com.techinterviewer.ai.agents;

import com.techinterviewer.ai.agents.guard.EvidenceVerifier;
import com.techinterviewer.ai.agents.guard.InjectionHeuristics;
import com.techinterviewer.ai.agents.output.AssessmentDraft;
import com.techinterviewer.ai.prompt.PromptRegistry;
import com.techinterviewer.ai.prompt.PromptTemplate;
import com.techinterviewer.ai.prompt.PromptValue;
import com.techinterviewer.ai.provider.ContentBlockedException;
import com.techinterviewer.ai.provider.LlmException;
import com.techinterviewer.ai.routing.AiTask;
import com.techinterviewer.ai.structured.JsonSchemaRegistry;
import com.techinterviewer.ai.structured.StructuredCall;
import com.techinterviewer.ai.structured.StructuredOutputClient;
import com.techinterviewer.ai.structured.StructuredOutputException;
import com.techinterviewer.ai.structured.StructuredResult;
import com.techinterviewer.evaluation.Rubric;
import com.techinterviewer.evaluation.RubricRegistry;
import com.techinterviewer.evaluation.domain.CodeAnalysis;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.EvaluationFlag;
import com.techinterviewer.evaluation.domain.Evaluator;
import com.techinterviewer.evaluation.domain.EvaluatorConfidence;
import com.techinterviewer.evaluation.domain.FactualError;
import com.techinterviewer.evaluation.domain.KeyPointAssessment;
import com.techinterviewer.evaluation.domain.KeyPointStatus;
import com.techinterviewer.interview.domain.CodingExample;
import com.techinterviewer.interview.domain.ExpectedKeyPoint;
import com.techinterviewer.interview.domain.QuestionDetails;
import com.techinterviewer.interview.port.AiUnavailableException;
import com.techinterviewer.interview.port.AnswerAssessment;
import com.techinterviewer.interview.port.AnswerBlockedException;
import com.techinterviewer.interview.port.AnswerEvaluator;
import com.techinterviewer.interview.port.CodeTestEvidence;
import com.techinterviewer.interview.port.CriterionRating;
import com.techinterviewer.interview.port.DesignContext;
import com.techinterviewer.interview.port.EvaluationRequest;
import com.techinterviewer.interview.port.ThreadTurn;
import com.techinterviewer.rag.KnowledgeRetriever;
import com.techinterviewer.rag.context.KnowledgeContext;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * <b>Answer evaluator</b>: judges one answer against the rubric of its evaluator kind (introduction, technical, coding,
 * system design; one prompt each) and returns ratings and key point verdicts, never a score.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>The schema is narrowed to this request: {@code ratings} must have exactly the rubric's criteria and key
 *       point verdicts may only name the question's key point ids.
 *   <li>Validated: every rubric criterion rated once, every expected key point judged exactly once, and a quote for
 *       every key point judged covered or partial (a failure triggers the repair attempt).
 *   <li>Evidence verification: a quote that does not occur in the candidate's words (this answer or, for a design
 *       thread, an earlier one) downgrades its key point to missing and flags {@code EVAL_EVIDENCE_MISMATCH}.
 *   <li>{@code INJECTION_SUSPECTED} is set when the model or the deterministic heuristics see an injection attempt. What
 *       the heuristics detect is removed from the copy of the answer the model grades (by the prompt renderer, {@link InjectionHeuristics#redact});
 *       the server's rating rules bound what a flagged answer can score. Quotes are verified against the full answer.
 *   <li>A factual error may name the earlier mistake of the interview it repeats, by a token ({@code m1}, {@code m2},
 *       ...) the schema restricts to the mistakes given; the token is mapped back to that mistake's claim.
 *   <li>The model's confidence is passed on as its own estimate (not calibrated); the server may only lower it.
 * </ol>
 *
 * <p>Role-specific evaluation: the request carries the focus areas of the question's skill profile (for React, component
 * architecture, state management and rendering performance), which the prompt tells the model to judge depth,
 * practical understanding and reasoning by. The model never sees the weights; the server applies them.
 *
 * <p>Grounded (all kinds but the introduction): the model receives reference material, first the chunks the
 * question's key points cite, then chunks retrieved for the question and its key points, and uses it to decide whether
 * the candidate's statements are correct. It is fenced as data: it cannot change the rubric or the rules. A factual
 * error may cite the material that contradicts the candidate ({@code sources}), which becomes the error's chunk ids.
 * Material that does not cover a statement does not make it wrong.
 *
 * <p>Failures follow the port contract (AI_ARCHITECTURE.md 10.6): a safety refusal is {@link AnswerBlockedException}
 * (unassessable at once), a provider failure {@link AiUnavailableException} (retried), output that stays invalid after
 * the repair attempt an {@link IllegalArgumentException} (counted as invalid output, retried).
 */
public final class AnswerEvaluatorAgent implements AnswerEvaluator {

    static final String RATING_SCHEMA = "criterion-rating.v1";
    /** A design dimension is rated with the candidate's words it rests on. */
    static final String DESIGN_RATING_SCHEMA = "design-criterion-rating.v1";
    /** Prefix of the tokens that stand for the interview's earlier mistakes: m1, m2, ... */
    static final String MISTAKE_TOKEN = "m";

    private static final Logger log = LoggerFactory.getLogger(AnswerEvaluatorAgent.class);

    private final StructuredOutputClient client;
    private final PromptRegistry prompts;
    private final JsonSchemaRegistry schemas;
    private final KnowledgeRetriever knowledge;

    public AnswerEvaluatorAgent(StructuredOutputClient client, PromptRegistry prompts, JsonSchemaRegistry schemas, KnowledgeRetriever knowledge) {
        this.client = client;
        this.prompts = prompts;
        this.schemas = schemas;
        this.knowledge = knowledge;
    }

    @Override
    public AnswerAssessment evaluate(EvaluationRequest request) {
        Rubric rubric = RubricRegistry.forEvaluator(request.evaluator());
        PromptTemplate template = prompts.latest(promptId(request.evaluator()));
        KnowledgeContext context = request.evaluator() == Evaluator.INTRO
                ? KnowledgeContext.DISABLED : knowledge.ground(GroundingQueries.evaluation(request));
        Map<String, PromptValue> values = values(request);
        if (request.evaluator() != Evaluator.INTRO) {
            values.put("references", AgentSupport.references(context));
        }
        StructuredResult<AssessmentDraft> result;
        try {
            result = client.generate(new StructuredCall<>(
                    AiTask.ANSWER_EVALUATION, template, values, schema(template, rubric, request, context), AssessmentDraft.class,
                    draft -> problems(rubric, request, draft, context), request.interviewId(), context.recordedChunkIds()));
        } catch (ContentBlockedException blocked) {
            throw new AnswerBlockedException("the provider refused to evaluate answer " + request.answerId(), blocked);
        } catch (LlmException failure) {
            log.warn("Evaluation of answer {} failed: {}", request.answerId(), failure.getMessage());
            throw new AiUnavailableException(AiUnavailableException.DEFAULT_DETAIL, failure);
        } catch (StructuredOutputException invalid) {
            log.warn("Evaluation of answer {} produced invalid output: {}", request.answerId(), invalid.getMessage());
            throw new IllegalArgumentException(invalid.getMessage(), invalid);
        }
        return assessment(request, result, context);
    }

    static String promptId(Evaluator evaluator) {
        return switch (evaluator) {
            case INTRO -> "answer-evaluation-intro";
            case TECHNICAL -> "answer-evaluation-technical";
            case CODING -> "answer-evaluation-coding";
            case SYSTEM_DESIGN -> "answer-evaluation-system-design";
        };
    }

    private ObjectNode schema(PromptTemplate template, Rubric rubric, EvaluationRequest request, KnowledgeContext context) {
        ObjectNode schema = schemas.get(template.schema());
        ObjectNode errorItems = AgentSupport.itemsOf(schema, "factualErrors");
        if (errorItems.path("properties").has("sources")) {
            AgentSupport.restrictSources(errorItems, context);
        }
        if (errorItems.path("properties").has("criterion")) {
            AgentSupport.restrictTo(errorItems, "criterion", rubric.criteria().stream().map(Criterion::name).toList());
        }
        if (errorItems.path("properties").has("repeats")) {
            ObjectNode repeats = (ObjectNode) errorItems.path("properties").path("repeats");
            ObjectNode items = repeats.putObject("items");
            items.put("type", "string");
            List<String> tokens = mistakeTokens(request);
            if (tokens.isEmpty()) {
                repeats.put("maxItems", 0);
            } else {
                ArrayNode allowed = items.putArray("enum");
                tokens.forEach(allowed::add);
            }
        }
        ObjectNode ratings = (ObjectNode) schema.path("properties").path("ratings");
        ObjectNode properties = ratings.putObject("properties");
        ArrayNode required = ratings.putArray("required");
        String ratingSchema = request.evaluator() == Evaluator.SYSTEM_DESIGN ? DESIGN_RATING_SCHEMA : RATING_SCHEMA;
        for (Criterion criterion : rubric.criteria()) {
            properties.set(criterion.name(), schemas.get(ratingSchema));
            required.add(criterion.name());
        }
        ObjectNode keyPoints = (ObjectNode) schema.path("properties").path("keyPoints");
        List<String> ids = request.expectedKeyPoints().stream().map(ExpectedKeyPoint::id).toList();
        if (ids.isEmpty()) {
            keyPoints.put("maxItems", 0);
        } else {
            keyPoints.put("minItems", ids.size());
            keyPoints.put("maxItems", ids.size());
            AgentSupport.restrictTo((ObjectNode) keyPoints.path("items"), "keyPointId", ids);
        }
        return schema;
    }

    private static Map<String, PromptValue> values(EvaluationRequest request) {
        Map<String, PromptValue> values = new HashMap<>();
        values.put("question", PromptValue.trusted(request.questionPrompt()));
        // A detected instruction to the grader never reaches the model (the prompt renderer removes it from every untrusted
        // value); the flag is set from the full answer below.
        values.put("answer", PromptValue.untrusted("candidate_answer", request.answerText()));
        if (request.evaluator() == Evaluator.INTRO) {
            return values;
        }
        if (request.evaluator() == Evaluator.SYSTEM_DESIGN) {
            DesignContext design = request.design();
            values.put("probe", PromptValue.trusted(design == null || design.probe() == null
                    ? "none: the latest question is the problem itself" : design.probe().name()));
            values.put("architecture", PromptValue.untrusted("architecture_diagram", design == null || design.architecture() == null
                    ? "(the candidate drew no diagram)" : design.architecture()));
        } else {
            values.put("topic", PromptValue.trusted(request.topic() == null
                    ? "general" : request.topic().skillName() + " / " + request.topic().topicName()));
        }
        values.put("difficulty", PromptValue.trusted(Integer.toString(request.difficulty())));
        values.put("keyPoints", PromptValue.trusted(AgentSupport.keyPointLines(request.expectedKeyPoints())));
        values.put("thread", PromptValue.untrusted("thread", thread(request.thread())));
        if (request.evaluator() == Evaluator.CODING) {
            values.put("codingLanguage", PromptValue.trusted(request.codingLanguage()));
            values.put("details", PromptValue.trusted(details(request.details())));
            values.put("tests", PromptValue.trusted(tests(request.codeTests())));
            values.put("testOutput", PromptValue.untrusted("test_output", testOutput(request.codeTests())));
            values.put("referenceComplexity", PromptValue.trusted(referenceComplexity(request.codeTests())));
        } else {
            values.put("memory", PromptValue.untrusted("interview_memory", request.memorySummary()));
        }
        values.put("focus", PromptValue.trusted(AgentSupport.bullets(request.focusAreas(), EvaluationRequest.MAX_FOCUS_AREAS, 120)));
        values.put("mistakes", PromptValue.untrusted("earlier_mistakes", mistakes(request)));
        return values;
    }

    /** The tokens of the earlier mistakes, in the order of the request: m1 is the oldest. */
    static List<String> mistakeTokens(EvaluationRequest request) {
        List<String> tokens = new ArrayList<>();
        for (int i = 1; i <= request.earlierMistakes().size(); i++) {
            tokens.add(MISTAKE_TOKEN + i);
        }
        return tokens;
    }

    private static String mistakes(EvaluationRequest request) {
        if (request.earlierMistakes().isEmpty()) {
            return AgentSupport.NONE;
        }
        StringBuilder text = new StringBuilder();
        List<String> tokens = mistakeTokens(request);
        for (int i = 0; i < tokens.size(); i++) {
            text.append('[').append(tokens.get(i)).append("] ")
                    .append(AgentSupport.shorten(request.earlierMistakes().get(i).strip().replaceAll("\\s+", " "), 300)).append('\n');
        }
        return text.toString().stripTrailing();
    }

    private static String thread(List<ThreadTurn> turns) {
        if (turns.isEmpty()) {
            return AgentSupport.NONE;
        }
        StringBuilder text = new StringBuilder();
        int number = 1;
        for (ThreadTurn turn : turns) {
            text.append("Question ").append(number).append(": ").append(turn.questionPrompt()).append('\n')
                    .append("Answer ").append(number).append(": ")
                    .append(turn.skipped() ? "(skipped)" : turn.answerText()).append("\n\n");
            number++;
        }
        return text.toString().stripTrailing();
    }

    /**
     * The server's measurements as the prompt shows them: the counts, which the server computed, so trusted text. What the
     * candidate's program printed, threw or returned is not in it: see {@link #testOutput}.
     */
    static String tests(@Nullable CodeTestEvidence tests) {
        if (tests == null) {
            return "The tests were not run: this answer continues an earlier problem.";
        }
        return tests.summary();
    }

    /**
     * The failing tests and the compiler's messages. They quote the candidate's own program (a message it threw, a value it
     * returned, an identifier or a string literal in its source), so they are candidate-written text and go into the prompt
     * fenced as data, like the answer (SECURITY.md, AI-6).
     */
    static String testOutput(@Nullable CodeTestEvidence tests) {
        if (tests == null || (tests.compileOutput() == null && tests.failures().isEmpty())) {
            return AgentSupport.NONE;
        }
        StringBuilder text = new StringBuilder();
        if (tests.compileOutput() != null) {
            text.append("Compiler output:\n").append(AgentSupport.shorten(tests.compileOutput(), 2000));
        }
        tests.failures().forEach(line -> text.append(text.isEmpty() ? "" : "\n").append("- ").append(line));
        return text.toString();
    }

    private static String referenceComplexity(@Nullable CodeTestEvidence tests) {
        if (tests == null || tests.referenceTime() == null) {
            return "not given: judge the complexity against the constraints";
        }
        return "time " + tests.referenceTime() + ", space " + tests.referenceSpace();
    }

    private static String details(QuestionDetails details) {
        if (details == null) {
            return AgentSupport.NONE;
        }
        StringBuilder text = new StringBuilder("Constraints:\n").append(AgentSupport.bullets(details.constraints(), 20, 500));
        int number = 1;
        for (CodingExample example : details.examples()) {
            text.append("\nExample ").append(number++).append(": input ").append(example.input())
                    .append(" -> output ").append(example.output());
            if (example.explanation() != null) {
                text.append(" (").append(example.explanation()).append(')');
            }
        }
        if (details.starterSignature() != null) {
            text.append("\nStarter signature: ").append(details.starterSignature());
        }
        return text.toString();
    }

    static List<String> problems(Rubric rubric, EvaluationRequest request, AssessmentDraft draft) {
        return problems(rubric, request, draft, KnowledgeContext.DISABLED);
    }

    static List<String> problems(Rubric rubric, EvaluationRequest request, AssessmentDraft draft, KnowledgeContext context) {
        List<String> problems = new ArrayList<>();
        List<String> mistakeTokens = mistakeTokens(request);
        for (AssessmentDraft.FactualErrorDraft error : draft.factualErrors()) {
            problems.addAll(AgentSupport.sourceProblems("factualErrors.sources", error.sourceTokens(), context));
            if (rubric.evaluator() == Evaluator.SYSTEM_DESIGN && (error.criterion() == null || !rubric.criteria().contains(error.criterion()))) {
                problems.add("factualErrors.criterion: name the design dimension of the mistake, one of " + rubric.criteria());
            }
            for (String token : error.repeatTokens()) {
                if (!mistakeTokens.contains(token)) {
                    problems.add("factualErrors.repeats: " + token + " is not one of the earlier mistakes "
                            + (mistakeTokens.isEmpty() ? "(there are none: leave it empty)" : mistakeTokens.toString()));
                }
            }
        }
        Set<Criterion> expected = rubric.criteria();
        if (!draft.ratings().keySet().equals(expected)) {
            Set<Criterion> missing = EnumSet.copyOf(expected);
            missing.removeAll(draft.ratings().keySet());
            Set<Criterion> extra = draft.ratings().isEmpty() ? EnumSet.noneOf(Criterion.class) : EnumSet.copyOf(draft.ratings().keySet());
            extra.removeAll(expected);
            problems.add("ratings: rate exactly " + expected + "; missing " + missing + ", not in the rubric " + extra);
        }
        Set<String> wanted = new LinkedHashSet<>();
        request.expectedKeyPoints().forEach(point -> wanted.add(point.id()));
        Set<String> seen = new HashSet<>();
        for (AssessmentDraft.KeyPointVerdict verdict : draft.keyPoints()) {
            if (!wanted.contains(verdict.keyPointId())) {
                problems.add("keyPoints: there is no key point " + verdict.keyPointId());
            } else if (!seen.add(verdict.keyPointId())) {
                problems.add("keyPoints: key point " + verdict.keyPointId() + " is judged twice");
            }
            if (verdict.status() != KeyPointStatus.MISSING && (verdict.evidenceQuote() == null || verdict.evidenceQuote().isBlank())) {
                problems.add("keyPoints: " + verdict.keyPointId() + " is " + verdict.status() + " but has no evidenceQuote from the answer");
            }
        }
        Set<String> unjudged = new LinkedHashSet<>(wanted);
        unjudged.removeAll(seen);
        if (!unjudged.isEmpty()) {
            problems.add("keyPoints: every key point must be judged; missing " + unjudged);
        }
        return problems;
    }

    private static AnswerAssessment assessment(EvaluationRequest request, StructuredResult<AssessmentDraft> result, KnowledgeContext context) {
        AssessmentDraft draft = result.value();
        Set<EvaluationFlag> flags = EnumSet.noneOf(EvaluationFlag.class);
        for (AssessmentDraft.ModelFlag flag : draft.flags()) {
            if (flag != AssessmentDraft.ModelFlag.LANGUAGE_MISMATCH || request.evaluator() == Evaluator.CODING) {
                flags.add(EvaluationFlag.valueOf(flag.name()));
            }
        }
        // The diagram is the candidate's text too: an instruction hidden in a component's notes is an injection attempt.
        String architecture = request.design() == null ? null : request.design().architecture();
        if (InjectionHeuristics.suspected(request.answerText()) || (architecture != null && InjectionHeuristics.suspected(architecture))) {
            flags.add(EvaluationFlag.INJECTION_SUSPECTED);
        }
        String candidateWords = candidateWords(request);
        List<KeyPointAssessment> keyPoints = new ArrayList<>();
        for (AssessmentDraft.KeyPointVerdict verdict : draft.keyPoints()) {
            if (verdict.status() == KeyPointStatus.MISSING) {
                keyPoints.add(new KeyPointAssessment(verdict.keyPointId(), KeyPointStatus.MISSING, null, false));
                continue;
            }
            String quote = verdict.evidenceQuote().strip();
            if (EvidenceVerifier.occursIn(candidateWords, quote)) {
                keyPoints.add(new KeyPointAssessment(verdict.keyPointId(), verdict.status(), quote, true));
            } else {
                // The model cited words the candidate never wrote: the key point is not shown, and the evaluation says so.
                log.debug("Answer {}: the quote for key point {} ({}) does not occur in the answer: {}",
                        request.answerId(), verdict.keyPointId(), verdict.status(), quote);
                keyPoints.add(new KeyPointAssessment(verdict.keyPointId(), KeyPointStatus.MISSING, null, false));
                flags.add(EvaluationFlag.EVAL_EVIDENCE_MISMATCH);
            }
        }
        Map<Criterion, CriterionRating> ratings = new EnumMap<>(Criterion.class);
        for (Map.Entry<Criterion, AssessmentDraft.RatingDraft> entry : draft.ratings().entrySet()) {
            List<String> verified = new ArrayList<>();
            for (String quote : entry.getValue().quotes()) {
                String stripped = quote.strip();
                if (EvidenceVerifier.occursIn(candidateWords, stripped)) {
                    if (!verified.contains(stripped)) {
                        verified.add(stripped);
                    }
                } else {
                    // Words the candidate never wrote back no rating: the quote is dropped and the evaluation says so.
                    log.debug("Answer {}: the quote for {} does not occur in the candidate's words: {}", request.answerId(), entry.getKey(), quote);
                    flags.add(EvaluationFlag.EVAL_EVIDENCE_MISMATCH);
                }
            }
            ratings.put(entry.getKey(), new CriterionRating(entry.getValue().rating(), entry.getValue().rationale().strip(), verified));
        }
        return new AnswerAssessment(
                ratings,
                keyPoints,
                errors(request, draft, context, candidateWords, flags),
                draft.candidateClaims().stream().map(String::strip).distinct().toList(),
                draft.summary().strip(),
                flags,
                result.promptId(),
                result.promptVersion(),
                result.model(),
                result.llmCallId(),
                draft.confidence() == null ? null
                        : new EvaluatorConfidence(draft.confidence().level(), draft.confidence().reason().strip()),
                request.evaluator() != Evaluator.CODING || draft.codeAnalysis() == null ? null
                        : new CodeAnalysis(draft.codeAnalysis().approach().strip(), draft.codeAnalysis().timeComplexity().strip(),
                                draft.codeAnalysis().spaceComplexity().strip()));
    }

    /**
     * The factual errors of the assessment. A design misconception must be the candidate's own words: its claim is checked against
     * what the candidate wrote, like a quote, and one that is not there (a weakness restated as a statement the candidate never
     * made) is dropped and flags the evaluation.
     */
    private static List<FactualError> errors(EvaluationRequest request, AssessmentDraft draft, KnowledgeContext context, String candidateWords,
            Set<EvaluationFlag> flags) {
        boolean design = request.evaluator() == Evaluator.SYSTEM_DESIGN;
        List<FactualError> errors = new ArrayList<>();
        for (AssessmentDraft.FactualErrorDraft error : draft.factualErrors()) {
            String claim = error.claim().strip();
            if (design && !EvidenceVerifier.occursIn(candidateWords, claim)) {
                log.debug("Answer {}: the design mistake is not in the candidate's words: {}", request.answerId(), claim);
                flags.add(EvaluationFlag.EVAL_EVIDENCE_MISMATCH);
                continue;
            }
            errors.add(new FactualError(claim, error.correction().strip(), AgentSupport.chunkIds(error.sourceTokens(), context),
                    repeated(request, error), design ? error.criterion() : null));
        }
        return errors;
    }

    /** The claim of the earlier mistake an error repeats, from its validated token; null when it repeats none. */
    private static @Nullable String repeated(EvaluationRequest request, AssessmentDraft.FactualErrorDraft error) {
        if (error.repeatTokens().isEmpty()) {
            return null;
        }
        int index = Integer.parseInt(error.repeatTokens().getFirst().substring(MISTAKE_TOKEN.length())) - 1;
        return request.earlierMistakes().get(index);
    }

    /**
     * What the candidate wrote in this thread: the answer, and for a cumulative design evaluation the earlier answers and the
     * architecture diagram.
     */
    private static String candidateWords(EvaluationRequest request) {
        StringBuilder words = new StringBuilder(request.answerText());
        request.thread().stream().filter(turn -> !turn.skipped()).forEach(turn -> words.append('\n').append(turn.answerText()));
        if (request.design() != null && request.design().architecture() != null) {
            words.append('\n').append(request.design().architecture());
        }
        return words.toString();
    }
}
