/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Smart Reply (Fix 35) — command-driven auto responder.
 *
 * Two independent modules:
 *  • PV module — anyone who writes a registered command in a private chat
 *    gets an automatic answer (built-in info commands + unlimited custom
 *    commands with placeholder support).
 *  • Group module — ONLY the app owner (outgoing messages) can issue
 *    admin commands inside groups: kick/pin/delete/delete-all/link/mute/
 *    ban/unban/promote, plus the info commands. The command text itself is
 *    swallowed and never posted to the group.
 *
 * Everything is customizable: triggers can be renamed, every command can be
 * toggled, custom commands are fully user-defined.
 */
package org.telegram.messenger;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.text.TextUtils;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONObject;

public class DrGramSmartReply {

    private static volatile DrGramSmartReply[] instances = new DrGramSmartReply[UserConfig.MAX_ACCOUNT_COUNT];

    private final int currentAccount;
    private final SharedPreferences preferences;
    private final HashMap<String, Long> lastAnswerTimes = new HashMap<>();
    private int replyDepth = 0; // recursion guard for our own sends

    public static DrGramSmartReply getInstance(int account) {
        DrGramSmartReply instance = instances[account];
        if (instance == null) {
            synchronized (DrGramSmartReply.class) {
                instance = instances[account];
                if (instance == null) {
                    instance = new DrGramSmartReply(account);
                    instances[account] = instance;
                }
            }
        }
        return instance;
    }

    private DrGramSmartReply(int account) {
        currentAccount = account;
        String prefsName = account == 0 ? "drgram_smartreply" : "drgram_smartreply_" + account;
        preferences = ApplicationLoader.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
    }

    //region command registry

    private static final String[] INFO_IDS = {"time", "date", "id", "username", "battery", "me", "weather", "fal", "help"};
    private static final String[] INFO_DEFAULTS = {
            "ساعت", "تاریخ", "ایدی", "یوزرنیم", "باطری", "من", "آب و هوا", "فال حافظ", "راهنما"
    };

    // DrGram Fix 40: reply-driven commands (owner fires them on any replied post)
    private static final String[] REPLY_IDS = {"save", "sticker", "photo"};
    private static final String[] REPLY_DEFAULTS = {"ذخیره", "استیکر شو", "عکس شو"};

    // DrGram Fix 40: group admin commands with arguments («اسم گروه: نام جدید»)
    private static final String[] GROUP_PREFIX_IDS = {"rename", "desc"};
    private static final String[] GROUP_PREFIX_DEFAULTS = {"اسم گروه", "توضیحات"};

    private static final String[] GROUP_IDS = {"kick", "pin", "del", "delall", "link", "mute", "ban", "unban", "promote"};
    private static final String[] GROUP_DEFAULTS = {
            "remo", "پین شو", "حذف", "حذف همه", "لینک گروه", "سایلنت شو", "بن", "آنبن", "پروموت"
    };

    public static String[] infoIds() {
        return INFO_IDS;
    }

    public static String[] replyIds() {
        return REPLY_IDS;
    }

    public static String[] groupPrefixIds() {
        return GROUP_PREFIX_IDS;
    }

    public static String[] groupIds() {
        return GROUP_IDS;
    }

    public static String defaultTrigger(String id) {
        for (int i = 0; i < INFO_IDS.length; i++) {
            if (INFO_IDS[i].equals(id)) {
                return INFO_DEFAULTS[i];
            }
        }
        for (int i = 0; i < REPLY_IDS.length; i++) {
            if (REPLY_IDS[i].equals(id)) {
                return REPLY_DEFAULTS[i];
            }
        }
        for (int i = 0; i < GROUP_PREFIX_IDS.length; i++) {
            if (GROUP_PREFIX_IDS[i].equals(id)) {
                return GROUP_PREFIX_DEFAULTS[i];
            }
        }
        for (int i = 0; i < GROUP_IDS.length; i++) {
            if (GROUP_IDS[i].equals(id)) {
                return GROUP_DEFAULTS[i];
            }
        }
        return id;
    }

    public String getTrigger(String id) {
        return preferences.getString("trig_" + id, defaultTrigger(id));
    }

    public void setTrigger(String id, String value) {
        String v = value == null ? defaultTrigger(id) : value.trim();
        if (v.length() == 0) {
            v = defaultTrigger(id);
        }
        preferences.edit().putString("trig_" + id, v).apply();
    }

