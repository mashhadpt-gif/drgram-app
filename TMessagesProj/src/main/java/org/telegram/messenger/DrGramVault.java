/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Vault — local-only hidden chats storage.
 * Chats hidden by the user are filtered out of every dialog list on this
 * device. Nothing is ever sent to Telegram servers about this preference.
 */
package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class DrGramVault {

    private static final long REVEAL_COOLDOWN_MS = 1500;

    private static volatile DrGramVault[] instances = new DrGramVault[UserConfig.MAX_ACCOUNT_COUNT];

    private final int currentAccount;
    private final SharedPreferences preferences;
    private Set<String> hiddenIds;
    private String vaultCode;
    private long lastRevealAttempt;

    public static DrGramVault getInstance(int account) {
        DrGramVault instance = instances[account];
        if (instance == null) {
            synchronized (DrGramVault.class) {
                instance = instances[account];
                if (instance == null) {
                    instance = new DrGramVault(account);
                    instances[account] = instance;
                }
            }
        }
        return instance;
    }

    private DrGramVault(int account) {
        currentAccount = account;
        Context context = ApplicationLoader.applicationContext;
        String prefsName = account == 0 ? "drgram_vault" : "drgram_vault_" + account;
        preferences = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        hiddenIds = preferences.getStringSet("hidden_ids", new HashSet<>());
        vaultCode = preferences.getString("vault_code", "");
    }

    private void save() {
        preferences.edit()
                .putStringSet("hidden_ids", hiddenIds)
                .putString("vault_code", vaultCode)
                .apply();
    }

    public boolean isHidden(long dialogId) {
        return hiddenIds.contains(String.valueOf(dialogId));
    }

    public int getHiddenCount() {
        return hiddenIds.size();
    }

    public ArrayList<Long> getHiddenIds() {
        ArrayList<Long> ids = new ArrayList<>();
        for (String id : hiddenIds) {
            try {
                ids.add(Long.parseLong(id));
            } catch (NumberFormatException ignore) {
            }
        }
        return ids;
    }

    public void hide(long dialogId) {
        hiddenIds.add(String.valueOf(dialogId));
        save();
        notifyListsChanged();
    }

    public void unhide(long dialogId) {
        hiddenIds.remove(String.valueOf(dialogId));
        save();
        notifyListsChanged();
    }

    public boolean hasCode() {
        return !TextUtils.isEmpty(vaultCode);
    }

    public String getCode() {
        return vaultCode;
    }

    public void setCode(String code) {
        vaultCode = code == null ? "" : code.trim();
        save();
    }

    /**
     * Called when the user types something into the main search field.
     * Returns true when the typed text matches the vault code, meaning the
     * caller should open the vault screen instead of searching.
     */
    public boolean matchesRevealCode(CharSequence text) {
        if (TextUtils.isEmpty(vaultCode) || text == null) {
            return false;
        }
        String typed = text.toString().trim();
        if (typed.length() < 2 || typed.length() != vaultCode.length()) {
            return false;
        }
        if (System.currentTimeMillis() - lastRevealAttempt < REVEAL_COOLDOWN_MS) {
            return false;
        }
        lastRevealAttempt = System.currentTimeMillis();
        return vaultCode.equals(typed);
    }

    /**
     * Returns a filtered copy of the supplied dialog list with hidden chats
     * removed. When nothing is hidden the original list is returned as-is,
     * so the common case costs exactly one size check.
     */
    public ArrayList<TLRPC.Dialog> filterDialogs(ArrayList<TLRPC.Dialog> dialogs) {
        if (hiddenIds.isEmpty() || dialogs == null) {
            return dialogs;
        }
        ArrayList<TLRPC.Dialog> result = new ArrayList<>(dialogs.size());
        for (int i = 0; i < dialogs.size(); i++) {
            TLRPC.Dialog dialog = dialogs.get(i);
            if (dialog != null && hiddenIds.contains(String.valueOf(dialog.id))) {
                continue;
            }
            result.add(dialog);
        }
        return result;
    }

    private void notifyListsChanged() {
        AndroidUtilities.runOnUIThread(() -> {
            if (UserConfig.selectedAccount == currentAccount) {
                NotificationCenter.getInstance(currentAccount)
                        .postNotificationName(NotificationCenter.dialogsNeedReload);
            }
        });
    }
}
