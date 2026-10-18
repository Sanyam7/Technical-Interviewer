package com.techinterviewer.interview.service;

import com.techinterviewer.catalog.CatalogService;
import com.techinterviewer.catalog.InterviewOptions;
import com.techinterviewer.catalog.SkillRef;
import com.techinterviewer.common.error.FieldValidationException;
import com.techinterviewer.common.error.FieldViolation;
import com.techinterviewer.interview.api.dto.ConfigurationRequest;
import com.techinterviewer.interview.api.dto.StageMixView;
import com.techinterviewer.interview.domain.InterviewConfiguration;
import com.techinterviewer.interview.domain.InterviewStrategy;
import com.techinterviewer.interview.domain.StageMix;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The rules of a configuration that annotations cannot express: the duration is one the product offers, the skills
 * exist, are active and are not repeated, the coding language fits the interview type, a CUSTOM strategy has a stage mix that adds
 * up to 100 (and no other strategy has one), and a résumé-focused strategy is personalised from the résumé. Whether the candidate has a résumé
 * for a personalised interview is checked when the interview is created (a configuration may be saved before the upload). Every
 * violation of a request is collected and reported on its own field in one
 * {@code 400 VALIDATION_FAILED}, so a client can fix a form in one round trip.
 */
@Component
class ConfigurationValidator {

    private final CatalogService catalog;

    ConfigurationValidator(CatalogService catalog) {
        this.catalog = catalog;
    }

    /**
     * @param fieldPrefix prepended to every reported field: empty for a configuration on its own, {@code "configuration."}
     *                    for one inline in an interview request
     * @throws FieldValidationException listing every violation
     */
    ValidatedConfiguration validate(ConfigurationRequest request, String fieldPrefix) {
        List<FieldViolation> violations = new ArrayList<>(rules(request, fieldPrefix));
        List<SkillRef> skills = List.of();
        try {
            skills = catalog.requireActiveSkills(request.skills(), index -> skillField(fieldPrefix, index));
        } catch (FieldValidationException unknown) {
            violations.addAll(unknown.violations());
        }
        if (!violations.isEmpty()) {
            throw new FieldValidationException(violations.stream()
                    .sorted(Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::message))
                    .toList());
        }
        return new ValidatedConfiguration(
                request.role(),
                request.experienceLevel(),
                request.difficulty(),
                request.durationMinutes(),
                request.interviewType(),
                request.codingLanguage(),
                Boolean.TRUE.equals(request.useResume()),
                skills,
                strategyOf(request),
                customMix(request));
    }

    private static InterviewStrategy strategyOf(ConfigurationRequest request) {
        return request.strategy() == null ? InterviewStrategy.DEFAULT : request.strategy();
    }

    private static @Nullable StageMix customMix(ConfigurationRequest request) {
        StageMixView mix = request.stageMix();
        return strategyOf(request) == InterviewStrategy.CUSTOM && mix != null
                ? new StageMix(mix.fundamentals(), mix.practical(), mix.project())
                : null;
    }

    /** The rules that need no catalog: pure, so they are testable without a database. */
    static List<FieldViolation> rules(ConfigurationRequest request, String fieldPrefix) {
        List<FieldViolation> violations = new ArrayList<>();
        if (!InterviewOptions.isSupportedDuration(request.durationMinutes())) {
            violations.add(new FieldViolation(
                    fieldPrefix + "durationMinutes", "Must be one of " + InterviewOptions.DURATIONS_MINUTES + "."));
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < request.skills().size(); i++) {
            String slug = request.skills().get(i);
            if (!seen.add(slug)) {
                violations.add(new FieldViolation(skillField(fieldPrefix, i), "Skill '" + slug + "' is listed more than once."));
            }
        }
        codingLanguageViolation(request, fieldPrefix).ifPresent(violations::add);
        violations.addAll(strategyViolations(request, fieldPrefix));
        return violations;
    }

    private static List<FieldViolation> strategyViolations(ConfigurationRequest request, String fieldPrefix) {
        List<FieldViolation> violations = new ArrayList<>();
        InterviewStrategy strategy = strategyOf(request);
        StageMixView mix = request.stageMix();
        if (strategy == InterviewStrategy.CUSTOM) {
            if (mix == null) {
                violations.add(new FieldViolation(fieldPrefix + "stageMix", "Required for a CUSTOM strategy."));
            } else if (mix.fundamentals() != null && mix.practical() != null && mix.project() != null
                    && mix.fundamentals() + mix.practical() + mix.project() != StageMix.TOTAL) {
                violations.add(new FieldViolation(fieldPrefix + "stageMix", "The shares must add up to " + StageMix.TOTAL + " but add up to "
                        + (mix.fundamentals() + mix.practical() + mix.project()) + "."));
            }
        } else if (mix != null) {
            violations.add(new FieldViolation(fieldPrefix + "stageMix", "Only a CUSTOM strategy takes a stage mix; " + strategy
                    + " has its own."));
        }
        if (strategy.requiresResume() && !Boolean.TRUE.equals(request.useResume())) {
            violations.add(new FieldViolation(fieldPrefix + "strategy", "A " + strategy + " interview is personalised from your résumé: set "
                    + "useResume."));
        }
        return violations;
    }

    private static Optional<FieldViolation> codingLanguageViolation(ConfigurationRequest request, String fieldPrefix) {
        String field = fieldPrefix + "codingLanguage";
        String language = request.codingLanguage();
        boolean needed = InterviewConfiguration.requiresCodingLanguage(request.interviewType());
        if (!needed) {
            return language == null
                    ? Optional.empty()
                    : Optional.of(new FieldViolation(field, "Not allowed for a " + request.interviewType() + " interview."));
        }
        if (language == null) {
            return Optional.of(new FieldViolation(field, "Required for a " + request.interviewType() + " interview."));
        }
        return InterviewOptions.isSupportedCodingLanguage(language)
                ? Optional.empty()
                : Optional.of(new FieldViolation(field, "Must be one of " + InterviewOptions.CODING_LANGUAGES + "."));
    }

    private static String skillField(String fieldPrefix, int index) {
        return fieldPrefix + "skills[" + index + "]";
    }
}
