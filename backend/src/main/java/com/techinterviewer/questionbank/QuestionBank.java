package com.techinterviewer.questionbank;

import com.techinterviewer.interview.domain.QuestionType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The curated question bank (AI_ARCHITECTURE.md section 2.15): per catalog topic, its subtopics, common misconceptions, the
 * knowledge-base documents behind it and question templates by type and level. One file per skill,
 * {@code resources/question-bank/<skill>.json}, loaded and validated at startup.
 *
 * <p>The bank does not ask questions itself. It gives the question generator an angle (a model question and what a strong answer
 * covers) for the topic, type and level the engine decided; the generator words the actual question at that level, grounded in
 * the knowledge base. So questions are realistic and accurate without being the same fixed list for every candidate: which
 * template an interview gets depends on the interview, a template is used once per interview, and its wording is the model's.
 */
public final class QuestionBank {

    public static final String LOCATION = "classpath*:question-bank/*.json";

    private static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    /** A skill's file as written. */
    private record SkillFile(String skill, List<TopicFile> topics) {
    }

    private record TopicFile(String topic, List<String> subtopics, List<String> misconceptions, List<String> references,
            List<TemplateFile> templates) {

        BankTopic topic(String skill) {
            return new BankTopic(skill, topic, subtopics == null ? List.of() : subtopics, misconceptions == null ? List.of() : misconceptions,
                    references == null ? List.of() : references,
                    templates == null ? List.of() : templates.stream().map(TemplateFile::template).toList());
        }
    }

    private record TemplateFile(String id, QuestionType type, String subtopic, List<Integer> levels, String question, List<String> keyPoints) {

        BankTemplate template() {
            if (levels == null || levels.size() != 2) {
                throw new IllegalArgumentException("template " + id + ": levels must be [lowest, highest]");
            }
            return new BankTemplate(id, type, subtopic, levels.get(0), levels.get(1), question, keyPoints == null ? List.of() : keyPoints);
        }
    }

    /**
     * A template chosen for a question, with the topic it belongs to.
     *
     * @param topic    the topic's curated material
     * @param template the template
     */
    public record Selection(BankTopic topic, BankTemplate template) {
    }

    private final Map<String, BankTopic> byKey;

    /**
     * @throws IllegalStateException when a topic appears twice or a template id is used twice in the bank
     */
    public QuestionBank(Collection<BankTopic> topics) {
        Map<String, BankTopic> map = new LinkedHashMap<>();
        Map<String, String> templateOwners = new LinkedHashMap<>();
        topics.stream().sorted(Comparator.comparing(BankTopic::key)).forEach(topic -> {
            if (map.put(topic.key(), topic) != null) {
                throw new IllegalStateException("question bank topic " + topic.key() + " is defined twice");
            }
            for (BankTemplate template : topic.templates()) {
                String owner = templateOwners.put(template.id(), topic.key());
                if (owner != null) {
                    throw new IllegalStateException("question bank template " + template.id() + " is used by " + owner + " and " + topic.key());
                }
            }
        });
        this.byKey = Map.copyOf(map);
    }

    public static QuestionBank load() {
        try {
            List<BankTopic> topics = new ArrayList<>();
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources(LOCATION)) {
                topics.addAll(parse(resource.getContentAsString(StandardCharsets.UTF_8), String.valueOf(resource.getFilename())));
            }
            return new QuestionBank(topics);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read the question bank", unreadable);
        }
    }

    static List<BankTopic> parse(String content, String file) {
        try {
            SkillFile skill = JSON.readValue(content, SkillFile.class);
            if (!file.equals(skill.skill() + ".json")) {
                throw new IllegalStateException("question bank skill " + skill.skill() + " must be in " + skill.skill() + ".json, not " + file);
            }
            if (skill.topics() == null || skill.topics().isEmpty()) {
                throw new IllegalArgumentException("it lists no topics");
            }
            return skill.topics().stream().map(topic -> topic.topic(skill.skill())).toList();
        } catch (JacksonException | IllegalArgumentException invalid) {
            throw new IllegalStateException("question bank file " + file + " is invalid: " + invalid.getMessage(), invalid);
        }
    }

    /** Every topic, by {@code skill/topic} key. */
    public List<BankTopic> topics() {
        return byKey.values().stream().sorted(Comparator.comparing(BankTopic::key)).toList();
    }

    public Optional<BankTopic> topic(String topicKey) {
        return Optional.ofNullable(byKey.get(topicKey));
    }

    public int templateCount() {
        return byKey.values().stream().mapToInt(topic -> topic.templates().size()).sum();
    }

    /**
     * The template for a question of {@code type} on {@code topicKey} at {@code level}, not in {@code used}. Candidates are the
     * unused templates of the type's stage; the best are those of the type itself that suit the level, then those of the other type
     * of the stage that suit it, then the nearest in level (a template a level off is still a good angle: the generator writes the
     * question at the decided level). Among equally good templates {@code seed} decides (the interview and the topic), so two
     * interviews do not always get the same angle and a retried request chooses the same one. Empty for EXPERIENCE, for a topic
     * the bank does not cover and when every template of the stage is used.
     */
    public Optional<Selection> choose(String topicKey, QuestionType type, int level, Set<String> used, long seed) {
        BankTopic topic = byKey.get(topicKey);
        if (topic == null || type == QuestionType.EXPERIENCE) {
            return Optional.empty();
        }
        List<BankTemplate> candidates = topic.templates().stream()
                .filter(template -> template.stage() == type.stage() && !used.contains(template.id()))
                .toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        Comparator<BankTemplate> rank = Comparator.comparingInt((BankTemplate template) -> template.distanceTo(level))
                .thenComparingInt(template -> template.type() == type ? 0 : 1);
        Comparator<BankTemplate> fit = Comparator.comparingInt((BankTemplate template) -> template.distanceTo(level) == 0 ? 0 : 1)
                .thenComparingInt(template -> template.type() == type ? 0 : 1)
                .thenComparing(rank);
        BankTemplate best = candidates.stream().min(fit).orElseThrow();
        List<BankTemplate> equallyGood = candidates.stream()
                .filter(template -> fit.compare(template, best) == 0)
                .sorted(Comparator.comparing(BankTemplate::id))
                .toList();
        BankTemplate chosen = equallyGood.get((int) Math.floorMod(seed, (long) equallyGood.size()));
        return Optional.of(new Selection(topic, chosen));
    }
}
