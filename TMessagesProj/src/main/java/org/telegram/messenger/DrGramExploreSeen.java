/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Fix 30: persistent "seen" store for the Explore feed.
 * Every post the user opens (or swipes to) in the Explore viewer is recorded
 * locally and never shown again in the feed — Instagram-style. The store is
 * bounded (oldest entries are dropped first) and lives per account.
 */
package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

public final class DrGramExploreSeen {

    private static final Object LOCK = new Object();
    private static final int MAX_SEEN = 4000;
    @SuppressWarnings("unchecked")
    private static final Set<String>[] Cache = new HashSet[UserConfig.MAX_ACCOUNT_COUNT];

    private DrGramExploreSeen() {}

    private static SharedPreferences prefs(int account) {
        return ApplicationLoader.applicationContext.getSharedPreferences(
                "drgramexplore_" + account, Context.MODE_PRIVATE);
    }

    private static Set<String> set(int account) {
        Set<String> local = Cache[account];
        if (local != null) {
            return local;
        }
        synchronized (LOCK) {
            local = Cache[account];
            if (local == null) {
                Set<String> stored = null;
                try {
                    stored = prefs(account).getStringSet("seen", null);
                } catch (Throwable ignore) {}
                local = stored == null ? new HashSet<>() : new HashSet<>(stored);
                Cache[account] = local;
            }
        }
        return local;
    }

    public static boolean isSeen(int account, long dialogId, int messageId) {
        try {
            return set(account).contains(dialogId + "_" + messageId);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void markSeen(int account, long dialogId, int messageId) {
        try {
            synchronized (LOCK) {
                Set<String> set = set(account);
                String key = dialogId + "_" + messageId;
                if (!set.add(key)) {
                    return;
                }
                if (set.size() > MAX_SEEN) {
                    Iterator<String> it = set.iterator();
                    int overflow = set.size() - MAX_SEEN + 128;
                    for (int i = 0; i < overflow && it.hasNext(); i++) {
                        it.next();
                        it.remove();
                    }
                }
                persist(account, set);
            }
        } catch (Throwable ignore) {}
    }

    public static void clear(int account) {
        try {
            synchronized (LOCK) {
                set(account).clear();
                persist(account, set(account));
            }
        } catch (Throwable ignore) {}
    }

    private static void persist(int account, Set<String> set) {
        try {
            prefs(account).edit().putStringSet("seen", new HashSet<>(set)).commit();
        } catch (Throwable ignore) {}
    }
}
