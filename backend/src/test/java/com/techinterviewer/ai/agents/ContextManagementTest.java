package com.techinterviewer.ai.agents;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.ai.support.AiStack;
import com.techinterviewer.ai.support.ScriptedLlmProvider;
import com.techinterviewer.evaluation.domain.Evaluator;
import com.techinterviewer.interview.domain.ExpectedKeyPoint;
import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.TargetRole;
import com.techinterviewer.interview.port.AskedQuestion;
import com.techinterviewer.interview.port.EvaluationRequest;
import com.techinterviewer.interview.port.QuestionRequest;
import com.techinterviewer.interview.port.ThreadTurn;
import com.techinterviewer.interview.port.TopicRef;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Cost control: the model is sent what the task needs, never the interview transcript. */
class ContextManagementTest {

    private static final TopicRef TOPIC = new TopicRef("java", "Java", "concurrency", "Concurrency");

    private final ScriptedLlmProvider model = new ScriptedLlmProvider();
    private final AiStack stack = AiStack.around(model);

    @Test
    void aLongInterviewSendsOnlyTheMostRecentQuestionsAndShortenedToTheLimits() {
        List<AskedQuestion> asked = IntStream.rangeClosed(1, 100)
                .mapToObj(i -> new AskedQuestion("java/topic" + i, "Question number " + i + " " + "x".repeat(2000))).toList();
        QuestionRequest request = new QuestionRequest(UUID.randomUUID(), Section.QUESTIONING, QuestionKind.TECHNICAL, TOPIC, 3,
                TargetRole.BACKEND, ExperienceLevel.MID, null, List.of("Java"), asked, List.of(), null);
        model.thenAnswer("not json").thenAnswer("not json");

        try {
            stack.questionGenerator().generate(request);
        } catch (RuntimeException expected) {
            // The scripted model answers nonsense; only the request it received matters here.
        }

        String prompt = model.requests().getFirst().userPrompt();
        assertThat(prompt).contains("Question number 100 ").contains("Question number 71 ")
                .doesNotContain("Question number 70 ").doesNotContain("Question number 1 ");
        assertThat(prompt.length()).as("100 questions of 2000 characters stay far below their raw size").isLessThan(15_000);
    }

    @Test
    void anEvaluationCarriesItsOwnThreadAndNoOtherAnswerOfTheInterview() {
        List<ThreadTurn> thread = List.of(new ThreadTurn("Earlier question of this thread?", "Earlier answer of this thread.", false));
        EvaluationRequest request = new EvaluationRequest(UUID.randomUUID(), UUID.randomUUID(), Evaluator.TECHNICAL, TOPIC, 3,
                "Current question?", List.of(new ExpectedKeyPoint("kp1", "an idea", List.of())), null, "Current answer.", null,
                thread, "");
        model.thenAnswer("not json").thenAnswer("not json");

        try {
            stack.answerEvaluator().evaluate(request);
        } catch (RuntimeException expected) {
            // Only the request matters here.
        }

        String prompt = model.requests().getFirst().userPrompt();
        assertThat(prompt).contains("Current question?").contains("Current answer.")
                .contains("Earlier question of this thread?").contains("Earlier answer of this thread.");
        assertThat(model.requests().getFirst().systemPrompt()).doesNotContain("Current answer.");
    }

    @Test
    void thereIsNoModelCallForAnythingTheServerCanDecide() {
        // Planning, difficulty adaptation, scoring and completion never reach a provider: the only tasks are these five. Reading a
        // résumé is a model call, but planning from it is not: the links of its experiences to topics come from a fixed vocabulary.
        assertThat(com.techinterviewer.ai.routing.AiTask.values()).extracting(Enum::name).containsExactlyInAnyOrder(
                "QUESTION_GENERATION", "FOLLOW_UP_GENERATION", "ANSWER_EVALUATION", "REPORT_WRITING", "RESUME_EXTRACTION");
    }
}
