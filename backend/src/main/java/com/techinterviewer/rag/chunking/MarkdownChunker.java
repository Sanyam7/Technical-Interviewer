package com.techinterviewer.rag.chunking;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a Markdown document into passages for retrieval, following its structure:
 * <ol>
 *   <li>Every heading starts a new section; the heading path ("Title › Section › Subsection") is kept with each chunk.
 *       A section is the natural unit of meaning, so a chunk never mixes two sections.
 *   <li>A section is cut into blocks: paragraphs (separated by blank lines) and fenced code blocks. A code block is
 *       never split, even if it alone exceeds the size limit: half a code example is worse than a long chunk.
 *   <li>Blocks are packed into chunks of at most {@code maxTokens} (plus the overlap below). A paragraph larger than that is split between
 *       sentences.
 *   <li>When a section needs several chunks, each continuation starts with the last sentences of the previous chunk
 *       (up to {@code overlapTokens}), so an idea cut at the boundary is still found from either side.
 * </ol>
 * Headings inside code blocks are code, not structure. Sections without text are skipped.
 */
public final class MarkdownChunker {

    public static final int DEFAULT_MAX_TOKENS = 450;
    public static final int DEFAULT_OVERLAP_TOKENS = 50;
    static final String SEPARATOR = " › ";

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~)");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+(?=[A-Z0-9`(\"'])");

    private final int maxTokens;
    private final int overlapTokens;

    public MarkdownChunker() {
        this(DEFAULT_MAX_TOKENS, DEFAULT_OVERLAP_TOKENS);
    }

    public MarkdownChunker(int maxTokens, int overlapTokens) {
        if (maxTokens < 20 || overlapTokens < 0 || overlapTokens >= maxTokens / 2) {
            throw new IllegalArgumentException("need maxTokens >= 20 and 0 <= overlapTokens < maxTokens / 2");
        }
        this.maxTokens = maxTokens;
        this.overlapTokens = overlapTokens;
    }

    public List<Chunk> chunk(String title, String markdown) {
        List<Chunk> chunks = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        List<String> sectionLines = new ArrayList<>();
        boolean inCode = false;
        for (String line : markdown.replace("\r\n", "\n").split("\n", -1)) {
            if (FENCE.matcher(line).find()) {
                inCode = !inCode;
            }
            Matcher heading = inCode ? null : HEADING.matcher(line);
            if (heading != null && heading.matches()) {
                emit(path(title, headings), sectionLines, chunks);
                sectionLines.clear();
                int level = heading.group(1).length();
                // A level-1 heading is the document's own title when present: it starts the path again.
                int depth = Math.max(0, level - 2);
                while (headings.size() > depth) {
                    headings.removeLast();
                }
                if (level > 1) {
                    headings.add(heading.group(2).strip());
                }
            } else {
                sectionLines.add(line);
            }
        }
        emit(path(title, headings), sectionLines, chunks);
        return chunks;
    }

    private static String path(String title, List<String> headings) {
        List<String> parts = new ArrayList<>();
        parts.add(title.strip());
        parts.addAll(headings);
        String path = String.join(SEPARATOR, parts);
        return path.length() <= 500 ? path : path.substring(0, 497) + "...";
    }

    private void emit(String headingPath, List<String> lines, List<Chunk> chunks) {
        List<Block> blocks = blocks(lines);
        if (blocks.isEmpty()) {
            return;
        }
        List<String> current = new ArrayList<>();
        int tokens = 0;
        boolean continuation = false;
        for (Block block : blocks) {
            for (String piece : block.code() ? List.of(block.text()) : splitLongParagraph(block.text())) {
                int size = TokenEstimator.estimate(piece);
                if (!current.isEmpty() && tokens + size > maxTokens && !(continuation && current.size() == 1 && tokens <= overlapTokens)) {
                    String text = String.join("\n\n", current);
                    add(chunks, headingPath, text);
                    current.clear();
                    String overlap = overlap(text);
                    tokens = 0;
                    continuation = !overlap.isEmpty();
                    if (continuation) {
                        current.add(overlap);
                        tokens = TokenEstimator.estimate(overlap);
                    }
                }
                current.add(piece);
                tokens += size;
            }
        }
        add(chunks, headingPath, String.join("\n\n", current));
    }

    private static void add(List<Chunk> chunks, String headingPath, String text) {
        String content = text.strip();
        if (!content.isEmpty()) {
            chunks.add(new Chunk(chunks.size(), headingPath, content, TokenEstimator.estimate(content)));
        }
    }

    /** The trailing sentences of a chunk, at most {@code overlapTokens}; empty when the chunk ends with code. */
    private String overlap(String text) {
        if (overlapTokens == 0 || text.stripTrailing().endsWith("```") || text.stripTrailing().endsWith("~~~")) {
            return "";
        }
        String[] sentences = SENTENCE_END.split(text.substring(text.lastIndexOf("\n\n") + 1).strip());
        StringBuilder overlap = new StringBuilder();
        for (int i = sentences.length - 1; i >= 0; i--) {
            String candidate = sentences[i] + (overlap.isEmpty() ? "" : " " + overlap);
            if (TokenEstimator.estimate(candidate) > overlapTokens) {
                break;
            }
            overlap.setLength(0);
            overlap.append(candidate);
        }
        return overlap.toString();
    }

    /** A paragraph within the limit as is; a longer one cut between sentences into pieces within the limit. */
    private List<String> splitLongParagraph(String paragraph) {
        if (TokenEstimator.estimate(paragraph) <= maxTokens) {
            return List.of(paragraph);
        }
        List<String> pieces = new ArrayList<>();
        StringBuilder piece = new StringBuilder();
        for (String sentence : SENTENCE_END.split(paragraph)) {
            if (!piece.isEmpty() && TokenEstimator.estimate(piece + " " + sentence) > maxTokens) {
                pieces.add(piece.toString());
                piece.setLength(0);
            }
            if (TokenEstimator.estimate(sentence) > maxTokens) {
                // A single sentence beyond the limit: cut it at the limit, the only way left to bound the size.
                for (int start = 0; start < sentence.length(); start += maxTokens * 4) {
                    pieces.add(sentence.substring(start, Math.min(sentence.length(), start + maxTokens * 4)));
                }
                continue;
            }
            piece.append(piece.isEmpty() ? "" : " ").append(sentence);
        }
        if (!piece.isEmpty()) {
            pieces.add(piece.toString());
        }
        return pieces;
    }

    private record Block(String text, boolean code) {
    }

    private static List<Block> blocks(List<String> lines) {
        List<Block> blocks = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        boolean inCode = false;
        for (String line : lines) {
            boolean fence = FENCE.matcher(line).find();
            if (inCode) {
                text.append('\n').append(line);
                if (fence) {
                    blocks.add(new Block(text.toString(), true));
                    text.setLength(0);
                    inCode = false;
                }
            } else if (fence) {
                flush(text, blocks);
                text.append(line);
                inCode = true;
            } else if (line.isBlank()) {
                flush(text, blocks);
            } else {
                text.append(text.isEmpty() ? "" : "\n").append(line.stripTrailing());
            }
        }
        if (inCode) {
            // An unclosed fence: keep what there is as code rather than lose it.
            blocks.add(new Block(text.toString(), true));
        } else {
            flush(text, blocks);
        }
        return blocks;
    }

    private static void flush(StringBuilder text, List<Block> blocks) {
        if (!text.toString().isBlank()) {
            blocks.add(new Block(text.toString(), false));
        }
        text.setLength(0);
    }
}
