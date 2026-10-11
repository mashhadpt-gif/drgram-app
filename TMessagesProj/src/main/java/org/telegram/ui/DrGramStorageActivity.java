/*
 * DrGram Storage Butler — local media caches at a glance, one-tap sweeping,
 * storage root display and a keep-original-names preference. Local only.
 */
package org.telegram.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DrGramStorageButler;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Switch;

import java.io.File;

public class DrGramStorageActivity extends BaseFragment {

    private LinearLayout content;
    private TextView totalLine;
    private boolean sweeping;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.DrGramButlerTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, AndroidUtilities.dp(24));
        content.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fragmentView = scroll;

        TextView hint = new TextView(context);
        hint.setText(LocaleController.getString(R.string.DrGramButlerHint));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        hint.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        hint.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(18), AndroidUtilities.dp(20), AndroidUtilities.dp(8));
        content.addView(hint);

        totalLine = new TextView(context);
        totalLine.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        totalLine.setTypeface(AndroidUtilities.bold());
        totalLine.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        totalLine.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(6), AndroidUtilities.dp(20), AndroidUtilities.dp(10));
        content.addView(totalLine);

        content.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramButlerSweepAll), v -> confirmSweepAll()));
        content.addView(makeSwitchRow(context,
                LocaleController.getString(R.string.DrGramButlerRealNames),
                LocaleController.getString(R.string.DrGramButlerRealNamesHint),
                DrGramStorageButler.keepOriginalNames(),
                (view, checked) -> DrGramStorageButler.setKeepOriginalNames(checked)));
        content.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramButlerPath), v -> copyPath()));

        refreshSizes();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshSizes();
    }

    private void refreshSizes() {
        if (totalLine == null) {
            return;
        }
        totalLine.setText(LocaleController.getString(R.string.DrGramButlerCalculating));
        AndroidUtilities.runOnUIThread(new Runnable() {
            @Override
            public void run() {
                new Thread(() -> {
                    final long total = DrGramStorageButler.totalCacheSize();
                    final long images = DrGramStorageButler.imageCacheSize();
                    final long videos = DrGramStorageButler.videoCacheSize();
                    final long audio = DrGramStorageButler.audioCacheSize();
                    final long docs = DrGramStorageButler.documentCacheSize();
                    AndroidUtilities.runOnUIThread(() -> {
                        if (totalLine == null) {
                            return;
                        }
                        totalLine.setText(LocaleController.formatString(R.string.DrGramButlerTotals,
                                AndroidUtilities.formatFileSize(total)));
                        Context context = content != null ? content.getContext() : null;
                        if (context == null) {
                            return;
                        }
                        // rebuild per-cache rows (keep hint, total, sweep, toggle, path = first 5)
                        while (content.getChildCount() > 5) {
                            content.removeViewAt(5);
                        }
                        content.addView(makeCacheRow(context, LocaleController.getString(R.string.DrGramButlerImages), images));
                        content.addView(makeCacheRow(context, LocaleController.getString(R.string.DrGramButlerVideos), videos));
                        content.addView(makeCacheRow(context, LocaleController.getString(R.string.DrGramButlerAudio), audio));
                        content.addView(makeCacheRow(context, LocaleController.getString(R.string.DrGramButlerDocuments), docs));
                    });
                }).start();
            }
        });
    }

    private View makeCacheRow(Context context, String title, long size) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(card());
        row.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(13), AndroidUtilities.dp(16), AndroidUtilities.dp(13));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(AndroidUtilities.dp(16), AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(4));
        row.setLayoutParams(params);

        TextView name = new TextView(context);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        name.setTextColor(0xFFFFF6DF);
        name.setText(title);
        row.addView(name, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        TextView sizeView = new TextView(context);
        sizeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        sizeView.setTextColor(0xFFC9B98F);
        sizeView.setText(AndroidUtilities.formatFileSize(size));
        row.addView(sizeView);

        TextView sweep = new TextView(context);
        sweep.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        sweep.setTextColor(0xFFE63B52);
        sweep.setTypeface(AndroidUtilities.bold());
        sweep.setText("  " + LocaleController.getString(R.string.DrGramButlerSweep));
        sweep.setOnClickListener(v -> {
            File dir;
            if (title.equals(LocaleController.getString(R.string.DrGramButlerImages))) {
                dir = FileLoader.getDirectory(FileLoader.MEDIA_DIR_IMAGE);
            } else if (title.equals(LocaleController.getString(R.string.DrGramButlerVideos))) {
                dir = FileLoader.getDirectory(FileLoader.MEDIA_DIR_VIDEO);
            } else if (title.equals(LocaleController.getString(R.string.DrGramButlerAudio))) {
                dir = FileLoader.getDirectory(FileLoader.MEDIA_DIR_AUDIO);
            } else {
                dir = FileLoader.getDirectory(FileLoader.MEDIA_DIR_DOCUMENT);
            }
            final File target = dir;
            new Thread(() -> {
                DrGramStorageButler.sweep(target);
                AndroidUtilities.runOnUIThread(this::refreshSizes);
            }).start();
        });
        row.addView(sweep);
        return row;
    }

    private View makeActionRow(Context context, String title, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(card(0xFFF5B90D));
        row.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(13), AndroidUtilities.dp(16), AndroidUtilities.dp(13));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(AndroidUtilities.dp(16), AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(4));
        row.setLayoutParams(params);
        TextView label = new TextView(context);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        label.setTypeface(AndroidUtilities.bold());
        label.setTextColor(0xFFFFF6DF);
        label.setText(title);
        row.addView(label, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        row.setOnClickListener(listener);
        return row;
    }

    private View makeSwitchRow(Context context, String title, String subtitle, boolean checked, Switch.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(card());
        row.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(AndroidUtilities.dp(16), AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(4));
        row.setLayoutParams(params);

        LinearLayout top = new LinearLayout(context);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(context);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        name.setTextColor(0xFFFFF6DF);
        name.setText(title);
        top.addView(name, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        Switch sw = new Switch(context);
        sw.setChecked(checked, false);
        sw.setOnCheckedChangeListener(listener);
        top.addView(sw);

        row.addView(top);

        TextView sub = new TextView(context);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        sub.setTextColor(0xFFC9B98F);
        sub.setText(subtitle);
        sub.setPadding(0, AndroidUtilities.dp(4), 0, 0);
        row.addView(sub);
        return row;
    }

    private void confirmSweepAll() {
        Context context = getParentActivity();
        if (context == null || sweeping) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.DrGramButlerSweepAll));
        builder.setMessage(LocaleController.getString(R.string.DrGramButlerSweepConfirm));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setPositiveButton(LocaleController.getString(R.string.DrGramButlerSweep), (dialog, which) -> {
            sweeping = true;
            new Thread(() -> {
                final long freed = DrGramStorageButler.sweepAll();
                sweeping = false;
                AndroidUtilities.runOnUIThread(() -> {
                    refreshSizes();
                    if (getParentActivity() != null) {
                        Toast.makeText(getParentActivity(),
                                LocaleController.formatString(R.string.DrGramButlerFreed, AndroidUtilities.formatFileSize(freed)),
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }).start();
        });
        builder.show();
    }

    private void copyPath() {
        File root = DrGramStorageButler.storageRoot();
        String path = root != null ? root.getAbsolutePath() : "";
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    ApplicationLoader.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("path", path));
            if (getParentActivity() != null) {
                Toast.makeText(getParentActivity(), LocaleController.getString(R.string.DrGramButlerCopied), Toast.LENGTH_SHORT).show();
            }
        } catch (Exception ignore) {
        }
    }

    private static GradientDrawable card() {
        return card(0xCCFFC94A);
    }

    private static GradientDrawable card(int stroke) {
        GradientDrawable card = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0xFF3A2C0E, 0xFF211A0A});
        card.setCornerRadius(AndroidUtilities.dp(17));
        card.setStroke(AndroidUtilities.dp(1), stroke);
        return card;
    }
}
