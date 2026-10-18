package com.techinterviewer.ai.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.ai.provider.LlmRequest;
import com.techinterviewer.ai.support.AiStack;
import com.techinterviewer.ai.support.ScriptedLlmProvider;
import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.QuestionType;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.TargetRole;
import com.techinterviewer.interview.port.CuratedTemplate;
import com.techinterviewer.interview.port.GeneratedQuestion;
import com.techinterviewer.interview.port.QuestionRequest;
import com.techinterviewer.interview.port.ResumeAnchor;
import com.techinterviewer.interview.port.TopicRef;
import com.techinterviewer.rag.retrieval.RetrievalQuery;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Typed technical questions (AI_ARCHITECTURE.md section 2.15): the prompt follows the type the engine decided, a fundamentals or
 * practical question is written from the curated angle and never sees what the candidate said about their own work, and an
 * experience question without a résumé is checked against the candidate's statements.
 */
class TypedQuestionsTest {

    private static final TopicRef COLLECTIONS = new TopicRef("java", "Java", "collections", "Collections");
    private static final CuratedTemplate HASHMAP = new CuratedTemplate("java-hashmap-internals", QuestionType.INTERNALS, "HashMap internals",
            "How does HashMap store and find an entry, and what happens when two keys have the same hash code?",
            List.of("The key's hashCode is spread and mapped to a bucket index", "equals decides which entry of the bucket is the key"),
            List.of("HashMap keeps its keys in insertion order"));
    private static final List<String> CLAIMS = List.of("At my last job I built the order service with Spring Boot and Kafka");
    private static final String KEY_POINTS = """
            [{"id":"kp1","text":"the hash code picks a bucket","sources":[]},
             {"id":"kp2","text":"equals finds the key in the bucket","sources":[]},
             {"id":"kp3","text":"a crowded bucket becomes a tree","sources":[]}]""";

    private final ScriptedLlmProvider model = new ScriptedLlmProvider();
    private final AiStack stack = AiStack.around(model);

    private static QuestionRequest typed(QuestionType type, @Nullable CuratedTemplate template, @Nullable ResumeAnchor anchor, List<String> claims) {
        return new QuestionRequest(UUID.randomUUID(), Section.QUESTIONING, QuestionKind.TECHNICAL, COLLECTIONS, 3, TargetRole.BACKEND,
                ExperienceLevel.MID, null, List.of("Java"), List.of(), claims, null, anchor, type, template);
    }

    private static String question(String prompt) {
        return """
                {"topic":"java/collections","difficulty":3,"prompt":"%s","estimatedMinutes":4,"expectedKeyPoints":%s}"""
                .formatted(prompt, KEY_POINTS);
    }

    private static String experience(String prompt, String quote) {
        return """
                {"topic":"java/collections","difficulty":3,"prompt":"%s","estimatedMinutes":5,"expectedKeyPoints":%s,"resumeQuote":"%s"}"""
                .formatted(prompt, KEY_POINTS, quote);
    }

    @Test
    void aFundamentalsQuestionIsWrittenFromTheCuratedAngleAndNeverSeesWhatTheCandidateSaid() {
        model.thenAnswer(question("Walk me through what happens inside a HashMap on put and get when two keys collide."));

        GeneratedQuestion generated = stack.questionGenerator().generate(typed(QuestionType.INTERNALS, HASHMAP, null, List.of()));

        assertThat(generated.promptId()).isEqualTo("fundamentals-question");
        LlmRequest sent = model.lastRequest();
        assertThat(sent.systemPrompt()).contains("never ask about their own work");
        assertThat(sent.userPrompt())
                .contains("Kind of question: INTERNALS")
                .contains("HashMap internals: How does HashMap store and find an entry")
                .contains("- equals decides which entry of the bucket is the key")
                .contains("- HashMap keeps its keys in insertion order")
                .doesNotContain("candidate_claims")
                .doesNotContain("Kafka");
    }

    @Test
    void scenarioAndDebuggingQuestionsUseThePracticalPromptAndWorkWithoutAnAngle() {
        model.thenAnswer(question("A cache keyed by a mutable object loses entries. How would you find out why?"));

        GeneratedQuestion generated = stack.questionGenerator().generate(typed(QuestionType.DEBUGGING, null, null, List.of()));

        assertThat(generated.promptId()).isEqualTo("scenario-question");
        assertThat(model.lastRequest().userPrompt()).contains("Kind of question: DEBUGGING").contains(QuestionGeneratorAgent.NO_ANGLE);
    }

