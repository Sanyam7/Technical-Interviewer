package com.techinterviewer.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.TestJson;
import com.techinterviewer.catalog.TopicInfo;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.interview.api.dto.AnswerFormat;
import com.techinterviewer.interview.api.dto.CodingExampleView;
import com.techinterviewer.interview.api.dto.DesignDetailsResponse;
import com.techinterviewer.interview.api.dto.QuestionDetailsResponse;
import com.techinterviewer.interview.api.dto.QuestionResponse;
import com.techinterviewer.interview.api.dto.TopicView;
import com.techinterviewer.interview.domain.CodingExample;
import com.techinterviewer.interview.domain.ExpectedKeyPoint;
import com.techinterviewer.interview.domain.InterviewQuestion;
import com.techinterviewer.interview.domain.QuestionContent;
import com.techinterviewer.interview.domain.QuestionDetails;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.QuestionOrigin;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.persistence.Fixtures;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class QuestionViewMapperTest {

    private static final Instant T0 = Instant.parse("2026-10-05T12:00:00Z");
    private static final UUID INTERVIEW = UUID.fromString("0192f1c2-7a3b-7c11-9d4e-5f6a7b8c9d10");
    private static final TopicInfo CONCURRENCY = new TopicInfo(7, 1, "java", "Java", "concurrency", "Concurrency");
    private static final QuestionViewMapper.Context CONTEXT =
            new QuestionViewMapper.Context(Map.of(7L, CONCURRENCY), Fixtures.plan(), "python");

    private static QuestionContent content(String prompt, QuestionDetails details) {
        return new QuestionContent(
                prompt,
                details,
                List.of(new ExpectedKeyPoint("kp-secret", "the secret key point text", List.of(UUID.randomUUID()))),
                "rubric-secret.v1",
                QuestionOrigin.GENERATED,
                null);
    }

    private static InterviewQuestion technical() {
        return InterviewQuestion.root(INTERVIEW, 2, QuestionKind.TECHNICAL, 7L, 4, content("Explain volatile.", null), T0);
    }

    private static InterviewQuestion coding(QuestionDetails details) {
        return InterviewQuestion.root(INTERVIEW, 5, QuestionKind.CODING, null, 5, content("Sum an array.", details), T0);
    }

    @Test
    void aTechnicalQuestionShowsItsTopicWordingAndAdviceAndNothingElse() {
        InterviewQuestion question = technical();

        QuestionResponse view = QuestionViewMapper.toView(question, false, CONTEXT);

        assertThat(view).isEqualTo(new QuestionResponse(
                question.getId(), 2, QuestionKind.TECHNICAL, false, Section.QUESTIONING, new TopicView("Java", "Concurrency"),
                "Explain volatile.", AnswerFormat.TEXT, 4, null, false, null));
    }

    @Test
    void theAnsweredFlagIsCarriedThrough() {
        assertThat(QuestionViewMapper.toView(technical(), true, CONTEXT).answered()).isTrue();
        assertThat(QuestionViewMapper.toView(technical(), false, CONTEXT).answered()).isFalse();
    }

    @Test
    void theIntroductionHasNoTopicAndNoDetails() {
        InterviewQuestion intro = InterviewQuestion.root(INTERVIEW, 1, QuestionKind.INTRO, null, 3, Fixtures.staticContent(), T0);

        QuestionResponse view = QuestionViewMapper.toView(intro, false, CONTEXT);

        assertThat(view.topic()).isNull();
        assertThat(view.details()).isNull();
        assertThat(view.kind()).isEqualTo(QuestionKind.INTRO);
        assertThat(view.section()).isEqualTo(Section.INTRODUCTION);
        assertThat(view.answerFormat()).isEqualTo(AnswerFormat.TEXT);
        assertThat(view.suggestedMinutes()).isEqualTo(4);
    }

    @Test
    void aFollowUpIsMarkedAndGetsHalfTheTimeOfAMainQuestion() {
        InterviewQuestion root = technical();
        InterviewQuestion followUp = InterviewQuestion.followUp(
                INTERVIEW, 3, Section.QUESTIONING, 7L, 4, root.getId(), 1, content("Why?", null), T0);

        QuestionResponse view = QuestionViewMapper.toView(followUp, false, CONTEXT);

        assertThat(view.isFollowUp()).isTrue();
        assertThat(view.kind()).isEqualTo(QuestionKind.FOLLOW_UP);
        assertThat(view.suggestedMinutes()).isEqualTo(2);
        assertThat(view.topic()).isEqualTo(new TopicView("Java", "Concurrency"));
        assertThat(view.answerFormat()).isEqualTo(AnswerFormat.TEXT);
    }

    @Test
    void aCodingQuestionIsAnsweredWithCodeAndShowsItsMaterial() {
        QuestionDetails details = new QuestionDetails(
                List.of("1 <= n <= 10^5", "values fit in int"),
                List.of(new CodingExample("[1,2,3]", "6", "1+2+3"), new CodingExample("[]", "0", null)),
                "int sum(int[] values)",
                "java");

        QuestionResponse view = QuestionViewMapper.toView(coding(details), false, CONTEXT);

        assertThat(view.answerFormat()).isEqualTo(AnswerFormat.CODE);
        assertThat(view.suggestedMinutes()).isEqualTo(16);
        assertThat(view.details()).isEqualTo(new QuestionDetailsResponse(
                "java",
                List.of("1 <= n <= 10^5", "values fit in int"),
                List.of(new CodingExampleView("[1,2,3]", "6", "1+2+3"), new CodingExampleView("[]", "0", null)),
                "int sum(int[] values)",
                null,
                false));
    }

    @Test
    void aCodingQuestionWithoutALanguageUsesTheLanguageOfTheInterview() {
        QuestionDetails details = new QuestionDetails(List.of(), List.of(), null, null);

        assertThat(QuestionViewMapper.toView(coding(details), false, CONTEXT).details().language()).isEqualTo("python");
    }

    @Test
    void aCodingQuestionWithoutStoredDetailsStillHasEmptyDetailsInTheInterviewLanguage() {
        QuestionResponse view = QuestionViewMapper.toView(coding(null), false, CONTEXT);

        assertThat(view.details()).isEqualTo(new QuestionDetailsResponse("python", List.of(), List.of(), null, null, false));
    }

    @Test
    void aFollowUpOfACodingQuestionIsAnsweredWithCodeButCarriesNoDetails() {
        InterviewQuestion root = coding(null);
        InterviewQuestion followUp = InterviewQuestion.followUp(
                INTERVIEW, 6, Section.CODING, null, 5, root.getId(), 1, content("What is the complexity?", null), T0);

        QuestionResponse view = QuestionViewMapper.toView(followUp, false, CONTEXT);

        assertThat(view.answerFormat()).isEqualTo(AnswerFormat.CODE);
        assertThat(view.details()).isNull();
        assertThat(view.suggestedMinutes()).isEqualTo(8);
    }

    @Test
    void aSystemDesignQuestionAndItsFollowUpAreText() {
        InterviewQuestion design = InterviewQuestion.root(INTERVIEW, 7, QuestionKind.SYSTEM_DESIGN, null, 4, content("Design a cache.", null), T0);
        InterviewQuestion followUp = InterviewQuestion.followUp(
                INTERVIEW, 8, Section.SYSTEM_DESIGN, null, 4, design.getId(), 1, content("How does it scale?", null), T0);

        assertThat(QuestionViewMapper.toView(design, false, CONTEXT).answerFormat()).isEqualTo(AnswerFormat.TEXT);
        assertThat(QuestionViewMapper.toView(design, false, CONTEXT).details()).isNull();
        assertThat(QuestionViewMapper.toView(followUp, false, CONTEXT).answerFormat()).isEqualTo(AnswerFormat.TEXT);
    }

    @Test
    void aBankDesignProblemShowsItsTitleAndFactsAndAProbeShowsItsDimension() {
        InterviewQuestion problem = designProblem();
        InterviewQuestion probe = InterviewQuestion.followUp(INTERVIEW, 8, Section.SYSTEM_DESIGN, null, 3, problem.getId(), 1,
                content("What happens if a link goes viral?", null).probing(Criterion.SCALABILITY), T0);
        QuestionViewMapper.Context context = new QuestionViewMapper.Context(Map.of(7L, CONCURRENCY), Fixtures.plan(), null, Map.of(), false,
                Map.of("url-shortener", "Design a URL shortener"));

        assertThat(QuestionViewMapper.toView(problem, false, context).design())
                .isEqualTo(new DesignDetailsResponse("Design a URL shortener", List.of("100 million new links per month"), null));
        assertThat(QuestionViewMapper.toView(probe, false, context).design())
                .isEqualTo(new DesignDetailsResponse(null, List.of(), Criterion.SCALABILITY));
        assertThat(QuestionViewMapper.toView(technical(), false, context).design()).as("only design questions").isNull();
    }

    private static InterviewQuestion designProblem() {
        return InterviewQuestion.root(INTERVIEW, 7, QuestionKind.SYSTEM_DESIGN, null, 3, QuestionContent.designProblem("Design a URL shortener.",
                new QuestionDetails(List.of("100 million new links per month"), List.of(), null, null),
                List.of(new ExpectedKeyPoint("kp-secret", "the secret key point text", List.of())), "rubric-secret.v2", "url-shortener"), T0);
    }

    @Test
    void aTopicThatWasNotLoadedIsABug() {
        QuestionViewMapper.Context withoutTopics = new QuestionViewMapper.Context(Map.of(), Fixtures.plan(), null);

        assertThatThrownBy(() -> QuestionViewMapper.toView(technical(), false, withoutTopics))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("topic 7");
    }

    @Test
    void theTopicIdsToLoadAreThoseOfTheQuestionsWithATopic() {
        List<InterviewQuestion> questions = List.of(technical(), coding(null), technical());

        assertThat(QuestionViewMapper.topicIds(questions)).containsExactly(7L);
    }

    @Test
    void theJsonOfAViewNeverContainsAnythingServerSide() {
        QuestionDetails details = new QuestionDetails(List.of("c"), List.of(new CodingExample("i", "o", null)), "sig", "java");
        List<InterviewQuestion> questions = List.of(technical(), coding(details), designProblem());

        for (InterviewQuestion question : questions) {
            JsonNode tree = TestJson.applicationMapper().valueToTree(QuestionViewMapper.toView(question, true, CONTEXT));
            String text = tree.toString();

            assertThat(text).doesNotContain("secret", "kp-", "rubric", "expected", "difficulty", "GENERATED", "origin", "STATIC", "url-shortener");
            assertThat(tree.propertyNames()).containsExactlyInAnyOrder(
                    "id", "sequenceNo", "kind", "isFollowUp", "section", "topic", "prompt", "answerFormat", "suggestedMinutes", "details", "answered",
                    "design", "resume", "questionType");
        }
    }
}
