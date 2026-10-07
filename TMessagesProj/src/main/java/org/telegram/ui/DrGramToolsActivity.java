/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Tools — the control hub for DrGram's smart features:
 *   • DrGram Secretary: offline auto-responder
 *   • DrGram Vault: locally hidden chats
 *   • DrGram Online Guard: presence alerts for chosen contacts
 */
package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.DrGramOnlineGuard;
import org.telegram.messenger.DrGramSecretary;
import org.telegram.messenger.DrGramVault;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.Set;

public class DrGramToolsActivity extends BaseFragment {

    private LinearLayout contentView;
    private Switch secretarySwitch;
    private Switch guardSwitch;
    private TextView vaultCountText;
    private LinearLayout guardListContainer;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.DrGramToolsTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        contentView = new LinearLayout(context);
        contentView.setOrientation(LinearLayout.VERTICAL);
        contentView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        fragmentView = contentView;

        buildSecretarySection(context);
        buildVaultSection(context);
        buildGuardSection(context);

        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshDynamicRows();
    }

    //region Secretary

    private void buildSecretarySection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramSecretaryTitle),
                LocaleController.getString(R.string.DrGramSecretaryHint));

        secretarySwitch = new Switch(context);
        secretarySwitch.setChecked(DrGramSecretary.getInstance(currentAccount).isEnabled());
        LinearLayout row = makeRow(context, LocaleController.getString(R.string.DrGramSecretaryEnable), secretarySwitch);
        row.setOnClickListener(v -> {
            boolean newState = !DrGramSecretary.getInstance(currentAccount).isEnabled();
            DrGramSecretary.getInstance(currentAccount).setEnabled(newState);
            secretarySwitch.setChecked(newState);
        });
        contentView.addView(row);

        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSecretaryText), v -> showSecretaryTextDialog()));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSecretaryGroups), v -> {
            DrGramSecretary secretary = DrGramSecretary.getInstance(currentAccount);
            secretary.setIncludeGroups(!secretary.isIncludeGroups());
            refreshDynamicRows();
        }, "secretary_groups"));
    }

    private void showSecretaryTextDialog() {
        if (getParentActivity() == null) {
            return;
        }
        Context context = getParentActivity();
        AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.DrGramSecretaryEditTitle));

        EditText input = new EditText(context);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(2);
        int pad = AndroidUtilities.dp(20);
        input.setPadding(pad, pad / 2, pad, pad / 2);
        input.setText(DrGramSecretary.getInstance(currentAccount).getReplyText());
        FrameLayout container = new FrameLayout(context);
        container.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 8, 8, 8, 0));
        builder.setView(container);

        builder.setPositiveButton(LocaleController.getString(R.string.DrGramSave), (dialog, which) ->
                DrGramSecretary.getInstance(currentAccount).setReplyText(input.getText().toString()));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.show();
    }

    //endregion

    //region Vault

    private void buildVaultSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramVaultSection),
                LocaleController.getString(R.string.DrGramVaultHint));

        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramVaultSetCode), v -> showVaultCodeDialog()));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramVaultOpen), v ->
                presentFragment(new DrGramVaultActivity())));
        vaultCountText = new TextView(context);
        vaultCountText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        vaultCountText.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        LinearLayout row = makeRow(context, LocaleController.getString(R.string.DrGramVaultCount), vaultCountText);
        contentView.addView(row);
    }

    private void showVaultCodeDialog() {
        if (getParentActivity() == null) {
            return;
        }
        Context context = getParentActivity();
        AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.DrGramVaultCodeTitle));
        builder.setMessage(LocaleController.getString(R.string.DrGramVaultCodeHint));

        EditText input = new EditText(context);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        int pad = AndroidUtilities.dp(20);
        input.setPadding(pad, pad / 2, pad, pad / 2);
        input.setText(DrGramVault.getInstance(currentAccount).getCode());
        FrameLayout container = new FrameLayout(context);
        container.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 8, 8, 8, 0));
        builder.setView(container);

        builder.setPositiveButton(LocaleController.getString(R.string.DrGramSave), (dialog, which) ->
                DrGramVault.getInstance(currentAccount).setCode(input.getText().toString()));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.show();
    }

    //endregion

    //region Online guard

    private void buildGuardSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramGuardTitle),
                LocaleController.getString(R.string.DrGramGuardHint));

        guardSwitch = new Switch(context);
        guardSwitch.setChecked(DrGramOnlineGuard.isEnabled(currentAccount));
        LinearLayout row = makeRow(context, LocaleController.getString(R.string.DrGramGuardEnable), guardSwitch);
        row.setOnClickListener(v -> {
            boolean newState = !DrGramOnlineGuard.isEnabled(currentAccount);
            DrGramOnlineGuard.setEnabled(currentAccount, newState);
            guardSwitch.setChecked(newState);
        });
        contentView.addView(row);

        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramGuardAdd), v -> openContactPicker()));

        guardListContainer = new LinearLayout(context);
        guardListContainer.setOrientation(LinearLayout.VERTICAL);
        contentView.addView(guardListContainer);
    }

    private void openContactPicker() {
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_USERS_ONLY);
        DialogsActivity fragment = new DialogsActivity(args);
        fragment.setDelegate((f, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            for (int i = 0; i < dids.size(); i++) {
                long dialogId = dids.get(i).dialogId;
                if (dialogId > 0) {
                    DrGramOnlineGuard.watch(currentAccount, dialogId);
                }
            }
            refreshDynamicRows();
            return true;
        });
        presentFragment(fragment);
    }

    private void rebuildGuardList(Context context) {
        guardListContainer.removeAllViews();
        Set<String> watched = DrGramOnlineGuard.getWatchedIds(currentAccount);
        for (String id : watched) {
            long userId;
            try {
                userId = Long.parseLong(id);
            } catch (NumberFormatException ignore) {
                continue;
            }
            TLRPC.User user = getMessagesController().getUser(userId);
            String name = user != null ? UserObject.getUserName(user) :
                    LocaleController.getString(R.string.DrGramGuardUnknownUser);
            TextView cell = new TextView(context);
            cell.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            cell.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            cell.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(11), AndroidUtilities.dp(21), AndroidUtilities.dp(11));
            cell.setText(name);
            cell.setOnClickListener(v -> {
                DrGramOnlineGuard.unwatch(currentAccount, userId);
                rebuildGuardList(context);
            });
            guardListContainer.addView(cell);
        }
    }

    //endregion

    //region shared row builders

    private void addHeader(Context context, String title, String hint) {
        TextView header = new TextView(context);
        header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        header.setTypeface(AndroidUtilities.bold());
        header.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueHeader));
        header.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(22), AndroidUtilities.dp(21), AndroidUtilities.dp(4));
        header.setText(title);
        contentView.addView(header);

        TextView hintView = new TextView(context);
        hintView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        hintView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        hintView.setPadding(AndroidUtilities.dp(21), 0, AndroidUtilities.dp(21), AndroidUtilities.dp(8));
        hintView.setText(hint);
        contentView.addView(hintView);
    }

    private LinearLayout makeRow(Context context, String title, View valueView) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundDrawable(Theme.getSelectorDrawable(false));
        row.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(11), AndroidUtilities.dp(21), AndroidUtilities.dp(11));

        TextView titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        titleView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        titleView.setText(title);
        titleView.setGravity(Gravity.LEFT);
        row.addView(titleView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        valueView.setLayoutParams(valueParams);
        row.addView(valueView);
        return row;
    }

    private LinearLayout makeActionRow(Context context, String title, View.OnClickListener listener) {
        return makeActionRow(context, title, listener, null);
    }

    private LinearLayout makeActionRow(Context context, String title, View.OnClickListener listener, String tag) {
        LinearLayout row = makeRow(context, title, new TextView(context));
        row.setOnClickListener(listener);
        if (tag != null) {
            row.setTag(tag);
        }
        return row;
    }

    private void refreshDynamicRows() {
        if (secretarySwitch != null) {
            secretarySwitch.setChecked(DrGramSecretary.getInstance(currentAccount).isEnabled());
        }
        if (guardSwitch != null) {
            guardSwitch.setChecked(DrGramOnlineGuard.isEnabled(currentAccount));
        }
        if (vaultCountText != null) {
            vaultCountText.setText(String.valueOf(DrGramVault.getInstance(currentAccount).getHiddenCount()));
        }
        if (guardListContainer != null) {
            View groupsRow = contentView.findViewWithTag("secretary_groups");
            if (groupsRow instanceof ViewGroup) {
                TextView titleView = (TextView) ((ViewGroup) groupsRow).getChildAt(0);
                String base = LocaleController.getString(R.string.DrGramSecretaryGroups);
                titleView.setText(base + ": " + (DrGramSecretary.getInstance(currentAccount).isIncludeGroups() ? "ON" : "OFF"));
            }
            rebuildGuardList(guardListContainer.getContext());
        }
    }

    //endregion
}
