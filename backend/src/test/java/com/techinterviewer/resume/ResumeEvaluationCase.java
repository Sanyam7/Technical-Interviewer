package com.techinterviewer.resume;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** One labelled résumé of {@code ai-evals/datasets/resume-extraction.jsonl}. */
record ResumeEvaluationCase(String id, String note, String resume, Expected expected) {

    /**
     * @param substance      whether the document holds anything a résumé would (false: NOT_A_RESUME)
     * @param injection      whether it contains text addressed to the AI, which must be flagged
     * @param statedYears    the years of experience the résumé states in words; null when it states none
     * @param experiences    statements that must be among the profile's experiences (a prefix of each is enough)
     * @param technologies   canonical technology names that must be in the profile
     * @param mustNotContain text that must appear nowhere in the stored profile (injected claims, contact details)
     */
    record Expected(boolean substance, boolean injection, @Nullable Integer statedYears, List<String> experiences, List<String> technologies,
            List<String> mustNotContain) {
    }

    private static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    static List<ResumeEvaluationCase> load() {
        Path path = Path.of(System.getProperty("resume.eval.dataset", "../ai-evals/datasets/resume-extraction.jsonl"));
        try {
            return Files.readAllLines(path).stream().filter(line -> !line.isBlank())
                    .map(line -> JSON.readValue(line, ResumeEvaluationCase.class)).toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
