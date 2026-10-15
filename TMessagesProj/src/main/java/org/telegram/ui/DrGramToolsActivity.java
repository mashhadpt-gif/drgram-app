/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Tools — the control hub for DrGram's smart features:
 *   • DrGram Secretary: offline auto-responder
 *   • DrGram Vault: locally hidden chats
 *   • DrGram Online Guard: presence alerts for chosen contacts
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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import org.telegram.ui.Components.Switch;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.DrGramChatTools;
import org.telegram.messenger.DrGramThemeInstaller;
import org.telegram.messenger.DrGramLuxSounds;
import org.telegram.messenger.DrGramVoiceChanger;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.DrGramFavorites;
import org.telegram.messenger.DrGramMedia;
import org.telegram.messenger.DrGramOnlineGuard;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.DrGramSecretary;
import org.telegram.messenger.DrGramVault;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.DrGramLuxDecor;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.Set;

public class DrGramToolsActivity extends BaseFragment {

    private LinearLayout contentView;
    private Switch secretarySwitch;
    private Switch guardSwitch;
    private TextView vaultCountText;
    private LinearLayout guardListContainer;
    private TextView favCountText;
    private Switch collapseSwitch;
    private Switch quickSwitch;
    private Switch storyGradientSwitch;
    private Switch drawSendSwitch;
    // DrGram Fix 38: chime picker state (which theme the pick applies to)
    private boolean drgramPickChimeLove;
    // DrGram Fix 34: the open glass group — every section's rows live in ONE translucent panel
    private LinearLayout glassCard;

    @Override
    public View createView(Context context) {
        // DrGram Fix 29: the tools page must never take the app down — a failure
        // lands on a minimal golden fallback so the crash reporter stays reachable.
        try {
            return drgramCreateViewInternal(context);
        } catch (Throwable t) {
            return drgramFallbackView(context, t);
        }
    }

