package org.telegram.messenger;

import android.content.SharedPreferences;
import android.os.SystemClock;

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
import java.util.concurrent.atomic.AtomicBoolean;
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

    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    public static final String DEFAULT_MODEL = "gpt-4o-mini";
    public static final float DEFAULT_TEMPERATURE = 0.2f;
    public static final int DEFAULT_MAX_TOKENS = 8192;
    public static final float DEFAULT_TOP_P = 1f;
    public static final int MIN_RANGE = 25;

    /** Messages per request before the transcript is split and merged. */
    private static final int CHUNK_MESSAGES = 250;
    /**
     * Characters per request. 250 messages can be 100k chars (~25k tokens), which overflows the
     * context window of most free models — the message count alone was not a real limit.
     * ponytail: chars/4 is the usual rough token ratio; Persian runs worse, hence the low budget.
     */
    private static final int CHUNK_CHARS = 24000;
    /**
     * Messages per history page, derived from the range so the bar always moves in ~10 steps.
     *
     * A fixed small page looks smooth for 50 but turns 1000 into 100 round trips, which is how a
     * client earns a flood wait. Clamped to the TL cap of 100.
     */
    static int pageSize(int limit) {
        return Math.max(10, Math.min(100, limit / 10));
    }
    private static final int TIMEOUT_MS = 90000;
    /**
     * How long a request may stay completely silent before it is treated as a failure.
     *
     * A free-tier model can sit in a provider queue for minutes; the read timeout above never
     * fires while keep-alive traffic trickles in, so the dialog used to sit at 0% indefinitely
     * with no way to tell "queued" from "hung". Measured from the moment the payload is written:
     * for a streaming request the clock stops at the first content token, for a plain request at
     * the response headers.
     */
    private static final int FIRST_TOKEN_TIMEOUT_MS = 45000;
    /** Absolute ceiling for one request, first token or not. */
    private static final int TOTAL_RESPONSE_TIMEOUT_MS = 180000;
    /** Marks the two watchdog failures so the UI can offer Retry instead of a raw message. */
    public static final String ERROR_SLOW_RESPONSE = "ai_summary_slow_response";
    private static final int MAX_TEXT_CHARS = 400;
    private static final Pattern REASONING_TAG =
            Pattern.compile("(?is)<(think|thinking|reasoning)>.*?</\\1>");
    /** Quoted snake_case JSON keys: Latin letters that belong to the schema, not to the answer. */
    private static final Pattern SCHEMA_KEY = Pattern.compile("\"[a-z][a-z0-9_]*\"\\s*:");

    /**
     * The language rule, kept separate so it can be re-attached to a custom prompt.
     *
     * A user who edited the system prompt before this rule existed was sending a prompt with no
     * language instruction at all, which is why the violation kept "coming back" after each fix.
     */
    static final String LANGUAGE_BLOCK = "LANGUAGE IS NON-NEGOTIABLE: every single string value in your JSON output —\n"
            + "in every field, from every chunk, in every merge step — must be in the\n"
            + "dominant language of the conversation, using only characters/words that\n"
            + "belong to that language (plus English JSON keys). Never insert a word,\n"
            + "character, or script from any third language, even accidentally. Check\n"
            + "this before finalizing your response. Detect that dominant language from the\n"
            + "actual message text provided; never default to English. A word that was already\n"
            + "in another language in the source messages may stay as it was.\n"
            + "This applies no matter how short the conversation is: 20 messages in Persian\n"
            + "still require a fully Persian answer. The language of these instructions is\n"
            + "irrelevant — never mirror it. If the messages use the Arabic script (Persian,\n"
            + "Farsi, Dari, Arabic), every sentence you write must use that same script.\n"
            + "Exception: proper nouns, product/brand/technology names, API names, library\n"
            + "names, repository names, usernames, and URLs keep their original form and\n"
            + "are never translated, even when everything else is in the dominant language.";

    /** Appended verbatim when a first attempt came back in the wrong script. */
    private static final String LANGUAGE_RETRY_INSTRUCTION = "YOUR PREVIOUS ATTEMPT WAS REJECTED: it was written in the wrong language.\n"
            + "Read the messages again, identify the script they are written in, and write\n"
            + "EVERY string value in your JSON in that same script. Do not translate the\n"
            + "conversation into English. Do not answer in the language of these instructions.";

    public static final String DEFAULT_SYSTEM_PROMPT = "You are AI Summary Pro, a conversation-intelligence engine that converts Telegram\n"
            + "group chat messages into structured, factual summaries.\n"
            + "\n"
            + LANGUAGE_BLOCK + "\n"
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
            + "4. NEVER characterize a participant's personality, emotional traits, relationship\n"
            + "   dynamics, or psychological state. This covers clinical labels (\"seems depressed\",\n"
            + "   \"anxious\", \"unmotivated\", \"needs support\") AND soft, everyday phrasings that do\n"
            + "   the same thing: \"seems sensitive about X\", \"they support each other emotionally\",\n"
            + "   \"shows a close bond\", \"is insecure about\", \"cares deeply\", \"is frustrated with\".\n"
            + "   Both are forbidden unless someone in the chat explicitly said that about\n"
            + "   themselves or about the relationship, in which case you may quote or paraphrase\n"
            + "   only what they said and attribute it to them.\n"
            + "   ai_insights may describe WHICH topics came up and HOW OFTEN. It must never\n"
            + "   describe WHY people feel a certain way or WHAT KIND OF PEOPLE they are.\n"
            + "   When in doubt, state the observable fact and stop: \"weight was mentioned in\n"
            + "   jokes on several occasions\" is allowed; \"both are sensitive about their weight\"\n"
            + "   is not. Never add an interpretive layer on top of an observation.\n"
            + "   A message like \"nothing feels good\" is a statement in the chat, not a condition\n"
            + "   to assess. sentiment describes the tone of the CONVERSATION as a whole, never\n"
            + "   the psychology of a participant. This applies to every field, including\n"
            + "   ai_insights, sentiment.overall_mood, executive_summary and daily_recap. If a\n"
            + "   topic is personal or sensitive, report only what was literally said, or leave\n"
            + "   the field empty.\n"
            + "   sentiment.overall_mood describes the tone/register of the CONVERSATION as a\n"
            + "   whole (\"friendly and humorous\", \"serious and formal\", \"neutral\", \"mixed\" —\n"
            + "   written in the output language) — never a participant's emotional or mental\n"
            + "   state (\"worried\", \"anxious\", \"sad\"). If a participant literally stated a\n"
            + "   feeling, that fact belongs in daily_recap or executive_summary as an\n"
            + "   attributed statement, not in overall_mood.\n"
            + "5. Anything already marked [REDACTED] stays redacted. Never reproduce a credential,\n"
            + "   API key, token, card number or password, even inside quotes or code blocks.\n"
            + "6. Quotes: at most 5, each under 25 words, each with speaker and time. Never alter\n"
            + "   the wording.\n"
            + "7. Sentiment percentages must sum to 100. They should reflect the overall mix of\n"
            + "   tone across the conversation's substantive messages, not be derived mechanically\n"
            + "   from raw message counts. This is a coarse estimate; consistency matters more\n"
            + "   than precision.\n"
            + "8. Leave statistics at 0 — the app fills in the real counts itself. Likewise, do not\n"
            + "   compute message counts for most_active_members: the app computes those\n"
            + "   deterministically from the same message batch. Return most_active_members empty\n"
            + "   or omit it — anything you put there is discarded.\n"
            + "9. If the range contains zero usable messages, return the schema with empty fields\n"
            + "   and set meta.note to explain why.\n"
            + "10. DATES — For any date or timestamp you output (timeline entries, quote timestamps,\n"
            + "   etc.), use ONLY the exact date/time values provided with each message in\n"
            + "   the input. Never infer, assume, or default to a year or date from your own\n"
            + "   training data or general knowledge — if a message's date isn't explicitly\n"
            + "   provided in the input, omit the date rather than guessing one. If an input\n"
            + "   message provides only a time without an explicit date, output only the time\n"
            + "   (HH:MM) for that entry — do not attach a guessed date.\n"
            + "11. Output ONLY valid JSON matching the schema below. No markdown fences, no\n"
            + "   commentary, no preamble or postamble.\n"
            + "12. TIMELINE — Collapse repetition. If the same type of message or action repeats\n"
            + "   more than 3 times within a short window (a few minutes to an hour), write ONE\n"
            + "   entry describing the pattern and its time range instead of one entry per\n"
            + "   occurrence — for example \"between 01:57 and 02:52 this request was repeated\n"
            + "   25+ times\". Never output more than 3 near-identical consecutive timeline\n"
            + "   entries. The timeline is a story of what happened, not a log of every message.\n"
            + "13. REAL WORDS ONLY — Use only real, standard words of the output language. If you\n"
            + "   are unsure whether a word exists or how to phrase something, use a simpler and\n"
            + "   more common word instead of inventing or garbling one. Never emit a string that\n"
            + "   is not a real word in the output language. Prefer plain, everyday vocabulary\n"
            + "   over rare or elaborate constructions.\n"
            + "14. LINKS — important_links.url must always be a clickable absolute URL starting\n"
            + "   with http:// or https://. A Telegram @username or channel mention is not a URL:\n"
            + "   convert it to https://t.me/username (drop the @). A t.me/... or example.com/...\n"
            + "   without a scheme gets https:// prefixed. If something cannot be turned into a\n"
            + "   real URL, leave it out of important_links and mention it in the prose instead.\n"
            + "15. meta.language must be an ISO 639-1 two-letter code (e.g. \"fa\", \"en\", \"ar\"),\n"
            + "   never a full language name written out.\n"
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
            + "  \"meta\": { \"range\": \"string\", \"language\": \"ISO 639-1 two-letter code, e.g. fa/en/ar\", \"note\": \"string|null\" },\n"
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
            + "  \"most_active_members\": [],\n"
            + "  \"timeline\": [{ \"time\": \"YYYY-MM-DDTHH:MM if a full date+time is available in the input; otherwise just HH:MM if only a time was given. Copied exactly from the input — never invented.\", \"event\": \"string\" }],\n"
            + "  \"ai_insights\": [\"string\"],\n"
            + "  \"sentiment\": { \"positive_pct\": 0, \"neutral_pct\": 0, \"negative_pct\": 0, \"overall_mood\": \"string\" },\n"
            + "  \"statistics\": { \"messages\": 0, \"participants\": 0, \"links\": 0, \"duration_minutes\": 0 },\n"
            + "  \"important_quotes\": [{ \"text\": \"string\", \"author\": \"string\", \"time\": \"YYYY-MM-DDTHH:MM if a full date+time is available in the input; otherwise just HH:MM if only a time was given. Copied exactly from the input — never invented.\" }],\n"
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

    /** Request-level progress: which chunk of how many is being summarized. */
    public interface Stage {
        void onStep(int done, int total);
    }

    /**
     * Whether the current request is still waiting for the provider to say anything.
     *
     * A queued free-tier model produces no bytes for minutes, and a progress bar frozen at 0%
     * reads as a crash. The UI uses this to switch between "connecting" and "receiving".
     */
    public interface Waiting {
        void onWaiting(boolean waiting);
    }

    /**
     * One handle that aborts everything a single summary run owns: the in-flight TL history
     * request and the HTTP connection to the model.
     *
     * Closing the dialog used to hide the UI and leave both running, so a cancelled summary kept
     * burning network and quota, and a second tap raced with the first run's callbacks.
     */
    public static class Cancellation {

        private volatile boolean cancelled;
        private volatile HttpURLConnection connection;
        private volatile int tlToken;
        private volatile int tlAccount = -1;

        public void cancel() {
            cancelled = true;
            HttpURLConnection open = connection;
            connection = null;
            if (open != null) {
                // disconnect() unblocks a thread parked in read(): the only way to stop an
                // HttpURLConnection mid-response.
                open.disconnect();
            }
            int token = tlToken;
            int account = tlAccount;
            tlToken = 0;
            if (token != 0 && account >= 0) {
                ConnectionsManager.getInstance(account).cancelRequest(token, false);
            }
        }

        public boolean isCancelled() {
            return cancelled;
        }

        void attach(HttpURLConnection open) {
            connection = open;
            if (cancelled) {
                open.disconnect();
            }
        }

        void detach() {
            connection = null;
        }

        void attachTl(int account, int token) {
            tlAccount = account;
            tlToken = token;
            if (cancelled) {
                ConnectionsManager.getInstance(account).cancelRequest(token, false);
            }
        }
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

    /**
     * The system prompt actually sent, with the language rule guaranteed present.
     *
     * A prompt the user customised before the language block existed has no language instruction
     * at all, which is why "the language fix stopped working" kept recurring: the fix was in
     * DEFAULT_SYSTEM_PROMPT while the stored copy was what got sent. The block is re-attached at
     * the top of any custom prompt that lacks it instead of silently trusting the stored text.
     */
    public static String systemPrompt() {
        String prompt = prefs().getString(PREF_SYSTEM_PROMPT, null);
        if (isEmpty(prompt)) {
            return DEFAULT_SYSTEM_PROMPT;
        }
        if (prompt.contains("LANGUAGE IS NON-NEGOTIABLE")) {
            return prompt;
        }
        return LANGUAGE_BLOCK + "\n\n" + prompt;
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
    public static void request(List<String> transcript, Progress progress, Stage stage, Waiting waiting,
                              Cancellation cancellation, Callback callback) {
        Utilities.globalQueue.postRunnable(() -> {
            String summary = null;
            String error = null;
            try {
                summary = summarize(transcript, progress, stage, waiting, cancellation);
            } catch (Exception e) {
                FileLog.e(e);
                error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
            // A cancelled run must not deliver anything: no summary, no error dialog.
            if (cancellation != null && cancellation.isCancelled()) {
                return;
            }
            final String resultSummary = summary == null ? null : normalizeOutput(applyStatistics(summary, transcript));
            final String resultError = error;
            AndroidUtilities.runOnUIThread(() -> callback.onResult(resultSummary, resultError));
        });
    }

    /**
     * Post-processes the model's object for the two things a prompt rule cannot guarantee:
     * a timeline that repeats one event dozens of times, and a "link" that is not a link.
     *
     * Both were asked of the model in RULES 12 and 14; this is the deterministic backstop for when
     * it ignores them. Applied after applyStatistics so a failure here cannot lose the summary.
     */
    static String normalizeOutput(String summary) {
        try {
            String body = summary.trim();
            int start = body.indexOf('{');
            int end = body.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return summary;
            }
            JSONObject root = new JSONObject(body.substring(start, end + 1));
            JSONArray timeline = root.optJSONArray("timeline");
            if (timeline != null) {
                root.put("timeline", collapseTimeline(timeline));
            }
            JSONArray links = root.optJSONArray("important_links");
            if (links != null) {
                root.put("important_links", normalizeLinks(links));
            }
            return root.toString();
        } catch (Exception e) {
            FileLog.e(e);
            return summary;
        }
    }

    /**
     * Folds runs of near-identical consecutive entries into one, keeping the time span.
     *
     * A chat where one request is repeated 25 times produced 25 timeline rows, which buries the
     * events that only happened once. The marker appended to the kept entry is digits and
     * punctuation only — "×25 (01:57–02:52)" — so it carries no language of its own and cannot
     * reintroduce the wrong-script problem.
     */
    static JSONArray collapseTimeline(JSONArray timeline) {
        JSONArray out = new JSONArray();
        int i = 0;
        while (i < timeline.length()) {
            String key = timelineKey(timeline.opt(i));
            int run = 1;
            while (i + run < timeline.length() && key != null && key.equals(timelineKey(timeline.opt(i + run)))) {
                run++;
            }
            if (run <= 3) {
                for (int j = 0; j < run; j++) {
                    out.put(timeline.opt(i + j));
                }
            } else {
                Object first = timeline.opt(i);
                Object last = timeline.opt(i + run - 1);
                if (first instanceof JSONObject) {
                    try {
                        JSONObject collapsed = new JSONObject(first.toString());
                        String from = clockOf(first);
                        String to = clockOf(last);
                        String span = from.isEmpty() || to.isEmpty() || from.equals(to)
                                ? "" : " (" + from + "–" + to + ")";
                        collapsed.put("event", collapsed.optString("event", "").trim() + " ×" + run + span);
                        out.put(collapsed);
                    } catch (Exception e) {
                        out.put(first);
                    }
                } else {
                    out.put(first);
                }
            }
            i += run;
        }
        return out;
    }

    /** Comparison key for a timeline entry: its event text reduced to letters and digits. */
    private static String timelineKey(Object entry) {
        String event = entry instanceof JSONObject
                ? ((JSONObject) entry).optString("event", "")
                : String.valueOf(entry);
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < event.length(); i++) {
            char c = event.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                key.append(Character.toLowerCase(c));
            }
        }
        return key.length() == 0 ? null : key.toString();
    }

    /** "HH:MM" out of an ISO timestamp, or the raw value when it is already a clock time. */
    private static String clockOf(Object entry) {
        if (!(entry instanceof JSONObject)) {
            return "";
        }
        String time = ((JSONObject) entry).optString("time", "").trim();
        int t = time.indexOf('T');
        if (t >= 0 && time.length() >= t + 6) {
            return time.substring(t + 1, t + 6);
        }
        return time.length() == 5 && time.charAt(2) == ':' ? time : "";
    }

    /**
     * Makes every important_links entry clickable, dropping the ones that cannot be.
     *
     * The model returned a Telegram mention (@channel) as a url, which renders as dead text. A
     * mention becomes https://t.me/name, a scheme-less host gets https://, and anything else is
     * removed rather than shown as a link that does nothing.
     */
    static JSONArray normalizeLinks(JSONArray links) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < links.length(); i++) {
            Object item = links.opt(i);
            if (item instanceof JSONObject) {
                JSONObject link = (JSONObject) item;
                String url = normalizeUrl(link.optString("url", ""));
                if (url.isEmpty()) {
                    continue;
                }
                try {
                    JSONObject copy = new JSONObject(link.toString());
                    copy.put("url", url);
                    out.put(copy);
                } catch (Exception e) {
                    out.put(item);
                }
            } else {
                String url = normalizeUrl(String.valueOf(item));
                if (!url.isEmpty()) {
                    out.put(url);
                }
            }
        }
        return out;
    }

    /** Absolute URL for a mention or scheme-less host, or "" when the value is not link-like. */
    static String normalizeUrl(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.isEmpty() || "null".equals(value)) {
            return "";
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value;
        }
        if (value.startsWith("@")) {
            String name = value.substring(1).trim();
            // Telegram usernames are letters, digits and underscore; anything else is prose that
            // happened to start with @, not a mention.
            return name.matches("[A-Za-z0-9_]{3,}") ? "https://t.me/" + name : "";
        }
        if (value.startsWith("t.me/") || value.startsWith("www.")) {
            return "https://" + value;
        }
        // host/path with a plausible TLD and no spaces: the model dropped the scheme.
        if (!value.contains(" ") && value.matches("[A-Za-z0-9._~-]+\\.[A-Za-z]{2,}(/\\S*)?")) {
            return "https://" + value;
        }
        return "";
    }

    /**
     * Fetches the last {@code limit} messages of a dialog from the server, newest first.
     *
     * The local cache only holds what was already downloaded — roughly the last few dozen
     * messages — so reading it made every range return the same rows and the same
     * duration_minutes. messages.getHistory caps a page at 100, hence the paging loop.
     *
     * The callback's error string is null unless the fetch stopped early (flood wait, unknown
     * peer); the messages collected before the failure are still delivered so a partial range is
     * summarized rather than dropped.
     *
     * ponytail: sequential pages, one in flight at a time. 1000 messages is 10 round trips;
     * parallelise only if that ever feels slow.
     */
    public static void fetchHistory(int currentAccount, long dialogId, int limit, Progress progress,
                                    Cancellation cancellation, Utilities.Callback2<ArrayList<TLRPC.Message>, String> callback) {
        fetchPage(currentAccount, dialogId, limit, 0, new ArrayList<>(), progress, cancellation, callback);
    }

    private static void fetchPage(int currentAccount, long dialogId, int limit, int offsetId,
                                  ArrayList<TLRPC.Message> collected, Progress progress,
                                  Cancellation cancellation, Utilities.Callback2<ArrayList<TLRPC.Message>, String> callback) {
        if (cancellation != null && cancellation.isCancelled()) {
            return;
        }
        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
        req.offset_id = offsetId;
        req.limit = Math.min(pageSize(limit), limit - collected.size());
        if (req.peer == null || req.limit <= 0) {
            String reason = req.peer == null && collected.isEmpty() ? "chat peer not found" : null;
            AndroidUtilities.runOnUIThread(() -> callback.run(collected, reason));
            return;
        }
        final int before = collected.size();
        int token = ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (cancellation != null && cancellation.isCancelled()) {
                return;
            }
            if (error != null || !(response instanceof TLRPC.messages_Messages)) {
                String reason = error != null
                        ? "Telegram error " + error.code + ": " + error.text
                        : "unexpected empty response";
                // The fetch phase used to swallow MTProto errors whole, so a flood wait during a
                // 1000-message range surfaced as "no messages to summarize" with no log line.
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e("AiSummary: fetch stopped after " + collected.size() + " of " + limit + " messages: " + reason);
                }
                callback.run(collected, reason);
                return;
            }
            TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
            // Sender names are resolved from the controller's caches, so the page's users and
            // chats have to be registered before the transcript is built.
            MessagesController.getInstance(currentAccount).putUsers(res.users, false);
            MessagesController.getInstance(currentAccount).putChats(res.chats, false);
            collected.addAll(res.messages);
            if (progress != null) {
                progress.onTokens(collected.size());
            }
            if (res.messages.isEmpty() || collected.size() >= limit || collected.size() == before) {
                callback.run(collected, null);
            } else {
                fetchPage(currentAccount, dialogId, limit, res.messages.get(res.messages.size() - 1).id,
                        collected, progress, cancellation, callback);
            }
        }));
        if (cancellation != null) {
            cancellation.attachTl(currentAccount, token);
        }
    }

    /**
     * Overwrites the model's statistics and most_active_members with counts from the transcript.
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
            // Insertion-ordered so equal counts keep the order senders first appear in the chat,
            // which makes the ranking stable across runs of the same range.
            java.util.LinkedHashMap<String, Integer> counts = new java.util.LinkedHashMap<>();
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
                    String sender = line.substring(close + 2, colon);
                    Integer seen = counts.get(sender);
                    counts.put(sender, seen == null ? 1 : seen + 1);
                }
            }
            stats.put("messages", transcript.size());
            stats.put("participants", counts.size());
            stats.put("duration_minutes", first > 0 && last > first ? (last - first) / 60000L : 0);
            // Whatever the model returned here is discarded: the app counted the same batch and
            // cannot be wrong about it, while the model was inventing per-person totals.
            root.put("most_active_members", activeMembers(counts));
            return root.toString();
        } catch (Exception e) {
            // Never lose a good summary over a statistics rewrite.
            FileLog.e(e);
            return summary;
        }
    }

    /** Senders ranked by message count, highest first, ties in first-seen order. */
    private static JSONArray activeMembers(java.util.LinkedHashMap<String, Integer> counts) throws Exception {
        List<java.util.Map.Entry<String, Integer>> ranked = new ArrayList<>(counts.entrySet());
        // Stable sort on the descending count keeps the insertion order of equal counts.
        java.util.Collections.sort(ranked, (a, b) -> b.getValue() - a.getValue());
        JSONArray members = new JSONArray();
        for (java.util.Map.Entry<String, Integer> entry : ranked) {
            members.put(new JSONObject().put("name", entry.getKey()).put("message_count", entry.getValue()));
        }
        return members;
    }

    private static long parseStamp(String value) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).parse(value).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Splits at whichever limit hits first: message count or characters.
     *
     * Returns the exclusive end index of the chunk starting at {@code from}. A line is only taken
     * when it still fits, so a chunk never exceeds the budget — the earlier add-then-check version
     * overshot by a whole line, which was invisible for 200-char transcript lines but doubled the
     * payload when the lines being chunked were 20k-char partial summaries. Always advances by at
     * least one line so a single over-long line can never loop forever.
     */
    static int chunkEnd(List<String> transcript, int from) {
        int chars = 0;
        int i = from;
        while (i < transcript.size() && i - from < CHUNK_MESSAGES) {
            int next = chars + transcript.get(i).length() + 1;
            if (next > CHUNK_CHARS && i > from) {
                break;
            }
            chars = next;
            i++;
        }
        return Math.max(i, from + 1);
    }

    private static String summarize(List<String> transcript, Progress progress, Stage stage, Waiting waiting, Cancellation cancellation) throws Exception {
        // Chunk boundaries are known before any request, so the user can be told "1 of 4" instead
        // of watching a still bar through four sequential calls.
        List<int[]> bounds = new ArrayList<>();
        for (int start = 0; start < transcript.size(); ) {
            int end = chunkEnd(transcript, start);
            bounds.add(new int[]{start, end});
            start = end;
        }
        List<String> partials = new ArrayList<>();
        for (int i = 0; i < bounds.size(); i++) {
            if (stage != null) {
                final int done = i;
                final int total = bounds.size();
                AndroidUtilities.runOnUIThread(() -> stage.onStep(done, total));
            }
            int[] b = bounds.get(i);
            String source = join(transcript, b[0], b[1]);
            String partial = complete(systemPrompt(), source, progress, waiting, cancellation);
            partials.add(enforceLanguage(partial, source, progress, waiting, cancellation));
        }
        // Incremental fold: the merge step is itself a request with the same context limit, so
        // merging 10 partials in one call would overflow exactly like the un-chunked transcript did.
        // Each round merges only as many partials as fit the budget, until one is left.
        while (partials.size() > 1) {
            List<String> merged = new ArrayList<>();
            for (int start = 0; start < partials.size(); ) {
                int end = chunkEnd(partials, start);
                if (end - start == 1) {
                    // A partial too large to pair with anything passes through untouched rather
                    // than being re-summarized alone, which would only lose detail.
                    merged.add(partials.get(start));
                } else {
                    String source = join(partials, start, end);
                    String result = complete(systemPrompt() + "\n\n" + MERGE_INSTRUCTION,
                            source, progress, waiting, cancellation);
                    // The merge step is where a correct Persian partial used to come back in
                    // English, so it needs the same guard as the chunk step.
                    merged.add(enforceLanguage(result, source, progress, waiting, cancellation));
                }
                start = end;
            }
            if (merged.size() >= partials.size()) {
                // No round made progress: every partial alone exceeds half the char budget, so no
                // two can share a merge call. Returning only the first chunk silently dropped the
                // newer spans while applyStatistics still reported the full range; merge locally
                // instead — offline, deterministic, and lossless.
                return mergeObjects(merged);
            }
            partials = merged;
        }
        return partials.isEmpty() ? "" : partials.get(0);
    }

    /**
     * Deterministic local merge for the fold's stall case: no model call, nothing dropped.
     *
     * Arrays append in chunk order with exact-duplicate elements removed, nested objects merge
     * key by key, strings join with a newline. Numbers and type mismatches keep the earlier
     * span's value — they are model estimates anyway, and statistics is overwritten in-app later.
     */
    static String mergeObjects(List<String> partials) {
        JSONObject merged = null;
        for (String partial : partials) {
            try {
                String body = partial.trim();
                int start = body.indexOf('{');
                int end = body.lastIndexOf('}');
                JSONObject object = new JSONObject(body.substring(start, end + 1));
                if (merged == null) {
                    merged = object;
                } else {
                    mergeInto(merged, object);
                }
            } catch (Exception e) {
                // One malformed partial must not discard the others' content.
                FileLog.e(e);
            }
        }
        return merged == null ? (partials.isEmpty() ? "" : partials.get(0)) : merged.toString();
    }

    private static void mergeInto(JSONObject merged, JSONObject object) {
        for (java.util.Iterator<String> it = object.keys(); it.hasNext(); ) {
            String key = it.next();
            try {
                merged.put(key, mergeValues(merged.opt(key), object.opt(key)));
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    private static Object mergeValues(Object base, Object add) {
        if (base == null || JSONObject.NULL.equals(base)) {
            return add;
        }
        if (add == null || JSONObject.NULL.equals(add)) {
            return base;
        }
        if (base instanceof JSONArray && add instanceof JSONArray) {
            JSONArray array = new JSONArray();
            java.util.Set<String> seen = new java.util.LinkedHashSet<>();
            appendUnique(array, seen, (JSONArray) base);
            appendUnique(array, seen, (JSONArray) add);
            return array;
        }
        if (base instanceof JSONObject && add instanceof JSONObject) {
            mergeInto((JSONObject) base, (JSONObject) add);
            return base;
        }
        if (base instanceof String && add instanceof String) {
            String first = ((String) base).trim();
            String second = ((String) add).trim();
            return first.isEmpty() ? second : second.isEmpty() ? first : first + "\n" + second;
        }
        return base;
    }

    private static void appendUnique(JSONArray out, java.util.Set<String> seen, JSONArray source) {
        for (int i = 0; i < source.length(); i++) {
            Object item = source.opt(i);
            if (item == null || JSONObject.NULL.equals(item)) {
                continue;
            }
            if (seen.add(item.toString())) {
                out.put(item);
            }
        }
    }

    /**
     * Re-requests a summary once when it came back in a different script than the messages.
     *
     * Prompt-only defences kept failing on short ranges: 50 Persian messages came back fully
     * English while 100 and 500 of the same chat were correct, which is a model coin flip no
     * wording can close. Detection is script-level on purpose — telling Persian from Dari is not
     * possible here and not needed, while Latin-vs-Arabic is unambiguous and is the failure that
     * actually happens. One retry only: a second wrong answer is logged and returned rather than
     * burning quota in a loop.
     */
    private static String enforceLanguage(String summary, String source, Progress progress,
                                         Waiting waiting, Cancellation cancellation) throws Exception {
        // summaryText on both sides: in the merge step the "source" is itself a set of JSON
        // partials, and counting their English schema keys would make every merge look Latin.
        // For a plain transcript there is no object to walk and the text passes through unchanged.
        String want = dominantScript(summaryText(source));
        String got = dominantScript(summaryText(summary));
        if (want == null || got == null || want.equals(got)) {
            return summary;
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.e("AiSummary: language mismatch input=" + want + " output=" + got + ", retrying once");
        }
        String retry = complete(systemPrompt() + "\n\n" + LANGUAGE_RETRY_INSTRUCTION,
                source, progress, waiting, cancellation);
        String retryScript = dominantScript(summaryText(retry));
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("AiSummary: language retry produced=" + retryScript
                    + (want.equals(retryScript) ? " (fixed)" : " (still wrong, keeping it)"));
        }
        // Even a still-wrong retry is a complete summary; a second failure means the model cannot
        // follow the rule, and an error dialog would be worse than a readable wrong-language answer.
        return want.equals(retryScript) ? retry : summary;
    }

    /**
     * Concatenated string values of a summary object, so detection sees the prose and not the
     * English schema keys — which would make every output look Latin.
     *
     * The merge step passes several objects at once, which is not one parseable value; those fall
     * back to stripping the snake_case keys textually. A plain transcript parses as neither and
     * passes through unchanged, which is what detection wants.
     */
    static String summaryText(String summary) {
        try {
            String body = summary.trim();
            int start = body.indexOf('{');
            int end = body.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return summary;
            }
            StringBuilder text = new StringBuilder();
            collectStrings(new JSONObject(body.substring(start, end + 1)), text);
            return text.toString();
        } catch (Exception e) {
            return SCHEMA_KEY.matcher(summary).replaceAll(" ");
        }
    }

    private static void collectStrings(Object value, StringBuilder out) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            for (java.util.Iterator<String> it = object.keys(); it.hasNext(); ) {
                collectStrings(object.opt(it.next()), out);
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                collectStrings(array.opt(i), out);
            }
        } else if (value instanceof String) {
            out.append(' ').append(value);
        }
    }

    /**
     * "arabic", "cyrillic", "latin" or null when there is not enough letter evidence to judge.
     *
     * Only letters count: digits, punctuation, timestamps and URLs are script-neutral and a
     * transcript is full of them. A single foreign word does not flip the verdict — the winner
     * needs a clear majority, so a Persian summary quoting an English product name still reads as
     * Arabic script.
     */
    static String dominantScript(String text) {
        if (text == null) {
            return null;
        }
        int arabic = 0;
        int cyrillic = 0;
        int latin = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x0600 && c <= 0x06FF || c >= 0x0750 && c <= 0x077F || c >= 0xFB50 && c <= 0xFDFF
                    || c >= 0xFE70 && c <= 0xFEFF) {
                arabic++;
            } else if (c >= 0x0400 && c <= 0x04FF) {
                cyrillic++;
            } else if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') {
                latin++;
            }
        }
        int total = arabic + cyrillic + latin;
        // Below this there is no signal: an empty schema or a two-word note must not trigger a
        // retry that costs a whole request.
        if (total < 40) {
            return null;
        }
        int best = Math.max(arabic, Math.max(cyrillic, latin));
        if (best * 2 <= total) {
            return null;
        }
        return best == arabic ? "arabic" : best == cyrillic ? "cyrillic" : "latin";
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

    private static String complete(String systemPrompt, String userContent, Progress progress, Waiting waiting, Cancellation cancellation) throws Exception {
        if (cancellation != null && cancellation.isCancelled()) {
            throw new Exception("cancelled");
        }
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
                    + " est_input_tokens=" + (systemPrompt.length() + userContent.length()) / 4
                    + " stream=" + stream
                    + " model=" + model);
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + "/chat/completions").openConnection();
        if (cancellation != null) {
            cancellation.attach(connection);
        }
        // The watchdog owns the "silent provider" case that no socket timeout catches: it fires
        // once and disconnects, which unblocks the reader thread exactly the way Cancel does.
        // firstByte flips as soon as the provider says anything real, so a slow-but-alive stream
        // is never killed mid-answer.
        final AtomicBoolean firstByte = new AtomicBoolean();
        final AtomicBoolean timedOut = new AtomicBoolean();
        final long startedAt = SystemClock.elapsedRealtime();
        final long[] ttfb = { -1 };
        final HttpURLConnection watched = connection;
        final Runnable firstByteWatchdog = () -> {
            if (!firstByte.get()) {
                timedOut.set(true);
                watched.disconnect();
            }
        };
        final Runnable totalWatchdog = () -> {
            timedOut.set(true);
            watched.disconnect();
        };
        if (waiting != null) {
            AndroidUtilities.runOnUIThread(() -> waiting.onWaiting(true));
        }
        final Runnable markReceiving = () -> {
            if (firstByte.compareAndSet(false, true)) {
                ttfb[0] = SystemClock.elapsedRealtime() - startedAt;
                AndroidUtilities.cancelRunOnUIThread(firstByteWatchdog);
                if (waiting != null) {
                    AndroidUtilities.runOnUIThread(() -> waiting.onWaiting(false));
                }
            }
        };
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
            // Armed before the socket work, not after: a network that stalls the TCP/TLS handshake
            // instead of refusing it would otherwise sit inside getOutputStream for the full
            // connect timeout with the watchdog not yet running.
            AndroidUtilities.runOnUIThread(firstByteWatchdog, FIRST_TOKEN_TIMEOUT_MS);
            AndroidUtilities.runOnUIThread(totalWatchdog, TOTAL_RESPONSE_TIMEOUT_MS);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload);
            }
            int code = connection.getResponseCode();
            if (code >= 400) {
                markReceiving.run();
                String body = trimForError(readAll(connection.getErrorStream()));
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("AiSummary: HTTP " + code + " after " + ttfb[0] + "ms body=" + body);
                }
                throw new Exception("HTTP " + code + ": " + body);
            }
            String content;
            if (stream) {
                // Streaming: headers arriving does not mean the model started generating, so the
                // clock keeps running until a content delta lands.
                content = readStream(connection.getInputStream(), progress, markReceiving, cancellation);
            } else {
                // Non-streaming: the provider holds the connection until the whole answer exists,
                // so response headers are the first real sign of life there is.
                markReceiving.run();
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
                // Reasoning models can return content:null with the text in their own key.
                // optString keeps that out of getString's cryptic type error so requireJson
                // below reports the real problem instead.
                content = choice.getJSONObject("message").optString("content", "");
            }
            content = requireJson(content);
            if (content.isEmpty()) {
                throw new Exception("empty response");
            }
            return content;
        } catch (Exception e) {
            // A watchdog disconnect surfaces as a generic IOException, so the real cause has to be
            // rewritten here or the user sees "unexpected end of stream" for a queued model.
            if (timedOut.get() && (cancellation == null || !cancellation.isCancelled())) {
                long waited = SystemClock.elapsedRealtime() - startedAt;
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e("AiSummary: timing start=0 ttfb=" + (firstByte.get() ? ttfb[0] + "ms" : "never")
                            + " aborted_after=" + waited + "ms model=" + model
                            + " reason=" + (firstByte.get() ? "total response timeout" : "no first token"));
                }
                throw new Exception(ERROR_SLOW_RESPONSE);
            }
            throw e;
        } finally {
            AndroidUtilities.cancelRunOnUIThread(firstByteWatchdog);
            AndroidUtilities.cancelRunOnUIThread(totalWatchdog);
            if (BuildVars.LOGS_ENABLED && !timedOut.get()) {
                // The three numbers that decide whether the model or the network is at fault:
                // ttfb above ~20s repeatedly means the free-tier queue, not the client.
                FileLog.d("AiSummary: timing ttfb=" + (ttfb[0] < 0 ? "never" : ttfb[0] + "ms")
                        + " total=" + (SystemClock.elapsedRealtime() - startedAt) + "ms"
                        + " stream=" + stream + " model=" + model);
            }
            if (cancellation != null) {
                cancellation.detach();
            }
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
    private static String readStream(InputStream stream, Progress progress, Runnable markReceiving, Cancellation cancellation) throws Exception {
        StringBuilder content = new StringBuilder();
        int tokens = 0;
        boolean truncated = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (cancellation != null && cancellation.isCancelled()) {
                    throw new Exception("cancelled");
                }
                truncated |= line.contains("\"finish_reason\":\"length\"") || line.contains("\"finish_reason\": \"length\"");
                String delta = streamDelta(line);
                if (delta == null) {
                    // Keep-alive comments and role-only deltas are not the model generating text,
                    // so they must not stop the first-token clock.
                    continue;
                }
                if (markReceiving != null) {
                    markReceiving.run();
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
        // The provider's own words are the whole diagnostic value: context-window overflow,
        // rate limit and quota errors all look identical once truncated to a generic message.
        return trimmed.length() > 1500 ? trimmed.substring(0, 1500) + "…" : trimmed;
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
