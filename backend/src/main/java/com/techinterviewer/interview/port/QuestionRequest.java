package com.techinterviewer.interview.port;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import com.techinterviewer.interview.domain.DifficultyBand;
import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.QuestionType;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.TargetRole;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Everything the question generator may know to write the next question (AI_ARCHITECTURE.md section 2.2).
 * Every decision in it (topic, difficulty, kind, follow-up target) was made by deterministic code.
 *
 * <p>The introduction is a static text and never goes through this request, so {@code kind} is never INTRO.
 *
 * @param interviewId      the interview (for logging and call records)
 * @param section          section the question belongs to
 * @param kind             TECHNICAL, FOLLOW_UP, CODING or SYSTEM_DESIGN, consistent with {@code section}
 * @param topic            topic to ask about; required for TECHNICAL, optional for coding and design
 * @param targetDifficulty 1 to 5
 * @param role             the role interviewed for
 * @param experience       seniority of the candidate
 * @param codingLanguage   programming language of a coding question (required for CODING)
 * @param focusSkills      names of the skills the candidate chose, for context when there is no topic
 * @param askedQuestions   questions already asked, oldest first
 * @param candidateClaims  claims the candidate made: the subject of an EXPERIENCE question without a résumé anchor; given to a question
 *                         without a type (a plan without stages) to personalise it; empty for every other typed question, so that a
 *                         fundamentals or practical question is about the technology, not the candidate's projects
 * @param followUp         required for FOLLOW_UP and absent otherwise
 * @param resumeAnchor     a TECHNICAL question only: the résumé experience to ask about, chosen by the engine; absent otherwise
 * @param questionType     a TECHNICAL question of a staged plan only: the type the engine decided (AI_ARCHITECTURE.md section 2.15)
 * @param template         a typed TECHNICAL question only: the curated angle of the question bank for it, when the bank has one
 */
public record QuestionRequest(
        UUID interviewId,
        Section section,
        QuestionKind kind,
        @Nullable TopicRef topic,
        int targetDifficulty,
        TargetRole role,
        ExperienceLevel experience,
        @Nullable String codingLanguage,
        List<String> focusSkills,
        List<AskedQuestion> askedQuestions,
        List<String> candidateClaims,
        @Nullable FollowUpTarget followUp,
        @Nullable ResumeAnchor resumeAnchor,
        @Nullable QuestionType questionType,
        @Nullable CuratedTemplate template) {

    public static final int MAX_ASKED_QUESTIONS = 100;

    public QuestionRequest {
        Require.nonNull(interviewId, "interviewId");
        Require.nonNull(section, "section");
        Require.nonNull(kind, "kind");
        Require.between(targetDifficulty, DifficultyBand.LOWEST, DifficultyBand.HIGHEST, "targetDifficulty");
        Require.nonNull(role, "role");
        Require.nonNull(experience, "experience");
        codingLanguage = Require.nullOrNotBlank(codingLanguage, "codingLanguage", 20);
        focusSkills = Immutables.list(focusSkills);
        askedQuestions = Immutables.list(askedQuestions);
        candidateClaims = Immutables.list(candidateClaims);
        focusSkills.forEach(skill -> Require.notBlank(skill, "focusSkills entry", 80));
        candidateClaims.forEach(claim -> Require.notBlank(claim, "candidateClaims entry", 300));
        Require.between(askedQuestions.size(), 0, MAX_ASKED_QUESTIONS, "askedQuestions size");
        switch (kind) {
            case INTRO -> throw new IllegalArgumentException("the introduction is static and is never generated");
            case TECHNICAL -> {
                requireSection(section, kind, Section.QUESTIONING);
                Require.nonNull(topic, "topic");
            }
            case CODING -> requireSection(section, kind, Section.CODING);
            case SYSTEM_DESIGN -> requireSection(section, kind, Section.SYSTEM_DESIGN);
            case FOLLOW_UP -> {
                if (section == Section.INTRODUCTION) {
                    throw new IllegalArgumentException("the introduction has no follow-ups");
                }
                Require.nonNull(followUp, "followUp");
            }
        }
        if (resumeAnchor != null && kind != QuestionKind.TECHNICAL) {
            throw new IllegalArgumentException("only a technical question is anchored on a résumé experience");
        }
        if (questionType != null && kind != QuestionKind.TECHNICAL) {
            throw new IllegalArgumentException("only a technical main question has a question type");
        }
        if (resumeAnchor != null && questionType != null && questionType != QuestionType.EXPERIENCE) {
            throw new IllegalArgumentException("a question anchored on a résumé experience is an EXPERIENCE question");
        }
        if (template != null && (questionType == null || template.type().stage() != questionType.stage())) {
            throw new IllegalArgumentException("a template belongs to a typed question of its stage");
        }
        if (kind != QuestionKind.FOLLOW_UP && followUp != null) {
            throw new IllegalArgumentException("followUp is only allowed for a FOLLOW_UP question");
        }
        // Every question of the coding section, a follow-up included, is answered in the configured language.
        if (section == Section.CODING && codingLanguage == null) {
            throw new IllegalArgumentException("codingLanguage is required in the coding section");
        }
    }

    /** A request without a question type. */
    public QuestionRequest(UUID interviewId, Section section, QuestionKind kind, @Nullable TopicRef topic, int targetDifficulty, TargetRole role,
            ExperienceLevel experience, @Nullable String codingLanguage, List<String> focusSkills, List<AskedQuestion> askedQuestions,
            List<String> candidateClaims, @Nullable FollowUpTarget followUp, @Nullable ResumeAnchor resumeAnchor) {
        this(interviewId, section, kind, topic, targetDifficulty, role, experience, codingLanguage, focusSkills, askedQuestions, candidateClaims,
                followUp, resumeAnchor, null, null);
    }

    /** A request without a résumé anchor. */
    public QuestionRequest(UUID interviewId, Section section, QuestionKind kind, @Nullable TopicRef topic, int targetDifficulty, TargetRole role,
            ExperienceLevel experience, @Nullable String codingLanguage, List<String> focusSkills, List<AskedQuestion> askedQuestions,
            List<String> candidateClaims, @Nullable FollowUpTarget followUp) {
        this(interviewId, section, kind, topic, targetDifficulty, role, experience, codingLanguage, focusSkills, askedQuestions, candidateClaims,
                followUp, null, null, null);
    }

    private static void requireSection(Section actual, QuestionKind kind, Section expected) {
        if (actual != expected) {
            throw new IllegalArgumentException(kind + " questions belong to " + expected + " but section is " + actual);
        }
    }
}
