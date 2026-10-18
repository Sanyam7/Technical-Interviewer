package com.techinterviewer.resume;

import static com.techinterviewer.resume.ResumeFiles.RESUME;
import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.resume.ResumeExtraction.ExperienceDraft;
import com.techinterviewer.resume.ResumeExtraction.FactDraft;
import com.techinterviewer.resume.ResumeExtraction.PositionDraft;
import com.techinterviewer.resume.ResumeExtraction.StatedYears;
import com.techinterviewer.resume.domain.ExperienceKind;
import com.techinterviewer.resume.domain.ResumeExperience;
import com.techinterviewer.resume.domain.ResumeFact;
import com.techinterviewer.resume.domain.ResumePosition;
import com.techinterviewer.resume.domain.ResumeProfile;
import com.techinterviewer.resume.domain.YearsOfExperience;
import com.techinterviewer.resume.parse.ResumeText;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The guarantee that the profile holds nothing the résumé does not say (AI_ARCHITECTURE.md section 2.13). */
class ResumeProfileVerifierTest {

    private static final YearMonth NOW = YearMonth.of(2026, 10);
    private static final String TEXT = ResumeText.withoutContactDetails(ResumeText.clean(RESUME));
    private static final ResumeVocabulary VOCABULARY = ResumeVocabulary.load();

    /** A draft builder: everything empty unless set. */
    private static final class Draft {
        final List<PositionDraft> positions = new ArrayList<>();
        @Nullable StatedYears years;
        final List<FactDraft> technologies = new ArrayList<>();
        final List<FactDraft> skills = new ArrayList<>();
        final List<FactDraft> domains = new ArrayList<>();
        final List<ExperienceDraft> experiences = new ArrayList<>();
        String seen = TEXT;

        Draft experience(String kind, String text, @Nullable String context, String... technologies) {
            experiences.add(new ExperienceDraft(kind, text, context, List.of(technologies)));
            return this;
        }

        ResumeProfileVerifier.Result verify() {
            return ResumeProfileVerifier.verify(new ResumeExtraction(positions, years, technologies, skills, domains, experiences, seen, false,
                    "test-model", "v1", null), VOCABULARY, NOW);
        }
    }

