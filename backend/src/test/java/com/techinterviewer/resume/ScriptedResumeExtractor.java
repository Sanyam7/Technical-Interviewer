package com.techinterviewer.resume;

import com.techinterviewer.interview.port.AiUnavailableException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A test double of the {@link ResumeExtractor} port that behaves like an honest model: every bullet line ("- ...") is a
 * responsibility with the technologies it names, every technology of the vocabulary the text names is a technology with its line
 * as evidence, and "N years of experience" is the stated figure. It records the texts it was given (so a test can see what a
 * model would have received), and can be scripted, or made unavailable.
 */
public final class ScriptedResumeExtractor implements ResumeExtractor {

    private static final Pattern YEARS = Pattern.compile("(\\d{1,2})\\+? years of experience");
    private static final ResumeVocabulary VOCABULARY = ResumeVocabulary.load();

    private final List<String> texts = new CopyOnWriteArrayList<>();
    private volatile boolean available = true;
    private volatile @Nullable Function<String, ResumeExtraction> script;

    public void reset() {
        texts.clear();
        available = true;
        script = null;
    }

    public void unavailable() {
        available = false;
    }

    public void script(Function<String, ResumeExtraction> script) {
        this.script = script;
    }

    /** The texts the extractor received, oldest first. */
    public List<String> texts() {
        return List.copyOf(texts);
    }

    @Override
    public ResumeExtraction extract(UUID userId, String text) {
        texts.add(text);
        if (!available) {
            throw new AiUnavailableException("Reading a résumé is unavailable (scripted).");
        }
        Function<String, ResumeExtraction> scripted = script;
        return scripted != null ? scripted.apply(text) : honest(text);
    }

    public static ResumeExtraction honest(String text) {
        List<ResumeExtraction.ExperienceDraft> experiences = new ArrayList<>();
        Map<String, ResumeExtraction.FactDraft> technologies = new LinkedHashMap<>();
        ResumeExtraction.StatedYears years = null;
        for (String line : text.split("\n")) {
            if (line.startsWith("- ")) {
                String statement = line.substring(2).strip();
                experiences.add(new ResumeExtraction.ExperienceDraft("RESPONSIBILITY", statement, null,
                        VOCABULARY.technologiesIn(statement, true).stream().map(ResumeVocabulary.Technology::name).toList()));
            }
            for (ResumeVocabulary.Technology technology : VOCABULARY.technologiesIn(line, false)) {
                technologies.putIfAbsent(technology.name(), new ResumeExtraction.FactDraft(technology.name(), line.strip()));
            }
            Matcher stated = YEARS.matcher(line);
            if (years == null && stated.find()) {
                years = new ResumeExtraction.StatedYears(Integer.parseInt(stated.group(1)), line.strip());
            }
        }
        return new ResumeExtraction(List.of(), years, List.copyOf(technologies.values()), List.of(), List.of(), experiences, text, false,
                "scripted-model", "v1", null);
    }
}
