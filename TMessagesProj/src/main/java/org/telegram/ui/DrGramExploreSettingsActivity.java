/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGramExploreSettingsActivity — settings for the Explore feed (DrGram Fix 31).
 * Opened from the gear button at the top of Explore. Everything the user asked
 * for lives here:
 *   - Hidden channels: restore any channel hidden via the chip long-press menu,
 *     one by one or all at once (this is the missing "undo" for hiding).
 *   - Media filter: show everything, only videos or only photos.
 *   - Feed: hide seen posts (on/off) and clear the seen history.
 *   - Grid columns: auto, 3, 4 or 5 tiles per row.
 * All values are stored per account inside the Explore prefs. Original design
 * follows the luxury control-center language (dark gradient, gold cards).
 */
package org.telegram.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DrGramExploreController;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.DrGramLuxDecor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Switch;

public class DrGramExploreSettingsActivity extends BaseFragment {

    private static final int GOLD = 0xFFF5B90D;

    private LinearLayout contentView;
    private LinearLayout hiddenContainer;
    private LinearLayout filterContainer;
    private LinearLayout columnsContainer;
    private Switch hideSeenSwitch;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.DrGramExploreSettings));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        contentView = new LinearLayout(context);
        contentView.setOrientation(LinearLayout.VERTICAL);
        contentView.setPadding(0, 0, 0, AndroidUtilities.dp(28));
        contentView.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                org.telegram.messenger.DrGramThemeInstaller.isLoveMode()
                        ? new int[] {0xFF160A0F, 0xFF251017, 0xFF10090D}
                        : new int[] {0xFF0C0A05, 0xFF261D09, 0xFF0C0A05}));

        // DrGram Fix 31 lesson learned from the control-center crash: never call
        // setScrollbarFadingEnabled on our custom screens and keep framework
        // scrollbars disabled — the page must be draw-proof on every device.
        android.widget.ScrollView scroller = new android.widget.ScrollView(context);
        scroller.setFillViewport(true);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.addView(contentView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fragmentView = scroller;

        TextView hero = new TextView(context);
        hero.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        hero.setTextColor(0xFFFFC94A);
        hero.setGravity(Gravity.CENTER);
        hero.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(22), AndroidUtilities.dp(24), AndroidUtilities.dp(6));
        hero.setText(LocaleController.getString(R.string.DrGramExploreSetSub));
        contentView.addView(hero);

        try {
            contentView.addView(DrGramLuxDecor.ornamentDivider(context, org.telegram.messenger.DrGramThemeInstaller.isLoveMode()));
        } catch (Throwable t) {
            FileLog.e(t);
        }

        // ---- hidden channels ----
        addHeader(context, LocaleController.getString(R.string.DrGramExploreSetHidden), null);
        hiddenContainer = new LinearLayout(context);
        hiddenContainer.setOrientation(LinearLayout.VERTICAL);
        contentView.addView(hiddenContainer);
        rebuildHiddenRows();

        // ---- media filter ----
        addHeader(context, LocaleController.getString(R.string.DrGramExploreSetFilter), null);
        filterContainer = new LinearLayout(context);
        filterContainer.setOrientation(LinearLayout.VERTICAL);
        contentView.addView(filterContainer);
        rebuildFilterRows();

        // ---- feed behaviour ----
        addHeader(context, LocaleController.getString(R.string.DrGramExploreSetFeed), null);
        hideSeenSwitch = new Switch(context);
        hideSeenSwitch.setChecked(getController().isHideSeen(), false);
        contentView.addView(makeToggleRow(context,
                LocaleController.getString(R.string.DrGramExploreHideSeen),
                LocaleController.getString(R.string.DrGramExploreHideSeenDesc),
                hideSeenSwitch, () -> {
                    getController().setHideSeen(!getController().isHideSeen());
                    hideSeenSwitch.setChecked(getController().isHideSeen(), false);
                }));
        contentView.addView(makeActionRow(context,
                LocaleController.getString(R.string.DrGramExploreClearSeen),
                LocaleController.getString(R.string.DrGramExploreHideSeenDesc), () -> {
                    getController().clearSeenHistory();
                    showToast(LocaleController.getString(R.string.DrGramExploreClearSeenDone));
                }));

        // ---- grid columns ----
        addHeader(context, LocaleController.getString(R.string.DrGramExploreSetColumns),
                LocaleController.getString(R.string.DrGramExploreColumnsHint));
        columnsContainer = new LinearLayout(context);
        columnsContainer.setOrientation(LinearLayout.VERTICAL);
        contentView.addView(columnsContainer);
        rebuildColumnRows();

        try {
            contentView.addView(DrGramLuxDecor.signatureFooter(context, org.telegram.messenger.DrGramThemeInstaller.isLoveMode()));
        } catch (Throwable t) {
            FileLog.e(t);
        }

        return fragmentView;
    }

    private DrGramExploreController getController() {
        return DrGramExploreController.getInstance(currentAccount);
    }

    //region Hidden channels

    private void rebuildHiddenRows() {
        Context context = getContext();
        if (hiddenContainer == null || context == null) {
            return;
        }
        hiddenContainer.removeAllViews();
        java.util.ArrayList<Long> hidden = getController().getHiddenList();
        if (hidden.isEmpty()) {
            hiddenContainer.addView(makeStaticRow(context,
                    LocaleController.getString(R.string.DrGramExploreSetHiddenEmpty), 0x99E4D2A5));
            return;
        }
        for (int a = 0; a < hidden.size(); a++) {
            hiddenContainer.addView(makeHiddenChannelRow(context, hidden.get(a)));
        }
        hiddenContainer.addView(makeActionRow(context,
                LocaleController.getString(R.string.DrGramExploreUnhideAll),
                null, () -> {
                    java.util.ArrayList<Long> all = getController().getHiddenList();
                    for (int a = 0; a < all.size(); a++) {
                        getController().setHidden(all.get(a), false);
                    }
                    getController().refreshIfNeeded(true);
                    rebuildHiddenRows();
                    showToast(LocaleController.getString(R.string.DrGramExploreUnhideAll));
                }));
    }

    private LinearLayout makeHiddenChannelRow(Context context, long dialogId) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundDrawable(goldCard(context));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(AndroidUtilities.dp(16), AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(4));
        row.setLayoutParams(rowParams);
        row.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(10));

        TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
        BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(AndroidUtilities.dp(19));
        AvatarDrawable avatarDrawable = new AvatarDrawable(chat);
        avatar.setForUserOrChat(chat, avatarDrawable);
        row.addView(avatar, LayoutHelper.createLinear(38, 38, Gravity.CENTER_VERTICAL));

        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        title.setTextColor(0xFFFFF6DF);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setText(chat != null && !TextUtils.isEmpty(chat.title) ? chat.title : ("#" + dialogId));
        textCol.addView(title);

        LinearLayout titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.setMargins(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(8), 0);
        row.addView(textCol, titleParams);

        TextView restore = new TextView(context);
        restore.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        restore.setTypeface(AndroidUtilities.bold());
        restore.setTextColor(0xFF141005);
        restore.setText(LocaleController.getString(R.string.DrGramExploreRestore));
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(AndroidUtilities.dp(14));
        pill.setColor(GOLD);
        restore.setBackgroundDrawable(pill);
        int padH = AndroidUtilities.dp(14);
        restore.setPadding(padH, AndroidUtilities.dp(6), padH, AndroidUtilities.dp(6));
        restore.setOnClickListener(v -> {
            getController().setHidden(dialogId, false);
            getController().refreshIfNeeded(true);
            rebuildHiddenRows();
        });
        row.addView(restore, LayoutHelper.createLinear(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        return row;
    }
    //endregion

    //region Media filter / columns

    private void rebuildFilterRows() {
        Context context = getContext();
        if (filterContainer == null || context == null) {
            return;
        }
        int current = getController().getMediaFilter();
        filterContainer.removeAllViews();
        filterContainer.addView(makeChoiceRow(context,
                LocaleController.getString(R.string.ExploreFilterAll), current == DrGramExploreController.FILTER_ALL, () -> {
                    getController().setMediaFilter(DrGramExploreController.FILTER_ALL);
                    rebuildFilterRows();
                }));
        filterContainer.addView(makeChoiceRow(context,
                LocaleController.getString(R.string.DrGramExploreFilterVideos), current == DrGramExploreController.FILTER_VIDEOS, () -> {
                    getController().setMediaFilter(DrGramExploreController.FILTER_VIDEOS);
                    rebuildFilterRows();
                }));
        filterContainer.addView(makeChoiceRow(context,
                LocaleController.getString(R.string.DrGramExploreFilterPhotos), current == DrGramExploreController.FILTER_PHOTOS, () -> {
                    getController().setMediaFilter(DrGramExploreController.FILTER_PHOTOS);
                    rebuildFilterRows();
                }));
    }

    private void rebuildColumnRows() {
        Context context = getContext();
        if (columnsContainer == null || context == null) {
            return;
        }
        int current = getController().getGridColumns();
        columnsContainer.removeAllViews();
        columnsContainer.addView(makeChoiceRow(context,
                LocaleController.getString(R.string.DrGramExploreColumnsAuto), current == 0, () -> {
                    getController().setGridColumns(0);
                    rebuildColumnRows();
                    showToast(LocaleController.getString(R.string.DrGramExploreColumnsHint));
                }));
        for (int columns = 3; columns <= 5; columns++) {
            final int value = columns;
            columnsContainer.addView(makeChoiceRow(context,
                    String.valueOf(columns), current == value, () -> {
                        getController().setGridColumns(value);
                        rebuildColumnRows();
                        showToast(LocaleController.getString(R.string.DrGramExploreColumnsHint));
                    }));
        }
    }

    private LinearLayout makeChoiceRow(Context context, String title, boolean selected, Runnable action) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundDrawable(goldCard(context));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(AndroidUtilities.dp(16), AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(4));
        row.setLayoutParams(rowParams);
        row.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(13), AndroidUtilities.dp(16), AndroidUtilities.dp(13));

        TextView titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        titleView.setTextColor(selected ? GOLD : 0xFFF2E8CF);
        titleView.setTypeface(selected ? AndroidUtilities.bold() : android.graphics.Typeface.DEFAULT);
        titleView.setText(title);
        row.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView check = new TextView(context);
        check.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        check.setTypeface(AndroidUtilities.bold());
        check.setTextColor(GOLD);
        check.setText("✓");
        check.setVisibility(selected ? View.VISIBLE : View.GONE);
        row.addView(check, LayoutHelper.createLinear(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

        row.setOnClickListener(v -> {
            try {
                action.run();
            } catch (Throwable t) {
                FileLog.e(t);
            }
        });
        return row;
    }
    //endregion

    //region Row builders

    private void addHeader(Context context, String title, String subtitle) {
        TextView header = new TextView(context);
        header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        header.setTypeface(AndroidUtilities.bold());
        header.setTextColor(0xFFFFC94A);
        header.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(18), AndroidUtilities.dp(24), AndroidUtilities.dp(4));
        header.setText(title);
        contentView.addView(header);
        if (subtitle != null) {
            TextView sub = new TextView(context);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            sub.setTextColor(0x99E4D2A5);
            sub.setPadding(AndroidUtilities.dp(24), 0, AndroidUtilities.dp(24), AndroidUtilities.dp(4));
            sub.setText(subtitle);
            contentView.addView(sub);
        }
    }

    private LinearLayout makeActionRow(Context context, String title, String subtitle, Runnable action) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundDrawable(goldCard(context));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(AndroidUtilities.dp(16), AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(4));
        row.setLayoutParams(rowParams);
        row.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(13), AndroidUtilities.dp(16), AndroidUtilities.dp(13));

        TextView titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        titleView.setTextColor(0xFFFFF6DF);
        titleView.setText(title);
        row.addView(titleView);
        if (subtitle != null) {
            TextView subView = new TextView(context);
            subView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            subView.setTextColor(0x99E4D2A5);
            subView.setPadding(0, AndroidUtilities.dp(2), 0, 0);
            subView.setText(subtitle);
            row.addView(subView);
        }
        row.setOnClickListener(v -> {
            try {
                action.run();
            } catch (Throwable t) {
                FileLog.e(t);
            }
        });
        return row;
    }

    private LinearLayout makeToggleRow(Context context, String title, String subtitle, Switch toggleView, Runnable action) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundDrawable(goldCard(context));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(AndroidUtilities.dp(16), AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(4));
        row.setLayoutParams(rowParams);
        row.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(10));

        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        titleView.setTextColor(0xFFFFF6DF);
        titleView.setText(title);
        textCol.addView(titleView);
        if (subtitle != null) {
            TextView subView = new TextView(context);
            subView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            subView.setTextColor(0x99E4D2A5);
            subView.setPadding(0, AndroidUtilities.dp(2), 0, 0);
            subView.setText(subtitle);
            textCol.addView(subView);
        }
        row.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(toggleView, LayoutHelper.createLinear(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        row.setOnClickListener(v -> {
            try {
                action.run();
            } catch (Throwable t) {
                FileLog.e(t);
            }
        });
        return row;
    }

    private LinearLayout makeStaticRow(Context context, String text, int color) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        view.setTextColor(color);
        view.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(6), AndroidUtilities.dp(24), AndroidUtilities.dp(6));
        view.setText(text);
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private GradientDrawable goldCard(Context context) {
        boolean loveMode = org.telegram.messenger.DrGramThemeInstaller.isLoveMode();
        GradientDrawable card = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                loveMode ? new int[] {0xFF2A1018, 0xFF1C0B11} : new int[] {0xFF3A2C0E, 0xFF211A0A});
        card.setCornerRadius(AndroidUtilities.dp(17));
        card.setStroke(AndroidUtilities.dp(1), loveMode ? 0x66E63B52 : 0x66FFC94A);
        return card;
    }

    private void showToast(String text) {
        try {
            if (getParentActivity() != null && text != null) {
                Toast.makeText(getParentActivity(), text, Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignore) {}
    }
    //endregion

    // DrGram Fix 13 pattern: self-managed edge-to-edge so the last rows clear the navbar
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
