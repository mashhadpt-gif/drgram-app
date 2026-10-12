/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Fix 21: the sticky notifications bar. A small stack of luxury cards
 * that floats above the bottom folder tabs on the main screen. A card opens
 * the chat it came from or is dismissed with its own close button; cards
 * clear themselves as soon as the conversation is read.
 */
package org.telegram.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.DrGramStickyNotifs;
import org.telegram.messenger.DrGramThemeInstaller;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

public class DrGramStickyNotifBar extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {

    private static final int MAX_CARDS = 2;

    private final int currentAccount;
    private final BaseFragment parentFragment;
    private final LinearLayout cardsContainer;
    private String lastShownSignature = "";

    public DrGramStickyNotifBar(Context context, BaseFragment parent, int account) {
        super(context);
        currentAccount = account;
        parentFragment = parent;
        setClipChildren(false);
        setClipToPadding(false);
        setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(4), AndroidUtilities.dp(10), AndroidUtilities.dp(4));
        cardsContainer = new LinearLayout(context);
        cardsContainer.setOrientation(LinearLayout.VERTICAL);
        addView(cardsContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));
        setVisibility(GONE);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.dialogsNeedReload);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.updateInterfaces);
        refresh();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.dialogsNeedReload);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.updateInterfaces);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        refresh();
    }

    public void refresh() {
        try {
            if (!SharedConfig.drgramStickyNotifs) {
                applyVisibility(false, "");
                return;
            }
            DrGramStickyNotifs store = DrGramStickyNotifs.getInstance(currentAccount);
            store.pruneUnread();
            ArrayList<DrGramStickyNotifs.StickyItem> list = store.getList();
            if (list.isEmpty()) {
                applyVisibility(false, "");
                return;
            }
            int from = Math.max(0, list.size() - MAX_CARDS);
            StringBuilder signature = new StringBuilder();
            for (int i = from; i < list.size(); i++) {
                signature.append(list.get(i).dialogId).append('|');
            }
            String sig = signature.toString();
            if (sig.equals(lastShownSignature) && getVisibility() == VISIBLE) {
                return;
            }
            lastShownSignature = sig;
            rebuildCards(list, from);
            setVisibility(VISIBLE);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private void applyVisibility(boolean visible, String signature) {
        lastShownSignature = signature;
        setVisibility(visible ? VISIBLE : GONE);
    }

    private void rebuildCards(ArrayList<DrGramStickyNotifs.StickyItem> list, int from) {
        cardsContainer.removeAllViews();
        boolean loveMode = DrGramThemeInstaller.isLoveMode();
        for (int i = list.size() - 1; i >= from; i--) {
            cardsContainer.addView(makeCard(list.get(i), loveMode), LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, AndroidUtilities.dp(6)));
        }
    }

    private LinearLayout makeCard(final DrGramStickyNotifs.StickyItem item, boolean loveMode) {
        Context context = getContext();
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                loveMode ? new int[] {0xF22A1018, 0xF21C0B11} : new int[] {0xF23A2C0E, 0xF2211A0A});
        background.setCornerRadius(AndroidUtilities.dp(20));
        background.setStroke(AndroidUtilities.dp(1), loveMode ? 0xCCE63B52 : 0xCCFFC94A);
        card.setBackgroundDrawable(background);
        card.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(8), AndroidUtilities.dp(10));
        card.setElevation(AndroidUtilities.dp(3));

        View avatar = new View(context);
        avatar.setBackgroundDrawable(makeAvatarDrawable(item.dialogId));
        card.addView(avatar, LayoutHelper.createLinear(AndroidUtilities.dp(38), AndroidUtilities.dp(38), 0, 0, AndroidUtilities.dp(10), 0));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFFFFF6DF);
        title.setText(item.title);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView body = new TextView(context);
        body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        body.setTextColor(0xCCFFF6DF);
        body.setText(item.text);
        body.setMaxLines(2);
        body.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, AndroidUtilities.dp(1), 0, 0));

        card.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        TextView close = new TextView(context);
        close.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        close.setTextColor(0x99FFF6DF);
        close.setGravity(Gravity.CENTER);
        close.setText("×");
        close.setOnClickListener(v -> {
            DrGramStickyNotifs.getInstance(currentAccount).dismiss(item.dialogId);
            lastShownSignature = "";
            refresh();
        });
        card.addView(close, LayoutHelper.createLinear(AndroidUtilities.dp(30), AndroidUtilities.dp(40), 0, 0, AndroidUtilities.dp(4), 0));

        card.setOnClickListener(v -> openDialog(item.dialogId));
        return card;
    }

    private Drawable makeAvatarDrawable(long dialogId) {
        try {
            if (dialogId < 0) {
                TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialogId);
                if (chat != null) {
                    return new AvatarDrawable(chat);
                }
            } else {
                TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(dialogId);
                if (user != null) {
                    return new AvatarDrawable(user);
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        GradientDrawable fallback = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                DrGramThemeInstaller.isLoveMode() ? new int[] {0xFFE63B52, 0xFF7A1F30} : new int[] {0xFFF5B90D, 0xFF8A6A0B});
        fallback.setShape(GradientDrawable.OVAL);
        return fallback;
    }

    private void openDialog(long dialogId) {
        try {
            if (parentFragment == null || parentFragment.getParentActivity() == null) {
                return;
            }
            Bundle args = new Bundle();
            args.putBoolean("scrollToTopOnResume", true);
            args.putLong("dialog_id", dialogId);
            parentFragment.presentFragment(new ChatActivity(args), false);
            DrGramStickyNotifs.getInstance(currentAccount).dismiss(dialogId);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }
}
