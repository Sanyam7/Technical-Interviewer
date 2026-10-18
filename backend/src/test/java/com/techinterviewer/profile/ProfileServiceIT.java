package com.techinterviewer.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.techinterviewer.TestClock;
import com.techinterviewer.TestcontainersConfiguration;
import com.techinterviewer.common.error.ErrorCode;
import com.techinterviewer.common.error.FieldValidationException;
import com.techinterviewer.common.error.FieldViolation;
import com.techinterviewer.common.id.UuidV7;
import com.techinterviewer.profile.dto.ProfileResponse;
import com.techinterviewer.profile.dto.ProfileSkillRequest;
import com.techinterviewer.profile.dto.ProfileSkillResponse;
import com.techinterviewer.profile.dto.UpdateProfileRequest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import({TestcontainersConfiguration.class, ProfileServiceIT.FixedClock.class})
class ProfileServiceIT {

    /** The service reads the application clock; tests move this one. */
    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {

        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock();
        }
    }

    @Autowired
    ProfileService service;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestClock clock;

    ProfileTestUsers users;

    @BeforeEach
    void setUp() {
        users = new ProfileTestUsers(jdbc);
        clock.set(TestClock.START);
    }

    @AfterEach
    void tearDown() {
        users.cleanUp();
    }

    private static ProfileSkillRequest rated(String skill, int rating) {
        return new ProfileSkillRequest(skill, rating);
    }

    private static UpdateProfileRequest request(String headline, Integer years, String title, ProfileSkillRequest... skills) {
        return new UpdateProfileRequest(headline, years, title, List.of(skills));
    }

    private long profileCount(UUID user) {
        return jdbc.sql("SELECT count(*) FROM candidate_profiles WHERE user_id = :u").param("u", user).query(Long.class).single();
    }

    // ---- lazy creation --------------------------------------------------------------------------------------

    @Test
    void theFirstReadCreatesAnEmptyProfileAndLaterReadsReuseIt() {
        UUID user = users.create();
        assertThat(profileCount(user)).isZero();

        ProfileResponse first = service.get(user);
        ProfileResponse second = service.get(user);

        assertThat(first.headline()).isNull();
        assertThat(first.yearsExperience()).isNull();
        assertThat(first.jobTitle()).isNull();
        assertThat(first.skills()).isEmpty();
        assertThat(first.updatedAt()).isEqualTo(TestClock.START);
        assertThat(second).isEqualTo(first);
        assertThat(profileCount(user)).isEqualTo(1);
    }

    @Test
    void simultaneousFirstReadsCreateOneProfileAndAllSucceed() throws Exception {
        for (int round = 0; round < 5; round++) {
            UUID user = users.create();

            List<ProfileResponse> responses = runConcurrently(12, () -> service.get(user));

            assertThat(responses).hasSize(12).containsOnly(responses.get(0));
            assertThat(profileCount(user)).as("round " + round).isEqualTo(1);
        }
    }

    @Test
    void simultaneousFirstWritesCreateOneProfileAndTheLastWriterWinsCompletely() throws Exception {
        UUID user = users.create();
        List<UpdateProfileRequest> requests = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            requests.add(request("writer " + i, i, "title " + i, rated("java", 1 + i % 5), rated(i % 2 == 0 ? "react" : "typescript", 3)));
        }
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(requests.size());
        try {
            List<Future<ProfileResponse>> futures = new ArrayList<>();
            for (UpdateProfileRequest request : requests) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return service.replace(user, request);
                }));
            }
            go.countDown();
            for (Future<ProfileResponse> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        ProfileResponse stored = service.get(user);
        assertThat(profileCount(user)).isEqualTo(1);
        int winner = Integer.parseInt(stored.headline().substring("writer ".length()));
        assertThat(stored).isEqualTo(service.get(user));
        assertThat(stored.yearsExperience()).isEqualTo(winner);
        assertThat(stored.jobTitle()).isEqualTo("title " + winner);
        assertThat(stored.skills()).extracting(ProfileSkillResponse::skill, ProfileSkillResponse::selfRating)
                .containsExactlyInAnyOrder(
                        tuple("java", 1 + winner % 5),
                        tuple(winner % 2 == 0 ? "react" : "typescript", 3));
    }

    @Test
    void aUserThatDoesNotExistGetsNoProfileAndTheFailureIsNotSwallowed() {
        UUID ghost = UuidV7.generate();

        assertThatThrownBy(() -> service.get(ghost)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> service.replace(ghost, request("h", 1, "t"))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(profileCount(ghost)).isZero();
    }

    // ---- replacing ------------------------------------------------------------------------------------------

    @Test
    void replaceStoresEverythingAndAnswersWithSlugsNamesAndRatingsByName() {
        UUID user = users.create();

        ProfileResponse response = service.replace(user, request("Backend engineer", 7, "Staff Engineer",
                rated("spring-boot", 5), rated("java", 4), rated("postgresql", 3)));

        assertThat(response.headline()).isEqualTo("Backend engineer");
        assertThat(response.yearsExperience()).isEqualTo(7);
        assertThat(response.jobTitle()).isEqualTo("Staff Engineer");
        assertThat(response.skills()).containsExactly(
                new ProfileSkillResponse("java", "Java", 4),
                new ProfileSkillResponse("postgresql", "PostgreSQL", 3),
                new ProfileSkillResponse("spring-boot", "Spring Boot", 5));
        assertThat(service.get(user)).isEqualTo(response);
    }

    @Test
    void replaceReplacesTheWholeSetAndClearsWhatIsNotSent() {
        UUID user = users.create();
        service.replace(user, request("first", 3, "first title", rated("java", 4), rated("react", 2)));

        ProfileResponse second = service.replace(user, request(null, null, null, rated("react", 5), rated("typescript", 1)));

        assertThat(second.headline()).isNull();
        assertThat(second.yearsExperience()).isNull();
        assertThat(second.jobTitle()).isNull();
        assertThat(second.skills()).containsExactly(new ProfileSkillResponse("react", "React", 5), new ProfileSkillResponse("typescript", "TypeScript", 1));

        ProfileResponse empty = service.replace(user, request("only text", 0, "t"));
        assertThat(empty.skills()).isEmpty();
        assertThat(empty.yearsExperience()).isZero();
        assertThat(profileCount(user)).isEqualTo(1);
    }

    @Test
    void blankTextIsStoredAsAbsentAndOtherTextIsTrimmed() {
        UUID user = users.create();

        ProfileResponse blank = service.replace(user, request("   ", 2, "\t"));
        ProfileResponse padded = service.replace(user, request("  Hello  ", 2, " Engineer "));

        assertThat(blank.headline()).isNull();
        assertThat(blank.jobTitle()).isNull();
        assertThat(padded.headline()).isEqualTo("Hello");
        assertThat(padded.jobTitle()).isEqualTo("Engineer");
    }

    @Test
    void textAtTheLengthLimitsIsAccepted() {
        UUID user = users.create();

        ProfileResponse response = service.replace(user, request("h".repeat(120), 60, "t".repeat(60)));

        assertThat(response.headline()).hasSize(120);
        assertThat(response.jobTitle()).hasSize(60);
        assertThat(response.yearsExperience()).isEqualTo(60);
    }

    @Test
    void replaceMovesUpdatedAtWithTheClockAndLeavesCreatedAtAlone() {
        UUID user = users.create();
        service.get(user);
        clock.advance(Duration.ofMinutes(42));

        ProfileResponse response = service.replace(user, request("h", 1, "t"));

        assertThat(response.updatedAt()).isEqualTo(TestClock.START.plus(Duration.ofMinutes(42)));
        assertThat(jdbc.sql("SELECT created_at FROM candidate_profiles WHERE user_id = :u").param("u", user)
                .query(OffsetDateTime.class).single().toInstant()).isEqualTo(TestClock.START);
    }

    @Test
    void aSkillListedTwiceIsRejectedOnTheRepeatedEntryAndNothingIsStored() {
        UUID user = users.create();

        assertThatThrownBy(() -> service.replace(user, request("h", 1, "t", rated("java", 3), rated("react", 3), rated("java", 5))))
                .isInstanceOfSatisfying(FieldValidationException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ex.violations()).extracting(FieldViolation::field).containsExactly("skills[2].skill");
                    assertThat(ex.violations().get(0).message()).contains("'java'").contains("more than once");
                });
        assertThat(profileCount(user)).isZero();
    }

    @Test
    void unknownAndRetiredSkillsAreRejectedTogetherOnTheirOwnFieldAndTheProfileStaysAsItWas() {
        UUID user = users.create();
        service.replace(user, request("keep", 5, "keep", rated("java", 4)));
        long retired = jdbc.sql("INSERT INTO skills (slug, name, category, active) VALUES ('retired-skill', 'Retired', 'TOOL', false) RETURNING id")
                .query(Long.class).single();
        try {
            assertThatThrownBy(() -> service.replace(user, request("new", 1, "new",
                    rated("react", 3), rated("cobol", 3), rated("retired-skill", 2), rated("java", 1))))
                    .isInstanceOfSatisfying(FieldValidationException.class, ex ->
                            assertThat(ex.violations()).extracting(FieldViolation::field).containsExactly("skills[1].skill", "skills[2].skill"));
            ProfileResponse unchanged = service.get(user);
            assertThat(unchanged.headline()).isEqualTo("keep");
            assertThat(unchanged.skills()).containsExactly(new ProfileSkillResponse("java", "Java", 4));
        } finally {
            jdbc.sql("DELETE FROM skills WHERE id = :id").param("id", retired).update();
        }
    }

    @Test
    void aRejectedFirstWriteDoesNotCreateAProfile() {
        UUID user = users.create();

        assertThatThrownBy(() -> service.replace(user, request("h", 1, "t", rated("cobol", 3)))).isInstanceOf(FieldValidationException.class);

        assertThat(profileCount(user)).isZero();
    }

    @Test
    void aSkillRetiredAfterItWasRatedStaysOnTheProfile() {
        UUID user = users.create();
        service.replace(user, request("h", 1, "t", rated("java", 4)));
        long extra = jdbc.sql("INSERT INTO skills (slug, name, category, active) VALUES ('soon-retired', 'Soon Retired', 'TOOL', true) RETURNING id")
                .query(Long.class).single();
        try {
            service.replace(user, request("h", 1, "t", rated("java", 4), rated("soon-retired", 2)));
            jdbc.sql("UPDATE skills SET active = false WHERE id = :id").param("id", extra).update();

            assertThat(service.get(user).skills()).extracting(ProfileSkillResponse::skill).containsExactly("java", "soon-retired");
        } finally {
            jdbc.sql("DELETE FROM candidate_profile_skills WHERE skill_id = :id").param("id", extra).update();
            jdbc.sql("DELETE FROM skills WHERE id = :id").param("id", extra).update();
        }
    }

    // ---- isolation ------------------------------------------------------------------------------------------

    @Test
    void twoUsersNeverSeeOrChangeEachOthersProfileWhenTheirRequestsInterleave() {
        UUID ada = users.create();
        UUID grace = users.create();

        service.replace(ada, request("Ada", 10, "Analyst", rated("java", 5)));
        assertThat(service.get(grace).headline()).isNull();
        service.replace(grace, request("Grace", 20, "Admiral", rated("react", 2), rated("typescript", 3)));
        service.replace(ada, request("Ada v2", 11, "Analyst II", rated("postgresql", 4)));

        ProfileResponse adaView = service.get(ada);
        ProfileResponse graceView = service.get(grace);
        assertThat(adaView.headline()).isEqualTo("Ada v2");
        assertThat(adaView.skills()).containsExactly(new ProfileSkillResponse("postgresql", "PostgreSQL", 4));
        assertThat(graceView.headline()).isEqualTo("Grace");
        assertThat(graceView.yearsExperience()).isEqualTo(20);
        assertThat(graceView.skills()).extracting(ProfileSkillResponse::skill).containsExactly("react", "typescript");
        assertThat(profileCount(ada)).isEqualTo(1);
        assertThat(profileCount(grace)).isEqualTo(1);
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private <T> List<T> runConcurrently(int threads, Callable<T> task) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return task.call();
                }));
            }
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
