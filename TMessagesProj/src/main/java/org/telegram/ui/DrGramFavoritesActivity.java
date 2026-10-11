/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Favorites screen — lists the messages starred on this device.
 * Tap a row to jump straight to the original message inside its chat;
 * long-press to take it out of the list.
 */
package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.DrGramFavorites;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Locale;

public class DrGramFavoritesActivity extends BaseFragment {

    private TextView emptyTextView;
    private FavoritesAdapter adapter;

    public DrGramFavoritesActivity() {
        super();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.DrGramFavTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout rootLayout = new FrameLayout(context);
        rootLayout.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        fragmentView = rootLayout;

        emptyTextView = new TextView(context);
        emptyTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        emptyTextView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyTextView.setGravity(Gravity.CENTER);
        emptyTextView.setPadding(AndroidUtilities.dp(40), 0, AndroidUtilities.dp(40), 0);
        emptyTextView.setText(LocaleController.getString(R.string.DrGramFavEmpty));
        rootLayout.addView(emptyTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        RecyclerListView listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setClipToPadding(false);
        listView.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8));
        listView.setAdapter(adapter = new FavoritesAdapter(context));
        listView.setOnItemClickListener((view, position) -> {
            DrGramFavorites.Entry entry = adapter.entryAt(position);
            if (entry == null) {
                return;
            }
            Bundle args = new Bundle();
            args.putLong("dialog_id", entry.dialogId);
            args.putInt("message_id", entry.msgId);
            presentFragment(new ChatActivity(args));
        });
        listView.setOnItemLongClickListener((view, position) -> {
            DrGramFavorites.Entry entry = adapter.entryAt(position);
            if (entry == null) {
                return false;
            }
            confirmRemove(entry);
            return true;
        });
        rootLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        updateEmptyState();
        return fragmentView;
    }

    private void updateEmptyState() {
        if (adapter == null || emptyTextView == null) {
            return;
        }
        emptyTextView.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
    }

    private void confirmRemove(DrGramFavorites.Entry entry) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.DrGramFavRemove));
        String name = TextUtils.isEmpty(entry.title) ? LocaleController.getString(R.string.DrGramFavTitle) : entry.title;
        builder.setMessage(LocaleController.formatString(R.string.DrGramFavRemoveText, name));
        builder.setPositiveButton(LocaleController.getString(R.string.DrGramFavRemove), (dialog, which) -> {
            DrGramFavorites.getInstance(currentAccount).remove(entry.dialogId, entry.msgId);
            adapter.reload();
            updateEmptyState();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.show();
    }

    private class FavoritesAdapter extends RecyclerView.Adapter<FavoritesAdapter.RowHolder> {

        private final Context context;
        private final ArrayList<DrGramFavorites.Entry> items = new ArrayList<>();

        FavoritesAdapter(Context context) {
            this.context = context;
            reload();
        }

        void reload() {
            items.clear();
            items.addAll(DrGramFavorites.getInstance(currentAccount).getList());
            notifyDataSetChanged();
        }

        DrGramFavorites.Entry entryAt(int position) {
            return position >= 0 && position < items.size() ? items.get(position) : null;
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @Override
        public RowHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout cell = new LinearLayout(context);
            cell.setOrientation(LinearLayout.HORIZONTAL);
            cell.setGravity(Gravity.CENTER_VERTICAL);
            int pad = AndroidUtilities.dp(16);
            cell.setPadding(pad, pad / 2, pad, pad / 2);
            cell.setBackgroundDrawable(Theme.getSelectorDrawable(false));

            BackupImageView avatar = new BackupImageView(context);
            avatar.setId(1);

            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(context);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            name.setId(2);
            name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            TextView snippet = new TextView(context);
            snippet.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            snippet.setId(3);
            snippet.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            snippet.setMaxLines(1);
            snippet.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(name);
            texts.addView(snippet);

            cell.addView(avatar, LayoutHelper.createLinear(42, 42, Gravity.CENTER_VERTICAL));
            cell.addView(texts, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 14, 0, 0, 0));
            return new RowHolder(cell);
        }

        @Override
        public void onBindViewHolder(RowHolder holder, int position) {
            DrGramFavorites.Entry entry = items.get(position);
            MessagesController messagesController = MessagesController.getInstance(currentAccount);
            AvatarDrawable avatarDrawable = new AvatarDrawable();
            TLObject avatarObject = null;
            if (entry.dialogId > 0) {
                TLRPC.User user = messagesController.getUser(entry.dialogId);
                if (user != null) {
                    avatarDrawable.setInfo(user);
                    avatarObject = user;
                }
            } else {
                TLRPC.Chat chat = messagesController.getChat(-entry.dialogId);
                if (chat != null) {
                    avatarDrawable.setInfo(chat);
                    avatarObject = chat;
                }
            }
            holder.avatar.setForUserOrChat(avatarObject, avatarDrawable);
            holder.name.setText(TextUtils.isEmpty(entry.title) ? String.format(Locale.US, "id %d", entry.dialogId) : entry.title);
            if (TextUtils.isEmpty(entry.snippet)) {
                holder.snippet.setText(DateFormat.format("yyyy/MM/dd", entry.date).toString());
            } else {
                holder.snippet.setText(entry.snippet);
            }
        }

        class RowHolder extends RecyclerView.ViewHolder {
            BackupImageView avatar;
            TextView name;
            TextView snippet;

            RowHolder(View itemView) {
                super(itemView);
                avatar = itemView.findViewById(1);
                name = itemView.findViewById(2);
                snippet = itemView.findViewById(3);
            }
        }
    }
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
