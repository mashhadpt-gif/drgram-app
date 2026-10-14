/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Hashtags+ screen — your personal hashtag collection. Lists the tags
 * you used in outgoing messages and the tags you searched for, one tap away
 * from a global search. Hold a row to remove it; the menu clears everything.
 */
package org.telegram.ui;

import android.content.Context;
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
import org.telegram.messenger.DrGramHashtags;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.DrGramGlassKit;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

public class DrGramHashtagsActivity extends BaseFragment {

    private TextView emptyTextView;
    private TagsAdapter adapter;

    public DrGramHashtagsActivity() {
        super();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.DrGramTagsTitle));
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItemWithWidth(1, R.drawable.msg_delete, AndroidUtilities.dp(48), LocaleController.getString(R.string.DrGramTagsClear));
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
        emptyTextView.setText(LocaleController.getString(R.string.DrGramTagsEmpty));
        rootLayout.addView(emptyTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        RecyclerListView listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setClipToPadding(false);
        listView.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8));
        listView.setAdapter(adapter = new TagsAdapter(context));
        listView.setOnItemClickListener((view, position) -> {
            DrGramHashtags.Tag tag = adapter.tagAt(position);
            if (tag == null) {
                return;
            }
            DialogsActivity dialogsActivity = new DialogsActivity(new android.os.Bundle());
            dialogsActivity.setInitialSearchString(tag.tag.startsWith("#") ? tag.tag : "#" + tag.tag);
            presentFragment(dialogsActivity);
        });
        listView.setOnItemLongClickListener((view, position) -> {
            DrGramHashtags.Tag tag = adapter.tagAt(position);
            if (tag == null) {
                return false;
            }
            confirmRemove(tag);
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

    private void confirmRemove(DrGramHashtags.Tag tag) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.DrGramTagsRemove));
        builder.setMessage(LocaleController.getString(R.string.DrGramTagsRemoveText));
        builder.setPositiveButton(LocaleController.getString(R.string.DrGramTagsRemove), (dialog, which) -> {
            DrGramHashtags.removeTag(currentAccount, tag.tag);
            adapter.reload();
            updateEmptyState();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.show();
    }

    private void confirmClear() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.DrGramTagsClear));
        builder.setMessage(LocaleController.getString(R.string.DrGramTagsClearText));
        builder.setPositiveButton(LocaleController.getString(R.string.DrGramTagsClear), (dialog, which) -> {
            DrGramHashtags.clearAll(currentAccount);
            adapter.reload();
            updateEmptyState();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.show();
    }

    private class TagsAdapter extends RecyclerView.Adapter<TagsAdapter.RowHolder> {

        private final Context context;
        private final ArrayList<DrGramHashtags.Tag> items = new ArrayList<>();

        TagsAdapter(Context context) {
            this.context = context;
            reload();
        }

        void reload() {
            items.clear();
            items.addAll(DrGramHashtags.getCollection(currentAccount));
            notifyDataSetChanged();
        }

        DrGramHashtags.Tag tagAt(int position) {
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

            TextView icon = new TextView(context);
            icon.setId(4);
            icon.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
            icon.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
            icon.setText("#");

            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView tagView = new TextView(context);
            tagView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            tagView.setId(2);
            tagView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            TextView meta = new TextView(context);
            meta.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            meta.setId(3);
            meta.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            texts.addView(tagView);
            texts.addView(meta);

            cell.addView(icon, LayoutHelper.createLinear(28, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
            cell.addView(texts, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 10, 0, 0, 0));
            return new RowHolder(cell);
        }

        @Override
        public void onBindViewHolder(RowHolder holder, int position) {
            DrGramHashtags.Tag tag = items.get(position);
            holder.tagView.setText(tag.tag);
            StringBuilder meta = new StringBuilder();
            if (tag.useCount > 0) {
                meta.append(LocaleController.formatString(R.string.DrGramTagsUsedCount, tag.useCount));
            }
            if (tag.lastUsed > 0) {
                if (meta.length() > 0) {
                    meta.append("  ·  ");
                }
                meta.append(DateFormat.format("yyyy/MM/dd", tag.lastUsed).toString());
            }
            holder.meta.setText(meta.length() > 0 ? meta.toString() : LocaleController.getString(R.string.DrGramTagsFromSearch));
        }

        class RowHolder extends RecyclerView.ViewHolder {
            TextView tagView;
            TextView meta;

            RowHolder(View itemView) {
                super(itemView);
                tagView = itemView.findViewById(2);
                meta = itemView.findViewById(3);
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
