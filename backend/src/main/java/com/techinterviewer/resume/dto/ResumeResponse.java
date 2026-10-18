package com.techinterviewer.resume.dto;

import com.techinterviewer.resume.ResumeFileType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

@Schema(description = "The candidate's résumé as the interview uses it: what was uploaded and the verified profile. The file and its "
        + "full text are never stored or returned.")
public record ResumeResponse(
        @Schema(description = "The uploaded file's name, cleaned; shown only to its owner.", example = "ada-lovelace-cv.pdf") String fileName,
        @Schema(description = "What the document was, decided from its content.") ResumeFileType fileType,
        @Schema(description = "Size of the uploaded file in bytes.") int sizeBytes,
        @Schema(description = "Pages of a PDF; absent for other documents.") @Nullable Integer pageCount,
        Instant uploadedAt,
        @Schema(description = "The résumé contained passages addressed to the AI (instructions); they were ignored.")
        boolean injectionSuspected,
        @Schema(description = "Items the extractor proposed that the résumé does not support; they were left out.") int droppedItems,
        ResumeProfileView profile) {

    @Schema(description = "The structured profile. Every item rests on words of the résumé (evidence).")
    public record ResumeProfileView(
            @Nullable YearsView yearsOfExperience,
            List<PositionView> positions,
            List<FactView> technologies,
            List<FactView> skills,
            List<FactView> domains,
            @Schema(description = "Projects, responsibilities and achievements: what personalised questions are anchored on.")
            List<ExperienceView> experiences) {
    }

    @Schema(description = "Years of professional experience.")
    public record YearsView(
            int years,
            @Schema(description = "STATED: the résumé says so; POSITIONS: computed from the dates of the roles.", allowableValues = {"STATED", "POSITIONS"})
            String basis,
            @Nullable String evidence) {
    }

    public record PositionView(
            String title,
            @Nullable String organization,
            @Schema(description = "First month, YYYY-MM.", example = "2021-03") String start,
            @Schema(description = "Last month, YYYY-MM; absent for a current role.") @Nullable String end,
            String evidence) {
    }

    public record FactView(String name, String evidence) {
    }

    public record ExperienceView(
            @Schema(example = "x1") String id,
            @Schema(allowableValues = {"PROJECT", "RESPONSIBILITY", "ACHIEVEMENT"}) String kind,
            @Schema(description = "The statement as the résumé words it.") String text,
            @Schema(description = "The role or project it belongs to, as the résumé names it.") @Nullable String context,
            List<String> technologies) {
    }
}
