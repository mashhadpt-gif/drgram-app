/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Chat Tools — small behavior switches for the chat screen:
 *   • Summary view: very long text messages are laid out with only their
 *     first paragraphs; the full text is always intact and one menu tap away.
 *   • Quick chat switcher: jump between recent chats from the chat menu.
 *
 * The trimming is purely a layout step: message data, copy, reply and
 * forwarding are never modified. Nothing here talks to the network.
 */
package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;

public class DrGramChatTools {

    public static final int COLLAPSE_MIN_CHARS = 1500;
    public static final int COLLAPSE_KEEP_HEIGHT_DP = 170;

    private static volatile DrGramChatTools[] instances = new DrGramChatTools[UserConfig.MAX_ACCOUNT_COUNT];

    private final int currentAccount;
    private final SharedPreferences preferences;

    public static DrGramChatTools getInstance(int account) {
        DrGramChatTools instance = instances[account];
        if (instance == null) {
            synchronized (DrGramChatTools.class) {
                instance = instances[account];
                if (instance == null) {
                    instance = new DrGramChatTools(account);
                    instances[account] = instance;
                }
            }
        }
        return instance;
    }

    private DrGramChatTools(int account) {
        currentAccount = account;
        preferences = ApplicationLoader.applicationContext.getSharedPreferences("drgram_chattools_" + account, Context.MODE_PRIVATE);
    }

    public boolean isCollapseEnabled() {
        try {
            return preferences.getBoolean("collapse_long", true);
        } catch (Exception e) {
            return true;
        }
    }

    public void setCollapseEnabled(boolean value) {
        preferences.edit().putBoolean("collapse_long", value).apply();
    }

    public boolean isQuickChatsEnabled() {
        try {
            return preferences.getBoolean("quick_chats", true);
        } catch (Exception e) {
            return true;
        }
    }

    public void setQuickChatsEnabled(boolean value) {
        preferences.edit().putBoolean("quick_chats", value).apply();
    }

    /**
     * Called at the very end of MessageObject.generateLayout. Keeps the first
     * text layout blocks (up to COLLAPSE_KEEP_HEIGHT_DP) and drops the rest so
     * the bubble renders a short preview. Returns the original list unchanged
     * whenever anything about the message makes collapsing unsafe: expanded
     * by the user, non plain-text, code blocks, quotes or short messages.
     */
    public static ArrayList<MessageObject.TextLayoutBlock> trimBlocks(int account, MessageObject messageObject, ArrayList<MessageObject.TextLayoutBlock> blocks) {
        try {
            if (blocks == null || blocks.size() <= 2 || messageObject == null) {
                return blocks;
            }
            if (messageObject.drgramExpanded || messageObject.type != MessageObject.TYPE_TEXT) {
                return blocks;
            }
            if (messageObject.hasCode || messageObject.hasQuote || messageObject.hasSingleQuote) {
                return blocks;
            }
            if (messageObject.messageText == null || messageObject.messageText.length() < COLLAPSE_MIN_CHARS) {
                return blocks;
            }
            if (!getInstance(account).isCollapseEnabled()) {
                return blocks;
            }
            int kept = 1;
            int height = 0;
            for (int i = 0; i < blocks.size(); ++i) {
                MessageObject.TextLayoutBlock block = blocks.get(i);
                height += block.padTop + block.height() + block.padBottom;
                kept = i + 1;
                if (height >= AndroidUtilities.dp(COLLAPSE_KEEP_HEIGHT_DP)) {
                    break;
                }
            }
            if (kept >= blocks.size()) {
                return blocks;
            }
            return new ArrayList<>(blocks.subList(0, kept));
        } catch (Throwable ignore) {
            return blocks;
        }
    }
}
