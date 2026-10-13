/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Fix 32: DrGramStyleGalleryActivity — the "style gallery".
 * Replaces the plain ThemeActivity shortcut with a real LuxGram gallery:
 *   - LuxGram signature themes: the two branded palettes as large cards with
 *     an Active badge; tapping returns the brand look (and leaves free mode).
 *   - Curated collection: elegant community themes bundled with the app
 *     (AMOLED Night, Indigo, Soft Dark, Sky Blue, Dracula). Each card renders
 *     a mini chat preview from the theme's real palette, parsed at runtime
 *     from the bundled .attheme asset. Applying one switches the app into
 *     "free theme" mode; the brand self-healing respects that choice.
 * Design language follows the luxury control center (dark gradient, gold
 * cards, ornament divider). No framework scrollbars — Fix 31 lesson.
 */
package org.telegram.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DrGramThemeInstaller;
import org.telegram.messenger.DrGramThemeTuner;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.DrGramLuxDecor;
import org.telegram.ui.Components.LayoutHelper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class DrGramStyleGalleryActivity extends BaseFragment {

    private static final String[][] CURATED = {
            // asset, internal theme name, localization key, fallback bg, fallback accent
            {"luxgram_amoled.attheme", "LuxGram AMOLED", "DrGramThemeNameAmoled", "0xFF101114", "0xFF4A9CD8"},
            {"luxgram_indigo.attheme", "LuxGram Indigo", "DrGramThemeNameIndigo", "0xFFFAFAFA", "0xFF3F51B5"},
            {"luxgram_softdark.attheme", "LuxGram Soft Dark", "DrGramThemeNameSoftDark", "0xFF222B35", "0xFF54677D"},
            {"luxgram_skyblue.attheme", "LuxGram Sky Blue", "DrGramThemeNameSkyBlue", "0xFF1D2C3B", "0xFF4E7A9E"},
            {"luxgram_dracula.attheme", "LuxGram Dracula", "DrGramThemeNameDracula", "0xFF282A36", "0xFFBD93F9"},
    };

    private LinearLayout contentView;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.DrGramStyleGallery));
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
        contentView.setPadding(0, 0, 0, AndroidUtilities.dp(28));
        contentView.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                DrGramThemeInstaller.isLoveMode()
                        ? new int[] {0xFF160A0F, 0xFF251017, 0xFF10090D}
                        : new int[] {0xFF0C0A05, 0xFF261D09, 0xFF0C0A05}));

        // Fix 31 lesson: never setScrollbarFadingEnabled on custom screens; the
        // framework scrollbars stay disabled so the page is draw-proof.
        android.widget.ScrollView scroller = new android.widget.ScrollView(context);
        scroller.setFillViewport(true);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.addView(contentView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fragmentView = scroller;

        try {
            TextView hero = new TextView(context);
            hero.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            hero.setTextColor(0xFFFFC94A);
            hero.setGravity(Gravity.CENTER);
            hero.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(22), AndroidUtilities.dp(24), AndroidUtilities.dp(6));
            hero.setText(LocaleController.getString(R.string.DrGramGalleryBrandHint));
            contentView.addView(hero);
            contentView.addView(DrGramLuxDecor.ornamentDivider(context, DrGramThemeInstaller.isLoveMode()));
        } catch (Throwable t) {
            FileLog.e(t);
        }

        buildBrandSection(context);
        buildCuratedSection(context);
        buildBackToBrandRow(context);

        return fragmentView;
    }

    //region sections

    private void buildBrandSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramGalleryBrand), null);
        boolean loveMode = DrGramThemeInstaller.isLoveMode();
        boolean freeMode = DrGramThemeInstaller.isFreeThemeMode();

        LinearLayout cardsRow = new LinearLayout(context);
        cardsRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(AndroidUtilities.dp(14), AndroidUtilities.dp(6), AndroidUtilities.dp(14), AndroidUtilities.dp(6));
        contentView.addView(cardsRow, rowParams);

        LinearLayout goldCard = makeBrandCard(context,
                LocaleController.getString(R.string.DrGramSetThemeGold),
                LocaleController.getString(R.string.DrGramSetThemeGoldDesc),
                DrGramThemeTuner.previewAccent(false, DrGramThemeInstaller.getGoldness()),
                0xFF1A1408, 0xFF3A2C0E, !loveMode && !freeMode);
        LinearLayout redCard = makeBrandCard(context,
                LocaleController.getString(R.string.DrGramSetThemeRed),
                LocaleController.getString(R.string.DrGramSetThemeRedDesc),
                DrGramThemeTuner.previewAccent(true, DrGramThemeInstaller.getRedness()),
                0xFF1C0B11, 0xFF2A1018, loveMode && !freeMode);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cardParams.setMargins(AndroidUtilities.dp(2), 0, AndroidUtilities.dp(2), 0);
        cardsRow.addView(goldCard, cardParams);
        cardsRow.addView(redCard, cardParams);

        goldCard.setOnClickListener(v -> {
            if (applyBrandMode(false)) {
                rebuildEverything();
            }
        });
        redCard.setOnClickListener(v -> {
            if (applyBrandMode(true)) {
                rebuildEverything();
            }
        });
    }

    private void buildCuratedSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramGalleryCurated),
                LocaleController.getString(R.string.DrGramGalleryCuratedHint));

        String activeKey = DrGramThemeInstaller.isFreeThemeMode()
                ? DrGramThemeInstaller.freeThemeName() : null;
        for (String[] entry : CURATED) {
            String asset = entry[0];
            String themeName = entry[1];
            int fallbackBg = parseColor(entry[3]);
            int fallbackAccent = parseColor(entry[4]);
            Map<String, Integer> colors = parseThemeAssetColors(asset);
            int bg = colors.containsKey("windowBackgroundWhite") ? colors.get("windowBackgroundWhite") : fallbackBg;
            int inBubble = colors.containsKey("chat_inBubble") ? colors.get("chat_inBubble") : 0xFF2A2F38;
            int outBubble = colors.containsKey("chat_outBubble") ? colors.get("chat_outBubble") : fallbackAccent;
            int accent = colors.containsKey("windowBackgroundChecked") ? colors.get("windowBackgroundChecked") : fallbackAccent;

            String localName = themeName;
            int nameRes = resId(entry[2]);
            if (nameRes != 0) {
                try {
                    localName = LocaleController.getString(nameRes);
                } catch (Throwable ignore) {
                }
            }
            boolean active = themeName.equals(activeKey);
            View card = makeCuratedCard(context, localName, themeName, bg, inBubble, outBubble, accent, active);
            contentView.addView(card);
            card.setOnClickListener(v -> {
                if (DrGramThemeInstaller.applyCuratedTheme(asset, themeName)) {
                    try {
                        Toast.makeText(getParentActivity(),
                                LocaleController.getString(R.string.DrGramGalleryApplied),
                                Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignore) {
                    }
                    rebuildEverything();
                } else {
                    try {
                        Toast.makeText(getParentActivity(),
                                LocaleController.getString(R.string.DrGramGalleryApplyFailed),
                                Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignore) {
                    }
                }
            });
        }
    }

    private void buildBackToBrandRow(Context context) {
        if (!DrGramThemeInstaller.isFreeThemeMode()) {
            return;
        }
        addHeader(context, LocaleController.getString(R.string.DrGramGalleryBackToBrand),
                LocaleController.getString(R.string.DrGramGalleryBackHint));
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundDrawable(goldCard(context));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(AndroidUtilities.dp(16), AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(4));
        row.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(13), AndroidUtilities.dp(16), AndroidUtilities.dp(13));
        row.setLayoutParams(rowParams);
        TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        title.setTextColor(0xFFFFF6DF);
        title.setText(LocaleController.getString(R.string.DrGramGalleryBackToBrand));
        row.addView(title);
        row.setOnClickListener(v -> {
            DrGramThemeInstaller.clearFreeThemeMode();
            if (DrGramThemeInstaller.setLoveMode(DrGramThemeInstaller.isLoveMode())) {
                rebuildEverything();
            }
        });
        contentView.addView(row);
    }

    //endregion

    //region card factories

    private LinearLayout makeBrandCard(Context context, String title, String subtitle, int accent,
                                       int gradStart, int gradEnd, boolean active) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.START);
        card.setPadding(AndroidUtilities.dp(15), AndroidUtilities.dp(13), AndroidUtilities.dp(13), AndroidUtilities.dp(13));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {gradStart, gradEnd});
        bg.setCornerRadius(AndroidUtilities.dp(17));
        bg.setStroke(AndroidUtilities.dp(active ? 2 : 1), accent);
        card.setBackgroundDrawable(bg);

        card.addView(makePreviewMock(context, 0xFF151109, 0xFF241D0E, accent, accent));

        TextView titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(accent);
        titleView.setText(title);
        titleView.setPadding(0, AndroidUtilities.dp(10), 0, 0);
        card.addView(titleView);

        TextView subView = new TextView(context);
        subView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        subView.setTextColor(0x99E4D2A5);
        subView.setText(subtitle);
        subView.setPadding(0, AndroidUtilities.dp(2), 0, 0);
        card.addView(subView);

        if (active) {
            card.addView(makeActiveBadge(context, accent), makeBadgeParams());
        }
        return card;
    }

    private View makeCuratedCard(Context context, String localName, String themeName,
                                 int bg, int inBubble, int outBubble, int accent, boolean active) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(AndroidUtilities.dp(15), AndroidUtilities.dp(13), AndroidUtilities.dp(13), AndroidUtilities.dp(13));
        GradientDrawable cardBg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0xFF3A2C0E, 0xFF211A0A});
        cardBg.setCornerRadius(AndroidUtilities.dp(17));
        cardBg.setStroke(AndroidUtilities.dp(active ? 2 : 1), active ? 0xFFFFC94A : 0x66FFC94A);
        card.setBackgroundDrawable(cardBg);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(AndroidUtilities.dp(16), AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(4));
        card.setLayoutParams(cardParams);

        LinearLayout previewRow = new LinearLayout(context);
        previewRow.setOrientation(LinearLayout.HORIZONTAL);
        previewRow.addView(makePreviewMock(context, bg, inBubble, outBubble, accent),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout nameCol = new LinearLayout(context);
        nameCol.setOrientation(LinearLayout.VERTICAL);
        nameCol.setGravity(Gravity.CENTER_VERTICAL);
        nameCol.setPadding(AndroidUtilities.dp(12), 0, 0, 0);
        TextView nameView = new TextView(context);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        nameView.setTypeface(AndroidUtilities.bold());
        nameView.setTextColor(0xFFFFF6DF);
        nameView.setText(localName);
        nameCol.addView(nameView);
        TextView subView = new TextView(context);
        subView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        subView.setTextColor(0x80E4D2A5);
        subView.setText(themeName);
        subView.setPadding(0, AndroidUtilities.dp(2), 0, 0);
        nameCol.addView(subView);
        if (active) {
            nameCol.addView(makeActiveBadge(context, 0xFFFFC94A), makeBadgeParams());
        }
        previewRow.addView(nameCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.2f));
        card.addView(previewRow);
        return card;
    }

    /**
     * A tiny chat mockup (background + incoming bubble + outgoing bubble)
     * painted with the theme's real colors.
     */
    private View makePreviewMock(Context context, int bg, int inBubble, int outBubble, int accent) {
        LinearLayout mock = new LinearLayout(context);
        mock.setOrientation(LinearLayout.VERTICAL);
        int width = AndroidUtilities.displaySize.x - AndroidUtilities.dp(120);
        mock.setLayoutParams(new LinearLayout.LayoutParams(
                Math.max(AndroidUtilities.dp(86), width / 2), AndroidUtilities.dp(76)));
        GradientDrawable mockBg = new GradientDrawable();
        mockBg.setCornerRadius(AndroidUtilities.dp(12));
        mockBg.setColor(bg);
        mockBg.setStroke(AndroidUtilities.dp(1), 0x24FFFFFF);
        mock.setBackgroundDrawable(mockBg);
        mock.setPadding(AndroidUtilities.dp(9), AndroidUtilities.dp(9), AndroidUtilities.dp(9), AndroidUtilities.dp(9));

        View inView = new View(context);
        GradientDrawable inBg = new GradientDrawable();
        inBg.setCornerRadius(AndroidUtilities.dp(10));
        inBg.setColor(inBubble);
        inView.setBackgroundDrawable(inBg);
        LinearLayout.LayoutParams inParams = new LinearLayout.LayoutParams(
                AndroidUtilities.dp(52), AndroidUtilities.dp(18));
        inParams.gravity = Gravity.START;
        mock.addView(inView, inParams);

        LinearLayout bottomRow = new LinearLayout(context);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bottomParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bottomParams.topMargin = AndroidUtilities.dp(7);
        bottomRow.setLayoutParams(bottomParams);
        View outView = new View(context);
        GradientDrawable outBg = new GradientDrawable();
        outBg.setCornerRadius(AndroidUtilities.dp(10));
        outBg.setColor(outBubble);
        outView.setBackgroundDrawable(outBg);
        bottomRow.addView(outView, new LinearLayout.LayoutParams(
                AndroidUtilities.dp(52), AndroidUtilities.dp(18)));
        View dotView = new View(context);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(accent);
        dotView.setBackgroundDrawable(dotBg);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(
                AndroidUtilities.dp(12), AndroidUtilities.dp(12));
        dotParams.gravity = Gravity.CENTER_VERTICAL;
        dotParams.leftMargin = AndroidUtilities.dp(8);
        bottomRow.addView(dotView, dotParams);
        mock.addView(bottomRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return mock;
    }

    private TextView makeActiveBadge(Context context, int accent) {
        TextView badge = new TextView(context);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
        badge.setTypeface(AndroidUtilities.bold());
        badge.setTextColor(0xFF0C0A05);
        badge.setGravity(Gravity.CENTER);
        badge.setText(LocaleController.getString(R.string.DrGramGalleryActive).toUpperCase());
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setCornerRadius(AndroidUtilities.dp(11));
        badgeBg.setColor(accent);
        badge.setBackgroundDrawable(badgeBg);
        badge.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(4), AndroidUtilities.dp(10), AndroidUtilities.dp(4));
        return badge;
    }

    private LinearLayout.LayoutParams makeBadgeParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = AndroidUtilities.dp(9);
        return params;
    }

    private GradientDrawable goldCard(Context context) {
        boolean loveMode = DrGramThemeInstaller.isLoveMode();
        GradientDrawable card = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                loveMode ? new int[] {0xFF2A1018, 0xFF1C0B11} : new int[] {0xFF3A2C0E, 0xFF211A0A});
        card.setCornerRadius(AndroidUtilities.dp(17));
        card.setStroke(AndroidUtilities.dp(1), loveMode ? 0x66E63B52 : 0x66FFC94A);
        return card;
    }

    //endregion

    //region helpers

    private int parseColor(String hex) {
        try {
            return (int) (0xFF000000L | Long.parseLong(hex.startsWith("0x") ? hex.substring(2) : hex, 16));
        } catch (Throwable t) {
            return 0xFF222222;
        }
    }

    private int resId(String key) {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            return ctx.getResources().getIdentifier(key, "string", ctx.getPackageName());
        } catch (Throwable t) {
            return 0;
        }
    }

    private boolean applyBrandMode(boolean loveMode) {
        DrGramThemeInstaller.clearFreeThemeMode();
        boolean applied = DrGramThemeInstaller.setLoveMode(loveMode);
        if (!applied && getParentActivity() != null) {
            Toast.makeText(getParentActivity(),
                    LocaleController.getString(R.string.DrGramSetThemeApplyFailed),
                    Toast.LENGTH_LONG).show();
        }
        return applied;
    }

    private void rebuildEverything() {
        if (parentLayout != null) {
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    parentLayout.rebuildAllFragmentViews(true, true);
                } catch (Throwable t) {
                    FileLog.e(t);
                }
            });
        }
    }

    /**
     * Reads the most useful preview colors straight from the bundled asset —
     * the gallery always shows what the theme really looks like.
     */
    private Map<String, Integer> parseThemeAssetColors(String asset) {
        Map<String, Integer> result = new HashMap<>();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(
                    new InputStreamReader(ApplicationLoader.applicationContext.getAssets().open(asset), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                if (!"windowBackgroundWhite".equals(key) && !"chat_inBubble".equals(key)
                        && !"chat_outBubble".equals(key) && !"windowBackgroundChecked".equals(key)) {
                    continue;
                }
                try {
                    result.put(key, Integer.parseInt(line.substring(eq + 1).trim()));
                } catch (Throwable ignore) {
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (Throwable ignore) {
            }
        }
        return result;
    }

    private void addHeader(Context context, String title, String subtitle) {
        TextView header = new TextView(context);
        header.setTypeface(AndroidUtilities.bold());
        header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        header.setTextColor(0xFFFFC94A);
        header.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(18), AndroidUtilities.dp(24), AndroidUtilities.dp(4));
        header.setText(title);
        contentView.addView(header);
        if (subtitle != null) {
            TextView sub = new TextView(context);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            sub.setTextColor(0x99E4D2A5);
            sub.setPadding(AndroidUtilities.dp(24), 0, AndroidUtilities.dp(24), AndroidUtilities.dp(4));
            sub.setText(subtitle);
            contentView.addView(sub);
        }
    }

    //endregion

    // Fix 13 pattern: self-managed edge-to-edge so the last rows clear the navbar
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
