/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Contact History — a local change log for your contact list.
 *
 * Every time the app starts (and whenever the contact list refreshes),
 * DrGram compares your contacts with the last snapshot it took and records
 * what changed: name, username, profile photo, phone number, new and
 * removed contacts. Everything stays on this device in per-account
 * preferences; nothing is uploaded and no extra requests are made.
 */
package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class DrGramContactHistory {

    public static class Entry {
        public static final int KIND_NAME = 0;
        public static final int KIND_USERNAME = 1;
        public static final int KIND_PHOTO = 2;
        public static final int KIND_PHONE = 3;
        public static final int KIND_REMOVED = 4;
        public static final int KIND_ADDED = 5;

        public long time;
        public long userId;
        public String contactName;
        public int kind;
        public String oldValue;
        public String newValue;
    }

    private static final int MAX_ENTRIES = 300;

    private static volatile boolean started;

    public static void onAppReady() {
        if (started) {
            return;
        }
        started = true;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            try {
                if (!UserConfig.getInstance(a).isClientActivated()) {
                    continue;
                }
                NotificationCenter.getInstance(a).addObserver(DrGramContactHistory::onNotification, NotificationCenter.contactsDidLoad);
            } catch (Throwable ignore) {
            }
        }
        // Contacts are usually ready a few seconds after login; still, run one
        // delayed pass so the very first launch records a baseline.
        AndroidUtilities.runOnUIThread(() -> checkNow(UserConfig.selectedAccount), 25000);
    }

    private static void onNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.contactsDidLoad) {
            checkNow(account);
        }
    }

    /**
     * Compares the current contact list with the stored snapshot and appends
     * the differences to the local change log.
     */
    public static void checkNow(int account) {
        try {
            ContactsController contactsController = ContactsController.getInstance(account);
            MessagesController messagesController = MessagesController.getInstance(account);
            if (!contactsController.contactsLoaded) {
                return;
            }
            SharedPreferences preferences = prefs(account);
            HashMap<String, Snapshot> previous = loadSnapshots(preferences);
            HashMap<String, Snapshot> current = new HashMap<>();
            ArrayList<Entry> newEntries = new ArrayList<>();
            long now = System.currentTimeMillis() / 1000L;

            for (int i = 0; i < contactsController.contacts.size(); i++) {
                TLRPC.TL_contact contact = contactsController.contacts.get(i);
                if (contact == null) {
                    continue;
                }
                TLRPC.User user = messagesController.getUser(contact.user_id);
                if (user == null || user.self || user.bot) {
                    continue;
                }
                Snapshot snapshot = Snapshot.from(user);
                current.put(snapshot.key(), snapshot);
                Snapshot was = previous.get(snapshot.key());
                if (was == null) {
                    if (!previous.isEmpty()) {
                        newEntries.add(makeEntry(now, user.id, snapshot.name, Entry.KIND_ADDED, "", ""));
                    }
                } else {
                    if (!TextUtils.equals(was.name, snapshot.name)) {
                        newEntries.add(makeEntry(now, user.id, snapshot.name, Entry.KIND_NAME, was.name, snapshot.name));
                    }
                    if (!TextUtils.equals(was.username, snapshot.username)) {
                        newEntries.add(makeEntry(now, user.id, snapshot.name, Entry.KIND_USERNAME, safe(was.username), safe(snapshot.username)));
                    }
                    if (!TextUtils.equals(was.photoId, snapshot.photoId)) {
                        newEntries.add(makeEntry(now, user.id, snapshot.name, Entry.KIND_PHOTO, "", ""));
                    }
                    if (!TextUtils.equals(was.phone, snapshot.phone)) {
                        newEntries.add(makeEntry(now, user.id, snapshot.name, Entry.KIND_PHONE, safe(was.phone), safe(snapshot.phone)));
                    }
                }
            }
            if (!previous.isEmpty()) {
                for (Map.Entry<String, Snapshot> e : previous.entrySet()) {
                    if (!current.containsKey(e.getKey())) {
                        newEntries.add(makeEntry(now, e.getValue().userId, e.getValue().name, Entry.KIND_REMOVED, "", ""));
                    }
                }
            }
            saveSnapshots(preferences, current);
            if (!newEntries.isEmpty()) {
                appendEntries(preferences, newEntries);
            }
        } catch (Throwable ignore) {
        }
    }

    private static Entry makeEntry(long time, long userId, String contactName, int kind, String oldValue, String newValue) {
        Entry entry = new Entry();
        entry.time = time;
        entry.userId = userId;
        entry.contactName = contactName;
        entry.kind = kind;
        entry.oldValue = oldValue;
        entry.newValue = newValue;
        return entry;
    }

    //region storage

    private static SharedPreferences prefs(int account) {
        String prefsName = account == 0 ? "drgram_contactlog" : "drgram_contactlog_" + account;
        return ApplicationLoader.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
    }

    public static ArrayList<Entry> getLog(int account) {
        ArrayList<Entry> entries = new ArrayList<>();
        try {
            String raw = prefs(account).getString("log", null);
            if (raw != null) {
                JSONArray array = new JSONArray(raw);
                for (int i = 0; i < array.length(); ++i) {
                    JSONObject object = array.getJSONObject(i);
                    Entry entry = new Entry();
                    entry.time = object.optLong("t", 0L);
                    entry.userId = object.optLong("u", 0L);
                    entry.contactName = object.optString("n", "");
                    entry.kind = object.optInt("k", 0);
                    entry.oldValue = object.optString("o", "");
                    entry.newValue = object.optString("w", "");
                    entries.add(entry);
                }
            }
        } catch (Throwable ignore) {
        }
        return entries;
    }

    public static void clearLog(int account) {
        prefs(account).edit().remove("log").apply();
    }

    private static void appendEntries(SharedPreferences preferences, ArrayList<Entry> additions) {
        try {
            ArrayList<Entry> entries = new ArrayList<>(additions);
            String raw = preferences.getString("log", null);
            if (raw != null) {
                JSONArray array = new JSONArray(raw);
                for (int i = 0; i < array.length(); ++i) {
                    JSONObject object = array.getJSONObject(i);
                    Entry entry = new Entry();
                    entry.time = object.optLong("t", 0L);
                    entry.userId = object.optLong("u", 0L);
                    entry.contactName = object.optString("n", "");
                    entry.kind = object.optInt("k", 0);
                    entry.oldValue = object.optString("o", "");
                    entry.newValue = object.optString("w", "");
                    entries.add(entry);
                }
            }
            while (entries.size() > MAX_ENTRIES) {
                entries.remove(entries.size() - 1);
            }
            JSONArray array = new JSONArray();
            for (Entry entry : entries) {
                try {
                    JSONObject object = new JSONObject();
                    object.put("t", entry.time);
                    object.put("u", entry.userId);
                    object.put("n", entry.contactName);
                    object.put("k", entry.kind);
                    object.put("o", safe(entry.oldValue));
                    object.put("w", safe(entry.newValue));
                    array.put(object);
                } catch (Throwable ignore) {
                }
            }
            preferences.edit().putString("log", array.toString()).apply();
        } catch (Throwable ignore) {
        }
    }

    private static class Snapshot {
        long userId;
        String name;
        String username;
        String photoId;
        String phone;

        String key() {
            return String.valueOf(userId);
        }

        static Snapshot from(TLRPC.User user) {
            Snapshot snapshot = new Snapshot();
            snapshot.userId = user.id;
            snapshot.name = safe(UserObject.getUserName(user));
            snapshot.username = safe(user.username);
            if (TextUtils.isEmpty(snapshot.username) && user.usernames != null && !user.usernames.isEmpty() && user.usernames.get(0) != null) {
                snapshot.username = safe(user.usernames.get(0).username);
            }
            if (user.photo instanceof TLRPC.TL_userProfilePhoto) {
                snapshot.photoId = String.valueOf(user.photo.photo_id);
            } else {
                snapshot.photoId = "0";
            }
            snapshot.phone = safe(user.phone);
            return snapshot;
        }
    }

    private static HashMap<String, Snapshot> loadSnapshots(SharedPreferences preferences) {
        HashMap<String, Snapshot> snapshots = new HashMap<>();
        try {
            String raw = preferences.getString("snapshots", null);
            if (raw != null) {
                JSONArray array = new JSONArray(raw);
                for (int i = 0; i < array.length(); ++i) {
                    JSONObject object = array.getJSONObject(i);
                    Snapshot snapshot = new Snapshot();
                    snapshot.userId = object.optLong("u", 0L);
                    snapshot.name = object.optString("n", "");
                    snapshot.username = object.optString("s", "");
                    snapshot.photoId = object.optString("p", "0");
                    snapshot.phone = object.optString("h", "");
                    snapshots.put(snapshot.key(), snapshot);
                }
            }
        } catch (Throwable ignore) {
        }
        return snapshots;
    }

    private static void saveSnapshots(SharedPreferences preferences, HashMap<String, Snapshot> snapshots) {
        try {
            JSONArray array = new JSONArray();
            for (Snapshot snapshot : snapshots.values()) {
                try {
                    JSONObject object = new JSONObject();
                    object.put("u", snapshot.userId);
                    object.put("n", snapshot.name);
                    object.put("s", safe(snapshot.username));
                    object.put("p", snapshot.photoId);
                    object.put("h", safe(snapshot.phone));
                    array.put(object);
                } catch (Throwable ignore) {
                }
            }
            preferences.edit().putString("snapshots", array.toString()).apply();
        } catch (Throwable ignore) {
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    //endregion
}
