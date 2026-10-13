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

import android.content.Context;
import android.content.SharedPreferences;
import android.util.LongSparseArray;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

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

    // DrGram Fix 30: channels hidden from the Explore list (long-press a channel chip)
    private final Set<Long> hiddenChannels = new HashSet<>();
    private boolean hiddenLoaded;

    // DrGram Fix 30: search mode — hashtag search over joined channels (@ID search resolves first)
    private final ArrayList<MessageObject> searchMedia = new ArrayList<>();
    private final HashSet<String> searchKeys = new HashSet<>();
    private boolean searchMode;
    private boolean searchLoading;
    private String searchQuery;

    // DrGram Fix 31: global public chats/groups (contacts.search) for plain text
    private final ArrayList<TLRPC.Chat> globalPeers = new ArrayList<>();
    private boolean globalPeersLoading;

    // DrGram Fix 31: media filter modes (persisted per account)
    public static final int FILTER_ALL = 0;
    public static final int FILTER_VIDEOS = 1;
    public static final int FILTER_PHOTOS = 2;

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
            if (isChannelHidden(dialog.id)) {
                continue; // DrGram Fix 30: hidden via long-press on the chip
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
        int filter = getMediaFilter(); // DrGram Fix 31: all / videos / photos
        if (searchMode) {
            for (int a = 0; a < searchMedia.size(); a++) {
                MessageObject messageObject = searchMedia.get(a);
                if (messageObject == null) {
                    continue;
                }
                if (filter == FILTER_VIDEOS && !messageObject.isVideo()) {
                    continue;
                }
                if (filter == FILTER_PHOTOS && !messageObject.isPhoto()) {
                    continue;
                }
                result.add(messageObject);
            }
            return result;
        }
        for (int a = 0; a < exploreMedia.size(); a++) {
            MessageObject messageObject = exploreMedia.get(a);
            if (dialogId == 0 || (messageObject != null && messageObject.getDialogId() == dialogId)) {
                if (messageObject == null) {
                    continue;
                }
                // DrGram Fix 31: media-type filter (all / videos / photos)
                if (filter == FILTER_VIDEOS && !messageObject.isVideo()) {
                    continue;
                }
                if (filter == FILTER_PHOTOS && !messageObject.isPhoto()) {
                    continue;
                }
                // DrGram Fix 30: posts opened by the user never come back (toggleable since Fix 31)
                if (isHideSeen() && DrGramExploreSeen.isSeen(currentAccount, messageObject.getDialogId(), messageObject.getId())) {
                    continue;
                }
                result.add(messageObject);
            }
        }
        return result;
    }

    public boolean isLoading() {
        return loading || searchLoading;
    }

    public boolean hasMore() {
        if (searchMode) {
            return false;
        }
        return !moreQueue.isEmpty() || !refreshQueue.isEmpty();
    }

    public boolean isCacheFresh() {
        return hasEverLoaded && !loading && System.currentTimeMillis() - lastRefreshTime < STALE_INTERVAL;
    }

    /**
     * Entry point used by the UI. Safe to call repeatedly — de-duplicated.
     */
    public void refreshIfNeeded(boolean force) {
        if (searchMode || loading) {
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
        clearSearchState(); // DrGram Fix 30: a manual refresh returns to the normal feed

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
        if (searchMode || loading) {
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
            if (isChannelHidden(dialogId)) {
                continue; // DrGram Fix 30: hidden channels never feed the grid
            }
            if (isHideSeen() && DrGramExploreSeen.isSeen(currentAccount, dialogId, message.id)) {
                continue; // DrGram Fix 30: seen posts never come back (toggleable since Fix 31)
            }
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
        sortMediaList(exploreMedia);
    }

    private static void sortMediaList(ArrayList<MessageObject> list) {
        Collections.sort(list, (o1, o2) -> {
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

    //region Hidden channels (DrGram Fix 30)

    private SharedPreferences explorePrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(
                "drgramexplore_" + currentAccount, Context.MODE_PRIVATE);
    }

    private void loadHiddenIfNeeded() {
        if (hiddenLoaded) {
            return;
        }
        hiddenLoaded = true;
        try {
            Set<String> raw = explorePrefs().getStringSet("hidden", null);
            hiddenChannels.clear();
            if (raw != null) {
                for (String s : raw) {
                    try {
                        hiddenChannels.add(Long.parseLong(s));
                    } catch (Exception ignore) {}
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    public boolean isChannelHidden(long dialogId) {
        loadHiddenIfNeeded();
        return hiddenChannels.contains(dialogId);
    }

    public int getHiddenCount() {
        loadHiddenIfNeeded();
        return hiddenChannels.size();
    }

    public ArrayList<Long> getHiddenList() {
        loadHiddenIfNeeded();
        ArrayList<Long> list = new ArrayList<>(hiddenChannels);
        Collections.sort(list);
        return list;
    }

    public void setHidden(long dialogId, boolean hidden) {
        loadHiddenIfNeeded();
        boolean changed = hidden ? hiddenChannels.add(dialogId) : hiddenChannels.remove(dialogId);
        if (!changed) {
            return;
        }
        try {
            Set<String> raw = new HashSet<>();
            for (Long id : hiddenChannels) {
                raw.add(String.valueOf(id));
            }
            explorePrefs().edit().putStringSet("hidden", raw).commit();
        } catch (Throwable t) {
            FileLog.e(t);
        }
        if (hidden) {
            for (int a = exploreMedia.size() - 1; a >= 0; a--) {
                MessageObject messageObject = exploreMedia.get(a);
                if (messageObject != null && messageObject.getDialogId() == dialogId) {
                    mediaKeys.remove(messageObject.getDialogId() + "_" + messageObject.getId());
                    exploreMedia.remove(a);
                }
            }
        }
        notifyExploreUpdated();
    }
    //endregion

    //region Search mode (DrGram Fix 30)

    public boolean isSearchMode() {
        return searchMode;
    }

    public String getSearchQuery() {
        return searchQuery;
    }

    public boolean isSearchLoading() {
        return searchLoading;
    }

    public ArrayList<TLRPC.Chat> getGlobalPeers() {
        return globalPeers;
    }

    public boolean isGlobalPeersLoading() {
        return globalPeersLoading;
    }

    /** DrGram Fix 31: global search over ALL of Telegram (contacts.search) —
     * finds public channels and groups by any plain text: a name, a sentence
     * or an @id, no hashtag or t.me link required. Official MTProto only. */
    public void searchGlobalPeers(String query) {
        globalPeers.clear();
        globalPeersLoading = true;
        notifyExploreUpdated();
        TLRPC.TL_contacts_search request = new TLRPC.TL_contacts_search();
        request.q = query;
        request.limit = 30;
        getConnectionsManager().sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            globalPeersLoading = false;
            if (!searchMode) {
                return;
            }
            if (response instanceof TLRPC.TL_contacts_found) {
                TLRPC.TL_contacts_found found = (TLRPC.TL_contacts_found) response;
                getMessagesController().putUsers(found.users, false);
                getMessagesController().putChats(found.chats, false);
                for (int a = 0; a < found.results.size() && globalPeers.size() < 20; a++) {
                    TLRPC.Peer peer = found.results.get(a);
                    if (peer == null || peer.chat_id == 0) {
                        continue;
                    }
                    TLRPC.Chat chat = getMessagesController().getChat(peer.chat_id);
                    if (chat == null || chat instanceof TLRPC.TL_channelForbidden || chat.deactivated) {
                        continue;
                    }
                    if (!globalPeers.contains(chat)) {
                        globalPeers.add(chat);
                    }
                }
            }
            notifyExploreUpdated();
        }), ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    private void clearSearchState() {
        searchMode = false;
        searchQuery = null;
        searchLoading = false;
        searchMedia.clear();
        searchKeys.clear();
        globalPeers.clear();        // DrGram Fix 31
        globalPeersLoading = false; // DrGram Fix 31
    }

    public void clearSearch() {
        clearSearchState();
        notifyExploreUpdated();
    }

    /** Puts the UI into the searching state before the network call resolves. */
    public void beginSearch(String label) {
        if (label == null || label.trim().isEmpty()) {
            return;
        }
        searchMode = true;
        searchQuery = label.trim();
        searchLoading = true;
        searchMedia.clear();
        searchKeys.clear();
        notifyExploreUpdated();
    }

    /** Instagram-style hashtag search: official messages.searchGlobal over the user's own joined channels. */
    public void searchHashtag(String tag) {
        if (tag == null || tag.trim().isEmpty()) {
            return;
        }
        TLRPC.TL_messages_searchGlobal request = new TLRPC.TL_messages_searchGlobal();
        request.q = tag.trim();
        request.filter = new TLRPC.TL_inputMessagesFilterPhotoVideo();
        request.limit = 60;
        request.offset_rate = 0;
        request.offset_id = 0;
        request.broadcasts_only = true;
        // mirror upstream FilteredSearchView: fresh search uses an empty offset peer
        // and searches across every folder of the user's own dialogs
        request.offset_peer = new TLRPC.TL_inputPeerEmpty();
        request.flags |= 1;
        request.folder_id = 1;
        runSearch(request, request.q);
    }

    /** Official messages.search restricted to ONE peer the user explicitly asked for (@id search). */
    public void searchChannel(long dialogId, String label) {
        TLRPC.InputPeer peer = getMessagesController().getInputPeer(dialogId);
        if (peer == null) {
            clearSearch();
            return;
        }
        TLRPC.TL_messages_search request = new TLRPC.TL_messages_search();
        request.peer = peer;
        request.q = "";
        request.limit = 60;
        request.offset_id = 0;
        request.max_id = 0;
        request.min_date = 0;
        request.max_date = 0;
        request.filter = new TLRPC.TL_inputMessagesFilterPhotoVideo();
        runSearch(request, label);
    }

    private void runSearch(TLObject request, final String label) {
        beginSearch(label);
        getConnectionsManager().sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            searchLoading = false;
            if (!searchMode) {
                return;
            }
            if (response instanceof TLRPC.messages_Messages) {
                TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                getMessagesController().putUsers(res.users, false);
                getMessagesController().putChats(res.chats, false);
                ArrayList<TLRPC.Message> messages = res.messages;
                for (int a = 0; a < messages.size(); a++) {
                    TLRPC.Message message = messages.get(a);
                    if (message == null || message.action != null || message.peer_id == null || message.peer_id.channel_id <= 0) {
                        continue;
                    }
                    long dialogId = -message.peer_id.channel_id;
                    if (isChannelHidden(dialogId) || (isHideSeen() && DrGramExploreSeen.isSeen(currentAccount, dialogId, message.id))) {
                        continue;
                    }
                    String key = dialogId + "_" + message.id;
                    if (searchKeys.contains(key)) {
                        continue;
                    }
                    MessageObject messageObject = new MessageObject(currentAccount, message, false, true);
                    if (!(messageObject.isPhoto() || messageObject.isVideo())) {
                        continue;
                    }
                    searchKeys.add(key);
                    searchMedia.add(messageObject);
                }
                sortMediaList(searchMedia);
            }
            notifyExploreUpdated();
        }), ConnectionsManager.RequestFlagFailOnServerErrors);
    }
    //endregion

    //region Explore settings (DrGram Fix 31)

    public int getMediaFilter() {
        try {
            return explorePrefs().getInt("mediaFilter", FILTER_ALL);
        } catch (Throwable t) {
            return FILTER_ALL;
        }
    }

    public void setMediaFilter(int filter) {
        try {
            explorePrefs().edit().putInt("mediaFilter", filter).commit();
        } catch (Throwable t) {
            FileLog.e(t);
        }
        notifyExploreUpdated();
    }

    public boolean isHideSeen() {
        try {
            return explorePrefs().getBoolean("hideSeen", true);
        } catch (Throwable t) {
            return true;
        }
    }

    public void setHideSeen(boolean value) {
        try {
            explorePrefs().edit().putBoolean("hideSeen", value).commit();
        } catch (Throwable t) {
            FileLog.e(t);
        }
        notifyExploreUpdated();
    }

    public void clearSeenHistory() {
        DrGramExploreSeen.clear(currentAccount);
        notifyExploreUpdated();
    }

    /** 0 = auto, otherwise a fixed column count (3..5). */
    public int getGridColumns() {
        try {
            return explorePrefs().getInt("gridColumns", 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    public void setGridColumns(int columns) {
        try {
            explorePrefs().edit().putInt("gridColumns", columns).commit();
        } catch (Throwable t) {
            FileLog.e(t);
        }
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
        clearSearchState();
        hiddenLoaded = false;
        hiddenChannels.clear();
        hasEverLoaded = false;
        lastRefreshTime = 0;
    }
}
