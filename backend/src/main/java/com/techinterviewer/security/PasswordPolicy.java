package com.techinterviewer.security;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Rules for the password of an account. The limits are in UTF-8 <b>bytes</b>, not characters: BCrypt only reads the
 * first 72 bytes, so a longer password would silently have a shorter effective value (and Spring's encoder
 * refuses it), and a character count would let a few emoji exceed the limit.
 */
public final class PasswordPolicy {

    public static final int MIN_BYTES = 10;
    /** BCrypt's input limit. */
    public static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    /**
     * The canonical (NFC) form of a password. A password typed on one device may arrive decomposed ("e" plus a combining
     * accent) and on another composed ("é"); without normalisation the two would hash differently and lock the user out.
     */
    public static String normalize(String password) {
        return Normalizer.normalize(password, Normalizer.Form.NFC);
    }

    /**
     * Passwords that a guesser tries first (the usual lists, reduced to the ones of at least ten characters, the minimum length
     * here). A short list on purpose: it is checked against the password with everything but letters and digits removed, so
     * {@code Password-123!} is caught like {@code password123}. NIST SP 800-63B section 3.1.1.2 asks for a check against known
     * compromised passwords; a bigger list, or a breach service, is the upgrade (SECURITY.md, AUTH-3).
     */
    private static final Set<String> COMMON = Set.of(
            "1234567890", "12345678901", "123456789012", "1234567890123", "0123456789", "0987654321", "9876543210", "1122334455",
            "1234512345", "1111111111", "0000000000", "1q2w3e4r5t", "1qaz2wsx3edc", "1qazxsw23edc", "qazwsxedcrfv", "q1w2e3r4t5",
            "qwertyuiop", "qwertyuiop1", "qwerty1234", "qwerty12345", "qwerty123456", "qwertyuiopasdf", "asdfghjkl", "asdfghjkl1",
            "asdfghjkl123", "zxcvbnm123", "zxcvbnmasdf", "abcdefghij", "abcdefghijk", "abcd123456", "abc1234567", "abcabcabcabc",
            "password12", "password123", "password1234", "password12345", "password123456", "password1!", "password01", "password11",
            "passw0rd123", "passw0rd1234", "p4ssw0rd123", "pass123456", "pass1234567", "passwordpassword", "mypassword1", "mypassword123",
            "letmein123", "letmein1234", "letmeinplease", "welcome123", "welcome1234", "welcome12345", "welcomehome", "admin12345",
            "admin123456", "administrator", "administrator1", "adminadmin", "adminadmin1", "changeme123", "changemenow", "changeme1234",
            "iloveyou12", "iloveyou123", "iloveyou1234", "iloveyouiloveyou", "loveyou123", "monkey1234", "monkey12345", "dragon1234",
            "dragon12345", "sunshine123", "sunshine1234", "princess123", "princess1234", "football123", "football1234", "baseball123",
            "superman123", "batman12345", "trustno1234", "master12345", "shadow12345", "michael1234", "jennifer123", "charlie1234",
            "starwars123", "whatever123", "freedom123", "computer123", "internet123", "secret12345", "secretpassword", "supersecret",
            "test123456", "testtest123", "testpassword", "testing1234", "qwertyqwerty", "asdasdasd123", "zaq12wsxcde3", "1qaz!qaz1qaz",
            "pa55word123", "p@ssword123", "p@ssw0rd123", "p@ssw0rd1234", "passw0rd!23", "passwort123", "contrasena123", "motdepasse123");

    /**
     * Why a password that is long enough is still refused, or empty when it is acceptable: it is one of the most common passwords,
     * it is made of one or two characters, or it contains the part of the e-mail address before the {@code @} (or the display
     * name) of the person it protects. The message tells the person what to change and quotes nothing.
     */
    public static Optional<String> weakness(String password, String email, String displayName) {
        String folded = fold(password);
        if (COMMON.contains(folded)) {
            return Optional.of("is one of the most commonly used passwords; choose one that is not");
        }
        if (folded.chars().distinct().count() <= 2) {
            return Optional.of("repeats one or two characters; choose a longer, less regular password");
        }
        String local = email == null ? "" : fold(email.substring(0, Math.max(0, email.indexOf('@'))));
        if (local.length() >= 4 && folded.contains(local)) {
            return Optional.of("contains the name of your e-mail address; choose one that is not about you");
        }
        String name = displayName == null ? "" : fold(displayName);
        if (name.length() >= 4 && folded.contains(name)) {
            return Optional.of("contains your name; choose one that is not about you");
        }
        return Optional.empty();
    }

    /** Lower case, compatibility-normalised, letters and digits only: {@code Password-123!} and {@code password123} compare equal. */
    private static String fold(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    public static int byteLength(CharSequence password) {
        return password.toString().getBytes(StandardCharsets.UTF_8).length;
    }
}
