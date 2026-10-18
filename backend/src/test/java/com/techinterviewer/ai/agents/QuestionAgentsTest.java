package com.techinterviewer.ai.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.ai.observability.LlmCallStatus;
import com.techinterviewer.ai.provider.InvalidRequestException;
import com.techinterviewer.ai.provider.LlmRequest;
import com.techinterviewer.ai.support.AiStack;
import com.techinterviewer.ai.support.ScriptedLlmProvider;
import com.techinterviewer.design.DesignProbes;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.interview.domain.ExpectedKeyPoint;
import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.FollowUpMode;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.QuestionOrigin;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.TargetRole;
import com.techinterviewer.interview.port.AiUnavailableException;
import com.techinterviewer.interview.port.AskedQuestion;
import com.techinterviewer.interview.port.DesignProbe;
import com.techinterviewer.interview.port.FollowUpTarget;
import com.techinterviewer.interview.port.GeneratedQuestion;
import com.techinterviewer.interview.port.QuestionRequest;
import com.techinterviewer.interview.port.ThreadTurn;
import com.techinterviewer.interview.port.TopicRef;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The question generator and the follow-up generator behind the QuestionGenerator port. */
class QuestionAgentsTest {

    private static final TopicRef TOPIC = new TopicRef("java", "Java", "concurrency", "Concurrency");
    private static final UUID INTERVIEW = UUID.randomUUID();
    private static final String PROMPT = "Why can two threads that both run count++ on a shared int lose an update, and how would you prevent it?";

    private final ScriptedLlmProvider model = new ScriptedLlmProvider();
    private final AiStack stack = AiStack.around(model);

    private static QuestionRequest technical(List<AskedQuestion> asked, List<String> claims) {
        return new QuestionRequest(INTERVIEW, Section.QUESTIONING, QuestionKind.TECHNICAL, TOPIC, 3, TargetRole.BACKEND,
                ExperienceLevel.MID, null, List.of("Java"), asked, claims, null);
    }

    private static String question(String topic, int difficulty, String prompt, String keyPoints) {
        return """
                {"topic":"%s","difficulty":%d,"prompt":"%s","estimatedMinutes":4,"expectedKeyPoints":%s}"""
                .formatted(topic, difficulty, prompt, keyPoints);
    }

    private static final String KEY_POINTS = """
            [{"id":"kp1","text":"count++ is a read, an add and a write"},
             {"id":"kp2","text":"interleaved threads overwrite each other's write"},
             {"id":"kp3","text":"AtomicInteger or a lock makes it atomic"}]""";

    @Test
    void aTechnicalQuestionIsGeneratedAtTheRequestedTopicAndDifficultyWithItsProvenance() {
        model.thenAnswer(question("java/concurrency", 3, PROMPT, KEY_POINTS));

        GeneratedQuestion generated = stack.questionGenerator().generate(technical(List.of(), List.of()));

        assertThat(generated.prompt()).isEqualTo(PROMPT);
        assertThat(generated.expectedKeyPoints()).extracting(ExpectedKeyPoint::id).containsExactly("kp1", "kp2", "kp3");
        assertThat(generated.origin()).isEqualTo(QuestionOrigin.GENERATED);
        assertThat(generated.difficulty()).isEqualTo(3);
        assertThat(generated.topicSlug()).isEqualTo("java/concurrency");
        assertThat(generated.details()).isNull();
        assertThat(generated.groundingChunkIds()).isEmpty();
        assertThat(generated.promptId()).isEqualTo("technical-question");
        assertThat(generated.promptVersion()).isEqualTo("v2");
        assertThat(generated.model()).isEqualTo("test-model");
        assertThat(generated.llmCallId()).isEqualTo(stack.recorder.idOf(0));
        assertThat(stack.recorder.records().getFirst().task()).isEqualTo("QUESTION_GENERATION");
    }

    @Test
    void theSchemaPinsTheTopicAndTheDifficultyAndThePromptDelimitsCandidateText() {
        model.thenAnswer(question("java/concurrency", 3, PROMPT, KEY_POINTS));

        stack.questionGenerator().generate(technical(
                List.of(new AskedQuestion("java/jvm", "What is the JIT compiler?")), List.of("I ran Kafka in production")));

        LlmRequest sent = model.lastRequest();
        assertThat(sent.responseSchema().at("/properties/topic/enum/0").asString()).isEqualTo("java/concurrency");
        assertThat(sent.responseSchema().at("/properties/topic/enum").size()).isEqualTo(1);
        assertThat(sent.responseSchema().at("/properties/difficulty/minimum").asInt()).isEqualTo(3);
        assertThat(sent.responseSchema().at("/properties/difficulty/maximum").asInt()).isEqualTo(3);
        assertThat(sent.userPrompt()).contains("Topic key: java/concurrency").contains("Difficulty: 3 of 5")
                .containsPattern("\\[BEGIN UNTRUSTED candidate_claims [0-9a-f]{16}]\\n- I ran Kafka in production")
                .containsPattern("\\[BEGIN UNTRUSTED asked_questions [0-9a-f]{16}]\\n- What is the JIT compiler\\?");
    }

