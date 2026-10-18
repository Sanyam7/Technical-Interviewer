package com.techinterviewer.interview.domain;

/**
 * The stages of the questioning section (AI_ARCHITECTURE.md section 2.15). The plan says how many main questions each stage gets
 * and in which order the stages run; the engine asks them in that order.
 *
 * <ul>
 *   <li>FUNDAMENTALS: the core concepts and internal mechanics of every selected technology, without the résumé;
 *   <li>PRACTICAL: applying them: realistic scenarios and debugging;
 *   <li>PROJECT: the candidate's own experience: an experience from the résumé, or what they said about their work.
 * </ul>
 *
 * The adaptive deep dive is not a stage of its own: it is the follow-ups inside every stage (a gap probe or a harder question on
 * the same subject), which never leave the stage's subject.
 */
public enum InterviewStage {
    FUNDAMENTALS,
    PRACTICAL,
    PROJECT
}
