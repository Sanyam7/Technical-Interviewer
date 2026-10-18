package com.techinterviewer.profile;

import com.techinterviewer.catalog.CatalogService;
import com.techinterviewer.catalog.SkillRef;
import com.techinterviewer.common.error.FieldValidationException;
import com.techinterviewer.common.error.FieldViolation;
import com.techinterviewer.profile.dto.ProfileResponse;
import com.techinterviewer.profile.dto.ProfileSkillRequest;
import com.techinterviewer.profile.dto.ProfileSkillResponse;
import com.techinterviewer.profile.dto.UpdateProfileRequest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A candidate's own profile. Every operation takes the id of the authenticated user and touches only that user's
 * profile; there is no way to name another one. The profile is created on first access.
 *
 * <p>Transactions are explicit ({@link TransactionTemplate}) because creating a profile must be able to lose a
 * race: the insert of the second of two simultaneous first requests violates the unique {@code user_id} and
 * aborts its transaction, so the reload has to happen in a fresh one.
 */
@Service
public class ProfileService {

    private final ProfileRepository profiles;
    private final CatalogService catalog;
    private final Clock clock;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;

    ProfileService(ProfileRepository profiles, CatalogService catalog, Clock clock, PlatformTransactionManager transactionManager) {
        this.profiles = profiles;
        this.catalog = catalog;
        this.clock = clock;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    /** The profile of the user, created empty when this is the first access. */
    public ProfileResponse get(UUID userId) {
        Optional<ProfileResponse> existing = find(userId);
        if (existing.isPresent()) {
            return existing.get();
        }
        createIfAbsent(userId);
        return find(userId).orElseThrow(() -> new IllegalStateException("profile of user " + userId + " vanished after it was created"));
    }

    /**
     * Replaces the profile of the user: the three fields (absent or blank clears them) and the whole set of rated
     * skills.
     *
     * @throws FieldValidationException when a skill is listed twice or is not an active catalog skill
     */
    public ProfileResponse replace(UUID userId, UpdateProfileRequest request) {
        List<String> slugs = request.skills().stream().map(ProfileSkillRequest::skill).toList();
        rejectRepeatedSkills(slugs);
        List<SkillRef> resolved = catalog.requireActiveSkills(slugs, ProfileService::skillField);
        Map<Long, Integer> ratings = new LinkedHashMap<>();
        for (int i = 0; i < resolved.size(); i++) {
            ratings.put(resolved.get(i).id(), request.skills().get(i).selfRating());
        }
        createIfAbsent(userId);
        return writeTransaction.execute(status -> {
            CandidateProfile profile = profiles.findByUserIdForUpdate(userId)
                    .orElseThrow(() -> new IllegalStateException("profile of user " + userId + " vanished after it was created"));
            profile.replace(
                    blankToNull(request.headline()),
                    request.yearsExperience(),
                    blankToNull(request.jobTitle()),
                    ratings,
                    clock.instant());
            return toResponse(profile);
        });
    }

    private Optional<ProfileResponse> find(UUID userId) {
        return readTransaction.execute(status -> profiles.findByUserId(userId).map(this::toResponse));
    }

    private void createIfAbsent(UUID userId) {
        try {
            writeTransaction.executeWithoutResult(status -> {
                if (!profiles.existsByUserId(userId)) {
                    profiles.saveAndFlush(new CandidateProfile(userId, clock.instant()));
                }
            });
        } catch (DataIntegrityViolationException ex) {
            // A simultaneous request created it first: that is the outcome we wanted. Anything else (the user does
            // not exist) leaves no profile behind and is the original failure.
            if (!profiles.existsByUserId(userId)) {
                throw ex;
            }
        }
    }

    private ProfileResponse toResponse(CandidateProfile profile) {
        Map<Long, SkillRef> skills = catalog.skillsById(profile.getSkillRatings().keySet());
        List<ProfileSkillResponse> rated = new ArrayList<>();
        profile.getSkillRatings().forEach((skillId, rating) -> {
            SkillRef skill = skills.get(skillId);
            rated.add(new ProfileSkillResponse(skill.slug(), skill.name(), rating));
        });
        rated.sort(Comparator.comparing(ProfileSkillResponse::name).thenComparing(ProfileSkillResponse::skill));
        return new ProfileResponse(
                profile.getHeadline(), profile.getYearsExperience(), profile.getJobTitle(), rated, profile.getUpdatedAt());
    }

    private static void rejectRepeatedSkills(List<String> slugs) {
        Set<String> seen = new HashSet<>();
        List<FieldViolation> repeated = new ArrayList<>();
        for (int i = 0; i < slugs.size(); i++) {
            if (!seen.add(slugs.get(i))) {
                repeated.add(new FieldViolation(skillField(i), "Skill '" + slugs.get(i) + "' is listed more than once."));
            }
        }
        if (!repeated.isEmpty()) {
            throw new FieldValidationException(repeated);
        }
    }

    private static String skillField(int index) {
        return "skills[" + index + "].skill";
    }

    private static @Nullable String blankToNull(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return text.strip();
    }
}
