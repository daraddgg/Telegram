package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AiSummary;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

/**
 * Editable AI Summary provider configuration, reachable from the main Settings list.
 *
 * Every field carries a visible label, values are loaded from prefs on open, and a pinned Save
 * button commits them. The stored API key is never rendered: the field shows a mask and is only
 * written back when the user types a replacement.
 */
public class AiSummarySettingsActivity extends BaseFragment {

    private static final int ROW_PROVIDER_HEADER = 0;
    private static final int ROW_BASE_URL = 1;
    private static final int ROW_API_KEY = 2;
    private static final int ROW_MODEL = 3;
    private static final int ROW_PROVIDER_SHADOW = 4;
    private static final int ROW_SAMPLING_HEADER = 5;
    private static final int ROW_TEMPERATURE = 6;
    private static final int ROW_MAX_TOKENS = 7;
    private static final int ROW_TOP_P = 8;
    private static final int ROW_SAMPLING_SHADOW = 9;
    private static final int ROW_ADVANCED_HEADER = 10;
    private static final int ROW_STREAMING = 11;
    private static final int ROW_SYSTEM_PROMPT = 12;
    private static final int ROW_HEADERS = 13;
    private static final int ROW_NOTICE = 14;
    private static final int ROW_COUNT = 15;

    private LabeledEditCell baseUrlCell;
    private LabeledEditCell apiKeyCell;
    private LabeledEditCell modelCell;
    private LabeledEditCell temperatureCell;
    private LabeledEditCell maxTokensCell;
    private LabeledEditCell topPCell;
    private LabeledEditCell headersCell;
    private TextSettingsCell promptCell;

    private boolean streaming;
    private String maskedKey;
    private String systemPrompt;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        // No overlay title: the connection-state overlay ("Updating…", "Connecting…") would
        // otherwise replace this screen's own title.
        actionBar.setAllowOverlayTitle(false);
        actionBar.setTitle(LocaleController.getString(R.string.AiSummary));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        // Stored values are the source of truth for what the fields show; an unset provider URL
        // falls back to the real default rather than an empty box with a misleading hint.
        SharedPreferences prefs = AiSummary.prefs();
        maskedKey = AiSummary.maskKey(prefs.getString(AiSummary.PREF_API_KEY, ""));
        streaming = prefs.getBoolean(AiSummary.PREF_STREAMING, false);
        systemPrompt = AiSummary.systemPrompt();

        baseUrlCell = new LabeledEditCell(context, LocaleController.getString(R.string.AiSummaryBaseUrl),
                AiSummary.DEFAULT_BASE_URL, prefs.getString(AiSummary.PREF_BASE_URL, AiSummary.DEFAULT_BASE_URL), false);
        apiKeyCell = new LabeledEditCell(context, LocaleController.getString(R.string.AiSummaryApiKey),
                maskedKey.isEmpty() ? LocaleController.getString(R.string.AiSummaryApiKeyHint) : maskedKey, "", false);
        apiKeyCell.editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        modelCell = new LabeledEditCell(context, LocaleController.getString(R.string.AiSummaryModel),
                AiSummary.DEFAULT_MODEL, prefs.getString(AiSummary.PREF_MODEL, AiSummary.DEFAULT_MODEL), false);

