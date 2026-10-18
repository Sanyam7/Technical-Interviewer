package com.techinterviewer.interview.service;

import com.techinterviewer.catalog.CatalogService;
import com.techinterviewer.catalog.SkillRef;
import com.techinterviewer.common.error.FieldValidationException;
import com.techinterviewer.config.AppProperties;
import com.techinterviewer.interview.domain.InterviewConfiguration;
import com.techinterviewer.interview.domain.InterviewPlan;
import com.techinterviewer.interview.engine.InterviewPlanner;
import com.techinterviewer.interview.engine.PlannerInput;
import com.techinterviewer.resume.ResumeSnapshot;
import com.techinterviewer.resume.ResumeVocabulary;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Builds the plan of an interview from its configuration: the catalog supplies the topics, the planner decides. */
@Component
class InterviewPlanFactory {

    static final String NO_FITTING_TOPIC =
            "None of the topics of the selected skills fits the requested difficulty. Choose other skills or another difficulty.";

    private final CatalogService catalog;
    private final int maxFollowUpDepth;
    private final int maxDesignProbes;
    private final ResumeVocabulary vocabulary;

    InterviewPlanFactory(CatalogService catalog, AppProperties properties, ResumeVocabulary vocabulary) {
        this.catalog = catalog;
        this.vocabulary = vocabulary;
        this.maxFollowUpDepth = properties.interview().maxFollowUpDepth();
        this.maxDesignProbes = properties.interview().maxDesignProbes();
    }

    /**
     * @param field the request field to report when the configuration cannot be planned
     * @throws FieldValidationException when no topic of the selected skills fits the difficulty band (a technical
     *                                  interview then has nothing to ask)
     */
    InterviewPlan plan(InterviewConfiguration configuration, String field) {
        return plan(configuration, field, null);
    }

    /**
     * The plan of a configuration, personalised from {@code resume} when it is given (AI_ARCHITECTURE.md section 2.13).
     *
     */
    InterviewPlan plan(InterviewConfiguration configuration, String field, @Nullable ResumeSnapshot resume) {
        Map<Long, SkillRef> skills = catalog.skillsById(configuration.getSkillIds());
        PlannerInput input = PlannerInputs.of(
                configuration, skills, catalog.topicsForPlanning(configuration.getSkillIds()), maxFollowUpDepth, maxDesignProbes);
        if (resume != null) {
            input = CandidateContext.of(resume.id(), resume.profile(), input.skills(), vocabulary).applyTo(input);
        }
        try {
            return InterviewPlanner.plan(input);
        } catch (IllegalArgumentException noFittingTopic) {
            throw new FieldValidationException(field, NO_FITTING_TOPIC);
        }
    }
}
