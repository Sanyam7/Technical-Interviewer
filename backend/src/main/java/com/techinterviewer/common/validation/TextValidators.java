package com.techinterviewer.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** The character rules behind {@link SingleLineText} and {@link MultilineText}. */
public final class TextValidators {

    private TextValidators() {
    }

    public static final class SingleLine implements ConstraintValidator<SingleLineText, CharSequence> {

        @Override
        public boolean isValid(CharSequence text, ConstraintValidatorContext context) {
            return text == null || text.codePoints().noneMatch(SingleLine::forbidden);
        }

        private static boolean forbidden(int codePoint) {
            int type = Character.getType(codePoint);
            return type == Character.CONTROL
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR
                    || type == Character.SURROGATE
                    || isBidiOverride(codePoint);
        }
    }

    public static final class Multiline implements ConstraintValidator<MultilineText, CharSequence> {

        @Override
        public boolean isValid(CharSequence text, ConstraintValidatorContext context) {
            return text == null || text.codePoints().noneMatch(Multiline::forbidden);
        }

        private static boolean forbidden(int codePoint) {
            if (codePoint == '\t' || codePoint == '\n' || codePoint == '\r') {
                return false;
            }
            return Character.getType(codePoint) == Character.CONTROL
                    || Character.getType(codePoint) == Character.SURROGATE
                    || isBidiOverride(codePoint);
        }
    }

    /** Embeddings, overrides and isolates (U+202A to U+202E, U+2066 to U+2069) plus the marks that can hide them. */
    private static boolean isBidiOverride(int codePoint) {
        return codePoint >= 0x202A && codePoint <= 0x202E
                || codePoint >= 0x2066 && codePoint <= 0x2069
                || codePoint == 0x061C
                || codePoint == 0x200E
                || codePoint == 0x200F;
    }
}
