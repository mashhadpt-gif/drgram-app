/*
 * Device-local account visibility controls for DrGram.
 * Hiding an account only removes it from account-picker UI; it does not sign out,
 * erase, alter, or contact any Telegram account.
 */
package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public final class DrGramHiddenAccounts {
    private static final String PREFS = "drgram_hidden_accounts";
    private static final String KEY = "hidden_account_indices";

    private DrGramHiddenAccounts() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static Set<String> read() {
        try {
            return new HashSet<>(prefs().getStringSet(KEY, new HashSet<>()));
        } catch (Throwable t) {
            FileLog.e(t);
            return new HashSet<>();
        }
    }

    public static boolean isHidden(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return false;
        }
        return read().contains(String.valueOf(account));
    }

    /**
     * The currently active account cannot be hidden. This prevents a user from
     * hiding the account they are actively using and accidentally locking
     * themselves out of its switcher entry.
     */
    public static boolean setHidden(int account, boolean hidden) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return false;
        }
        if (!UserConfig.getInstance(account).isClientActivated()) {
            return false;
        }
        if (hidden && account == UserConfig.selectedAccount) {
            return false;
        }
        try {
            Set<String> values = read();
            if (hidden) {
                values.add(String.valueOf(account));
            } else {
                values.remove(String.valueOf(account));
            }
            prefs().edit().putStringSet(KEY, new HashSet<>(values)).apply();
            return true;
        } catch (Throwable t) {
            FileLog.e(t);
            return false;
        }
    }

    public static ArrayList<Integer> getHiddenAccounts() {
        ArrayList<Integer> result = new ArrayList<>();
        Set<String> values = read();
        for (String value : values) {
            try {
                int account = Integer.parseInt(value);
                if (account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT
                        && UserConfig.getInstance(account).isClientActivated()) {
                    result.add(account);
                }
            } catch (NumberFormatException ignore) {
            }
        }
        return result;
    }
}
