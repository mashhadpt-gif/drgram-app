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
import android.util.Base64;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

public class DrGramVault {

    private static final long REVEAL_COOLDOWN_MS = 1500;

    private static volatile DrGramVault[] instances = new DrGramVault[UserConfig.MAX_ACCOUNT_COUNT];

    private final int currentAccount;
    private final SharedPreferences preferences;
    private Set<String> hiddenIds;
    private String vaultCodeHash;
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
        // SharedPreferences returns a live set on some Android versions; always
        // clone it before mutation to avoid corrupting the stored value.
        hiddenIds = new HashSet<>(preferences.getStringSet("hidden_ids", new HashSet<>()));
        vaultCodeHash = preferences.getString("vault_code_hash", "");
        // One-time, backwards-compatible migration from the old plaintext code.
        String legacyCode = preferences.getString("vault_code", "");
        if (TextUtils.isEmpty(vaultCodeHash) && !TextUtils.isEmpty(legacyCode)) {
            vaultCodeHash = hashCode(legacyCode);
            save();
        }
    }

    private void save() {
        SharedPreferences.Editor editor = preferences.edit()
                .putStringSet("hidden_ids", new HashSet<>(hiddenIds))
                .remove("vault_code");
        if (TextUtils.isEmpty(vaultCodeHash)) {
            editor.remove("vault_code_hash");
        } else {
            editor.putString("vault_code_hash", vaultCodeHash);
        }
        editor.apply();
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
        return !TextUtils.isEmpty(vaultCodeHash);
    }

    /**
     * Never expose a saved vault code back to the UI. The settings dialog should
     * accept a new code rather than pre-filling a secret that is stored locally.
     */
    public String getCode() {
        return "";
    }

    public void setCode(String code) {
        String value = code == null ? "" : code.trim();
        vaultCodeHash = value.isEmpty() ? "" : hashCode(value);
        save();
    }

    private static String hashCode(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.encodeToString(digest.digest(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        } catch (Exception e) {
            // SHA-256 is required by Android; fail closed if a provider is unavailable.
            FileLog.e(e);
            return "";
        }
    }

    /**
     * Called when the user types something into the main search field.
     * Returns true when the typed text matches the vault code, meaning the
     * caller should open the vault screen instead of searching.
     */
    public boolean matchesRevealCode(CharSequence text) {
        if (TextUtils.isEmpty(vaultCodeHash) || text == null) {
            return false;
        }
        String typed = text.toString().trim();
        if (typed.length() < 2) {
            return false;
        }
        if (System.currentTimeMillis() - lastRevealAttempt < REVEAL_COOLDOWN_MS) {
            return false;
        }
        lastRevealAttempt = System.currentTimeMillis();
        try {
            return MessageDigest.isEqual(
                    vaultCodeHash.getBytes(StandardCharsets.UTF_8),
                    hashCode(typed).getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignore) {
            return false;
        }
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