    private static final String GROUNDED =
            "You said you built the order service with Spring Boot and Kafka: which collections held the in-flight orders, and why?";

    @Test
    void anExperienceQuestionWithoutAResumeIsAboutWhatTheCandidateSaidAndMustQuoteIt() {
        model.thenAnswer(experience(GROUNDED, "led the platform team")).thenAnswer(experience(GROUNDED, "built the order service with Spring Boot"));

        GeneratedQuestion generated = stack.questionGenerator().generate(typed(QuestionType.EXPERIENCE, null, null, CLAIMS));

        assertThat(generated.promptId()).isEqualTo("experience-question");
        assertThat(generated.prompt()).isEqualTo(GROUNDED);
        assertThat(model.requests().getFirst().userPrompt())
                .containsPattern("\\[BEGIN UNTRUSTED candidate_statements [0-9a-f]{16}]\\n- At my last job I built the order service");
        assertThat(model.lastRequest().userPrompt()).contains("- resumeQuote: copy at least three words of one of the candidate's statements");
    }

    @Test
    void anExperienceQuestionMayNotPresumeATechnologyTheCandidateDidNotMention() {
        model.thenAnswer(experience("You used RabbitMQ in the order service: how did you store the orders?", "built the order service"))
                .thenAnswer(experience(GROUNDED, "built the order service with Spring Boot"));

        GeneratedQuestion generated = stack.questionGenerator().generate(typed(QuestionType.EXPERIENCE, null, null, CLAIMS));

        assertThat(generated.prompt()).isEqualTo(GROUNDED);
        assertThat(model.lastRequest().userPrompt()).contains("- prompt: it names RabbitMQ, which the candidate did not mention");
    }

    @Test
    void anExperienceQuestionAnchoredOnTheResumeStillUsesTheResumePrompt() {
        ResumeAnchor anchor = new ResumeAnchor("x1", "PROJECT", "Built an order service with Spring Boot that kept orders in a ConcurrentHashMap.",
                null, List.of("Spring Boot"));
        model.thenAnswer(experience("How did the ConcurrentHashMap in the order service you built behave under concurrent updates?",
                "kept orders in a ConcurrentHashMap"));

        GeneratedQuestion generated = stack.questionGenerator().generate(typed(QuestionType.EXPERIENCE, null, anchor, List.of()));

        assertThat(generated.promptId()).isEqualTo("resume-question");
    }

    @Test
    void aQuestionWithoutATypeIsWrittenAsBeforeStagesExisted() {
        model.thenAnswer(question("Why does a HashMap need both hashCode and equals?"));

        GeneratedQuestion generated = stack.questionGenerator().generate(new QuestionRequest(UUID.randomUUID(), Section.QUESTIONING,
                QuestionKind.TECHNICAL, COLLECTIONS, 3, TargetRole.BACKEND, ExperienceLevel.MID, null, List.of("Java"), List.of(), CLAIMS, null));

        assertThat(generated.promptId()).isEqualTo("technical-question");
        assertThat(model.lastRequest().userPrompt()).contains("candidate_claims");
    }

    @Test
    void theSearchOfATypedQuestionLooksForItsAngleAndItsIdentifiers() {
        RetrievalQuery fundamentals = GroundingQueries.question(typed(QuestionType.INTERNALS, HASHMAP, null, List.of())).queries().getFirst();
        RetrievalQuery practical = GroundingQueries.question(typed(QuestionType.SCENARIO, null, null, List.of())).queries().getFirst();

        assertThat(fundamentals.semanticText()).contains("HashMap internals").contains("how it works internally");
        assertThat(fundamentals.keywordTerms()).contains("HashMap", "hashCode");
        assertThat(fundamentals.topicKey()).isEqualTo("java/collections");
        assertThat(practical.semanticText()).contains("Applying it in practice").doesNotContain("HashMap");
        assertThat(practical.keywordTerms()).isEmpty();
    }

    @Test
    void theRequestKeepsTypesTemplatesAndAnchorsConsistent() {
        assertThatThrownBy(() -> typed(QuestionType.SCENARIO, HASHMAP, null, List.of())).hasMessageContaining("template");
        ResumeAnchor anchor = new ResumeAnchor("x1", "PROJECT", "Built an order service with Spring Boot.", null, List.of());
        assertThatThrownBy(() -> typed(QuestionType.CONCEPT, null, anchor, List.of())).hasMessageContaining("EXPERIENCE");
    }
}
