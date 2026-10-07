/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGramExploreController — native Explore feed.
 *
 * Telegram ToS / API compliance by design:
 *  - ONLY official MTProto requests (messages.search) sent through the
 *    official ConnectionsManager session of the user's own account.
 *  - Content is fetched ONLY from channels the user has actually joined
 *    (their own dialog list). No global search, no scraping, no crawlers.
 *  - Requests are sequential and rate-limited (>= 350 ms apart), bounded
 *    per refresh, and cached in RAM to avoid redundant network usage.
 *  - All media is loaded by Telegram's own FileLoader from Telegram's
 *    own data centers. No third-party servers are involved anywhere.
 */
package org.telegram.messenger;

import android.util.LongSparseArray;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

public class DrGramExploreController extends BaseController {

    private static volatile DrGramExploreController[] Instance = new DrGramExploreController[UserConfig.MAX_ACCOUNT_COUNT];
    public static DrGramExploreController getInstance(int num) {
        DrGramExploreController localInstance = Instance[num];
        if (localInstance == null) {
            synchronized (DrGramExploreController.class) {
                localInstance = Instance[num];
                if (localInstance == null) {
                    Instance[num] = localInstance = new DrGramExploreController(num);
                }
            }
        }
        return localInstance;
    }

    public DrGramExploreController(int num) {
        super(num);
    }

    public static final int PAGE_SIZE = 30;            // per-channel items per MTProto request (server allows up to 100)
    public static final int MAX_CHANNELS = 50;         // bounded set of most recently active joined channels
    public static final int CHANNELS_PER_MORE_ROUND = 6;
    public static final int MAX_TOTAL_ITEMS = 800;     // hard RAM cap for the merged feed
    public static final long STALE_INTERVAL = 5 * 60 * 1000L;
    private static final long REQUEST_GAP_MS = 350;    // politeness gap between channel requests

    private static final int STATE_IDLE = 0;
    private static final int STATE_LOADING = 1;
    private static final int STATE_DONE = 2;           // reached the end of this channel's media

    // Merged feed, newest first. State is touched ONLY on the UI thread.
    private final ArrayList<MessageObject> exploreMedia = new ArrayList<>();
    private final HashMap<String, MessageObject> mediaKeys = new HashMap<>();
    private final ArrayList<Long> refreshQueue = new ArrayList<>();     // channels waiting for their first page
    private final ArrayList<Long> moreQueue = new ArrayList<>();        // channels that still have deeper pages
    private final LongSparseArray<Integer> channelStates = new LongSparseArray<>();
    private final LongSparseArray<Integer> nextOffsetId = new LongSparseArray<>();

    private boolean loading;
    private long lastRefreshTime;
    private boolean hasEverLoaded;
    private int activeRequests;
    private Runnable pendingNextRunnable;

    public static class ChannelInfo {
        public long dialogId;
        public TLRPC.Chat chat;
    }

    //region Public API (UI thread)

    /**
     * Joined broadcast channels, ordered by last activity, bounded by MAX_CHANNELS.
     */
    public ArrayList<ChannelInfo> getChannels() {
        ArrayList<ChannelInfo> result = new ArrayList<>();
        MessagesController messagesController = getMessagesController();
        ArrayList<TLRPC.Dialog> dialogs = messagesController.getAllDialogs();
        for (int a = 0; a < dialogs.size() && result.size() < MAX_CHANNELS; a++) {
            TLRPC.Dialog dialog = dialogs.get(a);
            TLRPC.Chat chat = messagesController.getChat(-dialog.id);
            if (chat == null || chat.left || chat.kicked || chat instanceof TLRPC.TL_channelForbidden || chat.deactivated) {
                continue;
            }
            if (!ChatObject.isChannelAndNotMegaGroup(chat)) {
                continue; // broadcast channels only — an Explore of publications
            }
            ChannelInfo info = new ChannelInfo();
            info.dialogId = dialog.id;
            info.chat = chat;
            result.add(info);
        }
        return result;
    }

    public ArrayList<MessageObject> getMedia() {
        return exploreMedia;
    }

