/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Fix 21: the group members bar. A floating strip of member avatars
 * right under the chat header for groups — recent members for megagroups,
 * the participant list for basic groups. Online members carry a green dot;
 * tapping a member opens their profile.
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
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ConnectionsManager;
import org.telegram.messenger.DrGramThemeInstaller;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ChatObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

public class DrGramMembersBar extends FrameLayout {

    private static final ArrayList<Integer> HIDDEN_THIS_SESSION = new ArrayList<>();

    private final int currentAccount;
    private final int chatId;
    private final BaseFragment parentFragment;
    private final RecyclerView listView;
    private final MembersAdapter adapter;
    private final ArrayList<TLRPC.User> members = new ArrayList<>();
    private boolean loading;

    public DrGramMembersBar(Context context, int account, int chatId, BaseFragment parent) {
        super(context);
        currentAccount = account;
        this.chatId = chatId;
        parentFragment = parent;

        boolean loveMode = DrGramThemeInstaller.isLoveMode();
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                loveMode ? new int[] {0xE62A1018, 0xE61C0B11} : new int[] {0xE63A2C0E, 0xE6211A0A});
        background.setCornerRadius(AndroidUtilities.dp(18));
        background.setStroke(AndroidUtilities.dp(1), loveMode ? 0x66E63B52 : 0x66FFC94A);
        setBackgroundDrawable(background);
        setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(6), AndroidUtilities.dp(8), AndroidUtilities.dp(6));
        setElevation(AndroidUtilities.dp(3));

        adapter = new MembersAdapter();
        listView = new RecyclerView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
        listView.setClipToPadding(false);
        listView.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
        listView.setVerticalScrollBarEnabled(false);
        listView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        listView.setAdapter(adapter);
        addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, AndroidUtilities.dp(42), Gravity.CENTER));

        // DrGram Fix 21: long-press hides the bar for this chat until the app restarts.
        setOnLongClickListener(v -> {
            hideForThisSession();
            return true;
        });

        if (HIDDEN_THIS_SESSION.contains(chatId)) {
            setVisibility(GONE);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getVisibility() != GONE) {
            loadMembers();
        }
    }

    private void loadMembers() {
        try {
            TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat((long) chatId);
            if (chat == null) {
                return;
            }
            TLRPC.ChatFull full = MessagesController.getInstance(currentAccount).getChatFull(chatId);
            if (full != null && full.participants != null && full.participants.participants != null && !full.participants.participants.isEmpty()) {
                applyBasicParticipants(full.participants.participants);
                return;
            }
            if (!ChatObject.isMegagroup(chat)) {
                if (ChatObject.isChannel(chat)) {
                    return;
                }
                MessagesController.getInstance(currentAccount).loadFullChat(chatId, 0, true);
                listView.postDelayed(() -> {
                    if (members.isEmpty()) {
                        TLRPC.ChatFull updated = MessagesController.getInstance(currentAccount).getChatFull(chatId);
                        if (updated != null && updated.participants != null && updated.participants.participants != null) {
                            applyBasicParticipants(updated.participants.participants);
                        }
                    }
                }, 2200);
                return;
            }
            if (loading) {
                return;
            }
            loading = true;
            TLRPC.TL_channels_getParticipants request = new TLRPC.TL_channels_getParticipants();
            request.channel = MessagesController.getInstance(currentAccount).getInputChannel(chatId);
            request.filter = new TLRPC.TL_channelParticipantsRecent();
            request.offset = 0;
            request.limit = 40;
            ConnectionsManager.getInstance(currentAccount).sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                loading = false;
                if (!(response instanceof TLRPC.TL_channels_channelParticipants)) {
                    return;
                }
                TLRPC.TL_channels_channelParticipants res = (TLRPC.TL_channels_channelParticipants) response;
                MessagesController.getInstance(currentAccount).putUsers(res.users, false);
                ArrayList<TLRPC.User> loaded = new ArrayList<>();
                for (int i = 0; i < res.participants.size(); i++) {
                    TLRPC.ChannelParticipant participant = res.participants.get(i);
                    long userId = MessageObject.getPeerId(participant.peer);
                    TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(userId);
                    if (user != null) {
                        loaded.add(user);
                    }
                }
                if (loaded.isEmpty()) {
                    return;
                }
                members.clear();
                members.addAll(loaded);
                adapter.notifyDataSetChanged();
            }));
        } catch (Throwable t) {
            loading = false;
            FileLog.e(t);
        }
    }

    private void applyBasicParticipants(ArrayList<TLRPC.ChatParticipant> participants) {
        try {
            ArrayList<TLRPC.User> loaded = new ArrayList<>();
            for (int i = 0; i < participants.size(); i++) {
                TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(participants.get(i).user_id);
                if (user != null) {
                    loaded.add(user);
                }
            }
            if (loaded.isEmpty()) {
                return;
            }
            members.clear();
            members.addAll(loaded);
            adapter.notifyDataSetChanged();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private void hideForThisSession() {
        HIDDEN_THIS_SESSION.add(chatId);
        setVisibility(GONE);
    }

    private class MembersAdapter extends RecyclerView.Adapter<MembersAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context context = parent.getContext();
            LinearLayout cell = new LinearLayout(context);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(AndroidUtilities.dp(5), 0, AndroidUtilities.dp(5), 0);

            FrameLayout avatarWrap = new FrameLayout(context);
            View avatar = new View(context);
            avatarWrap.addView(avatar, LayoutHelper.createFrame(AndroidUtilities.dp(32), AndroidUtilities.dp(32), Gravity.CENTER));
            View dot = new View(context);
            GradientDrawable dotBackground = new GradientDrawable();
            dotBackground.setShape(GradientDrawable.OVAL);
            dotBackground.setColor(0xFF35D07F);
            dot.setBackgroundDrawable(dotBackground);
            avatarWrap.addView(dot, LayoutHelper.createFrame(AndroidUtilities.dp(9), AndroidUtilities.dp(9), Gravity.BOTTOM | Gravity.END, 0, 0, AndroidUtilities.dp(1), AndroidUtilities.dp(1)));
            cell.addView(avatarWrap, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

            TextView name = new TextView(context);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
            name.setTextColor(0xE6FFF6DF);
            name.setMaxLines(1);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER);
            cell.addView(name, LayoutHelper.createLinear(AndroidUtilities.dp(58), LayoutHelper.WRAP_CONTENT, 0, AndroidUtilities.dp(2), 0, 0));

            return new Holder(cell, avatar, name, dot);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            final TLRPC.User user = members.get(position);
            if (user == null) {
                return;
            }
            try {
                holder.avatar.setBackgroundDrawable(new AvatarDrawable(user));
            } catch (Throwable t) {
                FileLog.e(t);
            }
            holder.name.setText(UserObject.getUserName(user));
            boolean online = user.status instanceof TLRPC.TL_userStatusOnline
                    && user.status.expires > ConnectionsManager.getInstance(currentAccount).getCurrentTime();
            holder.dot.setVisibility(online ? View.VISIBLE : View.INVISIBLE);

            holder.cell.setOnClickListener(v -> {
                try {
                    if (parentFragment == null || parentFragment.getParentActivity() == null) {
                        return;
                    }
                    Bundle args = new Bundle();
                    args.putInt("user_id", user.id);
                    parentFragment.presentFragment(new ProfileActivity(args));
                } catch (Throwable t) {
                    FileLog.e(t);
                }
            });
        }

        @Override
        public int getItemCount() {
            return members.size();
        }

        private class Holder extends RecyclerView.ViewHolder {

            final LinearLayout cell;
            final View avatar;
            final TextView name;
            final View dot;

            Holder(View view, View avatarView, TextView nameView, View dotView) {
                super(view);
                cell = (LinearLayout) view;
                avatar = avatarView;
                name = nameView;
                dot = dotView;
            }
        }
    }
}
