package com.techinterviewer.resume;

import com.techinterviewer.common.api.ApiPaths;
import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import com.techinterviewer.resume.dto.ResumeResponse;
import com.techinterviewer.security.AuthenticatedUser;
import com.techinterviewer.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The authenticated candidate's résumé (AI_ARCHITECTURE.md section 2.13). The user comes from the access token; no id is
 * accepted from the client, so a résumé is reachable only by its owner. No endpoint returns the file or its text.
 */
@RestController
@RequestMapping(ApiPaths.BASE + "/profile/resume")
@Tag(name = "Profile")
public class ResumeController {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String PROBLEM = "application/problem+json";

    private final ResumeService resumes;

    public ResumeController(ResumeService resumes) {
        this.resumes = resumes;
    }

    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = JSON)
    @Operation(
            operationId = "uploadResume",
            summary = "Upload my résumé",
            description = "Reads a résumé (PDF, Word .docx or plain text, at most 2 MB and 10 pages), extracts a structured profile with a "
                    + "model and keeps only what the résumé's own words support. Replaces the previous résumé. The file and its text "
                    + "are not stored. Takes as long as one model call. Rate limited per user.")
    @ApiResponse(responseCode = "200", description = "The stored résumé profile",
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ResumeResponse.class)))
    @ApiResponse(responseCode = "413", description = "The file is larger than 2 MB (code PAYLOAD_TOO_LARGE)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = "#/components/schemas/Problem")))
    @ApiResponse(responseCode = "415", description = "Not a PDF, Word document or text file, whatever its name says (code UNSUPPORTED_FILE_TYPE)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = "#/components/schemas/Problem")))
    @ApiResponse(responseCode = "422", description = "The document is damaged or protected (DOCUMENT_UNREADABLE), has no text (DOCUMENT_EMPTY), "
            + "is too long (DOCUMENT_TOO_LONG) or holds nothing a résumé would (NOT_A_RESUME)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = "#/components/schemas/Problem")))
    @ApiResponse(responseCode = "503", description = "No AI model is available to read the résumé (code AI_PROVIDER_UNAVAILABLE)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = "#/components/schemas/Problem")))
    public ResumeResponse upload(@CurrentUser AuthenticatedUser user, @RequestPart("file") MultipartFile file) throws IOException {
        return resumes.upload(user.id(), file.getOriginalFilename(), file.getBytes());
    }

    @GetMapping(produces = JSON)
    @Operation(operationId = "getResume", summary = "My résumé", description = "The stored résumé profile.")
    @ApiResponse(responseCode = "200", description = "The résumé profile",
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ResumeResponse.class)))
    @ApiResponse(responseCode = "404", description = "No résumé was uploaded (code NOT_FOUND)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = "#/components/schemas/Problem")))
    public ResumeResponse get(@CurrentUser AuthenticatedUser user) {
        return resumes.find(user.id()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No résumé has been uploaded."));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "deleteResume", summary = "Delete my résumé",
            description = "Deletes the résumé profile. Interviews personalised from it continue without personalisation. Idempotent.")
    @ApiResponse(responseCode = "204", description = "Deleted, or there was none")
    public void delete(@CurrentUser AuthenticatedUser user) {
        resumes.delete(user.id());
    }
}
