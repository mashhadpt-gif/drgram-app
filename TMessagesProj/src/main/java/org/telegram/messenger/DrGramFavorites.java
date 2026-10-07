/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Favorites — a private, device-local list of starred messages.
 * Stores only lightweight references (dialog id, message id, a title and a
 * short text snippet) in the app's own storage so the user can jump back to
 * any saved message later. Nothing is uploaded anywhere.
 */
package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

public class DrGramFavorites {

    public static class Entry {
        public long dialogId;
        public int msgId;
        public String title;
        public String snippet;
        public long date;
    }

    private static final int MAX_ENTRIES = 200;

    private static volatile DrGramFavorites[] instances = new DrGramFavorites[UserConfig.MAX_ACCOUNT_COUNT];

    private final SharedPreferences preferences;
    private final ArrayList<Entry> entries = new ArrayList<>();

    public static DrGramFavorites getInstance(int account) {
        DrGramFavorites instance = instances[account];
        if (instance == null) {
            synchronized (DrGramFavorites.class) {
                instance = instances[account];
                if (instance == null) {
                    instance = new DrGramFavorites(account);
                    instances[account] = instance;
                }
            }
        }
        return instance;
    }

    private DrGramFavorites(int account) {
        preferences = ApplicationLoader.applicationContext.getSharedPreferences("drgram_favorites_" + account, Context.MODE_PRIVATE);
        load();
    }

    private void load() {
        entries.clear();
        try {
            JSONArray array = new JSONArray(preferences.getString("entries", "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                Entry entry = new Entry();
                entry.dialogId = object.getLong("d");
                entry.msgId = object.getInt("m");
                entry.title = object.optString("t");
                entry.snippet = object.optString("s");
                entry.date = object.getLong("dt");
                entries.add(entry);
            }
        } catch (Exception ignore) {
        }
    }

    private void save() {
        try {
            JSONArray array = new JSONArray();
            for (int i = 0; i < entries.size(); i++) {
                Entry entry = entries.get(i);
                JSONObject object = new JSONObject();
                object.put("d", entry.dialogId);
                object.put("m", entry.msgId);
                object.put("t", entry.title == null ? "" : entry.title);
                object.put("s", entry.snippet == null ? "" : entry.snippet);
                object.put("dt", entry.date);
                array.put(object);
            }
            preferences.edit().putString("entries", array.toString()).apply();
        } catch (Exception ignore) {
        }
    }

    public boolean isFavorite(long dialogId, int msgId) {
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.dialogId == dialogId && entry.msgId == msgId) {
                return true;
            }
        }
        return false;
    }

    /** Adds the message to favorites, or removes it if already there. Returns true when added. */
    public boolean toggle(long dialogId, int msgId, String title, String snippet) {
        if (isFavorite(dialogId, msgId)) {
            remove(dialogId, msgId);
            return false;
        }
        Entry entry = new Entry();
        entry.dialogId = dialogId;
        entry.msgId = msgId;
        entry.title = title == null ? "" : title;
        entry.snippet = snippet == null ? "" : snippet;
        entry.date = System.currentTimeMillis();
        entries.add(0, entry);
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(entries.size() - 1);
        }
        save();
        return true;
    }

    public void remove(long dialogId, int msgId) {
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.dialogId == dialogId && entry.msgId == msgId) {
                entries.remove(i);
                save();
                return;
            }
        }
    }

    public ArrayList<Entry> getList() {
        return new ArrayList<>(entries);
    }

    public int getCount() {
        return entries.size();
    }
}
