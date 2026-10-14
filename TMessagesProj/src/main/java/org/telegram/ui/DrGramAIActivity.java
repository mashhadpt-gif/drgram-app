/*
 * DrGram AI writing tools. Requests are user-triggered, use HTTPS only, and
 * show a clear consent prompt before sending the entered text to the configured
 * provider. This is an OpenAI-compatible Chat Completions client.
 */
package org.telegram.ui;

import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
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

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DrGramAIConfig;
import org.telegram.messenger.DrGramThemeInstaller;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.DrGramGlassKit;
import org.telegram.ui.Components.LayoutHelper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import javax.net.ssl.HttpsURLConnection;

public class DrGramAIActivity extends BaseFragment {
    private static final int MAX_INPUT_CHARS = 20000;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    private EditText endpointField;
    private EditText modelField;
    private EditText keyField;
    private EditText inputField;
    private TextView resultView;
    private Button saveButton;
    private final ArrayList<Button> actionButtons = new ArrayList<>();

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.DrGramAiToolsTitle));
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
        content.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(28));
        content.setBackground(DrGramGlassKit.pageGradient()); // DrGram Fix 36: glass canvas

        TextView intro = new TextView(context);
        intro.setText(LocaleController.getString(R.string.DrGramAiToolsHint));
        intro.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        intro.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        content.addView(intro, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        endpointField = createField(context, DrGramAIConfig.getEndpoint(), LocaleController.getString(R.string.DrGramAiEndpoint), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, true);
        modelField = createField(context, DrGramAIConfig.getModel(), LocaleController.getString(R.string.DrGramAiModel), InputType.TYPE_CLASS_TEXT, true);
        keyField = createField(context, "", LocaleController.getString(R.string.DrGramAiApiKeyHint), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, true);
        addField(content, LocaleController.getString(R.string.DrGramAiEndpoint), endpointField);
        addField(content, LocaleController.getString(R.string.DrGramAiModel), modelField);
        addField(content, LocaleController.getString(R.string.DrGramAiApiKey), keyField);

        saveButton = makeButton(context, LocaleController.getString(R.string.DrGramAiSave), true);
        saveButton.setOnClickListener(v -> saveConfiguration(true));
        content.addView(saveButton, buttonLayout());

        TextView inputLabel = makeLabel(context, LocaleController.getString(R.string.DrGramAiInput));
        inputLabel.setPadding(0, AndroidUtilities.dp(18), 0, AndroidUtilities.dp(6));
        content.addView(inputLabel);
        inputField = createField(context, "", LocaleController.getString(R.string.DrGramAiInputHint),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, false);
        inputField.setMinLines(5);
        inputField.setGravity(Gravity.TOP | Gravity.START);
        content.addView(inputField, fieldLayout());

        LinearLayout buttonsRow1 = new LinearLayout(context);
        buttonsRow1.setOrientation(LinearLayout.HORIZONTAL);
        addActionButton(context, buttonsRow1, LocaleController.getString(R.string.DrGramAiSummarize),
                "Summarize the input accurately. Keep the key facts and important caveats.");
        addActionButton(context, buttonsRow1, LocaleController.getString(R.string.DrGramAiKeyPoints),
                "Extract the most important points as a concise bullet list.");
        content.addView(buttonsRow1, rowLayout());

        LinearLayout buttonsRow2 = new LinearLayout(context);
        buttonsRow2.setOrientation(LinearLayout.HORIZONTAL);
        addActionButton(context, buttonsRow2, LocaleController.getString(R.string.DrGramAiRewrite),
                "Rewrite the input clearly and professionally without changing its meaning.");
        addActionButton(context, buttonsRow2, LocaleController.getString(R.string.DrGramAiExplain),
                "Explain the input in simple language and define any difficult terms.");
        content.addView(buttonsRow2, rowLayout());

        LinearLayout buttonsRow3 = new LinearLayout(context);
        buttonsRow3.setOrientation(LinearLayout.HORIZONTAL);
        addActionButton(context, buttonsRow3, LocaleController.getString(R.string.DrGramAiTone),
                "Analyze the tone of the input. Give a brief explanation and suggest a neutral alternative if useful.");
        addActionButton(context, buttonsRow3, LocaleController.getString(R.string.DrGramAiEmoji),
                "Add a small number of suitable emojis where they improve the input. Preserve its meaning.");
        content.addView(buttonsRow3, rowLayout());

        TextView resultLabel = makeLabel(context, LocaleController.getString(R.string.DrGramAiResult));
        resultLabel.setPadding(0, AndroidUtilities.dp(18), 0, AndroidUtilities.dp(6));
        content.addView(resultLabel);
        resultView = new TextView(context);
        resultView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        resultView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        resultView.setTextIsSelectable(true);
        resultView.setGravity(Gravity.START);
        resultView.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(14), AndroidUtilities.dp(14), AndroidUtilities.dp(14));
        GradientDrawable resultBackground = new GradientDrawable();
        resultBackground.setCornerRadius(AndroidUtilities.dp(14));
        resultBackground.setColor(getThemedColor(Theme.key_dialogBackground));
        resultBackground.setStroke(AndroidUtilities.dp(1), getThemedColor(Theme.key_divider));
        resultView.setBackground(resultBackground);
        resultView.setText(LocaleController.getString(R.string.DrGramAiResultHint));
        content.addView(resultView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button copyButton = makeButton(context, LocaleController.getString(R.string.DrGramAiCopyResult), false);
        copyButton.setOnClickListener(v -> copyResult());
        content.addView(copyButton, buttonLayout());

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fragmentView = scrollView;
        return fragmentView;
    }

    private EditText createField(Context context, String value, String hint, int inputType, boolean singleLine) {
        EditText field = new EditText(context);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        field.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        field.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        field.setHint(hint);
        field.setText(value);
        field.setInputType(inputType);
        field.setSingleLine(singleLine);
        field.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(10));
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(AndroidUtilities.dp(12));
        background.setColor(getThemedColor(Theme.key_dialogBackground));
        background.setStroke(AndroidUtilities.dp(1), getThemedColor(Theme.key_divider));
        field.setBackground(background);
        return field;
    }

    private void addField(LinearLayout parent, String label, EditText field) {
        TextView labelView = makeLabel(getParentActivity(), label);
        labelView.setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(4));
        parent.addView(labelView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(field, fieldLayout());
    }

    private TextView makeLabel(Context context, String value) {
        TextView label = new TextView(context);
        label.setText(value);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        label.setTypeface(AndroidUtilities.bold());
        label.setTextColor(DrGramThemeInstaller.accentColor());
        return label;
    }

    private Button makeButton(Context context, String text, boolean primary) {
        Button button = new Button(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        int accent = DrGramThemeInstaller.accentColor();
        button.setTextColor(primary && !DrGramThemeInstaller.isLoveMode() ? 0xFF16120A : 0xFFFFFFFF);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(AndroidUtilities.dp(12));
        background.setColor(primary ? accent : getThemedColor(Theme.key_dialogBackground));
        if (!primary) {
            background.setStroke(AndroidUtilities.dp(1), accent);
        }
        button.setBackground(background);
        return button;
    }

    private void addActionButton(Context context, LinearLayout row, String label, String instruction) {
        Button button = makeButton(context, label, false);
        button.setOnClickListener(v -> runPrompt(instruction));
        actionButtons.add(button);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(AndroidUtilities.dp(3), 0, AndroidUtilities.dp(3), 0);
        row.addView(button, params);
    }

    private LinearLayout.LayoutParams fieldLayout() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, AndroidUtilities.dp(4));
        return params;
    }

    private LinearLayout.LayoutParams buttonLayout() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, AndroidUtilities.dp(7), 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams rowLayout() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, AndroidUtilities.dp(8), 0, 0);
        return params;
    }

    private boolean saveConfiguration(boolean showToast) {
        if (endpointField == null || modelField == null || keyField == null) {
            return false;
        }
        try {
            DrGramAIConfig.save(endpointField.getText().toString(), modelField.getText().toString(), keyField.getText().toString());
            keyField.setText("");
            if (showToast && getParentActivity() != null) {
                android.widget.Toast.makeText(getParentActivity(), LocaleController.getString(R.string.DrGramAiSaved), android.widget.Toast.LENGTH_SHORT).show();
            }
            return true;
        } catch (Exception e) {
            FileLog.e("DrGram AI settings could not be saved: " + e.getClass().getSimpleName());
            if (getParentActivity() != null) {
                android.widget.Toast.makeText(getParentActivity(), LocaleController.getString(R.string.DrGramAiBadSettings), android.widget.Toast.LENGTH_LONG).show();
            }
            return false;
        }
    }

    private void runPrompt(String instruction) {
        if (inputField == null || TextUtilsCompat.isBlank(inputField.getText().toString())) {
            if (getParentActivity() != null) {
                android.widget.Toast.makeText(getParentActivity(), LocaleController.getString(R.string.DrGramAiInputRequired), android.widget.Toast.LENGTH_SHORT).show();
            }
            return;
        }
        String input = inputField.getText().toString().trim();
        if (input.length() > MAX_INPUT_CHARS) {
            if (getParentActivity() != null) {
                android.widget.Toast.makeText(getParentActivity(), LocaleController.getString(R.string.DrGramAiInputTooLong), android.widget.Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (!saveConfiguration(false)) {
            return;
        }
        String apiKey = DrGramAIConfig.getApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            if (getParentActivity() != null) {
                android.widget.Toast.makeText(getParentActivity(), LocaleController.getString(R.string.DrGramAiNeedKey), android.widget.Toast.LENGTH_LONG).show();
            }
            return;
        }
        final String endpoint = DrGramAIConfig.getEndpoint();
        final String model = DrGramAIConfig.getModel();
        final String prompt = instruction;

        new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(LocaleController.getString(R.string.DrGramAiConsentTitle))
                .setMessage(LocaleController.getString(R.string.DrGramAiConsent))
                .setPositiveButton(LocaleController.getString(R.string.DrGramAiRun), (dialog, which) ->
                        performRequest(endpoint, model, apiKey, prompt, input))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private void performRequest(String endpoint, String model, String apiKey, String instruction, String input) {
        setActionsEnabled(false);
        if (resultView != null) {
            resultView.setText(LocaleController.getString(R.string.DrGramAiWorking));
        }
        Thread worker = new Thread(() -> {
            String output;
            try {
                URL url = new URL(endpoint);
                if (!"https".equalsIgnoreCase(url.getProtocol())) {
                    throw new IllegalArgumentException("HTTPS is required");
                }
                HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Authorization", "Bearer " + apiKey);

                JSONArray messages = new JSONArray();
                messages.put(new JSONObject()
                        .put("role", "system")
                        .put("content", "You are a careful writing assistant. Follow the requested operation accurately. Reply in the same language as the input. Preserve meaning and do not invent facts."));
                messages.put(new JSONObject()
                        .put("role", "user")
                        .put("content", instruction + "\n\nINPUT:\n" + input));

                byte[] body = new JSONObject()
                        .put("model", model)
                        .put("messages", messages)
                        .put("max_completion_tokens", 1200)
                        .toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream stream = connection.getOutputStream()) {
                    stream.write(body);
                }

                int status = connection.getResponseCode();
                InputStream responseStream = status >= 200 && status < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                String response = responseStream == null ? "" : readLimited(responseStream, MAX_RESPONSE_BYTES);
                connection.disconnect();

                if (status < 200 || status >= 300) {
                    output = LocaleController.getString(R.string.DrGramAiRequestFailed) + " (HTTP " + status + ")";
                } else {
                    JSONObject json = new JSONObject(response);
                    JSONArray choices = json.optJSONArray("choices");
                    if (choices == null || choices.length() == 0) {
                        output = LocaleController.getString(R.string.DrGramAiNoResult);
                    } else {
                        JSONObject message = choices.getJSONObject(0).optJSONObject("message");
                        output = message == null ? "" : message.optString("content", "").trim();
                        if (output.isEmpty()) {
                            output = LocaleController.getString(R.string.DrGramAiNoResult);
                        }
                    }
                }
            } catch (Exception e) {
                FileLog.e("DrGram AI request failed: " + e.getClass().getSimpleName());
                output = LocaleController.getString(R.string.DrGramAiRequestFailed);
            }
            final String finalOutput = output;
            AndroidUtilities.runOnUIThread(() -> {
                if (resultView != null) {
                    resultView.setText(finalOutput);
                }
                setActionsEnabled(true);
            });
        }, "DrGramAIRequest");
        worker.setDaemon(true);
        worker.start();
    }

    private String readLimited(InputStream input, int limit) throws Exception {
        try (InputStream stream = input; ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] data = new byte[8192];
            int total = 0;
            int n;
            while ((n = stream.read(data)) != -1) {
                total += n;
                if (total > limit) {
                    throw new IllegalStateException("AI response too large");
                }
                buffer.write(data, 0, n);
            }
            return buffer.toString("UTF-8");
        }
    }

    private void setActionsEnabled(boolean enabled) {
        if (saveButton != null) {
            saveButton.setEnabled(enabled);
        }
        for (Button button : actionButtons) {
            button.setEnabled(enabled);
        }
    }

    private void copyResult() {
        if (resultView == null || getParentActivity() == null) {
            return;
        }
        String text = resultView.getText().toString();
        if (text.isEmpty()) {
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getParentActivity().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("DrGram AI result", text));
            android.widget.Toast.makeText(getParentActivity(), LocaleController.getString(R.string.DrGramLinkExtractorCopied), android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private static final class TextUtilsCompat {
        static boolean isBlank(String value) {
            return value == null || value.trim().isEmpty();
        }
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
