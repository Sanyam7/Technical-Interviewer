package com.techinterviewer.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;

/**
 * A short text that is shown on one line: no control characters (including NUL, tab and line breaks), no Unicode line
 * or paragraph separators, no bidirectional override characters that can make text read differently from how it is
 * stored, and no unpaired surrogates. {@code null} is valid; combine with {@code @NotBlank} when it is required.
 */
@Documented
@Constraint(validatedBy = TextValidators.SingleLine.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface SingleLineText {

    String message() default "must not contain control or line separator characters";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
