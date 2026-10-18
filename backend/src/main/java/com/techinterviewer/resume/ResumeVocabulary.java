package com.techinterviewer.resume;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The curated vocabulary that ties what a résumé says to the interview catalog ({@code resources/resume/vocabulary.json},
 * AI_ARCHITECTURE.md section 2.13). Deterministic, so every link an interview is planned from can be explained: "this
 * experience names Kafka, which the vocabulary ties to the topic Messaging and asynchronous processing".
 *
 * <ul>
 *   <li>A <b>technology</b> has a canonical name and aliases, optionally the catalog skill it is (Spring Boot is the skill
 *       spring-boot) and the topics its use is evidence of (Kafka: system-design/messaging-async). An <em>ambiguous</em>
 *       technology is also an ordinary word (React, Swift, Rust): found in text it does not count unless the extractor
 *       listed it.
 *   <li>A <b>keyword</b> ties a topic to text that uses it ("multithreading": java/concurrency).
 * </ul>
 *
 * Matching is on whole words, ignoring case, Unicode forms, and hyphens versus spaces ("event-driven" = "event driven").
 */
public final class ResumeVocabulary {

    public static final String LOCATION = "resume/vocabulary.json";

    private static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private static final Pattern TOPIC_KEY = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*/[a-z0-9]+(-[a-z0-9]+)*");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-_\\x{2010}-\\x{2015}]+");

    /**
     * @param name      canonical name, as the profile shows it
     * @param aliases   how a résumé may write it (the canonical name need not be listed)
     * @param skill     the catalog skill this technology is, if any
     * @param topics    catalog topics ({@code skill/topic}) its use is evidence of
     * @param ambiguous it is also an ordinary word, so finding it in text proves nothing
     */
    public record Technology(String name, List<String> aliases, @Nullable String skill, List<String> topics, boolean ambiguous) {

        public Technology {
            Require.notBlank(name, "name", 40);
            aliases = Immutables.list(aliases);
            topics = Immutables.list(topics);
            topics.forEach(ResumeVocabulary::requireTopicKey);
        }

        /** The canonical name and the aliases. */
        List<String> spellings() {
            List<String> all = new ArrayList<>(aliases);
            all.add(name);
            return all;
        }
    }

    /**
     * What an experience is linked to.
     *
     * @param topics topic key to the number of distinct terms that tie it (the strength of the link)
     * @param skills the catalog skills the experience names itself
     */
    public record Links(Map<String, Integer> topics, Set<String> skills) {

        public Links {
            topics = Immutables.map(topics);
            skills = Set.copyOf(skills);
        }

        public boolean isEmpty() {
            return topics.isEmpty() && skills.isEmpty();
        }
    }

    private record VocabularyFile(@Nullable String _comment, List<TechnologyFile> technologies, Map<String, List<String>> keywords) {
    }

    private record TechnologyFile(String name, List<String> aliases, @Nullable String skill, @Nullable List<String> topics, @Nullable Boolean ambiguous) {
    }

    private final List<Technology> technologies;
    private final Map<String, Technology> bySpelling = new HashMap<>();
    private final Map<String, List<String>> keywords;
    private final Map<String, Pattern> patterns = new java.util.concurrent.ConcurrentHashMap<>();

    public ResumeVocabulary(List<Technology> technologies, Map<String, List<String>> keywords) {
        this.technologies = Immutables.list(technologies);
        Map<String, List<String>> sorted = new TreeMap<>();
        keywords.forEach((topic, phrases) -> {
            requireTopicKey(topic);
            sorted.put(topic, List.copyOf(phrases));
            phrases.forEach(this::compile);
        });
        this.keywords = Map.copyOf(sorted);
        Set<String> names = new LinkedHashSet<>();
        for (Technology technology : this.technologies) {
            if (!names.add(fold(technology.name()))) {
                throw new IllegalStateException("technology " + technology.name() + " is listed twice");
            }
            for (String spelling : technology.spellings()) {
                Technology other = bySpelling.put(fold(spelling), technology);
                if (other != null && other != technology) {
                    throw new IllegalStateException("'" + spelling + "' names both " + other.name() + " and " + technology.name());
                }
                compile(spelling);
            }
        }
    }

    public static ResumeVocabulary load() {
        try (InputStream in = ResumeVocabulary.class.getClassLoader().getResourceAsStream(LOCATION)) {
            if (in == null) {
                throw new IllegalStateException("the résumé vocabulary " + LOCATION + " is missing");
            }
            return parse(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read the résumé vocabulary", unreadable);
        }
    }

    static ResumeVocabulary parse(String content) {
        try {
            VocabularyFile file = JSON.readValue(content, VocabularyFile.class);
            return new ResumeVocabulary(
                    file.technologies().stream().map(entry -> new Technology(entry.name(), entry.aliases(), entry.skill(),
                            entry.topics() == null ? List.of() : entry.topics(), Boolean.TRUE.equals(entry.ambiguous()))).toList(),
                    file.keywords());
        } catch (JacksonException | IllegalArgumentException invalid) {
            throw new IllegalStateException("the résumé vocabulary is invalid: " + invalid.getMessage(), invalid);
        }
    }

    public List<Technology> technologies() {
        return technologies;
    }

    /** Topic key to its keywords. */
    public Map<String, List<String>> keywords() {
        return keywords;
    }

    /** The technology a name stands for ("Postgres" is PostgreSQL), if the vocabulary knows it. */
    public Optional<Technology> technology(String name) {
        return Optional.ofNullable(bySpelling.get(fold(name)));
    }

    /** Whether {@code text} names the technology by any of its spellings. */
    public boolean names(String text, Technology technology) {
        String folded = fold(text);
        return technology.spellings().stream().anyMatch(spelling -> matches(folded, spelling));
    }

    /** Whether {@code phrase} occurs in {@code text} as whole words. */
    public boolean occurs(String text, String phrase) {
        return matches(fold(text), phrase);
    }

    /** The technologies {@code text} names, in vocabulary order; ambiguous ones only when {@code includeAmbiguous}. */
    public List<Technology> technologiesIn(String text, boolean includeAmbiguous) {
        String folded = fold(text);
        return technologies.stream()
                .filter(technology -> includeAmbiguous || !technology.ambiguous())
                .filter(technology -> technology.spellings().stream().anyMatch(spelling -> matches(folded, spelling)))
                .toList();
    }

    /**
     * What an experience is linked to, among the given topics and skills: a topic through a technology the experience uses (its
     * verified technologies, by canonical name) or a keyword in its text; a skill when one of its technologies is that skill.
     */
    public Links links(List<String> technologyNames, String text, Set<String> topicKeys, Set<String> skills) {
        Map<String, Set<String>> evidence = new LinkedHashMap<>();
        Set<String> linkedSkills = new LinkedHashSet<>();
        for (String name : technologyNames) {
            technology(name).ifPresent(technology -> {
                technology.topics().stream().filter(topicKeys::contains)
                        .forEach(topic -> evidence.computeIfAbsent(topic, key -> new LinkedHashSet<>()).add(technology.name()));
                if (technology.skill() != null && skills.contains(technology.skill())) {
                    linkedSkills.add(technology.skill());
                }
            });
        }
        String folded = fold(text);
        keywords.forEach((topic, phrases) -> {
            if (topicKeys.contains(topic)) {
                phrases.stream().filter(phrase -> matches(folded, phrase))
                        .forEach(phrase -> evidence.computeIfAbsent(topic, key -> new LinkedHashSet<>()).add(phrase));
            }
        });
        Map<String, Integer> strengths = new TreeMap<>();
        evidence.forEach((topic, terms) -> strengths.put(topic, terms.size()));
        return new Links(strengths, linkedSkills);
    }

    /** Every topic key the vocabulary names, for checking it against the catalog. */
    public Set<String> topicKeys() {
        Set<String> keys = new LinkedHashSet<>(keywords.keySet());
        technologies.forEach(technology -> keys.addAll(technology.topics()));
        return keys;
    }

    /** Every skill slug the vocabulary names. */
    public Set<String> skills() {
        Set<String> slugs = new LinkedHashSet<>();
        technologies.stream().map(Technology::skill).filter(java.util.Objects::nonNull).forEach(slugs::add);
        return slugs;
    }

    private boolean matches(String foldedText, String phrase) {
        return compile(phrase).matcher(foldedText).find();
    }

    private Pattern compile(String phrase) {
        return patterns.computeIfAbsent(fold(phrase), folded -> {
            if (folded.isBlank()) {
                throw new IllegalStateException("the résumé vocabulary has a blank term");
            }
            return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(folded) + "(?![\\p{L}\\p{N}])");
        });
    }

    /** Compatibility form, lower case, hyphens and runs of white space as one space. */
    static String fold(String text) {
        return SEPARATORS.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }

    private static void requireTopicKey(String key) {
        if (key == null || !TOPIC_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("'" + key + "' is not a skill/topic key");
        }
    }
}
