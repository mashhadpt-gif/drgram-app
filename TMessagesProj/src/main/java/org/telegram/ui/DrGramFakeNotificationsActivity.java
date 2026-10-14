/*
 * DrGram local notification preview.
 * These notifications are generated on this device only and never impersonate
 * or message another Telegram account.
 */
package org.telegram.ui;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.DrGramGlassKit;

public class DrGramFakeNotificationsActivity extends BaseFragment {
    private static final String CHANNEL_ID = "drgram_local_preview";
    private static final int REQUEST_CODE_NOTIFICATIONS = 4815;

    private EditText titleField;
    private EditText bodyField;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.DrGramFakeNotificationsTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(24));
        content.setBackground(DrGramGlassKit.pageGradient()); // DrGram Fix 36: glass canvas

        TextView hint = new TextView(context);
        hint.setText(LocaleController.getString(R.string.DrGramFakeNotificationsHint));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        hint.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        content.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        titleField = createField(context, LocaleController.getString(R.string.DrGramFakeNotificationTitleHint),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, true);
        TextView titleLabel = createLabel(context, LocaleController.getString(R.string.DrGramFakeNotificationTitle));
        titleLabel.setPadding(0, AndroidUtilities.dp(18), 0, AndroidUtilities.dp(4));
        content.addView(titleLabel);
        content.addView(titleField, fieldLayout());

        bodyField = createField(context, LocaleController.getString(R.string.DrGramFakeNotificationBodyHint),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, false);
        bodyField.setMinLines(4);
        bodyField.setGravity(Gravity.TOP | Gravity.START);
        TextView bodyLabel = createLabel(context, LocaleController.getString(R.string.DrGramFakeNotificationBody));
        bodyLabel.setPadding(0, AndroidUtilities.dp(14), 0, AndroidUtilities.dp(4));
        content.addView(bodyLabel);
        content.addView(bodyField, fieldLayout());

        Button send = new Button(context);
        send.setText(LocaleController.getString(R.string.DrGramFakeNotificationSend));
        send.setAllCaps(false);
        send.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        send.setTextColor(DrGramThemeInstallerCompat.isGoldMode() ? 0xFF17120A : 0xFFFFFFFF);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(12));
        bg.setColor(DrGramThemeInstallerCompat.accentColor());
        send.setBackground(bg);
        send.setOnClickListener(v -> showPreview());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonParams.setMargins(0, AndroidUtilities.dp(12), 0, 0);
        content.addView(send, buttonParams);

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fragmentView = scroll;
        return fragmentView;
    }

    private EditText createField(Context context, String hint, int type, boolean singleLine) {
        EditText edit = new EditText(context);
        edit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        edit.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        edit.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        edit.setHint(hint);
        edit.setInputType(type);
        edit.setSingleLine(singleLine);
        edit.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(10));
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(AndroidUtilities.dp(12));
        shape.setColor(getThemedColor(Theme.key_dialogBackground));
        shape.setStroke(AndroidUtilities.dp(1), getThemedColor(Theme.key_divider));
        edit.setBackground(shape);
        return edit;
    }

    private TextView createLabel(Context context, String text) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        label.setTypeface(AndroidUtilities.bold());
        label.setTextColor(DrGramThemeInstallerCompat.accentColor());
        return label;
    }

    private LinearLayout.LayoutParams fieldLayout() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, AndroidUtilities.dp(5));
        return params;
    }

    private void showPreview() {
        if (getParentActivity() == null || titleField == null || bodyField == null) {
            return;
        }
        String title = titleField.getText().toString().trim();
        String body = bodyField.getText().toString().trim();
        if (title.isEmpty() && body.isEmpty()) {
            android.widget.Toast.makeText(getParentActivity(),
                    LocaleController.getString(R.string.DrGramFakeNotificationTitleRequired),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (title.isEmpty()) {
            title = LocaleController.getString(R.string.DrGramFakeNotificationTitle);
        }
        if (body.isEmpty()) {
            body = title;
        }

        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(getParentActivity(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(getParentActivity(), new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_CODE_NOTIFICATIONS);
            return;
        }

        if (!NotificationManagerCompat.from(getParentActivity()).areNotificationsEnabled()) {
            new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                    .setTitle(LocaleController.getString(R.string.DrGramFakeNotificationPermTitle))
                    .setMessage(LocaleController.getString(R.string.DrGramFakeNotificationPermMessage))
                    .setPositiveButton(LocaleController.getString(R.string.DrGramFakeNotificationOpenSettings), (dialog, which) -> openNotificationSettings())
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .show();
            return;
        }

        Context context = getParentActivity();
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    LocaleController.getString(R.string.DrGramFakeNotificationChannel),
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription(LocaleController.getString(R.string.DrGramFakeNotificationsHint));
            manager.createNotificationChannel(channel);
        }

        Intent launch = new Intent(context, LaunchActivity.class);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(context, 4815, launch, pendingFlags);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setContentIntent(contentIntent);
        NotificationManagerCompat.from(context).notify((int) (System.currentTimeMillis() & 0x7fffffff), builder.build());
        android.widget.Toast.makeText(context, LocaleController.getString(R.string.DrGramFakeNotificationSent), android.widget.Toast.LENGTH_SHORT).show();
    }

    private void openNotificationSettings() {
        if (getParentActivity() == null) {
            return;
        }
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getParentActivity().getPackageName());
        try {
            getParentActivity().startActivity(intent);
        } catch (Exception e) {
            Intent fallback = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getParentActivity().getPackageName(), null));
            getParentActivity().startActivity(fallback);
        }
    }

    // Small indirection keeps this screen's accent aligned with the active DrGram brand.
    private static final class DrGramThemeInstallerCompat {
        static boolean isGoldMode() {
            return !org.telegram.messenger.DrGramThemeInstaller.isLoveMode();
        }
        static int accentColor() {
            return org.telegram.messenger.DrGramThemeInstaller.accentColor();
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