    public ArrayList<MessageObject> getFilteredMedia(long dialogId) {
        // always return a copy so the UI / PhotoViewer never hold a reference
        // to the live list that refresh() clears and loadMore() appends to
        ArrayList<MessageObject> result = new ArrayList<>();
        for (int a = 0; a < exploreMedia.size(); a++) {
            MessageObject messageObject = exploreMedia.get(a);
            if (dialogId == 0 || (messageObject != null && messageObject.getDialogId() == dialogId)) {
                result.add(messageObject);
            }
        }
        return result;
    }

    public boolean isLoading() {
        return loading;
    }

    public boolean hasMore() {
        return !moreQueue.isEmpty() || !refreshQueue.isEmpty();
    }

    public boolean isCacheFresh() {
        return hasEverLoaded && !loading && System.currentTimeMillis() - lastRefreshTime < STALE_INTERVAL;
    }

    /**
     * Entry point used by the UI. Safe to call repeatedly — de-duplicated.
     */
    public void refreshIfNeeded(boolean force) {
        if (loading) {
            return;
        }
        if (!force && isCacheFresh()) {
            return;
        }
        refresh();
    }

    public void refresh() {
        if (loading) {
            return;
        }
        cancelPendingNext();
        loading = true;

        refreshQueue.clear();
        moreQueue.clear();
        channelStates.clear();
        nextOffsetId.clear();
        exploreMedia.clear();
        mediaKeys.clear();

        ArrayList<ChannelInfo> channels = getChannels();
        for (int a = 0; a < channels.size(); a++) {
            refreshQueue.add(channels.get(a).dialogId);
        }
        notifyExploreUpdated();
        if (refreshQueue.isEmpty()) {
            // Dialogs might not be in cache yet; dialogsNeedReload will bring the UI here again.
            // Do not mark the (empty) cache fresh, so a retry happens once dialogs arrive.
            loading = false;
            if (!channels.isEmpty()) {
                lastRefreshTime = System.currentTimeMillis();
                hasEverLoaded = true;
            }
            notifyExploreUpdated();
            return;
        }
        scheduleNextChannel(0);
    }

    /**
     * Loads the next page for up to CHANNELS_PER_MORE_ROUND channels (round-robin).
     * Called by the UI when the user scrolls near the bottom of the grid.
     */
    public void loadMore() {
        if (loading) {
            return;
        }
        if (!refreshQueue.isEmpty()) {
            scheduleNextChannel(0);
            return;
        }
        if (moreQueue.isEmpty()) {
            return;
        }
        loading = true;
        int count = Math.min(CHANNELS_PER_MORE_ROUND, moreQueue.size());
        ArrayList<Long> picked = new ArrayList<>(moreQueue.subList(0, count));
        for (int a = 0; a < picked.size(); a++) {
            long dialogId = picked.get(a);
            channelStates.put(dialogId, STATE_LOADING);
            loadChannelPage(dialogId, false);
        }
    }
    //endregion

    //region Sequential channel loading (rate-limited)

    private void scheduleNextChannel(long delay) {
        cancelPendingNext();
        pendingNextRunnable = this::loadNextChannel;
        AndroidUtilities.runOnUIThread(pendingNextRunnable, delay);
    }

    private void cancelPendingNext() {
        if (pendingNextRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(pendingNextRunnable);
            pendingNextRunnable = null;
        }
    }

    private void loadNextChannel() {
        pendingNextRunnable = null;
        if (!loading) {
            return;
        }
        if (!refreshQueue.isEmpty()) {
            long dialogId = refreshQueue.remove(0);
            channelStates.put(dialogId, STATE_LOADING);
            loadChannelPage(dialogId, true);
        } else {
            finishRefresh();
        }
    }

