package org.telegram.messenger;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Summarizes a chat transcript through an OpenAI-compatible chat/completions endpoint.
 *
 * Configuration lives in global shared prefs so it is not tied to a single account,
 * while the summary itself is always requested for one explicit chat.
 */
public class AiSummary {

    public static final String PREF_BASE_URL = "ai_summary_base_url";
    public static final String PREF_API_KEY = "ai_summary_api_key";
    public static final String PREF_MODEL = "ai_summary_model";
    public static final String PREF_TEMPERATURE = "ai_summary_temperature";
    public static final String PREF_MAX_TOKENS = "ai_summary_max_tokens";
    public static final String PREF_TOP_P = "ai_summary_top_p";
    public static final String PREF_STREAMING = "ai_summary_streaming";
    public static final String PREF_SYSTEM_PROMPT = "ai_summary_system_prompt";
    public static final String PREF_HEADERS = "ai_summary_headers";
    public static final String PREF_RANGE = "ai_summary_range";

    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    public static final String DEFAULT_MODEL = "gpt-4o-mini";
    public static final float DEFAULT_TEMPERATURE = 0.2f;
    public static final int DEFAULT_MAX_TOKENS = 4096;
    public static final float DEFAULT_TOP_P = 1f;
    public static final int DEFAULT_RANGE = 100;
    public static final int MIN_RANGE = 25;

    /** Messages per request before the transcript is split and merged. */
    private static final int CHUNK_MESSAGES = 250;
    private static final int TIMEOUT_MS = 90000;
    private static final int MAX_TEXT_CHARS = 400;

    public static final String DEFAULT_SYSTEM_PROMPT = "You are AI Summary Pro, a conversation-intelligence engine that converts Telegram\n"
            + "group chat messages into structured, factual summaries.\n"
            + "\n"
            + "RULES:\n"
            + "1. Ground everything in the provided messages only. Never invent decisions, tasks,\n"
            + "   names, links, quotes, or events. If a section has no supporting content, return\n"
            + "   it empty — do not fabricate placeholder content.\n"
            + "2. Attribute action items to a person only when the assignment is clear from\n"
            + "   context. Otherwise use \"Unknown Owner\".\n"
            + "3. Detect the dominant language of the conversation and write summary prose in that\n"
            + "   language. Direct quotes stay in their original language. JSON keys stay English.\n"
            + "4. Anything already marked [REDACTED] stays redacted. Never reproduce a credential,\n"
            + "   API key, token, card number or password, even inside quotes or code blocks.\n"
            + "5. Quotes: at most 5, each under 25 words, each with speaker and time. Never alter\n"
            + "   the wording.\n"
            + "6. Sentiment percentages must sum to 100.\n"
            + "7. If the range contains zero usable messages, return the schema with empty fields\n"
            + "   and set meta.note to explain why.\n"
            + "8. Output ONLY valid JSON matching the schema below. No markdown fences, no\n"
            + "   commentary, no preamble or postamble.\n"
            + "\n"
            + "SIGNIFICANCE FILTER — apply before populating any section:\n"
            + "- Casual banter, jokes, teasing, and rhetorical questions between friends are\n"
            + "  NOT open_questions, action_items, or key_decisions, even if phrased as a\n"
            + "  question. Only include something in open_questions if it reads like someone\n"
            + "  genuinely needs an answer to move forward (logistics, a real decision, a\n"
            + "  fact nobody has).\n"
            + "- Don't expand a passing joke or one-off remark (a drink's taste, a meme, a\n"
            + "  nickname, banter about a game) into a multi-sentence topic. If a topic is\n"
            + "  purely social/joking, either skip it in hot_topics or mention it in one\n"
            + "  short clause at most.\n"
            + "- executive_summary and daily_recap should be weighted by how much genuine\n"
            + "  substance exists, not by message count. A chat that's 90% jokes and 10% a\n"
            + "  real question should mostly be about that 10%.\n"
            + "\n"
            + "SCHEMA:\n"
            + "{\n"
            + "  \"meta\": { \"range\": \"string\", \"language\": \"string\", \"note\": \"string|null\" },\n"
            + "  \"one_sentence_summary\": \"string\",\n"
            + "  \"executive_summary\": \"string\",\n"
            + "  \"daily_recap\": \"string\",\n"
            + "  \"hot_topics\": [\"string\"],\n"
            + "  \"key_decisions\": [\"string\"],\n"
            + "  \"action_items\": [{ \"owner\": \"string\", \"tasks\": [\"string\"] }],\n"
            + "  \"open_questions\": [\"string\"],\n"
            + "  \"problems_issues\": [\"string\"],\n"
            + "  \"ideas_suggestions\": [\"string\"],\n"
            + "  \"shared_content\": { \"photos\": 0, \"videos\": 0, \"voice_messages\": 0, \"documents\": 0, \"links\": 0 },\n"
            + "  \"important_links\": [{ \"title\": \"string\", \"url\": \"string\" }],\n"
            + "  \"most_active_members\": [{ \"name\": \"string\", \"message_count\": 0 }],\n"
            + "  \"timeline\": [{ \"time\": \"HH:MM\", \"event\": \"string\" }],\n"
            + "  \"ai_insights\": [\"string\"],\n"
            + "  \"sentiment\": { \"positive_pct\": 0, \"neutral_pct\": 0, \"negative_pct\": 0, \"overall_mood\": \"string\" },\n"
            + "  \"statistics\": { \"messages\": 0, \"participants\": 0, \"links\": 0, \"duration_minutes\": 0 },\n"
            + "  \"important_quotes\": [{ \"text\": \"string\", \"author\": \"string\", \"time\": \"HH:MM\" }],\n"
            + "  \"technical_summary\": { \"technologies\": [\"string\"], \"apis\": [\"string\"], \"libraries\": [\"string\"], \"repositories\": [\"string\"], \"errors\": [\"string\"] },\n"
            + "  \"business_summary\": { \"decisions\": [\"string\"], \"deadlines\": [\"string\"], \"risks\": [\"string\"], \"stakeholders\": [\"string\"], \"deliverables\": [\"string\"] }\n"
            + "}";