    // DrGram Fix 38: receive the picked audio file and store it as the
    // love/gold chime — custom choices always beat the bundled ones
    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, android.content.Intent data) {
        if (requestCode != 47381 || resultCode != android.app.Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        try {
            String imported = org.telegram.messenger.DrGramLuxSounds.importSound(getParentActivity(), data.getData());
            if (imported != null) {
                org.telegram.messenger.DrGramLuxSounds.setCustomSound(drgramPickChimeLove, imported);
                android.widget.Toast.makeText(getParentActivity(), LocaleController.getString(R.string.DrGramLuxSoundCustom), android.widget.Toast.LENGTH_SHORT).show();
            } else {
                android.widget.Toast.makeText(getParentActivity(), LocaleController.getString(R.string.DrGramZabanavisFailed), android.widget.Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private View drgramCreateViewInternal(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.DrGramToolsTitle));
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
        // DrGram Fix 34: calmer onyx canvas — the gold glow lives in the accents, not the floor
        contentView.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                DrGramThemeInstaller.isLoveMode()
                        ? new int[] {0xFF0E070B, 0xFF1C0D13, 0xFF0E070B}
                        : new int[] {0xFF0B0A07, 0xFF191409, 0xFF0B0A07}));

        android.widget.ScrollView scroller = new android.widget.ScrollView(context);
        scroller.setFillViewport(true);
        scroller.setClipToPadding(false);
        scroller.setVerticalScrollBarEnabled(true);
        scroller.addView(contentView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fragmentView = scroller;

        try {
            addSignatureHero(context, LocaleController.getString(R.string.DrGramToolsHero)); // DrGram Fix 34: glass hero, no ornament clutter
        } catch (Throwable t) {
            // DrGram Fix 14 (hardening): decoration must never crash the page.
            FileLog.e(t);
        }
        buildSettingsCenterSection(context);
        buildSecretarySection(context);
        buildSmartReplySection(context); // DrGram Fix 35
        buildAntiDeleteSection(context); // DrGram Fix 36
        buildBackupSection(context); // DrGram Fix 36
        buildCardSection(context); // DrGram Fix 36
        buildVaultSection(context);
        buildGuardSection(context);
        buildRadarSection(context);
        buildVipSection(context);
        buildFavoritesSection(context);
        buildChatTweaksSection(context);
        buildIdFinderSection(context);
        buildMediaSection(context);
        buildButlerSection(context);
        buildTagsSection(context);
        buildClogSection(context);

        closeGlassCard(); // DrGram Fix 34: the last section's panel closes before the brand footer
        try {
            contentView.addView(DrGramLuxDecor.signatureFooter(context, DrGramThemeInstaller.isLoveMode())); // DrGram Fix 24
        } catch (Throwable t) {
            FileLog.e(t);
        }

        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshBrandSurfaces();
        refreshDynamicRows();
    }

    //region Settings center

    private void buildSettingsCenterSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramSettingsCenter),
                LocaleController.getString(R.string.DrGramSettingsCenterHint));
        addSectionRow(makeActionRow(context, LocaleController.getString(R.string.DrGramSettingsCenterOpen), v ->
                drgramOpenSettingsCenter())); // DrGram Fix 24: guarded, never crash the tools page
        // DrGram Fix 29: one tap -> the exact crash log, shareable to support
        addSectionRow(makeActionRow(context, LocaleController.getString(R.string.DrGramSendCrash), v ->
                drgramShareLastCrash()));
        // DrGram Fix 32: style gallery — brand palettes + curated collection
        addSectionRow(makeActionRow(context, LocaleController.getString(R.string.DrGramStyleGallery), v ->
                presentFragment(new DrGramStyleGalleryActivity())));
    }

    //endregion

    /**
     * DrGram Fix 24: the control center previously failed to open on some devices
     * (class-verify crash — DrGramSettingsActivity was the only DrGram page with a
     * raw java.util.function.Consumer dependency, an API-24+ class). The dependency
     * is now gone and the open itself is guarded: worst case the user lands in the
     * native Telegram settings instead of losing the section to a crash.
     */
    private void drgramOpenSettingsCenter() {
        try {
            presentFragment(new DrGramSettingsActivity());
        } catch (Throwable t) {
            FileLog.e(t);
            try {
                presentFragment(new SettingsActivity());
            } catch (Throwable ignore) {
                FileLog.e(ignore);
            }
        }
    }

    /**
     * DrGram Fix 29: share the last recorded crash log so a bug report carries
     * the exact stack trace — no adb or logcat needed.
     */
    private void drgramShareLastCrash() {
        try {
            if (getParentActivity() == null) {
                return;
            }
            String text = org.telegram.messenger.DrGramCrashSaver.lastCrashText();
            if (text == null) {
                android.widget.Toast.makeText(getParentActivity(),
                        LocaleController.getString(R.string.DrGramNoCrash),
                        android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(android.content.Intent.EXTRA_TEXT, text);
            getParentActivity().startActivity(android.content.Intent.createChooser(intent,
                    LocaleController.getString(R.string.DrGramSendCrash)));
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private View drgramFallbackView(Context context, Throwable t) {
        org.telegram.messenger.DrGramCrashSaver.recordNonFatal("tools_page", t); // DrGram Fix 29
        try {
            actionBar.setBackButtonImage(R.drawable.ic_ab_back);
            actionBar.setAllowOverlayTitle(true);
            actionBar.setTitle(LocaleController.getString(R.string.DrGramToolsTitle));
            actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
                @Override
                public void onItemClick(int id) {
                    if (id == -1) {
                        finishFragment();
                    }
                }
            });
        } catch (Throwable ignore) {}
        LinearLayout fb = new LinearLayout(context);
        fb.setOrientation(LinearLayout.VERTICAL);
        fb.setGravity(Gravity.CENTER);
        fb.setBackgroundColor(0xFF0C0A05);
        TextView tv = new TextView(context);
        tv.setTextColor(0xFFFFC94A);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        tv.setPadding(AndroidUtilities.dp(28), AndroidUtilities.dp(28), AndroidUtilities.dp(28), AndroidUtilities.dp(28));
        tv.setText(LocaleController.getString(R.string.DrGramCCFallbackText));
        fb.addView(tv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        fragmentView = fb;
        return fb;
    }

    //region Contact radar (DrGram Fix 20)

    private void buildRadarSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramRadarSectionTitle),
                LocaleController.getString(R.string.DrGramRadarSectionHint));
        addSectionRow(makeActionRow(context, LocaleController.getString(R.string.DrGramRadarOpen), v ->
                presentFragment(new DrGramRadarActivity())));
    }

    private void buildVipSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramVipSectionTitle),
                LocaleController.getString(R.string.DrGramVipSectionHint));
        addSectionRow(makeActionRow(context, LocaleController.getString(R.string.DrGramVipOpen), v ->
                presentFragment(new DrGramVipActivity())));
    }

    //endregion

    //region Storage butler (DrGram Fix 20)

    private void buildButlerSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramButlerSectionTitle),
                LocaleController.getString(R.string.DrGramButlerSectionHint));
        addSectionRow(makeActionRow(context, LocaleController.getString(R.string.DrGramButlerOpen), v ->
                presentFragment(new DrGramStorageActivity())));
    }

    //endregion

    //region Secretary

    private void buildSecretarySection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramSecretaryTitle),
                LocaleController.getString(R.string.DrGramSecretaryHint));

        secretarySwitch = new Switch(context);
        secretarySwitch.setChecked(DrGramSecretary.getInstance(currentAccount).isEnabled(), false);
        LinearLayout row = makeRow(context, LocaleController.getString(R.string.DrGramSecretaryEnable), secretarySwitch);
        row.setOnClickListener(v -> {
            boolean newState = !DrGramSecretary.getInstance(currentAccount).isEnabled();
            DrGramSecretary.getInstance(currentAccount).setEnabled(newState);
            secretarySwitch.setChecked(newState, false);
        });
        addSectionRow(row);

        addSectionRow(makeActionRow(context, LocaleController.getString(R.string.DrGramSecretaryText), v -> showSecretaryTextDialog()));
        addSectionRow(makeActionRow(context, LocaleController.getString(R.string.DrGramSecretaryGroups), v -> {
            DrGramSecretary secretary = DrGramSecretary.getInstance(currentAccount);
            secretary.setIncludeGroups(!secretary.isIncludeGroups());
            refreshDynamicRows();
        }, "secretary_groups"));
    }

    private void showSecretaryTextDialog() {
        if (getParentActivity() == null) {
            return;
        }
        Context context = getParentActivity();
        AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.DrGramSecretaryEditTitle));

        EditText input = new EditText(context);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(2);
        int pad = AndroidUtilities.dp(20);
        input.setPadding(pad, pad / 2, pad, pad / 2);
        input.setText(DrGramSecretary.getInstance(currentAccount).getReplyText());
        // DrGram Fix 14: guaranteed readable text on the dark luxury dialogs —
        // bare EditTexts resolved a dark framework color on the dark background.
        input.setTextColor(0xFFF7F0DF);
        input.setHintTextColor(0x99B9AD95);
        android.graphics.drawable.GradientDrawable secretaryFieldBg = new android.graphics.drawable.GradientDrawable();
        secretaryFieldBg.setCornerRadius(AndroidUtilities.dp(12));
        secretaryFieldBg.setColor(0x1AFFFFFF);
        secretaryFieldBg.setStroke(AndroidUtilities.dp(1), 0x59F4D27A);
        input.setBackground(secretaryFieldBg);
        FrameLayout container = new FrameLayout(context);
        container.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 8, 8, 8, 0));
        builder.setView(container);

        builder.setPositiveButton(LocaleController.getString(R.string.DrGramSave), (dialog, which) ->
                DrGramSecretary.getInstance(currentAccount).setReplyText(input.getText().toString()));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.show();
    }

    //endregion

    //region Smart reply (DrGram Fix 35)

    private void buildSmartReplySection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramSmartSectionTitle),
                LocaleController.getString(R.string.DrGramSmartSectionHint));
        addSectionRow(makeActionRow(context, LocaleController.getString(R.string.DrGramSmartReplyOpen), v ->
                presentFragment(new DrGramSmartReplyActivity())));
    }

    //endregion

    //region Fix 36: anti-delete + encrypted backup + profile card

    private void buildAntiDeleteSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramAntiDeleteSectionTitle),
                LocaleController.getString(R.string.DrGramAntiDeleteSectionHint));
        addSectionRow(makeActionRow(context, LocaleController.getString(R.string.DrGramAntiDeleteOpen), v ->
                presentFragment(new DrGramAntiDeleteActivity())));
    }

    private void buildBackupSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramBackupSectionTitle),
                LocaleController.getString(R.string.DrGramBackupSectionHint));
        addSectionRow(makeActionRow(context, LocaleController.getString(R.string.DrGramBackupOpen), v ->
                presentFragment(new DrGramBackupActivity())));
    }

    private void buildCardSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramCardSectionTitle),
                LocaleController.getString(R.string.DrGramCardSectionHint));
        addSectionRow(makeActionRow(context, LocaleController.getString(R.string.DrGramCardOpen), v ->
                presentFragment(new DrGramProfileCardActivity())));
    }

    //endregion

    //region Vault

    private void buildVaultSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramVaultSection),
                LocaleController.getString(R.string.DrGramVaultHint));

        addSectionRow(makeActionRow(context, LocaleController.getString(R.string.DrGramVaultSetCode), v -> showVaultCodeDialog()));
        addSectionRow(makeActionRow(context, LocaleController.getString(R.string.DrGramVaultOpen), v ->
                presentFragment(new DrGramVaultActivity())));
        vaultCountText = new TextView(context);
        vaultCountText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        vaultCountText.setTypeface(AndroidUtilities.bold());
        vaultCountText.setTextColor(drgramLoveMode() ? 0xFFF5A9B8 : 0xFFF4D27A); // DrGram Fix 34: accent stat
        LinearLayout row = makeRow(context, LocaleController.getString(R.string.DrGramVaultCount), vaultCountText);
        addSectionRow(row);
    }

    private void showVaultCodeDialog() {
        if (getParentActivity() == null) {
            return;
        }
        Context context = getParentActivity();
        AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.DrGramVaultCodeTitle));
        builder.setMessage(LocaleController.getString(R.string.DrGramVaultCodeHint));

        EditText input = new EditText(context);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        int pad = AndroidUtilities.dp(20);
        input.setPadding(pad, pad / 2, pad, pad / 2);
        input.setText(DrGramVault.getInstance(currentAccount).getCode());
        // DrGram Fix 14: guaranteed readable text on the dark luxury dialogs —
        // bare EditTexts resolved a dark framework color on the dark background.
        input.setTextColor(0xFFF7F0DF);
        input.setHintTextColor(0x99B9AD95);
        android.graphics.drawable.GradientDrawable vaultFieldBg = new android.graphics.drawable.GradientDrawable();
        vaultFieldBg.setCornerRadius(AndroidUtilities.dp(12));
        vaultFieldBg.setColor(0x1AFFFFFF);
        vaultFieldBg.setStroke(AndroidUtilities.dp(1), 0x59F4D27A);
        input.setBackground(vaultFieldBg);
        FrameLayout container = new FrameLayout(context);
        container.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 8, 8, 8, 0));
        builder.setView(container);

        builder.setPositiveButton(LocaleController.getString(R.string.DrGramSave), (dialog, which) ->
                DrGramVault.getInstance(currentAccount).setCode(input.getText().toString()));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.show();
    }

    //endregion

    //region Online guard

    private void buildGuardSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramGuardTitle),
                LocaleController.getString(R.string.DrGramGuardHint));

        guardSwitch = new Switch(context);
        guardSwitch.setChecked(DrGramOnlineGuard.isEnabled(currentAccount), false);
        LinearLayout row = makeRow(context, LocaleController.getString(R.string.DrGramGuardEnable), guardSwitch);
        row.setOnClickListener(v -> {
            boolean newState = !DrGramOnlineGuard.isEnabled(currentAccount);
            DrGramOnlineGuard.setEnabled(currentAccount, newState);
            guardSwitch.setChecked(newState, false);
        });
        addSectionRow(row);

        addSectionRow(makeActionRow(context, LocaleController.getString(R.string.DrGramGuardAdd), v -> openContactPicker()));

        guardListContainer = new LinearLayout(context);
        guardListContainer.setOrientation(LinearLayout.VERTICAL);
        applyListDividers(guardListContainer); // DrGram Fix 34
        addSectionRow(guardListContainer);
    }

    private void openContactPicker() {
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_USERS_ONLY);
        DialogsActivity fragment = new DialogsActivity(args);
        fragment.setDelegate((f, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            for (int i = 0; i < dids.size(); i++) {
                long dialogId = dids.get(i).dialogId;
                if (dialogId > 0) {
                    DrGramOnlineGuard.watch(currentAccount, dialogId);
                }
            }
            refreshDynamicRows();
            return true;
        });
        presentFragment(fragment);
    }

    private void rebuildGuardList(Context context) {
        guardListContainer.removeAllViews();
        Set<String> watched = DrGramOnlineGuard.getWatchedIds(currentAccount);
        for (String id : watched) {
            long userId;
            try {
                userId = Long.parseLong(id);
            } catch (NumberFormatException ignore) {
                continue;
            }
            TLRPC.User user = getMessagesController().getUser(userId);
            String name = user != null ? UserObject.getUserName(user) :
                    LocaleController.getString(R.string.DrGramGuardUnknownUser);
            TextView cell = new TextView(context);
            cell.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            cell.setTextColor(0xFFF1EADA); // DrGram Fix 34: ivory inside the glass panel
            cell.setPadding(AndroidUtilities.dp(17), AndroidUtilities.dp(12), AndroidUtilities.dp(17), AndroidUtilities.dp(12));
            cell.setText("◇  " + name);
            cell.setOnClickListener(v -> {
                DrGramOnlineGuard.unwatch(currentAccount, userId);
                rebuildGuardList(context);
            });
            guardListContainer.addView(cell);
        }
    }

    //endregion

    //region Favorites

    private void buildFavoritesSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramFavSection),
                LocaleController.getString(R.string.DrGramFavHint));

        addSectionRow(makeActionRow(context, LocaleController.getString(R.string.DrGramFavOpen), v ->
                presentFragment(new DrGramFavoritesActivity())));
        favCountText = new TextView(context);
        favCountText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        favCountText.setTypeface(AndroidUtilities.bold());
        favCountText.setTextColor(drgramLoveMode() ? 0xFFF5A9B8 : 0xFFF4D27A); // DrGram Fix 34: accent stat
        LinearLayout row = makeRow(context, LocaleController.getString(R.string.DrGramFavCount), favCountText);
        addSectionRow(row);
    }

    //endregion

    //region Chat tweaks

    private void buildChatTweaksSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramChatSection),
                LocaleController.getString(R.string.DrGramChatHint));

        collapseSwitch = new Switch(context);
        collapseSwitch.setChecked(DrGramChatTools.getInstance(currentAccount).isCollapseEnabled(), false);
        LinearLayout collapseRow = makeRow(context, LocaleController.getString(R.string.DrGramCollapse), collapseSwitch);
        collapseRow.setOnClickListener(v -> {
            boolean newState = !DrGramChatTools.getInstance(currentAccount).isCollapseEnabled();
            DrGramChatTools.getInstance(currentAccount).setCollapseEnabled(newState);
            collapseSwitch.setChecked(newState, false);
        });
        addSectionRow(collapseRow);

        quickSwitch = new Switch(context);
        quickSwitch.setChecked(DrGramChatTools.getInstance(currentAccount).isQuickChatsEnabled(), false);
        LinearLayout quickRow = makeRow(context, LocaleController.getString(R.string.DrGramQuickChats), quickSwitch);
        quickRow.setOnClickListener(v -> {
            boolean newState = !DrGramChatTools.getInstance(currentAccount).isQuickChatsEnabled();
            DrGramChatTools.getInstance(currentAccount).setQuickChatsEnabled(newState);
            quickSwitch.setChecked(newState, false);
        });
        addSectionRow(quickRow);
    }

    //endregion

    //region ID tracker

    private void buildIdFinderSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramIdSection),
                LocaleController.getString(R.string.DrGramIdHint));
        addSectionRow(makeActionRow(context, LocaleController.getString(R.string.DrGramIdAction), v -> showIdFinderDialog()));
    }

    private void showIdFinderDialog() {
        if (getParentActivity() == null) {
            return;
        }
        Context context = getParentActivity();
        AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.DrGramIdAction));

        EditText input = new EditText(context);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(LocaleController.getString(R.string.DrGramIdInputHint));
        int pad = AndroidUtilities.dp(20);
        input.setPadding(pad, pad / 2, pad, pad / 2);
        FrameLayout container = new FrameLayout(context);
        container.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 8, 8, 8, 0));
        builder.setView(container);

        builder.setPositiveButton(LocaleController.getString(R.string.DrGramIdOpenBtn), (dialog, which) ->
                drgramResolveId(input.getText().toString()));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.show();
    }

    private void drgramResolveId(String raw) {
        String query = raw == null ? "" : raw.trim();
        if (query.isEmpty()) {
            return;
        }
        String[] prefixes = {"https://t.me/", "http://t.me/", "https://telegram.me/", "telegram.me/", "t.me/", "tg://resolve?domain=", "@"};
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String prefix : prefixes) {
                if (query.startsWith(prefix)) {
                    query = query.substring(prefix.length());
                    changed = true;
                    break;
                }
            }
        }
        int slash = query.indexOf('/');
        if (slash > 0) {
            query = query.substring(0, slash);
        }
        query = query.trim();
        if (query.isEmpty()) {
            return;
        }
        if (query.matches("\\d+")) {
            drgramOpenById(Long.parseLong(query));
        } else {
            drgramResolveUsername(query);
        }
    }

    private void drgramOpenById(long id) {
        MessagesController messagesController = getMessagesController();
        if (id > 0) {
            TLRPC.User user = messagesController.getUser(id);
            if (user != null) {
                drgramOpenProfile(user.id);
                return;
            }
            TLRPC.TL_users_getUsers req = new TLRPC.TL_users_getUsers();
            TLRPC.TL_inputUser inputUser = new TLRPC.TL_inputUser();
            inputUser.user_id = id;
            inputUser.access_hash = 0;
            req.id.add(inputUser);
            getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                TLRPC.User found = null;
                if (response instanceof org.telegram.tgnet.Vector) {
                    ArrayList<Object> objects = ((org.telegram.tgnet.Vector) response).objects;
                    for (int i = 0; i < objects.size(); i++) {
                        if (objects.get(i) instanceof TLRPC.User) {
                            TLRPC.User candidate = (TLRPC.User) objects.get(i);
                            if (candidate.id == id) {
                                found = candidate;
                            }
                        }
                    }
                }
                if (found != null) {
                    messagesController.putUser(found, false);
                    drgramOpenProfile(found.id);
                } else {
                    drgramToastNotFound();
                }
            }));
        } else {
            long chatId = id <= -1000000000000L ? -(id + 1000000000000L) : -id;
            TLRPC.Chat chat = messagesController.getChat(chatId);
            if (chat != null) {
                drgramOpenChat(chat.id);
                return;
            }
            TLRPC.TL_channels_getChannels req = new TLRPC.TL_channels_getChannels();
            TLRPC.TL_inputChannel inputChannel = new TLRPC.TL_inputChannel();
            inputChannel.channel_id = chatId;
            inputChannel.access_hash = 0;
            req.id.add(inputChannel);
            getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                TLRPC.Chat found = null;
                if (response instanceof TLRPC.TL_messages_chats) {
                    TLRPC.TL_messages_chats chats = (TLRPC.TL_messages_chats) response;
                    for (int i = 0; i < chats.chats.size(); i++) {
                        TLRPC.Chat candidate = chats.chats.get(i);
                        if (candidate.id == chatId) {
                            found = candidate;
                        }
                    }
                }
                if (found != null) {
                    messagesController.putChat(found, false);
                    drgramOpenChat(found.id);
                } else {
                    drgramToastNotFound();
                }
            }));
        }
    }

    private void drgramResolveUsername(String username) {
        TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = username;
        getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            TLRPC.User user = null;
            TLRPC.Chat chat = null;
            if (response instanceof TLRPC.TL_contacts_resolvedPeer) {
                TLRPC.TL_contacts_resolvedPeer resolvedPeer = (TLRPC.TL_contacts_resolvedPeer) response;
                getMessagesController().putUsers(resolvedPeer.users, false);
                getMessagesController().putChats(resolvedPeer.chats, false);
                long dialogId = org.telegram.messenger.DialogObject.getPeerDialogId(resolvedPeer.peer);
                if (dialogId > 0) {
                    user = getMessagesController().getUser(dialogId);
                } else if (dialogId < 0) {
                    chat = getMessagesController().getChat(-dialogId);
                }
            }
            if (user != null) {
                drgramOpenProfile(user.id);
            } else if (chat != null) {
                drgramOpenChat(chat.id);
            } else {
                drgramToastNotFound();
            }
        }));
    }

    private void drgramOpenProfile(long userId) {
        Bundle args = new Bundle();
        args.putLong("user_id", userId);
        presentFragment(new ProfileActivity(args));
    }

    private void drgramOpenChat(long chatId) {
        Bundle args = new Bundle();
        args.putLong("chat_id", chatId);
        presentFragment(new ChatActivity(args));
    }

    private void drgramToastNotFound() {
        if (getParentActivity() == null) {
            return;
        }
        android.widget.Toast.makeText(getParentActivity(), LocaleController.getString(R.string.DrGramIdNotFound), android.widget.Toast.LENGTH_SHORT).show();
    }

    //endregion

    //region Media & effects

    private void buildMediaSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramMediaSection),
                LocaleController.getString(R.string.DrGramMediaHint));

        addSectionRow(makeActionRow(context, LocaleController.getString(R.string.DrGramVoiceEffect), v -> showVoiceFxDialog(), "media_voice_fx"));

        // DrGram Fix 37: send-side voice changer — the recipient hears it
        addSectionRow(makeActionRow(context, voiceChangeRowTitle(), v -> showVoiceChangeDialog(), "media_voice_change"));

        // DrGram Fix 41: voice change intensity
        addSectionRow(makeActionRow(context, voiceIntensityRowTitle(), v -> {
            if (getParentActivity() == null) {
                return;
            }
            int intensity = DrGramVoiceChanger.getIntensity();
            String[] names = {
                    LocaleController.getString(R.string.DrGramBeautifyLight) + (intensity == 0 ? "  \u2714" : ""),
                    LocaleController.getString(R.string.DrGramBeautifyMedium) + (intensity == 1 ? "  \u2714" : ""),
                    LocaleController.getString(R.string.DrGramBeautifyStrong) + (intensity == 2 ? "  \u2714" : "")
            };
            new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                    .setTitle(LocaleController.getString(R.string.DrGramVoiceIntensity))
                    .setItems(names, (dialog, which) -> {
                        DrGramVoiceChanger.setIntensity(which);
                        refreshDynamicRows();
                    })
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .show();
        }, "media_voice_intensity"));

        // DrGram Fix 38: echo is now a room-character picker (استودیو/غار/صحنه)
        Switch echoSwitch = new Switch(context);
        echoSwitch.setChecked(DrGramVoiceChanger.getEchoStyle() != DrGramVoiceChanger.ECHO_OFF, false);
        LinearLayout echoRow = makeRow(context, LocaleController.getString(R.string.DrGramStudioEcho), echoSwitch);
        echoRow.setTag("media_voice_echo");
        echoRow.setOnClickListener(v -> {
            if (getParentActivity() == null) {
                return;
            }
            String[] echoItems = {
                    LocaleController.getString(R.string.DrGramEchoOff),
                    LocaleController.getString(R.string.DrGramEchoStudio),
                    LocaleController.getString(R.string.DrGramEchoCave),
                    LocaleController.getString(R.string.DrGramEchoStage)
            };
            new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                    .setTitle(LocaleController.getString(R.string.DrGramStudioEcho))
                    .setItems(echoItems, (dialog, which) -> {
                        DrGramVoiceChanger.setEchoStyle(which);
                        echoSwitch.setChecked(which != DrGramVoiceChanger.ECHO_OFF, false);
                    })
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .show();
        });
        addSectionRow(echoRow);

        // DrGram Fix 37: luxury brand notification chime (gold / love)
        Switch luxSoundSwitch = new Switch(context);
        luxSoundSwitch.setChecked(DrGramLuxSounds.isEnabled(), false);
        LinearLayout luxSoundRow = makeRow(context, LocaleController.getString(R.string.DrGramLuxSound), luxSoundSwitch);
        luxSoundRow.setTag("media_lux_sound");
        luxSoundRow.setOnClickListener(v -> {
            boolean newState = !DrGramLuxSounds.isEnabled();
            DrGramLuxSounds.setEnabled(newState);
            luxSoundSwitch.setChecked(newState, false);
        });
        addSectionRow(luxSoundRow);

        // DrGram Fix 40: camera beautify (صاف کردن صورت) for stories & video messages
        Switch beautifySwitch = new Switch(context);
        beautifySwitch.setChecked(org.telegram.messenger.DrGramMedia.isBeautifyEnabled(), false);
        LinearLayout beautifyRow = makeRow(context, LocaleController.getString(R.string.DrGramBeautify), beautifySwitch);
        beautifyRow.setTag("media_beautify");
        beautifyRow.setOnClickListener(v -> {
            if (getParentActivity() == null) {
                return;
            }
            float current = org.telegram.messenger.DrGramMedia.beautifyStrength();
            String[] items = {
                    LocaleController.getString(R.string.DrGramBeautifyLight) + (current <= 0.3f + 0.01f ? "  ✔" : ""),
                    LocaleController.getString(R.string.DrGramBeautifyMedium) + (current > 0.3f + 0.01f && current <= 0.6f + 0.01f ? "  ✔" : ""),
                    LocaleController.getString(R.string.DrGramBeautifyStrong) + (current > 0.6f + 0.01f ? "  ✔" : "")
            };
            new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                    .setTitle(LocaleController.getString(R.string.DrGramBeautify))
                    .setItems(items, (dialog, which) -> {
                        float v2 = which == 0 ? 0.3f : (which == 1 ? 0.55f : 0.85f);
                        org.telegram.messenger.DrGramMedia.setBeautifyEnabled(true);
                        org.telegram.messenger.DrGramMedia.setBeautifyStrength(v2);
                        beautifySwitch.setChecked(true, false);
                    })
                    .setNeutralButton(LocaleController.getString(R.string.DrGramVoiceOff), (dialog, which) -> {
                        org.telegram.messenger.DrGramMedia.setBeautifyEnabled(false);
                        beautifySwitch.setChecked(false, false);
                    })
                    .show();
        });
        addSectionRow(beautifyRow);

        // DrGram Fix 41: nose slimming level
        LinearLayout noseRow = makeActionRow(context, drgramLevelTitle(LocaleController.getString(R.string.DrGramBeautifyNose),
                org.telegram.messenger.DrGramMedia.beautifyNose()), v -> {
            if (getParentActivity() == null) {
                return;
            }
            drgramShowLevelDialog(LocaleController.getString(R.string.DrGramBeautifyNose),
                    org.telegram.messenger.DrGramMedia.beautifyNose(), v2 -> {
                        org.telegram.messenger.DrGramMedia.setBeautifyNose(v2);
                        refreshDynamicRows();
                    });
        }, "media_beautify_nose");
        addSectionRow(noseRow);

        // DrGram Fix 41: teeth whitening level
        LinearLayout teethRow = makeActionRow(context, drgramLevelTitle(LocaleController.getString(R.string.DrGramBeautifyTeeth),
                org.telegram.messenger.DrGramMedia.beautifyTeeth()), v -> {
            if (getParentActivity() == null) {
                return;
            }
            drgramShowLevelDialog(LocaleController.getString(R.string.DrGramBeautifyTeeth),
                    org.telegram.messenger.DrGramMedia.beautifyTeeth(), v2 -> {
                        org.telegram.messenger.DrGramMedia.setBeautifyTeeth(v2);
                        refreshDynamicRows();
                    });
        }, "media_beautify_teeth");
        addSectionRow(teethRow);

        // DrGram Fix 38: pick your own love/gold notification chime
        boolean drgramLoveNow = org.telegram.messenger.DrGramThemeInstaller.isLoveMode();
        String drgramCustom = drgramLoveNow ? DrGramLuxSounds.customLoveSound() : DrGramLuxSounds.customGoldSound();
        String drgramChimeLabel = LocaleController.getString(R.string.DrGramLuxSoundPick) + "\n" + (drgramCustom != null
                ? LocaleController.getString(R.string.DrGramLuxSoundCustom)
                : LocaleController.getString(R.string.DrGramLuxSoundBundled));
        LinearLayout luxPickRow = makeActionRow(context, drgramChimeLabel, v -> {
            if (getParentActivity() == null) {
                return;
            }
            drgramPickChimeLove = drgramLoveNow;
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
            intent.setType("audio/*");
            intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            try {
                getParentActivity().startActivityForResult(
                        android.content.Intent.createChooser(intent, LocaleController.getString(R.string.DrGramLuxSoundPick)), 47381);
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }, "media_lux_sound_pick");
        addSectionRow(luxPickRow);

        storyGradientSwitch = new Switch(context);
        storyGradientSwitch.setChecked(DrGramMedia.isStoryGradientEnabled(), false);
        LinearLayout storyRow = makeRow(context, LocaleController.getString(R.string.DrGramStoryGradient), storyGradientSwitch);
        storyRow.setOnClickListener(v -> {
            boolean newState = !DrGramMedia.isStoryGradientEnabled();
            DrGramMedia.setStoryGradientEnabled(newState);
            storyGradientSwitch.setChecked(newState, false);
        });
        addSectionRow(storyRow);

        // DrGram Fix 45: hands-free story recording
        Switch handsFreeSwitch = new Switch(context);
        handsFreeSwitch.setChecked(DrGramMedia.storyHandsFree(), false);
        LinearLayout handsFreeRow = makeRow(context, LocaleController.getString(R.string.DrGramStoryHandsFree), handsFreeSwitch);
        handsFreeRow.setOnClickListener(v -> {
            boolean newState = !DrGramMedia.storyHandsFree();
            DrGramMedia.setStoryHandsFree(newState);
            handsFreeSwitch.setChecked(newState, false);
        });
        addSectionRow(handsFreeRow);

        // DrGram Fix 45: portrait background blur for photo stories
        Switch portraitSwitch = new Switch(context);
        portraitSwitch.setChecked(DrGramMedia.storyPortraitBg(), false);
        LinearLayout portraitRow = makeRow(context, LocaleController.getString(R.string.DrGramStoryPortrait), portraitSwitch);
        portraitRow.setOnClickListener(v -> {
            boolean newState = !DrGramMedia.storyPortraitBg();
            DrGramMedia.setStoryPortraitBg(newState);
            portraitSwitch.setChecked(newState, false);
        });
        addSectionRow(portraitRow);

        drawSendSwitch = new Switch(context);
        drawSendSwitch.setChecked(DrGramMedia.isDrawSendEnabled(), false);
        LinearLayout drawRow = makeRow(context, LocaleController.getString(R.string.DrGramDrawToggle), drawSendSwitch);
        drawRow.setOnClickListener(v -> {
            boolean newState = !DrGramMedia.isDrawSendEnabled();
            DrGramMedia.setDrawSendEnabled(newState);
            drawSendSwitch.setChecked(newState, false);
        });
        addSectionRow(drawRow);

        addSectionRow(makeActionRow(context, LocaleController.getString(R.string.DrGramDownloadsEntry), v -> openDownloads()));
        addSectionRow(makeActionRow(context, LocaleController.getString(R.string.DrGramLinkExtractorTitle), v ->
                presentFragment(new DrGramLinkExtractorActivity())));

        // DrGram Fix 39: send guard — confirm before sending voice / sticker / GIF
        addHeader(context, LocaleController.getString(R.string.DrGramSgSection),
                LocaleController.getString(R.string.DrGramSgHintSend));

        Switch guardVoiceSwitch = new Switch(context);
        guardVoiceSwitch.setChecked(org.telegram.messenger.DrGramSendGuard.voiceConfirmEnabled(), false);
        LinearLayout guardVoiceRow = makeRow(context, LocaleController.getString(R.string.DrGramSgVoice), guardVoiceSwitch);
        guardVoiceRow.setOnClickListener(v -> {
            boolean newState = !org.telegram.messenger.DrGramSendGuard.voiceConfirmEnabled();
            org.telegram.messenger.DrGramSendGuard.setVoiceConfirmEnabled(newState);
            guardVoiceSwitch.setChecked(newState, false);
        });
        addSectionRow(guardVoiceRow);

        Switch guardStickerSwitch = new Switch(context);
        guardStickerSwitch.setChecked(org.telegram.messenger.DrGramSendGuard.stickerConfirmEnabled(), false);
        LinearLayout guardStickerRow = makeRow(context, LocaleController.getString(R.string.DrGramSgSticker), guardStickerSwitch);
        guardStickerRow.setOnClickListener(v -> {
            boolean newState = !org.telegram.messenger.DrGramSendGuard.stickerConfirmEnabled();
            org.telegram.messenger.DrGramSendGuard.setStickerConfirmEnabled(newState);
            guardStickerSwitch.setChecked(newState, false);
        });
        addSectionRow(guardStickerRow);

        Switch guardGifSwitch = new Switch(context);
        guardGifSwitch.setChecked(org.telegram.messenger.DrGramSendGuard.gifConfirmEnabled(), false);
        LinearLayout guardGifRow = makeRow(context, LocaleController.getString(R.string.DrGramSgGif), guardGifSwitch);
        guardGifRow.setOnClickListener(v -> {
            boolean newState = !org.telegram.messenger.DrGramSendGuard.gifConfirmEnabled();
            org.telegram.messenger.DrGramSendGuard.setGifConfirmEnabled(newState);
            guardGifSwitch.setChecked(newState, false);
        });
        addSectionRow(guardGifRow);
    }

    /** DrGram Fix 37: title with the current send-side voice changer mode. */
    private String voiceChangeRowTitle() {
        String[] names = {
                LocaleController.getString(R.string.DrGramVoiceChangeOff),
                LocaleController.getString(R.string.DrGramVoiceM2F),
                LocaleController.getString(R.string.DrGramVoiceF2M)
        };
        int mode = DrGramVoiceChanger.getMode();
        return LocaleController.getString(R.string.DrGramVoiceChange) + ": "
                + names[Math.max(0, Math.min(names.length - 1, mode))];
    }

    private void showVoiceChangeDialog() {
        if (getParentActivity() == null) {
            return;
        }
        String[] items = {
                LocaleController.getString(R.string.DrGramVoiceChangeOff),
                LocaleController.getString(R.string.DrGramVoiceM2F),
                LocaleController.getString(R.string.DrGramVoiceF2M)
        };
        new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(LocaleController.getString(R.string.DrGramVoiceChangePick))
                .setItems(items, (dialog, which) -> {
                    DrGramVoiceChanger.setMode(which);
                    refreshDynamicRows();
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    /** DrGram Fix 41: intensity of the gender voice change (light/medium/strong). */
    private String voiceIntensityRowTitle() {
        int intensity = DrGramVoiceChanger.getIntensity();
        String[] names = {
                LocaleController.getString(R.string.DrGramBeautifyLight),
                LocaleController.getString(R.string.DrGramBeautifyMedium),
                LocaleController.getString(R.string.DrGramBeautifyStrong)
        };
        return LocaleController.getString(R.string.DrGramVoiceIntensity) + ": "
                + names[Math.max(0, Math.min(names.length - 1, intensity))];
    }

    /** DrGram Fix 41: shared 3-level picker title with a check mark. */
    private String drgramLevelTitle(String base, float value) {
        String[] names = {
                LocaleController.getString(R.string.DrGramBeautifyLight),
                LocaleController.getString(R.string.DrGramBeautifyMedium),
                LocaleController.getString(R.string.DrGramBeautifyStrong)
        };
        int idx = value <= 0.3f + 0.01f ? 0 : (value <= 0.6f + 0.01f ? 1 : 2);
        return base + ": " + names[idx];
    }

    /** DrGram Fix 41: shared 3-level picker (0.3 / 0.55 / 0.85). */
    private void drgramShowLevelDialog(String title, float current, org.telegram.messenger.Utilities.Callback<Float> onPick) {
        String[] items = {
                LocaleController.getString(R.string.DrGramBeautifyLight) + (current <= 0.3f + 0.01f ? "  \u2714" : ""),
                LocaleController.getString(R.string.DrGramBeautifyMedium) + (current > 0.3f + 0.01f && current <= 0.6f + 0.01f ? "  \u2714" : ""),
                LocaleController.getString(R.string.DrGramBeautifyStrong) + (current > 0.6f + 0.01f ? "  \u2714" : "")
        };
        new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(title)
                .setItems(items, (dialog, which) -> onPick.run(which == 0 ? 0.3f : (which == 1 ? 0.55f : 0.85f)))
                .setNeutralButton(LocaleController.getString(R.string.DrGramVoiceOff), (dialog, which) -> onPick.run(0f))
                .show();
    }

    private void showVoiceFxDialog() {
        if (getParentActivity() == null) {
            return;
        }
        String[] items = {
                LocaleController.getString(R.string.DrGramVoiceOff),
                LocaleController.getString(R.string.DrGramVoiceChipmunk),
                LocaleController.getString(R.string.DrGramVoiceDeep),
                LocaleController.getString(R.string.DrGramVoiceBee),
                LocaleController.getString(R.string.DrGramVoiceGiant)
        };
        new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(LocaleController.getString(R.string.DrGramVoicePick))
                .setItems(items, (dialog, which) -> {
                    DrGramMedia.setVoiceFx(which);
                    drgramApplyVoiceFxNow();
                    refreshDynamicRows();
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private void openDownloads() {
        Bundle args = new Bundle();
        args.putBoolean("drgramStartDownloads", true);
        presentFragment(new DialogsActivity(args));
    }

    private void drgramApplyVoiceFxNow() {
        try {
            org.telegram.messenger.MediaController mediaController = org.telegram.messenger.MediaController.getInstance();
            org.telegram.messenger.MessageObject playing = mediaController.getPlayingMessageObject();
            if (playing != null && playing.isVoice()) {
                mediaController.setPlaybackSpeed(false, mediaController.getPlaybackSpeed(false));
            }
        } catch (Exception ignore) {
        }
    }

    //endregion

    //region Hashtags+

    private void buildTagsSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramTagsSection),
                LocaleController.getString(R.string.DrGramTagsHint));
        addSectionRow(makeActionRow(context, LocaleController.getString(R.string.DrGramTagsOpen), v ->
                presentFragment(new DrGramHashtagsActivity())));
    }

    //endregion

    //region Contact changes

    private void buildClogSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramClogSection),
                LocaleController.getString(R.string.DrGramClogHint));
        addSectionRow(makeActionRow(context, LocaleController.getString(R.string.DrGramClogOpen), v ->
                presentFragment(new DrGramContactLogActivity())));
    }

    //endregion


    /**
     * DrGram Signature hero: a deliberate luxury/romance brand moment, not a generic settings header.
     */
    private void addSignatureHero(Context context, String subtitle) {
        boolean love = drgramLoveMode();
        int accent = DrGramThemeInstaller.accentColor();
        LinearLayout hero = new LinearLayout(context);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER);
        hero.setTag("drgram_hero");
        hero.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(26), AndroidUtilities.dp(24), AndroidUtilities.dp(24));
        // DrGram Fix 34: glassmorphism — translucent panel + hairline stroke, flat and calm.
        // The old heavy gold-gradient block + double frame is gone: luxury now reads as light.
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
        brand.setText(love ? "LOVE  MODE" : "GOLD  LUXE");
        brand.setTextColor(love ? 0xFFFFDCE3 : 0xFFFFE8AE);
        brand.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 21);
        brand.setTypeface(AndroidUtilities.bold());
        brand.setGravity(Gravity.CENTER);
        brand.setLetterSpacing(0.14f);
        LinearLayout.LayoutParams bp = LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT);
        bp.topMargin = AndroidUtilities.dp(8);
        hero.addView(brand, bp);

        TextView tagline = new TextView(context);
        tagline.setText(subtitle);
        tagline.setTextColor(love ? 0xA6E8B8C3 : 0xA6E4D2A5);
        tagline.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        tagline.setGravity(Gravity.CENTER);
        tagline.setLineSpacing(AndroidUtilities.dp(3), 1f);
        LinearLayout.LayoutParams tp = LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT);
        tp.topMargin = AndroidUtilities.dp(8);
        hero.addView(tagline, tp);

        TextView state = new TextView(context);
        state.setText(love ? "♥  ROMANTIC • DEEP • ELEGANT" : "✧  CHAMPAGNE • ONYX • TIMELESS");
        state.setTextColor(accent);
        state.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 9);
        state.setGravity(Gravity.CENTER);
        state.setTypeface(AndroidUtilities.bold());
        state.setLetterSpacing(0.10f);
        // DrGram Fix 34: stroke-only capsule — no tinted fill, just a thin jewel line
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

    //region shared row builders

    private void addHeader(Context context, String title, String hint) {
        // DrGram Fix 34: measured typographic rhythm — clear space, small accent label,
        // muted hint, then the whole section sits in ONE translucent glass panel.
        // Section boundaries are unmistakable: 24dp above the title, 10dp below the panel.
        closeGlassCard();

        TextView header = new TextView(context);
        header.setTag("drgram_section_title");
        header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        header.setTypeface(AndroidUtilities.bold());
        header.setLetterSpacing(0.04f);
        header.setTextColor(DrGramThemeInstaller.accentColor());
        header.setText("❖  " + title);
        header.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(24), AndroidUtilities.dp(21), AndroidUtilities.dp(3));
        contentView.addView(header);

        TextView hintView = new TextView(context);
        hintView.setTag("drgram_section_hint");
        hintView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
        hintView.setTextColor(drgramLoveMode() ? 0x8FD8A8B4 : 0x8FC7BA93);
        hintView.setLineSpacing(AndroidUtilities.dp(2), 1f);
        hintView.setPadding(AndroidUtilities.dp(21), 0, AndroidUtilities.dp(21), AndroidUtilities.dp(12));
        hintView.setText(hint);
        contentView.addView(hintView);

        glassCard = new LinearLayout(context);
        glassCard.setOrientation(LinearLayout.VERTICAL);
        glassCard.setTag("drgram_glass_card");
        glassCard.setBackground(glassPanel(20, 0x12FFFFFF, drgramLoveMode() ? 0x30F06A82 : 0x2EF4D27A));
        applyListDividers(glassCard);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14), AndroidUtilities.dp(10));
        contentView.addView(glassCard, cardParams);
    }

    private void refreshBrandSurfaces() {
        if (contentView == null) {
            return;
        }
        boolean loveMode = drgramLoveMode();
        contentView.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                loveMode
                        ? new int[] {0xFF0E070B, 0xFF1C0D13, 0xFF0E070B}
                        : new int[] {0xFF0B0A07, 0xFF191409, 0xFF0B0A07}));
        refreshBrandRows(contentView, loveMode);
    }

    private void refreshBrandRows(ViewGroup group, boolean loveMode) {
        // DrGram Fix 34: only tagged glass surfaces get retinted — rows stay flat
        // and quiet, so a mode switch never resurrects the old bordered cards.
        int accent = DrGramThemeInstaller.accentColor();
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            Object tag = child.getTag();
            if ("drgram_section_title".equals(tag)) {
                ((TextView) child).setTextColor(accent);
            } else if ("drgram_section_hint".equals(tag)) {
                ((TextView) child).setTextColor(loveMode ? 0x8FD8A8B4 : 0x8FC7BA93);
            } else if ("drgram_glass_card".equals(tag)) {
                child.setBackground(glassPanel(20, 0x12FFFFFF, loveMode ? 0x30F06A82 : 0x2EF4D27A));
            } else if ("drgram_hero".equals(tag)) {
                child.setBackground(glassPanel(24, 0x16FFFFFF, loveMode ? 0x4DF06A82 : 0x4DF4D27A));
            }
            if (child instanceof ViewGroup) {
                refreshBrandRows((ViewGroup) child, loveMode);
            }
        }
    }

    private LinearLayout makeRow(Context context, String title, View valueView) {
        // DrGram Fix 34: flat glass row — no per-row borders. The whole section
        // shares one translucent panel with hairline separators (grouped-list feel).
        // DrGram Fix 38: a quiet haptic tick on every Lux row tap (micro-interaction)
        LinearLayout row = new LinearLayout(context) {
            @Override
            public boolean performClick() {
                org.telegram.messenger.DrGramHaptics.tick(this);
                return super.performClick();
            }
        };
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundDrawable(glassRipple());
        row.setPadding(AndroidUtilities.dp(17), AndroidUtilities.dp(13), AndroidUtilities.dp(17), AndroidUtilities.dp(13));
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
        titleView.setTextColor(0xFFF1EADA);
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
        return makeActionRow(context, title, listener, null);
    }

    private LinearLayout makeActionRow(Context context, String title, View.OnClickListener listener, String tag) {
        LinearLayout row = makeRow(context, title, new TextView(context));
        row.setOnClickListener(listener);
        if (tag != null) {
            row.setTag(tag);
        }
        return row;
    }

    //region DrGram Fix 34: glass design system — soft panels, hairline strokes, clear rhythm

    /** Translucent glass panel: flat, quiet, closed by a hairline accent stroke. */
    private android.graphics.drawable.GradientDrawable glassPanel(float radiusDp, int fill, int stroke) {
        android.graphics.drawable.GradientDrawable panel = new android.graphics.drawable.GradientDrawable();
        panel.setCornerRadius(AndroidUtilities.dp(radiusDp));
        panel.setColor(fill);
        panel.setStroke(AndroidUtilities.dp(1), stroke);
        return panel;
    }

    /** Quiet touch feedback — a soft rounded bloom, no gold glare. */
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

    /** Hairline row separators, inset to match the row padding. */
    private void applyListDividers(LinearLayout list) {
        list.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        list.setDividerDrawable(new android.graphics.drawable.InsetDrawable(
                new android.graphics.drawable.ColorDrawable(0x12FFFFFF),
                AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), 0));
    }

    /** Rows live inside the current section's glass group — never loose on the page. */
    private void addSectionRow(View row) {
        if (glassCard != null) {
            glassCard.addView(row);
        } else {
            contentView.addView(row);
        }
    }

    /** Ends the open glass group (the next header or the footer starts fresh). */
    private void closeGlassCard() {
        glassCard = null;
    }

    private boolean drgramLoveMode() {
        return DrGramThemeInstaller.isLoveMode();
    }

    //endregion

    private void refreshDynamicRows() {
        if (secretarySwitch != null) {
            secretarySwitch.setChecked(DrGramSecretary.getInstance(currentAccount).isEnabled(), false);
        }
        if (guardSwitch != null) {
            guardSwitch.setChecked(DrGramOnlineGuard.isEnabled(currentAccount), false);
        }
        if (vaultCountText != null) {
            vaultCountText.setText(String.valueOf(DrGramVault.getInstance(currentAccount).getHiddenCount()));
        }
        if (favCountText != null) {
            favCountText.setText(String.valueOf(DrGramFavorites.getInstance(currentAccount).getCount()));
        }
        View voiceFxRow = contentView.findViewWithTag("media_voice_fx");
        if (voiceFxRow instanceof ViewGroup) {
            TextView titleView = (TextView) ((ViewGroup) voiceFxRow).getChildAt(0);
            String[] names = {
                    LocaleController.getString(R.string.DrGramVoiceOff),
                    LocaleController.getString(R.string.DrGramVoiceChipmunk),
                    LocaleController.getString(R.string.DrGramVoiceDeep),
                    LocaleController.getString(R.string.DrGramVoiceBee),
                    LocaleController.getString(R.string.DrGramVoiceGiant)
            };
            int fx = DrGramMedia.getVoiceFx();
            titleView.setText(LocaleController.getString(R.string.DrGramVoiceEffect) + ": "
                    + names[Math.max(0, Math.min(names.length - 1, fx))]);
        }
        // DrGram Fix 37: keep the voice-changer row title in sync
        View voiceChangeRow = contentView.findViewWithTag("media_voice_change");
        if (voiceChangeRow instanceof ViewGroup) {
            TextView titleView = (TextView) ((ViewGroup) voiceChangeRow).getChildAt(0);
            titleView.setText(voiceChangeRowTitle());
        }
        if (guardListContainer != null) {
            View groupsRow = contentView.findViewWithTag("secretary_groups");
            if (groupsRow instanceof ViewGroup) {
                TextView titleView = (TextView) ((ViewGroup) groupsRow).getChildAt(0);
                String base = LocaleController.getString(R.string.DrGramSecretaryGroups);
                titleView.setText(base + ": " + (DrGramSecretary.getInstance(currentAccount).isIncludeGroups() ? "ON" : "OFF"));
            }
            rebuildGuardList(guardListContainer.getContext());
        }
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
        // DrGram Fix 24: inset handling is cosmetic — never fatal.
        try {
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
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }
}
