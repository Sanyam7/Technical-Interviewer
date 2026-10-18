package com.techinterviewer.ai.agents;

import com.techinterviewer.ai.agents.guard.QuestionChecks;
import com.techinterviewer.ai.agents.output.CodingDraft;
import com.techinterviewer.ai.agents.output.QuestionDraft;
import com.techinterviewer.ai.prompt.PromptRegistry;
import com.techinterviewer.ai.prompt.PromptTemplate;
import com.techinterviewer.ai.prompt.PromptValue;
import com.techinterviewer.ai.routing.AiTask;
import com.techinterviewer.ai.structured.JsonSchemaRegistry;
import com.techinterviewer.ai.structured.StructuredCall;
import com.techinterviewer.ai.structured.StructuredOutputClient;
import com.techinterviewer.ai.structured.StructuredResult;
import com.techinterviewer.interview.domain.CodingExample;
import com.techinterviewer.interview.domain.QuestionDetails;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.QuestionOrigin;
import com.techinterviewer.interview.domain.QuestionType;
import com.techinterviewer.interview.port.AskedQuestion;
import com.techinterviewer.interview.port.CuratedTemplate;
import com.techinterviewer.interview.port.GeneratedQuestion;
import com.techinterviewer.interview.port.QuestionRequest;
import com.techinterviewer.rag.KnowledgeRetriever;
import com.techinterviewer.interview.port.QuestionEmbedding;
import com.techinterviewer.rag.context.KnowledgeContext;
import com.techinterviewer.rag.dedup.QuestionDeduplicator;
import com.techinterviewer.interview.port.ResumeAnchor;
import com.techinterviewer.resume.ResumeEvidence;
import com.techinterviewer.resume.ResumeVocabulary;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.node.ObjectNode;

/**
 * <b>Question generator</b>: writes a root question (technical, coding or system design) for the topic and difficulty
 * the engine chose. One prompt per kind, one schema for technical and design questions and one for coding problems.
 *
 * <p>Validated before it is accepted (a failure triggers the repair attempt): the echoed topic and difficulty equal
 * the requested ones (both also constrained in the schema), the prompt does not contain the text of one of its key
 * points, it is not a near-duplicate of a question already asked, key point ids are unique, the estimated time suits
 * the kind, and a coding problem has its examples. The topic and difficulty of the result are the requested ones.
 *
 * <p>Grounded in the knowledge base: the agent retrieves reference material for the topic (or, for coding and design
 * problems, for the kind of problem) and shows it to the model as fenced data. Each key point may cite the material it
 * rests on ({@code sources}: r1, r2, ...; constrained to the given tokens in the schema), and the citations become the
 * key point's chunk ids, which the evaluator later receives again. The chunks shown become the question's grounding
 * ({@code question_sources}). Without material the question is written from the model's own knowledge, as before.
 *
 * <p>Semantic de-duplication: a draft that passes those checks is embedded and compared with the earlier questions of the
 * interview ({@link QuestionDeduplicator}); one that asks essentially the same as an earlier question is sent back for
 * repair with that reason, like every other failed check. The accepted question carries its vector, which the interview
 * stores so later questions are compared with it. Without an embedding model only the lexical check runs.
 *
 * <p>Résumé-anchored questions (AI_ARCHITECTURE.md section 2.13): when the engine anchored a technical question on an experience
 * of the candidate's résumé, the prompt {@code resume-question} is used and the experience is shown as untrusted data. The
 * question must not put words in the candidate's mouth, which is checked before it is accepted: the words it refers to
 * ({@code resumeQuote}) must be in the experience, it may name no technology of the résumé vocabulary that neither the
 * experience nor the topic names, and no figure (two or more digits) the experience does not contain.
 *
 * <p>Typed questions (AI_ARCHITECTURE.md section 2.15): when the engine decided a question type, the prompt follows it:
 * {@code fundamentals-question} for CONCEPT and INTERNALS, {@code scenario-question} for SCENARIO and DEBUGGING, both with the
 * curated angle of the question bank (trusted, server-written) and without anything the candidate said, so a fundamentals or
 * practical question is about the technology; {@code resume-question} for an EXPERIENCE question anchored on the résumé, and
 * {@code experience-question} for one about what the candidate said in the interview, checked like a résumé question against those
 * statements. A request without a type (a plan stored before stages) uses {@code technical-question} as before.
 *
 * <p>Not yet: the curated exemplar fallback.
 */
