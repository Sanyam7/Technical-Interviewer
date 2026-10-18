package com.techinterviewer.evaluation.support;

import com.techinterviewer.interview.domain.CandidateAnswer;
import com.techinterviewer.interview.domain.ExpectedKeyPoint;
import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.Interview;
import com.techinterviewer.interview.domain.InterviewConfiguration;
import com.techinterviewer.interview.domain.InterviewDifficulty;
import com.techinterviewer.interview.domain.InterviewEvent;
import com.techinterviewer.interview.domain.InterviewQuestion;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.interview.domain.InterviewStateTransition;
import com.techinterviewer.interview.domain.InterviewType;
import com.techinterviewer.interview.domain.QuestionContent;
import com.techinterviewer.interview.domain.QuestionDetails;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.QuestionOrigin;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.TargetRole;
import com.techinterviewer.interview.repository.CandidateAnswerRepository;
import com.techinterviewer.interview.repository.InterviewConfigurationRepository;
import com.techinterviewer.interview.repository.InterviewQuestionRepository;
import com.techinterviewer.interview.repository.InterviewRepository;
import com.techinterviewer.interview.repository.InterviewStateTransitionRepository;
import com.techinterviewer.persistence.Fixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes an interview with its questions and answers into the database, the way the interview use cases would have left
 * it: the transition log is complete, the interview ran for 43 minutes and ends in the requested state. Every question
 * has two expected key points whose texts contain {@code EXPECTED-KP}, so a test can prove they never reach a response.
 */
public final class ScenarioBuilder {

    public static final String SECRET_MARKER = "EXPECTED-KP";
    public static final Instant START = Fixtures.NOW;
    public static final Duration RUNTIME = Duration.ofMinutes(43);

    /** A question and the answer row written for it (a skipped question has one too; absent when it was never answered). */
    public record Asked(UUID questionId, @Nullable UUID answerId, int sequenceNo, List<ExpectedKeyPoint> expectedKeyPoints) {
    }

    /** What was written. */
    public record Built(UUID userId, UUID interviewId, UUID configurationId) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final InterviewRepository interviews;
    private final InterviewConfigurationRepository configurations;
    private final InterviewQuestionRepository questions;
    private final CandidateAnswerRepository answers;
    private final InterviewStateTransitionRepository transitions;
    private final UUID userId;
    private final InterviewConfiguration configuration;
    private final Interview interview;
    private final UUID interviewId;
    private final List<InterviewQuestion> questionRows = new ArrayList<>();
    private final List<CandidateAnswer> answerRows = new ArrayList<>();

    public ScenarioBuilder(
            UUID userId,
            JdbcTemplate jdbc,
            TransactionTemplate transaction,
            InterviewRepository interviews,
            InterviewConfigurationRepository configurations,
            InterviewQuestionRepository questions,
            CandidateAnswerRepository answers,
            InterviewStateTransitionRepository transitions) {
        this.userId = userId;
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.interviews = interviews;
        this.configurations = configurations;
        this.questions = questions;
        this.answers = answers;
        this.transitions = transitions;
        this.configuration = InterviewConfiguration.create(userId, TargetRole.BACKEND, ExperienceLevel.SENIOR,
                InterviewDifficulty.HARD, 60, InterviewType.FULL_LOOP, "java", false,
                Set.copyOf(jdbc.queryForList("select id from skills order by id limit 2", Long.class)), START);
        this.interview = Interview.create(userId, configuration.getId(), Fixtures.plan(), START);
        this.interviewId = interview.getId();
    }

    public UUID interviewId() {
        return interviewId;
    }

    /** The introduction (sequence number 1; a null {@code answer} skips it). */
    public Asked intro(@Nullable String answer) {
        InterviewQuestion question = InterviewQuestion.root(interviewId, next(), QuestionKind.INTRO, null, 1,
                Fixtures.staticContent(), START);
        return add(question, answer, null);
    }

    /** A technical question on a {@code skill/topic} of the seeded catalog; null {@code answer} skips it. */
    public Asked technical(String topic, int difficulty, @Nullable String answer) {
        int sequenceNo = next();
        InterviewQuestion question = InterviewQuestion.root(interviewId, sequenceNo, QuestionKind.TECHNICAL, topicId(topic), difficulty,
                content("technical.v1", sequenceNo, null), START);
        return add(question, answer, null);
    }

    /** A follow-up of {@code parent} in the section of its thread; null {@code topic} for coding and design threads. */
    public Asked followUp(Asked parent, Section section, @Nullable String topic, int difficulty, @Nullable String answer) {
        int sequenceNo = next();
        int depth = depthOf(parent) + 1;
        String rubric = switch (section) {
            case CODING -> "coding.v1";
            case SYSTEM_DESIGN -> "system-design.v1";
            default -> "technical.v1";
        };
        InterviewQuestion question = InterviewQuestion.followUp(interviewId, sequenceNo, section, topic == null ? null : topicId(topic),
                difficulty, parent.questionId(), depth, content(rubric, sequenceNo, null), START);
        return add(question, answer, section == Section.CODING ? "java" : null);
    }

