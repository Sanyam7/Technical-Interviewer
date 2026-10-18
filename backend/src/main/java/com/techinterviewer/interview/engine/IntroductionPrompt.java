package com.techinterviewer.interview.engine;

import com.techinterviewer.common.validation.Require;
import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.TargetRole;
import java.util.List;

/**
 * The text of the introduction question (ARCHITECTURE.md section 5: a fixed prompt, no model involved). It is built
 * from the interview configuration: the role, the candidate's seniority and the selected skills. There are three fixed
 * phrasings; the one used is chosen by a stable hash of the configuration, so the same configuration always gets the
 * same text and nothing is random.
 */
public final class IntroductionPrompt {

    private static final int VARIANTS = 3;
    private static final int MAX_SKILLS = 20;

    private IntroductionPrompt() {
    }

    /**
     * @param role       role interviewed for
     * @param experience seniority of the candidate
     * @param skillNames display names of the selected skills, in the order to show them; at least one
     */
    public static String render(TargetRole role, ExperienceLevel experience, List<String> skillNames) {
        Require.nonNull(role, "role");
        Require.nonNull(experience, "experience");
        List<String> skills = List.copyOf(Require.nonNull(skillNames, "skillNames"));
        if (skills.isEmpty() || skills.size() > MAX_SKILLS) {
            throw new IllegalArgumentException("skillNames must have between 1 and " + MAX_SKILLS + " entries");
        }
        skills.forEach(name -> Require.notBlank(name, "skill name", 80));

        String background = levelPhrase(experience) + " " + rolePhrase(role);
        String technologies = join(skills);
        int variant = Math.floorMod((role.name() + "|" + experience.name() + "|" + technologies).hashCode(), VARIANTS);
        return switch (variant) {
            case 0 -> "Welcome to your " + rolePhrase(role) + " interview. To begin, please introduce yourself and tell me about your "
                    + background + " experience, then walk me through a recent project in which you worked with " + technologies
                    + ". Say what your own part was and which decisions you made.";
            case 1 -> "Thanks for joining. Let's start with you: please introduce yourself and describe the " + background
                    + " work you have done so far. Then pick one recent project involving " + technologies
                    + " and tell me what you built and what you would do differently today.";
            default -> "Before the technical questions, I would like to get to know you. Please summarise your " + background
                    + " background and describe a recent piece of work with " + technologies
                    + ": the problem, your contribution and the trade-offs you faced.";
        };
    }

    private static String levelPhrase(ExperienceLevel experience) {
        return switch (experience) {
            case INTERN -> "intern-level";
            case JUNIOR -> "junior";
            case MID -> "mid-level";
            case SENIOR -> "senior";
            case STAFF -> "staff-level";
        };
    }

    private static String rolePhrase(TargetRole role) {
        return switch (role) {
            case BACKEND -> "backend";
            case FRONTEND -> "frontend";
            case FULL_STACK -> "full-stack";
        };
    }

    /** "Java", "Java and Spring Boot", "Java, Spring Boot and PostgreSQL". */
    private static String join(List<String> names) {
        if (names.size() == 1) {
            return names.getFirst();
        }
        String head = String.join(", ", names.subList(0, names.size() - 1));
        return head + " and " + names.getLast();
    }
}
