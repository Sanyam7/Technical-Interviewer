package com.techinterviewer.interview.engine;

import com.techinterviewer.interview.domain.DifficultyBand;
import com.techinterviewer.interview.domain.InterviewDifficulty;
import com.techinterviewer.interview.domain.InterviewMemory;
import com.techinterviewer.interview.domain.InterviewPlan;
import com.techinterviewer.interview.domain.InterviewStage;
import com.techinterviewer.interview.domain.PendingStep;
import com.techinterviewer.interview.domain.QuestionType;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.SectionPlan;
import com.techinterviewer.interview.domain.StagePlan;
import com.techinterviewer.interview.domain.TopicStat;
import java.util.Map;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Decides the next main question of a questioning section that runs in stages (AI_ARCHITECTURE.md section 2.15). Pure and
 * deterministic, like the rest of the engine. In order:
 *
 * <ol>
 *   <li><b>Stage:</b> the first stage, in the plan's order, that has asked fewer main questions than it plans. The stages run
 *       one after the other, so with the default strategy every selected technology's fundamentals are asked before any
 *       scenario, and scenarios before the candidate's own projects.
 *   <li><b>Topic and level:</b> {@link TopicSelector} under the plan's quotas, as without stages (the skill with the fewest answers
 *       first, so the fundamentals go round every skill). The PROJECT stage prefers a topic it has an unused résumé experience for.
 *   <li><b>Type:</b> FUNDAMENTALS asks CONCEPT questions at levels 1 and 2 (definitions and correct use are what a junior must
 *       know); from level 3 it alternates INTERNALS and CONCEPT, starting with INTERNALS (how it works, then why and when).
 *       PRACTICAL alternates SCENARIO and DEBUGGING, starting with SCENARIO. PROJECT asks EXPERIENCE.
 *   <li><b>What a project question is about:</b> the first unused résumé experience linked to the topic, else to its skill
 *       ({@link ResumeAnchoring}); without one, what the candidate said about their own work in the interview (the claims the
 *       evaluations recorded); without either, there is nothing of the candidate's own to discuss and the slot is asked as a
 *       SCENARIO instead. It still counts as the PROJECT stage's question.
 * </ol>
 *
 * Only a PROJECT question is ever anchored on the résumé: fundamentals and practical questions are about the technology, not
 * about the candidate's projects.
 */
public final class StagedQuestioning {

    /**
     * The decided question.
     *
     * @param step      the step to generate, with its type (and anchor)
     * @param stage     the stage whose slot it uses
     * @param rationale why, in words a reviewer can check against the rules
     */
    public record Choice(PendingStep step, InterviewStage stage, String rationale) {
    }

    private StagedQuestioning() {
    }

    /**
     * @param memory the memory after the answer just processed
     * @throws IllegalArgumentException when the plan's questioning section has no stages or no quotas
     */
    public static Choice next(InterviewPlan plan, Map<String, TopicRange> ranges, InterviewMemory memory) {
        SectionPlan questioning = plan.section(Section.QUESTIONING).orElseThrow();
        if (!questioning.staged()) {
            throw new IllegalArgumentException("the questioning section has no stages");
        }
        StagePlan stage = currentStage(questioning, memory);
        DifficultyBand band = plan.difficulty();
        Map<String, Integer> quotas = questioning.topicQuotas();
        boolean project = stage.stage() == InterviewStage.PROJECT;
        Predicate<String> preferred = topic -> project
                && ResumeAnchoring.pick(plan.resume(), memory.resumeItemsAsked(), topic).isPresent();
        TopicChoice choice = TopicSelector.next(quotas, ranges, memory, band, preferred);
        int decided = memory.stageQuestionsOf(stage.stage());
        StringBuilder why = new StringBuilder("QUESTIONING ").append(stage.stage()).append(" question ").append(decided + 1)
                .append(" of ").append(stage.slots()).append(": ");

        PendingStep step = PendingStep.nextTopic(Section.QUESTIONING, choice.topic(), choice.difficulty());
        QuestionType type;
        switch (stage.stage()) {
            case FUNDAMENTALS -> type = choice.difficulty() <= 2 || decided % 2 == 1 ? QuestionType.CONCEPT : QuestionType.INTERNALS;
            case PRACTICAL -> type = decided % 2 == 0 ? QuestionType.SCENARIO : QuestionType.DEBUGGING;
            case PROJECT -> {
                ResumeAnchoring.Anchor anchor = ResumeAnchoring.pick(plan.resume(), memory.resumeItemsAsked(), choice.topic()).orElse(null);
                if (anchor != null) {
                    type = QuestionType.EXPERIENCE;
                    step = step.anchoredOn(anchor.itemId());
                    why.append("anchored on résumé experience ").append(anchor.itemId()).append(" (").append(anchor.basis()).append("); ");
                } else if (!memory.candidateClaims().isEmpty()) {
                    type = QuestionType.EXPERIENCE;
                    why.append("about what the candidate said of their own work (").append(memory.candidateClaims().size())
                            .append(" statement(s)); ");
                } else {
                    type = QuestionType.SCENARIO;
                    why.append("nothing of the candidate's own work to discuss (no résumé experience, no statement): asked as a SCENARIO; ");
                }
            }
            default -> throw new IllegalStateException("unknown stage " + stage.stage());
        }
        why.append(type).append(' ').append(describe(choice, memory, quotas));
        return new Choice(step.ofType(type), stage.stage(), why.toString());
    }

    /**
     * The first stage in the plan's order that has a slot left. When every stage has used its slots (the section's own slot count
     * decides when it ends, so this is only reached by a plan whose stages were used up by questions decided but never answered),
     * the last stage that plans any question.
     */
    static StagePlan currentStage(SectionPlan questioning, InterviewMemory memory) {
        StagePlan last = null;
        for (StagePlan stage : questioning.stages()) {
            if (stage.slots() == 0) {
                continue;
            }
            if (memory.stageQuestionsOf(stage.stage()) < stage.slots()) {
                return stage;
            }
            last = stage;
        }
        if (last == null) {
            throw new IllegalArgumentException("no stage of the questioning section plans a question");
        }
        return last;
    }

    private static String describe(TopicChoice choice, InterviewMemory memory, Map<String, Integer> quotas) {
        TopicStat stat = memory.topicStats().get(choice.topic());
        String skill = TopicSelector.skillOf(choice.topic());
        long skillAnswers = memory.topicStats().entrySet().stream()
                .filter(entry -> TopicSelector.skillOf(entry.getKey()).equals(skill))
                .mapToInt(entry -> entry.getValue().asked())
                .sum();
        int asked = stat == null ? 0 : stat.asked();
        @Nullable Integer quota = quotas.get(choice.topic());
        return choice.topic() + " at level " + choice.difficulty() + " (" + InterviewDifficulty.ofLevel(choice.difficulty()) + "): skill " + skill + " has " + skillAnswers + " answer(s), topic asked "
                + asked + " of " + quota + (stat == null ? "; new topic, starts at the candidate's level" : "; returns at the topic's level");
    }
}
