package com.techinterviewer.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.TestcontainersConfiguration;
import com.techinterviewer.common.id.UuidV7;
import com.techinterviewer.interview.domain.AnswerSummary;
import com.techinterviewer.interview.domain.Claim;
import com.techinterviewer.interview.domain.ExpectedKeyPoint;
import com.techinterviewer.interview.domain.FollowUpMode;
import com.techinterviewer.interview.domain.InterviewMemory;
import com.techinterviewer.interview.domain.PendingStep;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.SectionProgress;
import com.techinterviewer.interview.domain.TopicStat;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jsonprobe.PlainEntity;
import jsonprobe.PlainRepository;
import jsonprobe.ProbeDocument;
import jsonprobe.ProbeEntity;
import jsonprobe.ProbeRepository;
import org.hibernate.SessionFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.stat.Statistics;
import org.hibernate.type.format.FormatMapper;
import org.hibernate.type.format.jackson.Jackson3JsonFormatMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hibernate against a real PostgreSQL with the infrastructure of this application: UUIDv7 ids assigned in the
 * constructor, {@code Persistable} so that {@code save} inserts without a select, and jsonb columns written and read
 * by the application's Jackson 3 mapper. The probe entities live in their own package and the table is created by
 * Hibernate for this context only (the real schema is owned by Flyway and checked by {@code SchemaMigrationIT}).
 */
@SpringBootTest(properties = {
    // The repositories of the application are not part of this context (only the probe repositories are), so beans
    // that depend on them must not be created eagerly.
    "spring.main.lazy-initialization=true",
    "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.properties.hibernate.generate_statistics=true"
})
@Import({TestcontainersConfiguration.class, PersistenceInfrastructureIT.ProbeConfig.class})
class PersistenceInfrastructureIT {

    @TestConfiguration(proxyBeanMethods = false)
    @EntityScan(basePackages = "jsonprobe")
    @EnableJpaRepositories(basePackages = "jsonprobe")
    static class ProbeConfig {
    }

    private static final Instant AT = Instant.parse("2026-10-05T14:03:00Z");

    @Autowired
    ProbeRepository probes;

    @Autowired
    PlainRepository plains;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Autowired
    EntityManager entityManager;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FormatMapper hibernateJsonFormatMapper;