    public boolean isOn(String id) {
        return preferences.getBoolean("on_" + id, true);
    }

    public void setOn(String id, boolean value) {
        preferences.edit().putBoolean("on_" + id, value).apply();
    }

    public boolean isPvEnabled() {
        return preferences.getBoolean("pv_enabled", false);
    }

    public void setPvEnabled(boolean value) {
        preferences.edit().putBoolean("pv_enabled", value).apply();
    }

    public boolean isGroupEnabled() {
        return preferences.getBoolean("group_enabled", false);
    }

    public void setGroupEnabled(boolean value) {
        preferences.edit().putBoolean("group_enabled", value).apply();
    }

    //endregion

    //region custom PV commands

    public ArrayList<String[]> getCustomCommands() {
        ArrayList<String[]> list = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(preferences.getString("custom_pv", "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                list.add(new String[]{obj.optString("t"), obj.optString("r")});
            }
        } catch (Throwable ignore) {
        }
        return list;
    }

    public void saveCustomCommands(ArrayList<String[]> list) {
        try {
            JSONArray array = new JSONArray();
            for (int i = 0; i < list.size(); i++) {
                String[] row = list.get(i);
                if (row[0].trim().length() == 0 || row[1].trim().length() == 0) {
                    continue;
                }
                JSONObject obj = new JSONObject();
                obj.put("t", row[0].trim());
                obj.put("r", row[1].trim());
                array.put(obj);
            }
            preferences.edit().putString("custom_pv", array.toString()).apply();
        } catch (Throwable ignore) {
        }
    }

    //endregion

    //region normalization + matching

