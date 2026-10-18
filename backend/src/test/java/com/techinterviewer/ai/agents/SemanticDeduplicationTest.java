package com.techinterviewer.ai.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.ai.observability.LlmCallStatus;
import com.techinterviewer.ai.support.AiStack;
import com.techinterviewer.ai.support.ScriptedLlmProvider;
import com.techinterviewer.interview.domain.ExpectedKeyPoint;
import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.FollowUpMode;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.TargetRole;
import com.techinterviewer.interview.port.AiUnavailableException;
import com.techinterviewer.interview.port.FollowUpTarget;
import com.techinterviewer.interview.port.GeneratedQuestion;
import com.techinterviewer.interview.port.QuestionEmbedding;
import com.techinterviewer.interview.port.QuestionRequest;
import com.techinterviewer.interview.port.TopicRef;
import com.techinterviewer.rag.dedup.QuestionDeduplicator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Semantic de-duplication in the generator agents: a question that asks essentially the same as an earlier one is sent
 * back for repair with the reason (the backend's check decides, the model rewrites), and the accepted question carries
 * its vector for storage.
 */
class SemanticDeduplicationTest {

    private static final UUID INTERVIEW = UUID.randomUUID();
    private static final TopicRef TOPIC = new TopicRef("java", "Java", "concurrency", "Concurrency");
    private static final String PARAPHRASE = "How do volatile fields differ from synchronized blocks when two threads share a counter?";
    private static final String FRESH = "When would you choose a ReentrantLock over synchronized, and what does tryLock give you?";

    private final ScriptedLlmProvider model = new ScriptedLlmProvider();
    private final AiStack stack = AiStack.around(model);
    private final FakeDeduplicator deduplicator = new FakeDeduplicator();

    {
        stack.deduplicator = deduplicator;
    }

    /** Embeds each text as a vector naming it; a text in {@code repeats} is reported as a repeat of an earlier question. */
    private static final class FakeDeduplicator implements QuestionDeduplicator {

        final List<String> embedded = new ArrayList<>();
        Set<String> repeats = Set.of();
        boolean unavailable;

        @Override
        public @Nullable QuestionEmbedding embed(UUID interviewId, String question) {
            embedded.add(question);
            if (unavailable) {
                return null;
            }
            float[] vector = new float[QuestionEmbedding.DIMENSIONS];
            vector[Math.floorMod(question.hashCode(), vector.length)] = 1;
            return new QuestionEmbedding("test-embedding", vector);
        }

        @Override
        public Optional<Repeat> repeatOf(UUID interviewId, QuestionEmbedding embedding) {
            return embedded.isEmpty() || !repeats.contains(embedded.getLast())
                    ? Optional.empty()
                    : Optional.of(new Repeat(UUID.randomUUID(), 0.93));
        }
    }

    private static QuestionRequest technical() {
        return new QuestionRequest(INTERVIEW, Section.QUESTIONING, QuestionKind.TECHNICAL, TOPIC, 3, TargetRole.BACKEND,
                ExperienceLevel.MID, null, List.of("Java"), List.of(), List.of(), null);
    }

    private static String question(String prompt) {
        return """
                {"topic":"java/concurrency","difficulty":3,"prompt":"%s","estimatedMinutes":4,"expectedKeyPoints":[
                 {"id":"kp1","text":"volatile gives visibility only"},{"id":"kp2","text":"synchronized gives mutual exclusion"},
                 {"id":"kp3","text":"count++ needs atomicity"}]}""".formatted(prompt);
    }

    @Test
    void aQuestionThatRepeatsAnEarlierOneInOtherWordsIsSentBackAndTheRewriteIsAccepted() {
        deduplicator.repeats = Set.of(PARAPHRASE);
        model.thenAnswer(question(PARAPHRASE)).thenAnswer(question(FRESH));

        GeneratedQuestion generated = stack.questionGenerator().generate(technical());

        assertThat(generated.prompt()).isEqualTo(FRESH);
        assertThat(model.lastRequest().userPrompt())
                .contains("- prompt: it asks essentially the same as a question already asked in this interview (similarity 0.93)")
                .as("the earlier question is not quoted back: it is model output").doesNotContain(PARAPHRASE.substring(0, 40));
        assertThat(stack.recorder.statuses()).containsExactly(LlmCallStatus.INVALID_OUTPUT, LlmCallStatus.REPAIRED);
        assertThat(deduplicator.embedded).containsExactly(PARAPHRASE, FRESH);
        assertThat(generated.embedding()).isNotNull();
        assertThat(generated.embedding().model()).isEqualTo("test-embedding");
        assertThat(generated.embedding()).as("the vector of the accepted text, not of the rejected one")
                .isEqualTo(deduplicator.embed(INTERVIEW, FRESH));
    }

    @Test
    void aRewriteThatStillRepeatsMakesTheGeneratorUnavailableSoTheInterviewWaitsAndRetries() {
        deduplicator.repeats = Set.of(PARAPHRASE);
        model.thenAnswer(question(PARAPHRASE)).thenAnswer(question(PARAPHRASE));

        assertThatThrownBy(() -> stack.questionGenerator().generate(technical())).isInstanceOf(AiUnavailableException.class);
    }

    @Test
    void aDraftThatFailsAnotherCheckIsNotEmbedded() {
        model.thenAnswer(question(FRESH).replace("\"difficulty\":3", "\"difficulty\":4")).thenAnswer(question(FRESH));

        stack.questionGenerator().generate(technical());

        assertThat(deduplicator.embedded).as("only the draft that passed the other checks").containsExactly(FRESH);
    }

    @Test
    void withoutAVectorTheQuestionIsAcceptedOnTheLexicalCheckAlone() {
        deduplicator.unavailable = true;
        model.thenAnswer(question(FRESH));

        GeneratedQuestion generated = stack.questionGenerator().generate(technical());

        assertThat(generated.prompt()).isEqualTo(FRESH);
        assertThat(generated.embedding()).isNull();
    }

    @Test
    void aFollowUpIsEmbeddedForLaterComparisonButNotComparedItself() {
        deduplicator.repeats = Set.of("Which part of your counter would still lose updates if the field were volatile?");
        model.thenAnswer("""
                {"mode":"PROBE_GAP","prompt":"Which part of your counter would still lose updates if the field were volatile?",
                 "estimatedMinutes":3,"expectedKeyPoints":[{"id":"fu1","text":"names the read-modify-write of the increment"}]}""");
        FollowUpTarget target = new FollowUpTarget(FollowUpMode.PROBE_GAP, FRESH, "I would make the field volatile.",
                new ExpectedKeyPoint("kp3", "count++ needs atomicity", List.of()), null);
        QuestionRequest followUp = new QuestionRequest(INTERVIEW, Section.QUESTIONING, QuestionKind.FOLLOW_UP, TOPIC, 3,
                TargetRole.BACKEND, ExperienceLevel.MID, null, List.of("Java"), List.of(), List.of(), target);

        GeneratedQuestion generated = stack.questionGenerator().generate(followUp);

        assertThat(generated.embedding()).isNotNull();
        assertThat(stack.recorder.statuses()).as("a follow-up stays close to its parent by design").containsExactly(LlmCallStatus.OK);
    }
}
