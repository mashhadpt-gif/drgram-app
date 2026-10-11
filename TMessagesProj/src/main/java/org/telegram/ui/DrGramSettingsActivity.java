/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGramSettingsActivity — the DrGram control center.
 * One page that gathers every DrGram capability plus fast shortcuts to the
 * native Telegram screens, organized as cards. Original layout and wording,
 * written for DrGram from scratch.
 */
package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import org.telegram.ui.Components.Switch;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BirthdayController;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.DrGramAutoForward;
import org.telegram.messenger.DrGramFavorites;
import org.telegram.messenger.DrGramHiddenAccounts;
import org.telegram.messenger.DrGramQuickReplies;
import org.telegram.messenger.DrGramThemeInstaller;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Premium.boosts.UserSelectorBottomSheet;

import java.util.ArrayList;
import java.util.List;

public class DrGramSettingsActivity extends BaseFragment {

    private LinearLayout contentView;
    private Switch autoForwardSwitch;
    private Switch hideAdsSwitch;
    private Switch declineSecretSwitch;
    private LinearLayout rulesContainer;
    private LinearLayout repliesContainer;
    private TextView specialContactText;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.DrGramSettingsCenter));
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
                        ? new int[] {0xFF160A0F, 0xFF251017, 0xFF10090D}
                        : new int[] {0xFF0C0A05, 0xFF261D09, 0xFF0C0A05}));
        contentView.setScrollbarFadingEnabled(false);
        android.widget.ScrollView scroller = new android.widget.ScrollView(context);
        scroller.setFillViewport(true);
        scroller.setClipToPadding(false);
        scroller.setVerticalScrollBarEnabled(true);
        scroller.addView(contentView, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fragmentView = scroller;

        addSignatureHero(context, LocaleController.getString(R.string.DrGramSettingsHero));
        buildGeneralSection(context);
        buildThemeSection(context);
        buildChatListSection(context);
        buildChatSection(context);
        buildGiftsSection(context);
        buildAutoForwardSection(context);
        buildQuickRepliesSection(context);
        buildAutoReplySection(context);
        buildSpecialSection(context);
        buildPrivacySection(context);
        buildMediaSection(context);
        buildNotifySection(context);
        buildHotNotificationsSection(context);
        buildAccountsSection(context);
        buildContactsSection(context);
        buildFavTagsSection(context);

        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshBrandSurfaces();
        refreshDynamicRows();
    }


    /**
     * DrGram Signature hero: a deliberate luxury/romance brand moment, not a generic settings header.
     */
    private void addSignatureHero(Context context, String subtitle) {
        boolean love = DrGramThemeInstaller.isLoveMode();
        int accent = DrGramThemeInstaller.accentColor();
        LinearLayout hero = new LinearLayout(context);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER);
        hero.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(30), AndroidUtilities.dp(24), AndroidUtilities.dp(27));
        android.graphics.drawable.GradientDrawable backdrop = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                love
                        ? new int[] {0xFF3A101F, 0xFF1B0A12, 0xFF3A101F}
                        : new int[] {0xFF49330A, 0xFF171105, 0xFF342307});
        backdrop.setCornerRadius(AndroidUtilities.dp(26));
        backdrop.setStroke(AndroidUtilities.dp(1), love ? 0xCCF06A82 : 0xD9F4D27A);
        hero.setBackground(backdrop);
        hero.setElevation(AndroidUtilities.dp(7));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.setMargins(AndroidUtilities.dp(16), AndroidUtilities.dp(18), AndroidUtilities.dp(16), AndroidUtilities.dp(8));
        contentView.addView(hero, hp);

        TextView emblem = new TextView(context);
        emblem.setText(love ? "♡  ✦  ♡" : "✦  ◈  ✦");
        emblem.setTextColor(love ? 0xFFFFA4B5 : 0xFFFFE6A0);
        emblem.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 25);
        emblem.setGravity(Gravity.CENTER);
        hero.addView(emblem, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView brand = new TextView(context);
        brand.setText(love ? "LOVE  MODE" : "GOLD  LUXE");
        brand.setTextColor(love ? 0xFFFFDCE3 : 0xFFFFE8AE);
        brand.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 25);
        brand.setTypeface(AndroidUtilities.bold());
        brand.setGravity(Gravity.CENTER);
        brand.setLetterSpacing(0.10f);
        LinearLayout.LayoutParams bp = LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT);
        bp.topMargin = AndroidUtilities.dp(7);
        hero.addView(brand, bp);

        TextView tagline = new TextView(context);
        tagline.setText(subtitle);
        tagline.setTextColor(love ? 0xFFE8B8C3 : 0xFFE4D2A5);
        tagline.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        tagline.setGravity(Gravity.CENTER);
        tagline.setLineSpacing(AndroidUtilities.dp(3), 1f);
        LinearLayout.LayoutParams tp = LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT);
        tp.topMargin = AndroidUtilities.dp(8);
        hero.addView(tagline, tp);

        TextView state = new TextView(context);
        state.setText(love ? "♥  ROMANTIC • DEEP • ELEGANT" : "✧  CHAMPAGNE • ONYX • TIMELESS");
        state.setTextColor(accent);
        state.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
        state.setGravity(Gravity.CENTER);
        state.setTypeface(AndroidUtilities.bold());
        state.setLetterSpacing(0.06f);
        android.graphics.drawable.GradientDrawable capsule = new android.graphics.drawable.GradientDrawable();
        capsule.setColor(love ? 0x443D0E1A : 0x443D2D0A);
        capsule.setCornerRadius(AndroidUtilities.dp(30));
        capsule.setStroke(AndroidUtilities.dp(1), love ? 0x99F06A82 : 0x99F4D27A);
        state.setBackground(capsule);
        state.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(8), AndroidUtilities.dp(14), AndroidUtilities.dp(8));
        LinearLayout.LayoutParams sp = LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT);
        sp.topMargin = AndroidUtilities.dp(16);
        hero.addView(state, sp);
    }

    //region 1. General

    private void buildGeneralSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramSetGeneral),
                LocaleController.getString(R.string.DrGramSetGeneralHint));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetMyProfile), v -> {
            Bundle args = new Bundle();
            args.putLong("user_id", getUserConfig().clientUserId);
            presentFragment(new ProfileActivity(args));
        }));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetAppearance), v ->
                chooseBrandMode()));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramAiToolsTitle), v ->
                presentFragment(new DrGramAIActivity())));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramQuickLinksTitle), v ->
                presentFragment(new DrGramQuickLinksActivity())));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramOpenToolsTitle), v ->
                presentFragment(new DrGramToolsActivity())));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetLanguage), v ->
                presentFragment(new LanguageSelectActivity())));
    }

    //endregion

    //region 1b. Luxury theme (Fix 8)

    private LinearLayout themePillGold;
    private LinearLayout themePillRed;

    private void buildThemeSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramSetTheme),
                LocaleController.getString(R.string.DrGramSetThemeHint));

        LinearLayout pillsRow = new LinearLayout(context);
        pillsRow.setOrientation(LinearLayout.HORIZONTAL);
        pillsRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(AndroidUtilities.dp(16), AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(6));
        contentView.addView(pillsRow, rowParams);

        themePillGold = makeThemePill(context, LocaleController.getString(R.string.DrGramSetThemeGold), 0xFFF5B90D, false);
        themePillRed = makeThemePill(context, LocaleController.getString(R.string.DrGramSetThemeRed), 0xFFE63B52, true);
        LinearLayout.LayoutParams pillParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pillParams.setMargins(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
        pillsRow.addView(themePillGold, pillParams);
        pillsRow.addView(themePillRed, pillParams);

        themePillGold.setOnClickListener(v -> applyBrandMode(false));
        themePillRed.setOnClickListener(v -> applyBrandMode(true));
        refreshThemePills();
    }

    private void chooseBrandMode() {
        if (getParentActivity() == null) {
            return;
        }
        String[] modes = {
                LocaleController.getString(R.string.DrGramSetThemeGold),
                LocaleController.getString(R.string.DrGramSetThemeRed)
        };
        new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.DrGramSetTheme))
                .setItems(modes, (dialog, which) -> applyBrandMode(which == 1))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private void applyBrandMode(boolean loveMode) {
        boolean applied = DrGramThemeInstaller.setLoveMode(loveMode);
        refreshBrandSurfaces();
        refreshThemePills();
        if (applied && parentLayout != null) {
            // DrGram Fix 13: rebuild every fragment view so the new palette is
            // visible instantly (same approach as Telegram's own ThemeActivity).
            parentLayout.rebuildAllFragmentViews(true, true);
        }
        if (!applied && getParentActivity() != null) {
            android.widget.Toast.makeText(getParentActivity(),
                    LocaleController.getString(R.string.DrGramSetThemeApplyFailed),
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private LinearLayout makeThemePill(Context context, String label, int accent, boolean red) {
        LinearLayout pill = new LinearLayout(context);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER);
        // These are actual mode switches, independent of Android's system theme.
        pill.setClickable(true);
        pill.setFocusable(true);

        android.widget.ImageView dot = new android.widget.ImageView(context);
        android.graphics.drawable.GradientDrawable dotBg = new android.graphics.drawable.GradientDrawable();
        dotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dotBg.setColor(accent);
        dot.setBackgroundDrawable(dotBg);
        pill.addView(dot, LayoutHelper.createLinear(14, 14, 0, 0, 0, 0));

        android.widget.TextView labelView = new android.widget.TextView(context);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        labelView.setTypeface(AndroidUtilities.bold());
        labelView.setTextColor(accent);
        labelView.setText(label);
        labelView.setPadding(AndroidUtilities.dp(8), 0, 0, 0);
        pill.addView(labelView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        return pill;
    }

    private void refreshThemePills() {
        if (themePillGold == null || themePillRed == null) {
            return;
        }
        int cardColor = getThemedColor(Theme.key_dialogBackground);
        boolean loveMode = DrGramThemeInstaller.isLoveMode();
        android.graphics.drawable.GradientDrawable goldBg = new android.graphics.drawable.GradientDrawable();
        goldBg.setCornerRadius(AndroidUtilities.dp(18));
        goldBg.setColor(loveMode ? cardColor : 0xFF33270D);
        goldBg.setStroke(AndroidUtilities.dp(loveMode ? 1 : 2), 0xFFFFC94A);
        themePillGold.setBackgroundDrawable(goldBg);
        themePillGold.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(12), AndroidUtilities.dp(14), AndroidUtilities.dp(12));
        android.graphics.drawable.GradientDrawable redBg = new android.graphics.drawable.GradientDrawable();
        redBg.setCornerRadius(AndroidUtilities.dp(18));
        redBg.setColor(loveMode ? 0xFF2B1017 : cardColor);
        redBg.setStroke(AndroidUtilities.dp(loveMode ? 2 : 1), 0xFFE63B52);
        themePillRed.setBackgroundDrawable(redBg);
        themePillRed.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(12), AndroidUtilities.dp(14), AndroidUtilities.dp(12));
        // Both are selectable; the thicker stroke marks the currently active mode.
        android.widget.TextView goldLabel = (android.widget.TextView) themePillGold.getChildAt(1);
        android.widget.TextView redLabel = (android.widget.TextView) themePillRed.getChildAt(1);
        goldLabel.setTextColor(0xFFFFC94A);
        redLabel.setTextColor(0xFFE63B52);
        themePillGold.setClickable(true);
        themePillRed.setClickable(true);
    }

    //endregion

    //region 2. Chat feel

    private void buildChatListSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramSetChatList),
                LocaleController.getString(R.string.DrGramSetChatListHint));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetChatListOpen), v ->
                presentFragment(new FiltersSetupActivity())));
    }

    private void buildGiftsSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramSetGifts),
                LocaleController.getString(R.string.DrGramSetGiftsHint));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetGiftsOpen), v ->
                UserSelectorBottomSheet.open(0, BirthdayController.getInstance(currentAccount).getState())));
    }

    private void buildChatSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramSetChat),
                LocaleController.getString(R.string.DrGramSetChatHint));

        Switch goldSwitch = new Switch(context);
        goldSwitch.setChecked(SharedConfig.drgramGoldChat, false);
        contentView.addView(makeToggleRow(context, LocaleController.getString(R.string.DrGramSetGoldChats), goldSwitch, () -> {
            SharedConfig.toggleDrgramGoldChat();
            goldSwitch.setChecked(SharedConfig.drgramGoldChat, false);
        }));

        Switch sparksSwitch = new Switch(context);
        sparksSwitch.setChecked(SharedConfig.drgramSendSparks, false);
        contentView.addView(makeToggleRow(context, LocaleController.getString(R.string.DrGramSetSparks), sparksSwitch, () -> {
            SharedConfig.toggleDrgramSendSparks();
            sparksSwitch.setChecked(SharedConfig.drgramSendSparks, false);
        }));

        Switch glassSwitch = new Switch(context);
        glassSwitch.setChecked(SharedConfig.drgramGlassUI, false);
        contentView.addView(makeToggleRow(context, LocaleController.getString(R.string.DrGramSetGlass), glassSwitch, () -> {
            SharedConfig.toggleDrgramGlassUI();
            glassSwitch.setChecked(SharedConfig.drgramGlassUI, false);
        }));

        Switch rainSwitch = new Switch(context);
        rainSwitch.setChecked(SharedConfig.drgramReactionRain, false);
        contentView.addView(makeToggleRow(context, LocaleController.getString(R.string.DrGramSetRain), rainSwitch, () -> {
            SharedConfig.toggleDrgramReactionRain();
            rainSwitch.setChecked(SharedConfig.drgramReactionRain, false);
        }));
    }

    //endregion

    //region 3. Auto relay

    private void buildAutoForwardSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramSetRelay),
                LocaleController.getString(R.string.DrGramSetRelayHint));

        autoForwardSwitch = new Switch(context);
        autoForwardSwitch.setChecked(SharedConfig.drgramAutoForward, false);
        contentView.addView(makeToggleRow(context, LocaleController.getString(R.string.DrGramSetRelayOn), autoForwardSwitch, () -> {
            SharedConfig.toggleDrgramAutoForward();
            autoForwardSwitch.setChecked(SharedConfig.drgramAutoForward, false);
        }));

        rulesContainer = new LinearLayout(context);
        rulesContainer.setOrientation(LinearLayout.VERTICAL);
        contentView.addView(rulesContainer);

        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetRelayAdd), v ->
                drgramStartAddRule()));
    }

    private void drgramStartAddRule() {
        if (getParentActivity() == null) {
            return;
        }
        drgramPickDialog(LocaleController.getString(R.string.DrGramSetRelayPickSource), 0L, dialogId ->
                drgramPickDialog(LocaleController.getString(R.string.DrGramSetRelayPickTarget), dialogId, targetId -> {
                    if (targetId == dialogId) {
                        android.widget.Toast.makeText(getParentActivity(), LocaleController.getString(R.string.DrGramSetRelaySame), android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    drgramAskMedia(media -> {
                        DrGramAutoForward.addRule(currentAccount, dialogId, targetId, media);
                        refreshDynamicRows();
                        android.widget.Toast.makeText(getParentActivity(), LocaleController.getString(R.string.DrGramSetRelayAdded), android.widget.Toast.LENGTH_SHORT).show();
                    });
                }));
    }

    private void drgramAskMedia(java.util.function.Consumer<Boolean> callback) {
        if (getParentActivity() == null) {
            return;
        }
        String[] items = {
                LocaleController.getString(R.string.DrGramSetRelayTextOnly),
                LocaleController.getString(R.string.DrGramSetRelayWithMedia)
        };
        new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.DrGramSetRelayWhat))
                .setItems(items, (dialog, which) -> callback.accept(which == 1))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    //endregion

    //region 4. Quick replies

    private void buildQuickRepliesSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramSetReplies),
                LocaleController.getString(R.string.DrGramSetRepliesHint));
        repliesContainer = new LinearLayout(context);
        repliesContainer.setOrientation(LinearLayout.VERTICAL);
        contentView.addView(repliesContainer);
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetRepliesAdd), v ->
                drgramEditReplyDialog(0, "", "")));
    }

    private void drgramEditReplyDialog(long id, String presetTitle, String presetText) {
        if (getParentActivity() == null) {
            return;
        }
        Context context = getParentActivity();
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        EditText titleField = new EditText(context);
        titleField.setHint(LocaleController.getString(R.string.DrGramSetRepliesName));
        titleField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        titleField.setText(presetTitle);
        // DrGram Fix 14: guaranteed readable text on the dark luxury dialogs —
        // bare EditTexts resolved a dark framework color on the dark background.
        titleField.setTextColor(0xFFF7F0DF);
        titleField.setHintTextColor(0x99B9AD95);
        android.graphics.drawable.GradientDrawable replyFieldBg = new android.graphics.drawable.GradientDrawable();
        replyFieldBg.setCornerRadius(AndroidUtilities.dp(12));
        replyFieldBg.setColor(0x1AFFFFFF);
        replyFieldBg.setStroke(AndroidUtilities.dp(1), 0x59F4D27A);
        titleField.setBackground(replyFieldBg);
        textField.setTextColor(0xFFF7F0DF);
        textField.setHintTextColor(0x99B9AD95);
        textField.setBackground(replyFieldBg);
        EditText textField = new EditText(context);
        textField.setHint(LocaleController.getString(R.string.DrGramSetRepliesText));
        textField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        textField.setMinLines(2);
        textField.setText(presetText);
        box.addView(titleField);
        box.addView(textField);

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(id == 0 ? LocaleController.getString(R.string.DrGramSetRepliesAdd) : LocaleController.getString(R.string.DrGramSetRepliesEdit))
                .setView(box)
                .setPositiveButton(LocaleController.getString(R.string.Save), (dialog, which) -> {
                    String title = titleField.getText().toString().trim();
                    String text = textField.getText().toString().trim();
                    if (text.isEmpty()) {
                        return;
                    }
                    if (id == 0) {
                        DrGramQuickReplies.add(title, text);
                    } else {
                        DrGramQuickReplies.update(id, title, text);
                    }
                    refreshDynamicRows();
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        if (id != 0) {
            builder.setNeutralButton(LocaleController.getString(R.string.Delete), (dialog, which) -> {
                DrGramQuickReplies.remove(id);
                refreshDynamicRows();
            });
        }
        builder.show();
    }

    private void buildAutoReplySection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramOpenToolsTitle),
                LocaleController.getString(R.string.DrGramOpenToolsHint));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSecretaryTitle), v ->
                presentFragment(new DrGramToolsActivity())));
    }

    //endregion

    //region 5. Pinned contact

    private void buildSpecialSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramSetSpecial),
                LocaleController.getString(R.string.DrGramSetSpecialHint));
        specialContactText = new TextView(context);
        specialContactText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        specialContactText.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        LinearLayout row = makeRow(context, LocaleController.getString(R.string.DrGramSetSpecialPick), specialContactText);
        row.setOnClickListener(v -> {
            if (SharedConfig.drgramSpecialContact != 0) {
                Bundle args = new Bundle();
                args.putLong("dialog_id", SharedConfig.drgramSpecialContact);
                presentFragment(new ChatActivity(args));
            } else {
                drgramPickSpecialContact();
            }
        });
        contentView.addView(row);
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetSpecialChange), v ->
                drgramPickSpecialContact()));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetSpecialClear), v -> {
            SharedConfig.setDrgramSpecialContact(0);
            refreshDynamicRows();
        }));
    }

    private void drgramPickSpecialContact() {
        drgramPickDialog(LocaleController.getString(R.string.DrGramSetSpecialPick), 0, dialogId -> {
            SharedConfig.setDrgramSpecialContact(dialogId);
            refreshDynamicRows();
        });
    }

    //endregion

    //region 6. Privacy

    private void buildPrivacySection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramSetPrivacy),
                LocaleController.getString(R.string.DrGramSetPrivacyHint));

        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetPasscode), v ->
                presentFragment(new PasscodeActivity(PasscodeActivity.TYPE_MANAGE_CODE_SETTINGS))));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetPrivacyNative), v ->
                presentFragment(new PrivacySettingsActivity())));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetVault), v ->
                presentFragment(new DrGramVaultActivity())));

        hideAdsSwitch = new Switch(context);
        hideAdsSwitch.setChecked(SharedConfig.drgramHideChannelAds, false);
        contentView.addView(makeToggleRow(context, LocaleController.getString(R.string.DrGramSetNoAds), hideAdsSwitch, () -> {
            SharedConfig.toggleDrgramHideChannelAds();
            hideAdsSwitch.setChecked(SharedConfig.drgramHideChannelAds, false);
        }));

        declineSecretSwitch = new Switch(context);
        declineSecretSwitch.setChecked(SharedConfig.drgramDeclineSecretChats, false);
        contentView.addView(makeToggleRow(context, LocaleController.getString(R.string.DrGramSetNoSecret), declineSecretSwitch, () -> {
            SharedConfig.toggleDrgramDeclineSecretChats();
            declineSecretSwitch.setChecked(SharedConfig.drgramDeclineSecretChats, false);
        }));
    }

    //endregion

    //region 7. Files and media

    private void buildMediaSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramSetMedia),
                LocaleController.getString(R.string.DrGramSetMediaHint));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetDownloads), v -> {
            Bundle args = new Bundle();
            args.putBoolean("drgramStartDownloads", true);
            presentFragment(new DialogsActivity(args));
        }));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramLinkExtractorTitle), v ->
                presentFragment(new DrGramLinkExtractorActivity())));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetStorage), v ->
                presentFragment(new CacheControlActivity())));
    }

    //endregion

    //region 8. Notifications

    private void buildNotifySection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramSetNotify),
                LocaleController.getString(R.string.DrGramSetNotifyHint));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetNotifyOpen), v ->
                presentFragment(new NotificationsSettingsActivity())));
    }

    //endregion

    //region 9. Accounts

    private void buildHotNotificationsSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramSetHotNotifications),
                LocaleController.getString(R.string.DrGramSetHotNotificationsHint));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramFakeNotificationsTitle), v ->
                presentFragment(new DrGramFakeNotificationsActivity())));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramToolsTitle), v ->
                presentFragment(new DrGramToolsActivity())));
    }

    private void buildAccountsSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramSetAccounts),
                LocaleController.getString(R.string.DrGramSetAccountsHint));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetAccountsSwitch), v ->
                drgramShowAccountSwitcher()));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramHiddenAccountsTitle), v ->
                drgramManageHiddenAccounts()));
    }

    private void drgramShowAccountSwitcher() {
        if (getParentActivity() == null) {
            return;
        }
        ArrayList<String> names = new ArrayList<>();
        final ArrayList<Integer> accounts = new ArrayList<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig config = UserConfig.getInstance(a);
            if (config.isClientActivated() && !DrGramHiddenAccounts.isHidden(a)) {
                names.add(ContactsController.formatName(config.getCurrentUser() == null ? "" : config.getCurrentUser().first_name,
                        config.getCurrentUser() == null ? "" : config.getCurrentUser().last_name)
                        + (a == currentAccount ? " ●" : ""));
                accounts.add(a);
            }
        }
        if (accounts.size() < 2) {
            android.widget.Toast.makeText(getParentActivity(), LocaleController.getString(R.string.DrGramSetAccountsOne), android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.DrGramSetAccountsSwitch))
                .setItems(names.toArray(new String[0]), (dialog, which) -> {
                    if (which >= 0 && which < accounts.size()) {
                        int target = accounts.get(which);
                        if (target != currentAccount && getParentActivity() instanceof LaunchActivity) {
                            ((LaunchActivity) getParentActivity()).switchToAccount(target, true);
                        }
                    }
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private void drgramManageHiddenAccounts() {
        if (getParentActivity() == null) {
            return;
        }
        ArrayList<Integer> accounts = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig config = UserConfig.getInstance(a);
            if (!config.isClientActivated() || config.getCurrentUser() == null) {
                continue;
            }
            accounts.add(a);
            String name = ContactsController.formatName(
                    config.getCurrentUser().first_name, config.getCurrentUser().last_name);
            if (a == currentAccount) {
                name += " ●  " + LocaleController.getString(R.string.DrGramHiddenAccountsActive);
            } else {
                name += DrGramHiddenAccounts.isHidden(a)
                        ? " — " + LocaleController.getString(R.string.DrGramHiddenAccountsHidden)
                        : " — " + LocaleController.getString(R.string.DrGramHiddenAccountsVisible);
            }
            names.add(name);
        }
        if (accounts.isEmpty()) {
            return;
        }
        new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.DrGramHiddenAccountsTitle))
                .setItems(names.toArray(new String[0]), (dialog, which) -> {
                    if (which < 0 || which >= accounts.size()) {
                        return;
                    }
                    int target = accounts.get(which);
                    if (target == currentAccount) {
                        android.widget.Toast.makeText(getParentActivity(),
                                LocaleController.getString(R.string.DrGramHiddenAccountsActiveWarning),
                                android.widget.Toast.LENGTH_LONG).show();
                        return;
                    }
                    boolean hide = !DrGramHiddenAccounts.isHidden(target);
                    if (DrGramHiddenAccounts.setHidden(target, hide)) {
                        android.widget.Toast.makeText(getParentActivity(),
                                LocaleController.getString(hide ? R.string.DrGramHiddenAccountsHiddenResult
                                        : R.string.DrGramHiddenAccountsVisibleResult),
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    //endregion

    //region 10. Contacts

    private void buildContactsSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramSetContacts),
                LocaleController.getString(R.string.DrGramSetContactsHint));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramPrivateContactsTitle), v ->
                presentFragment(new DrGramPrivateContactsActivity())));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetContactsOpen), v ->
                presentFragment(new ContactsActivity(new Bundle()))));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetClogOpen), v ->
                presentFragment(new DrGramContactLogActivity())));
    }

    //endregion

    //region 11. Favorites and hashtags

    private void buildFavTagsSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramSetFavTags),
                LocaleController.getString(R.string.DrGramSetFavTagsHint));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetExploreOpen), v ->
                presentFragment(new DrGramExploreActivity())));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetFavOpen), v ->
                presentFragment(new DrGramFavoritesActivity())));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetTagsOpen), v ->
                presentFragment(new DrGramHashtagsActivity())));
    }

    //endregion

    //region shared builders + dynamic refresh

    private void refreshDynamicRows() {
        if (autoForwardSwitch != null) {
            autoForwardSwitch.setChecked(SharedConfig.drgramAutoForward, false);
        }
        if (hideAdsSwitch != null) {
            hideAdsSwitch.setChecked(SharedConfig.drgramHideChannelAds, false);
        }
        if (declineSecretSwitch != null) {
            declineSecretSwitch.setChecked(SharedConfig.drgramDeclineSecretChats, false);
        }
        if (rulesContainer != null) {
            rebuildRules();
        }
        if (repliesContainer != null) {
            rebuildReplies();
        }
        if (specialContactText != null) {
            long id = SharedConfig.drgramSpecialContact;
            specialContactText.setText(id == 0 ? LocaleController.getString(R.string.DrGramSetSpecialNone)
                    : DrGramAutoForward.describe(currentAccount, id));
        }
    }

    private void rebuildRules() {
        rulesContainer.removeAllViews();
        Context context = rulesContainer.getContext();
        ArrayList<DrGramAutoForward.Rule> rules = DrGramAutoForward.getRules(currentAccount);
        for (int a = 0; a < rules.size(); a++) {
            DrGramAutoForward.Rule rule = rules.get(a);
            String label = DrGramAutoForward.describe(currentAccount, rule.source)
                    + "  ⬅  " + DrGramAutoForward.describe(currentAccount, rule.target)
                    + (rule.includeMedia ? "" : "  · " + LocaleController.getString(R.string.DrGramSetRelayTextOnly));
            LinearLayout row = makeActionRow(context, label, v -> {
                if (getParentActivity() == null) {
                    return;
                }
                new AlertDialog.Builder(getParentActivity())
                        .setTitle(LocaleController.getString(R.string.DrGramSetRelayRemove))
                        .setMessage(label)
                        .setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) -> {
                            DrGramAutoForward.removeRule(currentAccount, rule.id);
                            rebuildRules();
                        })
                        .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                        .show();
            });
            rulesContainer.addView(row);
        }
        if (rules.isEmpty()) {
            rulesContainer.addView(makeStaticRow(context, LocaleController.getString(R.string.DrGramSetRelayEmpty)));
        }
    }

    private void rebuildReplies() {
        repliesContainer.removeAllViews();
        Context context = repliesContainer.getContext();
        ArrayList<DrGramQuickReplies.Item> items = DrGramQuickReplies.getAll();
        for (int a = 0; a < items.size(); a++) {
            DrGramQuickReplies.Item item = items.get(a);
            String label = item.title + "  —  " + item.text;
            if (label.length() > 60) {
                label = label.substring(0, 60) + "…";
            }
            final long id = item.id;
            final String title = item.title;
            final String text = item.text;
            repliesContainer.addView(makeActionRow(context, label, v -> drgramEditReplyDialog(id, title, text)));
        }
        if (items.isEmpty()) {
            repliesContainer.addView(makeStaticRow(context, LocaleController.getString(R.string.DrGramSetRepliesEmpty)));
        }
    }

    private void drgramPickDialog(String title, long exclude, java.util.function.Consumer<Long> callback) {
        if (getParentActivity() == null) {
            return;
        }
        ArrayList<TLRPC.Dialog> dialogs = getMessagesController().getAllDialogs();
        ArrayList<String> names = new ArrayList<>();
        final ArrayList<Long> ids = new ArrayList<>();
        for (int a = 0; a < dialogs.size() && ids.size() < 40; a++) {
            TLRPC.Dialog dialog = dialogs.get(a);
            if (dialog == null || dialog.id == exclude || dialog instanceof TLRPC.TL_dialogFolder) {
                continue;
            }
            String name;
            if (dialog.id > 0) {
                TLRPC.User user = getMessagesController().getUser(dialog.id);
                if (user == null || UserObject.isUserSelf(user)) {
                    continue;
                }
                name = ContactsController.formatName(user.first_name, user.last_name);
            } else if (DialogObject.isEncryptedDialog(dialog.id)) {
                name = LocaleController.getString(R.string.DrGramSetRelaySecret);
            } else {
                TLRPC.Chat chat = getMessagesController().getChat(-dialog.id);
                if (chat == null || chat.left || chat.kicked) {
                    continue;
                }
                name = chat.title;
            }
            if (name == null || name.trim().isEmpty()) {
                continue;
            }
            names.add(name);
            ids.add(dialog.id);
        }
        if (ids.isEmpty()) {
            android.widget.Toast.makeText(getParentActivity(), LocaleController.getString(R.string.DrGramSetRelayNoChats), android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(getParentActivity())
                .setTitle(title)
                .setItems(names.toArray(new String[0]), (dialog, which) -> {
                    if (which >= 0 && which < ids.size()) {
                        callback.accept(ids.get(which));
                    }
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private TextView makeStaticRow(Context context, String text) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        view.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        view.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(6), AndroidUtilities.dp(21), AndroidUtilities.dp(6));
        view.setText(text);
        return view;
    }

    private void addHeader(Context context, String title, String hint) {
        // DrGram Fix 8: luxury section header — gold diamond + bold gold title
        TextView header = new TextView(context);
        header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        header.setTypeface(AndroidUtilities.bold());
        header.setTextColor(DrGramThemeInstaller.accentColor());
        header.setText("💎  " + title);
        header.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(22), AndroidUtilities.dp(21), AndroidUtilities.dp(2));
        contentView.addView(header);

        TextView hintView = new TextView(context);
        hintView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        hintView.setTextColor(0xFFB9AD95);
        hintView.setPadding(AndroidUtilities.dp(21), 0, AndroidUtilities.dp(21), AndroidUtilities.dp(8));
        hintView.setText(hint);
        contentView.addView(hintView);
    }

    private void refreshBrandSurfaces() {
        if (contentView == null) {
            return;
        }
        boolean loveMode = DrGramThemeInstaller.isLoveMode();
        contentView.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                loveMode
                        ? new int[] {0xFF160A0F, 0xFF251017, 0xFF10090D}
                        : new int[] {0xFF0C0A05, 0xFF261D09, 0xFF0C0A05}));
        refreshBrandRows(contentView, loveMode);
    }

    private void refreshBrandRows(ViewGroup group, boolean loveMode) {
        int accent = DrGramThemeInstaller.accentColor();
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            if (child instanceof TextView && ((TextView) child).getText().toString().startsWith("💎")) {
                ((TextView) child).setTextColor(accent);
            }
            if (!(child instanceof ViewGroup)) {
                continue;
            }
            ViewGroup row = (ViewGroup) child;
            if (row != contentView && row.getChildCount() == 2 && row.getChildAt(0) instanceof TextView) {
                android.graphics.drawable.GradientDrawable card = new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                        loveMode ? new int[] {0xFF2A1018, 0xFF1C0B11} : new int[] {0xFF3A2C0E, 0xFF211A0A});
                card.setCornerRadius(AndroidUtilities.dp(17));
                card.setStroke(AndroidUtilities.dp(1), loveMode ? 0xCCE63B52 : 0xCCFFC94A);
                row.setBackgroundDrawable(card);
                row.setElevation(AndroidUtilities.dp(2));
                ((TextView) row.getChildAt(0)).setTextColor(0xFFFFF6DF);
            }
            refreshBrandRows(row, loveMode);
        }
    }

    private LinearLayout makeRow(Context context, String title, View valueView) {
        // DrGram Fix 8: luxury card row — rounded dark card, RTL-safe start alignment
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        boolean loveMode = DrGramThemeInstaller.isLoveMode();
        android.graphics.drawable.GradientDrawable card = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                loveMode ? new int[] {0xFF2A1018, 0xFF1C0B11} : new int[] {0xFF3A2C0E, 0xFF211A0A});
        card.setCornerRadius(AndroidUtilities.dp(17));
        card.setStroke(AndroidUtilities.dp(1), loveMode ? 0xCCE63B52 : 0xCCFFC94A);
        row.setBackgroundDrawable(card);
        row.setElevation(AndroidUtilities.dp(2));
        row.setPadding(AndroidUtilities.dp(17), AndroidUtilities.dp(14), AndroidUtilities.dp(17), AndroidUtilities.dp(14));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(AndroidUtilities.dp(16), AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(4));
        row.setLayoutParams(rowParams);

        TextView titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        titleView.setTextColor(0xFFFFF6DF);
        titleView.setText(title);
        titleView.setGravity(Gravity.START);
        row.addView(titleView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        valueParams.setMargins(AndroidUtilities.dp(8), 0, 0, 0);
        valueView.setLayoutParams(valueParams);
        row.addView(valueView);
        return row;
    }

    private LinearLayout makeActionRow(Context context, String title, View.OnClickListener listener) {
        LinearLayout row = makeRow(context, title, new TextView(context));
        row.setOnClickListener(listener);
        return row;
    }

    private LinearLayout makeToggleRow(Context context, String title, Switch toggle, Runnable action) {
        LinearLayout row = makeRow(context, title, toggle);
        row.setOnClickListener(v -> action.run());
        return row;
    }

    //endregion
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
