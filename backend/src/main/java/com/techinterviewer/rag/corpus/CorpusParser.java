package com.techinterviewer.rag.corpus;

import com.techinterviewer.rag.DocumentType;
import com.techinterviewer.rag.Technology;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads a knowledge document: a front matter block of {@code key: value} lines between two {@code ---} lines, then
 * Markdown. Strict on purpose, because a wrong tag silently degrades retrieval: unknown or repeated keys, a missing
 * title, technology or doc_type, an unknown technology or type, a topic without a skill, a difficulty outside 1 to 5
 * and an empty body are all rejected, with every problem listed at once.
 *
 * <pre>
 * ---
 * title: Java concurrency
 * technology: java
 * skill: java
 * topic: concurrency
 * doc_type: REFERENCE
 * ---
 * ## The Java memory model
 * ...
 * </pre>
 */
public final class CorpusParser {

    static final int MAX_BYTES = 200_000;
    private static final Set<String> KEYS = Set.of("title", "technology", "skill", "topic", "doc_type", "difficulty");
    private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    private CorpusParser() {
    }

    public static CorpusDocument parse(String sourcePath, byte[] bytes) {
        List<String> problems = new ArrayList<>();
        if (bytes.length > MAX_BYTES) {
            throw new MalformedDocumentException(sourcePath, List.of("larger than " + MAX_BYTES + " bytes"));
        }
        String text = new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n");
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        if (!text.startsWith("---\n")) {
            throw new MalformedDocumentException(sourcePath, List.of("no front matter: the file must start with a --- line"));
        }
        int end = text.indexOf("\n---\n", 3);
        if (end < 0) {
            throw new MalformedDocumentException(sourcePath, List.of("the front matter is not closed by a --- line"));
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : text.substring(4, end).split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                problems.add("front matter line \"" + line.strip() + "\" is not key: value");
                continue;
            }
            String key = line.substring(0, colon).strip();
            String value = line.substring(colon + 1).strip();
            if (!KEYS.contains(key)) {
                problems.add("unknown front matter key \"" + key + "\"");
            } else if (fields.putIfAbsent(key, value) != null) {
                problems.add("front matter key \"" + key + "\" is repeated");
            }
        }
        String body = text.substring(end + 5).strip();

        String title = fields.getOrDefault("title", "");
        if (title.isBlank()) {
            problems.add("title is required");
        } else if (title.length() > 200) {
            problems.add("title is longer than 200 characters");
        }
        Technology technology = null;
        String technologyName = fields.getOrDefault("technology", "");
        if (technologyName.isBlank()) {
            problems.add("technology is required");
        } else {
            technology = Technology.fromSlug(technologyName).orElse(null);
            if (technology == null) {
                problems.add("technology \"" + technologyName + "\" is not one of "
                        + Arrays.stream(Technology.values()).map(Technology::slug).toList());
            }
        }
        DocumentType docType = null;
        String typeName = fields.getOrDefault("doc_type", "");
        if (typeName.isBlank()) {
            problems.add("doc_type is required");
        } else {
            try {
                docType = DocumentType.valueOf(typeName);
            } catch (IllegalArgumentException unknown) {
                problems.add("doc_type \"" + typeName + "\" is not REFERENCE, DESIGN_REFERENCE or QUESTION_EXEMPLAR");
            }
            if (docType == DocumentType.RESUME) {
                problems.add("doc_type RESUME is private to a candidate and never part of the knowledge base");
            }
        }
        String skill = slug(fields.get("skill"), "skill", problems);
        String topic = slug(fields.get("topic"), "topic", problems);
        if (topic != null && skill == null) {
            problems.add("topic requires skill");
        }
        Integer difficulty = null;
        if (fields.containsKey("difficulty")) {
            try {
                difficulty = Integer.valueOf(fields.get("difficulty"));
                if (difficulty < 1 || difficulty > 5) {
                    problems.add("difficulty must be 1 to 5 but was " + difficulty);
                }
            } catch (NumberFormatException notANumber) {
                problems.add("difficulty must be a number from 1 to 5");
            }
        }
        if (body.replaceAll("(?m)^#+.*$", "").isBlank()) {
            problems.add("the document has no text after its front matter");
        }
        if (!problems.isEmpty()) {
            throw new MalformedDocumentException(sourcePath, problems);
        }
        return new CorpusDocument(sourcePath, title, technology, skill, topic, docType, difficulty, body, sha256(bytes), bytes.length);
    }

    private static String slug(String value, String key, List<String> problems) {
        if (value == null) {
            return null;
        }
        if (!SLUG.matcher(value).matches()) {
            problems.add(key + " \"" + value + "\" is not a lower-case slug");
            return null;
        }
        return value;
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
