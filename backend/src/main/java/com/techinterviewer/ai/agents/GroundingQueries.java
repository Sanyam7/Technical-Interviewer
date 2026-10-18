package com.techinterviewer.ai.agents;

import com.techinterviewer.evaluation.domain.Evaluator;
import com.techinterviewer.interview.domain.ExpectedKeyPoint;
import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.FollowUpMode;
import com.techinterviewer.interview.domain.QuestionType;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.port.CuratedTemplate;
import com.techinterviewer.interview.port.EvaluationDigest;
import com.techinterviewer.interview.port.EvaluationRequest;
import com.techinterviewer.interview.port.FollowUpTarget;
import com.techinterviewer.interview.port.NarrativeRequest;
import com.techinterviewer.interview.port.QuestionRequest;
import com.techinterviewer.interview.port.TopicRef;
import com.techinterviewer.rag.DocumentType;
import com.techinterviewer.rag.KnowledgeRetriever.Grounding;
import com.techinterviewer.rag.Technology;
import com.techinterviewer.rag.retrieval.RetrievalQuery;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The searches each AI task runs against the knowledge base, built by code from what the interview already decided
 * (topic, kind, difficulty, the question and its key points). No model writes a query, so a candidate cannot steer
 * retrieval, and the same task always searches the same way.
 *
 * <p>The <b>technology scope</b> says which technologies may ground a question. A catalog skill maps to its own
 * technology and the neighbouring ones whose material helps (a React question may draw on JavaScript); a coding problem
 * draws on data structures, algorithms and its language; a design problem on the design technologies. Material outside
 * the scope never reaches the prompt, however similar it looks.
 *
 * <p>A technical question's search follows its type (AI_ARCHITECTURE.md section 2.15): a fundamentals question looks for the
 * core concepts and internal mechanics of the topic, a practical one for use, failure modes and diagnosis; with a curated angle
 * of the question bank, its subtopic and model question sharpen the search, and the identifiers in it ({@code HashMap},
 * {@code @Transactional}) go to the keyword half. Nothing of the candidate's résumé or answers is ever part of a question's search
 * (the résumé is not in the knowledge base at all), so it cannot crowd out the technical material.
 *
 * <p>Budgets per task (estimated tokens / chunks): question 1500 / 5, follow-up 800 / 3, evaluation 1500 / 5, report
 * 1500 / 6.
 */
final class GroundingQueries {

    static final int QUESTION_BUDGET = 1500;
    static final int QUESTION_CHUNKS = 5;
    static final int FOLLOW_UP_BUDGET = 800;
    static final int FOLLOW_UP_CHUNKS = 3;
    static final int EVALUATION_BUDGET = 1500;
    static final int EVALUATION_CHUNKS = 5;
    static final int REPORT_BUDGET = 1500;
    static final int REPORT_CHUNKS = 6;
    static final int REPORT_GAP_QUERIES = 4;

    static final Set<Technology> DESIGN_SCOPE = Set.copyOf(EnumSet.of(
            Technology.SYSTEM_DESIGN, Technology.MICROSERVICES, Technology.DATABASES, Technology.CLOUD, Technology.REST, Technology.DOCKER));

    private static final Map<String, Set<Technology>> SKILL_SCOPE = Map.of(
            "java", Set.of(Technology.JAVA),
            "spring-boot", Set.of(Technology.SPRING_BOOT, Technology.JAVA, Technology.REST),
            "postgresql", Set.of(Technology.SQL, Technology.DATABASES),
            "system-design", DESIGN_SCOPE,
            "react", Set.of(Technology.REACT, Technology.JAVASCRIPT),
            "typescript", Set.of(Technology.TYPESCRIPT, Technology.JAVASCRIPT, Technology.NODEJS),
            "javascript", Set.of(Technology.JAVASCRIPT, Technology.NODEJS),
            "rest-api", Set.of(Technology.REST, Technology.SYSTEM_DESIGN),
            "microservices", Set.of(Technology.MICROSERVICES, Technology.SYSTEM_DESIGN, Technology.DATABASES));

    private static final Map<String, Technology> LANGUAGES = Map.of(
            "java", Technology.JAVA, "typescript", Technology.TYPESCRIPT, "javascript", Technology.JAVASCRIPT);

    /** Material for judging and probing answers; question exemplars carry model answers and are kept out. */
    private static final Set<DocumentType> REFERENCE_MATERIAL = Set.of(DocumentType.REFERENCE, DocumentType.DESIGN_REFERENCE);

