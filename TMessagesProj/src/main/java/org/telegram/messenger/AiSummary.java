package org.telegram.messenger;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

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
    public static final int DEFAULT_MAX_TOKENS = 8192;
    public static final float DEFAULT_TOP_P = 1f;
    public static final int DEFAULT_RANGE = 100;
    public static final int MIN_RANGE = 25;

    /** Messages per request before the transcript is split and merged. */
    private static final int CHUNK_MESSAGES = 250;
    private static final int TIMEOUT_MS = 90000;
    private static final int MAX_TEXT_CHARS = 400;
    private static final Pattern REASONING_TAG =
            Pattern.compile("(?is)<(think|thinking|reasoning)>.*?</\\1>");

    public static final String DEFAULT_SYSTEM_PROMPT = "You are AI Summary Pro, a conversation-intelligence engine that converts Telegram\n"
            + "group chat messages into structured, factual summaries.\n"
            + "\n"
            + "LANGUAGE IS NON-NEGOTIABLE: every single string value in your JSON output —\n"
            + "in every field, from every chunk, in every merge step — must be in the\n"
            + "dominant language of the conversation, using only characters/words that\n"
            + "belong to that language (plus English JSON keys). Never insert a word,\n"
            + "character, or script from any third language, even accidentally. Check\n"
            + "this before finalizing your response. Detect that dominant language from the\n"
            + "actual message text provided; never default to English. A word that was already\n"
            + "in another language in the source messages may stay as it was.\n"
            + "\n"
            + "RULES:\n"
            + "1. Ground everything in the provided messages only. Never invent decisions, tasks,\n"
            + "   names, links, quotes, or events. If a section has no supporting content, return\n"
            + "   it empty — do not fabricate placeholder content.\n"
            + "2. Attribute action items to a person only when the assignment is clear from\n"
            + "   context. Otherwise use \"Unknown Owner\".\n"
            + "3. technical_summary and business_summary must only contain facts explicitly\n"
            + "   stated in the messages. Do not infer a person's role (e.g. \"tester\",\n"
            + "   \"target user\", \"stakeholder\") or a risk/status unless someone in the chat\n"
            + "   literally said it. If the conversation is casual and provides no real\n"
            + "   technical/business substance, leave these sections mostly or entirely\n"
            + "   empty rather than manufacturing professional-sounding framing.\n"
            + "4. NEVER diagnose, label, or speculate about anyone's mental health, emotional\n"
            + "   state, or personal wellbeing. Do not write that someone seems depressed,\n"
            + "   unmotivated, anxious, lonely, or needs support, and never suggest they get\n"
            + "   help — not in ai_insights, not in sentiment.overall_mood, not anywhere. A\n"
            + "   message like \"nothing feels good\" is a statement in the chat, not a\n"
            + "   condition to assess. sentiment describes the tone of the CONVERSATION as a\n"
            + "   whole, never the psychology of a participant. If a topic is personal or\n"
            + "   sensitive, report only what was literally said, or leave the field empty.\n"
            + "5. Anything already marked [REDACTED] stays redacted. Never reproduce a credential,\n"
            + "   API key, token, card number or password, even inside quotes or code blocks.\n"
            + "6. Quotes: at most 5, each under 25 words, each with speaker and time. Never alter\n"
            + "   the wording.\n"
            + "7. Sentiment percentages must sum to 100.\n"
            + "8. Leave statistics at 0 — the app fills in the real counts itself.\n"
            + "9. If the range contains zero usable messages, return the schema with empty fields\n"
            + "   and set meta.note to explain why.\n"
            + "10. DATES — For any date or timestamp you output (timeline entries, quote timestamps,\n"
            + "   etc.), use ONLY the exact date/time values provided with each message in\n"
            + "   the input. Never infer, assume, or default to a year or date from your own\n"
            + "   training data or general knowledge — if a message's date isn't explicitly\n"
            + "   provided in the input, omit the date rather than guessing one.\n"
            + "11. Output ONLY valid JSON matching the schema below. No markdown fences, no\n"
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
            + "  \"timeline\": [{ \"time\": \"YYYY-MM-DDTHH:MM (copied from the input, never invented)\", \"event\": \"string\" }],\n"
            + "  \"ai_insights\": [\"string\"],\n"
            + "  \"sentiment\": { \"positive_pct\": 0, \"neutral_pct\": 0, \"negative_pct\": 0, \"overall_mood\": \"string\" },\n"
            + "  \"statistics\": { \"messages\": 0, \"participants\": 0, \"links\": 0, \"duration_minutes\": 0 },\n"
            + "  \"important_quotes\": [{ \"text\": \"string\", \"author\": \"string\", \"time\": \"copied from the input, never invented\" }],\n"
            + "  \"technical_summary\": { \"technologies\": [\"string\"], \"apis\": [\"string\"], \"libraries\": [\"string\"], \"repositories\": [\"string\"], \"errors\": [\"string\"] },\n"
            + "  \"business_summary\": { \"decisions\": [\"string\"], \"deadlines\": [\"string\"], \"risks\": [\"string\"], \"stakeholders\": [\"string\"], \"deliverables\": [\"string\"] }\n"
            + "}";

    private static final String MERGE_INSTRUCTION = "The user message contains several JSON summaries of consecutive parts of one chat, "
            + "in chronological order. Merge them into a single object of the same schema: append items, drop duplicates, "
            + "keep the significance filter, and do not invent anything absent from the inputs.";

    public interface Callback {
        void onResult(String summary, String error);
    }

    /** Token counter for the streaming path; each SSE delta event is one token. */
    public interface Progress {
        void onTokens(int tokens);
    }

    private AiSummary() {
    }

    public static SharedPreferences prefs() {
        return MessagesController.getGlobalMainSettings();
    }

    public static boolean isConfigured() {
        return !isEmpty(prefs().getString(PREF_API_KEY, null));
    }

    public static boolean isStreaming() {
        return prefs().getBoolean(PREF_STREAMING, false);
    }

    /**
     * Token count as a percentage of the configured max_tokens, capped at 99 so the bar never
     * claims completion before the result is parsed. ponytail: token count is a proxy for real
     * progress — the model may stop early; upgrade to byte-based only if a provider reports totals.
     */
    public static int streamPercent(int tokens) {
        int max = prefs().getInt(PREF_MAX_TOKENS, DEFAULT_MAX_TOKENS);
        if (max <= 0) {
            max = DEFAULT_MAX_TOKENS;
        }
        return Math.min(99, tokens * 100 / max);
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
     *
     * Input comes newest-first from the database and is reversed here. Each line carries the send
     * time as a full ISO 8601 local timestamp, because a bare clock time gives the model no year
     * and it will invent one.
     */
    public static List<String> buildTranscript(List<TLRPC.Message> messages, int currentAccount, int limit) {
        SimpleDateFormat stamp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        List<String> reversed = new ArrayList<>();
        for (int i = 0; i < messages.size() && reversed.size() < limit; i++) {
            TLRPC.Message message = messages.get(i);
            if (message == null || message.action != null || message.message == null) {
                continue;
            }
            String body = message.message.replace('\n', ' ').trim();
            if (body.isEmpty()) {
                continue;
            }
            if (body.length() > MAX_TEXT_CHARS) {
                body = body.substring(0, MAX_TEXT_CHARS) + "…";
            }
            String when = stamp.format(new Date(message.date * 1000L));
            reversed.add("[" + when + "] " + senderName(message, currentAccount) + ": " + AiSummaryRedact.redact(body));
        }
        List<String> lines = new ArrayList<>(reversed.size());
        for (int i = reversed.size() - 1; i >= 0; i--) {
            lines.add(reversed.get(i));
        }
        return lines;
    }

    private static String senderName(TLRPC.Message message, int currentAccount) {
        long fromId = MessageObject.getFromChatId(message);
        // Private chats omit from_id in the TL message: the sender is only derivable from the out
        // flag plus the dialog peer. Without this both directions resolve to id 0 and every line
        // gets the same label, so the model sees a monologue and the other party's messages
        // vanish from the summary.
        if (fromId == 0) {
            fromId = message.out
                    ? UserConfig.getInstance(currentAccount).getClientUserId()
                    : MessageObject.getDialogId(message);
        }
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
    public static void request(List<String> transcript, Progress progress, Callback callback) {
        Utilities.globalQueue.postRunnable(() -> {
            String summary = null;
            String error = null;
            try {
                summary = summarize(transcript, progress);
            } catch (Exception e) {
                FileLog.e(e);
                error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
            final String resultSummary = summary == null ? null : applyStatistics(summary, transcript);
            final String resultError = error;
            AndroidUtilities.runOnUIThread(() -> callback.onResult(resultSummary, resultError));
        });
    }

    /**
     * Fetches the last {@code limit} messages of a dialog from the server, newest first.
     *
     * The local cache only holds what was already downloaded — roughly the last few dozen
     * messages — so reading it made every range return the same rows and the same
     * duration_minutes. messages.getHistory caps a page at 100, hence the paging loop.
     *
     * ponytail: sequential pages, one in flight at a time. 1000 messages is 10 round trips;
     * parallelise only if that ever feels slow.
     */
    public static void fetchHistory(int currentAccount, long dialogId, int limit, Utilities.Callback<ArrayList<TLRPC.Message>> callback) {
        fetchPage(currentAccount, dialogId, limit, 0, new ArrayList<>(), callback);
    }

    private static void fetchPage(int currentAccount, long dialogId, int limit, int offsetId,
                                  ArrayList<TLRPC.Message> collected, Utilities.Callback<ArrayList<TLRPC.Message>> callback) {
        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
        req.offset_id = offsetId;
        req.limit = Math.min(100, limit - collected.size());
        if (req.peer == null || req.limit <= 0) {
            AndroidUtilities.runOnUIThread(() -> callback.run(collected));
            return;
        }
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (!(response instanceof TLRPC.messages_Messages)) {
                callback.run(collected);
                return;
            }
            TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
            // Sender names are resolved from the controller's caches, so the page's users and
            // chats have to be registered before the transcript is built.
            MessagesController.getInstance(currentAccount).putUsers(res.users, false);
            MessagesController.getInstance(currentAccount).putChats(res.chats, false);
            collected.addAll(res.messages);
            if (res.messages.isEmpty() || collected.size() >= limit) {
                callback.run(collected);
            } else {
                fetchPage(currentAccount, dialogId, limit, res.messages.get(res.messages.size() - 1).id, collected, callback);
            }
        }));
    }

    /**
     * Overwrites the model's statistics block with counts computed from the transcript itself.
     *
     * The model guessed these numbers, which is why three different ranges reported nearly the
     * same duration_minutes. The transcript is the ground truth: it is exactly what was sent.
     */
    static String applyStatistics(String summary, List<String> transcript) {
        try {
            String body = summary.trim();
            int start = body.indexOf('{');
            int end = body.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return summary;
            }
            JSONObject root = new JSONObject(body.substring(start, end + 1));
            JSONObject stats = root.optJSONObject("statistics");
            if (stats == null) {
                stats = new JSONObject();
                root.put("statistics", stats);
            }
            java.util.Set<String> senders = new java.util.LinkedHashSet<>();
            long first = 0;
            long last = 0;
            for (String line : transcript) {
                // Lines are "[yyyy-MM-dd'T'HH:mm] Sender: text" — built by buildTranscript above.
                if (!line.startsWith("[")) {
                    continue;
                }
                int close = line.indexOf(']');
                if (close < 0) {
                    continue;
                }
                long when = parseStamp(line.substring(1, close));
                if (when > 0) {
                    if (first == 0) {
                        first = when;
                    }
                    last = when;
                }
                int colon = line.indexOf(": ", close);
                if (colon > close) {
                    senders.add(line.substring(close + 2, colon));
                }
            }
            stats.put("messages", transcript.size());
            stats.put("participants", senders.size());
            stats.put("duration_minutes", first > 0 && last > first ? (last - first) / 60000L : 0);
            return root.toString();
        } catch (Exception e) {
            // Never lose a good summary over a statistics rewrite.
            FileLog.e(e);
            return summary;
        }
    }

    private static long parseStamp(String value) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).parse(value).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String summarize(List<String> transcript, Progress progress) throws Exception {
        if (transcript.size() <= CHUNK_MESSAGES) {
            return complete(systemPrompt(), join(transcript, 0, transcript.size()), progress);
        }
        List<String> partials = new ArrayList<>();
        for (int start = 0; start < transcript.size(); start += CHUNK_MESSAGES) {
            int end = Math.min(start + CHUNK_MESSAGES, transcript.size());
            partials.add(complete(systemPrompt(), join(transcript, start, end), progress));
        }
        return complete(systemPrompt() + "\n\n" + MERGE_INSTRUCTION, join(partials, 0, partials.size()), progress);
    }

    /**
     * Drops a reasoning preamble that arrived inside content instead of its own field.
     *
     * Handles both the tagged form (`<think>…</think>`, `<reasoning>…</reasoning>`) and the
     * untagged form, where the model narrates first and the object starts later in the text.
     */
    static String stripReasoning(String content) {
        if (content == null) {
            return "";
        }
        String text = REASONING_TAG.matcher(content).replaceAll("").trim();
        // An unclosed tag means the budget ran out mid-thought: nothing usable follows.
        int open = text.indexOf("<think");
        if (open >= 0) {
            text = text.substring(0, open).trim();
        }
        int brace = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (brace >= 0 && end > brace) {
            text = text.substring(brace, end + 1);
        }
        return text.trim();
    }

    /** Rejects anything that is not a complete JSON object: raw prose must never reach the user. */
    private static String requireJson(String content) throws Exception {
        String text = stripReasoning(content);
        if (!text.startsWith("{") || !text.endsWith("}")) {
            throw new Exception("bad response: model did not return JSON (reasoning leak or truncation)");
        }
        try {
            new JSONObject(text);
        } catch (Exception e) {
            throw new Exception("bad response: incomplete JSON, try again or raise Max Tokens");
        }
        return text;
    }

    private static String trimForLog(String body) {
        return body.length() > 2000 ? body.substring(0, 2000) + "…" : body;
    }

    private static String complete(String systemPrompt, String userContent, Progress progress) throws Exception {
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

        boolean stream = progress != null && prefs.getBoolean(PREF_STREAMING, false);
        JSONArray messages = new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", systemPrompt))
                .put(new JSONObject().put("role", "user").put("content", userContent));
        JSONObject payloadJson = new JSONObject()
                .put("model", model)
                .put("temperature", prefs.getFloat(PREF_TEMPERATURE, DEFAULT_TEMPERATURE))
                .put("top_p", prefs.getFloat(PREF_TOP_P, DEFAULT_TOP_P))
                .put("messages", messages);
        if (stream) {
            payloadJson.put("stream", true);
        }
        // Reasoning models spend the whole token budget on a chain of thought and either leak it
        // as the answer or run out of room mid-JSON. OpenRouter honours this; providers that
        // don't recognise it ignore an unknown field.
        payloadJson.put("reasoning", new JSONObject().put("exclude", true));
        payloadJson.put("include_reasoning", false);
        int maxTokens = prefs.getInt(PREF_MAX_TOKENS, DEFAULT_MAX_TOKENS);
        if (maxTokens > 0) {
            payloadJson.put("max_tokens", maxTokens);
        }
        byte[] payload = payloadJson.toString().getBytes(StandardCharsets.UTF_8);

        // Proves what actually reaches the provider: the stored prompt length, the length that went
        // into the request, and whether the transcript still contains non-ASCII (Persian) text.
        // A mismatch between the first two means the prompt was truncated somewhere.
        if (BuildVars.LOGS_ENABLED) {
            String stored = prefs.getString(PREF_SYSTEM_PROMPT, null);
            FileLog.d("AiSummary: prompt stored=" + (stored == null ? DEFAULT_SYSTEM_PROMPT.length() + " (default)" : String.valueOf(stored.length()))
                    + " sent=" + systemPrompt.length()
                    + " user_chars=" + userContent.length()
                    + " user_non_ascii=" + countNonAscii(userContent)
                    + " payload_bytes=" + payload.length
                    + " stream=" + stream
                    + " model=" + model);
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + "/chat/completions").openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setDoOutput(true);
            // Explicit charset: the payload is UTF-8 encoded above, and a provider that assumes
            // ISO-8859-1 would turn Persian text into mojibake before the model ever sees it.
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (stream) {
                connection.setRequestProperty("Accept", "text/event-stream");
            }
            String apiKey = prefs.getString(PREF_API_KEY, "");
            if (!isEmpty(apiKey)) {
                connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            applyCustomHeaders(connection, prefs.getString(PREF_HEADERS, ""));
            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload);
            }
            int code = connection.getResponseCode();
            if (code >= 400) {
                throw new Exception("HTTP " + code + ": " + trimForError(readAll(connection.getErrorStream())));
            }
            String content;
            if (stream) {
                content = readStream(connection.getInputStream(), progress);
            } else {
                String body = readAll(connection.getInputStream());
                if (BuildVars.LOGS_ENABLED) {
                    // Whole response, not just the field we parse: shows whether reasoning came
                    // back in its own key, inside content, and what finish_reason really was.
                    FileLog.d("AiSummary: raw response " + trimForLog(body));
                }
                JSONObject choice = new JSONObject(body).getJSONArray("choices").getJSONObject(0);
                if ("length".equals(choice.optString("finish_reason"))) {
                    throw new Exception("truncated: response hit max_tokens, raise it and retry");
                }
                content = choice.getJSONObject("message").getString("content");
            }
            content = requireJson(content);
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

    /**
     * Consumes an SSE stream line by line, reporting a running token count as it goes.
     *
     * The schema output is one JSON object, so showing partial text would put `{"executive_su` on
     * screen. Only the count is surfaced; the parsed object is still rendered once complete.
     */
    private static String readStream(InputStream stream, Progress progress) throws Exception {
        StringBuilder content = new StringBuilder();
        int tokens = 0;
        boolean truncated = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                truncated |= line.contains("\"finish_reason\":\"length\"") || line.contains("\"finish_reason\": \"length\"");
                String delta = streamDelta(line);
                if (delta == null) {
                    continue;
                }
                content.append(delta);
                tokens++;
                final int count = tokens;
                AndroidUtilities.runOnUIThread(() -> progress.onTokens(count));
            }
        }
        if (truncated) {
            throw new Exception("truncated: response hit max_tokens, raise it and retry");
        }
        return content.toString().trim();
    }

    /** Content of one SSE line, or null when the line carries no delta text. */
    static String streamDelta(String line) {
        if (line == null || !line.startsWith("data:")) {
            return null;
        }
        String data = line.substring(5).trim();
        if (data.isEmpty() || "[DONE]".equals(data)) {
            return null;
        }
        try {
            JSONArray choices = new JSONObject(data).optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                return null;
            }
            JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
            if (delta == null) {
                return null;
            }
            String text = delta.optString("content", "");
            return text.isEmpty() ? null : text;
        } catch (Exception e) {
            // A malformed or keep-alive line must not abort a stream that is otherwise fine.
            return null;
        }
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

    /** Non-ASCII count: a Persian transcript that arrives here as 0 has been mangled upstream. */
    private static int countNonAscii(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 127) {
                count++;
            }
        }
        return count;
    }

    private static boolean isEmpty(CharSequence value) {
        return value == null || value.length() == 0;
    }
}
