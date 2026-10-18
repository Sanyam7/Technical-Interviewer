package com.techinterviewer.interview.service;

import com.techinterviewer.catalog.TopicInfo;
import com.techinterviewer.interview.api.dto.AnswerFormat;
import com.techinterviewer.interview.api.dto.CodingExampleView;
import com.techinterviewer.interview.api.dto.DesignDetailsResponse;
import com.techinterviewer.interview.api.dto.QuestionDetailsResponse;
import com.techinterviewer.interview.api.dto.QuestionResponse;
import com.techinterviewer.interview.api.dto.ResumeReferenceView;
import com.techinterviewer.interview.api.dto.TopicView;
import com.techinterviewer.interview.domain.InterviewPlan;
import com.techinterviewer.interview.domain.InterviewQuestion;
import com.techinterviewer.interview.domain.QuestionDetails;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.Section;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Maps a question to what a candidate may see: the wording, the topic, how to answer and how long to spend, plus the
 * material of a coding or design problem. The expected key points, the rubric, the difficulty and the origin of a question are
 * not part of the view and cannot leak through it, because this mapper never reads them.
 */
public final class QuestionViewMapper {

    private QuestionViewMapper() {
    }

    /**
     * What the mapper needs besides the question itself.
     *
     * @param topics         the topics of the questions to map, by id (every topic id of a mapped question must be in it)
     * @param plan           the plan of the interview, for the suggested time
     * @param codingLanguage the language of the coding round, shown when a coding question names none; absent without a coding round
     * @param problemTitles  titles of the curated coding problems, by problem id
     * @param testable       whether solutions in the interview's language are run against tests here
     * @param designTitles   titles of the curated system design problems, by problem id
     * @param resumeExperiences the experiences of the interview's résumé that questions are anchored on, by id; empty without one
     */
    public record Context(Map<Long, TopicInfo> topics, InterviewPlan plan, @Nullable String codingLanguage, Map<String, String> problemTitles,
            boolean testable, Map<String, String> designTitles, Map<String, ResumeReferenceView> resumeExperiences) {

        /** A context without a résumé. */
        public Context(Map<Long, TopicInfo> topics, InterviewPlan plan, @Nullable String codingLanguage, Map<String, String> problemTitles,
                boolean testable, Map<String, String> designTitles) {
            this(topics, plan, codingLanguage, problemTitles, testable, designTitles, Map.of());
        }

        /** A context without design problems. */
        public Context(Map<Long, TopicInfo> topics, InterviewPlan plan, @Nullable String codingLanguage, Map<String, String> problemTitles,
                boolean testable) {
            this(topics, plan, codingLanguage, problemTitles, testable, Map.of());
        }

        /** A context without coding problems. */
        public Context(Map<Long, TopicInfo> topics, InterviewPlan plan, @Nullable String codingLanguage) {
            this(topics, plan, codingLanguage, Map.of(), false);
        }
    }

    /** The topic ids of the given questions, which {@link Context#topics()} must cover. */
    public static List<Long> topicIds(List<InterviewQuestion> questions) {
        return questions.stream().map(InterviewQuestion::getTopicId).filter(Objects::nonNull).distinct().toList();
    }

    public static QuestionResponse toView(InterviewQuestion question, boolean answered, Context context) {
        return new QuestionResponse(
                question.getId(),
                question.getSequenceNo(),
                question.getKind(),
                question.isFollowUp(),
                question.getSection(),
                topicOf(question, context),
                question.getPrompt(),
                answerFormat(question),
                SuggestedTime.minutes(question.getKind(), question.getSection(), context.plan()),
                question.getKind() == QuestionKind.CODING ? detailsOf(question, context) : null,
                answered,
                question.getSection() == Section.SYSTEM_DESIGN ? designOf(question, context) : null,
                question.getResumeItemId() == null ? null : context.resumeExperiences().get(question.getResumeItemId()),
                question.getQuestionType());
    }

    /** The title and facts of a design problem, or the dimension a follow-up of the design thread probes. */
    private static DesignDetailsResponse designOf(InterviewQuestion question, Context context) {
        if (question.isFollowUp()) {
            return new DesignDetailsResponse(null, List.of(), question.getProbeCriterion());
        }
        String problem = question.getDesignProblemId();
        QuestionDetails details = question.getDetails();
        return new DesignDetailsResponse(problem == null ? null : context.designTitles().get(problem),
                details == null ? List.of() : details.constraints(), null);
    }

    private static @Nullable TopicView topicOf(InterviewQuestion question, Context context) {
        Long topicId = question.getTopicId();
        if (topicId == null) {
            return null;
        }
        TopicInfo topic = context.topics().get(topicId);
        if (topic == null) {
            throw new IllegalStateException("topic " + topicId + " of question " + question.getId() + " was not loaded");
        }
        return new TopicView(topic.skillName(), topic.name());
    }

    /** Coding questions and the follow-ups of the coding section are answered with code. */
    public static AnswerFormat answerFormat(InterviewQuestion question) {
        boolean code = question.getKind() == QuestionKind.CODING
                || (question.getKind() == QuestionKind.FOLLOW_UP && question.getSection() == Section.CODING);
        return code ? AnswerFormat.CODE : AnswerFormat.TEXT;
    }

    private static QuestionDetailsResponse detailsOf(InterviewQuestion question, Context context) {
        QuestionDetails details = question.getDetails();
        String codingLanguage = context.codingLanguage();
        String problem = question.getCodingProblemId();
        String title = problem == null ? null : context.problemTitles().get(problem);
        boolean testable = problem != null && context.testable();
        if (details == null) {
            return new QuestionDetailsResponse(codingLanguage, List.of(), List.of(), null, title, testable);
        }
        return new QuestionDetailsResponse(
                details.language() != null ? details.language() : codingLanguage,
                details.constraints(),
                details.examples().stream()
                        .map(example -> new CodingExampleView(example.input(), example.output(), example.explanation()))
                        .toList(),
                details.starterSignature(),
                title,
                testable);
    }
}
