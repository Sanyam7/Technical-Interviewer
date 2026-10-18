package com.techinterviewer.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.TestcontainersConfiguration;
import com.techinterviewer.catalog.dto.OptionsResponse;
import com.techinterviewer.catalog.dto.OptionsResponse.InterviewTypeOption;
import com.techinterviewer.catalog.dto.SkillResponse;
import com.techinterviewer.catalog.dto.TopicResponse;
import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import com.techinterviewer.common.error.FieldValidationException;
import com.techinterviewer.common.error.FieldViolation;
import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.InterviewDifficulty;
import com.techinterviewer.interview.domain.InterviewType;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.TargetRole;
import com.techinterviewer.interview.engine.TopicRange;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CatalogServiceIT {

    @Autowired
    CatalogService catalog;

    @Autowired
    JdbcClient jdbc;

    CatalogTestData data;

    @BeforeEach
    void setUp() {
        data = new CatalogTestData(jdbc);
    }

    @AfterEach
    void tearDown() {
        data.cleanUp();
    }

    private static String field(int index) {
        return "skills[" + index + "]";
    }

    // ---- options --------------------------------------------------------------------------------------------

    @Test
    void optionsAreDerivedFromTheEnumsAndInterviewOptions() {
        OptionsResponse options = catalog.options();

        assertThat(options.roles()).containsExactly(TargetRole.BACKEND, TargetRole.FRONTEND, TargetRole.FULL_STACK);
        assertThat(options.experienceLevels())
                .containsExactly(ExperienceLevel.INTERN, ExperienceLevel.JUNIOR, ExperienceLevel.MID, ExperienceLevel.SENIOR, ExperienceLevel.STAFF);
        assertThat(options.difficulties()).containsExactly(InterviewDifficulty.EASY, InterviewDifficulty.MEDIUM, InterviewDifficulty.HARD);
        assertThat(options.durationsMinutes()).containsExactly(15, 30, 45, 60, 90);
        assertThat(options.codingLanguages()).containsExactly("java", "javascript", "typescript", "python", "go");
        assertThat(options.executableLanguages()).as("code execution is off in the tests").isEmpty();
        assertThat(options.interviewTypes()).containsExactly(
                new InterviewTypeOption(InterviewType.TECHNICAL, List.of(Section.INTRODUCTION, Section.QUESTIONING)),
                new InterviewTypeOption(InterviewType.CODING, List.of(Section.INTRODUCTION, Section.CODING)),
                new InterviewTypeOption(InterviewType.SYSTEM_DESIGN, List.of(Section.INTRODUCTION, Section.SYSTEM_DESIGN)),
                new InterviewTypeOption(InterviewType.FULL_LOOP,
                        List.of(Section.INTRODUCTION, Section.QUESTIONING, Section.CODING, Section.SYSTEM_DESIGN)));
    }

    // ---- skills and topics --------------------------------------------------------------------------------------

    @Test
    void theSeededCatalogHasTheNineSkills() {
        List<SkillResponse> skills = catalog.activeSkills();

        assertThat(skills).extracting(SkillResponse::slug, SkillResponse::name, skill -> skill.category().name())
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("java", "Java", "LANGUAGE"),
                        org.assertj.core.groups.Tuple.tuple("javascript", "JavaScript", "LANGUAGE"),
                        org.assertj.core.groups.Tuple.tuple("microservices", "Microservices", "CONCEPT"),
                        org.assertj.core.groups.Tuple.tuple("postgresql", "PostgreSQL", "DATABASE"),
                        org.assertj.core.groups.Tuple.tuple("react", "React", "FRAMEWORK"),
                        org.assertj.core.groups.Tuple.tuple("rest-api", "REST APIs", "CONCEPT"),
                        org.assertj.core.groups.Tuple.tuple("spring-boot", "Spring Boot", "FRAMEWORK"),
                        org.assertj.core.groups.Tuple.tuple("system-design", "System Design", "CONCEPT"),
                        org.assertj.core.groups.Tuple.tuple("typescript", "TypeScript", "LANGUAGE"));
        assertThat(skills).allSatisfy(skill -> {
            assertThat(skill.id()).isPositive();
            assertThat(skill.description()).isNotBlank();
        });
    }

    @Test
    void theSeededCatalogHas41TopicsSplitAcrossTheSkillsAsDesigned() {
        Map<String, Integer> expected = Map.of(
                "java", 7, "spring-boot", 7, "postgresql", 6, "system-design", 6, "react", 6, "typescript", 5, "javascript", 2,
                "rest-api", 1, "microservices", 1);

        expected.forEach((slug, count) -> assertThat(catalog.topicsOfSkill(slug)).as(slug).hasSize(count));
        assertThat(expected.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(41);
        assertThat(jdbc.sql("SELECT count(*) FROM topics").query(Long.class).single()).isEqualTo(41L);
    }

    @Test
    void topicsComeEasiestFirstAndByNameWithinADifficulty() {
        List<TopicResponse> topics = catalog.topicsOfSkill("java");

        assertThat(topics).extracting(TopicResponse::slug).containsExactly(
                "collections", "core-language-oop", "exceptions", "concurrency", "generics", "streams-functional", "jvm-memory-gc");
        TopicResponse generics = topics.get(4);
        assertThat(generics.name()).isEqualTo("Generics");
        assertThat(generics.description()).isEqualTo("Type parameters, bounded types, wildcards, type erasure and PECS.");
        assertThat(generics.minDifficulty()).isEqualTo(2);
        assertThat(generics.maxDifficulty()).isEqualTo(4);
    }

    @Test
    void aTopicSlugSharedByTwoSkillsIsResolvedWithinTheRequestedSkill() {
        TopicResponse java = catalog.topicsOfSkill("java").stream().filter(t -> t.slug().equals("generics")).findFirst().orElseThrow();
        TopicResponse typescript = catalog.topicsOfSkill("typescript").stream().filter(t -> t.slug().equals("generics")).findFirst().orElseThrow();

        assertThat(java.maxDifficulty()).isEqualTo(4);
        assertThat(typescript.maxDifficulty()).isEqualTo(5);
        assertThat(typescript.description()).startsWith("Generic functions");
    }

    @Test
    void anInactiveSkillIsHiddenFromTheListAndHasNoTopics() {
        long skill = data.skill("retired-skill", "Retired Skill", false);
        data.topic(skill, "old-topic", "Old topic", 1, 2, 1.0);
        long active = data.skill("fresh-skill", "Fresh Skill", true);

        assertThat(catalog.activeSkills()).extracting(SkillResponse::slug).contains("fresh-skill").doesNotContain("retired-skill");
        assertThatThrownBy(() -> catalog.topicsOfSkill("retired-skill"))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThat(catalog.topicsOfSkill("fresh-skill")).isEmpty();
        assertThat(active).isPositive();
    }

    @Test
    void aSkillThatIsDeactivatedDisappearsFromTheCatalog() {
        long skill = data.skill("soon-retired", "Soon Retired", true);
        assertThat(catalog.activeSkills()).extracting(SkillResponse::slug).contains("soon-retired");

        data.deactivate(skill);

        assertThat(catalog.activeSkills()).extracting(SkillResponse::slug).doesNotContain("soon-retired");
    }

    @Test
    void unknownSkillSlugsAreNotFoundAndMatchingIsExact() {
        for (String slug : List.of("cobol", "Java", "JAVA", " java", "java ", "", "spring")) {
            assertThatThrownBy(() -> catalog.topicsOfSkill(slug)).as("'" + slug + "'")
                    .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        }
    }

    // ---- resolving slugs ----------------------------------------------------------------------------------------

    @Test
    void slugsResolveToActiveSkillsInTheOrderGiven() {
        List<SkillRef> resolved = catalog.requireActiveSkills(List.of("react", "java", "postgresql"), CatalogServiceIT::field);

        assertThat(resolved).extracting(SkillRef::slug).containsExactly("react", "java", "postgresql");
        assertThat(resolved).extracting(SkillRef::name).containsExactly("React", "Java", "PostgreSQL");
        assertThat(resolved).extracting(SkillRef::id).doesNotHaveDuplicates().allSatisfy(id -> assertThat(id).isPositive());
        assertThat(jdbc.sql("SELECT id FROM skills WHERE slug = 'java'").query(Long.class).single()).isEqualTo(resolved.get(1).id());
    }

    @Test
    void anEmptyListResolvesToNothing() {
        assertThat(catalog.requireActiveSkills(List.of(), CatalogServiceIT::field)).isEmpty();
    }

    @Test
    void aRepeatedSlugIsResolvedOncePerOccurrence() {
        assertThat(catalog.requireActiveSkills(List.of("java", "java"), CatalogServiceIT::field)).extracting(SkillRef::slug)
                .containsExactly("java", "java");
    }

    @Test
    void unknownInactiveAndMisspelledSlugsAreAllReportedOnTheirOwnFieldAndNothingIsResolved() {
        data.skill("retired-skill", "Retired Skill", false);

        assertThatThrownBy(() -> catalog.requireActiveSkills(
                        List.of("java", "cobol", "retired-skill", "Java", "react"), CatalogServiceIT::field))
                .isInstanceOfSatisfying(FieldValidationException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ex.violations()).extracting(FieldViolation::field).containsExactly(field(1), field(2), field(3));
                    assertThat(ex.violations().get(0).message()).contains("'cobol'");
                    assertThat(ex.violations().get(1).message()).contains("'retired-skill'");
                    assertThat(ex.violations().get(2).message()).contains("'Java'");
                });
    }

    @Test
    void theFieldPathOfTheCallerIsUsedForEveryViolation() {
        assertThatThrownBy(() -> catalog.requireActiveSkills(List.of("nope"), index -> "configuration.skills[" + index + "].skill"))
                .isInstanceOfSatisfying(FieldValidationException.class, ex ->
                        assertThat(ex.violations()).extracting(FieldViolation::field).containsExactly("configuration.skills[0].skill"));
    }

    @Test
    void skillsAreLookedUpByIdIncludingRetiredOnes() {
        long retired = data.skill("retired-skill", "Retired Skill", false);
        long javaId = catalog.requireActiveSkills(List.of("java"), CatalogServiceIT::field).get(0).id();

        Map<Long, SkillRef> byId = catalog.skillsById(List.of(javaId, retired, 987_654_321L));

        assertThat(byId).containsOnlyKeys(javaId, retired);
        assertThat(byId.get(javaId)).isEqualTo(new SkillRef(javaId, "java", "Java"));
        assertThat(byId.get(retired).slug()).isEqualTo("retired-skill");
        assertThat(catalog.skillsById(List.of())).isEmpty();
    }

    // ---- topics for the planner ---------------------------------------------------------------------------------

    @Test
    void thePlannerGetsTheTopicsOfTheSelectedSkillsInTheOrderOfTheSkills() {
        List<SkillRef> skills = catalog.requireActiveSkills(List.of("typescript", "java"), CatalogServiceIT::field);

        List<PlannerTopic> topics = catalog.topicsForPlanning(skills.stream().map(SkillRef::id).toList());

        assertThat(topics).hasSize(5 + 7);
        assertThat(topics.subList(0, 5)).allSatisfy(topic -> assertThat(topic.skillSlug()).isEqualTo("typescript"));
        assertThat(topics.subList(5, 12)).allSatisfy(topic -> assertThat(topic.skillSlug()).isEqualTo("java"));
        assertThat(topics.subList(0, 5)).extracting(PlannerTopic::slug).containsExactly(
                "strictness-tooling", "type-system-basics", "generics", "narrowing-type-guards", "utility-mapped-conditional-types");
        assertThat(topics.get(0).skillName()).isEqualTo("TypeScript");
    }

    @Test
    void aPlannerTopicCarriesItsDifficultyWindowAndWeight() {
        long skill = data.skill("planner-skill", "Planner Skill", true);
        data.topic(skill, "heavy", "Heavy topic", 2, 5, 2.50);
        data.topic(skill, "light", "Light topic", 1, 1, 0.25);

        List<PlannerTopic> topics = catalog.topicsForPlanning(List.of(skill));

        assertThat(topics).containsExactly(
                new PlannerTopic("planner-skill", "Planner Skill", "light", "Light topic", 1, 1, 0.25),
                new PlannerTopic("planner-skill", "Planner Skill", "heavy", "Heavy topic", 2, 5, 2.5));
        assertThat(topics.get(1).key()).isEqualTo("planner-skill/heavy");
    }

    @Test
    void theSeededTopicsAreWeightedByHowCoreTheyAre() {
        long javaId = catalog.requireActiveSkills(List.of("java"), CatalogServiceIT::field).get(0).id();

        assertThat(catalog.topicsForPlanning(List.of(javaId))).extracting(PlannerTopic::slug, PlannerTopic::weight).contains(
                org.assertj.core.groups.Tuple.tuple("collections", 1.5),
                org.assertj.core.groups.Tuple.tuple("core-language-oop", 1.4),
                org.assertj.core.groups.Tuple.tuple("generics", 0.9));
    }

    @Test
    void retiredAndUnknownSkillsContributeNoTopicsToThePlanner() {
        long retired = data.skill("retired-skill", "Retired Skill", false);
        data.topic(retired, "old-topic", "Old topic", 1, 2, 1.0);
        long javaId = catalog.requireActiveSkills(List.of("java"), CatalogServiceIT::field).get(0).id();

        List<PlannerTopic> topics = catalog.topicsForPlanning(List.of(retired, 987_654_321L, javaId));

        assertThat(topics).hasSize(7).allSatisfy(topic -> assertThat(topic.skillSlug()).isEqualTo("java"));
    }

    @Test
    void aSkillIdGivenTwiceContributesItsTopicsOnce() {
        long javaId = catalog.requireActiveSkills(List.of("java"), CatalogServiceIT::field).get(0).id();

        assertThat(catalog.topicsForPlanning(List.of(javaId, javaId))).hasSize(7);
    }

    @Test
    void noSkillsMeansNoTopics() {
        assertThat(catalog.topicsForPlanning(List.of())).isEmpty();
    }

    // ---- stored topics --------------------------------------------------------------------------------------

    @Test
    void storedTopicsResolveByIdWithTheirSkill() {
        long skill = data.skill("zz-topic-skill", "ZZ Topic Skill", true);
        long topic = data.topic(skill, "zz-topic", "ZZ Topic", 1, 3, 1.0);

        Map<Long, TopicInfo> found = catalog.topicsById(List.of(topic, 999_999_999L));

        assertThat(found).containsOnlyKeys(topic);
        TopicInfo info = found.get(topic);
        assertThat(info.skillId()).isEqualTo(skill);
        assertThat(info.skillSlug()).isEqualTo("zz-topic-skill");
        assertThat(info.skillName()).isEqualTo("ZZ Topic Skill");
        assertThat(info.slug()).isEqualTo("zz-topic");
        assertThat(info.name()).isEqualTo("ZZ Topic");
        assertThat(info.key()).isEqualTo("zz-topic-skill/zz-topic");
    }

    @Test
    void storedTopicsStillResolveAfterTheirSkillWasRetired() {
        long skill = data.skill("zz-retired-skill", "ZZ Retired", true);
        long topic = data.topic(skill, "zz-retired-topic", "ZZ Retired Topic", 1, 3, 1.0);
        data.deactivate(skill);

        assertThat(catalog.topicsById(List.of(topic))).containsOnlyKeys(topic);
        assertThat(catalog.findTopic("zz-retired-skill", "zz-retired-topic")).isPresent();
        assertThat(catalog.topicsForPlanning(List.of(skill))).isEmpty();
    }

    @Test
    void findTopicNeedsBothSlugsToMatch() {
        assertThat(catalog.findTopic("java", "concurrency")).get().satisfies(info -> {
            assertThat(info.skillSlug()).isEqualTo("java");
            assertThat(info.slug()).isEqualTo("concurrency");
        });
        assertThat(catalog.findTopic("java", "no-such-topic")).isEmpty();
        assertThat(catalog.findTopic("no-such-skill", "concurrency")).isEmpty();
        assertThat(catalog.findTopic("react", "concurrency")).isEmpty();
    }

    @Test
    void noIdsMeansNoStoredTopics() {
        assertThat(catalog.topicsById(List.of())).isEmpty();
    }

    // ---- difficulty ranges for the turn --------------------------------------------------------------------------

    @Test
    void theRangeOfATopicIsItsStoredDifficultyWindowAndKeepsWorkingAfterItsSkillWasRetired() {
        long skill = data.skill("zz-range-skill", "ZZ Range", true);
        data.topic(skill, "zz-narrow", "ZZ Narrow", 2, 3, 1.0);
        data.topic(skill, "zz-wide", "ZZ Wide", 1, 5, 1.0);
        data.deactivate(skill);

        Map<String, TopicRange> ranges = catalog.topicRanges(List.of("zz-range-skill/zz-narrow", "zz-range-skill/zz-wide"));

        assertThat(ranges).containsOnlyKeys("zz-range-skill/zz-narrow", "zz-range-skill/zz-wide");
        assertThat(ranges.get("zz-range-skill/zz-narrow")).isEqualTo(new TopicRange(2, 3));
        assertThat(ranges.get("zz-range-skill/zz-wide")).isEqualTo(new TopicRange(1, 5));
    }

    @Test
    void aTopicSlugSharedByTwoSkillsGetsTheRangeOfTheSkillAskedFor() {
        long first = data.skill("zz-first", "ZZ First", true);
        long second = data.skill("zz-second", "ZZ Second", true);
        data.topic(first, "zz-shared", "ZZ Shared", 1, 2, 1.0);
        data.topic(second, "zz-shared", "ZZ Shared", 4, 5, 1.0);

        Map<String, TopicRange> ranges = catalog.topicRanges(List.of("zz-first/zz-shared", "zz-second/zz-shared"));

        assertThat(ranges.get("zz-first/zz-shared")).isEqualTo(new TopicRange(1, 2));
        assertThat(ranges.get("zz-second/zz-shared")).isEqualTo(new TopicRange(4, 5));
    }

    @Test
    void keysThatNameNothingAreAbsentAndMalformedKeysAreIgnored() {
        Map<String, TopicRange> ranges = catalog.topicRanges(List.of("java/concurrency", "java/no-such-topic", "no-such-skill/concurrency", "react/concurrency",
                "concurrency", "/concurrency", "java/", ""));

        assertThat(ranges).containsOnlyKeys("java/concurrency");
        assertThat(catalog.topicRanges(List.of())).isEmpty();
        assertThat(catalog.topicRanges(List.of("not-a-key"))).isEmpty();
    }
}