    /** Persian-aware normalization: Arabic letters fold, ZWNJ becomes space, spaces collapse. */
    private static String norm(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replace('\u200c', ' ').replace('ي', 'ی').replace('ك', 'ک').trim();
        StringBuilder b = new StringBuilder(s.length());
        boolean lastSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\n' || c == '\t' || c == '\r') {
                if (!lastSpace) {
                    b.append(' ');
                }
                lastSpace = true;
            } else {
                b.append(c);
                lastSpace = false;
            }
        }
        return b.toString().toLowerCase(Locale.US);
    }

    private String matchInfo(String text) {
        for (int i = 0; i < INFO_IDS.length; i++) {
            if (isOn(INFO_IDS[i]) && norm(getTrigger(INFO_IDS[i])).equals(text)) {
                return INFO_IDS[i];
            }
        }
        return null;
    }

    private String matchGroup(String text) {
        for (int i = 0; i < GROUP_IDS.length; i++) {
            if (isOn(GROUP_IDS[i]) && norm(getTrigger(GROUP_IDS[i])).equals(text)) {
                return GROUP_IDS[i];
            }
        }
        return null;
    }

    private String[] matchCustom(String text) {
        ArrayList<String[]> list = getCustomCommands();
        for (int i = 0; i < list.size(); i++) {
            String[] row = list.get(i);
            if (norm(row[0]).equals(text)) {
                return row;
            }
        }
        return null;
    }

    /** DrGram Fix 40: exact-match reply commands (ذخیره / استیکر شو / عکس شو). */
    private String matchReply(String text) {
        for (int i = 0; i < REPLY_IDS.length; i++) {
            if (isOn(REPLY_IDS[i]) && norm(getTrigger(REPLY_IDS[i])).equals(text)) {
                return REPLY_IDS[i];
            }
        }
        return null;
    }

    /** DrGram Fix 40: prefix group commands — returns {id, argument} or null. */
    private String[] matchGroupPrefix(String text) {
        for (int i = 0; i < GROUP_PREFIX_IDS.length; i++) {
            if (!isOn(GROUP_PREFIX_IDS[i])) {
                continue;
            }
            String trigger = norm(getTrigger(GROUP_PREFIX_IDS[i]));
            if (text.startsWith(trigger)) {
                String rest = text.substring(trigger.length()).trim();
                // separators: «:» «:» «،» «-» or just a space
                if (rest.length() > 0 && ":،:-".indexOf(rest.charAt(0)) >= 0) {
                    rest = rest.substring(1).trim();
                }
                return new String[]{GROUP_PREFIX_IDS[i], rest};
            }
        }
        return null;
    }

    //endregion

    //region PV module — incoming auto answers

    /** Called from NotificationsController.processNewMessages — never throws into the caller. */
    public void onNewMessages(ArrayList<MessageObject> messageObjects) {
        if (!isPvEnabled() || messageObjects == null || messageObjects.isEmpty()) {
            return;
        }
        for (int i = 0; i < messageObjects.size(); i++) {
            try {
                considerPv(messageObjects.get(i));
            } catch (Throwable ignore) {
            }
        }
    }

    private void considerPv(MessageObject messageObject) {
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
        if (dialogId <= 0) {
            return; // PV module: private chats only, never groups or channels
        }
        String text = messageObject.messageOwner.message;
        if (TextUtils.isEmpty(text)) {
            return;
        }

        MessagesController messagesController = MessagesController.getInstance(currentAccount);
        TLRPC.User sender = messagesController.getUser(messageObject.getSenderId());
        if (sender != null && (sender.bot || sender.id == UserConfig.getInstance(currentAccount).clientUserId)) {
            return;
        }

        String norm = norm(text);
        String infoId = matchInfo(norm);
        String[] custom = infoId == null ? matchCustom(norm) : null;
        if (infoId == null && custom == null) {
            return;
        }

        // gentle anti-spam: one answer per dialog per 2.5s
        long now = System.currentTimeMillis();
        String key = dialogId + ":" + (infoId != null ? infoId : custom[0]);
        Long last = lastAnswerTimes.get(key);
        if (last != null && now - last < 2500L) {
            return;
        }
        lastAnswerTimes.put(key, now);

        if (infoId != null) {
            if ("weather".equals(infoId)) {
                answerWeather(dialogId); // async, network off the main thread
                return;
            }
            sendAnswer(dialogId, infoAnswer(infoId, sender, false));
        } else {
            sendAnswer(dialogId, applyPlaceholders(custom[1], sender));
        }
    }

    /** DrGram Fix 40: آب و هوا — async open-meteo fetch, answer lands in the chat. */
    private void answerWeather(long dialogId) {
        Utilities.globalQueue.postRunnable(() -> {
            String answer = DrGramWeather.fetchAnswer();
            if (answer == null) {
                answer = "⛔️ دریافت آب و هوا ناموفق بود؛ اتصال اینترنت را بررسی کن";
            }
            String finalAnswer = answer;
            AndroidUtilities.runOnUIThread(() -> sendAnswer(dialogId, finalAnswer));
        });
    }

    /** DrGram Fix 40: dispatches the reply-driven commands (ذخیره / استیکر شو / عکس شو). */
    private boolean runReplyCommand(String replyId, long dialogId, MessageObject reply) {
        if ("save".equals(replyId)) {
            DrGramStickerTools.saveToPv(currentAccount, dialogId, reply);
            return true;
        }
        if ("sticker".equals(replyId)) {
            DrGramStickerTools.toSticker(currentAccount, dialogId, reply);
            return true;
        }
        if ("photo".equals(replyId)) {
            DrGramStickerTools.toPhoto(currentAccount, dialogId, reply);
            return true;
        }
        return false;
    }

    //endregion

    //region group module — outgoing admin commands

    /**
     * Called from SendMessagesHelper.sendMessage BEFORE the message is dispatched.
     * Returns true when the text was a registered command and has been consumed:
     * the command text is never posted to the group.
     */
    public boolean onOutgoingCommand(SendMessagesHelper.SendMessageParams params) {
        if (params == null || params.message == null) {
            return false;
        }
        if (replyDepth > 0) {
            return false; // our own answers pass through sendMessage — never re-trigger
        }
        if (params.scheduleDate != 0) {
            return false; // scheduled texts are never treated as commands
        }
        long dialogId = params.peer;
        if (dialogId >= 0) {
            // DrGram Fix 37: the owner can fire PV commands from their own side
            // too — the typed command is swallowed and the answer is posted here
            return onOutgoingPvCommand(params, dialogId);
        }
        if (!isGroupEnabled()) {
            return false;
        }

        String norm = norm(params.message);
        String infoId = matchInfo(norm);
        String groupId = infoId == null ? matchGroup(norm) : null;
        String replyId = infoId == null && groupId == null ? matchReply(norm) : null;
        String[] prefix = infoId == null && groupId == null && replyId == null ? matchGroupPrefix(norm) : null;
        if (infoId == null && groupId == null && replyId == null && prefix == null) {
            return false;
        }

        long chatId = -dialogId;
        TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(chatId);
        if (chat == null || ChatObject.isChannelAndNotMegaGroup(chatId, currentAccount)) {
            return false; // unknown chat or broadcast channel — behave normally
        }
        boolean mega = ChatObject.isChannel(chat);
        boolean admin = chat.creator || ChatObject.canBlockUsers(chat);
        MessageObject reply = params.replyToMsg;

        if (infoId != null) {
            if ("weather".equals(infoId)) {
                answerWeather(dialogId);
                return true;
            }
            TLRPC.User me = UserConfig.getInstance(currentAccount).getCurrentUser();
            sendAnswer(dialogId, infoAnswer(infoId, me, true));
            return true;
        }

        if (replyId != null) { // DrGram Fix 40: ذخیره / استیکر شو / عکس شو also work in groups
            return runReplyCommand(replyId, dialogId, reply);
        }

        if (prefix != null) { // DrGram Fix 40: اسم گروه / توضیحات — admin only
            if (!admin) {
                notice(dialogId, "🚫 " + LocaleController.getString(R.string.DrGramSmartNeedAdmin));
                return true;
            }
            if (prefix[1] == null || prefix[1].trim().length() == 0) {
                notice(dialogId, "✍️ " + LocaleController.getString(R.string.DrGramSmartNeedArg));
                return true;
            }
            if ("rename".equals(prefix[0])) {
                execRename(chatId, chat, prefix[1].trim());
            } else {
                execDesc(chatId, chat, prefix[1].trim());
            }
            return true;
        }

        switch (groupId) {
            case "link":
                execExportLink(chatId, chat);
                return true;
            case "pin":
            case "del":
                if (reply == null) {
                    notice(dialogId, "↩️ " + LocaleController.getString(R.string.DrGramSmartNeedReply));
                    return true;
                }
                if ("pin".equals(groupId)) {
                    execPin(chat, reply.getId());
                } else {
                    execDelete(chatId, chat, mega, reply.getId());
                }
                return true;
            case "delall":
                if (reply == null) {
                    notice(dialogId, "↩️ " + LocaleController.getString(R.string.DrGramSmartNeedReply));
                    return true;
                }
                if (!mega) {
                    notice(dialogId, "⚠️ " + LocaleController.getString(R.string.DrGramSmartMegaOnly));
                    return true;
                }
                execDeleteParticipantHistory(chatId, chat, reply.getSenderId());
                return true;
            case "kick":
            case "ban":
            case "mute":
            case "unban":
            case "promote":
                if (reply == null) {
                    notice(dialogId, "↩️ " + LocaleController.getString(R.string.DrGramSmartNeedReply));
                    return true;
                }
                if (!admin) {
                    notice(dialogId, "🚫 " + LocaleController.getString(R.string.DrGramSmartNeedAdmin));
                    return true;
                }
                if (!mega && !"kick".equals(groupId)) {
                    notice(dialogId, "⚠️ " + LocaleController.getString(R.string.DrGramSmartMegaOnly));
                    return true;
                }
                long uid = reply.getSenderId();
                if ("kick".equals(groupId)) {
                    execKick(chatId, chat, uid, mega);
                } else if ("ban".equals(groupId)) {
                    execRestrict(chatId, chat, uid, true, 0, "بن شد");
                } else if ("mute".equals(groupId)) {
                    execRestrict(chatId, chat, uid, false, 0, "بی‌صدا شد");
                } else if ("unban".equals(groupId)) {
                    execRestrict(chatId, chat, uid, false, ConnectionsManager.getInstance(currentAccount).getCurrentTime() + 60, "آزاد شد");
                } else {
                    execPromote(chatId, chat, uid);
                }
                return true;
            default:
                return false;
        }
    }

    //endregion

    /**
     * Fix 37: PV commands can also be fired by the owner from their own side of
     * any private chat (or Saved Messages). The command text is consumed and the
     * answer is posted in the same chat — a live way to test and use the bot.
     */
    private boolean onOutgoingPvCommand(SendMessagesHelper.SendMessageParams params, long dialogId) {
        if (!isPvEnabled() || dialogId <= 0) {
            return false;
        }
        String norm = norm(params.message);
        String infoId = matchInfo(norm);
        String replyId = infoId == null ? matchReply(norm) : null;
        String[] custom = infoId == null && replyId == null ? matchCustom(norm) : null;
        if (infoId == null && replyId == null && custom == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        String key = dialogId + ":" + (infoId != null ? infoId : replyId != null ? replyId : custom[0]);
        Long last = lastAnswerTimes.get(key);
        if (last != null && now - last < 2500L) {
            return true; // answered moments ago — swallow anyway to avoid a doubled echo
        }
        lastAnswerTimes.put(key, now);
        if (replyId != null) {
            return runReplyCommand(replyId, dialogId, params.replyToMsg);
        }
        if (infoId != null) {
            if ("weather".equals(infoId)) {
                answerWeather(dialogId);
                return true;
            }
            TLRPC.User me = UserConfig.getInstance(currentAccount).getCurrentUser();
            sendAnswer(dialogId, infoAnswer(infoId, me, false));
            return true;
        }
        TLRPC.User me = UserConfig.getInstance(currentAccount).getCurrentUser();
        sendAnswer(dialogId, applyPlaceholders(custom[1], me));
        return true;
    }

    //region executors

    private void execPin(TLRPC.Chat chat, int messageId) {
        runOnUi(() -> MessagesController.getInstance(currentAccount).pinMessage(chat, null, messageId, false, false, false));
    }

    /** DrGram Fix 40: گروه را تغییر نام بده. */
    private void execRename(long chatId, TLRPC.Chat chat, String title) {
        TLRPC.TL_channels_editTitle req = new TLRPC.TL_channels_editTitle();
        req.channel = MessagesController.getInstance(currentAccount).getInputChannel(chatId);
        req.title = title;
        sendRequest(req, -(long) chatId, "✏️ نام گروه تغییر کرد");
    }

    /** DrGram Fix 40: توضیحات گروه را عوض کن. */
    private void execDesc(long chatId, TLRPC.Chat chat, String about) {
        MessagesController.getInstance(currentAccount).updateChatAbout(chatId, about, null);
        notice(-(long) chatId, "✏️ توضیحات گروه تغییر کرد");
    }

    private void execDelete(long chatId, TLRPC.Chat chat, boolean mega, int messageId) {
        TLObject request;
        if (mega) {
            TLRPC.TL_channels_deleteMessages req = new TLRPC.TL_channels_deleteMessages();
            req.channel = MessagesController.getInstance(currentAccount).getInputChannel(chatId);
            req.id.add(messageId);
            request = req;
        } else {
            TLRPC.TL_messages_deleteMessages req = new TLRPC.TL_messages_deleteMessages();
            req.id.add(messageId);
            req.revoke = true;
            request = req;
        }
        sendRequest(request, chatId, "پیام حذف شد");
    }

    private void execDeleteParticipantHistory(long chatId, TLRPC.Chat chat, long uid) {
        TLRPC.TL_channels_deleteParticipantHistory req = new TLRPC.TL_channels_deleteParticipantHistory();
        req.channel = MessagesController.getInstance(currentAccount).getInputChannel(chatId);
        req.participant = MessagesController.getInstance(currentAccount).getInputPeer(uid);
        sendRequest(req, chatId, "همه پیام‌های او حذف شد");
    }

    /** view=true → full ban; view=false send=true → forever mute; untilDate>0 → temporary (unban/kick). */
    private void execRestrict(long chatId, TLRPC.Chat chat, long uid, boolean banView, int untilDate, String okWord) {
        TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(uid);
        if (user == null) {
            notice(-(long) chatId, "⛔️ " + LocaleController.getString(R.string.DrGramSmartFailed));
            return;
        }
        TLRPC.TL_channels_editBanned req = new TLRPC.TL_channels_editBanned();
        req.channel = MessagesController.getInstance(currentAccount).getInputChannel(chatId);
        req.participant = MessagesController.getInstance(currentAccount).getInputPeer(user);
        TLRPC.TL_chatBannedRights rights = new TLRPC.TL_chatBannedRights();
        // ban/kick: everything blocked · mute: sending blocked forever · unban: all rights lifted
        boolean restrictAll = banView || untilDate == 0;
        rights.view_messages = banView;
        rights.send_messages = restrictAll;
        rights.send_media = restrictAll;
        rights.send_stickers = restrictAll;
        rights.send_gifs = restrictAll;
        rights.send_games = restrictAll;
        rights.send_inline = restrictAll;
        rights.send_polls = restrictAll;
        rights.send_photos = restrictAll;
        rights.send_videos = restrictAll;
        rights.send_roundvideos = restrictAll;
        rights.send_audios = restrictAll;
        rights.send_voices = restrictAll;
        rights.send_docs = restrictAll;
        rights.send_plain = restrictAll;
        rights.embed_links = restrictAll;
        rights.invite_users = banView;
        rights.pin_messages = banView;
        rights.until_date = untilDate;
        req.banned_rights = rights;
        sendRequest(req, -(long) chatId, "✅ کاربر " + okWord);
    }

    private void execKick(long chatId, TLRPC.Chat chat, long uid, boolean mega) {
        if (mega) {
            // kick = temporary ban for one minute
            execRestrict(chatId, chat, uid, true, ConnectionsManager.getInstance(currentAccount).getCurrentTime() + 60, "اخراج شد");
            return;
        }
        TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(uid);
        if (user == null) {
            return;
        }
        TLRPC.TL_messages_deleteChatUser req = new TLRPC.TL_messages_deleteChatUser();
        req.chat_id = chatId;
        req.user_id = MessagesController.getInstance(currentAccount).getInputUser(user);
        sendRequest(req, -(long) chatId, "✅ کاربر اخراج شد");
    }

    private void execPromote(long chatId, TLRPC.Chat chat, long uid) {
        TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(uid);
        if (user == null) {
            return;
        }
        TLRPC.TL_channels_editAdmin req = new TLRPC.TL_channels_editAdmin();
        req.channel = MessagesController.getInstance(currentAccount).getInputChannel(chatId);
        req.user_id = MessagesController.getInstance(currentAccount).getInputUser(user);
        TLRPC.TL_chatAdminRights rights = new TLRPC.TL_chatAdminRights();
        rights.change_info = true;
        rights.delete_messages = true;
        rights.ban_users = true;
        rights.invite_users = true;
        rights.pin_messages = true;
        rights.add_admins = false;
        req.admin_rights = rights;
        sendRequest(req, -(long) chatId, "✅ کاربر ادمین شد");
    }

    private void execExportLink(long chatId, TLRPC.Chat chat) {
        TLRPC.TL_messages_exportChatInvite req = new TLRPC.TL_messages_exportChatInvite();
        req.peer = MessagesController.getInstance(currentAccount).getInputPeer(-(long) chatId);
        final long dialogId = -(long) chatId;
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (error != null) {
                notice(dialogId, "⛔️ " + LocaleController.getString(R.string.DrGramSmartFailed) + ": " + error.text);
                return;
            }
            if (response instanceof TLRPC.TL_chatInviteExported) {
                sendAnswer(dialogId, "🔗 " + ((TLRPC.TL_chatInviteExported) response).link);
            }
        }));
    }

    private void sendRequest(TLObject request, long dialogId, String okText) {
        ConnectionsManager.getInstance(currentAccount).sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (error != null) {
                notice(dialogId, "⛔️ " + LocaleController.getString(R.string.DrGramSmartFailed) + ": " + error.text);
                return;
            }
            if (response instanceof TLRPC.Updates) {
                try {
                    MessagesController.getInstance(currentAccount).processUpdates((TLRPC.Updates) response, false);
                } catch (Throwable ignore) {
                }
            }
            notice(dialogId, okText.startsWith("✅") ? okText : "✅ " + okText);
        }));
    }

    //endregion

    //region answers

    private String infoAnswer(String id, TLRPC.User about, boolean inGroup) {
        if ("time".equals(id)) {
            return "🕐 ساعت: " + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        }
        if ("date".equals(id)) {
            return "📅 " + jalaliNow() + " — " + new SimpleDateFormat("EEEE, d MMMM yyyy", Locale.ENGLISH).format(new Date());
        }
        if ("id".equals(id)) {
            return about == null ? "" : "🆔 ایدی: " + about.id;
        }
        if ("username".equals(id)) {
            if (about == null) {
                return "";
            }
            String u = about.username;
            return u == null || u.length() == 0 ? "🔖 یوزرنیم نداری" : "🔖 یوزرنیم: @" + u;
        }
        if ("fal".equals(id)) { // DrGram Fix 40: offline Hafez oracle
            return DrGramHafez.random();
        }
        if ("battery".equals(id)) {
            return "🔋 باطری گوشی: " + batteryPercent() + "%";
        }
        if ("me".equals(id)) {
            TLRPC.User me = UserConfig.getInstance(currentAccount).getCurrentUser();
            if (me == null) {
                return "";
            }
            String u = me.username;
            return "👤 " + UserObject.getUserName(me) + "\n🆔 " + me.id + (u == null || u.length() == 0 ? "" : "\n🔖 @" + u);
        }
        if ("help".equals(id)) {
            StringBuilder b = new StringBuilder("🤖 ");
            b.append(LocaleController.getString(R.string.DrGramSmartReplyTitle)).append("\n");
            b.append(inGroup ? "— دستورهای گروه —" : "— دستورهای پی‌وی —").append("\n");
            for (String info : INFO_IDS) {
                if (isOn(info)) {
                    b.append("• ").append(getTrigger(info)).append("\n");
                }
            }
            if (inGroup) {
                b.append("\n— دستورهای مدیریتی (با ریپلی) —\n");
                for (String g : GROUP_IDS) {
                    if (isOn(g) && !"link".equals(g)) {
                        b.append("• ").append(getTrigger(g)).append("\n");
                    }
                }
                if (isOn("link")) {
                    b.append("• ").append(getTrigger("link")).append("\n");
                }
                b.append("\n— دستورهای با متن —\n"); // DrGram Fix 40
                for (String p : GROUP_PREFIX_IDS) {
                    if (isOn(p)) {
                        b.append("• ").append(getTrigger(p)).append(": <متن>\n");
                    }
                }
                for (String r : REPLY_IDS) {
                    if (isOn(r)) {
                        b.append("• ").append(getTrigger(r)).append(" (با ریپلی)\n");
                    }
                }
            } else {
                b.append("\n— دستورهای با ریپلی —\n"); // DrGram Fix 40
                for (String r : REPLY_IDS) {
                    if (isOn(r)) {
                        b.append("• ").append(getTrigger(r)).append("\n");
                    }
                }
                ArrayList<String[]> custom = getCustomCommands();
                if (!custom.isEmpty()) {
                    b.append("\n— دستورهای شخصی —\n");
                    for (int i = 0; i < custom.size(); i++) {
                        b.append("• ").append(custom.get(i)[0]).append("\n");
                    }
                }
            }
            return b.toString().trim();
        }
        return "";
    }

    private String jalaliNow() {
        try {
            Calendar c = Calendar.getInstance();
            int[] j = DrGramJalaliDate.gregorianToJalali(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
            String[] months = {"فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور", "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"};
            String[] weekdays = {"دوشنبه", "سه‌شنبه", "چهارشنبه", "پنجشنبه", "جمعه", "شنبه", "یکشنبه"};
            String weekday = weekdays[c.get(Calendar.DAY_OF_WEEK) - 2 < 0 ? 6 : c.get(Calendar.DAY_OF_WEEK) - 2];
            return weekday + " " + DrGramJalaliDate.faDigits(j[2] + " " + months[j[1] - 1] + " " + j[0]);
        } catch (Throwable t) {
            return "";
        }
    }

    private String batteryPercent() {
        try {
            Intent intent = ApplicationLoader.applicationContext.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent == null) {
                return "؟";
            }
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level < 0 || scale <= 0) {
                return "؟";
            }
            return String.valueOf(level * 100 / scale);
        } catch (Throwable t) {
            return "؟";
        }
    }

    /** Custom replies support {نام} {name} {ایدی} {id} {ساعت} {تاریخ}. */
    private String applyPlaceholders(String template, TLRPC.User sender) {
        String out = template;
        String time = new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
        String date = jalaliNow();
        String name = sender == null ? "" : UserObject.getFirstName(sender);
        String id = sender == null ? "" : String.valueOf(sender.id);
        out = out.replace("{نام}", name).replace("{name}", name);
        out = out.replace("{ایدی}", id).replace("{id}", id);
        out = out.replace("{ساعت}", time);
        out = out.replace("{تاریخ}", date);
        return out;
    }

    //endregion

    //region send helpers

    private void sendAnswer(long dialogId, String text) {
        if (TextUtils.isEmpty(text)) {
            return;
        }
        runOnUi(() -> {
            if (replyDepth > 2) {
                return; // belt-and-braces loop guard
            }
            replyDepth++;
            try {
                SendMessagesHelper.getInstance(currentAccount)
                        .sendMessage(SendMessagesHelper.SendMessageParams.of(text, dialogId));
            } finally {
                replyDepth--;
            }
        });
    }

    private void notice(long dialogId, String text) {
        sendAnswer(dialogId, text);
    }

    private void runOnUi(Runnable runnable) {
        AndroidUtilities.runOnUIThread(runnable);
    }

    //endregion
}