    @Test
    void aWrongTopicOrDifficultyIsSentBackForRepair() {
        model.thenAnswer(question("java/jvm", 4, PROMPT, KEY_POINTS))
                .thenAnswer(question("java/concurrency", 3, PROMPT, KEY_POINTS));

        GeneratedQuestion generated = stack.questionGenerator().generate(technical(List.of(), List.of()));

        assertThat(generated.difficulty()).isEqualTo(3);
        assertThat(model.lastRequest().userPrompt())
                .contains("- topic: must be exactly \"java/concurrency\" but was \"java/jvm\"")
                .contains("- difficulty: must be 3 but was 4");
        assertThat(stack.recorder.statuses()).containsExactly(LlmCallStatus.INVALID_OUTPUT, LlmCallStatus.REPAIRED);
    }

    @Test
    void aQuestionThatGivesAwayAKeyPointOrRepeatsAnEarlierQuestionIsSentBack() {
        String leaking = "Explain why count++ is a read, an add and a write and why that matters for two threads.";
        model.thenAnswer(question("java/concurrency", 3, leaking, KEY_POINTS))
                .thenAnswer(question("java/concurrency", 3, PROMPT, KEY_POINTS))
                .thenAnswer(question("java/concurrency", 3, PROMPT, KEY_POINTS))
                .thenAnswer(question("java/concurrency", 3, "How does a ConcurrentHashMap stay consistent when two threads resize it at once?", KEY_POINTS));

        stack.questionGenerator().generate(technical(List.of(), List.of()));
        assertThat(model.lastRequest().userPrompt()).contains("- prompt: it contains the text of key point kp1, which gives the answer away");

        GeneratedQuestion second = stack.questionGenerator().generate(technical(List.of(new AskedQuestion("java/concurrency", PROMPT)), List.of()));
        assertThat(model.lastRequest().userPrompt()).contains("it repeats a question already asked");
        assertThat(second.prompt()).startsWith("How does a ConcurrentHashMap");
    }

    @Test
    void aCodingProblemCarriesItsExamplesAndTheInterviewsLanguage() {
        model.thenAnswer("""
                {"topic":"none","difficulty":2,"prompt":"Given an array of integers, return the indices of the two numbers that add up to a target.",
                 "estimatedMinutes":15,
                 "expectedKeyPoints":[{"id":"kp1","text":"one pass with a hash map from value to index"},
                                      {"id":"kp2","text":"O(n) time and O(n) space"},{"id":"kp3","text":"handles duplicate values"}],
                 "coding":{"constraints":["2 <= n <= 10^5"],
                           "examples":[{"input":"[2,7,11,15], 9","output":"[0,1]","explanation":null}],
                           "starterSignature":"int[] twoSum(int[] nums, int target)"}}""");
        QuestionRequest coding = new QuestionRequest(INTERVIEW, Section.CODING, QuestionKind.CODING, null, 2, TargetRole.BACKEND,
                ExperienceLevel.MID, "java", List.of("Java"), List.of(), List.of(), null);

        GeneratedQuestion generated = stack.questionGenerator().generate(coding);

        assertThat(generated.promptId()).isEqualTo("coding-question");
        assertThat(generated.topicSlug()).isNull();
        assertThat(generated.details().language()).isEqualTo("java");
        assertThat(generated.details().examples()).singleElement()
                .satisfies(example -> assertThat(example.explanation()).isNull());
        assertThat(generated.details().starterSignature()).startsWith("int[] twoSum");
        assertThat(model.lastRequest().userPrompt()).contains("Language the candidate must use: java").contains("Topic key: none");
    }

