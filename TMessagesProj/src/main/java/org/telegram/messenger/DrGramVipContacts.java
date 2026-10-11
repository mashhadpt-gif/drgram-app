/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram VIP Circle — mark the people who matter most and give each of them
 * a personal tag color. Everything is stored locally in per-account prefs.
 */
package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class DrGramVipContacts {

    public static class Member {
        public long userId;
        public int color;
        public long addedAt;

        Member(long userId, int color, long addedAt) {
            this.userId = userId;
            this.color = color;
            this.addedAt = addedAt;
        }
    }

    private static SharedPreferences prefs(int account) {
        return ApplicationLoader.applicationContext.getSharedPreferences(
                "drgram_vip_contacts_" + account, Context.MODE_PRIVATE);
    }

    public static boolean isVip(int account, long userId) {
        return prefs(account).contains("u" + userId);
    }

    public static int getColor(int account, long userId, int fallback) {
        try {
            String raw = prefs(account).getString("u" + userId, null);
            if (raw != null) {
                return new JSONObject(raw).optInt("color", fallback);
            }
        } catch (Exception ignore) {
        }
        return fallback;
    }

    public static List<Member> getMembers(int account) {
        List<Member> out = new ArrayList<>();
        try {
            android.content.SharedPreferences p = prefs(account);
            Iterator<String> keys = p.getAll().keySet().iterator();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!key.startsWith("u")) {
                    continue;
                }
                long uid;
                try {
                    uid = Long.parseLong(key.substring(1));
                } catch (NumberFormatException ignore) {
                    continue;
                }
                JSONObject obj = new JSONObject(p.getString(key, "{}"));
                out.add(new Member(uid, obj.optInt("color", 0xFFF5B90D), obj.optLong("addedAt")));
            }
        } catch (Exception ignore) {
        }
        return out;
    }

    public static void add(int account, long userId, int color) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("color", color);
            obj.put("addedAt", System.currentTimeMillis());
            prefs(account).edit().putString("u" + userId, obj.toString()).apply();
        } catch (Exception ignore) {
        }
    }

    public static void setColor(int account, long userId, int color) {
        add(account, userId, color);
    }

    public static void remove(int account, long userId) {
        prefs(account).edit().remove("u" + userId).apply();
    }
}
