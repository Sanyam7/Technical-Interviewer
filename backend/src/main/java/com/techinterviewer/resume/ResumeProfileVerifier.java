package com.techinterviewer.resume;

import com.techinterviewer.resume.ResumeExtraction.ExperienceDraft;
import com.techinterviewer.resume.ResumeExtraction.FactDraft;
import com.techinterviewer.resume.ResumeExtraction.PositionDraft;
import com.techinterviewer.resume.ResumeExtraction.StatedYears;
import com.techinterviewer.resume.ResumeVocabulary.Technology;
import com.techinterviewer.resume.domain.ExperienceKind;
import com.techinterviewer.resume.domain.ResumeExperience;
import com.techinterviewer.resume.domain.ResumeFact;
import com.techinterviewer.resume.domain.ResumePosition;
import com.techinterviewer.resume.domain.ResumeProfile;
import com.techinterviewer.resume.domain.YearsOfExperience;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Keeps only what the résumé says (AI_ARCHITECTURE.md section 2.13). The extractor is a model and may invent; this class is
 * deterministic and decides what enters the profile. Every rule is checked against the text the model was given, so a passage
 * that was removed as an instruction to the AI cannot support anything:
 *
 * <ul>
 *   <li>every excerpt (evidence, an experience's text) must occur in the résumé, its words in sequence ({@link ResumeEvidence});
 *   <li>a technology's name must occur in its excerpt (an alias of the vocabulary counts: "Postgres" shows PostgreSQL) and is
 *       stored under its canonical name; skills and domains are labels for their excerpt, which must occur;
 *   <li>a position's title must occur in the résumé, and so must the years of its dates; a current role needs a word such as
 *       "present"; an employer the résumé does not name is removed (the position stays);
 *   <li>an experience keeps its context (role or project) only when the résumé names it, and its technologies only when its
 *       own text names them; the vocabulary adds those it names unambiguously (the extractor may overlook one);
 *   <li>years of experience: the stated figure when its excerpt holds the number and the word "year"; when the extractor gives
 *       none (a small model often does not), a sentence such as "6+ years of experience" found in the résumé by a pattern;
 *       else computed from the dated positions (overlapping roles counted once); else unknown.
 *   <li>dates: "2021-03" and "2021" as asked, and the forms models return instead ("03/2021", "Mar 2021").
 * </ul>
 *
 * What fails is dropped and counted, never repaired: an item is in the profile because the résumé shows it.
 */
public final class ResumeProfileVerifier {

    private static final int MIN_EVIDENCE_WORDS = 2;
    private static final Pattern YEAR_MONTH = Pattern.compile("(\\d{4})(?:[-/](\\d{1,2}))?");
    private static final Pattern MONTH_SLASH_YEAR = Pattern.compile("(\\d{1,2})/(\\d{4})");
    private static final Pattern NAMED_MONTH_YEAR = Pattern.compile("(?i)(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?\\s+(\\d{4})");
    private static final List<String> MONTHS = List.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec");
    /** "6 years of experience", "5+ years of professional experience", "over 4 years of experience". */
    private static final Pattern STATED_YEARS = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}])(\\d{1,2})\\s*\\+?\\s*(?:years?|yrs)\\.?\\s+(?:of\\s+)?(?:\\p{L}+\\s+){0,2}experience");
    private static final int LABEL_EVIDENCE_WORDS = 1;
    private static final Set<String> CURRENT = Set.of("present", "current", "now", "today", "ongoing", "date");
    private static final Set<String> CURRENT_END = Set.of("present", "current", "now", "ongoing");
    private static final int EARLIEST_YEAR = 1950;

    /** The verified profile and why each dropped item was dropped. */
    public record Result(ResumeProfile profile, List<String> dropped) {
    }

    private final ResumeVocabulary vocabulary;
    private final String text;
    private final ResumeEvidence evidence;
    private final List<String> textWords;
    private final YearMonth now;
    private final List<String> dropped = new ArrayList<>();

    private ResumeProfileVerifier(ResumeVocabulary vocabulary, String text, YearMonth now) {
        this.vocabulary = vocabulary;
        this.text = text;
        this.evidence = new ResumeEvidence(text);
        this.textWords = ResumeEvidence.words(text);
        this.now = now;
    }

    public static Result verify(ResumeExtraction draft, ResumeVocabulary vocabulary, YearMonth now) {
        return new ResumeProfileVerifier(vocabulary, draft.seenText(), now).run(draft);
    }

    private Result run(ResumeExtraction draft) {
        List<ResumePosition> positions = positions(draft.positions());
        List<ResumeExperience> experiences = experiences(draft.experiences());
        Map<String, ResumeFact> technologies = technologies(draft.technologies());
        for (ResumeExperience experience : experiences) {
            for (String name : experience.technologies()) {
                technologies.putIfAbsent(ResumeVocabulary.fold(name), new ResumeFact(name, experience.text()));
            }
        }
        ResumeProfile profile = new ResumeProfile(
                years(draft.statedYears(), positions),
                positions,
                limit(List.copyOf(technologies.values()), ResumeProfile.MAX_TECHNOLOGIES),
                labels(draft.skills(), "skill", ResumeProfile.MAX_SKILLS),
                labels(draft.domains(), "domain", ResumeProfile.MAX_DOMAINS),
                experiences,
                dropped.size());
        return new Result(profile, List.copyOf(dropped));
    }

    // ---- positions and years ---------------------------------------------------------------------------------------

    private List<ResumePosition> positions(List<PositionDraft> drafts) {
        List<ResumePosition> positions = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (PositionDraft draft : drafts) {
            Optional<ResumePosition> position = position(draft);
            if (position.isEmpty()) {
                continue;
            }
            ResumePosition kept = position.get();
            if (seen.add(ResumeVocabulary.fold(kept.title() + "|" + kept.organization() + "|" + kept.start()))
                    && positions.size() < ResumeProfile.MAX_POSITIONS) {
                positions.add(kept);
            }
        }
        return positions;
    }

    private Optional<ResumePosition> position(PositionDraft draft) {
        String title = trimmed(draft.title(), ResumeProfile.MAX_CONTEXT_LENGTH);
        if (title == null || !evidence.contains(title, 1)) {
            return drop("position: the title '" + draft.title() + "' is not in the résumé");
        }
        String excerpt = trimmed(draft.evidence(), ResumeProfile.MAX_EXCERPT_LENGTH);
        if (excerpt == null || !evidence.contains(excerpt, MIN_EVIDENCE_WORDS)) {
            return drop("position " + title + ": its evidence is not in the résumé");
        }
        YearMonth start = month(draft.start());
        if (start == null || start.isAfter(now)) {
            return drop("position " + title + ": the start '" + draft.start() + "' is not a date in the résumé");
        }
        YearMonth end;
        String endText = draft.end() == null ? "" : draft.end().strip().toLowerCase(Locale.ROOT);
        if (endText.isEmpty() || CURRENT_END.contains(endText)) {
            if (textWords.stream().noneMatch(CURRENT::contains)) {
                return drop("position " + title + ": it is listed as current but the résumé never says so");
            }
            end = null;
        } else {
            end = month(draft.end());
            if (end == null || end.isBefore(start) || end.isAfter(now)) {
                return drop("position " + title + ": the end '" + draft.end() + "' is not a date in the résumé");
            }
        }
        String organization = trimmed(draft.organization(), ResumeProfile.MAX_CONTEXT_LENGTH);
        if (organization != null && !evidence.contains(organization, 1)) {
            organization = null;
        }
        return Optional.of(new ResumePosition(title, organization, start, end, excerpt));
    }

    /** "2019-03", "2019", "03/2019" or "Mar 2019", when the year occurs in the résumé; else null. */
    private @Nullable YearMonth month(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String date = value.strip();
        String year;
        int month;
        java.util.regex.Matcher iso = YEAR_MONTH.matcher(date);
        java.util.regex.Matcher slash = MONTH_SLASH_YEAR.matcher(date);
        java.util.regex.Matcher named = NAMED_MONTH_YEAR.matcher(date);
        if (iso.matches()) {
            year = iso.group(1);
            month = iso.group(2) == null ? 1 : Integer.parseInt(iso.group(2));
        } else if (slash.matches()) {
            year = slash.group(2);
            month = Integer.parseInt(slash.group(1));
        } else if (named.matches()) {
            year = named.group(2);
            month = MONTHS.indexOf(named.group(1).toLowerCase(Locale.ROOT)) + 1;
        } else {
            return null;
        }
        if (!textWords.contains(year)) {
            return null;
        }
        try {
            YearMonth parsed = YearMonth.of(Integer.parseInt(year), month);
            return parsed.getYear() < EARLIEST_YEAR ? null : parsed;
        } catch (java.time.DateTimeException invalid) {
            return null;
        }
    }

    private @Nullable YearsOfExperience years(@Nullable StatedYears stated, List<ResumePosition> positions) {
        if (stated != null) {
            String excerpt = trimmed(stated.evidence(), ResumeProfile.MAX_EXCERPT_LENGTH);
            List<String> words = excerpt == null ? List.of() : ResumeEvidence.words(excerpt);
            boolean shows = excerpt != null
                    && stated.years() >= 0 && stated.years() <= YearsOfExperience.MAX_YEARS
                    && evidence.contains(excerpt, MIN_EVIDENCE_WORDS)
                    && words.contains(Integer.toString(stated.years()))
                    && words.stream().anyMatch(word -> word.startsWith("year") || word.equals("yrs"));
            if (shows) {
                return new YearsOfExperience(stated.years(), YearsOfExperience.Basis.STATED, excerpt);
            }
            dropped.add("years of experience: " + stated.years() + " is not what the résumé states");
        }
        YearsOfExperience found = statedInText();
        if (found != null) {
            return found;
        }
        if (positions.isEmpty()) {
            return null;
        }
        long months = coveredMonths(positions);
        return new YearsOfExperience((int) Math.min(YearsOfExperience.MAX_YEARS, months / 12), YearsOfExperience.Basis.POSITIONS, null);
    }

    /** The first "N years of experience" of the résumé, with its line as evidence; null when it states none. */
    private @Nullable YearsOfExperience statedInText() {
        for (String line : text.split("\n")) {
            java.util.regex.Matcher matcher = STATED_YEARS.matcher(line);
            if (matcher.find()) {
                int years = Integer.parseInt(matcher.group(1));
                String excerpt = line.strip().replaceAll("\\s+", " ");
                if (excerpt.length() > ResumeProfile.MAX_EXCERPT_LENGTH) {
                    excerpt = matcher.group().strip();
                }
                return years > YearsOfExperience.MAX_YEARS ? null : new YearsOfExperience(years, YearsOfExperience.Basis.STATED, excerpt);
            }
        }
        return null;
    }

    /** Months covered by at least one position, a current one up to now. */
    private long coveredMonths(List<ResumePosition> positions) {
        List<YearMonth[]> spans = positions.stream()
                .map(position -> new YearMonth[] {position.start(), position.end() == null ? now : position.end()})
                .sorted(Comparator.comparing((YearMonth[] span) -> span[0]))
                .toList();
        long months = 0;
        YearMonth from = null;
        YearMonth to = null;
        for (YearMonth[] span : spans) {
            if (to == null || span[0].isAfter(to)) {
                if (to != null) {
                    months += ChronoUnit.MONTHS.between(from, to);
                }
                from = span[0];
                to = span[1];
            } else if (span[1].isAfter(to)) {
                to = span[1];
            }
        }
        if (to != null) {
            months += ChronoUnit.MONTHS.between(from, to);
        }
        return months;
    }

    // ---- technologies, skills, domains -----------------------------------------------------------------------------

    private Map<String, ResumeFact> technologies(List<FactDraft> drafts) {
        Map<String, ResumeFact> kept = new LinkedHashMap<>();
        for (FactDraft draft : drafts) {
            String name = trimmed(draft.name(), ResumeFact.MAX_NAME_LENGTH);
            String excerpt = trimmed(draft.evidence(), ResumeProfile.MAX_EXCERPT_LENGTH);
            if (name == null || excerpt == null || !evidence.contains(excerpt, 1)) {
                dropped.add("technology " + draft.name() + ": its evidence is not in the résumé");
                continue;
            }
            Optional<String> canonical = technologyIn(name, excerpt);
            if (canonical.isEmpty()) {
                dropped.add("technology " + name + ": its evidence does not name it");
                continue;
            }
            kept.putIfAbsent(ResumeVocabulary.fold(canonical.get()), new ResumeFact(canonical.get(), excerpt));
        }
        return kept;
    }

    /** The canonical name of {@code name} when {@code excerpt} names it; empty when it does not. */
    private Optional<String> technologyIn(String name, String excerpt) {
        Optional<Technology> known = vocabulary.technology(name);
        if (known.isPresent()) {
            return vocabulary.names(excerpt, known.get()) ? Optional.of(known.get().name()) : Optional.empty();
        }
        return ResumeEvidence.within(excerpt, name) ? Optional.of(name) : Optional.empty();
    }

    private List<ResumeFact> labels(List<FactDraft> drafts, String what, int max) {
        Map<String, ResumeFact> kept = new LinkedHashMap<>();
        for (FactDraft draft : drafts) {
            String name = trimmed(draft.name(), ResumeFact.MAX_NAME_LENGTH);
            String excerpt = trimmed(draft.evidence(), ResumeProfile.MAX_EXCERPT_LENGTH);
            if (name == null || excerpt == null || !evidence.contains(excerpt, LABEL_EVIDENCE_WORDS)) {
                dropped.add(what + " " + draft.name() + ": its evidence is not in the résumé");
                continue;
            }
            kept.putIfAbsent(ResumeVocabulary.fold(name), new ResumeFact(name, excerpt));
        }
        return limit(List.copyOf(kept.values()), max);
    }

    // ---- experiences -----------------------------------------------------------------------------------------------

    private List<ResumeExperience> experiences(List<ExperienceDraft> drafts) {
        List<ResumeExperience> experiences = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ExperienceDraft draft : drafts) {
            ExperienceKind kind = kind(draft.kind());
            String statement = trimmed(draft.text(), ResumeProfile.MAX_EXCERPT_LENGTH);
            if (kind == null || statement == null || !evidence.contains(statement, ResumeExperience.MIN_WORDS)) {
                dropped.add("experience '" + draft.text() + "': it is not in the résumé");
                continue;
            }
            if (!seen.add(String.join(" ", ResumeEvidence.words(statement))) || experiences.size() >= ResumeProfile.MAX_EXPERIENCES) {
                continue;
            }
            String context = trimmed(draft.context(), ResumeProfile.MAX_CONTEXT_LENGTH);
            if (context != null && !evidence.contains(context, 1)) {
                context = null;
            }
            Set<String> technologies = new LinkedHashSet<>();
            for (String name : draft.technologies()) {
                technologyIn(name.strip(), statement).ifPresent(technologies::add);
            }
            vocabulary.technologiesIn(statement, false).forEach(technology -> technologies.add(technology.name()));
            experiences.add(new ResumeExperience("x" + (experiences.size() + 1), kind, statement, context, limit(List.copyOf(technologies), 10)));
        }
        return experiences;
    }

    private static @Nullable ExperienceKind kind(@Nullable String value) {
        if (value == null) {
            return null;
        }
        try {
            return ExperienceKind.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private <T> Optional<T> drop(String reason) {
        dropped.add(reason);
        return Optional.empty();
    }

    /** The stripped value when it is not blank and fits; else null (an over-long excerpt was not copied from one line). */
    private static @Nullable String trimmed(@Nullable String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip().replaceAll("\\s+", " ");
        return stripped.isEmpty() || stripped.length() > maxLength ? null : stripped;
    }

    private static <T> List<T> limit(List<T> items, int max) {
        return items.size() <= max ? items : items.subList(0, max);
    }
}
