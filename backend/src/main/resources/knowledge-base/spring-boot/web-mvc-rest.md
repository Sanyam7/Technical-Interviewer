---
title: Spring Web MVC and REST controllers
technology: spring-boot
skill: spring-boot
topic: web-mvc-rest
doc_type: REFERENCE
---

## Request handling

The `DispatcherServlet` receives each request, finds the handler method through handler mappings, resolves its
arguments, invokes it and writes the return value through an `HttpMessageConverter` (Jackson for JSON).
`@RestController` combines `@Controller` and `@ResponseBody`. `@PathVariable` binds URI template variables,
`@RequestParam` binds query parameters and `@RequestBody` deserialises the body. `ResponseEntity` controls the status
code and headers. In the servlet stack each request is handled by one thread from the server's pool (or one virtual
thread when enabled).

## Validation

Annotate a request body parameter with `@Valid` (or `@Validated`) and its fields with Bean Validation constraints such
as `@NotBlank`, `@Size`, `@Email` and `@Positive`; an invalid body raises `MethodArgumentNotValidException`, which
Spring turns into a 400. Validation of path and query parameters needs `@Validated` on the controller class. Never
trust client input: validate at the boundary, and keep business rules in the service layer.

## Exception handling and error responses

`@RestControllerAdvice` with `@ExceptionHandler` methods maps exceptions to HTTP responses in one place, instead of
try-catch blocks in every controller. Spring supports RFC 9457 problem details (`ProblemDetail`) as a standard error
body with type, title, status and detail. Map domain errors to meaningful statuses (404 for not found, 409 for a
conflict, 422 or 400 for invalid input) and never leak stack traces or internal messages to clients.

## DTOs and status codes

Expose DTOs, not JPA entities: entities leak internal structure, can trigger lazy loading during serialisation and
allow mass assignment of fields the client must not set. Return 201 Created with a `Location` header after creating a
resource, 204 No Content when there is no body, and 200 otherwise.