public final class QuestionGeneratorAgent {

    static final int MAX_ASKED_IN_PROMPT = 30;
    static final int MAX_ASKED_LENGTH = 300;
    static final int MAX_CLAIMS_IN_PROMPT = 10;
    static final String RESUME_PROMPT_ID = "resume-question";
    static final String FUNDAMENTALS_PROMPT_ID = "fundamentals-question";
    static final String SCENARIO_PROMPT_ID = "scenario-question";
    static final String EXPERIENCE_PROMPT_ID = "experience-question";
    static final String NO_ANGLE = "(none: choose an important aspect of the topic that interviewers ask about)";
    static final int MIN_RESUME_QUOTE_WORDS = 3;
    private static final java.util.regex.Pattern FIGURE = java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}])\\d[\\d,.]*\\d(?![\\p{L}\\p{N}])");

    private final StructuredOutputClient client;
    private final PromptRegistry prompts;
    private final JsonSchemaRegistry schemas;
    private final KnowledgeRetriever knowledge;
    private final QuestionDeduplicator deduplicator;
    private final ResumeVocabulary vocabulary;

    public QuestionGeneratorAgent(StructuredOutputClient client, PromptRegistry prompts, JsonSchemaRegistry schemas, KnowledgeRetriever knowledge) {
        this(client, prompts, schemas, knowledge, QuestionDeduplicator.NONE);
    }

    public QuestionGeneratorAgent(StructuredOutputClient client, PromptRegistry prompts, JsonSchemaRegistry schemas, KnowledgeRetriever knowledge,
            QuestionDeduplicator deduplicator) {
        this(client, prompts, schemas, knowledge, deduplicator, ResumeVocabulary.load());
    }

    public QuestionGeneratorAgent(StructuredOutputClient client, PromptRegistry prompts, JsonSchemaRegistry schemas, KnowledgeRetriever knowledge,
            QuestionDeduplicator deduplicator, ResumeVocabulary vocabulary) {
        this.client = client;
        this.prompts = prompts;
        this.schemas = schemas;
        this.knowledge = knowledge;
        this.deduplicator = deduplicator;
        this.vocabulary = vocabulary;
    }

    public GeneratedQuestion generate(QuestionRequest request) {
        PromptTemplate template = prompts.latest(promptIdFor(request));
        String topicKey = request.topic() == null ? "none" : request.topic().key();
        ObjectNode schema = schemas.get(template.schema());
        AgentSupport.restrictTo(schema, "topic", List.of(topicKey));
        AgentSupport.fix(schema, "difficulty", request.targetDifficulty());
        KnowledgeContext context = knowledge.ground(GroundingQueries.question(request));
        AgentSupport.restrictSources(AgentSupport.itemsOf(schema, "expectedKeyPoints"), context);
        Map<String, PromptValue> values = values(request, topicKey);
        values.put("references", AgentSupport.references(context));
        AtomicReference<QuestionEmbedding> accepted = new AtomicReference<>();
        StructuredResult<QuestionDraft> result = client.generate(new StructuredCall<>(
                AiTask.QUESTION_GENERATION, template, values, schema, QuestionDraft.class,
                draft -> {
                    List<String> problems = problems(request, topicKey, draft, context);
                    problems.addAll(resumeProblems(request, draft, vocabulary));
                    problems.addAll(statementProblems(request, draft, vocabulary));
                    if (problems.isEmpty()) {
                        semanticRepeat(request, draft, accepted).ifPresent(problems::add);
                    }
                    return problems;
                }, request.interviewId(), context.recordedChunkIds()));
        QuestionDraft draft = result.value();
        return new GeneratedQuestion(
                draft.prompt().strip(),
                AgentSupport.keyPoints(draft.expectedKeyPoints(), context),
                details(request, draft.coding()),
                QuestionOrigin.GENERATED,
                request.targetDifficulty(),
                request.topic() == null ? null : request.topic().key(),
                draft.estimatedMinutes(),
                context.chunkIds(),
                result.promptId(),
                result.promptVersion(),
                result.model(),
                result.llmCallId(),
                accepted.get());
    }

    /**
     * The repair instruction when the draft asks essentially the same as an earlier question of the interview; the draft's
     * vector is kept in {@code accepted} for storage. The earlier question is not quoted: its text is model output.
     */
    private Optional<String> semanticRepeat(QuestionRequest request, QuestionDraft draft, AtomicReference<QuestionEmbedding> accepted) {
        QuestionEmbedding embedding = deduplicator.embed(request.interviewId(), draft.prompt().strip());
        accepted.set(embedding);
        if (embedding == null) {
            return Optional.empty();
        }
        return deduplicator.repeatOf(request.interviewId(), embedding).map(repeat -> String.format(Locale.ROOT,
                "prompt: it asks essentially the same as a question already asked in this interview (similarity %.2f); ask about"
                        + " a different aspect of the topic", repeat.similarity()));
    }

    /** The prompt of a request: by its question type when it has one, else by its kind (see the class comment). */
    static String promptIdFor(QuestionRequest request) {
        if (request.resumeAnchor() != null) {
            return RESUME_PROMPT_ID;
        }
        QuestionType type = request.questionType();
        if (type == null) {
            return promptId(request.kind());
        }
        return switch (type) {
            case CONCEPT, INTERNALS -> FUNDAMENTALS_PROMPT_ID;
            case SCENARIO, DEBUGGING -> SCENARIO_PROMPT_ID;
            case EXPERIENCE -> EXPERIENCE_PROMPT_ID;
        };
    }

    static String promptId(QuestionKind kind) {
        return switch (kind) {
            case TECHNICAL -> "technical-question";
            case CODING -> "coding-question";
            case SYSTEM_DESIGN -> "system-design-question";
            case INTRO, FOLLOW_UP -> throw new IllegalArgumentException(kind + " questions are not written by the question generator agent");
        };
    }

    private static Map<String, PromptValue> values(QuestionRequest request, String topicKey) {
        Map<String, PromptValue> values = new HashMap<>();
        values.put("role", PromptValue.trusted(request.role().name()));
        values.put("experience", PromptValue.trusted(request.experience().name()));
        values.put("topicKey", PromptValue.trusted(topicKey));
        values.put("topicName", PromptValue.trusted(request.topic() == null ? "not fixed" : request.topic().topicName()));
        values.put("difficulty", PromptValue.trusted(Integer.toString(request.targetDifficulty())));
        values.put("askedQuestions", PromptValue.untrusted("asked_questions", AgentSupport.bullets(
                request.askedQuestions().stream().map(AskedQuestion::prompt).toList(), MAX_ASKED_IN_PROMPT, MAX_ASKED_LENGTH)));
        switch (request.kind()) {
            case TECHNICAL -> {
                values.put("skillName", PromptValue.trusted(request.topic().skillName()));
                ResumeAnchor anchor = request.resumeAnchor();
                QuestionType type = request.questionType();
                if (anchor != null) {
                    values.put("experienceKind", PromptValue.trusted(anchor.kind().toLowerCase(Locale.ROOT)));
                    values.put("resumeExperience", PromptValue.untrusted("resume_experience", experience(anchor)));
                } else if (type == null) {
                    values.put("candidateClaims", claims(request));
                } else if (type == QuestionType.EXPERIENCE) {
                    values.put("candidateStatements", PromptValue.untrusted("candidate_statements",
                            AgentSupport.bullets(request.candidateClaims(), MAX_CLAIMS_IN_PROMPT, 300)));
                } else {
                    CuratedTemplate angle = request.template();
                    values.put("questionType", PromptValue.trusted(type.name()));
                    values.put("angle", PromptValue.trusted(angle == null ? NO_ANGLE : angle.subtopic() + ": " + angle.question()));
                    values.put("angleKeyPoints", PromptValue.trusted(angle == null ? AgentSupport.NONE
                            : AgentSupport.bullets(angle.keyPoints(), BankLimits.KEY_POINTS, BankLimits.TEXT)));
                    values.put("misconceptions", PromptValue.trusted(angle == null ? AgentSupport.NONE
                            : AgentSupport.bullets(angle.misconceptions(), BankLimits.MISCONCEPTIONS, BankLimits.TEXT)));
                }
            }
            case SYSTEM_DESIGN -> {
                values.put("focusSkills", PromptValue.trusted(focusSkills(request)));
                values.put("candidateClaims", claims(request));
            }
            case CODING -> {
                values.put("focusSkills", PromptValue.trusted(focusSkills(request)));
                values.put("codingLanguage", PromptValue.trusted(request.codingLanguage()));
            }
            default -> throw new IllegalArgumentException("unexpected kind " + request.kind());
        }
        return values;
    }

    /** The experience as the model sees it: its statement, where it happened and what it names. */
    static String experience(ResumeAnchor anchor) {
        StringBuilder text = new StringBuilder();
        if (anchor.context() != null) {
            text.append("Where: ").append(anchor.context()).append('\n');
        }
        text.append("Statement: ").append(anchor.text());
        if (!anchor.technologies().isEmpty()) {
            text.append("\nTechnologies it names: ").append(String.join(", ", anchor.technologies()));
        }
        return text.toString();
    }

    /**
     * The problems of a question anchored on a résumé experience: what it refers to must be in the experience, and it must not
     * attribute a technology or a figure the experience does not state. Empty for any other question.
     */
    static List<String> resumeProblems(QuestionRequest request, QuestionDraft draft, ResumeVocabulary vocabulary) {
        ResumeAnchor anchor = request.resumeAnchor();
        if (anchor == null) {
            return List.of();
        }
        List<String> problems = new ArrayList<>();
        String quote = draft.resumeQuote();
        if (quote == null || ResumeEvidence.words(quote).size() < MIN_RESUME_QUOTE_WORDS || !ResumeEvidence.within(anchor.text(), quote)) {
            problems.add("resumeQuote: copy at least three words of the résumé statement exactly as written; \"" + quote
                    + "\" is not part of it");
        }
        String stated = anchor.text() + " " + (anchor.context() == null ? "" : anchor.context());
        Set<String> allowed = new HashSet<>(anchor.technologies());
        vocabulary.technologiesIn(stated, true).forEach(technology -> allowed.add(technology.name()));
        vocabulary.technologiesIn(request.topic().topicName() + " " + request.topic().skillName(), true)
                .forEach(technology -> allowed.add(technology.name()));
        for (ResumeVocabulary.Technology named : vocabulary.technologiesIn(draft.prompt(), false)) {
            if (!allowed.contains(named.name())) {
                problems.add("prompt: it names " + named.name() + ", which the résumé statement does not mention; refer only to what the "
                        + "candidate states and do not presume the tools they used");
            }
        }
        Set<String> statedFigures = figures(stated);
        for (String figure : figures(draft.prompt())) {
            if (!statedFigures.contains(figure)) {
                problems.add("prompt: the figure " + figure + " is not in the résumé statement; do not add numbers the candidate did not state");
            }
        }
        return problems;
    }

    /**
     * The problems of an EXPERIENCE question about what the candidate said in the interview (no résumé anchor): like a résumé
     * question, the words it refers to must be one of those statements, and it must not attribute a technology or a figure none of
     * them states. Empty for every other question.
     */
    static List<String> statementProblems(QuestionRequest request, QuestionDraft draft, ResumeVocabulary vocabulary) {
        if (request.resumeAnchor() != null || request.questionType() != QuestionType.EXPERIENCE) {
            return List.of();
        }
        List<String> statements = request.candidateClaims();
        List<String> problems = new ArrayList<>();
        String quote = draft.resumeQuote();
        boolean quoted = quote != null && ResumeEvidence.words(quote).size() >= MIN_RESUME_QUOTE_WORDS
                && statements.stream().anyMatch(statement -> ResumeEvidence.within(statement, quote));
        if (!quoted) {
            problems.add("resumeQuote: copy at least three words of one of the candidate's statements exactly as written; \"" + quote
                    + "\" is not part of them");
        }
        String stated = String.join(" ", statements);
        Set<String> allowed = new HashSet<>();
        vocabulary.technologiesIn(stated, true).forEach(technology -> allowed.add(technology.name()));
        vocabulary.technologiesIn(request.topic().topicName() + " " + request.topic().skillName(), true)
                .forEach(technology -> allowed.add(technology.name()));
        for (ResumeVocabulary.Technology named : vocabulary.technologiesIn(draft.prompt(), false)) {
            if (!allowed.contains(named.name())) {
                problems.add("prompt: it names " + named.name() + ", which the candidate did not mention; refer only to what the "
                        + "candidate said and do not presume the tools they used");
            }
        }
        Set<String> statedFigures = figures(stated);
        for (String figure : figures(draft.prompt())) {
            if (!statedFigures.contains(figure)) {
                problems.add("prompt: the figure " + figure + " is not in the candidate's statements; do not add numbers the candidate did not state");
            }
        }
        return problems;
    }

    /** How much of a curated template the prompt shows. */
    private static final class BankLimits {
        static final int KEY_POINTS = 6;
        static final int MISCONCEPTIONS = 6;
        static final int TEXT = 300;
    }

    /** Numbers of two or more digits that stand alone (not "p99" or "v12"), without a trailing separator. */
    private static Set<String> figures(String text) {
        Set<String> figures = new java.util.LinkedHashSet<>();
        Matcher matcher = FIGURE.matcher(text);
        while (matcher.find()) {
            figures.add(matcher.group());
        }
        return figures;
    }

    private static PromptValue claims(QuestionRequest request) {
        return PromptValue.untrusted("candidate_claims", AgentSupport.bullets(request.candidateClaims(), MAX_CLAIMS_IN_PROMPT, 300));
    }

    private static String focusSkills(QuestionRequest request) {
        return request.focusSkills().isEmpty() ? AgentSupport.NONE : String.join(", ", request.focusSkills());
    }

    static List<String> problems(QuestionRequest request, String topicKey, QuestionDraft draft) {
        return problems(request, topicKey, draft, KnowledgeContext.DISABLED);
    }

    static List<String> problems(QuestionRequest request, String topicKey, QuestionDraft draft, KnowledgeContext context) {
        List<String> problems = new ArrayList<>();
        for (var point : draft.expectedKeyPoints()) {
            problems.addAll(AgentSupport.sourceProblems("expectedKeyPoints." + point.id() + ".sources", point.sourceTokens(), context));
        }
        if (!topicKey.equals(draft.topic())) {
            problems.add("topic: must be exactly \"" + topicKey + "\" but was \"" + draft.topic() + "\"");
        }
        if (draft.difficulty() != request.targetDifficulty()) {
            problems.add("difficulty: must be " + request.targetDifficulty() + " but was " + draft.difficulty());
        }
        problems.addAll(AgentSupport.keyPointProblems(draft.prompt(), draft.expectedKeyPoints()));
        for (AskedQuestion asked : request.askedQuestions()) {
            if (QuestionChecks.nearDuplicate(draft.prompt(), asked.prompt())) {
                problems.add("prompt: it repeats a question already asked in this interview; ask something different");
                break;
            }
        }
        int minutes = draft.estimatedMinutes();
        boolean coding = request.kind() == QuestionKind.CODING;
        if (request.kind() == QuestionKind.TECHNICAL && minutes > 10) {
            problems.add("estimatedMinutes: a technical question takes at most 10 minutes but was " + minutes);
        }
        if (request.kind() != QuestionKind.TECHNICAL && (minutes < 5 || minutes > 45)) {
            problems.add("estimatedMinutes: a coding or design problem takes 5 to 45 minutes but was " + minutes);
        }
        if (coding && draft.coding() == null) {
            problems.add("coding: the constraints and examples of the problem are required");
        }
        if (!coding && draft.coding() != null) {
            problems.add("coding: only coding problems have this part");
        }
        return problems;
    }

    private static QuestionDetails details(QuestionRequest request, CodingDraft coding) {
        if (coding == null) {
            return null;
        }
        return new QuestionDetails(
                coding.constraints().stream().map(String::strip).toList(),
                coding.examples().stream()
                        .map(example -> new CodingExample(example.input(), example.output(),
                                example.explanation() == null || example.explanation().isBlank() ? null : example.explanation()))
                        .toList(),
                coding.starterSignature() == null || coding.starterSignature().isBlank() ? null : coding.starterSignature(),
                request.codingLanguage());
    }
}
