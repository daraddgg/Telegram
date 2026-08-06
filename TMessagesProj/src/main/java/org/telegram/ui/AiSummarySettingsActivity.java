package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AiSummary;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

/**
 * Editable AI Summary provider configuration. Values are persisted on fragment destroy.
 *
 * The stored API key is never rendered: the field shows a mask and is only written back when the
 * user types a replacement.
 */
public class AiSummarySettingsActivity extends BaseFragment {

    private EditTextCell baseUrlCell;
    private EditTextCell apiKeyCell;
    private EditTextCell modelCell;
    private EditTextCell temperatureCell;
    private EditTextCell maxTokensCell;
    private EditTextCell topPCell;
    private EditTextCell promptCell;
    private EditTextCell headersCell;
    private boolean streaming;

    private String maskedKey;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.AiSummary));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        SharedPreferences prefs = AiSummary.prefs();
        maskedKey = AiSummary.maskKey(prefs.getString(AiSummary.PREF_API_KEY, ""));
        streaming = prefs.getBoolean(AiSummary.PREF_STREAMING, false);

        baseUrlCell = textField(context, AiSummary.DEFAULT_BASE_URL, prefs.getString(AiSummary.PREF_BASE_URL, ""));
        apiKeyCell = textField(context, maskedKey.isEmpty() ? LocaleController.getString(R.string.AiSummaryApiKey) : maskedKey, "");
        apiKeyCell.editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        modelCell = textField(context, AiSummary.DEFAULT_MODEL, prefs.getString(AiSummary.PREF_MODEL, ""));
        temperatureCell = numberField(context, "temperature", floatText(prefs.getFloat(AiSummary.PREF_TEMPERATURE, AiSummary.DEFAULT_TEMPERATURE)), true);
        maxTokensCell = numberField(context, "max_tokens", String.valueOf(prefs.getInt(AiSummary.PREF_MAX_TOKENS, AiSummary.DEFAULT_MAX_TOKENS)), false);
        topPCell = numberField(context, "top_p", floatText(prefs.getFloat(AiSummary.PREF_TOP_P, AiSummary.DEFAULT_TOP_P)), true);
        promptCell = new EditTextCell(context, LocaleController.getString(R.string.AiSummarySystemPrompt), true, false, -1, getResourceProvider());
        promptCell.setText(AiSummary.systemPrompt());
        promptCell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        headersCell = textField(context, "X-Header: value", prefs.getString(AiSummary.PREF_HEADERS, ""));

        fragmentView = new FrameLayout(context);
        FrameLayout frameLayout = (FrameLayout) fragmentView;
        frameLayout.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));

        RecyclerListView listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(new ListAdapter());
        listView.setOnItemClickListener((view, position) -> {
            if (view instanceof TextCheckCell) {
                streaming = !streaming;
                ((TextCheckCell) view).setChecked(streaming);
            }
        });
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return fragmentView;
    }

    private EditTextCell textField(Context context, String hint, String value) {
        EditTextCell cell = new EditTextCell(context, hint, false, false, -1, getResourceProvider());
        cell.setText(value);
        cell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        cell.setDivider(true);
        return cell;
    }

    private EditTextCell numberField(Context context, String hint, String value, boolean decimal) {
        EditTextCell cell = textField(context, hint, value);
        cell.editText.setInputType(InputType.TYPE_CLASS_NUMBER | (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        return cell;
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
                .putString(AiSummary.PREF_BASE_URL, text(baseUrlCell))
                .putString(AiSummary.PREF_MODEL, text(modelCell))
                .putFloat(AiSummary.PREF_TEMPERATURE, parseFloat(text(temperatureCell), AiSummary.DEFAULT_TEMPERATURE))
                .putInt(AiSummary.PREF_MAX_TOKENS, parseInt(text(maxTokensCell), AiSummary.DEFAULT_MAX_TOKENS))
                .putFloat(AiSummary.PREF_TOP_P, parseFloat(text(topPCell), AiSummary.DEFAULT_TOP_P))
                .putBoolean(AiSummary.PREF_STREAMING, streaming)
                .putString(AiSummary.PREF_HEADERS, text(headersCell));

        String prompt = text(promptCell);
        if (prompt.isEmpty() || prompt.equals(AiSummary.DEFAULT_SYSTEM_PROMPT)) {
            editor.remove(AiSummary.PREF_SYSTEM_PROMPT);
        } else {
            editor.putString(AiSummary.PREF_SYSTEM_PROMPT, prompt);
        }

        // An untouched key field shows only the mask, so writing it back would destroy the real key.
        String key = text(apiKeyCell);
        if (!key.isEmpty() && !key.equals(maskedKey)) {
            editor.putString(AiSummary.PREF_API_KEY, key);
        }
        editor.apply();
    }

    private static String text(EditTextCell cell) {
        return cell.getText() == null ? "" : cell.getText().toString().trim();
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

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        // Every row is a distinct view type, so each cell instance is its own holder and nothing
        // gets recycled into the wrong slot. The list is short and fully visible in one screen.
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case 0:
                    view = header(LocaleController.getString(R.string.AiSummaryProvider));
                    break;
                case 1: view = baseUrlCell; break;
                case 2: view = apiKeyCell; break;
                case 3: view = modelCell; break;
                case 4:
                    view = header(LocaleController.getString(R.string.AiSummarySampling));
                    break;
                case 5: view = temperatureCell; break;
                case 6: view = maxTokensCell; break;
                case 7: view = topPCell; break;
                case 8:
                    TextCheckCell check = new TextCheckCell(getContext());
                    check.setTextAndCheck(LocaleController.getString(R.string.AiSummaryStreaming), streaming, true);
                    view = check;
                    break;
                case 9: view = promptCell; break;
                case 10: view = headersCell; break;
                default:
                    TextInfoPrivacyCell info = new TextInfoPrivacyCell(getContext());
                    info.setText(LocaleController.getString(R.string.AiSummaryPrivacyNotice));
                    view = info;
                    break;
            }
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
            return 12;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == 8;
        }
    }
}
