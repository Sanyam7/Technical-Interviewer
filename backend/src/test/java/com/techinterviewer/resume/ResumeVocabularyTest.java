package com.techinterviewer.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ResumeVocabularyTest {

    private final ResumeVocabulary vocabulary = ResumeVocabulary.load();

    /** The skill/topic keys of the seeded catalog, read from the repeatable migration. */
    private static Set<String> catalogTopics() throws IOException {
        String seed;
        try (var in = ResumeVocabularyTest.class.getClassLoader().getResourceAsStream("db/migration/R__seed_catalog.sql")) {
            seed = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        Matcher topic = Pattern.compile("\\('([a-z0-9-]+)', '([a-z0-9-]+)', '[^']+', '[^']*', \\d::smallint").matcher(seed);
        Set<String> keys = new TreeSet<>();
        while (topic.find()) {
            keys.add(topic.group(1) + "/" + topic.group(2));
        }
        return keys;
    }

    @Test
    void everyTopicAndSkillItNamesIsInTheCatalog() throws IOException {
        Set<String> catalog = catalogTopics();

        assertThat(catalog).hasSize(41);
        assertThat(catalog).containsAll(vocabulary.topicKeys());
        assertThat(vocabulary.skills()).allMatch(skill -> catalog.stream().anyMatch(key -> key.startsWith(skill + "/")));
        assertThat(vocabulary.keywords().keySet()).as("every catalog topic can be linked by a keyword").isEqualTo(catalog);
    }

    @Test
    void aliasesNameOneTechnologyEach() {
        assertThatThrownBy(() -> new ResumeVocabulary(List.of(
                new ResumeVocabulary.Technology("Kafka", List.of("kafka"), null, List.of(), false),
                new ResumeVocabulary.Technology("Pulsar", List.of("kafka"), null, List.of(), false)), java.util.Map.of()))
                .hasMessageContaining("'kafka' names both Kafka and Pulsar");
    }

    @Test
    void namesAreMatchedAsWholeWordsIgnoringCaseAndHyphens() {
        ResumeVocabulary.Technology kafka = vocabulary.technology("apache kafka").orElseThrow();

        assertThat(kafka.name()).isEqualTo("Kafka");
        assertThat(vocabulary.technology("Postgres").orElseThrow().name()).isEqualTo("PostgreSQL");
        assertThat(vocabulary.names("Built a KAFKA-based pipeline", kafka)).isTrue();
        assertThat(vocabulary.names("Built a Kafkaesque pipeline", kafka)).isFalse();
        assertThat(vocabulary.occurs("An Event-Driven architecture", "event driven")).isTrue();
        assertThat(vocabulary.technologiesIn("Node.js, C++ and C# services on .NET", false)).extracting(ResumeVocabulary.Technology::name)
                .containsExactly("C++", "C#", ".NET", "Node.js");
    }

    @Test
    void anAmbiguousTechnologyInPlainTextCountsOnlyWhenAsked() {
        String text = "Reacted to production incidents within minutes.";

        assertThat(vocabulary.technologiesIn("We react to incidents quickly", false)).isEmpty();
        assertThat(vocabulary.technologiesIn("We react to incidents quickly", true)).extracting(ResumeVocabulary.Technology::name).containsExactly("React");
        assertThat(vocabulary.technologiesIn(text, true)).isEmpty();
    }

    @Test
    void anExperienceIsLinkedToTopicsThroughItsTechnologiesAndKeywordsAndToTheSkillsItNames() {
        Set<String> topics = Set.of("system-design/messaging-async", "system-design/caching", "spring-boot/web-mvc-rest", "java/concurrency");
        Set<String> skills = Set.of("spring-boot", "system-design", "java");

        ResumeVocabulary.Links links = vocabulary.links(List.of("Spring Boot", "Kafka"),
                "Built a Spring Boot microservice using Kafka for event-driven order processing.", topics, skills);

        assertThat(links.topics()).containsExactlyInAnyOrderEntriesOf(java.util.Map.of("system-design/messaging-async", 2));
        assertThat(links.skills()).containsExactly("spring-boot");
    }

    @Test
    void onlyTheInterviewsTopicsAndSkillsAreLinked() {
        ResumeVocabulary.Links links = vocabulary.links(List.of("Redis"), "Reduced latency with Redis caching.", Set.of("java/concurrency"), Set.of("java"));

        assertThat(links.isEmpty()).isTrue();
    }
}
