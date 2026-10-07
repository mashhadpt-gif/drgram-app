/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGramQuickReplies — a personal phraseboard.
 * The user keeps short ready-made answers here and drops them into the
 * message field straight from the chat menu. Storage is device-local JSON;
 * nothing leaves the phone.
 */
package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

public class DrGramQuickReplies {

    public static class Item {
        public long id;
        public String title;
        public String text;
    }

    private static final int MAX_ITEMS = 30;
    private static final int MAX_TEXT = 700;

    private static ArrayList<Item> cached;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("drgram_settings", Context.MODE_PRIVATE);
    }

    public static synchronized ArrayList<Item> getAll() {
        if (cached != null) {
            return cached;
        }
        ArrayList<Item> items = new ArrayList<>();
        try {
            String raw = prefs().getString("quick_replies", null);
            if (!TextUtils.isEmpty(raw)) {
                JSONArray array = new JSONArray(raw);
                for (int a = 0; a < array.length() && items.size() < MAX_ITEMS; a++) {
                    JSONObject object = array.getJSONObject(a);
                    Item item = new Item();
                    item.id = object.getLong("id");
                    item.title = object.optString("title", "");
                    item.text = object.optString("text", "");
                    if (!TextUtils.isEmpty(item.text)) {
                        items.add(item);
                    }
                }
            }
        } catch (Exception ignore) {

        }
        cached = items;
        return items;
    }

    private static synchronized void save(ArrayList<Item> items) {
        try {
            JSONArray array = new JSONArray();
            for (int a = 0; a < items.size() && a < MAX_ITEMS; a++) {
                Item item = items.get(a);
                JSONObject object = new JSONObject();
                object.put("id", item.id);
                object.put("title", item.title == null ? "" : item.title);
                object.put("text", item.text == null ? "" : item.text);
                array.put(object);
            }
            prefs().edit().putString("quick_replies", array.toString()).apply();
            cached = items;
        } catch (Exception ignore) {

        }
    }

    public static synchronized boolean add(String title, String text) {
        ArrayList<Item> items = getAll();
        if (items.size() >= MAX_ITEMS || TextUtils.isEmpty(text)) {
            return false;
        }
        Item item = new Item();
        item.id = System.currentTimeMillis();
        item.title = TextUtils.isEmpty(title) ? text : title;
        item.text = text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
        if (item.title.length() > 40) {
            item.title = item.title.substring(0, 40);
        }
        items.add(item);
        save(items);
        return true;
    }

    public static synchronized boolean update(long id, String title, String text) {
        ArrayList<Item> items = getAll();
        for (int a = 0; a < items.size(); a++) {
            Item item = items.get(a);
            if (item.id == id) {
                if (!TextUtils.isEmpty(title)) {
                    item.title = title.length() > 40 ? title.substring(0, 40) : title;
                }
                if (!TextUtils.isEmpty(text)) {
                    item.text = text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    public static synchronized void remove(long id) {
        ArrayList<Item> items = getAll();
        for (int a = 0; a < items.size(); a++) {
            if (items.get(a).id == id) {
                items.remove(a);
                save(items);
                return;
            }
        }
    }

    public static synchronized int getCount() {
        return getAll().size();
    }
}