    private Statistics statistics;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from probe_documents");
        jdbc.update("delete from probe_plain");
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
    }

    private static ProbeDocument document() {
        UUID question = UUID.fromString("0192f1c2-7a3b-7c11-9d4e-5f6a7b8c9d0f");
        PendingStep pending = PendingStep.followUp(Section.QUESTIONING, "java/concurrency", 4, question, FollowUpMode.PROBE_GAP, "kp1", null);
        InterviewMemory memory = new InterviewMemory(
                Map.of("java/concurrency", new TopicStat(2, 71.5, 3, 78)),
                List.of("java/concurrency:happens-before"),
                List.of(new Claim("Used Kafka", false, UUID.fromString("0192f1c2-7a3b-7c11-9d4e-5f6a7b8c9d0e"))),
                List.of(new AnswerSummary(question, "java/concurrency", "Explained volatile.", 78)),
                Map.of("OFF_TOPIC", 1),
                Map.of(Section.QUESTIONING, new SectionProgress(3, AT)),
                pending);
        return new ProbeDocument(
                AT,
                Section.SYSTEM_DESIGN,
                Map.of(Section.QUESTIONING, 3, Section.CODING, 1),
                List.of(new ExpectedKeyPoint("kp1", "volatile guarantees visibility", List.of(UUID.randomUUID()))),
                memory);
    }

    private UUID saveProbe() {
        return transaction.execute(status -> probes.saveAndFlush(new ProbeEntity("probe", document())).getId());
    }

    // ---- the JSON mapper ----------------------------------------------------------------------------------------------

    @Test
    void hibernateUsesTheApplicationsJackson3FormatMapperForJsonColumns() {
        SessionFactoryImplementor sessionFactory = entityManagerFactory.unwrap(SessionFactoryImplementor.class);

        FormatMapper used = sessionFactory.getTypeConfiguration().getJsonFormatMapper();

        assertThat(used).isSameAs(hibernateJsonFormatMapper).isInstanceOf(Jackson3JsonFormatMapper.class);
        assertThat(sessionFactory.getProperties()).containsEntry("hibernate.type.json_format_mapper", hibernateJsonFormatMapper);
    }

    @Test
    void aRecordWithInstantsEnumsMapsAndListsSurvivesAJsonbRoundTrip() {
        ProbeDocument original = document();
        UUID id = transaction.execute(status -> probes.saveAndFlush(new ProbeEntity("probe", original)).getId());
        entityManager.clear();

        ProbeEntity loaded = transaction.execute(status -> probes.findById(id).orElseThrow());

        assertThat(loaded.doc().memory()).isEqualTo(original.memory());
        assertThat(loaded.doc().keyPoints()).isEqualTo(original.keyPoints());
        assertThat(loaded.doc()).isEqualTo(original);
        assertThat(loaded.doc().at()).isEqualTo(AT);
        assertThat(loaded.doc().section()).isEqualTo(Section.SYSTEM_DESIGN);
        assertThat(loaded.doc().perSection()).containsEntry(Section.QUESTIONING, 3).containsEntry(Section.CODING, 1);
        assertThat(loaded.doc().memory().pendingStep().followUpMode()).isEqualTo(FollowUpMode.PROBE_GAP);
        assertThat(loaded.doc().memory().sectionProgress().get(Section.QUESTIONING).startedAt()).isEqualTo(AT);
    }

    @Test
    void theColumnReallyIsJsonbAndHoldsIsoTimestampsAndEnumNames() {
        UUID id = saveProbe();

        assertThat(jdbc.queryForObject("select jsonb_typeof(doc) from probe_documents where id = ?", String.class, id)).isEqualTo("object");
        assertThat(jdbc.queryForObject("select doc ->> 'at' from probe_documents where id = ?", String.class, id)).isEqualTo("2026-10-05T14:03:00Z");
        assertThat(jdbc.queryForObject("select doc ->> 'section' from probe_documents where id = ?", String.class, id)).isEqualTo("SYSTEM_DESIGN");
        assertThat(jdbc.queryForObject("select doc #>> '{memory,sectionProgress,QUESTIONING,startedAt}' from probe_documents where id = ?", String.class, id))
                .isEqualTo("2026-10-05T14:03:00Z");
        assertThat(jdbc.queryForObject("select doc #>> '{memory,pendingStep,followUpMode}' from probe_documents where id = ?", String.class, id))
                .isEqualTo("PROBE_GAP");
        assertThat(jdbc.queryForObject("select jsonb_exists(doc -> 'memory' -> 'pendingStep', 'targetClaim') from probe_documents where id = ?", Boolean.class, id))
                .as("absent members are omitted, not stored as null").isFalse();
    }

    @Test
    void aStoredDocumentWithAnUnknownPropertyStillLoads() {
        UUID id = saveProbe();
        jdbc.update("update probe_documents set doc = doc || '{\"addedByANewerVersion\": {\"x\": 1}}'::jsonb where id = ?", id);
        entityManager.clear();

        ProbeEntity loaded = transaction.execute(status -> probes.findById(id).orElseThrow());

        assertThat(loaded.doc().section()).isEqualTo(Section.SYSTEM_DESIGN);
    }

    @Test
    void aStoredDocumentThatBreaksAnInvariantFailsToLoadInsteadOfLoadingHalfValid() {
        UUID id = saveProbe();
        jdbc.update("update probe_documents set doc = jsonb_set(doc, '{memory,topicStats,java/concurrency,level}', '9') where id = ?", id);
        entityManager.clear();

        assertThatThrownBy(() -> transaction.execute(status -> probes.findById(id).orElseThrow()))
                .hasStackTraceContaining("level must be between 1 and 5");
    }

    // ---- BaseEntity ---------------------------------------------------------------------------------------------------------

    @Test
    void anEntityHasAUuidV7IdFromTheMomentItIsCreated() {
        ProbeEntity entity = new ProbeEntity("a", document());

        assertThat(entity.getId()).isNotNull();
        assertThat(UuidV7.isV7(entity.getId())).isTrue();
        assertThat(entity.isNew()).isTrue();
    }

    @Test
    void anEntityIsNotNewOnceItIsPersistedOrLoaded() {
        ProbeEntity entity = new ProbeEntity("a", document());

        transaction.executeWithoutResult(status -> {
            probes.saveAndFlush(entity);
            assertThat(entity.isNew()).isFalse();
        });
        entityManager.clear();
        ProbeEntity loaded = transaction.execute(status -> probes.findById(entity.getId()).orElseThrow());

        assertThat(loaded.isNew()).isFalse();
        assertThat(loaded).isNotSameAs(entity);
    }

    @Test
    void savingANewEntityIsASingleInsertWhereAnAssignedIdWithoutPersistableSelectsFirst() {
        transaction.executeWithoutResult(status -> probes.saveAndFlush(new ProbeEntity("a", document())));
        assertThat(statistics.getEntityInsertCount()).isEqualTo(1);
        assertThat(statistics.getPrepareStatementCount()).as("one insert, no select before it").isEqualTo(1);

        statistics.clear();
        transaction.executeWithoutResult(status -> plains.saveAndFlush(new PlainEntity()));

        assertThat(statistics.getEntityInsertCount()).isEqualTo(1);
        assertThat(statistics.getPrepareStatementCount()).as("control: without Persistable, save() merges: select, then insert").isEqualTo(2);
    }

    @Test
    void savingAnExistingEntityAgainUpdatesInsteadOfInsertingADuplicate() {
        UUID id = saveProbe();
        entityManager.clear();

        transaction.executeWithoutResult(status -> {
            ProbeEntity loaded = probes.findById(id).orElseThrow();
            probes.saveAndFlush(loaded);
        });

        assertThat(jdbc.queryForObject("select count(*) from probe_documents", Integer.class)).isEqualTo(1);
    }

    @Test
    void entitiesAreEqualByIdAcrossSessionsAndDifferentIdsAreDifferentEntities() {
        UUID id = saveProbe();
        entityManager.clear();

        ProbeEntity first = transaction.execute(status -> probes.findById(id).orElseThrow());
        ProbeEntity second = transaction.execute(status -> probes.findById(id).orElseThrow());
        ProbeEntity other = new ProbeEntity("other", document());

        assertThat(first).isNotSameAs(second).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(first).isNotEqualTo(other);
        assertThat(first).isNotEqualTo(null).isNotEqualTo("a string");
        assertThat(first.toString()).contains("ProbeEntity").contains(id.toString());
    }

    @Test
    void theHashCodeDoesNotChangeWhenTheEntityIsPersisted() {
        ProbeEntity entity = new ProbeEntity("a", document());
        int before = entity.hashCode();

        transaction.executeWithoutResult(status -> probes.saveAndFlush(entity));

        assertThat(entity.hashCode()).isEqualTo(before);
    }

    @Test
    void idsCreatedOverTimeComeBackFromPostgresInCreationOrder() throws Exception {
        List<UUID> created = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            ProbeEntity entity = new ProbeEntity("n" + i, document());
            created.add(entity.getId());
            transaction.executeWithoutResult(status -> probes.save(entity));
            Thread.sleep(2);
        }

        List<UUID> byIndexOrder = jdbc.queryForList("select id from probe_documents order by id", UUID.class);

        assertThat(byIndexOrder).containsExactlyElementsOf(created);
    }
}
