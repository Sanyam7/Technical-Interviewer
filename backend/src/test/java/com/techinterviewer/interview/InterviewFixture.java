package com.techinterviewer.interview;

import com.techinterviewer.common.id.UuidV7;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.CriterionScore;
import com.techinterviewer.evaluation.domain.Evaluation;
import com.techinterviewer.evaluation.domain.EvaluationFindings;
import com.techinterviewer.evaluation.domain.Evaluator;
import com.techinterviewer.evaluation.domain.KeyPointAssessment;
import com.techinterviewer.evaluation.domain.KeyPointStatus;
import com.techinterviewer.evaluation.domain.ModelProvenance;
import com.techinterviewer.evaluation.repository.EvaluationRepository;
import com.techinterviewer.interview.domain.CandidateAnswer;
import com.techinterviewer.interview.domain.CodingExample;
import com.techinterviewer.interview.domain.ExpectedKeyPoint;
import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.Interview;
import com.techinterviewer.interview.domain.InterviewConfiguration;
import com.techinterviewer.interview.domain.InterviewDifficulty;
import com.techinterviewer.interview.domain.InterviewMemory;
import com.techinterviewer.interview.domain.InterviewEvent;
import com.techinterviewer.interview.domain.InterviewPlan;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Rows for the interview API tests that the API itself cannot produce yet (answered questions, evaluations, interviews in
 * the middle or at the end of the question phase), written through the entities and repositories exactly as the later
 * answer flow will. Users are inserted with SQL (the application has no way to create one without a password hash) and
 * removed by {@link #cleanUp()}, which removes everything that belongs to them through the foreign key cascades.
 */
public final class InterviewFixture {

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final InterviewRepository interviews;
    private final InterviewConfigurationRepository configurations;
    private final InterviewQuestionRepository questions;
    private final CandidateAnswerRepository answers;
    private final EvaluationRepository evaluations;
    private final InterviewStateTransitionRepository transitions;
    private final List<UUID> users = new ArrayList<>();

    public InterviewFixture(
            JdbcClient jdbc,
            TransactionTemplate transaction,
            InterviewRepository interviews,
            InterviewConfigurationRepository configurations,
            InterviewQuestionRepository questions,
            CandidateAnswerRepository answers,
            EvaluationRepository evaluations,
            InterviewStateTransitionRepository transitions) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.interviews = interviews;
        this.configurations = configurations;
        this.questions = questions;
        this.answers = answers;
        this.evaluations = evaluations;
        this.transitions = transitions;
    }

    public UUID user() {
        UUID id = UuidV7.generate();
        jdbc.sql("""
                        INSERT INTO users (id, email, password_hash, display_name, role)
                        VALUES (:id, :email, '{noop}not-a-real-hash', 'Test User', 'CANDIDATE')""")
                .param("id", id)
                .param("email", "interview-" + id + "@example.com")
                .update();
        users.add(id);
        return id;
    }

    public void cleanUp() {
        users.forEach(id -> jdbc.sql("DELETE FROM users WHERE id = :id").param("id", id).update());
        users.clear();
    }

    public long skillId(String slug) {
        return jdbc.sql("SELECT id FROM skills WHERE slug = :slug").param("slug", slug).query(Long.class).single();
    }

    public long topicId(String skillSlug, String topicSlug) {
        return jdbc.sql("""
                        SELECT t.id FROM topics t JOIN skills s ON s.id = t.skill_id
                        WHERE s.slug = :skill AND t.slug = :topic""")
                .param("skill", skillSlug)
                .param("topic", topicSlug)
                .query(Long.class)
                .single();
    }

    /** A FULL_LOOP configuration in Java, 60 minutes, owned by {@code owner}. */
    public InterviewConfiguration configuration(UUID owner, Instant now) {
        return configuration(owner, InterviewType.FULL_LOOP, "java", now, "java", "spring-boot");
    }

    public InterviewConfiguration configuration(UUID owner, InterviewType type, String codingLanguage, Instant now, String... skills) {
        List<Long> skillIds = new ArrayList<>();
        for (String slug : skills) {
            skillIds.add(skillId(slug));
        }
        InterviewConfiguration configuration = InterviewConfiguration.create(
                owner, TargetRole.BACKEND, ExperienceLevel.MID, InterviewDifficulty.MEDIUM, 60, type, codingLanguage, false, skillIds, now);
        return transaction.execute(status -> configurations.saveAndFlush(configuration));
    }

    /** A saved interview in INIT with the first row of its log, created from a fresh FULL_LOOP configuration of {@code owner}. */
    public Interview created(UUID owner, Instant now) {
        return created(owner, configuration(owner, now), Fixtures.plan(), now);
    }

    public Interview created(UUID owner, InterviewConfiguration configuration, InterviewPlan plan, Instant now) {
        return transaction.execute(status -> {
            Interview interview = interviews.saveAndFlush(Interview.create(owner, configuration.getId(), plan, now));
            transitions.saveAndFlush(InterviewStateTransition.creation(interview.getId(), now));
            return interview;
        });
    }

    /**
     * An interview in INTRODUCTION whose introduction question is current: started at {@code now} (the configuration's
     * duration, 60 minutes, gives the deadline), as the start endpoint leaves it.
     */
    public Interview started(UUID owner, Instant now) {
        Interview interview = created(owner, now);
        transaction.executeWithoutResult(status -> {
            Interview managed = interviews.findById(interview.getId()).orElseThrow();
            managed.start(now, 60);
            transitions.save(managed.applyTransition(InterviewState.INIT, InterviewState.INTRODUCTION, InterviewEvent.START, now));
            InterviewQuestion intro = questions.saveAndFlush(InterviewQuestion.root(
                    managed.getId(), 1, QuestionKind.INTRO, null, 3, Fixtures.staticContent(), now));
            managed.askQuestion(intro.getId(), now);
            interviews.saveAndFlush(managed);
        });
        return reload(interview.getId());
    }

    public Interview reload(UUID interviewId) {
        return transaction.execute(status -> interviews.findById(interviewId).orElseThrow());
    }

    /** Moves the interview (which must have been started) one step along the machine, saving the log row. */
    public Interview move(UUID interviewId, InterviewState to, InterviewEvent event, Instant at) {
        transaction.executeWithoutResult(status -> {
            Interview managed = interviews.findById(interviewId).orElseThrow();
            transitions.save(managed.applyTransition(managed.getState(), to, event, at));
            interviews.saveAndFlush(managed);
        });
        return reload(interviewId);
    }

    /**
     * An interview of {@code owner} brought to {@code target} along the shortest legal path of the state machine, every
     * move at {@code now}: INIT, INTRODUCTION (started, deadline 60 minutes after {@code now}), QUESTIONING, FOLLOW_UP,
     * CODING, SYSTEM_DESIGN, FINAL_EVALUATION (time up), COMPLETED or ABANDONED (from INIT). Questions are not asked
     * beyond the introduction.
     */
    public Interview inState(UUID owner, InterviewState target, Instant now) {
        if (target == InterviewState.INIT) {
            return created(owner, now);
        }
        if (target == InterviewState.ABANDONED) {
            Interview interview = created(owner, now);
            return move(interview.getId(), InterviewState.ABANDONED, InterviewEvent.ABANDON, now);
        }
        Interview interview = started(owner, now);
        UUID id = interview.getId();
        switch (target) {
            case INTRODUCTION -> {
            }
            case QUESTIONING -> move(id, InterviewState.QUESTIONING, InterviewEvent.INTRO_ANSWERED, now);
            case FOLLOW_UP -> {
                move(id, InterviewState.QUESTIONING, InterviewEvent.INTRO_ANSWERED, now);
                move(id, InterviewState.FOLLOW_UP, InterviewEvent.ANSWER_EVALUATED, now);
            }
            case CODING -> {
                move(id, InterviewState.QUESTIONING, InterviewEvent.INTRO_ANSWERED, now);
                move(id, InterviewState.CODING, InterviewEvent.SECTION_COMPLETE, now);
            }
            case SYSTEM_DESIGN -> {
                move(id, InterviewState.QUESTIONING, InterviewEvent.INTRO_ANSWERED, now);
                move(id, InterviewState.SYSTEM_DESIGN, InterviewEvent.SECTION_COMPLETE, now);
            }
            case FINAL_EVALUATION -> move(id, InterviewState.FINAL_EVALUATION, InterviewEvent.END_REQUESTED, now);
            case COMPLETED -> {
                move(id, InterviewState.FINAL_EVALUATION, InterviewEvent.END_REQUESTED, now);
                move(id, InterviewState.COMPLETED, InterviewEvent.REPORT_READY, now);
            }
            default -> throw new IllegalArgumentException("no path to " + target);
        }
        return reload(id);
    }

    /** Replaces the memory of the interview. */
    public void setMemory(UUID interviewId, InterviewMemory memory, Instant at) {
        transaction.executeWithoutResult(status -> {
            Interview managed = interviews.findById(interviewId).orElseThrow();
            managed.replaceMemory(memory, at);
            interviews.saveAndFlush(managed);
        });
    }

    /** Forgets the open question without any other change (the state after a question could not be generated). */
    public void clearCurrentQuestion(UUID interviewId, Instant at) {
        transaction.executeWithoutResult(status -> {
            Interview managed = interviews.findById(interviewId).orElseThrow();
            managed.clearCurrentQuestion(at);
            interviews.saveAndFlush(managed);
        });
    }

    /** Asks a new technical question as the next one (sequence number after the last) and makes it current. */
    public InterviewQuestion askTechnical(UUID interviewId, String skillSlug, String topicSlug, Instant at) {
        return ask(interviewId, QuestionKind.TECHNICAL, topicId(skillSlug, topicSlug), technicalContent(), at);
    }

    public InterviewQuestion askCoding(UUID interviewId, Instant at) {
        QuestionContent content = new QuestionContent(
                "Write a function that returns the sum of an array.",
                new QuestionDetails(List.of("1 <= n <= 10^5"), List.of(new CodingExample("[1,2,3]", "6", "1+2+3")), "int sum(int[] values)", "java"),
                List.of(new ExpectedKeyPoint("kp-secret", "handles overflow with long", List.of())),
                "coding.v1",
                QuestionOrigin.GENERATED,
                null);
        return ask(interviewId, QuestionKind.CODING, null, content, at);
    }

    public InterviewQuestion ask(UUID interviewId, QuestionKind kind, Long topicId, QuestionContent content, Instant at) {
        return transaction.execute(status -> {
            int next = questions.findLatestSequenceNo(interviewId) + 1;
            InterviewQuestion question = questions.saveAndFlush(InterviewQuestion.root(interviewId, next, kind, topicId, 4, content, at));
            Interview managed = interviews.findById(interviewId).orElseThrow();
            managed.askQuestion(question.getId(), at);
            interviews.saveAndFlush(managed);
            return question;
        });
    }

    /** A follow-up of {@code parent}, asked as the next question. */
    public InterviewQuestion askFollowUp(UUID interviewId, InterviewQuestion parent, Section section, Instant at) {
        return transaction.execute(status -> {
            int next = questions.findLatestSequenceNo(interviewId) + 1;
            InterviewQuestion question = questions.saveAndFlush(InterviewQuestion.followUp(
                    interviewId, next, section, parent.getTopicId(), 4, parent.getId(), 1, technicalContent(), at));
            Interview managed = interviews.findById(interviewId).orElseThrow();
            managed.askQuestion(question.getId(), at);
            interviews.saveAndFlush(managed);
            return question;
        });
    }

    public CandidateAnswer answer(InterviewQuestion question, String content, Instant at) {
        return transaction.execute(status -> answers.saveAndFlush(
                CandidateAnswer.submit(question.getId(), question.getInterviewId(), content, null, 60, at)));
    }

    public CandidateAnswer skip(InterviewQuestion question, Instant at) {
        return transaction.execute(status -> answers.saveAndFlush(
                CandidateAnswer.skip(question.getId(), question.getInterviewId(), 5, at)));
    }

    /** Stores a completed evaluation of the answer to a question made by {@link #askTechnical}: kp1 covered, kp2 missing. */
    public Evaluation evaluate(CandidateAnswer answer, Instant at) {
        return transaction.execute(status -> {
            CandidateAnswer managed = answers.findById(answer.getId()).orElseThrow();
            managed.markEvaluated();
            answers.saveAndFlush(managed);
            EvaluationFindings findings = new EvaluationFindings(
                    List.of(new CriterionScore(Criterion.CORRECTNESS, 3, 0.4, "Mostly right, secret-rationale."),
                            new CriterionScore(Criterion.DEPTH, 2, 0.6, "")),
                    List.of(new KeyPointAssessment("kp1", KeyPointStatus.COVERED, "volatile guarantees visibility", true),
                            new KeyPointAssessment("kp2", KeyPointStatus.MISSING, null, false)),
                    List.of(),
                    List.of(),
                    "Solid on visibility, missed mutual exclusion.",
                    Set.of());
            return evaluations.saveAndFlush(Evaluation.create(
                    answer.getId(), Evaluator.TECHNICAL, new BigDecimal("57.50"), findings,
                    new ModelProvenance("evaluate-technical", "1.0", "test-model", null), at));
        });
    }

    public static QuestionContent technicalContent() {
        return new QuestionContent(
                "Explain how volatile differs from synchronized.",
                null,
                List.of(new ExpectedKeyPoint("kp1", "volatile guarantees visibility", List.of()),
                        new ExpectedKeyPoint("kp2", "synchronized adds mutual exclusion", List.of())),
                "technical.v1",
                QuestionOrigin.GENERATED,
                null);
    }
}