    @Test
    void aCodingProblemWithoutItsMaterialIsSentBack() {
        model.thenAnswer(question("none", 2, "Given an array of integers, return the indices of two that add to a target.", KEY_POINTS))
                .thenAnswer(question("none", 2, "Given an array of integers, return the indices of two that add to a target.", KEY_POINTS));
        QuestionRequest coding = new QuestionRequest(INTERVIEW, Section.CODING, QuestionKind.CODING, null, 2, TargetRole.BACKEND,
                ExperienceLevel.MID, "java", List.of("Java"), List.of(), List.of(), null);

        assertThatThrownBy(() -> stack.questionGenerator().generate(coding))
                .isInstanceOf(AiUnavailableException.class)
                .hasMessage(AiUnavailableException.DEFAULT_DETAIL);
        assertThat(model.lastRequest().userPrompt()).contains("- coding: the constraints and examples of the problem are required");
    }

    @Test
    void everyFailureReachesTheInterviewAsAGenericUnavailability() {
        model.alwaysFail(new InvalidRequestException("gemini answered HTTP 400: model gemini-9 not found"));

        assertThatThrownBy(() -> stack.questionGenerator().generate(technical(List.of(), List.of())))
                .isInstanceOf(AiUnavailableException.class)
                .hasMessage(AiUnavailableException.DEFAULT_DETAIL)
                .hasRootCauseInstanceOf(InvalidRequestException.class);
    }

    // ---- follow-ups ------------------------------------------------------------------------------------------------

    private static final ExpectedKeyPoint GAP = new ExpectedKeyPoint("kp2", "interleaved threads overwrite each other's write", List.of());

    private static QuestionRequest followUp(FollowUpMode mode) {
        FollowUpTarget target = switch (mode) {
            case PROBE_GAP -> new FollowUpTarget(mode, PROMPT, "It is not atomic.", GAP, null);
            case PROBE_CLAIM -> new FollowUpTarget(mode, PROMPT, "We used AtomicLong in our ledger.", null, "Built a ledger with AtomicLong");
            case DEEPEN -> new FollowUpTarget(mode, PROMPT, "A thorough answer.", null, null);
            case PROBE_DIMENSION -> throw new IllegalArgumentException("a design probe has its own request: see designProbe()");
        };
        return new QuestionRequest(INTERVIEW, Section.QUESTIONING, QuestionKind.FOLLOW_UP, TOPIC, 4, TargetRole.BACKEND,
                ExperienceLevel.MID, null, List.of("Java"), List.of(), List.of(), target);
    }

    private static String followUpAnswer(String mode, String prompt) {
        return """
                {"mode":"%s","prompt":"%s","estimatedMinutes":3,
                 "expectedKeyPoints":[{"id":"fu1","text":"names a concrete interleaving of two increments"}]}""".formatted(mode, prompt);
    }

    private static QuestionRequest designProbe() {
        DesignProbe probe = new DesignProbe(Criterion.CACHING, DesignProbes.meaning(Criterion.CACHING),
                "Where would you cache short links, and what happens when a link is deleted?", "Design a URL shortener.",
                List.of(new ThreadTurn("Design a URL shortener.", "A stateless redirect service reads Postgres by short code.", false)),
                "Components:\n- Redirect service (service)\n- Links (database): Postgres\nConnections:\n- Redirect service -> Links");
        FollowUpTarget target = FollowUpTarget.designProbe("Design a URL shortener.", "A stateless redirect service reads Postgres.", probe);
        return new QuestionRequest(INTERVIEW, Section.SYSTEM_DESIGN, QuestionKind.FOLLOW_UP, null, 3, TargetRole.BACKEND,
                ExperienceLevel.MID, null, List.of("Java"), List.of(), List.of(), target);
    }

    @Test
    void aDesignProbeAppliesTheScenarioToTheCandidatesDesignAndWritesNoKeyPoints() {
        model.thenAnswer("""
                {"mode":"PROBE_DIMENSION","criterion":"CACHING","prompt":"Your redirect service reads Postgres on every click. Where would you cache, and what happens when a link is deleted?","estimatedMinutes":4}""");

        GeneratedQuestion generated = stack.questionGenerator().generate(designProbe());

        assertThat(generated.promptId()).isEqualTo("design-probe-question");
        assertThat(generated.prompt()).startsWith("Your redirect service reads Postgres");
        assertThat(generated.expectedKeyPoints()).as("the thread is judged against the problem's key points").isEmpty();
        assertThat(generated.difficulty()).isEqualTo(3);
        LlmRequest sent = model.lastRequest();
        assertThat(sent.responseSchema().at("/properties/criterion/enum/0").asString()).isEqualTo("CACHING");
        assertThat(sent.userPrompt()).contains("Dimension to probe: CACHING, that is caching:")
                .contains("Scenario to build the question on: Where would you cache short links")
                .containsPattern("\\[BEGIN UNTRUSTED design_discussion [0-9a-f]{16}]\\nQuestion 1: Design a URL shortener\\.\\nAnswer 1: A stateless")
                .containsPattern("\\[BEGIN UNTRUSTED architecture_diagram [0-9a-f]{16}]\\nComponents:");
    }

