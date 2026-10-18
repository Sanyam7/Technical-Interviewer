package com.techinterviewer.interview.domain;

import com.techinterviewer.common.persistence.BaseEntity;
import com.techinterviewer.common.validation.Require;
import com.techinterviewer.evaluation.domain.Criterion;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * A question that was asked in an interview (table {@code questions}). Questions are written once, when they are
 * generated, and never change. The columns owned by the AI layer (the embedding and its model) are not mapped.
 *
 * <p>Shape rules, checked at creation (the schema enforces only part of them):
 * <ul>
 *   <li>a <i>root</i> question has kind INTRO, TECHNICAL, CODING or SYSTEM_DESIGN, no parent and follow-up depth 0;
 *       its section follows from its kind (INTRO is the INTRODUCTION section, TECHNICAL the QUESTIONING section);
 *   <li>a <i>follow-up</i> has kind FOLLOW_UP, a parent and a depth from 1 to 2 (to 6 in a design thread, whose follow-ups
 *       probe one design dimension each), and lives in a section after the introduction;
 *   <li>the difficulty is 1 to 5 and the sequence number starts at 1.
 * </ul>
 * That the parent belongs to the same interview is checked by the service that has both questions at hand.
 */
@Entity
@Table(name = "questions")
public class InterviewQuestion extends BaseEntity {

    public static final int MAX_FOLLOW_UP_DEPTH = 2;

    @Column(name = "interview_id", nullable = false, updatable = false)
    private UUID interviewId;

    @Column(name = "parent_question_id", updatable = false)
    private @Nullable UUID parentQuestionId;

