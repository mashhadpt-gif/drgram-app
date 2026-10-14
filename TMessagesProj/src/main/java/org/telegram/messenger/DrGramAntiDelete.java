/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Anti-Delete (Fix 36) — keeps a copy of messages the other side
 * deletes ("anti-revoke"). Incoming texts are cached in a small LRU ring;
 * when the server reports them deleted, the cached copies move into a
 * separate "bag" the user can browse any time. Text content only in v1 —
 * media messages are kept as a labelled stub.
 */
package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class DrGramAntiDelete {

    private static final int CACHE_LIMIT = 600;   // rolling text cache
    private static final int BAG_LIMIT = 300;     // saved deleted messages

    private static volatile DrGramAntiDelete[] instances = new DrGramAntiDelete[UserConfig.MAX_ACCOUNT_COUNT];

    private final int currentAccount;
    private final SharedPreferences preferences;
    private final LinkedHashMap<String, String[]> cache = new LinkedHashMap<String, String[]>() {
        @Override
        protected boolean removeEldest(Map.Entry<String, String[]> eldest) {
            return size() > CACHE_LIMIT;
        }
    };
    private boolean cacheLoaded;
    private boolean bagLoaded;
    private ArrayList<String[]> bag; // {dialogId, senderName, time, text, mediaFlag}
    private long lastPersist;

    public static DrGramAntiDelete getInstance(int account) {
        DrGramAntiDelete instance = instances[account];
        if (instance == null) {
            synchronized (DrGramAntiDelete.class) {
                instance = instances[account];
                if (instance == null) {
                    instance = new DrGramAntiDelete(account);
                    instances[account] = instance;
                }
            }
        }
        return instance;
    }

    private DrGramAntiDelete(int account) {
        currentAccount = account;
        String prefsName = account == 0 ? "drgram_antidelete" : "drgram_antidelete_" + account;
        preferences = ApplicationLoader.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
    }

    //region settings

    public boolean isEnabled() {
        return preferences.getBoolean("enabled", false);
    }

    public void setEnabled(boolean value) {
        preferences.edit().putBoolean("enabled", value).apply();
    }

    public int getSavedCount() {
        loadBag();
        return bag.size();
    }

    public ArrayList<String[]> getBag() {
        loadBag();
        return bag;
    }

    public void clearBag() {
        loadBag();
        bag.clear();
        persistBag();
    }

    public void removeFromBag(int index) {
        loadBag();
        if (index >= 0 && index < bag.size()) {
            bag.remove(index);
            persistBag();
        }
    }

    //endregion

    //region capture

    /** Called from NotificationsController.processNewMessages for every fresh batch. */
    public void onNewMessages(ArrayList<MessageObject> messageObjects) {
        if (!isEnabled() || messageObjects == null || messageObjects.isEmpty()) {
            return;
        }
        loadCache();
        boolean dirty = false;
        for (int i = 0; i < messageObjects.size(); i++) {
            try {
                MessageObject messageObject = messageObjects.get(i);
                if (messageObject == null || messageObject.messageOwner == null
                        || messageObject.isOutOwner() || messageObject.isOut()
                        || messageObject.messageOwner.action != null) {
                    continue;
                }
                long dialogId = messageObject.getDialogId();
                if (dialogId == 0) {
                    continue;
                }
                String text = messageObject.messageOwner.message == null ? "" : messageObject.messageOwner.message;
                boolean media = messageObject.messageOwner.media != null
                        && !(messageObject.messageOwner.media instanceof TLRPC.TL_messageMediaEmpty)
                        && !(messageObject.messageOwner.media instanceof TLRPC.TL_messageMediaWebPage);
                if (text.length() == 0 && !media) {
                    continue;
                }
                if (text.length() > 1200) {
                    text = text.substring(0, 1200);
                }
                String sender = describeSender(messageObject.getSenderId());
                cache.put(dialogId + ":" + messageObject.getId(),
                        new String[]{String.valueOf(dialogId), String.valueOf(messageObject.getId()),
                                sender, text.length() == 0 ? "" : text, media ? "1" : "0"});
                dirty = true;
            } catch (Throwable ignore) {
            }
        }
        if (dirty) {
            persistCacheSoon();
        }
    }

    /**
     * Called from MessagesController.processUpdates right BEFORE the local
     * deletion runs. key = 0 for private/small chats, or the negative chat id.
     */
    public void onDeletedBatch(long key, ArrayList<Integer> ids) {
        if (!isEnabled() || ids == null || ids.isEmpty()) {
            return;
        }
        loadBag();
        boolean dirty = false;
        Iterator<HashMap.Entry<String, String[]>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            HashMap.Entry<String, String[]> entry = it.next();
            String[] record = entry.getValue();
            long dialogId;
            int mid;
            try {
                dialogId = Long.parseLong(record[0]);
                mid = Integer.parseInt(record[1]);
            } catch (Throwable ignore) {
                continue;
            }
            if (key != 0 && key != dialogId && key != -dialogId) {
                continue;
            }
            if (!ids.contains(mid)) {
                continue;
            }
            it.remove();
            String time = new SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.US).format(new Date());
            bag.add(0, new String[]{record[0], record[2], time,
                    record[3].length() == 0 ? "" : record[3], record[4]});
            dirty = true;
        }
        while (bag.size() > BAG_LIMIT) {
            bag.remove(bag.size() - 1);
        }
        if (dirty) {
            persistBag();
        }
    }

    private String describeSender(long senderId) {
        try {
            TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(senderId);
            if (user != null) {
                return UserObject.getUserName(user);
            }
        } catch (Throwable ignore) {
        }
        return String.valueOf(senderId);
    }

    //endregion

    //region persistence (debounced JSON in shared prefs)

    private void loadBag() {
        if (bagLoaded) {
            return;
        }
        bagLoaded = true;
        bag = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(preferences.getString("bag", "[]"));
            for (int i = 0; i < array.length() && i < BAG_LIMIT; i++) {
                JSONObject obj = array.getJSONObject(i);
                bag.add(new String[]{obj.optString("d"), obj.optString("s"),
                        obj.optString("t"), obj.optString("x"), obj.optString("m", "0")});
            }
        } catch (Throwable ignore) {
        }
    }

    private void persistBag() {
        try {
            JSONArray array = new JSONArray();
            for (int i = 0; i < bag.size(); i++) {
                String[] r = bag.get(i);
                JSONObject obj = new JSONObject();
                obj.put("d", r[0]);
                obj.put("s", r[1]);
                obj.put("t", r[2]);
                obj.put("x", r[3]);
                obj.put("m", r[4]);
                array.put(obj);
            }
            preferences.edit().putString("bag", array.toString()).apply();
        } catch (Throwable ignore) {
        }
    }

    private void persistCacheSoon() {
        long now = System.currentTimeMillis();
        if (now - lastPersist < 30_000L) {
            return;
        }
        lastPersist = now;
        try {
            JSONArray array = new JSONArray();
            int count = 0;
            Iterator<HashMap.Entry<String, String[]>> it = cache.entrySet().iterator();
            while (it.hasNext() && count < CACHE_LIMIT) {
                HashMap.Entry<String, String[]> entry = it.next();
                String[] r = entry.getValue();
                JSONObject obj = new JSONObject();
                obj.put("k", entry.getKey());
                obj.put("d", r[0]);
                obj.put("m", r[1]);
                obj.put("s", r[2]);
                obj.put("x", r[3]);
                obj.put("f", r[4]);
                array.put(obj);
                count++;
            }
            preferences.edit().putString("cache", array.toString()).apply();
        } catch (Throwable ignore) {
        }
    }

    private void loadCache() {
        if (cacheLoaded) {
            return;
        }
        cacheLoaded = true;
        try {
            JSONArray array = new JSONArray(preferences.getString("cache", "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                String k = obj.optString("k");
                if (k.length() == 0) {
                    continue;
                }
                cache.put(k, new String[]{obj.optString("d"), obj.optString("m"),
                        obj.optString("s"), obj.optString("x"), obj.optString("f", "0")});
            }
        } catch (Throwable ignore) {
        }
    }
}
