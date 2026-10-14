/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Smart Reply UI (Fix 35) — glassmorphism page matching the LuxGram
 * Tools design: glass panels, hairline strokes, flat rows, clear rhythm.
 * Left panel = PV commands, right panel = group admin commands; every
 * trigger is renameable, every command toggleable, custom PV commands are
 * fully editable.
 */
package org.telegram.ui;

import android.content.Context;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.DrGramSmartReply;
import org.telegram.messenger.DrGramThemeInstaller;
import org.telegram.messenger.DrGramWeather; // DrGram Fix 40
import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Switch;

import java.util.ArrayList;

public class DrGramSmartReplyActivity extends BaseFragment {

    /** Tiny local callback — avoids any API-level or Utilities dependency (Fix 24 lesson). */
    private interface BoolCallback {
        void run(boolean value);
    }

    private LinearLayout contentView;
    private LinearLayout customListContainer;
    private final java.util.HashMap<String, TextView> chipViews = new java.util.HashMap<>();

    @Override
    public View createView(Context context) {
        // DrGram Fix 35: the page must never take the app down (Fix 29 lesson)
        try {
            return drgramCreateViewInternal(context);
        } catch (Throwable t) {
            FileLog.e(t);
            return drgramFallbackView(context);
        }
    }

    private View drgramCreateViewInternal(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.DrGramSmartReplyTitle));
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
        contentView.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                DrGramThemeInstaller.isLoveMode()
                        ? new int[] {0xFF0E070B, 0xFF1C0D13, 0xFF0E070B}
                        : new int[] {0xFF0B0A07, 0xFF191409, 0xFF0B0A07}));

        android.widget.ScrollView scroller = new android.widget.ScrollView(context);
        scroller.setFillViewport(true);
        scroller.setClipToPadding(false);
        scroller.addView(contentView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fragmentView = scroller;

        try {
            addGlassHero(context);
        } catch (Throwable t) {
            FileLog.e(t);
        }

        buildModulesSection(context);
        buildPvSection(context);
        buildGroupSection(context);

        try {
            contentView.addView(org.telegram.ui.Components.DrGramLuxDecor.signatureFooter(context,
                    DrGramThemeInstaller.isLoveMode()));
        } catch (Throwable t) {
            FileLog.e(t);
        }

        return fragmentView;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        super.onInsets(left, top, right, bottom);
        try {
            if (fragmentView instanceof ViewGroup) {
                ViewGroup root = (ViewGroup) fragmentView;
                if (root instanceof android.widget.ScrollView && root.getChildCount() > 0
                        && root.getChildAt(0) instanceof ViewGroup) {
                    ViewGroup target = (ViewGroup) root.getChildAt(0);
                    target.setPadding(target.getPaddingLeft(), target.getPaddingTop(),
                            target.getPaddingRight(), Math.max(target.getPaddingBottom(), bottom + AndroidUtilities.dp(16)));
                    target.setClipToPadding(false);
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private View drgramFallbackView(Context context) {
        LinearLayout fb = new LinearLayout(context);
        fb.setOrientation(LinearLayout.VERTICAL);
        fb.setGravity(Gravity.CENTER);
        fb.setBackgroundColor(0xFF0B0A07);
        TextView tv = new TextView(context);
        tv.setTextColor(0xFFFFC94A);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        tv.setPadding(AndroidUtilities.dp(28), AndroidUtilities.dp(28), AndroidUtilities.dp(28), AndroidUtilities.dp(28));
        tv.setText(LocaleController.getString(R.string.DrGramSmartFailed));
        fb.addView(tv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return fb;
    }

    //region glass design system (mirrors the Tools page, Fix 34)

    private android.graphics.drawable.GradientDrawable glassPanel(float radiusDp, int fill, int stroke) {
        android.graphics.drawable.GradientDrawable panel = new android.graphics.drawable.GradientDrawable();
        panel.setCornerRadius(AndroidUtilities.dp(radiusDp));
        panel.setColor(fill);
        panel.setStroke(AndroidUtilities.dp(1), stroke);
        return panel;
    }

    private android.graphics.drawable.Drawable glassRipple() {
        try {
            android.graphics.drawable.GradientDrawable mask = new android.graphics.drawable.GradientDrawable();
            mask.setCornerRadius(AndroidUtilities.dp(12));
            mask.setColor(0xFFFFFFFF);
            return new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x24FFFFFF), null, mask);
        } catch (Throwable t) {
            return null;
        }
    }

    private void applyListDividers(LinearLayout list) {
        list.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        list.setDividerDrawable(new android.graphics.drawable.InsetDrawable(
                new android.graphics.drawable.ColorDrawable(0x12FFFFFF),
                AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), 0));
    }

    private LinearLayout openGlassCard() {
        boolean love = DrGramThemeInstaller.isLoveMode();
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(glassPanel(20, 0x12FFFFFF, love ? 0x30F06A82 : 0x2EF4D27A));
        applyListDividers(card);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14), AndroidUtilities.dp(10));
        contentView.addView(card, cardParams);
        return card;
    }

    private void addSectionHeader(Context context, String title, String hint) {
        TextView header = new TextView(context);
        header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        header.setTypeface(AndroidUtilities.bold());
        header.setLetterSpacing(0.04f);
        header.setTextColor(DrGramThemeInstaller.accentColor());
        header.setText("❖  " + title);
        header.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(24), AndroidUtilities.dp(21), AndroidUtilities.dp(3));
        contentView.addView(header);

        TextView hintView = new TextView(context);
        hintView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
        hintView.setTextColor(DrGramThemeInstaller.isLoveMode() ? 0x8FD8A8B4 : 0x8FC7BA93);
        hintView.setLineSpacing(AndroidUtilities.dp(2), 1f);
        hintView.setPadding(AndroidUtilities.dp(21), 0, AndroidUtilities.dp(21), AndroidUtilities.dp(12));
        hintView.setText(hint);
        contentView.addView(hintView);
    }

    private void addGlassHero(Context context) {
        boolean love = DrGramThemeInstaller.isLoveMode();
        LinearLayout hero = new LinearLayout(context);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER);
        hero.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(26), AndroidUtilities.dp(24), AndroidUtilities.dp(24));
        hero.setBackground(glassPanel(24, 0x16FFFFFF, love ? 0x4DF06A82 : 0x4DF4D27A));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.setMargins(AndroidUtilities.dp(14), AndroidUtilities.dp(18), AndroidUtilities.dp(14), AndroidUtilities.dp(4));
        contentView.addView(hero, hp);

        TextView emblem = new TextView(context);
        emblem.setText(love ? "♡  ✦  ♡" : "✦  ◈  ✦");
        emblem.setTextColor(love ? 0xFFFFA4B5 : 0xFFFFE6A0);
        emblem.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        emblem.setGravity(Gravity.CENTER);
        emblem.setLetterSpacing(0.14f);
        hero.addView(emblem, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView brand = new TextView(context);
        brand.setText("SMART  REPLY");
        brand.setTextColor(love ? 0xFFFFDCE3 : 0xFFFFE8AE);
        brand.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 21);
        brand.setTypeface(AndroidUtilities.bold());
        brand.setGravity(Gravity.CENTER);
        brand.setLetterSpacing(0.14f);
        LinearLayout.LayoutParams bp = LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT);
        bp.topMargin = AndroidUtilities.dp(8);
        hero.addView(brand, bp);

        TextView tagline = new TextView(context);
        tagline.setText(LocaleController.getString(R.string.DrGramSmartHero));
        tagline.setTextColor(love ? 0xA6E8B8C3 : 0xA6E4D2A5);
        tagline.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        tagline.setGravity(Gravity.CENTER);
        tagline.setLineSpacing(AndroidUtilities.dp(3), 1f);
        LinearLayout.LayoutParams tp = LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT);
        tp.topMargin = AndroidUtilities.dp(8);
        hero.addView(tagline, tp);

        TextView state = new TextView(context);
        state.setText(love ? "♥  PV • GROUP • AUTOMATED" : "✧  PV • GROUP • AUTOMATED");
        state.setTextColor(DrGramThemeInstaller.accentColor());
        state.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 9);
        state.setGravity(Gravity.CENTER);
        state.setTypeface(AndroidUtilities.bold());
        state.setLetterSpacing(0.10f);
        android.graphics.drawable.GradientDrawable capsule = new android.graphics.drawable.GradientDrawable();
        capsule.setColor(0x0AFFFFFF);
        capsule.setCornerRadius(AndroidUtilities.dp(30));
        capsule.setStroke(AndroidUtilities.dp(1), love ? 0x66F06A82 : 0x66F4D27A);
        state.setBackground(capsule);
        state.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(7), AndroidUtilities.dp(14), AndroidUtilities.dp(7));
        LinearLayout.LayoutParams sp = LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT);
        sp.topMargin = AndroidUtilities.dp(14);
        hero.addView(state, sp);
    }

    //endregion

    //region sections

    private void buildModulesSection(Context context) {
        addSectionHeader(context, LocaleController.getString(R.string.DrGramSmartModules), "");
        DrGramSmartReply engine = DrGramSmartReply.getInstance(currentAccount);

        LinearLayout card = openGlassCard();
        card.addView(makeSwitchRow(context,
                LocaleController.getString(R.string.DrGramSmartPvSwitch), engine.isPvEnabled(), v -> {
                    DrGramSmartReply.getInstance(currentAccount).setPvEnabled(v);
                }));
        card.addView(makeSwitchRow(context,
                LocaleController.getString(R.string.DrGramSmartGroupSwitch), engine.isGroupEnabled(), v -> {
                    DrGramSmartReply.getInstance(currentAccount).setGroupEnabled(v);
                }));
    }

    private void buildPvSection(Context context) {
        addSectionHeader(context, LocaleController.getString(R.string.DrGramSmartPvSection),
                LocaleController.getString(R.string.DrGramSmartPvHint));
        DrGramSmartReply engine = DrGramSmartReply.getInstance(currentAccount);
        LinearLayout card = openGlassCard();

        String[] infoIds = DrGramSmartReply.infoIds();
        String[] labelIds = {
                "DrGramSmartCmdTime",
                "DrGramSmartCmdDate", "DrGramSmartCmdId", "DrGramSmartCmdUsername",
                "DrGramSmartCmdBattery", "DrGramSmartCmdMe", "DrGramSmartCmdWeather",
                "DrGramSmartCmdFal", "DrGramSmartCmdHelp"
        };
        for (int i = 0; i < infoIds.length; i++) {
            String id = infoIds[i];
            String label = localeLabel(labelIds[i]);
            card.addView(makeCommandRow(context, id, label, engine.getTrigger(id), engine.isOn(id), true));
        }

        // DrGram Fix 40: weather city picker
        LinearLayout cityRow = makeActionRow(context,
                LocaleController.getString(R.string.DrGramSmartCityTitle) + "\n" + DrGramWeather.getCity(),
                v -> showCityDialog());
        cityRow.setTag("weather_city");
        card.addView(cityRow);

        // DrGram Fix 40: reply-driven commands (ذخیره / استیکر شو / عکس شو)
        String[] replyIds = DrGramSmartReply.replyIds();
        String[] replyLabels = {"DrGramSmartCmdSave", "DrGramSmartCmdSticker", "DrGramSmartCmdPhoto"};
        for (int i = 0; i < replyIds.length; i++) {
            String id = replyIds[i];
            card.addView(makeCommandRow(context, id, localeLabel(replyLabels[i]),
                    engine.getTrigger(id), engine.isOn(id), true));
        }

        customListContainer = new LinearLayout(context);
        customListContainer.setOrientation(LinearLayout.VERTICAL);
        applyListDividers(customListContainer);
        card.addView(customListContainer);

        LinearLayout addRow = makeActionRow(context, LocaleController.getString(R.string.DrGramSmartAddCommand), v ->
                showCustomDialog(null, null));
        card.addView(addRow);
        rebuildCustomList();
    }

    private void buildGroupSection(Context context) {
        addSectionHeader(context, LocaleController.getString(R.string.DrGramSmartGroupSection),
                LocaleController.getString(R.string.DrGramSmartGroupHint));
        DrGramSmartReply engine = DrGramSmartReply.getInstance(currentAccount);
        LinearLayout card = openGlassCard();

        String[] groupIds = DrGramSmartReply.groupIds();
        String[] labelIds = {"DrGramSmartCmdKick", "DrGramSmartCmdPin", "DrGramSmartCmdDel",
                "DrGramSmartCmdDelAll", "DrGramSmartCmdLink", "DrGramSmartCmdMute",
                "DrGramSmartCmdBan", "DrGramSmartCmdUnban", "DrGramSmartCmdPromote"};
        for (int i = 0; i < groupIds.length; i++) {
            String id = groupIds[i];
            card.addView(makeCommandRow(context, id, localeLabel(labelIds[i]),
                    engine.getTrigger(id), engine.isOn(id), false));
        }

        // DrGram Fix 40: group prefix commands (اسم گروه / توضیحات)
        String[] prefixIds = DrGramSmartReply.groupPrefixIds();
        String[] prefixLabels = {"DrGramSmartCmdRename", "DrGramSmartCmdDesc"};
        for (int i = 0; i < prefixIds.length; i++) {
            String id = prefixIds[i];
            card.addView(makeCommandRow(context, id, localeLabel(prefixLabels[i]),
                    engine.getTrigger(id), engine.isOn(id), false));
        }

        // DrGram Fix 40: reply commands also fire inside groups (owner)
        String[] replyIds = DrGramSmartReply.replyIds();
        String[] replyLabels = {"DrGramSmartCmdSave", "DrGramSmartCmdSticker", "DrGramSmartCmdPhoto"};
        for (int i = 0; i < replyIds.length; i++) {
            String id = replyIds[i];
            card.addView(makeCommandRow(context, id, localeLabel(replyLabels[i]),
                    engine.getTrigger(id), engine.isOn(id), false));
        }
    }

    /** DrGram Fix 40: pick the weather city with a text dialog (validated by geocoding). */
    private void showCityDialog() {
        if (getParentActivity() == null) {
            return;
        }
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.DrGramSmartCityTitle));
        builder.setMessage(LocaleController.getString(R.string.DrGramSmartCityHint));
        final android.widget.EditText input = new android.widget.EditText(getParentActivity());
        input.setSingleLine(true);
        input.setText(DrGramWeather.getCity());
        int pad = AndroidUtilities.dp(20);
        input.setPadding(pad, pad / 2, pad, pad / 2);
        builder.setView(input);
        builder.setPositiveButton(LocaleController.getString(R.string.Done), (dialog, which) -> {
            String name = input.getText().toString().trim();
            if (name.length() > 0) {
                DrGramWeather.changeCity(name, null);
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.show();
    }

    private String localeLabel(String resName) {
        try {
            int id = R.string.class.getField(resName).getInt(null);
            return LocaleController.getString(id);
        } catch (Throwable t) {
            return resName;
        }
    }

    //endregion

    //region rows

    /** Two-line command row: bold label + accent trigger chip, value view at the end. */
    private LinearLayout makeCommandRow(Context context, String id, String label, String trigger, boolean on, boolean pv) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundDrawable(glassRipple());
        row.setPadding(AndroidUtilities.dp(17), AndroidUtilities.dp(11), AndroidUtilities.dp(17), AndroidUtilities.dp(11));
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout textBox = new LinearLayout(context);
        textBox.setOrientation(LinearLayout.VERTICAL);

        TextView labelView = new TextView(context);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
        labelView.setTextColor(0xFFF1EADA);
        labelView.setText(label);
        textBox.addView(labelView);

        TextView chip = new TextView(context);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
        chip.setTypeface(AndroidUtilities.bold());
        chip.setTextColor(DrGramThemeInstaller.accentColor());
        chip.setText("»  " + trigger);
        LinearLayout.LayoutParams chipParams = LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT);
        chipParams.topMargin = AndroidUtilities.dp(2);
        textBox.addView(chip, chipParams);
        chipViews.put(id, chip);

        row.addView(textBox, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        Switch sw = new Switch(context);
        sw.setChecked(on, false);
        row.addView(sw, createSwitchParams());
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            DrGramSmartReply.getInstance(currentAccount).setOn(id, isChecked);
        });

        row.setOnClickListener(v -> showRenameDialog(id, label, trigger, pv));
        return row;
    }

    private LinearLayout makeSwitchRow(Context context, String label, boolean on, BoolCallback onChange) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundDrawable(glassRipple());
        row.setPadding(AndroidUtilities.dp(17), AndroidUtilities.dp(13), AndroidUtilities.dp(17), AndroidUtilities.dp(13));
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView labelView = new TextView(context);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
        labelView.setTextColor(0xFFF1EADA);
        labelView.setText(label);
        row.addView(labelView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        Switch sw = new Switch(context);
        sw.setChecked(on, false);
        row.addView(sw, createSwitchParams());
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> onChange.run(isChecked));
        row.setOnClickListener(v -> {
            boolean newState = !sw.isChecked();
            sw.setChecked(newState, false);
            onChange.run(newState);
        });
        return row;
    }

    private LinearLayout.LayoutParams createSwitchParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(AndroidUtilities.dp(8), 0, 0, 0);
        return params;
    }

    private LinearLayout makeActionRow(Context context, String title, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundDrawable(glassRipple());
        row.setPadding(AndroidUtilities.dp(17), AndroidUtilities.dp(13), AndroidUtilities.dp(17), AndroidUtilities.dp(13));
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView labelView = new TextView(context);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
        labelView.setTypeface(AndroidUtilities.bold());
        labelView.setTextColor(DrGramThemeInstaller.accentColor());
        labelView.setText("＋  " + title);
        row.addView(labelView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        row.setOnClickListener(listener);
        return row;
    }

    /** One custom command row inside the PV panel. */
    private LinearLayout makeCustomRow(Context context, final String trigger, final String reply) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundDrawable(glassRipple());
        row.setPadding(AndroidUtilities.dp(17), AndroidUtilities.dp(11), AndroidUtilities.dp(17), AndroidUtilities.dp(11));
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView triggerView = new TextView(context);
        triggerView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
        triggerView.setTypeface(AndroidUtilities.bold());
        triggerView.setTextColor(0xFFF1EADA);
        triggerView.setText(trigger);
        row.addView(triggerView);

        TextView replyView = new TextView(context);
        replyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
        replyView.setTextColor(DrGramThemeInstaller.isLoveMode() ? 0x99D8A8B4 : 0x99C7BA93);
        replyView.setMaxLines(1);
        replyView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        replyView.setText("↳  " + reply);
        LinearLayout.LayoutParams replyParams = LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT);
        replyParams.topMargin = AndroidUtilities.dp(2);
        row.addView(replyView, replyParams);

        row.setOnClickListener(v -> showCustomDialog(trigger, reply));
        return row;
    }

    private void rebuildCustomList() {
        if (customListContainer == null) {
            return;
        }
        customListContainer.removeAllViews();
        ArrayList<String[]> list = DrGramSmartReply.getInstance(currentAccount).getCustomCommands();
        for (int i = 0; i < list.size(); i++) {
            customListContainer.addView(makeCustomRow(customListContainer.getContext(), list.get(i)[0], list.get(i)[1]));
        }
    }

    //endregion

    //region dialogs

    private void showRenameDialog(String id, String label, String currentTrigger, boolean pv) {
        if (getParentActivity() == null) {
            return;
        }
        Context context = getParentActivity();
        AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.DrGramSmartEditTrigger));
        builder.setMessage(label);

        EditText input = glassInput(context, currentTrigger, false);
        FrameLayout container = new FrameLayout(context);
        container.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 8, 8, 8, 0));
        builder.setView(container);

        builder.setPositiveButton(LocaleController.getString(R.string.DrGramSave), (dialog, which) -> {
            String value = input.getText().toString().trim();
            if (value.length() == 0) {
                value = DrGramSmartReply.defaultTrigger(id);
            }
            DrGramSmartReply.getInstance(currentAccount).setTrigger(id, value);
            TextView chip = chipViews.get(id);
            if (chip != null) {
                chip.setText("»  " + value);
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.show();
    }

    private void showCustomDialog(String existingTrigger, String existingReply) {
        if (getParentActivity() == null) {
            return;
        }
        Context context = getParentActivity();
        boolean editing = existingTrigger != null;
        AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setTitle(LocaleController.getString(editing
                ? R.string.DrGramSmartEditTrigger : R.string.DrGramSmartAddCommand));

        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        EditText triggerInput = glassInput(context, existingTrigger == null ? "" : existingTrigger, false);
        triggerInput.setHint(LocaleController.getString(R.string.DrGramSmartTriggerHint));
        EditText replyInput = glassInput(context, existingReply == null ? "" : existingReply, true);
        replyInput.setHint(LocaleController.getString(R.string.DrGramSmartReplyHint));
        TextView hint = new TextView(context);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        hint.setTextColor(0x99C7BA93);
        hint.setText(LocaleController.getString(R.string.DrGramSmartPlaceholderHint));
        hint.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(8), AndroidUtilities.dp(20), 0);
        box.addView(triggerInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 8, 8, 8, 0));
        box.addView(replyInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 8, 8, 8, 0));
        box.addView(hint);
        builder.setView(box);

        builder.setPositiveButton(LocaleController.getString(R.string.DrGramSave), (dialog, which) -> {
            String trigger = triggerInput.getText().toString().trim();
            String reply = replyInput.getText().toString().trim();
            if (trigger.length() == 0 || reply.length() == 0) {
                android.widget.Toast.makeText(getParentActivity(),
                        LocaleController.getString(R.string.DrGramSmartEmptyTrigger),
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            DrGramSmartReply engine = DrGramSmartReply.getInstance(currentAccount);
            ArrayList<String[]> list = engine.getCustomCommands();
            // remove the old row when editing; reject duplicates otherwise
            for (int i = list.size() - 1; i >= 0; i--) {
                boolean same = list.get(i)[0].equalsIgnoreCase(existingTrigger == null ? "" : existingTrigger);
                if (editing && same) {
                    list.remove(i);
                } else if (list.get(i)[0].equalsIgnoreCase(trigger)) {
                    list.remove(i);
                }
            }
            list.add(new String[]{trigger, reply});
            engine.saveCustomCommands(list);
            rebuildCustomList();
        });
        if (editing) {
            builder.setNeutralButton(LocaleController.getString(R.string.DrGramSmartDeleteCmd), (dialog, which) -> {
                DrGramSmartReply engine = DrGramSmartReply.getInstance(currentAccount);
                ArrayList<String[]> list = engine.getCustomCommands();
                for (int i = list.size() - 1; i >= 0; i--) {
                    if (list.get(i)[0].equalsIgnoreCase(existingTrigger)) {
                        list.remove(i);
                    }
                }
                engine.saveCustomCommands(list);
                rebuildCustomList();
            });
        }
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.show();
    }

    private EditText glassInput(Context context, String text, boolean multiline) {
        EditText input = new EditText(context);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        input.setInputType(multiline
                ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE)
                : InputType.TYPE_CLASS_TEXT);
        input.setMinLines(multiline ? 2 : 1);
        int pad = AndroidUtilities.dp(16);
        input.setPadding(pad, pad / 2, pad, pad / 2);
        input.setText(text == null ? "" : text);
        // Fix 14 lesson: explicit readable colors on the dark luxury dialogs
        input.setTextColor(0xFFF7F0DF);
        input.setHintTextColor(0x99B9AD95);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(12));
        bg.setColor(0x1AFFFFFF);
        bg.setStroke(AndroidUtilities.dp(1), 0x59F4D27A);
        input.setBackground(bg);
        return input;
    }

    //endregion
}
