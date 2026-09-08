package io.github.thescarletarrow.dbmcp.registry;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Normalization rules for identifiers the model passes around (database names, credential aliases).
 */
final class Names {

    private static final Pattern VALID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    private Names() {
    }

    static String normalize(String raw, String what) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
        String value = raw.strip().toLowerCase(Locale.ROOT);
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException(what + " '" + raw + "' is invalid: use 1-64 chars of a-z, 0-9, '.', '_' or '-'");
        }
        return value;
    }
}