    private static final String MERGE_INSTRUCTION = "The user message contains several JSON summaries of consecutive parts of one chat, "
            + "in chronological order. Merge them into a single object of the same schema: append items, drop duplicates, "
            + "keep the significance filter, and do not invent anything absent from the inputs.";

    public interface Callback {
        void onResult(String summary, String error);
    }

    private AiSummary() {
    }

    public static SharedPreferences prefs() {
        return MessagesController.getGlobalMainSettings();
    }

    public static boolean isConfigured() {
        return !isEmpty(prefs().getString(PREF_API_KEY, null));
    }

    public static String systemPrompt() {
        String prompt = prefs().getString(PREF_SYSTEM_PROMPT, null);
        return isEmpty(prompt) ? DEFAULT_SYSTEM_PROMPT : prompt;
    }

    /** Shows only the tail of a stored key so the UI never renders it in full. */
    public static String maskKey(String key) {
        if (isEmpty(key)) {
            return "";
        }
        return key.length() <= 4 ? "••••" : "••••" + key.substring(key.length() - 4);
    }

    /**
     * Builds transcript lines that will leave the device, oldest first, at most {@code limit} messages.
     */
    public static List<String> buildTranscript(List<MessageObject> messages, int currentAccount, int limit) {
        List<String> reversed = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0 && reversed.size() < limit; i--) {
            MessageObject message = messages.get(i);
            if (message == null || message.messageOwner == null || message.messageOwner.action != null) {
                continue;
            }
            CharSequence text = message.messageText;
            if (text == null || text.length() == 0) {
                continue;
            }
            String body = text.toString().replace('\n', ' ').trim();
            if (body.isEmpty()) {
                continue;
            }
            if (body.length() > MAX_TEXT_CHARS) {
                body = body.substring(0, MAX_TEXT_CHARS) + "…";
            }
            reversed.add(senderName(message, currentAccount) + ": " + AiSummaryRedact.redact(body));
        }
        List<String> lines = new ArrayList<>(reversed.size());
        for (int i = reversed.size() - 1; i >= 0; i--) {
            lines.add(reversed.get(i));
        }
        return lines;
    }

    private static String senderName(MessageObject message, int currentAccount) {
        long fromId = message.getFromChatId();
        if (fromId > 0) {
            TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(fromId);
            String name = user != null ? UserObject.getUserName(user) : null;
            return isEmpty(name) ? "User " + fromId : name;
        }
        TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-fromId);
        return chat != null && !isEmpty(chat.title) ? chat.title : "Channel";
    }

    /**
     * Runs the request off the main thread and delivers the result on the main thread.
     * Transcripts longer than one request are summarized in chunks and merged, never truncated.
     */
    public static void request(List<String> transcript, Callback callback) {
        Utilities.globalQueue.postRunnable(() -> {
            String summary = null;
            String error = null;
            try {
                summary = summarize(transcript);
            } catch (Exception e) {
                FileLog.e(e);
                error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
            final String resultSummary = summary;
            final String resultError = error;
            AndroidUtilities.runOnUIThread(() -> callback.onResult(resultSummary, resultError));
        });
    }

    private static String summarize(List<String> transcript) throws Exception {
        if (transcript.size() <= CHUNK_MESSAGES) {
            return complete(systemPrompt(), join(transcript, 0, transcript.size()));
        }
        List<String> partials = new ArrayList<>();
        for (int start = 0; start < transcript.size(); start += CHUNK_MESSAGES) {
            int end = Math.min(start + CHUNK_MESSAGES, transcript.size());
            partials.add(complete(systemPrompt(), join(transcript, start, end)));
        }
        return complete(systemPrompt() + "\n\n" + MERGE_INSTRUCTION, join(partials, 0, partials.size()));
    }

    private static String complete(String systemPrompt, String userContent) throws Exception {
        SharedPreferences prefs = prefs();
        String baseUrl = prefs.getString(PREF_BASE_URL, DEFAULT_BASE_URL);
        String model = prefs.getString(PREF_MODEL, DEFAULT_MODEL);
        if (isEmpty(baseUrl)) {
            baseUrl = DEFAULT_BASE_URL;
        }
        if (isEmpty(model)) {
            model = DEFAULT_MODEL;
        }
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        JSONArray messages = new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", systemPrompt))
                .put(new JSONObject().put("role", "user").put("content", userContent));
        JSONObject payloadJson = new JSONObject()
                .put("model", model)
                .put("temperature", prefs.getFloat(PREF_TEMPERATURE, DEFAULT_TEMPERATURE))
                .put("top_p", prefs.getFloat(PREF_TOP_P, DEFAULT_TOP_P))
                .put("messages", messages);
        int maxTokens = prefs.getInt(PREF_MAX_TOKENS, DEFAULT_MAX_TOKENS);
        if (maxTokens > 0) {
            payloadJson.put("max_tokens", maxTokens);
        }
        byte[] payload = payloadJson.toString().getBytes(StandardCharsets.UTF_8);

        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + "/chat/completions").openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            String apiKey = prefs.getString(PREF_API_KEY, "");
            if (!isEmpty(apiKey)) {
                connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            applyCustomHeaders(connection, prefs.getString(PREF_HEADERS, ""));
            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload);
            }
            int code = connection.getResponseCode();
            String body = readAll(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
            if (code >= 400) {
                throw new Exception("HTTP " + code + ": " + trimForError(body));
            }
            String content = new JSONObject(body)
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim();
            if (content.isEmpty()) {
                throw new Exception("empty response");
            }
            return content;
        } finally {
            connection.disconnect();
        }
    }

    /** One `Name: value` header per line. */
    private static void applyCustomHeaders(HttpURLConnection connection, String headers) {
        if (isEmpty(headers)) {
            return;
        }
        for (String line : headers.split("\n")) {
            int separator = line.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String name = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            if (!name.isEmpty() && !value.isEmpty()) {
                connection.setRequestProperty(name, value);
            }
        }
    }

    private static String join(List<String> parts, int from, int to) {
        StringBuilder builder = new StringBuilder();
        for (int i = from; i < to; i++) {
            builder.append(parts.get(i)).append('\n');
        }
        return builder.toString();
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
    }

    private static String trimForError(String body) {
        String trimmed = body.trim();
        return trimmed.length() > 300 ? trimmed.substring(0, 300) : trimmed;
    }

    private static boolean isEmpty(CharSequence value) {
        return value == null || value.length() == 0;
    }
}
