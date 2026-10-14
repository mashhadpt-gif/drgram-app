/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Contact History screen — shows the locally recorded changes of your
 * contact list (name, username, photo, number, added and removed contacts).
 * Tap a row to open the contact's profile; use the menu to clear the log.
 */
package org.telegram.ui;

import android.content.Context;
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
import org.telegram.messenger.R;
import org.telegram.messenger.DrGramContactHistory;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.DrGramGlassKit;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Locale;

public class DrGramContactLogActivity extends BaseFragment {

    private TextView emptyTextView;
    private LogAdapter adapter;

    public DrGramContactLogActivity() {
        super();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.DrGramClogTitle));
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItemWithWidth(1, R.drawable.msg_delete, AndroidUtilities.dp(48), LocaleController.getString(R.string.DrGramClogClear));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == 1) {
                    confirmClear();
                }
            }
        });

        FrameLayout rootLayout = new FrameLayout(context);
        rootLayout.setBackground(DrGramGlassKit.pageGradient()); // DrGram Fix 36: glass canvas
        fragmentView = rootLayout;

        emptyTextView = new TextView(context);
        emptyTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        emptyTextView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyTextView.setGravity(Gravity.CENTER);
        emptyTextView.setPadding(AndroidUtilities.dp(40), 0, AndroidUtilities.dp(40), 0);
        emptyTextView.setText(LocaleController.getString(R.string.DrGramClogEmpty));
        rootLayout.addView(emptyTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        RecyclerListView listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setClipToPadding(false);
        listView.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8));
        listView.setAdapter(adapter = new LogAdapter(context));
        listView.setOnItemClickListener((view, position) -> {
            DrGramContactHistory.Entry entry = adapter.entryAt(position);
            if (entry != null && entry.userId > 0) {
                presentFragment(new ProfileActivity(userIdArgs(entry.userId)));
            }
        });
        rootLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        updateEmptyState();
        return fragmentView;
    }

    private android.os.Bundle userIdArgs(long id) {
        android.os.Bundle args = new android.os.Bundle();
        args.putLong("user_id", id);
        return args;
    }

    private void updateEmptyState() {
        if (adapter == null || emptyTextView == null) {
            return;
        }
        emptyTextView.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
    }

    private void confirmClear() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.DrGramClogClear));
        builder.setMessage(LocaleController.getString(R.string.DrGramClogClearText));
        builder.setPositiveButton(LocaleController.getString(R.string.DrGramClogClear), (dialog, which) -> {
            DrGramContactHistory.clearLog(currentAccount);
            adapter.reload();
            updateEmptyState();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.show();
    }

    private String kindLabel(int kind, DrGramContactHistory.Entry entry) {
        switch (kind) {
            case DrGramContactHistory.Entry.KIND_NAME:
                return LocaleController.getString(R.string.DrGramClogName);
            case DrGramContactHistory.Entry.KIND_USERNAME:
                return LocaleController.getString(R.string.DrGramClogUsername);
            case DrGramContactHistory.Entry.KIND_PHOTO:
                return LocaleController.getString(R.string.DrGramClogPhoto);
            case DrGramContactHistory.Entry.KIND_PHONE:
                return LocaleController.getString(R.string.DrGramClogPhone);
            case DrGramContactHistory.Entry.KIND_REMOVED:
                return LocaleController.getString(R.string.DrGramClogRemoved);
            case DrGramContactHistory.Entry.KIND_ADDED:
                return LocaleController.getString(R.string.DrGramClogAdded);
            default:
                return "";
        }
    }

    private class LogAdapter extends RecyclerView.Adapter<LogAdapter.RowHolder> {

        private final Context context;
        private final ArrayList<DrGramContactHistory.Entry> items = new ArrayList<>();

        LogAdapter(Context context) {
            this.context = context;
            reload();
        }

        void reload() {
            items.clear();
            items.addAll(DrGramContactHistory.getLog(currentAccount));
            notifyDataSetChanged();
        }

        DrGramContactHistory.Entry entryAt(int position) {
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
            TextView title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            title.setId(2);
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            TextView detail = new TextView(context);
            detail.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            detail.setId(3);
            detail.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            detail.setMaxLines(2);
            texts.addView(title);
            texts.addView(detail);

            cell.addView(avatar, LayoutHelper.createLinear(38, 38, Gravity.CENTER_VERTICAL));
            cell.addView(texts, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 14, 0, 0, 0));
            return new RowHolder(cell);
        }

        @Override
        public void onBindViewHolder(RowHolder holder, int position) {
            DrGramContactHistory.Entry entry = items.get(position);
            org.telegram.messenger.MessagesController messagesController = org.telegram.messenger.MessagesController.getInstance(currentAccount);
            org.telegram.tgnet.TLRPC.User user = messagesController.getUser(entry.userId);
            AvatarDrawable avatarDrawable = new AvatarDrawable();
            if (user != null) {
                avatarDrawable.setInfo(user);
            }
            holder.avatar.setForUserOrChat(user, avatarDrawable);

            String name = TextUtils.isEmpty(entry.contactName) ? String.format(Locale.US, "id %d", entry.userId) : entry.contactName;
            String kind = kindLabel(entry.kind, entry);
            holder.title.setText(String.format("%1$s — %2$s", name, kind));
            StringBuilder detail = new StringBuilder();
            if (entry.kind == DrGramContactHistory.Entry.KIND_NAME || entry.kind == DrGramContactHistory.Entry.KIND_USERNAME) {
                detail.append(safe(entry.oldValue)).append(" → ").append(safe(entry.newValue));
            }
            if (detail.length() > 0) {
                detail.append("  ·  ");
            }
            detail.append(DateFormat.format("yyyy/MM/dd HH:mm", entry.time * 1000L).toString());
            holder.detail.setText(detail.toString());
        }

        private String safe(String value) {
            return value == null ? "" : value;
        }

        class RowHolder extends RecyclerView.ViewHolder {
            BackupImageView avatar;
            TextView title;
            TextView detail;

            RowHolder(View itemView) {
                super(itemView);
                avatar = itemView.findViewById(1);
                title = itemView.findViewById(2);
                detail = itemView.findViewById(3);
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
