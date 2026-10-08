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

    private static long getAccountUserId(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return 0;
        }
        UserConfig config = UserConfig.getInstance(account);
        if (!config.isClientActivated()) {
            return 0;
        }
        return config.clientUserId;
    }

    public static boolean isHidden(int account) {
        long userId = getAccountUserId(account);
        return userId > 0 && read().contains(String.valueOf(userId));
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
        long userId = getAccountUserId(account);
        if (userId <= 0) {
            return false;
        }
        String key = String.valueOf(userId);
        try {
            Set<String> values = read();
            if (hidden) {
                values.add(key);
            } else {
                values.remove(key);
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
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (isHidden(account)) {
                result.add(account);
            }
        }
        return result;
    }
}
