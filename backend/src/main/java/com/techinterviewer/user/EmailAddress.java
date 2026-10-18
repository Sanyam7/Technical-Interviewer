package com.techinterviewer.user;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The one definition of an acceptable email address and of its canonical form.
 *
 * <p>Addresses are limited to ASCII (the HTML living standard's "valid email address" grammar with the usual
 * length limits), so lower-casing never changes their length and no locale specific case mapping (the Turkish
 * dotted capital I, for example) can occur. The database compares the column case-insensitively ({@code citext});
 * everything stored is also lower-cased here, so the stored value is canonical.
 */
public final class EmailAddress {

    /** RFC 5321 limit of a forward path: the longest address a mail server accepts. */
    public static final int MAX_LENGTH = 254;

    /** For {@code @Pattern}: local part of 1 to 64 characters, then a domain of dot separated labels of 1 to 63. */
    public static final String REGEX = "[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]{1,64}@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
            + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+";

    private static final Pattern PATTERN = Pattern.compile(REGEX);

    private EmailAddress() {
    }

    /** Whether the (normalised) address has the shape an account can have; anything else cannot be a stored email. */
    public static boolean isWellFormed(String email) {
        return email.length() <= MAX_LENGTH && PATTERN.matcher(email).matches();
    }

    /** Canonical form: surrounding whitespace removed, lower case. */
    public static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
