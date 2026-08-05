package org.telegram.messenger;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client-side redaction applied before any chat text leaves the device.
 *
 * Kept free of Android imports so it can be compiled and exercised standalone.
 */
public class AiSummaryRedact {

    public static final String MASK = "[REDACTED]";

    private static final Pattern[] PATTERNS = {
            // Bearer / api key style tokens, incl. sk-..., ghp_..., long opaque secrets
            Pattern.compile("\\b(?:sk|pk|rk)-[A-Za-z0-9_-]{16,}"),
            Pattern.compile("\\b(?:ghp|gho|ghu|ghs|ghr|github_pat)_[A-Za-z0-9_]{16,}"),
            Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}"),
            Pattern.compile("\\bey[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"),
            // key=value / "password: hunter2" forms
            Pattern.compile("(?i)\\b(?:pass(?:word|wd)?|passwd|api[_-]?key|apikey|secret|token|auth)\\b\\s*[:=]\\s*\\S+"),
            // Payment cards: 13-19 digits, optional space/dash grouping
            Pattern.compile("\\b(?:\\d[ -]?){12,18}\\d\\b"),
            // Private key blocks
            Pattern.compile("(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----"),
    };

    private AiSummaryRedact() {
    }

    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (Pattern pattern : PATTERNS) {
            Matcher matcher = pattern.matcher(result);
            if (matcher.find()) {
                result = matcher.reset().replaceAll(Matcher.quoteReplacement(MASK));
            }
        }
        return result;
    }
}
