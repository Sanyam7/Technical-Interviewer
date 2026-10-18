package com.techinterviewer.questionbank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.interview.domain.InterviewStage;
import com.techinterviewer.interview.domain.QuestionType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** The curated question bank: the shipped files and the choice of a template (AI_ARCHITECTURE.md section 2.15). */
class QuestionBankTest {

    private static final QuestionBank BANK = QuestionBank.load();

    private static Set<String> catalogTopics() throws IOException {
        String sql = new String(new PathMatchingResourcePatternResolver().getResource("classpath:db/migration/R__seed_catalog.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Matcher topic = Pattern.compile("\\('([a-z-]+)', '([a-z0-9-]+)', '").matcher(sql);
        Set<String> keys = new TreeSet<>();
        while (topic.find()) {
            keys.add(topic.group(1) + "/" + topic.group(2));
        }
        return keys;
    }

    @Test
    void theBankCoversExactlyTheCatalogTopics() throws IOException {
        assertThat(BANK.topics()).extracting(BankTopic::key).containsExactlyElementsOf(catalogTopics());
        assertThat(BANK.templateCount()).isGreaterThanOrEqualTo(4 * BANK.topics().size());
    }

    @Test
    void everyTopicHasFundamentalsAndPracticalTemplatesOfBothKindsWhereItMatters() {
        for (BankTopic topic : BANK.topics()) {
            assertThat(topic.templates()).as(topic.key()).anyMatch(template -> template.stage() == InterviewStage.FUNDAMENTALS);
            assertThat(topic.templates()).as(topic.key()).anyMatch(template -> template.stage() == InterviewStage.PRACTICAL);
        }
        long debugging = BANK.topics().stream().flatMap(topic -> topic.templates().stream())
                .filter(template -> template.type() == QuestionType.DEBUGGING).count();
        assertThat(debugging).as("debugging questions across the bank").isGreaterThanOrEqualTo(30);
    }

    @Test
    void everyReferenceIsADocumentOfTheKnowledgeBase() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        for (BankTopic topic : BANK.topics()) {
            assertThat(topic.references()).as(topic.key()).isNotEmpty();
            for (String reference : topic.references()) {
                assertThat(resolver.getResource("classpath:knowledge-base/" + reference).exists()).as(topic.key() + " cites " + reference).isTrue();
            }
        }
    }

    @Test
    void theExampleQuestionsOfTheBriefAreInTheBank() {
        List<String> questions = BANK.topics().stream().flatMap(topic -> topic.templates().stream()).map(BankTemplate::question).toList();

        assertThat(questions).anyMatch(question -> question.contains("HashMap") && question.contains("same hash code"));
        assertThat(questions).anyMatch(question -> question.contains("equals()") && question.contains("hashCode()"));
        assertThat(questions).anyMatch(question -> question.contains("Spring Boot application starts"));
        assertThat(questions).anyMatch(question -> question.contains("@Component") && question.contains("@Repository"));
        assertThat(questions).anyMatch(question -> question.contains("virtual DOM"));
        assertThat(questions).anyMatch(question -> question.contains("any, unknown and never"));
        assertThat(questions).anyMatch(question -> question.contains("Context API") && question.contains("Redux Toolkit"));
    }

    @Test
    void aTemplateOfTheRequestedTypeAndLevelIsChosenFirst() {
        QuestionBank.Selection selection = BANK.choose("java/collections", QuestionType.INTERNALS, 3, Set.of(), 7).orElseThrow();

        assertThat(selection.topic().key()).isEqualTo("java/collections");
        assertThat(selection.template().type()).isEqualTo(QuestionType.INTERNALS);
        assertThat(selection.template().minLevel()).isLessThanOrEqualTo(3);
        assertThat(selection.template().maxLevel()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void aTemplateIsUsedOnceAndThenTheOtherTypeOfTheStageAndThenNothing() {
        Set<String> used = new HashSet<>();
        List<QuestionBank.Selection> chosen = IntStream.range(0, 20)
                .mapToObj(i -> BANK.choose("javascript/async-event-loop", QuestionType.DEBUGGING, 3, used, 1))
                .takeWhile(java.util.Optional::isPresent)
                .map(java.util.Optional::orElseThrow)
                .peek(selection -> used.add(selection.template().id()))
                .toList();

        assertThat(chosen).extracting(selection -> selection.template().id()).doesNotHaveDuplicates();
        assertThat(chosen.getFirst().template().type()).isEqualTo(QuestionType.DEBUGGING);
        assertThat(chosen).allSatisfy(selection -> assertThat(selection.template().stage()).isEqualTo(InterviewStage.PRACTICAL));
        assertThat(BANK.choose("javascript/async-event-loop", QuestionType.SCENARIO, 3, used, 1)).isEmpty();
    }

    @Test
    void theInterviewDecidesBetweenEquallyGoodTemplatesSoInterviewsDiffer() {
        Set<String> ids = new HashSet<>();
        for (long seed = 0; seed < 40; seed++) {
            String first = BANK.choose("java/core-language-oop", QuestionType.CONCEPT, 2, Set.of(), seed).orElseThrow().template().id();
            assertThat(BANK.choose("java/core-language-oop", QuestionType.CONCEPT, 2, Set.of(), seed).orElseThrow().template().id())
                    .as("the same interview chooses the same template").isEqualTo(first);
            ids.add(first);
        }
        assertThat(ids).as("different interviews get different angles").hasSizeGreaterThan(1);
    }

    @Test
    void experienceQuestionsAndUnknownTopicsHaveNoTemplate() {
        assertThat(BANK.choose("java/collections", QuestionType.EXPERIENCE, 3, Set.of(), 1)).isEmpty();
        assertThat(BANK.choose("cobol/records", QuestionType.CONCEPT, 3, Set.of(), 1)).isEmpty();
    }

    // ---- validation ---------------------------------------------------------------------------------------------------

    private static String file(String templates) {
        return """
                {"skill": "java", "topics": [{"topic": "collections", "subtopics": ["Maps"], "misconceptions": [], "references": ["java/collections.md"],
                  "templates": [%s]}]}
                """.formatted(templates);
    }

    private static String template(String id, String type, String subtopic) {
        return """
                {"id": "%s", "type": "%s", "subtopic": "%s", "levels": [1, 3], "question": "How does a map work?", "keyPoints": ["one", "two"]}
                """.formatted(id, type, subtopic);
    }

    @Test
    void aValidFileParses() {
        List<BankTopic> topics = QuestionBank.parse(file(template("a", "CONCEPT", "Maps") + "," + template("b", "SCENARIO", "Maps")), "java.json");

        assertThat(topics).extracting(BankTopic::key).containsExactly("java/collections");
    }

    @Test
    void invalidFilesAreRefusedAtStartup() {
        String concept = template("a", "CONCEPT", "Maps");
        assertThatThrownBy(() -> QuestionBank.parse(file(concept), "java.json")).hasMessageContaining("no PRACTICAL template");
        assertThatThrownBy(() -> QuestionBank.parse(file(concept + "," + template("a", "SCENARIO", "Maps")), "java.json"))
                .hasMessageContaining("appears twice");
        assertThatThrownBy(() -> QuestionBank.parse(file(concept + "," + template("b", "SCENARIO", "Sets")), "java.json"))
                .hasMessageContaining("does not list");
        assertThatThrownBy(() -> QuestionBank.parse(file(concept + "," + template("b", "EXPERIENCE", "Maps")), "java.json"))
                .hasMessageContaining("EXPERIENCE");
        assertThatThrownBy(() -> QuestionBank.parse(file(concept + "," + template("b", "SCENARIO", "Maps")), "react.json"))
                .hasMessageContaining("must be in java.json");
        assertThatThrownBy(() -> QuestionBank.parse(file(concept + "," + template("b", "SCENARIO", "Maps")).replace("\"keyPoints\"", "\"keypoints\""),
                "java.json")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aTemplateIdIsUniqueAcrossTheWholeBank() {
        List<BankTopic> one = QuestionBank.parse(file(template("a", "CONCEPT", "Maps") + "," + template("b", "SCENARIO", "Maps")), "java.json");
        BankTopic other = new BankTopic("react", "hooks", List.of("Maps"), List.of(), List.of(), one.getFirst().templates());

        assertThatThrownBy(() -> new QuestionBank(List.of(one.getFirst(), other))).hasMessageContaining("is used by");
    }
}
