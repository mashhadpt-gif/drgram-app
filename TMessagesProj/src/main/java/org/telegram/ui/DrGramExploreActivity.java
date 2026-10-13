/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGramExploreActivity — native Explore tab.
 * An Instagram-style grid of the latest photos & videos published in the
 * channels the user has joined. All content is fetched through the official
 * MTProto API (see DrGramExploreController) and rendered by Telegram's own
 * image pipeline and PhotoViewer — no third-party services involved.
 */
package org.telegram.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DrGramExploreController;
import org.telegram.messenger.DrGramExploreSeen;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.DrGramExploreMediaCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

public class DrGramExploreActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int GOLD = 0xFFF5B90D;

    private DrGramExploreController exploreController;

    private RecyclerListView listView;
    private ExploreAdapter adapter;
    private GridLayoutManager layoutManager;

    private LinearLayout chipsContainer;
    private HorizontalScrollView chipsScroll;
    private TextView emptyTextView;
    private EditText searchField; // DrGram Fix 30
    private LinearLayout filterPills;   // DrGram Fix 31
    private int lastGlobalChipCount = -1; // DrGram Fix 31

    private long selectedChannelId; // 0 = all channels
    private ArrayList<MessageObject> currentMedia = new ArrayList<>();

    private int columnsCount = 3;

    // total item count at the moment the auto-chain last fired (stall guard)
    private int lastChainTotal = -1;

    public DrGramExploreActivity() {
        super();
        exploreController = DrGramExploreController.getInstance(currentAccount);
    }

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        getNotificationCenter().addObserver(this, NotificationCenter.dialogsNeedReload);
        getNotificationCenter().addObserver(this, NotificationCenter.drgramExploreUpdated);
        exploreController = DrGramExploreController.getInstance(currentAccount);
        exploreController.refreshIfNeeded(false);
        updateMedia(true);
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        getNotificationCenter().removeObserver(this, NotificationCenter.dialogsNeedReload);
        getNotificationCenter().removeObserver(this, NotificationCenter.drgramExploreUpdated);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.Explore));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        int prefColumns = exploreController.getGridColumns(); // DrGram Fix 31: settings-aware grid
        if (prefColumns >= 3 && prefColumns <= 5) {
            columnsCount = prefColumns;
        } else {
            columnsCount = Math.max(3, Math.min(5, (int) (AndroidUtilities.displaySize.x / AndroidUtilities.dp(118))));
        }

        FrameLayout rootLayout = new FrameLayout(context);
        rootLayout.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        fragmentView = rootLayout;

        // ---- channel filter chips ----
        chipsScroll = new HorizontalScrollView(context);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        chipsScroll.setVerticalScrollBarEnabled(false);
        chipsScroll.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        LinearLayout chipsWrapper = new LinearLayout(context);
        chipsWrapper.setOrientation(LinearLayout.HORIZONTAL);
        chipsScroll.addView(chipsWrapper, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT));
        chipsContainer = chipsWrapper;
        chipsScroll.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(88), Gravity.TOP));
        rootLayout.addView(chipsScroll);
        rebuildChips();

        // DrGram Fix 30: Instagram-style search bar — hashtags and @channel/group ids
        rootLayout.addView(createSearchRow(context));

        // DrGram Fix 31: media filter pills (All / Videos / Photos) + settings gear
        rootLayout.addView(createFilterRow(context));

        // ---- media grid ----
        listView = new RecyclerListView(context);
        layoutManager = new GridLayoutManager(context, columnsCount);
        listView.setLayoutManager(layoutManager);
        listView.setClipToPadding(false);
        listView.setPadding(AndroidUtilities.dp(2), 0, AndroidUtilities.dp(2), AndroidUtilities.dp(4));
        listView.setOnItemClickListener((view, position) -> {
            if (view instanceof DrGramExploreMediaCell) {
                openMedia((DrGramExploreMediaCell) view, position);
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            if (view instanceof DrGramExploreMediaCell) {
                openChannel((DrGramExploreMediaCell) view);
                return true;
            }
            return false;
        });
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if (dy > 0) {
                    lastChainTotal = -1; // real user scroll re-arms the chain
                }
                checkLoadMore();
            }

            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                // Instagram-style: keep the runway full after a fling settles
                if (newState == RecyclerView.SCROLL_STATE_IDLE
                        || newState == RecyclerView.SCROLL_STATE_SETTLING) {
                    checkLoadMore();
                }
            }
        });
        RecyclerView.ItemDecoration decoration = new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(android.graphics.Rect outRect, View view, RecyclerView parent, RecyclerView.State state) {
                outRect.set(AndroidUtilities.dp(2), AndroidUtilities.dp(2), AndroidUtilities.dp(2), AndroidUtilities.dp(2));
            }
        };
        listView.addItemDecoration(decoration);

        adapter = new ExploreAdapter(context);
        listView.setAdapter(adapter);
        FrameLayout.LayoutParams listParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        listParams.topMargin = AndroidUtilities.dp(88 + 48 + 42); // chips + search bar + filter row (Fix 30/31)
        rootLayout.addView(listView, listParams);
        // first open: if the already-loaded page fills (or under-fills) the
        // viewport, start pulling the next pages without waiting for a scroll
        listView.post(this::checkLoadMore);

        // ---- empty / loading state ----
        emptyTextView = new TextView(context);
        emptyTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        emptyTextView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyTextView.setGravity(Gravity.CENTER);
        emptyTextView.setPadding(AndroidUtilities.dp(40), 0, AndroidUtilities.dp(40), 0);
        emptyTextView.setVisibility(View.GONE);
        rootLayout.addView(emptyTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 0, 96, 0, 0));

        updateUIState();
        return fragmentView;
    }

    //region Data binding

    private void updateMedia(boolean resetSelectionIfMissing) {
        currentMedia = exploreController.getFilteredMedia(selectedChannelId);
        if (resetSelectionIfMissing && selectedChannelId != 0 && currentMedia.isEmpty() && !exploreController.isLoading()) {
            // channel may have lost its media (e.g. left) — fall back only when it has none
        }
    }

    /* Instagram-style infinite scroll: whenever the user is within 12 items
     * (~4 grid rows) of the loaded edge, the next page is fetched automatically.
     * The chain continues after every data update while the user is still parked
     * near the bottom, so the feed never stalls with a dead footer. */
    private void checkLoadMore() {
        if (listView == null || adapter == null || exploreController == null || layoutManager == null) {
            return;
        }
        int total = adapter.getItemCount();
        if (total <= 0) {
            return;
        }
        int lastVisible = layoutManager.findLastCompletelyVisibleItemPosition();
        int partial = layoutManager.findLastVisibleItemPosition();
        if (lastVisible == RecyclerView.NO_POSITION) {
            lastVisible = partial;
        } else if (partial != RecyclerView.NO_POSITION) {
            lastVisible = Math.max(lastVisible, partial);
        }
        if (lastVisible == RecyclerView.NO_POSITION || lastVisible < total - 12) {
            return;
        }
        if (!exploreController.hasMore() || exploreController.isLoading()) {
            return;
        }
        if (lastChainTotal == total) {
            // the previous automatic round added nothing (channels exhausted or
            // MAX_TOTAL_ITEMS cap) — stop the auto-chain until content changes
            return;
        }
        lastChainTotal = total;
        exploreController.loadMore();
    }

    private void postCheckLoadMore() {
        if (listView != null) {
            listView.post(this::checkLoadMore);
        }
    }

    private void updateUIState() {
        if (adapter == null) {
            return;
        }
        updateMedia(true);
        adapter.notifyDataSetChanged();
        boolean empty = currentMedia.isEmpty();
        if (empty) {
            emptyTextView.setVisibility(View.VISIBLE);
            if (exploreController.isSearchMode()) {
                // DrGram Fix 30/31: dedicated search states (global peers load async)
                boolean stillLoading = exploreController.isSearchLoading() || exploreController.isGlobalPeersLoading();
                emptyTextView.setText(LocaleController.getString(stillLoading
                        ? R.string.ExploreLoading : R.string.DrGramExploreSearchEmpty));
            } else if (exploreController.isLoading()) {
                emptyTextView.setText(LocaleController.getString(R.string.ExploreLoading));
            } else {
                emptyTextView.setText(LocaleController.getString(R.string.ExploreEmpty));
            }
        } else {
            emptyTextView.setVisibility(View.GONE);
        }
    }

    private void rebuildChips() {
        if (chipsContainer == null) {
            return;
        }
        Context context = getContext();
        if (context == null) {
            return;
        }
        chipsContainer.removeAllViews();

        // DrGram Fix 30: while a search is active the chip row shows the query + clear
        if (exploreController.isSearchMode()) {
            chipsContainer.addView(makeClearResultsChip(context));
            chipsContainer.addView(makeQueryChip(context, exploreController.getSearchQuery()));
            // DrGram Fix 31: global public channels/groups found for a plain text query
            ArrayList<TLRPC.Chat> globalPeers = exploreController.getGlobalPeers();
            if (!globalPeers.isEmpty()) {
                chipsContainer.addView(makeGlobalLabelChip(context));
                for (int a = 0; a < globalPeers.size(); a++) {
                    chipsContainer.addView(makeGlobalPeerChip(context, globalPeers.get(a)));
                }
            }
            chipsContainer.requestLayout();
            lastGlobalChipCount = globalPeers.size();
            return;
        }

        ArrayList<DrGramExploreController.ChannelInfo> channels = exploreController.getChannels();

        // verify the selected channel still exists, otherwise reset to "All"
        if (selectedChannelId != 0) {
            boolean found = false;
            for (int a = 0; a < channels.size(); a++) {
                if (channels.get(a).dialogId == selectedChannelId) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                selectedChannelId = 0;
            }
        }

        chipsContainer.addView(makeAllChip(context));
        for (int a = 0; a < channels.size(); a++) {
            chipsContainer.addView(makeChannelChip(context, channels.get(a)));
        }
        if (exploreController.getHiddenCount() > 0) {
            chipsContainer.addView(makeHiddenChip(context)); // DrGram Fix 30: hidden channels manager
        }
        chipsContainer.requestLayout();
        lastGlobalChipCount = 0;
    }

    private LinearLayout makeAllChip(Context context) {
        LinearLayout cell = new LinearLayout(context);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(AndroidUtilities.dp(7), AndroidUtilities.dp(6), AndroidUtilities.dp(7), AndroidUtilities.dp(4));
        cell.setOnClickListener(v -> {
            if (selectedChannelId != 0) {
                selectedChannelId = 0;
                rebuildChips();
                updateUIState();
            }
        });

        FrameLayout avatarHolder = new FrameLayout(context);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke(AndroidUtilities.dp(2), selectedChannelId == 0 ? GOLD : 0x00000000);
        avatarHolder.setBackgroundDrawable(ring);
        avatarHolder.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(4));

        ImageView gemView = new ImageView(context);
        gemView.setImageResource(R.drawable.drgram_explore);
        gemView.setColorFilter(GOLD);
        avatarHolder.addView(gemView, LayoutHelper.createFrame(24, 24, Gravity.CENTER));

        TextView name = new TextView(context);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        name.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        name.setTypeface(AndroidUtilities.bold());
        name.setMaxLines(1);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(0, AndroidUtilities.dp(4), 0, 0);
        name.setText(LocaleController.getString(R.string.ExploreFilterAll));

        cell.addView(avatarHolder, LayoutHelper.createLinear(56, 56));
        cell.addView(name, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
        return cell;
    }

    private LinearLayout makeChannelChip(Context context, DrGramExploreController.ChannelInfo info) {
        LinearLayout cell = new LinearLayout(context);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(AndroidUtilities.dp(7), AndroidUtilities.dp(6), AndroidUtilities.dp(7), AndroidUtilities.dp(4));
        cell.setOnClickListener(v -> {
            selectedChannelId = info.dialogId;
            rebuildChips();
            updateUIState();
        });
        // DrGram Fix 30: HOLD on a channel chip -> delete / hide options
        cell.setOnLongClickListener(v -> {
            drgramShowChannelOptions(info);
            return true;
        });

        FrameLayout avatarHolder = new FrameLayout(context);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke(AndroidUtilities.dp(2), selectedChannelId == info.dialogId ? GOLD : 0x00000000);
        avatarHolder.setBackgroundDrawable(ring);
        avatarHolder.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(4));

        BackupImageView imageView = new BackupImageView(context);
        imageView.setRoundRadius(AndroidUtilities.dp(24));
        AvatarDrawable avatarDrawable = new AvatarDrawable(info.chat);
        imageView.setForUserOrChat(info.chat, avatarDrawable);
        avatarHolder.addView(imageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        TextView name = new TextView(context);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        name.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        name.setMaxLines(1);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setMaxWidth(AndroidUtilities.dp(62));
        name.setPadding(0, AndroidUtilities.dp(4), 0, 0);
        name.setText(info.chat.title);

        cell.addView(avatarHolder, LayoutHelper.createLinear(56, 56));
        cell.addView(name, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
        return cell;
    }
    //endregion

    //region Media opening

    private void openMedia(DrGramExploreMediaCell cell, int position) {
        if (currentMedia == null || currentMedia.isEmpty() || position >= currentMedia.size()) {
            return;
        }
        // DrGram Fix 30: a post the user opens never returns to the feed
        try {
            MessageObject opened = currentMedia.get(position);
            if (opened != null) {
                DrGramExploreSeen.markSeen(currentAccount, opened.getDialogId(), opened.getId());
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        // DrGram Fix 25: pass the fragment so parentFragment is real (needed for reactions/share);
        // activity resolves itself from fragment.getParentActivity()
        PhotoViewer.getInstance().setParentActivity(null, DrGramExploreActivity.this, null);
        // DrGram Fix 19: Reels-style vertical navigation + autoplay
        PhotoViewer.getInstance().setDrgramVerticalNav(true);
        PhotoViewer.getInstance().openPhoto(currentMedia, position, 0, 0, 0, provider);
    }

    private void openChannel(DrGramExploreMediaCell cell) {
        MessageObject messageObject = cell.getMessageObject();
        if (messageObject == null) {
            return;
        }
        long dialogId = messageObject.getDialogId();
        Bundle args = new Bundle();
        args.putLong("chat_id", -dialogId);
        presentFragment(new ChatActivity(args));
    }

    private final PhotoViewer.EmptyPhotoViewerProvider provider = new PhotoViewer.EmptyPhotoViewerProvider() {
        @Override
        public PhotoViewer.PlaceProviderObject getPlaceForPhoto(MessageObject messageObject, TLRPC.FileLocation fileLocation, int index, boolean needPreview, boolean closing) {
            if (listView == null || messageObject == null) {
                return null;
            }
            int count = listView.getChildCount();
            for (int a = 0; a < count; a++) {
                View view = listView.getChildAt(a);
                if (view instanceof DrGramExploreMediaCell) {
                    DrGramExploreMediaCell cell = (DrGramExploreMediaCell) view;
                    MessageObject message = cell.getMessageObject();
                    if (message == null || messageObject.getId() != message.getId() || messageObject.getDialogId() != message.getDialogId()) {
                        continue;
                    }
                    int[] coords = new int[2];
                    cell.getLocationInWindow(coords);
                    PhotoViewer.PlaceProviderObject object = new PhotoViewer.PlaceProviderObject();
                    object.viewX = coords[0];
                    object.viewY = coords[1];
                    object.parentView = listView;
                    object.imageReceiver = cell.getImageReceiver();
                    object.thumb = object.imageReceiver.getBitmapSafe();
                    object.radius = object.imageReceiver.getRoundRadius(true);
                    object.clipTopAddition = 0;
                    object.clipBottomAddition = 0;
                    return object;
                }
            }
            return null;
        }

        @Override
        public void willSwitchFromPhoto(MessageObject messageObject, TLRPC.FileLocation fileLocation, int index) {
            // DrGram Fix 30: every post swiped to in the Reels viewer counts as seen
            try {
                if (messageObject != null && messageObject.getDialogId() < 0) {
                    DrGramExploreSeen.markSeen(currentAccount, messageObject.getDialogId(), messageObject.getId());
                }
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }

        @Override
        public void onClose() {
            // DrGram Fix 30: once the viewer is gone, drop seen posts from the grid
            try {
                if (listView != null) {
                    listView.postDelayed(() -> updateUIState(), 420);
                }
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
    };
    //endregion

    //region DrGram Fix 30: search bar + channel options

    private View createSearchRow(Context context) {
        FrameLayout searchRow = new FrameLayout(context);
        searchRow.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));

        FrameLayout capsule = new FrameLayout(context);
        GradientDrawable capsuleBg = new GradientDrawable();
        capsuleBg.setCornerRadius(AndroidUtilities.dp(23));
        capsuleBg.setColor(getThemedColor(Theme.key_graySection));
        capsuleBg.setStroke(AndroidUtilities.dp(1), 0x33F5B90D);
        capsule.setBackgroundDrawable(capsuleBg);
        searchRow.addView(capsule, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, AndroidUtilities.dp(40), Gravity.CENTER, 8, 4, 8, 4));

        ImageView searchIcon = new ImageView(context);
        searchIcon.setImageResource(R.drawable.outline_search_1_24);
        searchIcon.setColorFilter(GOLD);
        capsule.addView(searchIcon, LayoutHelper.createFrame(20, 20, Gravity.CENTER_VERTICAL, 14, 0, 0, 0));

        searchField = new EditText(context);
        searchField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        searchField.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        searchField.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        searchField.setHint(LocaleController.getString(R.string.DrGramExploreSearchHint));
        searchField.setSingleLine(true);
        searchField.setBackgroundDrawable(null);
        searchField.setPadding(0, 0, 0, 0);
        searchField.setInputType(InputType.TYPE_CLASS_TEXT);
        searchField.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        capsule.addView(searchField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER_VERTICAL, 42, 0, 10, 0));
        searchField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                submitSearch(searchField.getText().toString());
                AndroidUtilities.hideKeyboard(searchField);
                return true;
            }
            return false;
        });

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(48), Gravity.TOP);
        params.topMargin = AndroidUtilities.dp(88);
        searchRow.setLayoutParams(params);
        return searchRow;
    }

    private void submitSearch(String raw) {
        String query = raw == null ? "" : raw.trim();
        if (query.isEmpty()) {
            clearSearchResults();
            return;
        }
        String username = extractUsername(query);
        if (username != null) {
            drgramResolveAndSearch(username);
            return;
        }
        selectedChannelId = 0;
        exploreController.searchHashtag(query);
        // DrGram Fix 31: a plain text/sentence/id now searches ALL of Telegram —
        // public channels and groups are fetched via contacts.search and shown
        // as avatar chips above the caption/photo results. No # or t.me needed.
        exploreController.searchGlobalPeers(query);
        rebuildChips();
        updateUIState();
    }

    private static String extractUsername(String query) {
        String candidate;
        if (query.startsWith("@")) {
            candidate = query.substring(1).trim();
        } else {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("(?i)(?:https?://)?(?:t\\.me|telegram\\.me)/([A-Za-z0-9_]{4,64})/?")
                    .matcher(query);
            candidate = matcher.matches() ? matcher.group(1) : null;
        }
        if (candidate == null || !candidate.matches("[A-Za-z0-9_]{4,64}")) {
            return null;
        }
        return candidate;
    }

    private void drgramResolveAndSearch(String username) {
        if (getParentActivity() == null) {
            return;
        }
        selectedChannelId = 0;
        rebuildChips();
        updateUIState();
        exploreController.beginSearch("@" + username);
        TLRPC.TL_contacts_resolveUsername request = new TLRPC.TL_contacts_resolveUsername();
        request.username = username;
        getConnectionsManager().sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (fragmentView == null || !exploreController.isSearchMode()) {
                return;
            }
            if (response instanceof TLRPC.TL_contacts_resolvedPeer) {
                TLRPC.TL_contacts_resolvedPeer resolved = (TLRPC.TL_contacts_resolvedPeer) response;
                getMessagesController().putUsers(resolved.users, false);
                getMessagesController().putChats(resolved.chats, false);
                TLRPC.Peer peer = resolved.peer;
                TLRPC.Chat chat = peer != null && peer.channel_id != 0 ? getMessagesController().getChat(peer.channel_id) : null;
                if (chat != null && ChatObject.isChannel(chat)) {
                    exploreController.searchChannel(-chat.id, "@" + username);
                    rebuildChips();
                    updateUIState();
                    return;
                }
                Toast.makeText(getParentActivity(), LocaleController.getString(R.string.DrGramExploreSearchUserOnly), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(getParentActivity(), LocaleController.getString(R.string.DrGramExploreSearchNoPeer), Toast.LENGTH_SHORT).show();
            }
            exploreController.clearSearch();
            rebuildChips();
            updateUIState();
        }), ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    private void clearSearchResults() {
        exploreController.clearSearch();
        if (searchField != null) {
            searchField.setText("");
        }
        rebuildChips();
        updateUIState();
    }

    //region DrGram Fix 31: media filter row + settings gear

    private View createFilterRow(Context context) {
        FrameLayout row = new FrameLayout(context);
        row.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));

        filterPills = new LinearLayout(context);
        filterPills.setOrientation(LinearLayout.HORIZONTAL);
        filterPills.setGravity(Gravity.CENTER_VERTICAL);
        filterPills.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(4), AndroidUtilities.dp(8), AndroidUtilities.dp(6));
        fillFilterPills(context);
        row.addView(filterPills, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL));

        // Explore settings gear — restores hidden channels, media filter, seen posts
        ImageView gear = new ImageView(context);
        gear.setImageResource(R.drawable.msg_settings);
        gear.setColorFilter(GOLD);
        GradientDrawable gearBg = new GradientDrawable();
        gearBg.setShape(GradientDrawable.OVAL);
        gearBg.setColor(0x22F5B90D);
        gear.setBackgroundDrawable(gearBg);
        int pad = AndroidUtilities.dp(7);
        gear.setPadding(pad, pad, pad, pad);
        gear.setOnClickListener(v -> {
            try {
                presentFragment(new DrGramExploreSettingsActivity());
            } catch (Throwable t) {
                FileLog.e(t);
            }
        });
        FrameLayout.LayoutParams gearParams = new FrameLayout.LayoutParams(AndroidUtilities.dp(34), AndroidUtilities.dp(34), Gravity.END | Gravity.CENTER_VERTICAL);
        gearParams.rightMargin = AndroidUtilities.dp(10);
        row.addView(gear, gearParams);

        FrameLayout.LayoutParams rowParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(42), Gravity.TOP);
        rowParams.topMargin = AndroidUtilities.dp(136);
        row.setLayoutParams(rowParams);
        return row;
    }

    private void fillFilterPills(Context context) {
        filterPills.removeAllViews();
        filterPills.addView(makeFilterPill(context, LocaleController.getString(R.string.ExploreFilterAll), DrGramExploreController.FILTER_ALL));
        filterPills.addView(makeFilterPill(context, LocaleController.getString(R.string.DrGramExploreFilterVideos), DrGramExploreController.FILTER_VIDEOS));
        filterPills.addView(makeFilterPill(context, LocaleController.getString(R.string.DrGramExploreFilterPhotos), DrGramExploreController.FILTER_PHOTOS));
    }

    private TextView makeFilterPill(Context context, String title, int filter) {
        TextView pill = new TextView(context);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        pill.setSingleLine(true);
        pill.setTypeface(AndroidUtilities.bold());
        int padH = AndroidUtilities.dp(14);
        int padV = AndroidUtilities.dp(6);
        pill.setPadding(padH, padV, padH, padV);
        boolean active = exploreController.getMediaFilter() == filter;
        pill.setText(title);
        pill.setTextColor(active ? 0xFF141005 : GOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(20));
        bg.setColor(active ? GOLD : 0x00000000);
        bg.setStroke(AndroidUtilities.dp(1), active ? GOLD : 0x33F5B90D);
        pill.setBackgroundDrawable(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = AndroidUtilities.dp(8);
        pill.setLayoutParams(lp);
        pill.setOnClickListener(v -> {
            if (exploreController.getMediaFilter() != filter) {
                exploreController.setMediaFilter(filter);
                if (getContext() != null) {
                    fillFilterPills(getContext());
                }
                updateUIState();
            }
        });
        return pill;
    }
    //endregion

    //region DrGram Fix 31: global peer chips

    private LinearLayout makeGlobalLabelChip(Context context) {
        LinearLayout cell = new LinearLayout(context);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(AndroidUtilities.dp(7), AndroidUtilities.dp(6), AndroidUtilities.dp(7), AndroidUtilities.dp(4));

        FrameLayout avatarHolder = new FrameLayout(context);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0x22F5B90D);
        circle.setStroke(AndroidUtilities.dp(2), GOLD);
        avatarHolder.setBackgroundDrawable(circle);

        ImageView globe = new ImageView(context);
        globe.setImageResource(R.drawable.outline_search_1_24);
        globe.setColorFilter(GOLD);
        avatarHolder.addView(globe, LayoutHelper.createFrame(24, 24, Gravity.CENTER));

        TextView label = new TextView(context);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        label.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        label.setPadding(0, AndroidUtilities.dp(4), 0, 0);
        label.setText(LocaleController.getString(R.string.DrGramExploreGlobalResults));

        cell.addView(avatarHolder, LayoutHelper.createLinear(56, 56));
        cell.addView(label, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
        return cell;
    }

    private LinearLayout makeGlobalPeerChip(Context context, TLRPC.Chat chat) {
        LinearLayout cell = new LinearLayout(context);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(AndroidUtilities.dp(7), AndroidUtilities.dp(6), AndroidUtilities.dp(7), AndroidUtilities.dp(4));
        cell.setOnClickListener(v -> {
            try {
                Bundle args = new Bundle();
                args.putLong("chat_id", chat.id);
                presentFragment(new ChatActivity(args));
            } catch (Throwable t) {
                FileLog.e(t);
            }
        });

        FrameLayout avatarHolder = new FrameLayout(context);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke(AndroidUtilities.dp(2), GOLD);
        avatarHolder.setBackgroundDrawable(ring);
        avatarHolder.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(4));

        BackupImageView imageView = new BackupImageView(context);
        imageView.setRoundRadius(AndroidUtilities.dp(24));
        AvatarDrawable avatarDrawable = new AvatarDrawable(chat);
        imageView.setForUserOrChat(chat, avatarDrawable);
        avatarHolder.addView(imageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        TextView name = new TextView(context);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        name.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        name.setMaxLines(1);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setMaxWidth(AndroidUtilities.dp(62));
        name.setPadding(0, AndroidUtilities.dp(4), 0, 0);
        name.setText(chat.title);

        cell.addView(avatarHolder, LayoutHelper.createLinear(56, 56));
        cell.addView(name, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
        return cell;
    }
    //endregion

    private LinearLayout makeClearResultsChip(Context context) {
        LinearLayout cell = new LinearLayout(context);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(AndroidUtilities.dp(7), AndroidUtilities.dp(6), AndroidUtilities.dp(7), AndroidUtilities.dp(4));
        cell.setOnClickListener(v -> clearSearchResults());

        FrameLayout avatarHolder = new FrameLayout(context);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0x22F5B90D);
        circle.setStroke(AndroidUtilities.dp(2), GOLD);
        avatarHolder.setBackgroundDrawable(circle);

        TextView clear = new TextView(context);
        clear.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        clear.setTextColor(GOLD);
        clear.setGravity(Gravity.CENTER);
        clear.setText("✕");
        avatarHolder.addView(clear, LayoutHelper.createFrame(24, 24, Gravity.CENTER));

        TextView label = new TextView(context);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        label.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        label.setTypeface(AndroidUtilities.bold());
        label.setPadding(0, AndroidUtilities.dp(4), 0, 0);
        label.setText(LocaleController.getString(R.string.DrGramExploreClearResults));

        cell.addView(avatarHolder, LayoutHelper.createLinear(56, 56));
        cell.addView(label, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
        return cell;
    }

    private LinearLayout makeQueryChip(Context context, String query) {
        LinearLayout cell = new LinearLayout(context);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(AndroidUtilities.dp(7), AndroidUtilities.dp(6), AndroidUtilities.dp(7), AndroidUtilities.dp(4));
        cell.setOnClickListener(v -> {
            if (searchField != null) {
                searchField.requestFocus();
            }
        });

        FrameLayout avatarHolder = new FrameLayout(context);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke(AndroidUtilities.dp(2), GOLD);
        avatarHolder.setBackgroundDrawable(ring);

        TextView glyph = new TextView(context);
        glyph.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        glyph.setTextColor(GOLD);
        glyph.setGravity(Gravity.CENTER);
        glyph.setText("✦");
        avatarHolder.addView(glyph, LayoutHelper.createFrame(24, 24, Gravity.CENTER));

        TextView label = new TextView(context);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        label.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        label.setMaxLines(1);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setMaxWidth(AndroidUtilities.dp(150));
        label.setPadding(0, AndroidUtilities.dp(4), 0, 0);
        label.setText(LocaleController.getString(R.string.DrGramExploreResultsFor) + " " + (query == null ? "" : query));

        cell.addView(avatarHolder, LayoutHelper.createLinear(56, 56));
        cell.addView(label, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
        return cell;
    }

    private LinearLayout makeHiddenChip(Context context) {
        LinearLayout cell = new LinearLayout(context);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(AndroidUtilities.dp(7), AndroidUtilities.dp(6), AndroidUtilities.dp(7), AndroidUtilities.dp(4));
        cell.setOnClickListener(v -> drgramManageHidden());

        FrameLayout avatarHolder = new FrameLayout(context);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0x1F888888);
        circle.setStroke(AndroidUtilities.dp(2), 0x66888888);
        avatarHolder.setBackgroundDrawable(circle);

        ImageView hiddenIcon = new ImageView(context);
        hiddenIcon.setImageResource(R.drawable.mini_ephemeral_hidden_16);
        hiddenIcon.setColorFilter(0xFF888888);
        avatarHolder.addView(hiddenIcon, LayoutHelper.createFrame(24, 24, Gravity.CENTER));

        TextView label = new TextView(context);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        label.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        label.setPadding(0, AndroidUtilities.dp(4), 0, 0);
        label.setText(LocaleController.getString(R.string.DrGramExploreHiddenChip));

        cell.addView(avatarHolder, LayoutHelper.createLinear(56, 56));
        cell.addView(label, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
        return cell;
    }

    private void drgramManageHidden() {
        if (getParentActivity() == null) {
            return;
        }
        ArrayList<Long> hidden = exploreController.getHiddenList();
        if (hidden.isEmpty()) {
            return;
        }
        ArrayList<String> names = new ArrayList<>();
        for (int a = 0; a < hidden.size(); a++) {
            TLRPC.Chat chat = getMessagesController().getChat(-hidden.get(a));
            names.add(chat != null && !TextUtils.isEmpty(chat.title) ? chat.title : ("#" + hidden.get(a)));
        }
        new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.DrGramExploreHiddenChip))
                .setItems(names.toArray(new String[0]), (dialog, which) -> {
                    if (which >= 0 && which < hidden.size()) {
                        exploreController.setHidden(hidden.get(which), false);
                        rebuildChips();
                        exploreController.refreshIfNeeded(true);
                    }
                })
                .setNeutralButton(LocaleController.getString(R.string.DrGramExploreUnhideAll), (dialog, which) -> {
                    ArrayList<Long> all = exploreController.getHiddenList();
                    for (int a = 0; a < all.size(); a++) {
                        exploreController.setHidden(all.get(a), false);
                    }
                    rebuildChips();
                    exploreController.refreshIfNeeded(true);
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private void drgramShowChannelOptions(DrGramExploreController.ChannelInfo info) {
        if (getParentActivity() == null || info == null || info.chat == null) {
            return;
        }
        String[] items = {
                LocaleController.getString(R.string.DrGramExploreDeleteChannel),
                LocaleController.getString(R.string.DrGramExploreHideChannel)
        };
        new AlertDialog.Builder(getParentActivity())
                .setTitle(info.chat.title)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        drgramConfirmDeleteChannel(info);
                    } else if (which == 1) {
                        drgramHideChannel(info);
                    }
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private void drgramConfirmDeleteChannel(DrGramExploreController.ChannelInfo info) {
        if (getParentActivity() == null) {
            return;
        }
        new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.DrGramExploreDeleteChannel))
                .setMessage(LocaleController.formatString("DrGramExploreDeleteChannelDesc", R.string.DrGramExploreDeleteChannelDesc, info.chat.title))
                .setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) -> drgramDeleteChannel(info))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private void drgramDeleteChannel(DrGramExploreController.ChannelInfo info) {
        if (info == null || info.chat == null) {
            return;
        }
        try {
            TLRPC.Chat chat = getMessagesController().getChat(info.chat.id);
            if (chat == null) {
                chat = info.chat;
            }
            TLObject request;
            if (chat.creator) {
                TLRPC.TL_channels_deleteChannel delete = new TLRPC.TL_channels_deleteChannel();
                delete.channel = getMessagesController().getInputChannel(chat);
                request = delete;
            } else {
                TLRPC.TL_channels_leaveChannel leave = new TLRPC.TL_channels_leaveChannel();
                leave.channel = getMessagesController().getInputChannel(chat);
                request = leave;
            }
            final long dialogId = info.dialogId;
            getConnectionsManager().sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                try {
                    if (response instanceof TLRPC.Updates) {
                        getMessagesController().processUpdates((TLRPC.Updates) response, false);
                    }
                    getMessagesController().deleteDialog(dialogId, 0, false);
                    if (getParentActivity() != null) {
                        Toast.makeText(getParentActivity(), LocaleController.getString(R.string.DrGramExploreLeftDone), Toast.LENGTH_SHORT).show();
                    }
                    selectedChannelId = 0;
                    rebuildChips();
                    exploreController.refresh();
                } catch (Throwable t) {
                    FileLog.e(t);
                }
            }), ConnectionsManager.RequestFlagInvokeAfter);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private void drgramHideChannel(DrGramExploreController.ChannelInfo info) {
        if (info == null) {
            return;
        }
        exploreController.setHidden(info.dialogId, true);
        if (selectedChannelId == info.dialogId) {
            selectedChannelId = 0;
        }
        rebuildChips();
        updateUIState();
        if (getParentActivity() != null) {
            Toast.makeText(getParentActivity(), LocaleController.getString(R.string.DrGramExploreHideDone), Toast.LENGTH_SHORT).show();
        }
    }
    //endregion

    //region NotificationCenter

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.drgramExploreUpdated) {
            updateUIState();
            // keep the chain alive: after each loaded page, if the user is still
            // near the bottom, immediately pull the next page (Instagram-style)
            postCheckLoadMore();
            // DrGram Fix 31: global peer chips arrive asynchronously — refresh the
            // chip row only when their count actually changed (cheap guard, avoids
            // rebuilding the row on every feed page)
            int globalCount = exploreController.getGlobalPeers().size();
            if (globalCount != lastGlobalChipCount) {
                rebuildChips();
            }
        } else if (id == NotificationCenter.dialogsNeedReload) {
            rebuildChips();
            exploreController.refreshIfNeeded(false);
        }
    }
    //endregion

    //region Adapter

    private class ExploreAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int TYPE_MEDIA = 0;
        private static final int TYPE_FOOTER = 1;

        private final Context context;

        public ExploreAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemViewType(int position) {
            if (position < currentMedia.size()) {
                return TYPE_MEDIA;
            }
            return TYPE_FOOTER;
        }

        @Override
        public int getItemCount() {
            int count = currentMedia.size();
            if (exploreController.isLoading() || (exploreController.hasMore() && !currentMedia.isEmpty())) {
                count++;
            }
            return count;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            if (viewType == TYPE_MEDIA) {
                view = new DrGramExploreMediaCell(context);
                view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                view = new FooterLoadingView(context);
                view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(48)));
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() == TYPE_MEDIA) {
                ((DrGramExploreMediaCell) holder.itemView).setMessageObject(currentMedia.get(position));
            }
        }
    }

    private static class FooterLoadingView extends FrameLayout {
        public FooterLoadingView(Context context) {
            super(context);
            org.telegram.ui.Components.RadialProgressView progressView = new org.telegram.ui.Components.RadialProgressView(context);
            progressView.setSize(AndroidUtilities.dp(22));
            addView(progressView, LayoutHelper.createFrame(28, 28, Gravity.CENTER));
        }
    }
    //endregion
    // DrGram Fix 13: self-managed edge-to-edge. These pages previously relied on
    // the fragment container padding, which left the last rows behind the system
    // navigation bar — the page could not be scrolled to its very bottom.
    // Opting into edge-to-edge + padding the scroll content with the real navbar
    // inset makes every row reachable (and lets content scroll under the pill).
    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        super.onInsets(left, top, right, bottom);
        if (fragmentView instanceof ViewGroup) {
            ViewGroup root = (ViewGroup) fragmentView;
            ViewGroup target = root;
            if (root instanceof android.widget.ScrollView
                    && root.getChildCount() > 0
                    && root.getChildAt(0) instanceof ViewGroup) {
                target = (ViewGroup) root.getChildAt(0);
            }
            int newBottom = Math.max(target.getPaddingBottom(), bottom + AndroidUtilities.dp(16));
            target.setPadding(target.getPaddingLeft(), target.getPaddingTop(), target.getPaddingRight(), newBottom);
            target.setClipToPadding(false);
        }
    }
}
