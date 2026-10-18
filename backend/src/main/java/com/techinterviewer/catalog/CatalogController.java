package com.techinterviewer.catalog;

import com.techinterviewer.catalog.dto.OptionsResponse;
import com.techinterviewer.catalog.dto.SkillResponse;
import com.techinterviewer.catalog.dto.TopicResponse;
import com.techinterviewer.common.api.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public, cacheable catalog. Successful answers carry {@code Cache-Control: public, max-age=3600} and an
 * ETag (added by {@link CatalogWebConfig}); errors are never cached.
 */
@RestController
@RequestMapping(ApiPaths.BASE + "/catalog")
@Tag(name = "Catalog")
public class CatalogController {

    /** Written out (not built with {@code CacheControl}) so that the header reads exactly as documented. */
    static final String CACHE_CONTROL = "public, max-age=" + Duration.ofHours(1).toSeconds();

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    private final CatalogService catalog;

    public CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping(path = "/options", produces = JSON)
    @Operation(
            operationId = "getCatalogOptions",
            summary = "Interview configuration choices",
            description = "Public. Roles, experience levels, difficulties, durations, interview types with their "
                    + "sections, and coding languages. Cacheable for one hour.")
    @ApiResponse(responseCode = "200", description = "The choices",
            content = @Content(mediaType = JSON, schema = @Schema(implementation = OptionsResponse.class)))
    public ResponseEntity<OptionsResponse> options() {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).body(catalog.options());
    }

    @GetMapping(path = "/skills", produces = JSON)
    @Operation(operationId = "listCatalogSkills", summary = "Active skills", description = "Public. Skills a candidate can be interviewed on, by name. Cacheable for one hour.")
    @ApiResponse(responseCode = "200", description = "The active skills",
            content = @Content(mediaType = JSON, array = @ArraySchema(schema = @Schema(implementation = SkillResponse.class))))
    public ResponseEntity<List<SkillResponse>> skills() {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).body(catalog.activeSkills());
    }

    @GetMapping(path = "/skills/{slug}/topics", produces = JSON)
    @Operation(operationId = "listSkillTopics", summary = "Topics of a skill", description = "Public. Topics, easiest first, with their difficulty range. Cacheable for one hour.")
    @ApiResponse(responseCode = "200", description = "The topics of the skill",
            content = @Content(mediaType = JSON, array = @ArraySchema(schema = @Schema(implementation = TopicResponse.class))))
    @ApiResponse(responseCode = "404", description = "No active skill has this slug (code NOT_FOUND)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = "#/components/schemas/Problem")))
    public ResponseEntity<List<TopicResponse>> topics(
            @Parameter(description = "Slug of an active skill", example = "java") @PathVariable String slug) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).body(catalog.topicsOfSkill(slug));
    }
}