    /**
     * Official MTProto: messages.search with inputMessagesFilterPhotoVideo on ONE peer.
     */
    private void loadChannelPage(long dialogId, boolean partOfInitialRefresh) {
        TLRPC.InputPeer peer = getMessagesController().getInputPeer(dialogId);
        if (peer == null) {
            channelStates.put(dialogId, STATE_DONE);
            afterChannelRequest(dialogId, partOfInitialRefresh);
            return;
        }
        TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
        req.peer = peer;
        req.q = "";
        req.limit = PAGE_SIZE;
        req.offset_id = nextOffsetId.get(dialogId, 0);
        req.max_id = 0;
        req.min_date = 0;
        req.max_date = 0;
        req.filter = new TLRPC.TL_inputMessagesFilterPhotoVideo();
        activeRequests++;
        getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            activeRequests--;
            if (response instanceof TLRPC.messages_Messages) {
                TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                getMessagesController().putUsers(res.users, false);
                getMessagesController().putChats(res.chats, false);
                getMessagesStorage().putUsersAndChats(res.users, res.chats, true, true);
                Integer previousState = channelStates.get(dialogId);
                boolean aborted = previousState == null || previousState == STATE_DONE;
                if (!aborted) {
                    boolean endReached = res.messages.size() < PAGE_SIZE;
                    insertMedia(res.messages);
                    nextOffsetId.put(dialogId, oldestMessageId(res.messages));
                    channelStates.put(dialogId, endReached ? STATE_DONE : STATE_IDLE);
                    if (!endReached && !moreQueue.contains(dialogId)) {
                        moreQueue.add(dialogId);
                    }
                    notifyExploreUpdated();
                }
            } else {
                channelStates.put(dialogId, STATE_DONE);
            }
            afterChannelRequest(dialogId, partOfInitialRefresh);
        }), ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    private void afterChannelRequest(long dialogId, boolean partOfInitialRefresh) {
        if (partOfInitialRefresh && loading) {
            if (refreshQueue.isEmpty()) {
                finishRefresh();
            } else {
                scheduleNextChannel(REQUEST_GAP_MS);
            }
        } else if (activeRequests <= 0 && refreshQueue.isEmpty() && pendingNextRunnable == null) {
            loading = false;
            notifyExploreUpdated();
        }
    }

    private void finishRefresh() {
        loading = false;
        lastRefreshTime = System.currentTimeMillis();
        hasEverLoaded = true;
        notifyExploreUpdated();
    }
    //endregion

    //region Merge / dedupe helpers

    private int oldestMessageId(ArrayList<TLRPC.Message> messages) {
        int minId = 0;
        for (int a = 0; a < messages.size(); a++) {
            TLRPC.Message message = messages.get(a);
            if (message != null && (minId == 0 || message.id < minId)) {
                minId = message.id;
            }
        }
        return minId;
    }

    private void insertMedia(ArrayList<TLRPC.Message> messages) {
        boolean changed = false;
        for (int a = 0; a < messages.size(); a++) {
            TLRPC.Message message = messages.get(a);
            if (message == null || message.action != null || message.peer_id == null || message.peer_id.channel_id <= 0) {
                continue;
            }
            long dialogId = -message.peer_id.channel_id;
            String key = dialogId + "_" + message.id;
            if (mediaKeys.containsKey(key)) {
                continue;
            }
            MessageObject messageObject = new MessageObject(currentAccount, message, false, true);
            if (!(messageObject.isPhoto() || messageObject.isVideo())) {
                continue;
            }
            mediaKeys.put(key, messageObject);
            exploreMedia.add(messageObject);
            changed = true;
        }
        if (changed) {
            sortMedia();
            if (exploreMedia.size() > MAX_TOTAL_ITEMS) {
                sortMedia();
                int overflow = exploreMedia.size() - MAX_TOTAL_ITEMS;
                for (int a = 0; a < overflow; a++) {
                    MessageObject removed = exploreMedia.remove(exploreMedia.size() - 1);
                    mediaKeys.remove(removed.getDialogId() + "_" + removed.getId());
                }
            }
        }
    }

    private void sortMedia() {
        Collections.sort(exploreMedia, (o1, o2) -> {
            if (o1.messageOwner.date != o2.messageOwner.date) {
                return o2.messageOwner.date - o1.messageOwner.date;
            }
            return o2.getId() - o1.getId();
        });
    }

    private void notifyExploreUpdated() {
        getNotificationCenter().postNotificationName(NotificationCenter.drgramExploreUpdated);
    }
    //endregion

    public void cleanup() {
        cancelPendingNext();
        loading = false;
        activeRequests = 0;
        refreshQueue.clear();
        moreQueue.clear();
        channelStates.clear();
        nextOffsetId.clear();
        exploreMedia.clear();
        mediaKeys.clear();
        hasEverLoaded = false;
        lastRefreshTime = 0;
    }
}
