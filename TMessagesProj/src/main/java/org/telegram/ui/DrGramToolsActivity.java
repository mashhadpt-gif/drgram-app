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

    @Override
    public View createView(Context context) {
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
        contentView.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                DrGramThemeInstaller.isLoveMode()
                        ? new int[] {0xFF160A0F, 0xFF251017, 0xFF10090D}
                        : new int[] {0xFF100E08, 0xFF211A0A, 0xFF100E08}));

        android.widget.ScrollView scroller = new android.widget.ScrollView(context);
        scroller.setFillViewport(true);
        scroller.setClipToPadding(false);
        scroller.setVerticalScrollBarEnabled(true);
        scroller.addView(contentView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fragmentView = scroller;

        buildSettingsCenterSection(context);
        buildSecretarySection(context);
        buildVaultSection(context);
        buildGuardSection(context);
        buildFavoritesSection(context);
        buildChatTweaksSection(context);
        buildIdFinderSection(context);
        buildMediaSection(context);
        buildTagsSection(context);
        buildClogSection(context);

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
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSettingsCenterOpen), v ->
                presentFragment(new DrGramSettingsActivity())));
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
        contentView.addView(row);

        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSecretaryText), v -> showSecretaryTextDialog()));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSecretaryGroups), v -> {
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
        FrameLayout container = new FrameLayout(context);
        container.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 8, 8, 8, 0));
        builder.setView(container);

        builder.setPositiveButton(LocaleController.getString(R.string.DrGramSave), (dialog, which) ->
                DrGramSecretary.getInstance(currentAccount).setReplyText(input.getText().toString()));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.show();
    }

    //endregion

    //region Vault

    private void buildVaultSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramVaultSection),
                LocaleController.getString(R.string.DrGramVaultHint));

        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramVaultSetCode), v -> showVaultCodeDialog()));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramVaultOpen), v ->
                presentFragment(new DrGramVaultActivity())));
        vaultCountText = new TextView(context);
        vaultCountText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        vaultCountText.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        LinearLayout row = makeRow(context, LocaleController.getString(R.string.DrGramVaultCount), vaultCountText);
        contentView.addView(row);
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
        contentView.addView(row);

        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramGuardAdd), v -> openContactPicker()));

        guardListContainer = new LinearLayout(context);
        guardListContainer.setOrientation(LinearLayout.VERTICAL);
        contentView.addView(guardListContainer);
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
            cell.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            cell.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            cell.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(11), AndroidUtilities.dp(21), AndroidUtilities.dp(11));
            cell.setText(name);
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

        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramFavOpen), v ->
                presentFragment(new DrGramFavoritesActivity())));
        favCountText = new TextView(context);
        favCountText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        favCountText.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        LinearLayout row = makeRow(context, LocaleController.getString(R.string.DrGramFavCount), favCountText);
        contentView.addView(row);
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
        contentView.addView(collapseRow);

        quickSwitch = new Switch(context);
        quickSwitch.setChecked(DrGramChatTools.getInstance(currentAccount).isQuickChatsEnabled(), false);
        LinearLayout quickRow = makeRow(context, LocaleController.getString(R.string.DrGramQuickChats), quickSwitch);
        quickRow.setOnClickListener(v -> {
            boolean newState = !DrGramChatTools.getInstance(currentAccount).isQuickChatsEnabled();
            DrGramChatTools.getInstance(currentAccount).setQuickChatsEnabled(newState);
            quickSwitch.setChecked(newState, false);
        });
        contentView.addView(quickRow);
    }

    //endregion

    //region ID tracker

    private void buildIdFinderSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramIdSection),
                LocaleController.getString(R.string.DrGramIdHint));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramIdAction), v -> showIdFinderDialog()));
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

        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramVoiceEffect), v -> showVoiceFxDialog(), "media_voice_fx"));

        storyGradientSwitch = new Switch(context);
        storyGradientSwitch.setChecked(DrGramMedia.isStoryGradientEnabled(), false);
        LinearLayout storyRow = makeRow(context, LocaleController.getString(R.string.DrGramStoryGradient), storyGradientSwitch);
        storyRow.setOnClickListener(v -> {
            boolean newState = !DrGramMedia.isStoryGradientEnabled();
            DrGramMedia.setStoryGradientEnabled(newState);
            storyGradientSwitch.setChecked(newState, false);
        });
        contentView.addView(storyRow);

        drawSendSwitch = new Switch(context);
        drawSendSwitch.setChecked(DrGramMedia.isDrawSendEnabled(), false);
        LinearLayout drawRow = makeRow(context, LocaleController.getString(R.string.DrGramDrawToggle), drawSendSwitch);
        drawRow.setOnClickListener(v -> {
            boolean newState = !DrGramMedia.isDrawSendEnabled();
            DrGramMedia.setDrawSendEnabled(newState);
            drawSendSwitch.setChecked(newState, false);
        });
        contentView.addView(drawRow);

        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramDownloadsEntry), v -> openDownloads()));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramLinkExtractorTitle), v ->
                presentFragment(new DrGramLinkExtractorActivity())));
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
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramTagsOpen), v ->
                presentFragment(new DrGramHashtagsActivity())));
    }

    //endregion

    //region Contact changes

    private void buildClogSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramClogSection),
                LocaleController.getString(R.string.DrGramClogHint));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramClogOpen), v ->
                presentFragment(new DrGramContactLogActivity())));
    }

    //endregion

    //region shared row builders

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
                        : new int[] {0xFF100E08, 0xFF211A0A, 0xFF100E08}));
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
                        loveMode ? new int[] {0xFF2A1018, 0xFF1C0B11} : new int[] {0xFF2C220B, 0xFF1B1608});
                card.setCornerRadius(AndroidUtilities.dp(17));
                card.setStroke(AndroidUtilities.dp(1), loveMode ? 0x99E63B52 : 0x99F5B90D);
                row.setBackgroundDrawable(card);
                row.setElevation(AndroidUtilities.dp(2));
                ((TextView) row.getChildAt(0)).setTextColor(0xFFF7F0DF);
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
                loveMode ? new int[] {0xFF2A1018, 0xFF1C0B11} : new int[] {0xFF2C220B, 0xFF1B1608});
        card.setCornerRadius(AndroidUtilities.dp(17));
        card.setStroke(AndroidUtilities.dp(1), loveMode ? 0x99E63B52 : 0x99F5B90D);
        row.setBackgroundDrawable(card);
        row.setElevation(AndroidUtilities.dp(2));
        row.setPadding(AndroidUtilities.dp(17), AndroidUtilities.dp(14), AndroidUtilities.dp(17), AndroidUtilities.dp(14));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(AndroidUtilities.dp(16), AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(4));
        row.setLayoutParams(rowParams);

        TextView titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        titleView.setTextColor(0xFFF7F0DF);
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
