/*
 * DrGram Advanced Forward — forward with full editing power:
 * edit the text/caption, strip links, strip hashtags, strip @IDs, replace words,
 * rename files, and send as your own clean message or classic forward.
 * DrGram Fix 33: quote mode — opens pre-filled with a "Quote from <source>:" header
 * and always sends from the user's own name (no Forwarded label).
 */
package org.telegram.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.regex.Pattern;

public class DrGramForwardEditor extends BaseFragment implements DialogsActivity.DialogsActivityDelegate {

    private static final int GOLD = 0xFFF5B90D;
    private static final int RED = 0xFFE63B52;
    private static final int DARK_ON_GOLD = 0xFF2A1F04;

    private static final Pattern LINKS = Pattern.compile("(?i)(https?://\\S+|www\\.\\S+|t\\.me/\\S+)");
    private static final Pattern HASHTAGS = Pattern.compile("#[^\\s#]+");
    // DrGram Fix 25: @usernames (email-safe: a word char / dot / plus before @ blocks the match)
    private static final Pattern IDS = Pattern.compile("(?<![A-Za-z0-9._%+-])@[A-Za-z0-9_]{4,32}");

    private final ArrayList<MessageObject> messages;
    private final ArrayList<String[]> replaces = new ArrayList<>();
    private boolean stripLinks;
    private boolean stripHashtags;
    private boolean stripIds;
    private int primaryIndex = -1;
    private int fileIndex = -1;
    private String originalFileName;

    private EditText textInput;
    private EditText fileInput;
    private LinearLayout textSection;
    private LinearLayout fileSection;
    private TextView noTextHint;
    private TextView statusLine;
    private CheckBox fromMyNameCheck;
    private final boolean quoteMode; // DrGram Fix 33

    public DrGramForwardEditor(ArrayList<MessageObject> objects) {
        this(objects, false);
    }

    /**
     * DrGram Fix 33: quoted = true opens the editor as "forward with quote":
     * the text starts with a quote header from the source and the batch is
     * always sent from the user's own name (the Forwarded label is dropped).
     */
    public DrGramForwardEditor(ArrayList<MessageObject> objects, boolean quoted) {
        quoteMode = quoted;
        messages = objects == null ? new ArrayList<>() : objects;
        for (int i = 0; i < messages.size(); i++) {
            MessageObject m = messages.get(i);
            if (primaryIndex == -1 && m != null && m.messageOwner != null && m.messageOwner.action == null && !TextUtils.isEmpty(m.messageOwner.message)) {
                primaryIndex = i;
            }
            if (fileIndex == -1 && m != null && m.messageOwner != null && m.messageOwner.media instanceof TLRPC.TL_messageMediaDocument) {
                fileIndex = i;
                TLRPC.Document doc = ((TLRPC.TL_messageMediaDocument) m.messageOwner.media).document;
                if (doc != null && doc.attributes != null) {
                    for (int j = 0; j < doc.attributes.size(); j++) {
                        TLRPC.DocumentAttribute attr = doc.attributes.get(j);
                        if (attr instanceof TLRPC.TL_documentAttributeFilename) {
                            originalFileName = ((TLRPC.TL_documentAttributeFilename) attr).file_name;
                            break;
                        }
                    }
                }
            }
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.DrGramAdvFwdTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(16), AndroidUtilities.dp(32));
        content.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fragmentView = scroll;

        TextView countLine = new TextView(context);
        countLine.setText(LocaleController.formatString(R.string.DrGramAdvFwdCount, messages.size()));
        countLine.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        countLine.setTypeface(AndroidUtilities.bold());
        countLine.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        countLine.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(12), AndroidUtilities.dp(4), AndroidUtilities.dp(2));
        content.addView(countLine);