    /** Identifiers worth matching exactly: {@code @Transactional}, {@code ConcurrentHashMap}, {@code useEffect}, {@code SERIALIZABLE}. */
    private static final Pattern IDENTIFIER = Pattern.compile("@?[A-Za-z]+[a-z][A-Z][A-Za-z0-9]*|@[A-Za-z]+|\\b[A-Z][A-Z_]{3,}\\b");

    private GroundingQueries() {
    }

    /** The technologies whose material may ground a question of this skill; empty (any) for an unknown skill. */
    static Set<Technology> scopeOfSkill(String skillSlug) {
        return SKILL_SCOPE.getOrDefault(skillSlug, Set.of());
    }

    static Set<Technology> codingScope(@Nullable String language) {
        Set<Technology> scope = EnumSet.of(Technology.DATA_STRUCTURES, Technology.ALGORITHMS);
        if (language != null && LANGUAGES.containsKey(language.toLowerCase(Locale.ROOT))) {
            scope.add(LANGUAGES.get(language.toLowerCase(Locale.ROOT)));
        }
        return Set.copyOf(scope);
    }

    // ---- per task ------------------------------------------------------------------------------------------------

    static Grounding question(QuestionRequest request) {
        RetrievalQuery query = switch (request.kind()) {
            case TECHNICAL -> {
                TopicRef topic = request.topic();
                CuratedTemplate angle = request.template();
                String focus = angle == null ? "" : " " + angle.subtopic() + ": " + angle.question();
                yield new RetrievalQuery(
                        topic.skillName() + ": " + topic.topicName() + "." + focus + " " + aim(request.questionType(), request.experience()),
                        angle == null ? List.of() : identifiers(angle.question() + " " + String.join(" ", angle.keyPoints())),
                        scopeOfSkill(topic.skillSlug()), Set.of(), topic.key(),
                        request.targetDifficulty(), null, QUESTION_CHUNKS);
            }
            case CODING -> new RetrievalQuery(
                    "Data structures and algorithms for a coding interview problem in " + request.codingLanguage()
                            + ": complexity, techniques and trade-offs.",
                    List.of(), codingScope(request.codingLanguage()), Set.of(), null,
                    null, null, QUESTION_CHUNKS);
            case SYSTEM_DESIGN -> new RetrievalQuery(
                    "System design interview: scalability, data storage, caching, partitioning, messaging and reliability trade-offs"
                            + (request.focusSkills().isEmpty() ? "." : " for a system built with " + String.join(", ", request.focusSkills()) + "."),
                    List.of(), DESIGN_SCOPE, Set.of(),
                    null, null, null, QUESTION_CHUNKS);
            case INTRO, FOLLOW_UP -> throw new IllegalArgumentException(request.kind() + " questions are not grounded here");
        };
        return new Grounding(List.of(query), List.of(), QUESTION_BUDGET, QUESTION_CHUNKS, request.interviewId());
    }

    /** What a technical question's search looks for, by the question's type; a request without a type searches as before. */
    private static String aim(@Nullable QuestionType type, ExperienceLevel experience) {
        String level = experience.name().toLowerCase(Locale.ROOT);
        if (type == null) {
            return "Concepts, internals, pitfalls and trade-offs that a " + level + " engineer should understand.";
        }
        return switch (type) {
            case CONCEPT, INTERNALS -> "Core concepts, how it works internally, and its trade-offs, as a " + level + " engineer should know them.";
            case SCENARIO, DEBUGGING -> "Applying it in practice: choosing between options, common failure modes, diagnosing problems and fixing them.";
            case EXPERIENCE -> "Concepts, pitfalls and trade-offs that come up when building real systems with it.";
        };
    }

    static Grounding followUp(QuestionRequest request) {
        FollowUpTarget target = request.followUp();
        String aim = switch (target.mode()) {
            case PROBE_GAP -> target.targetKeyPoint() == null ? "" : target.targetKeyPoint().text();
            case PROBE_CLAIM -> "";
            case DEEPEN -> "Edge cases, failure modes, performance and trade-offs.";
            case PROBE_DIMENSION -> target.designProbe() == null ? "" : target.designProbe().meaning();
        };
        TopicRef topic = request.topic();
        List<String> terms = identifiers(target.parentQuestionPrompt() + " " + (target.mode() == FollowUpMode.PROBE_GAP ? aim : ""));
        RetrievalQuery query = new RetrievalQuery(target.parentQuestionPrompt() + "\n" + aim, terms,
                scope(topic, request.section(), request.codingLanguage()), REFERENCE_MATERIAL, topic == null ? null : topic.key(),
                null, null, FOLLOW_UP_CHUNKS);
        return new Grounding(List.of(query), List.of(), FOLLOW_UP_BUDGET, FOLLOW_UP_CHUNKS, request.interviewId());
    }

