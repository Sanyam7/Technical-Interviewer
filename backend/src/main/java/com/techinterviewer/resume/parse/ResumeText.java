package com.techinterviewer.resume.parse;

import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cleans the raw text of a résumé before anything else sees it, and removes contact details before a model does.
 *
 * <p>Cleaning: Unicode compatibility form (so the ligatures PDFs are full of, such as "ﬁ", become plain letters); line
 * breaks unified; control characters, invisible format characters (zero-width spaces, bidirectional overrides, soft hyphens),
 * private-use glyphs (icon fonts) and replacement characters removed, because they can hide text from a reader while a model
 * still reads it, and they break the matching of quotes; runs of spaces collapsed, lines trimmed, at most one empty line in a
 * row.
 */
public final class ResumeText {

    private static final Pattern SPACES = Pattern.compile("[\\t\\x{00A0}\\x{2000}-\\x{200A}\\x{202F}\\x{205F}\\x{3000} ]+");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");

    /** user@example.com */
    private static final Pattern EMAIL = Pattern.compile("[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}-]+(\\.[\\p{L}\\p{N}-]+)+");
    /** https://…, www.…, and bare profile links such as linkedin.com/in/someone or github.com/someone */
    private static final Pattern LINK = Pattern.compile(
            "(?i)\\b(https?://\\S+|www\\.\\S+|[a-z0-9-]+(\\.[a-z0-9-]+)*\\.(com|org|net|io|dev|me|in|co|ai|app)/\\S*)");
    /** A run of digits and phone punctuation; it is a phone number when it has 9 to 15 digits (a year range has 8). */
    private static final Pattern PHONE_CANDIDATE = Pattern.compile("(?<![\\p{L}\\p{N}])\\+?\\(?\\d[\\d ().\\-]{7,}\\d(?![\\p{L}\\p{N}])");

    public static final String EMAIL_MARK = "[email]";
    public static final String LINK_MARK = "[link]";
    public static final String PHONE_MARK = "[phone]";

    private ResumeText() {
    }

    /** The cleaned text (possibly empty). */
    public static String clean(String raw) {
        String text = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\f', '\n')
                .replace(' ', '\n')
                .replace(' ', '\n');
        StringBuilder kept = new StringBuilder(text.length());
        text.codePoints().forEach(codePoint -> {
            if (codePoint == '\n' || codePoint == '\t' || !removed(codePoint)) {
                kept.appendCodePoint(codePoint);
            }
        });
        StringBuilder lines = new StringBuilder(kept.length());
        for (String line : kept.toString().split("\n", -1)) {
            lines.append(SPACES.matcher(line).replaceAll(" ").strip()).append('\n');
        }
        return BLANK_LINES.matcher(lines).replaceAll("\n\n").strip();
    }

    /** Letters in the text: what decides whether a document has text to speak of. */
    public static long letters(String text) {
        return text.codePoints().filter(Character::isLetter).count();
    }

    /**
     * The text with e-mail addresses, links and phone numbers replaced by {@link #EMAIL_MARK}, {@link #LINK_MARK} and
     * {@link #PHONE_MARK}: an interview never needs them, so a model never receives them.
     */
    public static String withoutContactDetails(String text) {
        String result = EMAIL.matcher(text).replaceAll(EMAIL_MARK);
        result = LINK.matcher(result).replaceAll(LINK_MARK);
        Matcher phones = PHONE_CANDIDATE.matcher(result);
        StringBuilder out = new StringBuilder();
        while (phones.find()) {
            long digits = phones.group().chars().filter(Character::isDigit).count();
            phones.appendReplacement(out, digits >= 9 && digits <= 15 ? Matcher.quoteReplacement(PHONE_MARK) : Matcher.quoteReplacement(phones.group()));
        }
        phones.appendTail(out);
        return out.toString();
    }

    private static boolean removed(int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.CONTROL, Character.FORMAT, Character.PRIVATE_USE, Character.SURROGATE, Character.UNASSIGNED -> true;
            default -> codePoint == '�' || codePoint == '￼';
        };
    }
}
