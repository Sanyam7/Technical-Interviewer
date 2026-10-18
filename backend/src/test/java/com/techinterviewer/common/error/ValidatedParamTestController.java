package com.techinterviewer.common.error;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A controller with a class-level {@code @Validated}: its path and query constraints are enforced by method
 * validation through a proxy and surface as a {@code ConstraintViolationException}, not as the
 * {@code HandlerMethodValidationException} of controllers without the annotation.
 */
@RestController
@Validated
@Profile(ErrorTestController.PROFILE)
@RequestMapping("/test/validated")
class ValidatedParamTestController {

    @GetMapping("/items/{id}")
    String item(@PathVariable @Min(1) @Max(1000) long id, @RequestParam(required = false) @Size(min = 2, max = 5) String q) {
        return "item " + id + (q == null ? "" : " " + q);
    }
}
