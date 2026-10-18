package com.techinterviewer.ai.agents.output;

import com.techinterviewer.evaluation.domain.Confidence;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.CriterionScore;
import com.techinterviewer.evaluation.domain.KeyPointStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The evaluator's judgement as the model writes it ({@code schemas/answer-evaluation.v3.json}, the coding and system design
 * variants {@code answer-evaluation-coding.v1} and {@code answer-evaluation-system-design.v1}, narrowed per request to
 * the rubric's criteria, the question's key point ids and the tokens of the reference material and the earlier
 * mistakes). These are component assessments only: the server computes the score from the ratings, its consistency
 * rules and the weights of the question's skill.
 *
 * @param ratings         one rating per criterion of the rubric
 * @param keyPoints       one verdict per expected key point
 * @param factualErrors   wrong statements in the answer
 * @param candidateClaims what the candidate says about their own experience
 * @param summary         one sentence for the interview memory
 * @param flags           what the model noticed about the answer
 * @param confidence      how sure the model is of this judgement (schema v3); null from an older schema
 * @param codeAnalysis    approach and complexity of a coding solution (coding schema); null for other answers
 */
public record AssessmentDraft(
        @NotNull @Size(min = 1, max = 16) Map<@NotNull Criterion, @NotNull @Valid RatingDraft> ratings,
        @NotNull @Size(max = 10) List<@NotNull @Valid KeyPointVerdict> keyPoints,
        @NotNull @Size(max = 20) List<@NotNull @Valid FactualErrorDraft> factualErrors,
        @NotNull @Size(max = 10) List<@NotBlank @Size(max = 300) String> candidateClaims,
        @NotBlank @Size(max = 500) String summary,
        @NotNull Set<@NotNull ModelFlag> flags,
        @Valid @Nullable ConfidenceDraft confidence,
        @Valid @Nullable CodeAnalysisDraft codeAnalysis) {

    /** A judgement of an answer that is not a coding solution. */
    public AssessmentDraft(
            Map<Criterion, RatingDraft> ratings,
            List<KeyPointVerdict> keyPoints,
            List<FactualErrorDraft> factualErrors,
            List<String> candidateClaims,
            String summary,
            Set<ModelFlag> flags,
            @Nullable ConfidenceDraft confidence) {
        this(ratings, keyPoints, factualErrors, candidateClaims, summary, flags, confidence, null);
    }

    /** What a coding solution does: its approach and its own complexity in Big-O notation. */
    public record CodeAnalysisDraft(
            @NotBlank @Size(max = 500) String approach,
            @NotBlank @Size(max = 60) String timeComplexity,
            @NotBlank @Size(max = 60) String spaceComplexity) {
    }

    /** A judgement without a stated confidence (schemas before v3). */
    public AssessmentDraft(
            Map<Criterion, RatingDraft> ratings,
            List<KeyPointVerdict> keyPoints,
            List<FactualErrorDraft> factualErrors,
            List<String> candidateClaims,
            String summary,
            Set<ModelFlag> flags) {
        this(ratings, keyPoints, factualErrors, candidateClaims, summary, flags, null, null);
    }

    /** The model's own estimate of how sure it is; not calibrated. */
    public record ConfidenceDraft(@NotNull Confidence level, @NotBlank @Size(max = 500) String reason) {
    }

    /**
     * A rating from 0 (absent or wrong) to 4 (expert) with its reason and, for a design dimension, the candidate's words it rests on.
     *
     * @param evidence up to two quotes of the candidate (design schema); null from the other schemas
     */
    public record RatingDraft(
            @NotNull @Min(0) @Max(4) Integer rating,
            @NotBlank @Size(max = 2000) String rationale,
            @Nullable @Size(max = 2) List<@NotBlank @Size(max = CriterionScore.MAX_EVIDENCE_LENGTH) String> evidence) {

        /**
         * A quote over the limit is cut at a word boundary, not refused. Models quote whole sentences of a long design (Gemini
         * ignores string lengths in a schema, and a small local model ignores the prompt's), which made the entire evaluation
         * invalid; a verbatim quote cut at a word boundary is still the candidate's own words, and it is verified like any
         * other quote afterwards.
         */
        public RatingDraft {
            if (evidence != null) {
                evidence = evidence.stream().map(quote -> quote == null ? null : clip(quote)).toList();
            }
        }

        static String clip(String quote) {
            String text = quote.strip();
            if (text.length() <= CriterionScore.MAX_EVIDENCE_LENGTH) {
                return text;
            }
            int cut = text.lastIndexOf(' ', CriterionScore.MAX_EVIDENCE_LENGTH);
            return text.substring(0, cut > 0 ? cut : CriterionScore.MAX_EVIDENCE_LENGTH).strip();
        }

        public RatingDraft(Integer rating, String rationale) {
            this(rating, rationale, null);
        }

        /** The quotes, empty when the schema asks for none. */
        public List<String> quotes() {
            return evidence == null ? List.of() : evidence;
        }
    }

    /**
     * @param keyPointId    id of the expected key point
     * @param status        covered, partly covered or missing
     * @param evidenceQuote the candidate's own words that show it; required unless missing
     */
    public record KeyPointVerdict(
            @NotBlank @Size(max = 40) String keyPointId,
            @NotNull KeyPointStatus status,
            @Size(max = 1000) @Nullable String evidenceQuote) {
    }

    /**
     * @param claim      what the candidate said that is wrong
     * @param correction what is true
     * @param sources    tokens of the reference material that backs the correction (schema v2 and later)
     * @param repeats    the token of the earlier mistake this one repeats, at most one (schema v3)
     * @param criterion  the design dimension the mistake concerns (design schema); null from the other schemas
     */
    public record FactualErrorDraft(
            @NotBlank @Size(max = 1000) String claim,
            @NotBlank @Size(max = 1000) String correction,
            @Nullable @Size(max = 3) List<@NotBlank @Size(max = 10) String> sources,
            @Nullable @Size(max = 1) List<@NotBlank @Size(max = 10) String> repeats,
            @Nullable Criterion criterion) {

        public FactualErrorDraft(String claim, String correction) {
            this(claim, correction, null, null, null);
        }

        public FactualErrorDraft(String claim, String correction, @Nullable List<String> sources) {
            this(claim, correction, sources, null, null);
        }

        public FactualErrorDraft(String claim, String correction, @Nullable List<String> sources, @Nullable List<String> repeats) {
            this(claim, correction, sources, repeats, null);
        }

        /** The reference material (r1, r2, ...) that backs the correction; empty without reference material. */
        public List<String> sourceTokens() {
            return sources == null ? List.of() : sources;
        }

        /** The earlier mistake (m1, m2, ...) this one repeats; empty when none. */
        public List<String> repeatTokens() {
            return repeats == null ? List.of() : repeats;
        }
    }

    /** The flags a model may raise; the evidence-mismatch flag is the server's own. */
    public enum ModelFlag {
        INJECTION_SUSPECTED,
        OFF_TOPIC,
        LANGUAGE_MISMATCH
    }
}
