/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Hashtags+ — a local, personal hashtag collection.
 *
 * DrGram keeps track of the hashtags you type when sending messages and
 * merges them with the hashtags you searched for (the same history the
 * official search screen builds). Nothing is uploaded anywhere: the list
 * lives in per-account preferences on this device only.
 */
package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DrGramHashtags {

    public static class Tag {
        public String tag;
        public int useCount;
        public long lastUsed;

        public Tag(String tag, int useCount, long lastUsed) {
            this.tag = tag;
            this.useCount = useCount;
            this.lastUsed = lastUsed;
        }
    }

    private static final int MAX_TAGS = 200;
    private static final Pattern HASHTAG_PATTERN = Pattern.compile("(?<![\\w/])#[\\p{L}\\p{Nd}_]{1,64}");

    private static SharedPreferences prefs(int account) {
        String prefsName = account == 0 ? "drgram_hashtags" : "drgram_hashtags_" + account;
        return ApplicationLoader.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
    }

    /**
     * Called from ChatActivity whenever the user sends a text message.
     * Extracts #hashtags and adds them to the local collection.
     */
    public static void onOutgoingText(int account, CharSequence text) {
        try {
            if (text == null || text.length() < 2) {
                return;
            }
            String body = text.toString();
            if (!body.contains("#")) {
                return;
            }
            LinkedHashSet<String> found = new LinkedHashSet<>();
            Matcher matcher = HASHTAG_PATTERN.matcher(body);
            while (matcher.find() && found.size() < 8) {
                found.add(matcher.group().toLowerCase());
            }
            if (found.isEmpty()) {
                return;
            }
            HashMap<String, Tag> tags = loadTags(account);
            long now = System.currentTimeMillis();
            for (String raw : found) {
                Tag tag = tags.get(raw);
                if (tag == null) {
                    tags.put(raw, new Tag(raw, 1, now));
                } else {
                    tag.useCount++;
                    tag.lastUsed = now;
                }
            }
            saveTags(account, tags);
        } catch (Throwable ignore) {
        }
    }

    /**
     * The merged collection: hashtags used in your outgoing messages plus the
     * hashtags picked up by the official hashtag search, newest first.
     */
    public static ArrayList<Tag> getCollection(int account) {
        HashMap<String, Tag> tags = loadTags(account);
        try {
            ArrayList<String> history = HashtagSearchController.getInstance(account).history;
            if (history != null) {
                for (int i = history.size() - 1; i >= 0; --i) {
                    String entry = history.get(i);
                    if (TextUtils.isEmpty(entry)) {
                        continue;
                    }
                    String key = entry.toLowerCase();
                    Tag tag = tags.get(key);
                    if (tag == null) {
                        tag = new Tag(key, 0, 0);
                        tags.put(key, tag);
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        ArrayList<Tag> list = new ArrayList<>(tags.values());
        list.sort((a, b) -> {
            if (a.lastUsed != b.lastUsed) {
                return Long.compare(b.lastUsed, a.lastUsed);
            }
            return b.useCount - a.useCount;
        });
        if (list.size() > MAX_TAGS) {
            list.subList(MAX_TAGS, list.size()).clear();
        }
        return list;
    }

    public static void removeTag(int account, String tag) {
        try {
            HashMap<String, Tag> tags = loadTags(account);
            tags.remove(tag.toLowerCase());
            saveTags(account, tags);
        } catch (Throwable ignore) {
        }
        try {
            HashtagSearchController.getInstance(account).removeHashtagFromHistory(tag);
        } catch (Throwable ignore) {
        }
    }

    public static void clearAll(int account) {
        prefs(account).edit().remove("tags").apply();
        try {
            HashtagSearchController.getInstance(account).clearHistory();
        } catch (Throwable ignore) {
        }
    }

    private static HashMap<String, Tag> loadTags(int account) {
        HashMap<String, Tag> tags = new HashMap<>();
        try {
            String raw = prefs(account).getString("tags", null);
            if (raw != null) {
                JSONArray array = new JSONArray(raw);
                for (int i = 0; i < array.length(); ++i) {
                    JSONObject object = array.getJSONObject(i);
                    tags.put(object.getString("t"), new Tag(object.getString("t"), object.optInt("c", 1), object.optLong("d", 0L)));
                }
            }
        } catch (Throwable ignore) {
        }
        return tags;
    }

    private static void saveTags(int account, HashMap<String, Tag> tags) {
        try {
            JSONArray array = new JSONArray();
            ArrayList<Tag> list = new ArrayList<>(tags.values());
            list.sort((a, b) -> Long.compare(b.lastUsed, a.lastUsed));
            if (list.size() > MAX_TAGS) {
                list.subList(MAX_TAGS, list.size()).clear();
            }
            for (Tag tag : list) {
                try {
                    JSONObject object = new JSONObject();
                    object.put("t", tag.tag);
                    object.put("c", tag.useCount);
                    object.put("d", tag.lastUsed);
                    array.put(object);
                } catch (Throwable ignore) {
                }
            }
            prefs(account).edit().putString("tags", array.toString()).apply();
        } catch (Throwable ignore) {
        }
    }
}
