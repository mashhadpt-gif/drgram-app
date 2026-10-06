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
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DrGramExploreController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
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

    private long selectedChannelId; // 0 = all channels
    private ArrayList<MessageObject> currentMedia = new ArrayList<>();

    private int columnsCount = 3;

    public DrGramExploreActivity() {
        super();
        exploreController = DrGramExploreController.getInstance(currentAccount);
    }

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        getNotificationCenter().addObserver(this, NotificationCenter.dialogsDidLoad);
        getNotificationCenter().addObserver(this, NotificationCenter.drgramExploreUpdated);
        exploreController = DrGramExploreController.getInstance(currentAccount);
        exploreController.refreshIfNeeded(false);
        updateMedia(true);
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        getNotificationCenter().removeObserver(this, NotificationCenter.dialogsDidLoad);
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

        columnsCount = Math.max(3, Math.min(5, (int) (AndroidUtilities.displaySize.x / AndroidUtilities.dp(118))));

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
                checkLoadMore();
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
        listParams.topMargin = AndroidUtilities.dp(88);
        rootLayout.addView(listView, listParams);

        // ---- empty / loading state ----
        emptyTextView = new TextView(context);
        emptyTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        emptyTextView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyTextView.setGravity(Gravity.CENTER);
        emptyTextView.setPadding(AndroidUtilities.dp(40), 0, AndroidUtilities.dp(40), 0);
        emptyTextView.setVisibility(View.GONE);
        rootLayout.addView(emptyTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 0, 44, 0, 0));

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

    private void checkLoadMore() {
        if (listView == null || adapter == null || exploreController == null) {
            return;
        }
        int lastVisible = layoutManager.findLastCompletelyVisibleItemPosition();
        if (lastVisible == RecyclerView.NO_POSITION) {
            lastVisible = layoutManager.findLastVisibleItemPosition();
        }
        int total = adapter.getItemCount();
        if (total > 0 && lastVisible >= total - 9) {
            if (exploreController.hasMore() && !exploreController.isLoading()) {
                exploreController.loadMore();
            }
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
            if (exploreController.isLoading()) {
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
        chipsContainer.requestLayout();
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
        PhotoViewer.getInstance().setParentActivity(DrGramExploreActivity.this);
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
    };
    //endregion

    //region NotificationCenter

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.drgramExploreUpdated) {
            updateUIState();
        } else if (id == NotificationCenter.dialogsDidLoad) {
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
}
