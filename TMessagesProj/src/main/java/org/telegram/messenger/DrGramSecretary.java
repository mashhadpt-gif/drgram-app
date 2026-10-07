/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Secretary — offline auto-responder.
 * Watches incoming private messages and answers them with a user-defined
 * text after a per-chat cooldown, so the user never leaves a contact
 * hanging while busy. Groups and broadcast channels are ignored by
 * default; bots and service messages are always ignored.
 */
package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;

public class DrGramSecretary {

    private static final long REPLY_COOLDOWN_MS = 5 * 60 * 1000L;

    private static volatile DrGramSecretary[] instances = new DrGramSecretary[UserConfig.MAX_ACCOUNT_COUNT];

    private final int currentAccount;
    private final SharedPreferences preferences;
    private final HashMap<Long, Long> lastReplyTimes = new HashMap<>();

    public static DrGramSecretary getInstance(int account) {
        DrGramSecretary instance = instances[account];
        if (instance == null) {
            synchronized (DrGramSecretary.class) {
                instance = instances[account];
                if (instance == null) {
                    instance = new DrGramSecretary(account);
                    instances[account] = instance;
                }
            }
        }
        return instance;
    }

    private DrGramSecretary(int account) {
        currentAccount = account;
        String prefsName = account == 0 ? "drgram_secretary" : "drgram_secretary_" + account;
        preferences = ApplicationLoader.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
    }

    //region settings

    public boolean isEnabled() {
        return preferences.getBoolean("enabled", false);
    }

    public void setEnabled(boolean value) {
        preferences.edit().putBoolean("enabled", value).apply();
    }

    public String getReplyText() {
        return preferences.getString("reply_text", "");
    }

    public void setReplyText(String text) {
        preferences.edit().putString("reply_text", text == null ? "" : text.trim()).apply();
    }

    public boolean isIncludeGroups() {
        return preferences.getBoolean("include_groups", false);
    }

    public void setIncludeGroups(boolean value) {
        preferences.edit().putBoolean("include_groups", value).apply();
    }

    //endregion

    /**
     * Called from NotificationsController.processNewMessages for every batch
     * of freshly delivered messages. Never throws into the caller.
     */
    public void onNewMessages(ArrayList<MessageObject> messageObjects) {
        if (!isEnabled() || messageObjects == null || messageObjects.isEmpty()) {
            return;
        }
        String replyText = getReplyText();
        if (replyText.length() == 0) {
            return;
        }
        for (int i = 0; i < messageObjects.size(); i++) {
            try {
                considerReply(messageObjects.get(i), replyText);
            } catch (Throwable ignore) {
            }
        }
    }

    private void considerReply(MessageObject messageObject, String replyText) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return;
        }
        if (messageObject.isOutOwner() || messageObject.isOut()) {
            return;
        }
        if (messageObject.messageOwner.action != null) {
            return;
        }
        long dialogId = messageObject.getDialogId();
        if (dialogId == 0) {
            return;
        }

        MessagesController messagesController = MessagesController.getInstance(currentAccount);
        TLRPC.User fromUser = messagesController.getUser(messageObject.getSenderId());
        if (fromUser != null && (fromUser.bot || fromUser.id == UserConfig.getInstance(currentAccount).clientUserId)) {
            return;
        }

        if (dialogId < 0) {
            if (!isIncludeGroups()) {
                return;
            }
            if (ChatObject.isChannelAndNotMegaGroup(-dialogId, currentAccount)) {
                return;
            }
        }

        long now = System.currentTimeMillis();
        Long lastReplyTime = lastReplyTimes.get(dialogId);
        if (lastReplyTime != null && now - lastReplyTime < REPLY_COOLDOWN_MS) {
            return;
        }
        lastReplyTimes.put(dialogId, now);

        SendMessagesHelper.getInstance(currentAccount)
                .sendMessage(SendMessagesHelper.SendMessageParams.of(replyText, dialogId));
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("DrGramSecretary: auto-replied to dialog " + dialogId);
        }
    }
}
