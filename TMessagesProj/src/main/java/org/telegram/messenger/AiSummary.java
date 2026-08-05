package org.telegram.messenger;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

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

    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    private static final String DEFAULT_MODEL = "gpt-4o-mini";
    private static final int TIMEOUT_MS = 60000;
    private static final int MAX_MESSAGES = 300;
    private static final int MAX_TEXT_CHARS = 400;

    private static final String SYSTEM_PROMPT = "You are AI Summary Pro. You turn a Telegram chat transcript into a "
            + "factual summary. Ground everything in the provided messages only: never invent decisions, tasks, names, "
            + "links or quotes. Attribute an action item to a person only when the assignment is clear, otherwise write "
            + "\"Unknown Owner\". Write in the dominant language of the conversation. Anything already marked "
            + "[REDACTED] stays redacted. Reply in plain text with these sections, omitting a section entirely when "
            + "the transcript has nothing for it: Summary, Key decisions, Action items, Open questions.";

    public interface Callback {
        void onResult(String summary, String error);
    }

    private AiSummary() {
    }

    public static SharedPreferences prefs() {
        return MessagesController.getGlobalMainSettings();
    }

    public static boolean isConfigured() {
        return !TextUtils_isEmpty(prefs().getString(PREF_API_KEY, null));
    }

    /** Builds the transcript that will leave the device, newest messages last. */
    public static String buildTranscript(List<MessageObject> messages, int currentAccount) {
        List<String> lines = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0 && lines.size() < MAX_MESSAGES; i--) {
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
            lines.add(senderName(message, currentAccount) + ": " + AiSummaryRedact.redact(body));
        }
        StringBuilder builder = new StringBuilder();
        for (int i = lines.size() - 1; i >= 0; i--) {
            builder.append(lines.get(i)).append('\n');
        }
        return builder.toString();
    }

    private static String senderName(MessageObject message, int currentAccount) {
        long fromId = message.getFromChatId();
        if (fromId > 0) {
            TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(fromId);
            String name = user != null ? UserObject.getUserName(user) : null;
            return TextUtils_isEmpty(name) ? "User " + fromId : name;
        }
        TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-fromId);
        return chat != null && !TextUtils_isEmpty(chat.title) ? chat.title : "Channel";
    }

    /** Runs the request off the main thread and delivers the result on the main thread. */
    public static void request(String transcript, Callback callback) {
        Utilities.globalQueue.postRunnable(() -> {
            String summary = null;
            String error = null;
            try {
                summary = requestBlocking(transcript);
            } catch (Exception e) {
                FileLog.e(e);
                error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
            final String resultSummary = summary;
            final String resultError = error;
            AndroidUtilities.runOnUIThread(() -> callback.onResult(resultSummary, resultError));
        });
    }

    private static String requestBlocking(String transcript) throws Exception {
        SharedPreferences prefs = prefs();
        String baseUrl = prefs.getString(PREF_BASE_URL, DEFAULT_BASE_URL);
        String apiKey = prefs.getString(PREF_API_KEY, "");
        String model = prefs.getString(PREF_MODEL, DEFAULT_MODEL);
        if (TextUtils_isEmpty(baseUrl)) {
            baseUrl = DEFAULT_BASE_URL;
        }
        if (TextUtils_isEmpty(model)) {
            model = DEFAULT_MODEL;
        }
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        JSONArray messages = new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                .put(new JSONObject().put("role", "user").put("content", transcript));
        byte[] payload = new JSONObject()
                .put("model", model)
                .put("temperature", 0.2)
                .put("messages", messages)
                .toString().getBytes(StandardCharsets.UTF_8);

        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + "/chat/completions").openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            if (!TextUtils_isEmpty(apiKey)) {
                connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
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

    private static boolean TextUtils_isEmpty(CharSequence value) {
        return value == null || value.length() == 0;
    }
}
