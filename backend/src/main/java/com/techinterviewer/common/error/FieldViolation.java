package com.techinterviewer.common.error;

/** One invalid field of a request, reported in the {@code errors} array of a problem document. */
public record FieldViolation(String field, String message) {
}
