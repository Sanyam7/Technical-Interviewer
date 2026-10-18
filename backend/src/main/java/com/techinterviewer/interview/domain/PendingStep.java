package com.techinterviewer.interview.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.techinterviewer.common.validation.Require;
import com.techinterviewer.evaluation.domain.Criterion;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The question the interview has decided to ask next but has not generated yet. It is persisted together with
 * the state transition, <b>before</b> the question generator is called, so that when generation fails
 * ({@code interviews.current_question_id} stays null) the client can ask the server to retry
 * ({@code POST /interviews/{id}/questions/next}) and the retry produces exactly the question that was decided.
 *
 * <p>Every field is decided by deterministic code; the generator only words the question.
 *
 * @param kind              a new topic question, or a follow-up to {@code parentQuestionId}
 * @param section           section the question belongs to
 * @param topic             {@code skill/topic} key (absent for a coding or design question without a catalog topic)
 * @param difficulty        target difficulty, 1 to 5
 * @param parentQuestionId  FOLLOW_UP only: the question being followed up
 * @param followUpMode      FOLLOW_UP only: why
 * @param targetKeyPointId  PROBE_GAP only: the key point to probe
 * @param targetClaim       PROBE_CLAIM only: the claim to probe
 * @param targetCriterion   PROBE_DIMENSION only: the design dimension to probe
 * @param resumeItemId      a questioning topic question only: the résumé experience (x1, x2, ...) to anchor it on, chosen by
 *                          the engine (AI_ARCHITECTURE.md section 2.13); null otherwise
 * @param questionType      a questioning topic question of a staged plan only: what kind of question to ask (AI_ARCHITECTURE.md
 *                          section 2.15); null otherwise. A résumé anchor needs EXPERIENCE (or no type, in a plan without stages)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PendingStep(
        Kind kind,
        Section section,
        @Nullable String topic,
        int difficulty,
        @Nullable UUID parentQuestionId,
        @Nullable FollowUpMode followUpMode,
        @Nullable String targetKeyPointId,
        @Nullable String targetClaim,
        @Nullable Criterion targetCriterion,
        @Nullable String resumeItemId,
        @Nullable QuestionType questionType) {

    public enum Kind {
        NEXT_TOPIC,
        FOLLOW_UP
    }

    public PendingStep {
        Require.nonNull(kind, "kind");
        Require.nonNull(section, "section");
        topic = Require.nullOrNotBlank(topic, "topic", 160);
        Require.between(difficulty, DifficultyBand.LOWEST, DifficultyBand.HIGHEST, "difficulty");
        targetKeyPointId = Require.nullOrNotBlank(targetKeyPointId, "targetKeyPointId", 40);
        targetClaim = Require.nullOrNotBlank(targetClaim, "targetClaim", Claim.MAX_LENGTH);
        resumeItemId = Require.nullOrNotBlank(resumeItemId, "resumeItemId", 10);
        if (resumeItemId != null && (kind != Kind.NEXT_TOPIC || section != Section.QUESTIONING || topic == null)) {
            throw new IllegalArgumentException("only a questioning topic question is anchored on a résumé experience");
        }
        if (questionType != null && (kind != Kind.NEXT_TOPIC || section != Section.QUESTIONING || topic == null)) {
            throw new IllegalArgumentException("only a questioning topic question has a question type");
        }
        if (resumeItemId != null && questionType != null && questionType != QuestionType.EXPERIENCE) {
            throw new IllegalArgumentException("a question anchored on a résumé experience is an EXPERIENCE question");
        }
        if (kind == Kind.NEXT_TOPIC) {
            if (parentQuestionId != null || followUpMode != null || targetKeyPointId != null || targetClaim != null
                    || targetCriterion != null) {
                throw new IllegalArgumentException("a NEXT_TOPIC step has no parent question, mode or target");
            }
        } else {
            Require.nonNull(parentQuestionId, "parentQuestionId");
            Require.nonNull(followUpMode, "followUpMode");
            if ((followUpMode == FollowUpMode.PROBE_GAP) != (targetKeyPointId != null)) {
                throw new IllegalArgumentException("targetKeyPointId is required for PROBE_GAP and not allowed otherwise");
            }
            if ((followUpMode == FollowUpMode.PROBE_CLAIM) != (targetClaim != null)) {
                throw new IllegalArgumentException("targetClaim is required for PROBE_CLAIM and not allowed otherwise");
            }
            if ((followUpMode == FollowUpMode.PROBE_DIMENSION) != (targetCriterion != null)) {
                throw new IllegalArgumentException("targetCriterion is required for PROBE_DIMENSION and not allowed otherwise");
            }
            if (followUpMode == FollowUpMode.PROBE_DIMENSION && section != Section.SYSTEM_DESIGN) {
                throw new IllegalArgumentException("only a design thread probes design dimensions");
            }
        }
    }

    /** A step without a question type. */
    public PendingStep(Kind kind, Section section, @Nullable String topic, int difficulty, @Nullable UUID parentQuestionId,
            @Nullable FollowUpMode followUpMode, @Nullable String targetKeyPointId, @Nullable String targetClaim,
            @Nullable Criterion targetCriterion, @Nullable String resumeItemId) {
        this(kind, section, topic, difficulty, parentQuestionId, followUpMode, targetKeyPointId, targetClaim, targetCriterion, resumeItemId, null);
    }

    /** A step without a résumé anchor. */
    public PendingStep(Kind kind, Section section, @Nullable String topic, int difficulty, @Nullable UUID parentQuestionId,
            @Nullable FollowUpMode followUpMode, @Nullable String targetKeyPointId, @Nullable String targetClaim,
            @Nullable Criterion targetCriterion) {
        this(kind, section, topic, difficulty, parentQuestionId, followUpMode, targetKeyPointId, targetClaim, targetCriterion, null, null);
    }

    /** A step that probes no design dimension. */
    public PendingStep(Kind kind, Section section, @Nullable String topic, int difficulty, @Nullable UUID parentQuestionId,
            @Nullable FollowUpMode followUpMode, @Nullable String targetKeyPointId, @Nullable String targetClaim) {
        this(kind, section, topic, difficulty, parentQuestionId, followUpMode, targetKeyPointId, targetClaim, null, null, null);
    }

    /** This topic question, anchored on résumé experience {@code itemId}. */
    public PendingStep anchoredOn(String itemId) {
        return new PendingStep(kind, section, topic, difficulty, parentQuestionId, followUpMode, targetKeyPointId, targetClaim, targetCriterion,
                Require.notBlank(itemId, "itemId"), questionType);
    }

    /** This topic question, of type {@code type}. */
    public PendingStep ofType(QuestionType type) {
        return new PendingStep(kind, section, topic, difficulty, parentQuestionId, followUpMode, targetKeyPointId, targetClaim, targetCriterion,
                resumeItemId, Require.nonNull(type, "type"));
    }

    public static PendingStep nextTopic(Section section, @Nullable String topic, int difficulty) {
        return new PendingStep(Kind.NEXT_TOPIC, section, topic, difficulty, null, null, null, null, null);
    }

    public static PendingStep followUp(
            Section section,
            @Nullable String topic,
            int difficulty,
            UUID parentQuestionId,
            FollowUpMode mode,
            @Nullable String targetKeyPointId,
            @Nullable String targetClaim) {
        return new PendingStep(Kind.FOLLOW_UP, section, topic, difficulty, parentQuestionId, mode, targetKeyPointId, targetClaim, null);
    }

    /** A follow-up of a design thread that probes {@code criterion}. */
    public static PendingStep designProbe(int difficulty, UUID parentQuestionId, Criterion criterion) {
        return new PendingStep(Kind.FOLLOW_UP, Section.SYSTEM_DESIGN, null, difficulty, parentQuestionId, FollowUpMode.PROBE_DIMENSION, null,
                null, Require.nonNull(criterion, "criterion"));
    }
}