    public Asked coding(int difficulty, @Nullable String answer) {
        int sequenceNo = next();
        QuestionDetails details = new QuestionDetails(List.of("n <= 10"), List.of(), "int solve(int n)", "java");
        InterviewQuestion question = InterviewQuestion.root(interviewId, sequenceNo, QuestionKind.CODING, null, difficulty,
                content("coding.v1", sequenceNo, details), START);
        return add(question, answer, "java");
    }

    /** A coding problem that (against the convention) carries a catalog topic: the report must not count it as skill evidence. */
    public Asked codingOnTopic(String topic, int difficulty, @Nullable String answer) {
        int sequenceNo = next();
        QuestionDetails details = new QuestionDetails(List.of("n <= 10"), List.of(), "int solve(int n)", "java");
        InterviewQuestion question = InterviewQuestion.root(interviewId, sequenceNo, QuestionKind.CODING, topicId(topic), difficulty,
                content("coding.v1", sequenceNo, details), START);
        return add(question, answer, "java");
    }

    public Asked design(int difficulty, @Nullable String answer) {
        int sequenceNo = next();
        InterviewQuestion question = InterviewQuestion.root(interviewId, sequenceNo, QuestionKind.SYSTEM_DESIGN, null, difficulty,
                content("system-design.v1", sequenceNo, null), START);
        return add(question, answer, null);
    }

    /** Writes everything and leaves the interview in FINAL_EVALUATION (it ended by the candidate asking to stop). */
    public Built buildFinished() {
        return build(InterviewState.FINAL_EVALUATION);
    }

    /** Writes everything; the interview ends in {@code state}: INIT, QUESTIONING, FINAL_EVALUATION or ABANDONED. */
    public Built build(InterviewState state) {
        List<InterviewStateTransition> log = new ArrayList<>();
        log.add(InterviewStateTransition.creation(interviewId, START));
        advance(interview, state, log);
        transaction.executeWithoutResult(status -> {
            configurations.saveAndFlush(configuration);
            interviews.saveAndFlush(interview);
            transitions.saveAllAndFlush(log);
            questionRows.forEach(questions::saveAndFlush);
            answerRows.forEach(answers::saveAndFlush);
        });
        return new Built(userId, interviewId, configuration.getId());
    }

    private void advance(Interview interview, InterviewState target, List<InterviewStateTransition> log) {
        if (target == InterviewState.INIT) {
            return;
        }
        if (target == InterviewState.ABANDONED) {
            log.add(interview.applyTransition(InterviewState.INIT, InterviewState.ABANDONED, InterviewEvent.ABANDON, START));
            return;
        }
        interview.start(START, 60);
        log.add(interview.applyTransition(InterviewState.INIT, InterviewState.INTRODUCTION, InterviewEvent.START, START));
        log.add(interview.applyTransition(InterviewState.INTRODUCTION, InterviewState.QUESTIONING, InterviewEvent.INTRO_ANSWERED, START));
        if (target == InterviewState.FINAL_EVALUATION) {
            log.add(interview.applyTransition(InterviewState.QUESTIONING, InterviewState.FINAL_EVALUATION, InterviewEvent.END_REQUESTED,
                    START.plus(RUNTIME)));
        }
    }

    private int next() {
        return questionRows.size() + 1;
    }

    private Asked add(InterviewQuestion question, @Nullable String answer, @Nullable String codeLanguage) {
        questionRows.add(question);
        CandidateAnswer row = answer == null
                ? CandidateAnswer.skip(question.getId(), interviewId, 5, START)
                : CandidateAnswer.submit(question.getId(), interviewId, answer, codeLanguage, 30, START);
        answerRows.add(row);
        return new Asked(question.getId(), row.getId(), question.getSequenceNo(), question.getExpectedKeyPoints());
    }

    /** A technical question that was asked and never answered (the interview ended while it was current). */
    public Asked technicalUnanswered(String topic, int difficulty) {
        int sequenceNo = next();
        InterviewQuestion question = InterviewQuestion.root(interviewId, sequenceNo, QuestionKind.TECHNICAL, topicId(topic), difficulty,
                content("technical.v1", sequenceNo, null), START);
        questionRows.add(question);
        return new Asked(question.getId(), null, sequenceNo, question.getExpectedKeyPoints());
    }

    private int depthOf(Asked parent) {
        return questionRows.stream().filter(q -> q.getId().equals(parent.questionId())).findFirst().orElseThrow().getFollowUpDepth();
    }

    private long topicId(String key) {
        String[] parts = key.split("/");
        return jdbc.queryForObject(
                "select t.id from topics t join skills s on s.id = t.skill_id where s.slug = ? and t.slug = ?", Long.class, parts[0], parts[1]);
    }

    private static QuestionContent content(String rubricId, int sequenceNo, @Nullable QuestionDetails details) {
        return new QuestionContent("Question number " + sequenceNo + " asks about something.", details,
                List.of(new ExpectedKeyPoint("kp1", SECRET_MARKER + "-" + sequenceNo + "-1", List.of()),
                        new ExpectedKeyPoint("kp2", SECRET_MARKER + "-" + sequenceNo + "-2", List.of())),
                rubricId, QuestionOrigin.GENERATED, null);
    }
}
