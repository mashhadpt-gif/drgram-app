/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Vault screen — lists chats hidden on this device. Opened either
 * from DrGram Tools or by typing the vault code into the main search field.
 * Long-press a row to put the chat back into the normal list.
 */
package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
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
import org.telegram.messenger.UserObject;
import org.telegram.messenger.DrGramVault;
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

public class DrGramVaultActivity extends BaseFragment {

    private RecyclerListView listView;
    private TextView emptyTextView;
    private VaultAdapter adapter;

    public DrGramVaultActivity() {
        super();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.DrGramVaultTitle));
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
        emptyTextView.setText(LocaleController.getString(R.string.DrGramVaultEmpty));
        rootLayout.addView(emptyTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setClipToPadding(false);
        listView.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8));
        listView.setAdapter(adapter = new VaultAdapter(context));
        listView.setOnItemClickListener((view, position) -> {
            long dialogId = adapter.dialogIdAt(position);
            if (dialogId == 0) {
                return;
            }
            Bundle args = new Bundle();
            if (dialogId > 0) {
                args.putLong("user_id", dialogId);
            } else {
                args.putLong("chat_id", -dialogId);
            }
            presentFragment(new ChatActivity(args));
        });
        listView.setOnItemLongClickListener((view, position) -> {
            long dialogId = adapter.dialogIdAt(position);
            if (dialogId == 0) {
                return false;
            }
            confirmUnhide(dialogId, adapter.titleAt(position));
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

    private void confirmUnhide(long dialogId, String title) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.DrGramVaultUnhideTitle));
        builder.setMessage(LocaleController.formatString(R.string.DrGramVaultUnhideText, title));
        builder.setPositiveButton(LocaleController.getString(R.string.DrGramVaultUnhideYes), (dialog, which) -> {
            DrGramVault.getInstance(currentAccount).unhide(dialogId);
            adapter.reload();
            updateEmptyState();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.show();
    }

    private class VaultAdapter extends RecyclerView.Adapter<VaultAdapter.RowHolder> {

        private final Context context;
        private final ArrayList<Long> dialogIds = new ArrayList<>();
        private final ArrayList<String> titles = new ArrayList<>();

        VaultAdapter(Context context) {
            this.context = context;
            reload();
        }

        void reload() {
            dialogIds.clear();
            titles.clear();
            ArrayList<Long> hidden = DrGramVault.getInstance(currentAccount).getHiddenIds();
            MessagesController messagesController = MessagesController.getInstance(currentAccount);
            for (int i = 0; i < hidden.size(); i++) {
                long dialogId = hidden.get(i);
                String title;
                if (dialogId > 0) {
                    TLRPC.User user = messagesController.getUser(dialogId);
                    title = user != null ? UserObject.getUserName(user) : String.format(Locale.US, "id %d", dialogId);
                } else {
                    TLRPC.Chat chat = messagesController.getChat(-dialogId);
                    title = chat != null && !TextUtils.isEmpty(chat.title) ? chat.title : String.format(Locale.US, "id %d", dialogId);
                }
                dialogIds.add(dialogId);
                titles.add(title);
            }
            notifyDataSetChanged();
        }

        long dialogIdAt(int position) {
            return position >= 0 && position < dialogIds.size() ? dialogIds.get(position) : 0;
        }

        String titleAt(int position) {
            return position >= 0 && position < titles.size() ? titles.get(position) : "";
        }

        @Override
        public int getItemCount() {
            return dialogIds.size();
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
            TextView subtitle = new TextView(context);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            subtitle.setId(3);
            subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            texts.addView(name);
            texts.addView(subtitle);

            cell.addView(avatar, LayoutHelper.createLinear(42, 42, Gravity.CENTER_VERTICAL));
            cell.addView(texts, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 14, 0, 0, 0));
            return new RowHolder(cell);
        }

        @Override
        public void onBindViewHolder(RowHolder holder, int position) {
            long dialogId = dialogIds.get(position);
            MessagesController messagesController = MessagesController.getInstance(currentAccount);
            AvatarDrawable avatarDrawable;
            TLObject avatarObject = null;
            String name = titles.get(position);
            String subtitle = LocaleController.getString(R.string.DrGramVaultRowHint);
            if (dialogId > 0) {
                TLRPC.User user = messagesController.getUser(dialogId);
                avatarDrawable = new AvatarDrawable();
                avatarObject = user;
            } else {
                TLRPC.Chat chat = messagesController.getChat(-dialogId);
                avatarDrawable = new AvatarDrawable();
                avatarObject = chat;
            }
            holder.avatar.setForUserOrChat(avatarObject, avatarDrawable);
            holder.name.setText(name);
            holder.subtitle.setText(subtitle);
        }

        class RowHolder extends RecyclerView.ViewHolder {
            BackupImageView avatar;
            TextView name;
            TextView subtitle;

            RowHolder(View itemView) {
                super(itemView);
                avatar = itemView.findViewById(1);
                name = itemView.findViewById(2);
                subtitle = itemView.findViewById(3);
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
