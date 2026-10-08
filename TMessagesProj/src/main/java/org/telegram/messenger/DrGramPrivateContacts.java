/*
 * DrGram Private Contacts — hide selected contacts from standard contact pickers.
 * The privacy list is stored locally per account and never uploaded.
 */
package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public final class DrGramPrivateContacts {
    private DrGramPrivateContacts() {
    }

    private static SharedPreferences prefs(int account) {
        String name = account == 0 ? "drgram_private_contacts" : "drgram_private_contacts_" + account;
        return ApplicationLoader.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    private static Set<String> read(int account) {
        try {
            return new HashSet<>(prefs(account).getStringSet("ids", new HashSet<>()));
        } catch (Throwable t) {
            FileLog.e(t);
            return new HashSet<>();
        }
    }

    public static boolean isPrivate(int account, long userId) {
        if (userId <= 0) return false;
        return read(account).contains(String.valueOf(userId));
    }

    public static boolean setPrivate(int account, long userId, boolean hidden) {
        if (userId <= 0 || account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return false;
        }
        try {
            Set<String> ids = read(account);
            if (hidden) {
                ids.add(String.valueOf(userId));
            } else {
                ids.remove(String.valueOf(userId));
            }
            prefs(account).edit().putStringSet("ids", new HashSet<>(ids)).apply();
            return true;
        } catch (Throwable t) {
            FileLog.e(t);
            return false;
        }
    }

    public static ArrayList<TLRPC.TL_contact> filterVisible(int account, ArrayList<TLRPC.TL_contact> source) {
        ArrayList<TLRPC.TL_contact> result = new ArrayList<>();
        if (source == null || source.isEmpty()) return result;
        Set<String> ids = read(account);
        for (TLRPC.TL_contact contact : source) {
            if (contact != null && !ids.contains(String.valueOf(contact.user_id))) {
                result.add(contact);
            }
        }
        return result;
    }

    public static int getCount(int account) {
        return read(account).size();
    }
}