    @Test
    void aProbeForAnotherDimensionOrRepeatingTheDiscussionIsSentBack() {
        model.thenAnswer("""
                {"mode":"PROBE_DIMENSION","criterion":"CACHING","prompt":"Design a URL shortener.","estimatedMinutes":4}""")
                .thenAnswer("""
                {"mode":"PROBE_DIMENSION","criterion":"CACHING","prompt":"Where would you put a cache in front of Postgres, and how do deletes reach it?","estimatedMinutes":4}""");

        GeneratedQuestion generated = stack.questionGenerator().generate(designProbe());

        assertThat(generated.prompt()).startsWith("Where would you put a cache");
        assertThat(model.lastRequest().userPrompt()).contains("repeats a question already asked");
    }

    @Test
    void aFollowUpIsWordedForTheDecidedModeAtTheRequestedDifficulty() {
        model.thenAnswer(followUpAnswer("PROBE_GAP", "Walk me through what happens step by step when two threads increment at the same moment."));

        GeneratedQuestion generated = stack.questionGenerator().generate(followUp(FollowUpMode.PROBE_GAP));

        assertThat(generated.promptId()).isEqualTo("follow-up-question");
        assertThat(generated.difficulty()).isEqualTo(4);
        assertThat(generated.topicSlug()).isEqualTo("java/concurrency");
        assertThat(generated.expectedKeyPoints()).extracting(ExpectedKeyPoint::id).containsExactly("fu1");
        assertThat(stack.recorder.records().getFirst().task()).isEqualTo("FOLLOW_UP_GENERATION");
        LlmRequest sent = model.lastRequest();
        assertThat(sent.responseSchema().at("/properties/mode/enum").size()).isEqualTo(1);
        assertThat(sent.responseSchema().at("/properties/mode/enum/0").asString()).isEqualTo("PROBE_GAP");
        assertThat(sent.userPrompt()).contains("Follow-up mode: PROBE_GAP")
                .contains("Key point to probe without revealing it (PROBE_GAP only): " + GAP.text())
                .containsPattern("\\[BEGIN UNTRUSTED candidate_answer [0-9a-f]{16}]\\nIt is not atomic\\.");
    }

    @Test
    void theClaimToProbeIsCandidateTextAndIsDelimited() {
        model.thenAnswer(followUpAnswer("PROBE_CLAIM", "What contention did you measure on that AtomicLong under peak load?"));

        stack.questionGenerator().generate(followUp(FollowUpMode.PROBE_CLAIM));

        assertThat(model.lastRequest().userPrompt())
                .containsPattern("\\[BEGIN UNTRUSTED candidate_claim [0-9a-f]{16}]\\nBuilt a ledger with AtomicLong")
                .contains("Key point to probe without revealing it (PROBE_GAP only): not applicable");
    }

    @Test
    void aFollowUpThatRevealsItsGapRepeatsTheQuestionOrChangesTheModeIsSentBack() {
        model.thenAnswer(followUpAnswer("PROBE_GAP", "Did you know that interleaved threads overwrite each other's write?"))
                .thenAnswer(followUpAnswer("PROBE_GAP", PROMPT))
                .thenAnswer(followUpAnswer("DEEPEN", "What happens to throughput when sixty-four threads hammer one AtomicInteger?"))
                .thenAnswer(followUpAnswer("DEEPEN", "What happens to throughput when sixty-four threads hammer one AtomicInteger?"));

        assertThatThrownBy(() -> stack.questionGenerator().generate(followUp(FollowUpMode.PROBE_GAP)))
                .isInstanceOf(AiUnavailableException.class);
        assertThat(model.requests().get(1).userPrompt()).contains("it states the key point it should probe");
        assertThat(stack.recorder.statuses()).containsExactly(LlmCallStatus.INVALID_OUTPUT, LlmCallStatus.INVALID_OUTPUT);

        assertThatThrownBy(() -> stack.questionGenerator().generate(followUp(FollowUpMode.PROBE_GAP)))
                .isInstanceOf(AiUnavailableException.class);
        assertThat(model.lastRequest().userPrompt()).contains("mode: must be PROBE_GAP but was DEEPEN");
    }
}
