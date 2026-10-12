/*
 * DrGram Contact Radar — a private dashboard for your contact list:
 * live counts, who is online right now, and quick access to the VIP Circle.
 * Everything is computed locally from data the app already has.
 */
package org.telegram.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

public class DrGramRadarActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private LinearLayout content;
    private TextView statsLine;
    private LinearLayout onlineContainer;
    private boolean scheduled;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.DrGramRadarTitle));
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
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, AndroidUtilities.dp(24));
        content.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fragmentView = scroll;

        TextView hint = new TextView(context);
        hint.setText(LocaleController.getString(R.string.DrGramRadarHint));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        hint.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        hint.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(18), AndroidUtilities.dp(20), AndroidUtilities.dp(8));
        content.addView(hint);

        statsLine = new TextView(context);
        statsLine.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        statsLine.setTypeface(AndroidUtilities.bold());
        statsLine.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        statsLine.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(6), AndroidUtilities.dp(20), AndroidUtilities.dp(10));
        content.addView(statsLine);

        TextView section = new TextView(context);
        section.setText(LocaleController.getString(R.string.DrGramRadarOnline));
        section.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        section.setTypeface(AndroidUtilities.bold());
        section.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        section.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(14), AndroidUtilities.dp(20), AndroidUtilities.dp(6));
        content.addView(section);

        onlineContainer = new LinearLayout(context);
        onlineContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(onlineContainer);

        getNotificationCenter().addObserver(this, NotificationCenter.contactsDidLoad);
        return fragmentView;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        getNotificationCenter().removeObserver(this, NotificationCenter.contactsDidLoad);
        scheduled = false;
        AndroidUtilities.cancelRunOnUIThread(updateRunnable);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.contactsDidLoad) {
            rebuild();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        rebuild();
        if (!scheduled) {
            scheduled = true;
            AndroidUtilities.runOnUIThread(updateRunnable, 10000);
        }
    }

    private final Runnable updateRunnable = new Runnable() {
        @Override
        public void run() {
            if (getParentActivity() == null) {
                return;
            }
            rebuild();
            AndroidUtilities.runOnUIThread(this, 10000);
        }
    };

    private void rebuild() {
        if (content == null) {
            return;
        }
        ArrayList<TLRPC.TL_contact> contacts = new ArrayList<>(ContactsController.getInstance(currentAccount).contacts);
        int online = 0;
        int withUsername = 0;
        int withPhoto = 0;
        int hiddenSeen = 0;
        ArrayList<TLRPC.TL_contact> onlineNow = new ArrayList<>();
        for (TLRPC.TL_contact contact : contacts) {
            TLRPC.User user = getMessagesController().getUser(contact.user_id);
            if (user == null) {
                continue;
            }
            if (user.status instanceof TLRPC.TL_userStatusOnline) {
                online++;
                onlineNow.add(contact);
            } else if (user.status instanceof TLRPC.TL_userStatusRecently || user.status instanceof TLRPC.TL_userStatusOffline && user.status.expires == 0) {
                if (user.status instanceof TLRPC.TL_userStatusRecently) {
                    hiddenSeen++;
                }
            }
            if (!TextUtils2(user.username)) {
                withUsername++;
            }
            if (user.photo != null && !(user.photo instanceof TLRPC.TL_userProfilePhotoEmpty)) {
                withPhoto++;
            }
        }
        Collections.sort(onlineNow, new Comparator<TLRPC.TL_contact>() {
            @Override
            public int compare(TLRPC.TL_contact a, TLRPC.TL_contact b) {
                TLRPC.User ua = getMessagesController().getUser(a.user_id);
                TLRPC.User ub = getMessagesController().getUser(b.user_id);
                int sa = ua != null && ua.status != null ? ua.status.expires : 0;
                int sb = ub != null && ub.status != null ? ub.status.expires : 0;
                return Integer.compare(sb, sa);
            }
        });

        statsLine.setText(LocaleController.formatString(R.string.DrGramRadarStats,
                contacts.size(), online, withUsername, withPhoto));

        onlineContainer.removeAllViews();
        Context context = onlineContainer.getContext();
        if (onlineNow.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText(LocaleController.getString(R.string.DrGramRadarEmpty));
            empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            empty.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
            empty.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(10), AndroidUtilities.dp(20), AndroidUtilities.dp(10));
            onlineContainer.addView(empty);
            return;
        }
        int shown = 0;
        for (TLRPC.TL_contact contact : onlineNow) {
            if (shown >= 50) {
                break;
            }
            TLRPC.User user = getMessagesController().getUser(contact.user_id);
            if (user == null) {
                continue;
            }
            onlineContainer.addView(makeOnlineRow(context, user));
            shown++;
        }
    }

    private static boolean TextUtils2(String s) {
        return s == null || s.trim().isEmpty();
    }

    private View makeOnlineRow(Context context, TLRPC.User user) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(8), AndroidUtilities.dp(20), AndroidUtilities.dp(8));

        FrameLayout avatarHolder = new FrameLayout(context);
        BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(AndroidUtilities.dp(22));
        AvatarDrawable avatarDrawable = new AvatarDrawable(user);
        avatar.setForUserOrChat(user, avatarDrawable);
        avatarHolder.addView(avatar, LayoutHelper.createFrame(44, 44));

        View dot = new View(context);
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        d.setColor(0xFF4CC24C);
        dot.setBackgroundDrawable(d);
        android.widget.FrameLayout.LayoutParams dotParams = LayoutHelper.createFrame(12, 12, Gravity.BOTTOM | Gravity.END, 0, 0, 0, 0);
        avatarHolder.addView(dot, dotParams);

        row.addView(avatarHolder);

        TextView name = new TextView(context);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        name.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        name.setText(UserObject.getUserName(user));
        name.setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12), 0);
        row.addView(name, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        TextView status = new TextView(context);
        status.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        status.setTextColor(0xFF4CC24C);
        status.setText(LocaleController.getString(R.string.DrGramRadarNow));
        row.addView(status);

        row.setOnClickListener(v -> {
            Bundle args = new Bundle();
            args.putLong("user_id", user.id);
            presentFragment(new ProfileActivity(args));
        });
        return row;
    }
}
