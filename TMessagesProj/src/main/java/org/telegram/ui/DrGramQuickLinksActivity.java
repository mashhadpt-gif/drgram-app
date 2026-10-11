/*
 * DrGram Quick Links — local shortcuts to frequently used URLs and Telegram links.
 * Entries stay in private app preferences and are not uploaded to any service.
 */
package org.telegram.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

public class DrGramQuickLinksActivity extends BaseFragment {

    private static final String PREFS_PREFIX = "drgram_quick_links_";
    private LinearLayout content;
    private boolean storageCorrupt;

    private static final class QuickLink {
        long id;
        String title;
        String url;

        QuickLink(long id, String title, String url) {
            this.id = id;
            this.title = title;
            this.url = url;
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.DrGramQuickLinksTitle));
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
        content.setPadding(0, 0, 0, AndroidUtilities.dp(20));
        content.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fragmentView = scroll;

        TextView hint = new TextView(context);
        hint.setText(LocaleController.getString(R.string.DrGramQuickLinksHint));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        hint.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        hint.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(18), AndroidUtilities.dp(20), AndroidUtilities.dp(8));
        content.addView(hint);

        content.addView(makeRow(context, "＋  " + LocaleController.getString(R.string.DrGramQuickLinksAdd), true, () -> showEditor(null)));
        rebuildRows();
        return fragmentView;
    }

    private android.content.SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_PREFIX + currentAccount, Context.MODE_PRIVATE);
    }

    private ArrayList<QuickLink> loadLinks() {
        ArrayList<QuickLink> links = new ArrayList<>();
        storageCorrupt = false;
        String raw = prefs().getString("entries", "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                String title = item.optString("title", "").trim();
                String url = item.optString("url", "").trim();
                if (!title.isEmpty() && isValidUrl(url)) {
                    links.add(new QuickLink(item.optLong("id", i + 1), title, url));
                }
            }
        } catch (Exception e) {
            storageCorrupt = true;
        }
        return links;
    }

    private void saveLinks(ArrayList<QuickLink> links) {
        if (storageCorrupt) {
            Toast.makeText(getParentActivity(), LocaleController.getString(R.string.DrGramQuickLinksOpenFailed), Toast.LENGTH_LONG).show();
            return;
        }
        try {
            JSONArray array = new JSONArray();
            for (QuickLink link : links) {
                JSONObject item = new JSONObject();
                item.put("id", link.id);
                item.put("title", link.title);
                item.put("url", link.url);
                array.put(item);
            }
            prefs().edit().putString("entries", array.toString()).apply();
        } catch (Exception ignore) {
        }
        rebuildRows();
    }

    private void rebuildRows() {
        if (content == null) return;
        Context context = content.getContext();
        // Keep hint + "Add" row; replace all subsequent items.
        while (content.getChildCount() > 2) {
            content.removeViewAt(2);
        }
        ArrayList<QuickLink> links = loadLinks();
        if (links.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText(LocaleController.getString(R.string.DrGramQuickLinksEmpty));
            empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            empty.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(28), AndroidUtilities.dp(24), AndroidUtilities.dp(28));
            content.addView(empty);
            return;
        }
        for (QuickLink link : links) {
            final QuickLink item = link;
            LinearLayout row = makeRow(context, "↗  " + item.title + "\n" + item.url, false, () -> openLink(item.url));
            row.setOnLongClickListener(v -> {
                showLinkActions(item);
                return true;
            });
            content.addView(row);
        }
    }

    private LinearLayout makeRow(Context context, String title, boolean accent, Runnable click) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(14), AndroidUtilities.dp(16), AndroidUtilities.dp(14));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(15));
        bg.setColor(getThemedColor(Theme.key_dialogBackground));
        bg.setStroke(AndroidUtilities.dp(1), accent ? 0xFFF5B90D : getThemedColor(Theme.key_divider));
        row.setBackgroundDrawable(bg);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(AndroidUtilities.dp(16), AndroidUtilities.dp(5), AndroidUtilities.dp(16), AndroidUtilities.dp(5));
        row.setLayoutParams(params);
        TextView text = new TextView(context);
        text.setText(title);
        text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        text.setTextColor(accent ? 0xFFF5B90D : getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        row.addView(text, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> click.run());
        return row;
    }

    private void showEditor(QuickLink existing) {
        if (getParentActivity() == null) return;
        Context context = getParentActivity();
        LinearLayout fields = new LinearLayout(context);
        fields.setPadding(AndroidUtilities.dp(20), 0, AndroidUtilities.dp(20), 0);
        fields.setOrientation(LinearLayout.VERTICAL);
        EditText title = new EditText(context);
        title.setSingleLine(true);
        title.setHint(LocaleController.getString(R.string.DrGramQuickLinksName));
        title.setText(existing == null ? "" : existing.title);
        // DrGram Fix 14: guaranteed readable text on the dark luxury dialogs —
        // bare EditTexts resolved a dark framework color on the dark background.
        title.setTextColor(0xFFF7F0DF);
        title.setHintTextColor(0x99B9AD95);
        android.graphics.drawable.GradientDrawable linkFieldBg = new android.graphics.drawable.GradientDrawable();
        linkFieldBg.setCornerRadius(AndroidUtilities.dp(12));
        linkFieldBg.setColor(0x1AFFFFFF);
        linkFieldBg.setStroke(AndroidUtilities.dp(1), 0x59F4D27A);
        title.setBackground(linkFieldBg);
        EditText url = new EditText(context);
        url.setSingleLine(true);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setHint(LocaleController.getString(R.string.DrGramQuickLinksUrl));
        url.setText(existing == null ? "" : existing.url);
        url.setTextColor(0xFFF7F0DF);
        url.setHintTextColor(0x99B9AD95);
        url.setBackground(linkFieldBg);
        fields.addView(title);
        fields.addView(url);
        new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.DrGramQuickLinksAdd))
                .setView(fields)
                .setPositiveButton(LocaleController.getString(R.string.Save), (dialog, which) -> {
                    String linkTitle = title.getText().toString().trim();
                    String linkUrl = normalizeUrl(url.getText().toString().trim());
                    if (linkTitle.isEmpty() || !isValidUrl(linkUrl)) {
                        Toast.makeText(context, LocaleController.getString(R.string.DrGramQuickLinksInvalid), Toast.LENGTH_LONG).show();
                        return;
                    }
                    ArrayList<QuickLink> links = loadLinks();
                    if (storageCorrupt) {
                        Toast.makeText(context, LocaleController.getString(R.string.DrGramQuickLinksOpenFailed), Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (existing != null) {
                        for (QuickLink item : links) {
                            if (item.id == existing.id) {
                                item.title = linkTitle;
                                item.url = linkUrl;
                                break;
                            }
                        }
                    } else {
                        if (links.size() >= 100) {
                            Toast.makeText(context, LocaleController.getString(R.string.DrGramQuickLinksInvalid), Toast.LENGTH_LONG).show();
                            return;
                        }
                        links.add(0, new QuickLink(System.currentTimeMillis(), linkTitle, linkUrl));
                    }
                    saveLinks(links);
                    Toast.makeText(context, LocaleController.getString(R.string.DrGramQuickLinksSaved), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private void showLinkActions(QuickLink item) {
        if (getParentActivity() == null) return;
        new AlertDialog.Builder(getParentActivity())
                .setTitle(item.title)
                .setItems(new String[] {
                        LocaleController.getString(R.string.Edit),
                        LocaleController.getString(R.string.Delete)
                }, (dialog, which) -> {
                    if (which == 0) {
                        showEditor(item);
                    } else if (which == 1) {
                        ArrayList<QuickLink> links = loadLinks();
                        if (storageCorrupt) return;
                        for (int i = links.size() - 1; i >= 0; i--) {
                            if (links.get(i).id == item.id) links.remove(i);
                        }
                        saveLinks(links);
                    }
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private String normalizeUrl(String url) {
        if (url == null) return "";
        String value = url.trim();
        if (value.isEmpty()) return value;
        Uri parsed = Uri.parse(value);
        if (parsed.getScheme() == null) {
            value = "https://" + value;
        }
        return value;
    }

    private boolean isValidUrl(String value) {
        try {
            if (value == null || value.trim().isEmpty()) return false;
            Uri uri = Uri.parse(value.trim());
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme)
                    && !"https".equalsIgnoreCase(scheme)
                    && !"tg".equalsIgnoreCase(scheme)) {
                return false;
            }
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                return uri.getHost() != null && !uri.getHost().trim().isEmpty();
            }
            return !uri.toString().equalsIgnoreCase("tg:");
        } catch (Throwable ignore) {
            return false;
        }
    }

    private void openLink(String value) {
        if (getParentActivity() == null || !isValidUrl(value)) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(value));
            getParentActivity().startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(getParentActivity(), LocaleController.getString(R.string.DrGramQuickLinksOpenFailed), Toast.LENGTH_LONG).show();
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
