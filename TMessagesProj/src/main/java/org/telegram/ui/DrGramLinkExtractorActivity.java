/*
 * DrGram Link Extractor. Extracts URLs, Telegram usernames, emails, phone
 * numbers and hashtags from text explicitly pasted or entered by the user.
 */
package org.telegram.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DrGramThemeInstaller;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DrGramLinkExtractorActivity extends BaseFragment {
    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?i)(?:https?://|tg://)[^\\s<>\\\"']+|(?:www\\.)[^\\s<>\\\"']+|(?:t\\.me|telegram\\.me)/[^\\s<>\\\"']+");
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "(?i)[a-z0-9._%+\\-]+@[a-z0-9.\\-]+\\.[a-z]{2,}");
    private static final Pattern USERNAME_PATTERN = Pattern.compile(
            "(?<![\\p{L}\\p{N}_])@[a-zA-Z][a-zA-Z0-9_]{4,31}");
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "(?<![\\p{L}\\p{N}])\\+?\\d[\\d ()\\-.]{5,}\\d(?![\\p{L}\\p{N}])");
    private static final Pattern HASHTAG_PATTERN = Pattern.compile(
            "(?<![\\p{L}\\p{N}_])#[\\p{L}\\p{N}_]{2,}");

    private EditText inputField;
    private LinearLayout resultsContainer;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.DrGramLinkExtractorTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(24));
        content.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));

        TextView hint = new TextView(context);
        hint.setText(LocaleController.getString(R.string.DrGramLinkExtractorHint));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        hint.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        content.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        inputField = new EditText(context);
        inputField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        inputField.setMinLines(5);
        inputField.setGravity(Gravity.TOP | Gravity.START);
        inputField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        inputField.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        inputField.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        inputField.setHint(LocaleController.getString(R.string.DrGramLinkExtractorInputHint));
        inputField.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(10));
        GradientDrawable fieldBackground = new GradientDrawable();
        fieldBackground.setCornerRadius(AndroidUtilities.dp(12));
        fieldBackground.setColor(getThemedColor(Theme.key_dialogBackground));
        fieldBackground.setStroke(AndroidUtilities.dp(1), getThemedColor(Theme.key_divider));
        inputField.setBackground(fieldBackground);
        LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fieldParams.setMargins(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(8));
        content.addView(inputField, fieldParams);

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button pasteButton = makeButton(context, LocaleController.getString(R.string.DrGramLinkExtractorPaste), false);
        pasteButton.setOnClickListener(v -> pasteFromClipboard());
        Button extractButton = makeButton(context, LocaleController.getString(R.string.DrGramLinkExtractorExtract), true);
        extractButton.setOnClickListener(v -> extract());
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        half.setMargins(AndroidUtilities.dp(3), 0, AndroidUtilities.dp(3), 0);
        buttons.addView(pasteButton, half);
        buttons.addView(extractButton, half);
        content.addView(buttons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        resultsContainer = new LinearLayout(context);
        resultsContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams resultsParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        resultsParams.setMargins(0, AndroidUtilities.dp(14), 0, 0);
        content.addView(resultsContainer, resultsParams);
        showEmpty();

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fragmentView = scrollView;
        return fragmentView;
    }

    private Button makeButton(Context context, String text, boolean primary) {
        Button button = new Button(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        int accent = DrGramThemeInstaller.accentColor();
        button.setTextColor(primary && !DrGramThemeInstaller.isLoveMode() ? 0xFF16120A : 0xFFFFFFFF);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(12));
        bg.setColor(primary ? accent : getThemedColor(Theme.key_dialogBackground));
        if (!primary) {
            bg.setStroke(AndroidUtilities.dp(1), accent);
        }
        button.setBackground(bg);
        return button;
    }

    private void pasteFromClipboard() {
        if (getParentActivity() == null || inputField == null) {
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getParentActivity().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip().getItemCount() > 0) {
            CharSequence text = clipboard.getPrimaryClip().getItemAt(0).coerceToText(getParentActivity());
            inputField.setText(text == null ? "" : text);
        }
    }

    private void extract() {
        String text = inputField == null ? "" : inputField.getText().toString();
        LinkedHashMap<String, String> items = new LinkedHashMap<>();
        addMatches(items, URL_PATTERN, LocaleController.getString(R.string.DrGramLinkTypeUrl), text, true);
        addMatches(items, EMAIL_PATTERN, LocaleController.getString(R.string.DrGramLinkTypeEmail), text, false);
        addMatches(items, USERNAME_PATTERN, LocaleController.getString(R.string.DrGramLinkTypeUsername), text, false);
        addMatches(items, PHONE_PATTERN, LocaleController.getString(R.string.DrGramLinkTypePhone), text, false);
        addMatches(items, HASHTAG_PATTERN, LocaleController.getString(R.string.DrGramLinkTypeHashtag), text, false);

        resultsContainer.removeAllViews();
        if (items.isEmpty()) {
            showEmpty();
            return;
        }
        TextView count = new TextView(getParentActivity());
        count.setText(LocaleController.getString(R.string.DrGramLinkExtractorCount) + ": " + items.size());
        count.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        count.setTypeface(AndroidUtilities.bold());
        count.setTextColor(DrGramThemeInstaller.accentColor());
        count.setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(8));
        resultsContainer.addView(count);

        for (Map.Entry<String, String> entry : items.entrySet()) {
            TextView item = new TextView(getParentActivity());
            int separator = entry.getKey().indexOf("::");
            String type = separator < 0 ? entry.getKey() : entry.getKey().substring(0, separator);
            item.setText(type + "  ·  " + entry.getValue());
            item.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            item.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            item.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12));
            item.setTextIsSelectable(true);
            GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(AndroidUtilities.dp(12));
            background.setColor(getThemedColor(Theme.key_dialogBackground));
            background.setStroke(AndroidUtilities.dp(1), getThemedColor(Theme.key_divider));
            item.setBackground(background);
            item.setOnClickListener(v -> copyItem(entry.getValue()));
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.setMargins(0, 0, 0, AndroidUtilities.dp(5));
            resultsContainer.addView(item, rowParams);
        }
    }

    private void addMatches(LinkedHashMap<String, String> items, Pattern pattern, String type, String text, boolean trimUrl) {
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        while (matcher.find()) {
            String value = matcher.group().trim();
            if (trimUrl) {
                while (!value.isEmpty() && ".,!?;:)]}".indexOf(value.charAt(value.length() - 1)) >= 0) {
                    value = value.substring(0, value.length() - 1);
                }
            }
            if (!value.isEmpty()) {
                String normalized = value.toLowerCase(java.util.Locale.ROOT);
                boolean exists = false;
                for (String oldValue : items.values()) {
                    if (oldValue.toLowerCase(java.util.Locale.ROOT).equals(normalized)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    String itemKey = type + "::" + normalized;
                    if (!items.containsKey(itemKey)) {
                        if (items.size() >= 250) {
                            return;
                        }
                        items.put(itemKey, value);
                    }
                }
            }
        }
    }

    private void copyItem(String value) {
        if (getParentActivity() == null) {
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getParentActivity().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("DrGram extracted item", value));
            android.widget.Toast.makeText(getParentActivity(), LocaleController.getString(R.string.DrGramLinkExtractorCopied), android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void showEmpty() {
        if (resultsContainer == null || getParentActivity() == null) {
            return;
        }
        resultsContainer.removeAllViews();
        TextView text = new TextView(getParentActivity());
        text.setText(LocaleController.getString(R.string.DrGramLinkExtractorEmpty));
        text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        text.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        text.setPadding(0, AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6));
        resultsContainer.addView(text);
    }
    // DrGram Fix 13: self-managed edge-to-edge. These pages previously relied on
    // the fragment container padding, which left the last rows behind the system
    // navigation bar — the page could not be scrolled to its very bottom.
    // Opting into edge-to-edge + padding the scroll content with the real navbar
    // inset makes every row reachable (and lets content scroll under the pill).
    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        super.onInsets(left, top, right, bottom);
        if (fragmentView instanceof ViewGroup) {
            ViewGroup root = (ViewGroup) fragmentView;
            ViewGroup target = root;
            if (root instanceof android.widget.ScrollView
                    && root.getChildCount() > 0
                    && root.getChildAt(0) instanceof ViewGroup) {
                target = (ViewGroup) root.getChildAt(0);
            }
            int newBottom = Math.max(target.getPaddingBottom(), bottom + AndroidUtilities.dp(16));
            target.setPadding(target.getPaddingLeft(), target.getPaddingTop(), target.getPaddingRight(), newBottom);
            target.setClipToPadding(false);
        }
    }
}