    /** The evaluator's material: what the question's key points cite, then a search on the question and its key points. */
    static Grounding evaluation(EvaluationRequest request) {
        if (request.evaluator() == Evaluator.INTRO) {
            throw new IllegalArgumentException("the introduction is not grounded");
        }
        StringBuilder semantic = new StringBuilder(request.questionPrompt());
        List<UUID> pinned = new ArrayList<>();
        for (ExpectedKeyPoint point : request.expectedKeyPoints()) {
            semantic.append('\n').append(point.text());
            pinned.addAll(point.sourceChunkIds());
        }
        TopicRef topic = request.topic();
        Set<Technology> scope = switch (request.evaluator()) {
            case CODING -> codingScope(request.codingLanguage());
            case SYSTEM_DESIGN -> DESIGN_SCOPE;
            default -> topic == null ? Set.of() : scopeOfSkill(topic.skillSlug());
        };
        RetrievalQuery query = new RetrievalQuery(semantic.toString(),
                identifiers(semantic.toString()), scope, REFERENCE_MATERIAL,
                topic == null ? null : topic.key(), null, null, EVALUATION_CHUNKS);
        return new Grounding(List.of(query), pinned, EVALUATION_BUDGET, EVALUATION_CHUNKS, request.interviewId());
    }

    /** One search per evaluated answer with knowledge gaps, the weakest answers first, at most {@value #REPORT_GAP_QUERIES}. */
    static Grounding report(NarrativeRequest request) {
        List<RetrievalQuery> queries = request.evaluations().stream()
                .filter(digest -> !digest.gaps().isEmpty())
                .sorted(Comparator.comparingDouble(EvaluationDigest::score))
                .limit(REPORT_GAP_QUERIES)
                .map(GroundingQueries::gapQuery)
                .toList();
        return new Grounding(queries, List.of(), REPORT_BUDGET, REPORT_CHUNKS, request.interviewId());
    }

    private static RetrievalQuery gapQuery(EvaluationDigest digest) {
        String topicWords = digest.topic() == null ? digest.section().name().toLowerCase(Locale.ROOT).replace('_', ' ')
                : digest.topic().substring(digest.topic().indexOf('/') + 1).replace('-', ' ');
        Set<Technology> scope = digest.topic() != null
                ? scopeOfSkill(digest.topic().substring(0, digest.topic().indexOf('/')))
                : digest.section() == Section.CODING ? codingScope(null) : digest.section() == Section.SYSTEM_DESIGN ? DESIGN_SCOPE : Set.of();
        return new RetrievalQuery(topicWords + ": " + String.join("; ", digest.gaps()),
                identifiers(String.join(" ", digest.gaps())), scope, REFERENCE_MATERIAL, digest.topic(), null, null, 3);
    }

    private static Set<Technology> scope(@Nullable TopicRef topic, Section section, @Nullable String codingLanguage) {
        if (topic != null) {
            return scopeOfSkill(topic.skillSlug());
        }
        return switch (section) {
            case CODING -> codingScope(codingLanguage);
            case SYSTEM_DESIGN -> DESIGN_SCOPE;
            default -> Set.of();
        };
    }

    /**
     * The keyword half's terms: the exact identifiers in the text ({@code @Transactional}, {@code ConcurrentHashMap},
     * {@code useEffect}, {@code SERIALIZABLE}), which embeddings blur and full-text search matches exactly. Ordinary
     * words are left to the vector half: measured on the evaluation set, topic words as keywords matched every chunk of
     * their document and pulled the ranking down (RAG_ARCHITECTURE.md). At most {@value RetrievalQuery#MAX_TERMS}.
     */
    static List<String> identifiers(String text) {
        Set<String> terms = new LinkedHashSet<>();
        Matcher identifiers = IDENTIFIER.matcher(text);
        while (identifiers.find()) {
            terms.add(identifiers.group());
        }
        return terms.stream().limit(RetrievalQuery.MAX_TERMS).toList();
    }
}
