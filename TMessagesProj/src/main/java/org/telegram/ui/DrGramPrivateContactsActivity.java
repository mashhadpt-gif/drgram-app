/*
 * DrGram Private Contacts settings.
 * Users can locally hide/unhide existing contacts without deleting Telegram contacts.
 */
package org.telegram.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DrGramPrivateContacts;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

public class DrGramPrivateContactsActivity extends BaseFragment {

    private LinearLayout rows;

    private static final class ContactRow {
        long userId;
        String title;
        String subtitle;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.DrGramPrivateContactsTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(24));
        rows.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        scroll.addView(rows, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fragmentView = scroll;
        rebuildRows();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        rebuildRows();
    }

    private void rebuildRows() {
        if (rows == null) return;
        Context context = rows.getContext();
        rows.removeAllViews();

        TextView hint = new TextView(context);
        hint.setText(LocaleController.getString(R.string.DrGramPrivateContactsHint));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        hint.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        hint.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(12), AndroidUtilities.dp(20), AndroidUtilities.dp(14));
        rows.addView(hint);

        ArrayList<ContactRow> contacts = loadContacts();
        if (contacts.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText(LocaleController.getString(R.string.DrGramPrivateContactsEmpty));
            empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            empty.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(28), AndroidUtilities.dp(24), AndroidUtilities.dp(28));
            rows.addView(empty);
            return;
        }

        for (ContactRow item : contacts) {
            final ContactRow contact = item;
            boolean isPrivate = DrGramPrivateContacts.isPrivate(currentAccount, item.userId);
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(12));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(AndroidUtilities.dp(14));
            bg.setColor(isPrivate ? 0xFF2B1017 : getThemedColor(Theme.key_dialogBackground));
            bg.setStroke(AndroidUtilities.dp(isPrivate ? 2 : 1), isPrivate ? 0xFFE63B52 : getThemedColor(Theme.key_divider));
            row.setBackgroundDrawable(bg);

            TextView title = new TextView(context);
            title.setText(item.title);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            title.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            row.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            TextView subtitle = new TextView(context);
            String status = LocaleController.getString(isPrivate
                    ? R.string.DrGramPrivateContactsHidden
                    : R.string.DrGramPrivateContactsVisible);
            subtitle.setText(item.subtitle.isEmpty() ? status : item.subtitle + "  •  " + status);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            subtitle.setTextColor(isPrivate ? 0xFFE63B52 : getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
            row.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(AndroidUtilities.dp(14), AndroidUtilities.dp(4), AndroidUtilities.dp(14), AndroidUtilities.dp(4));
            rows.addView(row, lp);
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> {
                boolean newValue = !DrGramPrivateContacts.isPrivate(currentAccount, contact.userId);
                if (DrGramPrivateContacts.setPrivate(currentAccount, contact.userId, newValue)) {
                    rebuildRows();
                }
            });
        }
    }

    private ArrayList<ContactRow> loadContacts() {
        ArrayList<ContactRow> result = new ArrayList<>();
        try {
            ArrayList<TLRPC.TL_contact> contacts = ContactsController.getInstance(currentAccount).contacts;
            if (contacts == null) return result;
            MessagesController messages = MessagesController.getInstance(currentAccount);
            TLRPC.User currentUser = UserConfig.getInstance(currentAccount).getCurrentUser();
            for (TLRPC.TL_contact contact : contacts) {
                if (contact == null || (currentUser != null && contact.user_id == currentUser.id)) {
                    continue;
                }
                TLRPC.User user = messages.getUser(contact.user_id);
                if (user == null || user.bot || user.deleted) continue;
                ContactRow row = new ContactRow();
                row.userId = user.id;
                row.title = UserObject.getUserName(user);
                row.subtitle = user.username == null || user.username.isEmpty() ? "" : "@" + user.username;
                result.add(row);
            }
            Collections.sort(result, Comparator.comparing((ContactRow a) -> a.title == null ? "" : a.title, String.CASE_INSENSITIVE_ORDER));
        } catch (Throwable ignore) {
        }
        return result;
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
