package com.techinterviewer.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Free text or source code that may span lines: tab, line feed and carriage return are allowed, every other control
 * character (NUL included, which PostgreSQL cannot store) is refused, as are bidirectional override characters and
 * unpaired surrogates. {@code null} is valid.
 */
@Documented
@Constraint(validatedBy = TextValidators.Multiline.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface MultilineText {

    String message() default "must not contain control characters other than tab and line breaks";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
