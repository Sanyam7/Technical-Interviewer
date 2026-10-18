package com.techinterviewer.interview.api.dto;

import com.techinterviewer.common.validation.MultilineText;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The candidate's answer to the current question. It carries nothing but the answer: the state, the sequence number, a
 * score or an evaluation can never be sent (unknown properties are refused). The limit that depends on the question
 * (8000 characters for text, 20000 for code) and the rules on the language are checked by the service.
 */
@Schema(
        description = "An answer to the current question. content is at most 8000 characters for a TEXT question and 20000 for a CODE question. "
                + "A CODE answer must name the language of the interview in codeLanguage; a TEXT answer must not.",
        example = "{\"questionId\": \"0192f1d9-7c4e-7a53-9d2b-3f6a1c0e8b11\", \"content\": \"Because Spring uses proxies, a self-invocation bypasses the proxy, so ...\", \"timeTakenSeconds\": 185}")
public record AnswerRequest(
        @Schema(description = "The question being answered; it must be the current question of the interview (or one already answered, "
                + "which makes the request an idempotent retry).")
        @NotNull UUID questionId,
        @Schema(description = "The answer text or code.")
        @NotBlank @Size(max = MAX_CODE_CONTENT) @MultilineText String content,
        @Schema(description = "Language of a code answer; must equal the interview's coding language. Absent for a text answer.",
                example = "java", nullable = true)
        @Size(max = 20) @Nullable String codeLanguage,
        @Schema(description = "How long the candidate took, as the client measured it. Informational only.", nullable = true)
        @Min(0) @Max(MAX_TIME_TAKEN_SECONDS) @Nullable Integer timeTakenSeconds,
        @Schema(description = "The architecture diagram of a system design answer, as it stands with this answer. Allowed only for questions "
                + "of the system design section; absent when the candidate drew none.", nullable = true)
        @Valid @Nullable ArchitectureView architecture) {

    /** The longest content any answer may have (a code answer); a text answer is limited further by the service. */
    public static final int MAX_CODE_CONTENT = 20_000;
    public static final int MAX_TEXT_CONTENT = 8_000;
    public static final int MAX_TIME_TAKEN_SECONDS = 86_400;

    /** An answer without a diagram. */
    public AnswerRequest(UUID questionId, String content, @Nullable String codeLanguage, @Nullable Integer timeTakenSeconds) {
        this(questionId, content, codeLanguage, timeTakenSeconds, null);
    }
}