        temperatureCell = new LabeledEditCell(context, LocaleController.getString(R.string.AiSummaryTemperature),
                "0.2", floatText(prefs.getFloat(AiSummary.PREF_TEMPERATURE, AiSummary.DEFAULT_TEMPERATURE)), false);
        temperatureCell.editText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        maxTokensCell = new LabeledEditCell(context, LocaleController.getString(R.string.AiSummaryMaxTokens),
                "4096", String.valueOf(prefs.getInt(AiSummary.PREF_MAX_TOKENS, AiSummary.DEFAULT_MAX_TOKENS)), false);
        maxTokensCell.editText.setInputType(InputType.TYPE_CLASS_NUMBER);
        topPCell = new LabeledEditCell(context, LocaleController.getString(R.string.AiSummaryTopP),
                "1", floatText(prefs.getFloat(AiSummary.PREF_TOP_P, AiSummary.DEFAULT_TOP_P)), false);
        topPCell.editText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);

        headersCell = new LabeledEditCell(context, LocaleController.getString(R.string.AiSummaryCustomHeaders),
                "X-Title: MyApp", prefs.getString(AiSummary.PREF_HEADERS, ""), true);

        promptCell = new TextSettingsCell(context);
        promptCell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        updatePromptCell();

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));

        RecyclerListView listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(new ListAdapter());
        // Clipping stays on: with clipToPadding(false) the last rows draw inside the bottom
        // padding, i.e. underneath the pinned Save button, which is exactly what hid the notice.
        listView.setPadding(0, 0, 0, dp(72));
        listView.setOnItemClickListener((view, position) -> {
            if (position == ROW_STREAMING) {
                streaming = !streaming;
                ((TextCheckCell) view).setChecked(streaming);
            } else if (position == ROW_SYSTEM_PROMPT) {
                presentFragment(new PromptEditorActivity(this));
            }
        });
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // Pinned so the action is reachable without scrolling past the prompt and headers fields.
        TextView saveButton = new TextView(context);
        saveButton.setGravity(Gravity.CENTER);
        saveButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        saveButton.setTypeface(AndroidUtilities.bold());
        saveButton.setTextColor(getThemedColor(Theme.key_featuredStickers_buttonText));
        saveButton.setText(LocaleController.getString(R.string.Save));
        saveButton.setBackground(Theme.AdaptiveRipple.filledRect(getThemedColor(Theme.key_featuredStickers_addButton), 8));
        saveButton.setOnClickListener(v -> {
            save();
            finishFragment();
        });
        root.addView(saveButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.BOTTOM, 12, 0, 12, 12));

        fragmentView = root;
        return fragmentView;
    }

    void setSystemPrompt(String prompt) {
        systemPrompt = prompt == null || prompt.trim().isEmpty() ? AiSummary.DEFAULT_SYSTEM_PROMPT : prompt;
        updatePromptCell();
    }

    String getSystemPrompt() {
        return systemPrompt;
    }

    private void updatePromptCell() {
        if (promptCell == null) {
            return;
        }
        boolean custom = !AiSummary.DEFAULT_SYSTEM_PROMPT.equals(systemPrompt);
        promptCell.setTextAndValue(LocaleController.getString(R.string.AiSummarySystemPrompt),
                LocaleController.getString(custom ? R.string.AiSummaryPromptCustom : R.string.AiSummaryPromptDefault), true);
    }

    private static String floatText(float value) {
        return value == Math.round(value) ? String.valueOf(Math.round(value)) : String.valueOf(value);
    }

    @Override
    public void onFragmentDestroy() {
        save();
        super.onFragmentDestroy();
    }

    private void save() {
        if (baseUrlCell == null) {
            return;
        }
        SharedPreferences.Editor editor = AiSummary.prefs().edit()
                .putString(AiSummary.PREF_BASE_URL, baseUrlCell.text())
                .putString(AiSummary.PREF_MODEL, modelCell.text())
                .putFloat(AiSummary.PREF_TEMPERATURE, parseFloat(temperatureCell.text(), AiSummary.DEFAULT_TEMPERATURE))
                .putInt(AiSummary.PREF_MAX_TOKENS, parseInt(maxTokensCell.text(), AiSummary.DEFAULT_MAX_TOKENS))
                .putFloat(AiSummary.PREF_TOP_P, parseFloat(topPCell.text(), AiSummary.DEFAULT_TOP_P))
                .putBoolean(AiSummary.PREF_STREAMING, streaming)
                .putString(AiSummary.PREF_HEADERS, headersCell.text());

        if (systemPrompt == null || systemPrompt.trim().isEmpty() || systemPrompt.equals(AiSummary.DEFAULT_SYSTEM_PROMPT)) {
            editor.remove(AiSummary.PREF_SYSTEM_PROMPT);
        } else {
            editor.putString(AiSummary.PREF_SYSTEM_PROMPT, systemPrompt);
        }

        // An untouched key field shows only the mask, so writing it back would destroy the real key.
        String key = apiKeyCell.text();
        if (!key.isEmpty() && !key.equals(maskedKey)) {
            editor.putString(AiSummary.PREF_API_KEY, key);
        }
        editor.apply();
    }

    private static float parseFloat(String value, float fallback) {
        try {
            return Float.parseFloat(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    /** Label above the input so a bare number like "0.2" is never shown without its meaning. */
    private class LabeledEditCell extends LinearLayout {

        final EditTextBoldCursor editText;

        LabeledEditCell(Context context, String label, String hint, String value, boolean multiline) {
            super(context);
            setOrientation(VERTICAL);
            setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
            // 21dp matches HeaderCell's horizontal padding, so labels line up with section headers.
            setPadding(dp(21), dp(10), dp(21), dp(10));

            TextView labelView = new TextView(context);
            labelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            labelView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueHeader));
            labelView.setText(label + ":");
            addView(labelView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            editText = new EditTextBoldCursor(context);
            editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            editText.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            editText.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteHintText));
            // One fill, one radius, one minimum height for every field, so a short number looks
            // like the same control as a long URL.
            editText.setBackground(Theme.createRoundRectDrawable(dp(8), getThemedColor(Theme.key_graySection)));
            editText.setMinimumHeight(dp(44));
            editText.setGravity(Gravity.CENTER_VERTICAL);
            editText.setPadding(dp(12), dp(8), dp(12), dp(8));
            editText.setHint(hint);
            editText.setText(value);
            editText.setSingleLine(!multiline);
            if (multiline) {
                editText.setMinLines(3);
                editText.setMaxLines(5);
                editText.setGravity(Gravity.TOP | Gravity.START);
                editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            }
            addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        }

        String text() {
            return editText.getText() == null ? "" : editText.getText().toString().trim();
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        // One view type per row: each configured cell is its own holder, so no cell can be
        // recycled into another row's slot and lose the value the user typed.
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case ROW_PROVIDER_HEADER:
                    view = header(LocaleController.getString(R.string.AiSummaryProvider));
                    break;
                case ROW_BASE_URL: view = baseUrlCell; break;
                case ROW_API_KEY: view = apiKeyCell; break;
                case ROW_MODEL: view = modelCell; break;
                case ROW_SAMPLING_HEADER:
                    view = header(LocaleController.getString(R.string.AiSummarySampling));
                    break;
                case ROW_TEMPERATURE: view = temperatureCell; break;
                case ROW_MAX_TOKENS: view = maxTokensCell; break;
                case ROW_TOP_P: view = topPCell; break;
                case ROW_ADVANCED_HEADER:
                    view = header(LocaleController.getString(R.string.AiSummaryAdvanced));
                    break;
                case ROW_STREAMING:
                    TextCheckCell check = new TextCheckCell(getContext());
                    check.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    check.setTextAndCheck(LocaleController.getString(R.string.AiSummaryStreaming), streaming, true);
                    view = check;
                    break;
                case ROW_SYSTEM_PROMPT: view = promptCell; break;
                case ROW_HEADERS: view = headersCell; break;
                case ROW_NOTICE:
                    TextInfoPrivacyCell info = new TextInfoPrivacyCell(getContext());
                    info.setText(LocaleController.getString(R.string.AiSummaryPrivacyNotice));
                    view = info;
                    break;
                default:
                    view = new ShadowSectionCell(getContext());
                    break;
            }
            // Rows added without layout params get LinearLayoutManager's WRAP_CONTENT default,
            // so each field shrank to its own text width. One place, every row.
            view.setLayoutParams(new RecyclerView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        private HeaderCell header(String text) {
            HeaderCell cell = new HeaderCell(getContext());
            cell.setText(text);
            cell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
            return cell;
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        }

        @Override
        public int getItemViewType(int position) {
            return position;
        }

        @Override
        public int getItemCount() {
            return ROW_COUNT;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int type = holder.getItemViewType();
            return type == ROW_STREAMING || type == ROW_SYSTEM_PROMPT;
        }
    }

    /** Full-screen editor so the multi-page prompt never sits inline in the settings list. */
    public static class PromptEditorActivity extends BaseFragment {

        private static final int done_button = 1;

        private final AiSummarySettingsActivity parent;
        private EditTextBoldCursor editText;

        public PromptEditorActivity(AiSummarySettingsActivity parent) {
            this.parent = parent;
        }

        @Override
        public View createView(Context context) {
            actionBar.setBackButtonImage(R.drawable.ic_ab_back);
            actionBar.setAllowOverlayTitle(true);
            actionBar.setTitle(LocaleController.getString(R.string.AiSummarySystemPrompt));
            actionBar.createMenu().addItem(done_button, R.drawable.ic_ab_done);
            actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
                @Override
                public void onItemClick(int id) {
                    if (id == -1) {
                        finishFragment();
                    } else if (id == done_button) {
                        parent.setSystemPrompt(editText.getText().toString());
                        finishFragment();
                    }
                }
            });

            FrameLayout root = new FrameLayout(context);
            root.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));

            editText = new EditTextBoldCursor(context);
            editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            editText.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            editText.setBackgroundDrawable(null);
            editText.setGravity(Gravity.TOP | Gravity.START);
            editText.setPadding(dp(18), dp(14), dp(18), dp(14));
            editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            editText.setSingleLine(false);
            editText.setText(parent.getSystemPrompt());
            root.addView(editText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            TextView resetButton = new TextView(context);
            resetButton.setGravity(Gravity.CENTER);
            resetButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            resetButton.setTypeface(AndroidUtilities.bold());
            resetButton.setTextColor(getThemedColor(Theme.key_featuredStickers_buttonText));
            resetButton.setText(LocaleController.getString(R.string.AiSummaryPromptReset));
            resetButton.setBackground(Theme.AdaptiveRipple.filledRect(getThemedColor(Theme.key_featuredStickers_addButton), 8));
            resetButton.setOnClickListener(v -> editText.setText(AiSummary.DEFAULT_SYSTEM_PROMPT));
            root.addView(resetButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 44, Gravity.BOTTOM, 12, 0, 12, 12));

            fragmentView = root;
            return fragmentView;
        }
    }
}
