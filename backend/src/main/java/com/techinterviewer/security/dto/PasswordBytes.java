package com.techinterviewer.security.dto;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The UTF-8 encoding of the annotated password has between {@link #min()} and {@link #max()} bytes. */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PasswordBytesValidator.class)
public @interface PasswordBytes {

    int min();

    int max();

    String message() default "must be between {min} and {max} bytes when encoded as UTF-8";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
