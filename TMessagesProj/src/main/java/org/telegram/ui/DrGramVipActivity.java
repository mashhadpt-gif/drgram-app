/*
 * DrGram VIP Circle — manage the people you never want to lose track of.
 * Each member gets a personal tag color; the circle lives only on this device.
 */
package org.telegram.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DrGramVipContacts;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.DrGramGlassKit;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import java.util.List;

public class DrGramVipActivity extends BaseFragment {

    private static final int[] TAG_COLORS = {
            0xFFF5B90D, 0xFFE63B52, 0xFF4CC24C, 0xFF3B9BE6, 0xFFB03BE6, 0xFFFF8A3B, 0xFFF5F3EE
    };

    private LinearLayout content;
    private AlertDialog colorDialog;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.DrGramVipTitle));
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
        content.setBackground(DrGramGlassKit.pageGradient()); // DrGram Fix 36: glass canvas
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fragmentView = scroll;

        TextView hint = new TextView(context);
        hint.setText(LocaleController.getString(R.string.DrGramVipHint));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        hint.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        hint.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(18), AndroidUtilities.dp(20), AndroidUtilities.dp(8));
        content.addView(hint);

        content.addView(makeAddRow(context));
        rebuild();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        rebuild();
    }

    private View makeAddRow(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(themedCard(0xFFF5B90D));
        row.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(13), AndroidUtilities.dp(16), AndroidUtilities.dp(13));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(AndroidUtilities.dp(16), AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(6));
        row.setLayoutParams(params);

        TextView label = new TextView(context);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        label.setTypeface(AndroidUtilities.bold());
        label.setTextColor(0xFFFFF6DF);
        label.setText("＋  " + LocaleController.getString(R.string.DrGramVipAdd));
        row.addView(label, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        row.setOnClickListener(v -> openPicker());
        return row;
    }

    private static GradientDrawable themedCard(int stroke) {
        // DrGram Fix 36: translucent glass panel with a hairline accent stroke
        return DrGramGlassKit.panel(null, 18);
    }

    private void openPicker() {
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_USERS_ONLY);
        DialogsActivity fragment = new DialogsActivity(args);
        fragment.setDelegate((f, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            boolean added = false;
            for (int i = 0; i < dids.size(); i++) {
                long dialogId = dids.get(i).dialogId;
                if (dialogId > 0 && !DrGramVipContacts.isVip(currentAccount, dialogId)) {
                    DrGramVipContacts.add(currentAccount, dialogId, TAG_COLORS[DrGramVipContacts.getMembers(currentAccount).size() % TAG_COLORS.length]);
                    added = true;
                }
            }
            if (added) {
                rebuild();
                if (getParentActivity() != null) {
                    Toast.makeText(getParentActivity(), LocaleController.getString(R.string.DrGramVipAdded), Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        });
        presentFragment(fragment);
    }

    private void rebuild() {
        if (content == null) {
            return;
        }
        // remove old member rows (keep hint + add row)
        while (content.getChildCount() > 2) {
            content.removeViewAt(2);
        }
        Context context = content.getContext();
        List<DrGramVipContacts.Member> members = DrGramVipContacts.getMembers(currentAccount);
        if (members.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText(LocaleController.getString(R.string.DrGramVipEmpty));
            empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            empty.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
            empty.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(10), AndroidUtilities.dp(20), AndroidUtilities.dp(10));
            content.addView(empty);
            return;
        }
        for (DrGramVipContacts.Member member : members) {
            final long userId = member.userId;
            TLRPC.User user = getMessagesController().getUser(userId);
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(themedCard(member.color));
            row.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(10), AndroidUtilities.dp(14), AndroidUtilities.dp(10));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(AndroidUtilities.dp(16), AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(4));
            row.setLayoutParams(params);

            FrameLayout avatarHolder = new FrameLayout(context);
            BackupImageView avatar = new BackupImageView(context);
            avatar.setRoundRadius(AndroidUtilities.dp(20));
            AvatarDrawable avatarDrawable = user != null ? new AvatarDrawable(user) : new AvatarDrawable();
            if (user != null) {
                avatar.setForUserOrChat(user, avatarDrawable);
            }
            avatarHolder.addView(avatar, LayoutHelper.createFrame(40, 40));

            View tag = new View(context);
            GradientDrawable tagDrawable = new GradientDrawable();
            tagDrawable.setShape(GradientDrawable.OVAL);
            tagDrawable.setColor(member.color);
            tag.setBackgroundDrawable(tagDrawable);
            avatarHolder.addView(tag, LayoutHelper.createFrame(12, 12, Gravity.BOTTOM | Gravity.END, 0, 0, 0, 0));
            row.addView(avatarHolder);

            TextView name = new TextView(context);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            name.setTextColor(0xFFFFF6DF);
            name.setMaxLines(1);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setText(user != null ? UserObject.getUserName(user) : "#" + userId);
            name.setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12), 0);
            row.addView(name, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            TextView colorLabel = new TextView(context);
            colorLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            colorLabel.setTextColor(0xFFC9B98F);
            colorLabel.setText(LocaleController.getString(R.string.DrGramVipColor));
            row.addView(colorLabel);
            row.setOnClickListener(v -> showColorPicker(userId, member.color));
            row.setOnLongClickListener(v -> {
                DrGramVipContacts.remove(currentAccount, userId);
                rebuild();
                return true;
            });
            content.addView(row);
        }

        TextView hint2 = new TextView(context);
        hint2.setText(LocaleController.getString(R.string.DrGramVipRemoveHint));
        hint2.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        hint2.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        hint2.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(6), AndroidUtilities.dp(20), AndroidUtilities.dp(4));
        content.addView(hint2);
    }

    private void showColorPicker(long userId, int current) {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.DrGramVipPickColor));
        LinearLayout palette = new LinearLayout(context);
        palette.setOrientation(LinearLayout.HORIZONTAL);
        palette.setGravity(Gravity.CENTER);
        palette.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(8));
        for (final int color : TAG_COLORS) {
            View swatch = new View(context);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(color);
            if (color == current) {
                d.setStroke(AndroidUtilities.dp(2), 0xFFFFFFFF);
            }
            swatch.setBackgroundDrawable(d);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(AndroidUtilities.dp(38), AndroidUtilities.dp(38));
            p.setMargins(AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6), 0);
            swatch.setLayoutParams(p);
            swatch.setOnClickListener(v -> {
                DrGramVipContacts.setColor(currentAccount, userId, color);
                rebuild();
                if (colorDialog != null) {
                    try {
                        colorDialog.dismiss();
                    } catch (Exception ignore) {
                    }
                    colorDialog = null;
                }
            });
            palette.addView(swatch);
        }
        builder.setView(palette);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        colorDialog = builder.create();
        colorDialog.show();
    }
}