        TextView typeLine = new TextView(context);
        typeLine.setText(describeBatch());
        typeLine.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        typeLine.setTextColor(GOLD);
        typeLine.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), AndroidUtilities.dp(6));
        content.addView(typeLine);

        TextView hint = new TextView(context);
        hint.setText(LocaleController.getString(R.string.DrGramAdvFwdHint));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        hint.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        hint.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), AndroidUtilities.dp(10));
        content.addView(hint);

        if (quoteMode) { // DrGram Fix 33: explain the quote mode
            TextView quoteHint = new TextView(context);
            quoteHint.setText(LocaleController.getString(R.string.DrGramAdvFwdQuoteHint));
            quoteHint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            quoteHint.setTextColor(GOLD);
            quoteHint.setTypeface(AndroidUtilities.bold());
            quoteHint.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), AndroidUtilities.dp(10));
            content.addView(quoteHint);
        }

        textSection = new LinearLayout(context);
        textSection.setOrientation(LinearLayout.VERTICAL);
        content.addView(textSection);

        noTextHint = new TextView(context);
        noTextHint.setText(LocaleController.getString(R.string.DrGramAdvFwdNoText));
        noTextHint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        noTextHint.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        noTextHint.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(6), AndroidUtilities.dp(4), AndroidUtilities.dp(6));
        noTextHint.setVisibility(View.GONE);
        content.addView(noTextHint);

        if (primaryIndex == -1) {
            noTextHint.setVisibility(View.VISIBLE);
        } else {
            TextView textHeader = new TextView(context);
            textHeader.setText(LocaleController.getString(R.string.DrGramAdvFwdTextEdit));
            textHeader.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            textHeader.setTypeface(AndroidUtilities.bold());
            textHeader.setTextColor(GOLD);
            textHeader.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(8), AndroidUtilities.dp(4), AndroidUtilities.dp(6));
            textSection.addView(textHeader);

            textInput = new EditText(context);
            String initial = messages.get(primaryIndex).messageOwner.message;
            if (quoteMode) { // DrGram Fix 33: pre-fill the editable text with the quote header
                String source = sourceTitle();
                String header = LocaleController.formatString(R.string.DrGramAdvFwdQuoteHeader,
                        TextUtils.isEmpty(source) ? "…" : source);
                initial = header + "\n" + initial;
            }
            textInput.setText(initial);
            textInput.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            textInput.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            textInput.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteHintText));
            textInput.setGravity(Gravity.TOP | Gravity.START);
            textInput.setMinLines(4);
            textInput.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(10));
            textInput.setBackground(roundedWithStroke());
            textSection.addView(textInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 0, 4, 4));
        }

        // DrGram Fix 25: 2x2 grid so four chips never overflow narrow screens
        LinearLayout opsRow1 = new LinearLayout(context);
        opsRow1.setOrientation(LinearLayout.HORIZONTAL);
        opsRow1.setPadding(0, AndroidUtilities.dp(10), 0, 0);
        content.addView(opsRow1);

        TextView linksBtn = makeChip(context, LocaleController.getString(R.string.DrGramAdvFwdRemoveLinks));
        linksBtn.setOnClickListener(v -> {
            stripLinks = true;
            if (textInput != null) {
                textInput.setText(applyOps(textInput.getText().toString()));
            }
            flashStatus(LocaleController.getString(R.string.DrGramAdvFwdRemoveLinks));
        });
        opsRow1.addView(linksBtn, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 4, 0, 4, 0));

        TextView tagsBtn = makeChip(context, LocaleController.getString(R.string.DrGramAdvFwdRemoveHashtags));
        tagsBtn.setOnClickListener(v -> {
            stripHashtags = true;
            if (textInput != null) {
                textInput.setText(applyOps(textInput.getText().toString()));
            }
            flashStatus(LocaleController.getString(R.string.DrGramAdvFwdRemoveHashtags));
        });
        opsRow1.addView(tagsBtn, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 4, 0, 4, 0));

        LinearLayout opsRow2 = new LinearLayout(context);
        opsRow2.setOrientation(LinearLayout.HORIZONTAL);
        opsRow2.setPadding(0, AndroidUtilities.dp(8), 0, 0);
        content.addView(opsRow2);

        TextView idsBtn = makeChip(context, LocaleController.getString(R.string.DrGramAdvFwdRemoveIds));
        idsBtn.setOnClickListener(v -> {
            stripIds = true;
            if (textInput != null) {
                textInput.setText(applyOps(textInput.getText().toString()));
            }
            flashStatus(LocaleController.getString(R.string.DrGramAdvFwdRemoveIds));
        });
        opsRow2.addView(idsBtn, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 4, 0, 4, 0));

        TextView replaceBtn = makeChip(context, LocaleController.getString(R.string.DrGramAdvFwdReplace));
        replaceBtn.setOnClickListener(v -> showReplaceDialog());
        opsRow2.addView(replaceBtn, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 4, 0, 4, 0));

        statusLine = new TextView(context);
        statusLine.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        statusLine.setTextColor(GOLD);
        statusLine.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(8), AndroidUtilities.dp(4), 0);
        content.addView(statusLine);

        if (fileIndex != -1) {
            fileSection = new LinearLayout(context);
            fileSection.setOrientation(LinearLayout.VERTICAL);
            content.addView(fileSection);

            TextView fileHeader = new TextView(context);
            fileHeader.setText(LocaleController.getString(R.string.DrGramAdvFwdFileName));
            fileHeader.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            fileHeader.setTypeface(AndroidUtilities.bold());
            fileHeader.setTextColor(GOLD);
            fileHeader.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(14), AndroidUtilities.dp(4), AndroidUtilities.dp(6));
            fileSection.addView(fileHeader);

            fileInput = new EditText(context);
            fileInput.setText(originalFileName == null ? "" : originalFileName);
            fileInput.setSingleLine(true);
            fileInput.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            fileInput.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            fileInput.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(10));
            fileInput.setBackground(roundedWithStroke());
            fileSection.addView(fileInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 0, 4, 4));

            TextView fileHint = new TextView(context);
            fileHint.setText(LocaleController.getString(R.string.DrGramAdvFwdFileNameHint));
            fileHint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            fileHint.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
            fileHint.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
            fileSection.addView(fileHint);
        }

        LinearLayout toggleRow = new LinearLayout(context);
        toggleRow.setOrientation(LinearLayout.HORIZONTAL);
        toggleRow.setGravity(Gravity.CENTER_VERTICAL);
        toggleRow.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(4), AndroidUtilities.dp(8));
        content.addView(toggleRow);

        fromMyNameCheck = new CheckBox(context);
        fromMyNameCheck.setChecked(true);
        fromMyNameCheck.setButtonTintList(ColorStateList.valueOf(GOLD));
        toggleRow.addView(fromMyNameCheck, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 10, 0, 0));

        TextView toggleText = new TextView(context);
        toggleText.setText(LocaleController.getString(R.string.DrGramAdvFwdFromMyName));
        toggleText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        toggleText.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        toggleRow.addView(toggleText, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        if (quoteMode) { // DrGram Fix 33: quote mode always sends from the user's own name
            fromMyNameCheck.setEnabled(false);
            toggleRow.setAlpha(0.55f);
        } else {
            toggleRow.setOnClickListener(v -> fromMyNameCheck.toggle());
        }

        TextView pickBtn = new TextView(context);
        pickBtn.setText(LocaleController.getString(R.string.DrGramAdvFwdPick));
        pickBtn.setGravity(Gravity.CENTER);
        pickBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        pickBtn.setTypeface(AndroidUtilities.bold());
        pickBtn.setTextColor(0xFFFFFFFF);
        GradientDrawable pickBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{RED, 0xFFB01E33});
        pickBg.setCornerRadius(AndroidUtilities.dp(18));
        pickBtn.setBackground(pickBg);
        pickBtn.setPadding(0, AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14));
        pickBtn.setOnClickListener(v -> openDestinationPicker());
        content.addView(pickBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 18, 4, 0));

        return fragmentView;
    }

    private void openDestinationPicker() {
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD);
        args.putInt("messagesCount", messages.size());
        args.putBoolean("canSelectTopics", true);
        DialogsActivity fragment = new DialogsActivity(args);
        fragment.setDelegate(this);
        presentFragment(fragment);
    }

    @Override
    public boolean didSelectDialogs(DialogsActivity fragment, ArrayList<MessagesStorage.TopicKey> dids, CharSequence message, boolean param, boolean notify, int scheduleDate, int scheduleRepeatPeriod, TopicsFragment topicsFragment) {
        for (int j = 0; j < dids.size(); j++) {
            long did = dids.get(j).dialogId;
            SendMessagesHelper.getInstance(currentAccount).sendMessage(buildSendList(), did, fromMyNameCheck.isChecked(), false, notify, scheduleDate, null, -1, 0);
        }
        fragment.finishFragment();
        finishFragment();
        return true;
    }

    /**
     * DrGram Fix 33: resolves a friendly source name (channel/group/user title)
     * for the quote header, scanning the batch until something matches.
     */
    private String sourceTitle() {
        try {
            for (int i = 0; i < messages.size(); i++) {
                MessageObject mo = messages.get(i);
                if (mo == null) {
                    continue;
                }
                long chatId = mo.getChatId();
                if (chatId != 0) {
                    TLRPC.Chat chat = getMessagesController().getChat(chatId);
                    if (chat != null && !TextUtils.isEmpty(chat.title)) {
                        return chat.title;
                    }
                }
                long fromId = mo.getSenderId();
                if (fromId < 0) {
                    TLRPC.Chat chat = getMessagesController().getChat(-fromId);
                    if (chat != null && !TextUtils.isEmpty(chat.title)) {
                        return chat.title;
                    }
                } else if (fromId > 0) {
                    TLRPC.User user = getMessagesController().getUser(fromId);
                    if (user != null) {
                        String name = ContactsController.formatName(user.first_name, user.last_name);
                        if (!TextUtils.isEmpty(name)) {
                            return name;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        return "";
    }

    private ArrayList<MessageObject> buildSendList() {
        ArrayList<MessageObject> out = new ArrayList<>();
        String primaryText = textInput != null && primaryIndex != -1 ? textInput.getText().toString().trim() : null;
        String newFileName = fileInput != null ? fileInput.getText().toString().trim() : null;
        for (int i = 0; i < messages.size(); i++) {
            MessageObject src = messages.get(i);
            if (src == null || src.messageOwner == null) {
                continue;
            }
            TLRPC.Message ownerCopy = deepCopyMessage(src.messageOwner);
            if (ownerCopy == null) {
                out.add(src);
                continue;
            }
            String original = ownerCopy.message == null ? "" : ownerCopy.message;
            String newText = original;
            if (i == primaryIndex && primaryText != null) {
                newText = primaryText;
            } else {
                newText = applyOps(newText);
            }
            ownerCopy.message = newText;
            if (!newText.equals(original)) {
                ownerCopy.entities = new ArrayList<>();
            }
            if (i == fileIndex && newFileName != null && !newFileName.isEmpty() && (originalFileName == null || !newFileName.equals(originalFileName))) {
                renameDocument(ownerCopy, newFileName);
            }
            out.add(new MessageObject(currentAccount, ownerCopy, false, false));
        }
        return out;
    }

    private static TLRPC.Message deepCopyMessage(TLRPC.Message src) {
        try {
            NativeByteBuffer buffer = new NativeByteBuffer(src.getObjectSize());
            src.serializeToStream(buffer);
            buffer.position(0);
            TLRPC.Message copy = TLRPC.Message.TLdeserialize(buffer, buffer.readInt32(true), true);
            return copy;
        } catch (Exception e) {
            return null;
        }
    }

    private static void renameDocument(TLRPC.Message msg, String name) {
        TLRPC.Document doc = msg.media instanceof TLRPC.TL_messageMediaDocument ? ((TLRPC.TL_messageMediaDocument) msg.media).document : null;
        if (doc == null) {
            return;
        }
        if (doc.attributes == null) {
            doc.attributes = new ArrayList<>();
        }
        for (int i = 0; i < doc.attributes.size(); i++) {
            TLRPC.DocumentAttribute attr = doc.attributes.get(i);
            if (attr instanceof TLRPC.TL_documentAttributeFilename) {
                ((TLRPC.TL_documentAttributeFilename) attr).file_name = name;
                return;
            }
        }
        TLRPC.TL_documentAttributeFilename attr = new TLRPC.TL_documentAttributeFilename();
        attr.file_name = name;
        doc.attributes.add(attr);
    }

    private String applyOps(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        if (stripLinks) {
            text = LINKS.matcher(text).replaceAll(" ");
        }
        if (stripHashtags) {
            text = HASHTAGS.matcher(text).replaceAll(" ");
        }
        if (stripIds) {
            text = IDS.matcher(text).replaceAll(" ");
        }
        for (int i = 0; i < replaces.size(); i++) {
            String[] r = replaces.get(i);
            if ("1".equals(r[2])) {
                text = text.replace(r[0], r[1]);
            } else {
                text = Pattern.compile(Pattern.quote(r[0]), Pattern.CASE_INSENSITIVE | Pattern.LITERAL | Pattern.UNICODE_CASE)
                        .matcher(text)
                        .replaceAll(java.util.regex.Matcher.quoteReplacement(r[1]));
            }
        }
        text = text.replaceAll("[ \\t]{2,}", " ").replaceAll(" +\\n", "\n").trim();
        return text;
    }

    private void showReplaceDialog() {
        Context ctx = getParentActivity();
        if (ctx == null) {
            return;
        }
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = AndroidUtilities.dp(20);
        box.setPadding(pad, pad / 2, pad, 0);

        final EditText findField = new EditText(ctx);
        findField.setSingleLine(true);
        findField.setHint(LocaleController.getString(R.string.DrGramAdvFwdReplaceFind));
        box.addView(findField);

        final EditText replaceField = new EditText(ctx);
        replaceField.setSingleLine(true);
        replaceField.setHint(LocaleController.getString(R.string.DrGramAdvFwdReplaceWith));
        box.addView(replaceField);

        final CheckBox caseCheck = new CheckBox(ctx);
        caseCheck.setText(LocaleController.getString(R.string.DrGramAdvFwdCaseSensitive));
        caseCheck.setButtonTintList(ColorStateList.valueOf(GOLD));
        caseCheck.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        box.addView(caseCheck);

        new AlertDialog.Builder(ctx)
                .setTitle(LocaleController.getString(R.string.DrGramAdvFwdReplace))
                .setView(box)
                .setPositiveButton(R.string.DrGramAdvFwdReplace, (dialog, which) -> {
                    String find = findField.getText().toString();
                    String rep = replaceField.getText().toString();
                    if (TextUtils.isEmpty(find)) {
                        return;
                    }
                    replaces.add(new String[]{find, rep, caseCheck.isChecked() ? "1" : "0"});
                    if (textInput != null) {
                        textInput.setText(applyOps(textInput.getText().toString()));
                    }
                    flashStatus(LocaleController.getString(R.string.DrGramAdvFwdReplaced));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void flashStatus(String text) {
        if (statusLine == null) {
            return;
        }
        statusLine.setText(text);
        statusLine.animate().cancel();
        statusLine.setAlpha(1f);
        statusLine.postDelayed(() -> {
            if (statusLine != null) {
                statusLine.animate().alpha(0f).setDuration(600).start();
            }
        }, 1400);
    }

    private String describeBatch() {
        int photos = 0;
        int videos = 0;
        int files = 0;
        int voices = 0;
        int stickers = 0;
        int texts = 0;
        for (int i = 0; i < messages.size(); i++) {
            MessageObject m = messages.get(i);
            if (m == null || m.messageOwner == null) {
                continue;
            }
            if (m.messageOwner.action != null) {
                continue;
            }
            if (m.isSticker()) {
                stickers++;
            } else if (m.isVoice()) {
                voices++;
            } else if (m.isGif()) {
                videos++;
            } else if (m.isVideo()) {
                videos++;
            } else if (m.isPhoto()) {
                photos++;
            } else if (m.isDocument()) {
                files++;
            } else if (!TextUtils.isEmpty(m.messageOwner.message)) {
                texts++;
            } else {
                texts++;
            }
        }
        StringBuilder sb = new StringBuilder();
        appendType(sb, photos, R.string.DrGramAdvFwdTypePhoto);
        appendType(sb, videos, R.string.DrGramAdvFwdTypeVideo);
        appendType(sb, files, R.string.DrGramAdvFwdTypeFile);
        appendType(sb, voices, R.string.DrGramAdvFwdTypeVoice);
        appendType(sb, stickers, R.string.DrGramAdvFwdTypeSticker);
        appendType(sb, texts, R.string.DrGramAdvFwdTypeText);
        return sb.length() == 0 ? LocaleController.getString(R.string.DrGramAdvFwdTypeOther) : sb.toString();
    }

    private void appendType(StringBuilder sb, int count, int resId) {
        if (count <= 0) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(" · ");
        }
        sb.append(count).append(' ').append(LocaleController.getString(resId));
    }

    private TextView makeChip(Context context, String text) {
        TextView b = new TextView(context);
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        b.setTypeface(AndroidUtilities.bold());
        b.setTextColor(GOLD);
        b.setPadding(AndroidUtilities.dp(6), AndroidUtilities.dp(10), AndroidUtilities.dp(6), AndroidUtilities.dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(16));
        bg.setColor(0x00000000);
        bg.setStroke(AndroidUtilities.dp(1), GOLD);
        b.setBackground(bg);
        return b;
    }

    private GradientDrawable roundedWithStroke() {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(12));
        bg.setColor(0x00000000);
        bg.setStroke(AndroidUtilities.dp(1), GOLD);
        return bg;
    }
}
