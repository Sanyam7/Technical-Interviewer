package com.techinterviewer.security.dto;

import com.techinterviewer.security.PasswordPolicy;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Null is left to {@code @NotNull}. */
public class PasswordBytesValidator implements ConstraintValidator<PasswordBytes, CharSequence> {

    private int min;
    private int max;

    @Override
    public void initialize(PasswordBytes constraint) {
        this.min = constraint.min();
        this.max = constraint.max();
    }

    @Override
    public boolean isValid(CharSequence password, ConstraintValidatorContext context) {
        if (password == null) {
            return true;
        }
        int bytes = PasswordPolicy.byteLength(password);
        return bytes >= min && bytes <= max;
    }
}
