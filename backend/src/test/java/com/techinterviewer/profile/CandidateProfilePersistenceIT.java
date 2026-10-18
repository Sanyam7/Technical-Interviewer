package com.techinterviewer.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.TestClock;
import com.techinterviewer.TestcontainersConfiguration;
import com.techinterviewer.common.id.UuidV7;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The profile entity against the real schema (Hibernate validates the mapping when the context starts): what is
 * written is what the tables contain, and what the tables contain is what is read back.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CandidateProfilePersistenceIT {

    private static final Instant CREATED = TestClock.START;
    private static final Instant LATER = CREATED.plus(Duration.ofHours(2));

    @Autowired
    ProfileRepository profiles;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    EntityManager entityManager;

    ProfileTestUsers users;
    long java;
    long react;
    long typescript;

    @BeforeEach
    void setUp() {
        users = new ProfileTestUsers(jdbc);
        java = skillId("java");
        react = skillId("react");
        typescript = skillId("typescript");
    }

    @AfterEach
    void tearDown() {
        users.cleanUp();
    }

    private long skillId(String slug) {
        return jdbc.sql("SELECT id FROM skills WHERE slug = :slug").param("slug", slug).query(Long.class).single();
    }

    private void inTransaction(Consumer<CandidateProfile> action, UUID profileId) {
        transaction.executeWithoutResult(status -> action.accept(profiles.findById(profileId).orElseThrow()));
    }

    private Map<Long, Integer> storedRatings(UUID profileId) {
        return jdbc.sql("SELECT skill_id, self_rating FROM candidate_profile_skills WHERE profile_id = :id")
                .param("id", profileId)
                .query((rs, row) -> Map.entry(rs.getLong("skill_id"), rs.getInt("self_rating")))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Test
    void aProfileWithRatedSkillsRoundTripsThroughTheDatabase() {
        UUID user = users.create();
        CandidateProfile profile = new CandidateProfile(user, CREATED);
        profile.replace("Backend engineer", 7, "Staff Engineer", Map.of(java, 4, react, 2), LATER);
        profiles.saveAndFlush(profile);
        entityManager.clear();

        CandidateProfile loaded = transaction.execute(status -> {
            CandidateProfile found = profiles.findByUserId(user).orElseThrow();
            found.getSkillRatings();
            return found;
        });

        assertThat(loaded.getId()).isEqualTo(profile.getId());
        assertThat(loaded.getUserId()).isEqualTo(user);
        assertThat(loaded.getHeadline()).isEqualTo("Backend engineer");
        assertThat(loaded.getYearsExperience()).isEqualTo(7);
        assertThat(loaded.getJobTitle()).isEqualTo("Staff Engineer");
        assertThat(loaded.getSkillRatings()).containsExactlyInAnyOrderEntriesOf(Map.of(java, 4, react, 2));
        assertThat(loaded.getCreatedAt()).isEqualTo(CREATED);
        assertThat(loaded.getUpdatedAt()).isEqualTo(LATER);
        assertThat(storedRatings(profile.getId())).containsExactlyInAnyOrderEntriesOf(Map.of(java, 4, react, 2));
    }

    @Test
    void anEmptyProfileStoresNullsAndNoSkillRows() {
        UUID user = users.create();
        CandidateProfile profile = profiles.saveAndFlush(new CandidateProfile(user, CREATED));

        Map<String, Object> row = jdbc.sql("SELECT headline, years_experience, job_title FROM candidate_profiles WHERE id = :id")
                .param("id", profile.getId()).query().singleRow();

        assertThat(row.values()).containsOnlyNulls();
        assertThat(storedRatings(profile.getId())).isEmpty();
    }

    @Test
    void replacingTheSkillsRewritesTheRowsToExactlyTheNewSet() {
        UUID user = users.create();
        UUID id = profiles.saveAndFlush(profileWith(user, Map.of(java, 3, react, 4))).getId();

        inTransaction(profile -> profile.replace("h", 1, "t", Map.of(react, 5, typescript, 1), LATER), id);

        assertThat(storedRatings(id)).containsExactlyInAnyOrderEntriesOf(Map.of(react, 5, typescript, 1));
        assertThat(jdbc.sql("SELECT updated_at FROM candidate_profiles WHERE id = :id").param("id", id).query(OffsetDateTime.class).single().toInstant())
                .isEqualTo(LATER);
        assertThat(jdbc.sql("SELECT created_at FROM candidate_profiles WHERE id = :id").param("id", id).query(OffsetDateTime.class).single().toInstant())
                .isEqualTo(CREATED);
    }

    @Test
    void replacingWithNoSkillsRemovesEveryRow() {
        UUID user = users.create();
        UUID id = profiles.saveAndFlush(profileWith(user, Map.of(java, 3, react, 4))).getId();

        inTransaction(profile -> profile.replace(null, null, null, Map.of(), LATER), id);

        assertThat(storedRatings(id)).isEmpty();
        assertThat(profiles.findById(id).orElseThrow().getHeadline()).isNull();
    }

    @Test
    void changingOnlyARatingUpdatesThatRatingAndKeepsTheOtherRows() {
        UUID user = users.create();
        UUID id = profiles.saveAndFlush(profileWith(user, Map.of(java, 3, react, 4))).getId();

        inTransaction(profile -> profile.replace("h", 1, "t", Map.of(java, 5, react, 4), LATER), id);

        assertThat(storedRatings(id)).containsExactlyInAnyOrderEntriesOf(Map.of(java, 5, react, 4));
    }

    @Test
    void aUserHasAtMostOneProfile() {
        UUID user = users.create();
        profiles.saveAndFlush(new CandidateProfile(user, CREATED));

        assertThatThrownBy(() -> profiles.saveAndFlush(new CandidateProfile(user, LATER)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM candidate_profiles WHERE user_id = :u").param("u", user).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void twoUsersHaveSeparateProfilesAndRatings() {
        UUID ada = users.create();
        UUID grace = users.create();
        UUID adaProfile = profiles.saveAndFlush(profileWith(ada, Map.of(java, 5))).getId();
        UUID graceProfile = profiles.saveAndFlush(profileWith(grace, Map.of(react, 2))).getId();

        assertThat(storedRatings(adaProfile)).containsExactlyEntriesOf(Map.of(java, 5));
        assertThat(storedRatings(graceProfile)).containsExactlyEntriesOf(Map.of(react, 2));
        assertThat(profiles.findByUserId(ada).orElseThrow().getId()).isEqualTo(adaProfile);
        assertThat(profiles.findByUserId(grace).orElseThrow().getId()).isEqualTo(graceProfile);
        assertThat(profiles.existsByUserId(ada)).isTrue();
        assertThat(profiles.existsByUserId(UuidV7.generate())).isFalse();
        assertThat(profiles.findByUserId(UuidV7.generate())).isEmpty();
    }

    @Test
    void aProfileOfAnUnknownUserIsRejectedByTheDatabase() {
        assertThatThrownBy(() -> profiles.saveAndFlush(new CandidateProfile(UuidV7.generate(), CREATED)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aRatedSkillThatIsNotInTheCatalogIsRejectedByTheDatabase() {
        UUID user = users.create();

        assertThatThrownBy(() -> profiles.saveAndFlush(profileWith(user, Map.of(987_654_321L, 3))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingTheUserDeletesTheProfileAndItsRatings() {
        UUID user = users.create();
        UUID id = profiles.saveAndFlush(profileWith(user, Map.of(java, 3, react, 4))).getId();

        jdbc.sql("DELETE FROM users WHERE id = :id").param("id", user).update();

        assertThat(profiles.findById(id)).isEmpty();
        assertThat(storedRatings(id)).isEmpty();
    }

    @Test
    void maximumLengthFieldsZeroYearsAndEverySeededSkillAreStored() {
        UUID user = users.create();
        Map<Long, Integer> allSkills = new LinkedHashMap<>();
        List<Long> skillIds = jdbc.sql("SELECT id FROM skills ORDER BY id").query(Long.class).list();
        skillIds.forEach(id -> allSkills.put(id, 1));
        CandidateProfile profile = new CandidateProfile(user, CREATED);
        profile.replace("h".repeat(120), 0, "t".repeat(60), allSkills, LATER);

        UUID id = profiles.saveAndFlush(profile).getId();
        entityManager.clear();

        CandidateProfile loaded = profiles.findById(id).orElseThrow();
        assertThat(loaded.getHeadline()).hasSize(120);
        assertThat(loaded.getYearsExperience()).isZero();
        assertThat(loaded.getJobTitle()).hasSize(60);
        assertThat(storedRatings(id)).hasSize(9);
    }

    private CandidateProfile profileWith(UUID user, Map<Long, Integer> ratings) {
        CandidateProfile profile = new CandidateProfile(user, CREATED);
        profile.replace("h", 1, "t", ratings, CREATED);
        return profile;
    }
}
