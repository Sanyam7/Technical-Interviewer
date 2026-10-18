package com.techinterviewer.interview.api.dto;

import com.techinterviewer.common.validation.MultilineText;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A draft solution to run against the visible examples of the current coding question. Nothing is stored. */
@Schema(description = "Runs a draft solution against the visible examples of the current coding question. Nothing is stored and "
        + "nothing is evaluated.", example = "{\"code\": \"function twoSum(nums, target) { return [0, 1]; }\", \"language\": \"javascript\"}")
public record CodeRunRequest(
        @Schema(description = "The code to run, at most 20000 characters.") @NotBlank @Size(max = AnswerRequest.MAX_CODE_CONTENT) @MultilineText String code,
        @Schema(description = "Language of the code; must be the language of the interview.", example = "javascript") @NotBlank @Size(max = 20) String language) {
}
