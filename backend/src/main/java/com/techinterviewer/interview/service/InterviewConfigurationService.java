package com.techinterviewer.interview.service;

import com.techinterviewer.catalog.CatalogService;
import com.techinterviewer.catalog.SkillRef;
import com.techinterviewer.common.api.PageResponse;
import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import com.techinterviewer.interview.api.dto.ConfigurationRequest;
import com.techinterviewer.interview.api.dto.ConfigurationResponse;
import com.techinterviewer.interview.domain.InterviewConfiguration;
import com.techinterviewer.interview.repository.InterviewConfigurationRepository;
import com.techinterviewer.interview.repository.InterviewRepository;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * The saved configurations of a candidate. Every operation takes the id of the authenticated user and reaches only that
 * user's configurations; somebody else's is as good as missing ({@code 404}). A configuration never changes after it was
 * created, and one that an interview uses cannot be deleted.
 */
@Service
public class InterviewConfigurationService {

    static final String NOT_FOUND = InterviewLoader.CONFIGURATION_NOT_FOUND;
    static final String IN_USE = "An interview was created from this configuration, so it cannot be deleted.";

    private final ConfigurationValidator validator;
    private final InterviewConfigurationRepository configurations;
    private final InterviewRepository interviews;
    private final CatalogService catalog;
    private final InterviewTransactions transactions;
    private final Clock clock;

    InterviewConfigurationService(
            ConfigurationValidator validator,
            InterviewConfigurationRepository configurations,
            InterviewRepository interviews,
            CatalogService catalog,
            InterviewTransactions transactions,
            Clock clock) {
        this.validator = validator;
        this.configurations = configurations;
        this.interviews = interviews;
        this.catalog = catalog;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** @throws com.techinterviewer.common.error.FieldValidationException when a rule is broken, listing every violation */
    public ConfigurationResponse create(UUID userId, ConfigurationRequest request) {
        ValidatedConfiguration validated = validator.validate(request, "");
        InterviewConfiguration saved = transactions.write(
                () -> configurations.save(validated.toEntity(userId, clock.instant())));
        Map<Long, SkillRef> skills = validated.skills().stream().collect(Collectors.toMap(SkillRef::id, skill -> skill));
        return ConfigurationMapper.toResponse(saved, skills);
    }

    /** The user's configurations, newest first. */
    public PageResponse<ConfigurationResponse> list(UUID userId, int page, int size) {
        return transactions.read(() -> {
            Page<InterviewConfiguration> found = configurations.findPageByOwnerUserId(userId, PageRequest.of(page, size));
            Map<Long, SkillRef> skills = catalog.skillsById(
                    found.stream().flatMap(configuration -> configuration.getSkillIds().stream()).collect(Collectors.toSet()));
            return PageResponse.from(found.map(configuration -> ConfigurationMapper.toResponse(configuration, skills)));
        });
    }

    public ConfigurationResponse get(UUID userId, UUID configurationId) {
        return transactions.read(() -> {
            InterviewConfiguration configuration = configurations.findByIdAndOwnerUserId(configurationId, userId)
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, NOT_FOUND));
            return ConfigurationMapper.toResponse(configuration, catalog.skillsById(configuration.getSkillIds()));
        });
    }

    /**
     * @throws ApiException {@code 404} when the configuration is not the user's, {@code 409 CONFIGURATION_IN_USE} when an
     *                      interview uses it. That includes the race where an interview is created between the check and
     *                      the delete: the database refuses the delete and the refusal is reported the same way
     */
    public void delete(UUID userId, UUID configurationId) {
        try {
            transactions.execute(() -> {
                configurations.findByIdAndOwnerUserId(configurationId, userId)
                        .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, NOT_FOUND));
                if (interviews.existsByConfigurationId(configurationId)) {
                    throw new ApiException(ErrorCode.CONFIGURATION_IN_USE, IN_USE);
                }
                configurations.deleteByIdAndOwnerUserId(configurationId, userId);
            });
        } catch (DataIntegrityViolationException interviewCreatedMeanwhile) {
            throw new ApiException(ErrorCode.CONFIGURATION_IN_USE, IN_USE, interviewCreatedMeanwhile);
        }
    }
}