    @Test
    void aFaithfulExtractionIsKeptWhole() {
        Draft draft = new Draft();
        draft.positions.add(new PositionDraft("Senior Software Engineer", "Acme Payments", "2021-03", "present",
                "Senior Software Engineer, Acme Payments - Mar 2021 to Present"));
        draft.positions.add(new PositionDraft("Software Engineer", "Globex", "2017-06", "2021-02", "Software Engineer, Globex - Jun 2017 to Feb 2021"));
        draft.years = new StatedYears(8, "Senior Backend Engineer with 8 years of experience building payment systems.");
        draft.technologies.add(new FactDraft("Postgres", "Java, Spring Boot, Kafka, PostgreSQL, Redis, Docker, Kubernetes"));
        draft.technologies.add(new FactDraft("Kafka", "Java, Spring Boot, Kafka, PostgreSQL, Redis, Docker, Kubernetes"));
        draft.skills.add(new FactDraft("API design", "Designed REST APIs for the order service in Java and Spring Boot."));
        draft.domains.add(new FactDraft("Payments", "Senior Backend Engineer with 8 years of experience building payment systems."));
        draft.experience("PROJECT", "Built a Spring Boot microservice using Kafka to process card payment events.", "Acme Payments", "Spring Boot", "Kafka");
        draft.experience("achievement", "Reduced p99 latency of the checkout API by 40% with Redis caching.", "Senior Software Engineer, Acme Payments");

        ResumeProfileVerifier.Result result = draft.verify();
        ResumeProfile profile = result.profile();

        assertThat(result.dropped()).isEmpty();
        assertThat(profile.positions()).extracting(ResumePosition::title, ResumePosition::start, ResumePosition::end)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Senior Software Engineer", YearMonth.of(2021, 3), null),
                        org.assertj.core.groups.Tuple.tuple("Software Engineer", YearMonth.of(2017, 6), YearMonth.of(2021, 2)));
        assertThat(profile.yearsOfExperience()).isEqualTo(new YearsOfExperience(8, YearsOfExperience.Basis.STATED,
                "Senior Backend Engineer with 8 years of experience building payment systems."));
        assertThat(profile.technologies()).extracting(ResumeFact::name).containsExactly("PostgreSQL", "Kafka", "Spring Boot", "Redis");
        assertThat(profile.skills()).extracting(ResumeFact::name).containsExactly("API design");
        assertThat(profile.domains()).extracting(ResumeFact::name).containsExactly("Payments");
        assertThat(profile.experiences()).extracting(ResumeExperience::id, ResumeExperience::kind, ResumeExperience::technologies)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("x1", ExperienceKind.PROJECT, List.of("Spring Boot", "Kafka")),
                        org.assertj.core.groups.Tuple.tuple("x2", ExperienceKind.ACHIEVEMENT, List.of("Redis")));
    }

    @Test
    void anExperienceTheResumeDoesNotStateIsDropped() {
        Draft draft = new Draft()
                .experience("PROJECT", "Built a Spring Boot microservice using Kafka to process card payment events.", null)
                .experience("PROJECT", "Built a Kubernetes operator in Go that autoscaled Kafka clusters.", null)
                .experience("ACHIEVEMENT", "Reduced latency of the checkout API by 40% using Redis.", null);

        ResumeProfileVerifier.Result result = draft.verify();

        assertThat(result.profile().experiences()).extracting(ResumeExperience::text)
                .containsExactly("Built a Spring Boot microservice using Kafka to process card payment events.");
        assertThat(result.dropped()).hasSize(2);
        assertThat(result.profile().droppedItems()).isEqualTo(2);
    }

    @Test
    void wordsScatteredOverTheResumeAreNotACopy() {
        // Every word occurs somewhere in the résumé, but not in this order: it is a summary, not the candidate's statement.
        Draft draft = new Draft().experience("PROJECT", "Built payment events microservice using Redis and Kafka", null);

        assertThat(draft.verify().profile().experiences()).isEmpty();
    }

    @Test
    void aFaithfulCopyMayDifferInCasePunctuationAndLineBreaks() {
        Draft draft = new Draft().experience("PROJECT", "built a spring boot microservice, using kafka — to process card payment events", null);

        assertThat(draft.verify().profile().experiences()).hasSize(1);
    }

    @Test
    void aTechnologyMustBeNamedByItsEvidence() {
        Draft draft = new Draft();
        draft.technologies.add(new FactDraft("Kubernetes", "Java, Spring Boot, Kafka, PostgreSQL, Redis, Docker, Kubernetes"));
        draft.technologies.add(new FactDraft("Terraform", "Java, Spring Boot, Kafka, PostgreSQL, Redis, Docker, Kubernetes"));
        draft.technologies.add(new FactDraft("Elixir", "Elixir, Phoenix, LiveView"));

        ResumeProfileVerifier.Result result = draft.verify();

        assertThat(result.profile().technologies()).extracting(ResumeFact::name).containsExactly("Kubernetes");
        assertThat(result.dropped()).hasSize(2);
    }

    @Test
    void anExperienceKeepsOnlyTheTechnologiesItsTextNames() {
        Draft draft = new Draft().experience("RESPONSIBILITY", "Designed REST APIs for the order service in Java and Spring Boot.", "Globex",
                "Java", "Spring Boot", "Kubernetes", "GraphQL");

        assertThat(draft.verify().profile().experiences().getFirst().technologies()).containsExactly("Java", "Spring Boot");
    }

    @Test
    void theVocabularyAddsTechnologiesTheExtractorOverlooked() {
        Draft draft = new Draft().experience("ACHIEVEMENT", "Led the migration of the ledger from MySQL to PostgreSQL with zero downtime.", null);

        assertThat(draft.verify().profile().experiences().getFirst().technologies()).containsExactly("PostgreSQL", "MySQL");
    }

    @Test
    void aContextTheResumeDoesNotNameIsRemovedButTheExperienceStays() {
        Draft draft = new Draft().experience("PROJECT", "Built a Spring Boot microservice using Kafka to process card payment events.",
                "Staff Engineer at Google");

        ResumeExperience kept = draft.verify().profile().experiences().getFirst();

        assertThat(kept.context()).isNull();
    }

    @Test
    void positionsNeedTheirTitleTheirYearsAndForACurrentRoleAWordSayingSo() {
        Draft draft = new Draft();
        draft.positions.add(new PositionDraft("Principal Engineer", "Acme Payments", "2021-03", null, "Senior Software Engineer, Acme Payments"));
        draft.positions.add(new PositionDraft("Software Engineer", "Globex", "2015-06", "2021-02", "Software Engineer, Globex - Jun 2017 to Feb 2021"));
        draft.positions.add(new PositionDraft("Software Engineer", "Globex", "2017-06", "2016-02", "Software Engineer, Globex - Jun 2017 to Feb 2021"));
        draft.positions.add(new PositionDraft("Software Engineer", "Initech", "2017-06", "2021-02", "Software Engineer, Globex - Jun 2017 to Feb 2021"));

        ResumeProfileVerifier.Result result = draft.verify();

        // Only the last survives (its invented employer removed): a title not in the résumé, a year it never mentions, an end
        // before the start.
        assertThat(result.profile().positions()).singleElement()
                .satisfies(position -> assertThat(position.organization()).isNull());
        assertThat(result.dropped()).hasSize(3);

        Draft notCurrent = new Draft();
        notCurrent.seen = "Software Engineer, Globex - Jun 2017 to Feb 2021\n" + "x".repeat(10);
        notCurrent.positions.add(new PositionDraft("Software Engineer", "Globex", "2017-06", "present", "Software Engineer, Globex - Jun 2017 to Feb 2021"));
        assertThat(notCurrent.verify().profile().positions()).isEmpty();
    }

    @Test
    void anInventedFigureIsReplacedByWhatTheResumeStatesInWords() {
        Draft invented = new Draft();
        invented.years = new StatedYears(12, "Senior Backend Engineer with 8 years of experience building payment systems.");

        ResumeProfileVerifier.Result result = invented.verify();

        assertThat(result.profile().yearsOfExperience()).isEqualTo(new YearsOfExperience(8, YearsOfExperience.Basis.STATED,
                "Senior Backend Engineer with 8 years of experience building payment systems."));
        assertThat(result.dropped()).singleElement().asString().contains("12 is not what the résumé states");
        // A model that gives no figure (qwen3.5:4b never did in the live run) gets the same answer from the pattern.
        assertThat(new Draft().verify().profile().yearsOfExperience().years()).isEqualTo(8);
    }

    @Test
    void withoutAStatedFigureTheYearsAreComputedFromThePositionsCountingOverlapsOnce() {
        Draft draft = new Draft();
        draft.seen = TEXT.replace("with 8 years of experience ", "");
        draft.positions.add(new PositionDraft("Senior Software Engineer", null, "Mar 2021", "present", "Senior Software Engineer, Acme Payments - Mar 2021 to Present"));
        draft.positions.add(new PositionDraft("Software Engineer", null, "06/2017", "2021-02", "Software Engineer, Globex - Jun 2017 to Feb 2021"));
        draft.positions.add(new PositionDraft("Software Engineer", null, "09/2017", "2021-01", "Software Engineer, Globex - Jun 2017 to Feb 2021"));
        draft.positions.add(new PositionDraft("Software Engineer", null, "2019", "2020", "Software Engineer, Globex - Jun 2017 to Feb 2021"));

        ResumeProfileVerifier.Result result = draft.verify();
        ResumeProfile profile = result.profile();

        // June 2017 to October 2026 is 9 years and 4 months; the role from September 2017 lies inside it. The last role's years are
        // not in the résumé at all, so it is dropped.
        assertThat(profile.yearsOfExperience()).isEqualTo(new YearsOfExperience(9, YearsOfExperience.Basis.POSITIONS, null));
        assertThat(profile.positions()).extracting(ResumePosition::start).containsExactly(YearMonth.of(2021, 3), YearMonth.of(2017, 6),
                YearMonth.of(2017, 9));
        assertThat(result.dropped()).singleElement().asString().contains("the start '2019' is not a date in the résumé");
    }

    @Test
    void theStatedYearsPatternReadsTheUsualPhrasings() {
        for (String phrase : List.of("5+ years of professional experience in web apps.", "Over 4 years of experience in data platforms.",
                "12 yrs experience")) {
            Draft draft = new Draft();
            draft.seen = phrase + "\nBuilt things.";
            assertThat(draft.verify().profile().yearsOfExperience()).as(phrase).isNotNull()
                    .extracting(YearsOfExperience::basis).isEqualTo(YearsOfExperience.Basis.STATED);
        }
        Draft none = new Draft();
        none.seen = "Led a team of 8 engineers for 3 quarters.";
        assertThat(none.verify().profile().yearsOfExperience()).isNull();
    }

    @Test
    void aRemovedInstructionSupportsNothing() {
        // The extractor saw the résumé with an injected passage replaced; an "experience" copied from that passage is not in it.
        Draft draft = new Draft().experience("ACHIEVEMENT", "Architected Google's global search infrastructure as principal engineer.", null);
        draft.seen = TEXT + "\n\n[removed: a passage addressed to the evaluator]";

        assertThat(draft.verify().profile().experiences()).isEmpty();
    }

    @Test
    void aDocumentWithNothingVerifiableHasNoSubstance() {
        Draft draft = new Draft();
        draft.skills.add(new FactDraft("Leadership", "Senior Backend Engineer with 8 years of experience building payment systems."));

        assertThat(draft.verify().profile().hasSubstance()).isFalse();
        assertThat(new Draft().experience("PROJECT", "Built a Spring Boot microservice using Kafka to process card payment events.", null)
                .verify().profile().hasSubstance()).isTrue();
    }

    @Test
    void repeatedExperiencesAreKeptOnce() {
        Draft draft = new Draft()
                .experience("PROJECT", "Built a Spring Boot microservice using Kafka to process card payment events.", null)
                .experience("RESPONSIBILITY", "Built a Spring Boot microservice using Kafka to process card payment events", null);

        assertThat(draft.verify().profile().experiences()).hasSize(1);
    }
}
