package com.techinterviewer.config;

import com.techinterviewer.common.error.ErrorCode;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.security.PublicEndpoints;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.Arrays;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API documentation: title and version, the bearer security scheme (required by default; the operations that
 * {@link PublicEndpoints} lists are marked as not requiring it) and the problem document that every error uses,
 * attached to the standard failure responses: 401 and 403 on secured operations, 429 on every state-changing
 * operation that a rate limit covers, 400/413/415 on operations that read a request, and the rest of the polish in
 * {@link OpenApiSchemaPolisher}.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    static final String BEARER_AUTH = "bearerAuth";
    static final String PROBLEM_SCHEMA = "Problem";
    static final String PROBLEM_MEDIA_TYPE = "application/problem+json";

    @Bean
    OpenAPI openApi(BuildProperties build) {
        return new OpenAPI()
                .info(new Info()
                        .title("AI Technical Interviewer API")
                        .description("REST API of the AI Technical Interviewer. Errors are RFC 9457 "
                                + "application/problem+json documents carrying a code and a traceId. Authenticate with "
                                + "`Authorization: Bearer <accessToken>`; the access token comes from POST /api/v1/auth/login "
                                + "and is renewed with POST /api/v1/auth/refresh.")
                        .version(build.getVersion()))
                .components(new Components()
                        .addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Short-lived access token (15 minutes) issued by the login endpoint.")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }

    /** Marks public operations and adds the standard problem responses to every operation. */
    @Bean
    OpenApiCustomizer problemResponses() {
        return openApi -> {
            // Added here, not in the OpenAPI bean: springdoc rebuilds the schema map while it scans the controllers.
            if (openApi.getComponents() == null) {
                openApi.setComponents(new Components());
            }
            openApi.getComponents().addSchemas(PROBLEM_SCHEMA, problemSchema());
            if (openApi.getPaths() == null) {
                return;
            }
            boolean securedByDefault = openApi.getSecurity() != null && !openApi.getSecurity().isEmpty();
            openApi.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
                if (PublicEndpoints.isPublic(method.name(), path)) {
                    operation.setSecurity(List.of());
                }
                boolean secured = operation.getSecurity() == null ? securedByDefault : !operation.getSecurity().isEmpty();
                if (secured) {
                    addProblem(operation, "401", "Missing, invalid or expired access token (code UNAUTHENTICATED or TOKEN_EXPIRED).");
                    addProblem(operation, "403", "The account's role does not allow this operation (code FORBIDDEN).");
                }
                boolean writes = method == PathItem.HttpMethod.POST || method == PathItem.HttpMethod.PUT
                        || method == PathItem.HttpMethod.DELETE;
                if (writes && !path.endsWith("/auth/logout")) {
                    addProblem(operation, "429", "Rate limit or daily quota exceeded (code RATE_LIMITED or DAILY_INTERVIEW_LIMIT); see the Retry-After header.");
                    ApiResponse limited = operation.getResponses().get("429");
                    if (limited.getHeaders() == null || !limited.getHeaders().containsKey("Retry-After")) {
                        limited.addHeaderObject("Retry-After", new Header()
                                .description("Seconds to wait before the request can be repeated.")
                                .schema(new IntegerSchema()));
                    }
                }
                if (operation.getRequestBody() != null) {
                    addProblem(operation, "413", "The request body is larger than the limit (code PAYLOAD_TOO_LARGE).");
                    addProblem(operation, "415", "The request is not application/json (code UNSUPPORTED_MEDIA_TYPE).");
                }
                if (operation.getRequestBody() != null || operation.getParameters() != null && !operation.getParameters().isEmpty()) {
                    addProblem(operation, "400", "The request is malformed or a field is invalid (code VALIDATION_FAILED, MALFORMED_JSON or BAD_REQUEST); errors lists the fields.");
                }
            }));
            OpenApiSchemaPolisher.polish(openApi);
        };
    }

    private static void addProblem(Operation operation, String status, String description) {
        ApiResponses responses = operation.getResponses();
        if (responses == null) {
            responses = new ApiResponses();
            operation.setResponses(responses);
        }
        if (!responses.containsKey(status)) {
            responses.addApiResponse(status, new ApiResponse()
                    .description(description)
                    .content(new Content().addMediaType(PROBLEM_MEDIA_TYPE,
                            new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM_SCHEMA)))));
        }
    }

    /** RFC 9457 problem document as this API writes it (see ProblemDetails). */
    private static Schema<?> problemSchema() {
        List<String> codes = Arrays.stream(ErrorCode.values()).map(ErrorCode::code).toList();
        StringSchema code = new StringSchema();
        code.setEnum(codes);
        code.setDescription("Stable machine-readable error identifier.");
        ObjectSchema violation = new ObjectSchema();
        violation.addProperty("field", new StringSchema());
        violation.addProperty("message", new StringSchema());
        ObjectSchema problem = new ObjectSchema();
        problem.setDescription("application/problem+json document (RFC 9457). Some errors add members, for example "
                + "currentState on a 409 of an interview.");
        problem.addProperty("type", new StringSchema().format("uri"));
        problem.addProperty("title", new StringSchema());
        problem.addProperty("status", new IntegerSchema());
        problem.addProperty("detail", new StringSchema());
        problem.addProperty("instance", new StringSchema());
        problem.addProperty("code", code);
        problem.addProperty("traceId", new StringSchema().description("Quote it when reporting a problem."));
        problem.addProperty("errors", new ArraySchema().items(violation).description("Field violations of a 400."));
        StringSchema currentState = new StringSchema();
        currentState.setEnum(Arrays.stream(InterviewState.values()).map(Enum::name).toList());
        currentState.setDescription("The interview state at the time of a 409 about an interview, so the client can resynchronise.");
        problem.addProperty("currentState", currentState);
        problem.setRequired(List.of("type", "title", "status", "detail", "instance", "code", "traceId"));
        problem.setAdditionalProperties(true);
        return problem;
    }
}
