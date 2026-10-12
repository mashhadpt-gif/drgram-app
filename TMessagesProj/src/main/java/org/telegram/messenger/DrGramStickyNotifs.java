/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Fix 21: sticky in-app notifications. Incoming messages are kept in a
 * small session-only store and surfaced as dismissable cards above the folder
 * tabs on the main screen. Nothing is written to disk and nothing leaves the
 * device — the store dies with the process.
 */
package org.telegram.messenger;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;

public class DrGramStickyNotifs {

    public static class StickyItem {
        public long dialogId;
        public String title;
        public String text;
        public long date;
    }

    private static final DrGramStickyNotifs[] INSTANCES = new DrGramStickyNotifs[UserConfig.MAX_ACCOUNT_COUNT];

    public static DrGramStickyNotifs getInstance(int num) {
        DrGramStickyNotifs local;
        synchronized (DrGramStickyNotifs.class) {
            local = INSTANCES[num];
            if (local == null) {
                local = new DrGramStickyNotifs(num);
                INSTANCES[num] = local;
            }
        }
        return local;
    }

    private final int account;
    private final LinkedHashMap<Long, StickyItem> items = new LinkedHashMap<>();

    private DrGramStickyNotifs(int account) {
        this.account = account;
    }

    public ArrayList<StickyItem> getList() {
        synchronized (items) {
            return new ArrayList<>(items.values());
        }
    }

    public void collect(ArrayList<MessageObject> messages) {
        if (messages == null || messages.isEmpty() || !SharedConfig.drgramStickyNotifs) {
            return;
        }
        for (int i = 0; i < messages.size(); i++) {
            MessageObject messageObject = messages.get(i);
            if (messageObject == null || messageObject.messageOwner == null || messageObject.isOutOwner()) {
                continue;
            }
            long dialogId = messageObject.getDialogId();
            if (dialogId == 0) {
                continue;
            }
            StickyItem item = new StickyItem();
            item.dialogId = dialogId;
            item.title = DialogObject.getShortName(account, dialogId);
            CharSequence raw = messageObject.messageText;
            String text = raw == null ? "" : raw.toString();
            if (text.length() > 140) {
                text = text.substring(0, 140) + "…";
            }
            if (text.length() == 0) {
                text = LocaleController.getString(R.string.DrGramStickyMedia);
            }
            item.text = text;
            item.date = messageObject.messageOwner.date * 1000L;
            synchronized (items) {
                items.remove(dialogId);
                items.put(dialogId, item);
                while (items.size() > 5) {
                    Iterator<StickyItem> iterator = items.values().iterator();
                    iterator.next();
                    iterator.remove();
                }
            }
        }
    }

    public void pruneUnread() {
        MessagesController controller = MessagesController.getInstance(account);
        ArrayList<Long> remove = null;
        synchronized (items) {
            for (Long id : items.keySet()) {
                TLRPC.Dialog dialog = controller.dialogs_dict.get(id);
                boolean read = dialog == null
                        || (dialog.unread_count == 0 && dialog.unread_mentions_count == 0 && dialog.unread_reactions_count == 0);
                if (read) {
                    if (remove == null) {
                        remove = new ArrayList<>();
                    }
                    remove.add(id);
                }
            }
            if (remove != null) {
                for (int i = 0; i < remove.size(); i++) {
                    items.remove(remove.get(i));
                }
            }
        }
    }

    public void dismiss(long dialogId) {
        synchronized (items) {
            items.remove(dialogId);
        }
    }

    public void clearAll() {
        synchronized (items) {
            items.clear();
        }
    }
}