    @Column(name = "sequence_no", nullable = false, updatable = false)
    private int sequenceNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "section", nullable = false, updatable = false, length = 20)
    private Section section;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false, length = 20)
    private QuestionKind kind;

    @Column(name = "topic_id", updatable = false)
    private @Nullable Long topicId;

    @Column(name = "difficulty", nullable = false, updatable = false)
    @JdbcTypeCode(SqlTypes.SMALLINT)
    private int difficulty;

    @Column(name = "followup_depth", nullable = false, updatable = false)
    @JdbcTypeCode(SqlTypes.SMALLINT)
    private int followUpDepth;

    @Column(name = "prompt", nullable = false, updatable = false, columnDefinition = "text")
    private String prompt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", columnDefinition = "jsonb", updatable = false)
    private @Nullable QuestionDetails details;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "expected_key_points", columnDefinition = "jsonb", nullable = false, updatable = false)
    private List<ExpectedKeyPoint> expectedKeyPoints;

    @Column(name = "rubric_id", nullable = false, updatable = false, length = 40)
    private String rubricId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false, length = 20)
    private QuestionOrigin origin;

    @Column(name = "generation_llm_call_id", updatable = false)
    private @Nullable UUID generationLlmCallId;

    @Column(name = "coding_problem_id", updatable = false, length = 60)
    private @Nullable String codingProblemId;

    @Column(name = "design_problem_id", updatable = false, length = 60)
    private @Nullable String designProblemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "probe_criterion", updatable = false, length = 30)
    private @Nullable Criterion probeCriterion;

    @Column(name = "resume_item_id", updatable = false, length = 10)
    private @Nullable String resumeItemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "question_type", updatable = false, length = 12)
    private @Nullable QuestionType questionType;

    @Column(name = "template_id", updatable = false, length = QuestionContent.MAX_TEMPLATE_ID_LENGTH)
    private @Nullable String templateId;

    @Column(name = "asked_at", nullable = false, updatable = false)
    private Instant askedAt;

    protected InterviewQuestion() {
    }

    private InterviewQuestion(
            UUID interviewId,
            int sequenceNo,
            Section section,
            QuestionKind kind,
            @Nullable Long topicId,
            int difficulty,
            @Nullable UUID parentQuestionId,
            int followUpDepth,
            QuestionContent content,
            Instant askedAt) {
        this.interviewId = interviewId;
        this.sequenceNo = sequenceNo;
        this.section = section;
        this.kind = kind;
        this.topicId = topicId;
        this.difficulty = difficulty;
        this.parentQuestionId = parentQuestionId;
        this.followUpDepth = followUpDepth;
        this.prompt = content.prompt();
        this.details = content.details();
        this.expectedKeyPoints = content.expectedKeyPoints();
        this.rubricId = content.rubricId();
        this.origin = content.origin();
        this.generationLlmCallId = content.generationLlmCallId();
        this.codingProblemId = content.codingProblemId();
        this.designProblemId = content.designProblemId();
        this.probeCriterion = content.probeCriterion();
        this.resumeItemId = content.resumeItemId();
        this.questionType = content.questionType();
        this.templateId = content.templateId();
        this.askedAt = askedAt;
    }

    /**
     * A question that starts a thread (INTRO, TECHNICAL, CODING or SYSTEM_DESIGN). Its section is derived from its kind.
     *
     * @throws IllegalArgumentException for a FOLLOW_UP kind, a difficulty outside 1 to 5 or a sequence number below 1
     */
    public static InterviewQuestion root(
            UUID interviewId,
            int sequenceNo,
            QuestionKind kind,
            @Nullable Long topicId,
            int difficulty,
            QuestionContent content,
            Instant askedAt) {
        Require.nonNull(kind, "kind");
        Section section = switch (kind) {
            case INTRO -> Section.INTRODUCTION;
            case TECHNICAL -> Section.QUESTIONING;
            case CODING -> Section.CODING;
            case SYSTEM_DESIGN -> Section.SYSTEM_DESIGN;
            case FOLLOW_UP -> throw new IllegalArgumentException("a FOLLOW_UP question has a parent: use followUp()");
        };
        return build(interviewId, sequenceNo, section, kind, topicId, difficulty, null, 0, content, askedAt);
    }

    /**
     * A follow-up below {@code parentQuestionId}.
     *
     * @param followUpDepth 1 for a follow-up of a root question, 2 for a follow-up of a follow-up, and so on
     * @throws IllegalArgumentException for the INTRODUCTION section (the introduction has no follow-ups), a depth outside 1 to
     *                                  {@value #MAX_FOLLOW_UP_DEPTH} (to {@value InterviewPlan#MAX_THREAD_DEPTH} in a design
     *                                  thread), a difficulty outside 1 to 5 or a sequence number below 1
     */
    public static InterviewQuestion followUp(
            UUID interviewId,
            int sequenceNo,
            Section section,
            @Nullable Long topicId,
            int difficulty,
            UUID parentQuestionId,
            int followUpDepth,
            QuestionContent content,
            Instant askedAt) {
        Require.nonNull(section, "section");
        Require.nonNull(parentQuestionId, "parentQuestionId");
        if (section == Section.INTRODUCTION) {
            throw new IllegalArgumentException("the introduction has no follow-up questions");
        }
        Require.between(followUpDepth, 1, section == Section.SYSTEM_DESIGN ? InterviewPlan.MAX_THREAD_DEPTH : MAX_FOLLOW_UP_DEPTH,
                "followUpDepth");
        return build(interviewId, sequenceNo, section, QuestionKind.FOLLOW_UP, topicId, difficulty, parentQuestionId,
                followUpDepth, content, askedAt);
    }

    private static InterviewQuestion build(
            UUID interviewId,
            int sequenceNo,
            Section section,
            QuestionKind kind,
            @Nullable Long topicId,
            int difficulty,
            @Nullable UUID parentQuestionId,
            int followUpDepth,
            QuestionContent content,
            Instant askedAt) {
        Require.nonNull(interviewId, "interviewId");
        Require.atLeast(sequenceNo, 1, "sequenceNo");
        if (topicId != null && topicId <= 0) {
            throw new IllegalArgumentException("topicId must be positive but was " + topicId);
        }
        Require.between(difficulty, DifficultyBand.LOWEST, DifficultyBand.HIGHEST, "difficulty");
        Require.nonNull(content, "content");
        Require.nonNull(askedAt, "askedAt");
        if (content.codingProblemId() != null && kind != QuestionKind.CODING) {
            throw new IllegalArgumentException("only a coding question is taken from the problem bank");
        }
        if (content.designProblemId() != null && kind != QuestionKind.SYSTEM_DESIGN) {
            throw new IllegalArgumentException("only a system design question is taken from the design problem bank");
        }
        if (content.probeCriterion() != null && (kind != QuestionKind.FOLLOW_UP || section != Section.SYSTEM_DESIGN)) {
            throw new IllegalArgumentException("only a follow-up of a design thread probes a design dimension");
        }
        if (content.resumeItemId() != null && kind != QuestionKind.TECHNICAL) {
            throw new IllegalArgumentException("only a technical question is anchored on a résumé experience");
        }
        if (content.questionType() != null && section != Section.QUESTIONING) {
            throw new IllegalArgumentException("only a question of the questioning section has a question type");
        }
        if (content.templateId() != null && kind != QuestionKind.TECHNICAL) {
            throw new IllegalArgumentException("only a technical main question is written from a template");
        }
        return new InterviewQuestion(
                interviewId, sequenceNo, section, kind, topicId, difficulty, parentQuestionId, followUpDepth, content, askedAt);
    }

    /** The curated problem this system design question was taken from; null for every other question. */
    public @Nullable String getDesignProblemId() {
        return designProblemId;
    }

    /** The résumé experience this technical question is anchored on; null for every other question. */
    public @Nullable String getResumeItemId() {
        return resumeItemId;
    }

    /**
     * What kind of questioning question this is (AI_ARCHITECTURE.md section 2.15); a follow-up carries its thread's type, a claim
     * probe EXPERIENCE. Null outside the questioning section and for questions asked before stages existed.
     */
    public @Nullable QuestionType getQuestionType() {
        return questionType;
    }

    /** The question bank template this technical question was written from; null for every other question. */
    public @Nullable String getTemplateId() {
        return templateId;
    }

    /** The design dimension this follow-up probes; null for every other question. */
    public @Nullable Criterion getProbeCriterion() {
        return probeCriterion;
    }

    /** The curated problem this coding question was taken from; null for every other question. */
    public @Nullable String getCodingProblemId() {
        return codingProblemId;
    }

    public boolean isFollowUp() {
        return kind == QuestionKind.FOLLOW_UP;
    }

    public UUID getInterviewId() {
        return interviewId;
    }

    public @Nullable UUID getParentQuestionId() {
        return parentQuestionId;
    }

    /** Position in the interview, starting at 1; unique per interview. */
    public int getSequenceNo() {
        return sequenceNo;
    }

    public Section getSection() {
        return section;
    }

    public QuestionKind getKind() {
        return kind;
    }

    public @Nullable Long getTopicId() {
        return topicId;
    }

    /** 1 to 5. Never sent to a candidate. */
    public int getDifficulty() {
        return difficulty;
    }

    /** 0 for a root question, 1 or 2 for a follow-up. */
    public int getFollowUpDepth() {
        return followUpDepth;
    }

    public String getPrompt() {
        return prompt;
    }

    public @Nullable QuestionDetails getDetails() {
        return details;
    }

    /** What a good answer contains. Server-only: never sent to a candidate. */
    public List<ExpectedKeyPoint> getExpectedKeyPoints() {
        return Collections.unmodifiableList(expectedKeyPoints);
    }

    public String getRubricId() {
        return rubricId;
    }

    public QuestionOrigin getOrigin() {
        return origin;
    }

    public @Nullable UUID getGenerationLlmCallId() {
        return generationLlmCallId;
    }

    public Instant getAskedAt() {
        return askedAt;
    }
}
