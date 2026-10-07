/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGramAutoForward — local "smart relay" rules.
 * A rule watches one dialog and re-sends its fresh incoming messages
 * to another dialog of the user's choice. Everything stays on-device;
 * nothing is configured or stored server side. Forwarded messages that
 * arrive are never re-relayed, so rule chains cannot loop.
 */
package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.tgnet.TLRPC;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

public class DrGramAutoForward {

    public static class Rule {
        public long id;
        public long source;
        public long target;
        public boolean includeMedia;
        public boolean enabled = true;
    }

    private static final int MAX_RULES = 20;

    private static final SparseCache CACHE = new SparseCache();

    private static SharedPreferences prefs(int account) {
        return ApplicationLoader.applicationContext.getSharedPreferences("drgram_af_" + account, Context.MODE_PRIVATE);
    }

    //region Rules store

    public static synchronized ArrayList<Rule> getRules(int account) {
        ArrayList<Rule> cached = CACHE.get(account);
        if (cached != null) {
            return cached;
        }
        ArrayList<Rule> rules = new ArrayList<>();
        try {
            String raw = prefs(account).getString("rules", null);
            if (!TextUtils.isEmpty(raw)) {
                JSONArray array = new JSONArray(raw);
                for (int a = 0; a < array.length() && rules.size() < MAX_RULES; a++) {
                    JSONObject object = array.getJSONObject(a);
                    Rule rule = new Rule();
                    rule.id = object.getLong("id");
                    rule.source = object.getLong("source");
                    rule.target = object.getLong("target");
                    rule.includeMedia = object.optBoolean("media", true);
                    rule.enabled = object.optBoolean("enabled", true);
                    rules.add(rule);
                }
            }
        } catch (Exception ignore) {

        }
        CACHE.put(account, rules);
        return rules;
    }

    public static synchronized void saveRules(int account, ArrayList<Rule> rules) {
        try {
            JSONArray array = new JSONArray();
            for (int a = 0; a < rules.size() && a < MAX_RULES; a++) {
                Rule rule = rules.get(a);
                JSONObject object = new JSONObject();
                object.put("id", rule.id);
                object.put("source", rule.source);
                object.put("target", rule.target);
                object.put("media", rule.includeMedia);
                object.put("enabled", rule.enabled);
                array.put(object);
            }
            prefs(account).edit().putString("rules", array.toString()).apply();
            CACHE.put(account, new ArrayList<>(rules));
        } catch (Exception ignore) {

        }
    }

    public static synchronized void addRule(int account, long source, long target, boolean includeMedia) {
        ArrayList<Rule> rules = getRules(account);
        if (rules.size() >= MAX_RULES) {
            return;
        }
        for (int a = 0; a < rules.size(); a++) {
            Rule rule = rules.get(a);
            if (rule.source == source && rule.target == target) {
                return; // already watching that pair
            }
        }
        Rule rule = new Rule();
        rule.id = System.currentTimeMillis();
        rule.source = source;
        rule.target = target;
        rule.includeMedia = includeMedia;
        rules.add(rule);
        saveRules(account, rules);
    }

    public static synchronized void removeRule(int account, long id) {
        ArrayList<Rule> rules = getRules(account);
        for (int a = 0; a < rules.size(); a++) {
            if (rules.get(a).id == id) {
                rules.remove(a);
                saveRules(account, rules);
                return;
            }
        }
    }

    public static String describe(int account, long dialogId) {
        MessagesController messagesController = MessagesController.getInstance(account);
        if (dialogId == 0) {
            return "";
        }
        if (dialogId > 0) {
            TLRPC.User user = messagesController.getUser(dialogId);
            if (user != null) {
                return ContactsController.formatName(user.first_name, user.last_name);
            }
        } else if (DialogObject.isEncryptedDialog(dialogId)) {
            TLRPC.EncryptedChat encryptedChat = messagesController.getEncryptedChat(DialogObject.getEncryptedChatId(dialogId));
            if (encryptedChat != null) {
                TLRPC.User user = messagesController.getUser(encryptedChat.user_id);
                if (user != null) {
                    return ContactsController.formatName(user.first_name, user.last_name);
                }
            }
            return "Secret";
        } else {
            TLRPC.Chat chat = messagesController.getChat(-dialogId);
            if (chat != null) {
                return chat.title;
            }
        }
        return String.valueOf(dialogId);
    }
    //endregion

    //region Relay engine

    public static void onAppReady() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                NotificationCenter.getInstance(a).addObserver(DrGramAutoForward::onNotification, NotificationCenter.didReceiveNewMessages);
            }
        }
    }

    private static void onNotification(int id, int account, Object... args) {
        if (id != NotificationCenter.didReceiveNewMessages || !SharedConfig.drgramAutoForward || args == null || args.length < 2 || !(args[0] instanceof Long) || !(args[1] instanceof ArrayList)) {
            return;
        }
        long dialogId = (Long) args[0];
        if (dialogId == 0 || DialogObject.isEncryptedDialog(dialogId)) {
            return;
        }
        int mode = args.length > 3 && args[3] instanceof Integer ? (Integer) args[3] : 0;
        if (mode != 0) { // skip scheduled / quick replies / welcome message streams
            return;
        }
        ArrayList<Rule> rules = getRules(account);
        if (rules.isEmpty()) {
            return;
        }
        boolean anyEnabled = false;
        for (int a = 0; a < rules.size(); a++) {
            Rule rule = rules.get(a);
            if (rule.enabled && rule.source == dialogId) {
                anyEnabled = true;
                break;
            }
        }
        if (!anyEnabled) {
            return;
        }
        ArrayList<MessageObject> messages = (ArrayList<MessageObject>) args[1];
        for (int a = 0; a < messages.size(); a++) {
            MessageObject messageObject = messages.get(a);
            if (messageObject == null || !relayAllowed(messageObject)) {
                continue;
            }
            for (int b = 0; b < rules.size(); b++) {
                Rule rule = rules.get(b);
                if (!rule.enabled || rule.source != dialogId) {
                    continue;
                }
                if (!rule.includeMedia && hasRealMedia(messageObject)) {
                    continue;
                }
                try {
                    ArrayList<MessageObject> single = new ArrayList<>();
                    single.add(messageObject);
                    SendMessagesHelper.getInstance(account).sendMessage(single, rule.target, false, false, true, 0, 0);
                } catch (Exception ignore) {

                }
                break; // one relay per message: keeps rule chains safe
            }
        }
    }

    private static boolean relayAllowed(MessageObject messageObject) {
        if (messageObject.isOut() || messageObject.isSending() || messageObject.isDateObject) {
            return false;
        }
        if (messageObject.isSponsored() || messageObject.isEphemeral() || messageObject.isForwarded()) {
            return false; // forwarded blocks keep rule loops impossible
        }
        return !TextUtils.isEmpty(messageObject.messageText) || hasRealMedia(messageObject);
    }

    private static boolean hasRealMedia(MessageObject messageObject) {
        TLRPC.Message media = messageObject.messageOwner;
        return media != null && media.media != null && !(media.media instanceof TLRPC.TL_messageMediaEmpty);
    }
    //endregion

    private static final class SparseCache {
        private final android.util.SparseArray<ArrayList<Rule>> map = new android.util.SparseArray<>();

        ArrayList<Rule> get(int account) {
            return map.get(account);
        }

        void put(int account, ArrayList<Rule> rules) {
            map.put(account, rules);
        }
    }
}
