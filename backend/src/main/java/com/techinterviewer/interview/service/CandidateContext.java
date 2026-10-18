package com.techinterviewer.interview.service;

import com.techinterviewer.interview.engine.PlannerExperience;
import com.techinterviewer.interview.engine.PlannerInput;
import com.techinterviewer.interview.engine.PlannerSkill;
import com.techinterviewer.resume.ResumeVocabulary;
import com.techinterviewer.resume.domain.ExperienceKind;
import com.techinterviewer.resume.domain.ResumeExperience;
import com.techinterviewer.resume.domain.ResumeProfile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What a personalised interview knows about the candidate from the résumé (AI_ARCHITECTURE.md section 2.13): the résumé it is
 * linked to and its experiences, each with the topics and skills of this interview the vocabulary links it to. Experiences linked
 * to nothing the interview asks about are left out. The planner receives only ids and links, never the résumé's words.
 *
 * @param resumeId    the résumé the interview is personalised from
 * @param experiences the linked experiences, in résumé order
 */
record CandidateContext(UUID resumeId, List<PlannerExperience> experiences) {

    CandidateContext {
        experiences = List.copyOf(experiences);
    }

    /** The context of a résumé for an interview on {@code skills} (with their planning topics). */
    static CandidateContext of(UUID resumeId, ResumeProfile profile, List<PlannerSkill> skills, ResumeVocabulary vocabulary) {
        Set<String> skillSlugs = new HashSet<>();
        Set<String> topicKeys = new HashSet<>();
        for (PlannerSkill skill : skills) {
            skillSlugs.add(skill.slug());
            skill.topics().forEach(topic -> topicKeys.add(skill.topicKey(topic)));
        }
        List<PlannerExperience> linked = new ArrayList<>();
        for (ResumeExperience experience : profile.experiences()) {
            ResumeVocabulary.Links links = vocabulary.links(experience.technologies(), experience.text(), topicKeys, skillSlugs);
            if (!links.isEmpty()) {
                linked.add(new PlannerExperience(experience.id(), priority(experience.kind()), links.topics(), links.skills()));
            }
        }
        return new CandidateContext(resumeId, linked);
    }

    /** A project shows the most to ask about, then an achievement, then a responsibility. */
    private static int priority(ExperienceKind kind) {
        return switch (kind) {
            case PROJECT -> 0;
            case ACHIEVEMENT -> 1;
            case RESPONSIBILITY -> 2;
        };
    }

    /** The planner input with this context's experiences. */
    PlannerInput applyTo(PlannerInput input) {
        return input.withExperiences(experiences);
    }
}
