package com.techinterviewer.common.error;

import java.util.List;

/**
 * A request that is well formed but invalid on specific fields, found by a rule that annotations cannot express
 * (a value that must exist in the catalog, a list that must not repeat an entry). It answers
 * {@code 400 VALIDATION_FAILED} with the same {@code errors} array as bean validation does.
 */
public class FieldValidationException extends ApiException {

    static final String DETAIL = "One or more fields are invalid.";

    private final List<FieldViolation> violations;

    public FieldValidationException(List<FieldViolation> violations) {
        super(ErrorCode.VALIDATION_FAILED, DETAIL);
        if (violations.isEmpty()) {
            throw new IllegalArgumentException("a validation failure needs at least one violation");
        }
        this.violations = List.copyOf(violations);
    }

    public FieldValidationException(String field, String message) {
        this(List.of(new FieldViolation(field, message)));
    }

    public List<FieldViolation> violations() {
        return violations;
    }
}
