package org.telegram.messenger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders an AI Summary schema object into display text.
 *
 * Every schema field is walked generically, so adding a field to the prompt needs no change here.
 * Empty strings, empty arrays, empty objects and all-zero counters are dropped so the reader only
 * sees sections that carry content. Free of Android imports so it can be exercised standalone.
 */
public class AiSummaryFormat {

    /** Schema fields in display order. Unknown keys are appended after these. */
    private static final String[] ORDER = {
            "one_sentence_summary", "executive_summary", "daily_recap", "hot_topics", "key_decisions",
            "action_items", "open_questions", "problems_issues", "ideas_suggestions", "important_links",
            "most_active_members", "timeline", "ai_insights", "sentiment", "shared_content", "statistics",
            "important_quotes", "technical_summary", "business_summary",
    };

    private static final Pattern URL = Pattern.compile("\\b(?:https?://|www\\.)[^\\s<>\"']+|\\b[a-z0-9-]+\\.(?:ir|com|net|org|io|dev|me)(?:/[^\\s<>\"']*)?\\b");
    private static final Pattern FENCE = Pattern.compile("(?s)^\\s*```(?:json)?\\s*(.*?)\\s*```\\s*$");
    private static final String LINKS_KEY = "important_links";

    private AiSummaryFormat() {
    }

    /** Returns display text, or the input unchanged when it is not the expected JSON object. */
    public static String format(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return raw;
        }
        String body = raw.trim();
        Matcher fence = FENCE.matcher(body);
        if (fence.matches()) {
            body = fence.group(1).trim();
        }
        JSONObject root;
        try {
            root = new JSONObject(body);
        } catch (Exception e) {
            return raw.trim();
        }

        List<String> sections = new ArrayList<>();
        String note = root.optJSONObject("meta") != null ? root.optJSONObject("meta").optString("note", "") : "";
        if (!note.isEmpty() && !"null".equals(note)) {
            sections.add(note);
        }

        Set<String> keys = new LinkedHashSet<>();
        for (String key : ORDER) {
            keys.add(key);
        }
        for (java.util.Iterator<String> it = root.keys(); it.hasNext(); ) {
            keys.add(it.next());
        }

        for (String key : keys) {
            if ("meta".equals(key)) {
                continue;
            }
            String section = renderSection(key, root.opt(key));
            if (section != null) {
                sections.add(section);
            }
        }

        String text = join(sections, "\n\n");
        String linkSection = missingLinks(text, root.opt(LINKS_KEY));
        if (linkSection != null) {
            text = text.isEmpty() ? linkSection : text + "\n\n" + linkSection;
        }
        return text.isEmpty() ? raw.trim() : text;
    }

    private static String renderSection(String key, Object value) {
        String body = renderValue(value, false);
        if (body == null) {
            return null;
        }
        return title(key) + "\n" + body;
    }

    /** Returns null for anything with no content to show. */
    private static String renderValue(Object value, boolean nested) {
        if (value == null || JSONObject.NULL.equals(value)) {
            return null;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                String item = flatten(array.opt(i));
                if (item != null) {
                    lines.add((nested ? "" : "• ") + item);
                }
            }
            return lines.isEmpty() ? null : join(lines, "\n");
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            List<String> lines = new ArrayList<>();
            for (java.util.Iterator<String> it = object.keys(); it.hasNext(); ) {
                String key = it.next();
                Object child = object.opt(key);
                if (isBlankOrZero(child)) {
                    continue;
                }
                String rendered = renderValue(child, true);
                if (rendered != null) {
                    lines.add("• " + title(key) + ": " + rendered.replace("\n", ", "));
                }
            }
            return lines.isEmpty() ? null : join(lines, "\n");
        }
        return isBlankOrZero(value) ? null : String.valueOf(value).trim();
    }

    /** One line for an array element: objects collapse to their non-empty values. */
    private static String flatten(Object value) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            // An element whose list fields are all empty carries no content — an action item with an
            // owner but no tasks is noise, not a row.
            boolean hasList = false;
            boolean hasFilledList = false;
            List<String> parts = new ArrayList<>();
            for (java.util.Iterator<String> it = object.keys(); it.hasNext(); ) {
                Object child = object.opt(it.next());
                if (child instanceof JSONArray) {
                    hasList = true;
                    hasFilledList |= ((JSONArray) child).length() > 0;
                }
                if (isBlankOrZero(child)) {
                    continue;
                }
                String rendered = renderValue(child, true);
                if (rendered != null) {
                    parts.add(rendered.replace("\n", ", "));
                }
            }
            if (hasList && !hasFilledList) {
                return null;
            }
            return parts.isEmpty() ? null : join(parts, " — ");
        }
        return isBlankOrZero(value) ? null : String.valueOf(value).trim();
    }

    /** Links written inside prose still belong in the links section, whatever the model returned. */
    private static String missingLinks(String text, Object links) {
        Set<String> known = new LinkedHashSet<>();
        if (links instanceof JSONArray) {
            JSONArray array = (JSONArray) links;
            for (int i = 0; i < array.length(); i++) {
                Object item = array.opt(i);
                String url = item instanceof JSONObject ? ((JSONObject) item).optString("url", "") : String.valueOf(item);
                if (!url.isEmpty()) {
                    known.add(url);
                }
            }
        }
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = URL.matcher(text);
        while (matcher.find()) {
            String url = matcher.group();
            boolean seen = false;
            for (String existing : known) {
                if (existing.contains(url) || url.contains(existing)) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                found.add(url);
            }
        }
        if (found.isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        for (String url : found) {
            lines.add("• " + url);
        }
        return title(LINKS_KEY) + "\n" + join(lines, "\n");
    }

    private static boolean isBlankOrZero(Object value) {
        if (value == null || JSONObject.NULL.equals(value)) {
            return true;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue() == 0d;
        }
        if (value instanceof JSONArray) {
            return ((JSONArray) value).length() == 0;
        }
        if (value instanceof JSONObject) {
            return ((JSONObject) value).length() == 0;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() || "null".equals(text);
    }

    private static String title(String key) {
        String spaced = key.replace('_', ' ').trim();
        if (spaced.isEmpty()) {
            return key;
        }
        return spaced.substring(0, 1).toUpperCase(Locale.US) + spaced.substring(1);
    }

    private static String join(List<String> parts, String separator) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                builder.append(separator);
            }
            builder.append(parts.get(i));
        }
        return builder.toString();
    }
}
