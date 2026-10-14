/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGramSettingsActivity — the DrGram control center.
 * One page that gathers every DrGram capability plus fast shortcuts to the
 * native Telegram screens, organized as cards. Original layout and wording,
 * written for DrGram from scratch.
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
import android.widget.LinearLayout;
import org.telegram.ui.Components.Switch;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BirthdayController;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.DrGramAutoForward;
import org.telegram.messenger.DrGramFavorites;
import org.telegram.messenger.DrGramHiddenAccounts;
import org.telegram.messenger.DrGramQuickReplies;
import org.telegram.messenger.DrGramThemeInstaller;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.DrGramGlassKit;
import org.telegram.ui.Components.DrGramLuxDecor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Premium.boosts.UserSelectorBottomSheet;

import java.util.ArrayList;
import java.util.List;

public class DrGramSettingsActivity extends BaseFragment {

    private LinearLayout contentView;
    private Switch autoForwardSwitch;
    private Switch hideAdsSwitch;
    private Switch declineSecretSwitch;
    private LinearLayout rulesContainer;
    private LinearLayout repliesContainer;
    private TextView specialContactText;

    @Override
    public View createView(Context context) {
        // DrGram Fix 27: the hub must never take the app down. Whatever throws
        // inside gets logged, shown as a one-line diagnostic and replaced by a
        // minimal golden fallback view.
        try {
            // DrGram Fix 30: if the full hub failed recently, open the light
            // version first so the user ALWAYS gets a usable page (and can send
            // the recorded crash log). One tap restores the full hub.
            long failTs = MessagesController.getGlobalMainSettings().getLong("drgram_cc_fail_ts", 0);
            if (failTs > 0 && System.currentTimeMillis() - failTs < 30 * 60 * 1000L) {
                View lite = drgramLiteView(context);
                try {
                    MessagesController.getGlobalMainSettings().edit().putLong("drgram_cc_fail_ts", 0).commit();
                } catch (Throwable ignore) {}
                return lite;
            }
            return drgramCreateViewInternal(context);
        } catch (Throwable t) {
            return drgramFallbackView(context, t);
        }
    }

    private View drgramFallbackView(Context context, Throwable t) {
        FileLog.e(t);
        org.telegram.messenger.DrGramCrashSaver.recordNonFatal("control_center", t); // DrGram Fix 29
        try {
            MessagesController.getGlobalMainSettings().edit()
                    .putString("drgram_cc_error", android.util.Log.getStackTraceString(t))
                    .putLong("drgram_cc_fail_ts", System.currentTimeMillis()) // DrGram Fix 30: triggers lite mode
                    .commit();
        } catch (Throwable ignore) {}
        try {
            android.widget.Toast.makeText(ApplicationLoader.applicationContext,
                    LocaleController.formatString("DrGramCCFallback", R.string.DrGramCCFallback, t.getClass().getSimpleName()),
                    android.widget.Toast.LENGTH_LONG).show();
        } catch (Throwable ignore) {}
        try {
            actionBar.setBackButtonImage(R.drawable.ic_ab_back);
            actionBar.setAllowOverlayTitle(true);
            actionBar.setTitle(LocaleController.getString(R.string.DrGramSettingsCenter));
            actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
                @Override
                public void onItemClick(int id) {
                    if (id == -1) {
                        finishFragment();
                    }
                }
            });
        } catch (Throwable ignore) {}
        LinearLayout fb = new LinearLayout(context);
        fb.setOrientation(LinearLayout.VERTICAL);
        fb.setGravity(Gravity.CENTER);
        fb.setBackgroundColor(0xFF0C0A05);
        TextView tv = new TextView(context);
        tv.setTextColor(0xFFFFC94A);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        tv.setPadding(AndroidUtilities.dp(28), AndroidUtilities.dp(28), AndroidUtilities.dp(28), AndroidUtilities.dp(28));
        tv.setText(LocaleController.getString(R.string.DrGramCCFallbackText));
        fb.addView(tv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        fragmentView = fb;
        return fb;
    }

    @Override
    protected void onViewCreated(View view) {
        super.onViewCreated(view);
        // DrGram Fix 27: the GlassEngine post-draw hook is our prime suspect for
        // the frame-time crash on some devices — the hub does not need glass,
        // so the listener is detached right away.
        try {
            glassEngine.setRoot(null);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    /**
     * DrGram Fix 30: light control center — plain widgets, hardcoded palette,
     * zero dependency on section builders or dynamic data. Used after a recorded
     * failure so the page always opens; the full hub returns on the next tap.
     */
    private View drgramLiteView(Context context) {
        View built;
        try {
            built = drgramBuildLiteInternal(context);
        } catch (Throwable t) {
            return drgramFallbackView(context, t);
        }
        fragmentView = built;
        return built;
    }

    private View drgramBuildLiteInternal(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.DrGramSettingsCenter));
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
        content.setPadding(0, 0, 0, AndroidUtilities.dp(28));
        content.setBackground(DrGramGlassKit.pageGradient()); // DrGram Fix 36: glass canvas
        android.widget.ScrollView scroller = new android.widget.ScrollView(context);
        scroller.setFillViewport(true);
        // DrGram Fix 31: never let the framework draw scrollbars on our custom
        // screens — the lite view must be as crash-proof as the full hub.
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.addView(content, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView badge = new TextView(context);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        badge.setTextColor(0xFFFFC94A);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(26), AndroidUtilities.dp(24), 0);
        badge.setText(LocaleController.getString(R.string.DrGramCCLiteTitle));
        content.addView(badge);

        TextView text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        text.setTextColor(0xFFE4D2A5);
        text.setGravity(Gravity.CENTER);
        text.setLineSpacing(AndroidUtilities.dp(3), 1f);
        text.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), AndroidUtilities.dp(12));
        text.setText(LocaleController.getString(R.string.DrGramCCLiteText));
        content.addView(text);

        content.addView(makeLiteRow(context, LocaleController.getString(R.string.DrGramCCOpenFull), v -> {
            try {
                presentFragment(new DrGramSettingsActivity());
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }));
        content.addView(makeLiteRow(context, LocaleController.getString(R.string.DrGramSendCrash), v -> {
            try {
                if (getParentActivity() == null) {
                    return;
                }
                String crash = org.telegram.messenger.DrGramCrashSaver.lastCrashText();
                if (crash == null) {
                    android.widget.Toast.makeText(getParentActivity(),
                            LocaleController.getString(R.string.DrGramNoCrash), android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_SEND);
                intent.setType("text/plain");
                intent.putExtra(android.content.Intent.EXTRA_TEXT, crash);
                getParentActivity().startActivity(android.content.Intent.createChooser(intent, LocaleController.getString(R.string.DrGramSendCrash)));
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }));
        content.addView(makeLiteRow(context, LocaleController.getString(R.string.DrGramOpenToolsTitle), v -> {
            try {
                presentFragment(new DrGramToolsActivity());
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }));
        content.addView(makeLiteRow(context, LocaleController.getString(R.string.DrGramSetExploreOpen), v -> {
            try {
                presentFragment(new DrGramExploreActivity());
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }));
        return scroller;
    }

    private LinearLayout makeLiteRow(Context context, String title, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // DrGram Fix 36: flat glass row inside the section panel
        row.setBackgroundDrawable(DrGramGlassKit.rowRipple());
        row.setPadding(AndroidUtilities.dp(17), AndroidUtilities.dp(14), AndroidUtilities.dp(17), AndroidUtilities.dp(14));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(AndroidUtilities.dp(16), AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(4));
        row.setLayoutParams(rowParams);
        row.setOnClickListener(listener);
        TextView titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        titleView.setTextColor(0xFFFFF6DF);
        titleView.setText(title);
        row.addView(titleView);
        return row;
    }

    private View drgramCreateViewInternal(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.DrGramSettingsCenter));
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
        contentView.setBackground(DrGramGlassKit.pageGradient()); // DrGram Fix 36: glass canvas
        // DrGram Fix 31 (REAL crash fix, confirmed by user's logcat):
        // setScrollbarFadingEnabled(false) on Android 14 calls initScrollCache()
        // which creates a ScrollabilityCache with state=ON but a NULL
        // ScrollBarDrawable. The very first frame then dies inside
        // View.onDrawScrollBars at cache.scrollBar.mutate() — an NPE during
        // drawing, which is why no error dialog ever appeared and why the
        // createView guards of Fix 27/30 could never catch it. The flag was
        // pointless on a LinearLayout anyway, so it is simply removed.
        android.widget.ScrollView scroller = new android.widget.ScrollView(context);
        scroller.setFillViewport(true);
        scroller.setClipToPadding(false);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.addView(contentView, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fragmentView = scroller;

        try {
            addSignatureHero(context, LocaleController.getString(R.string.DrGramSettingsHero));
            contentView.addView(DrGramLuxDecor.ornamentDivider(context, DrGramThemeInstaller.isLoveMode())); // DrGram Fix 24
        } catch (Throwable t) {
            // DrGram Fix 14 (hardening): the hero is decoration — never crash the hub.
            FileLog.e(t);
        }
        // DrGram Fix 15 (hardening): no section is crash-critical. Whatever throws
        // on any device gets logged and skipped — the hub must always open.
        safeSection("general", this::buildGeneralSection);
        safeSection("connection", this::buildConnectionSection); // DrGram Fix 26
        safeSection("theme", this::buildThemeSection);
        safeSection("chatList", this::buildChatListSection);
        safeSection("chat", this::buildChatSection);
        safeSection("gifts", this::buildGiftsSection);
        safeSection("autoForward", this::buildAutoForwardSection);
        safeSection("quickReplies", this::buildQuickRepliesSection);
        safeSection("autoReply", this::buildAutoReplySection);
        safeSection("special", this::buildSpecialSection);
        safeSection("privacy", this::buildPrivacySection);
        safeSection("media", this::buildMediaSection);
        safeSection("notify", this::buildNotifySection);
        safeSection("hotNotifications", this::buildHotNotificationsSection);
        safeSection("accounts", this::buildAccountsSection);
        safeSection("contacts", this::buildContactsSection);
        safeSection("favTags", this::buildFavTagsSection);

        try {
            contentView.addView(DrGramLuxDecor.signatureFooter(context, DrGramThemeInstaller.isLoveMode())); // DrGram Fix 24
        } catch (Throwable t) {
            FileLog.e(t);
        }

        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        try {
            refreshBrandSurfaces();
            refreshDynamicRows();
        } catch (Throwable t) {
            // DrGram Fix 15 (hardening): dynamic refresh must never crash the hub.
            FileLog.e(t);
        }
    }

    private interface SectionBuilder {
        void build(Context context);
    }

    private Context context() {
        return getParentActivity() != null ? getParentActivity() : applicationContext();
    }

    private Context applicationContext() {
        return ApplicationLoader.applicationContext;
    }

    private void safeSection(String name, SectionBuilder builder) {
        try {
            builder.build(context());
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }


    /**
     * DrGram Signature hero: a deliberate luxury/romance brand moment, not a generic settings header.
     */
    private void addSignatureHero(Context context, String subtitle) {
        boolean love = DrGramThemeInstaller.isLoveMode();
        int accent = DrGramThemeInstaller.accentColor();
        LinearLayout hero = new LinearLayout(context);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER);
        hero.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(30), AndroidUtilities.dp(24), AndroidUtilities.dp(27));
        // DrGram Fix 36: glassmorphism hero — translucent panel + hairline stroke
        hero.setBackground(DrGramGlassKit.heroPanel(context));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.setMargins(AndroidUtilities.dp(16), AndroidUtilities.dp(18), AndroidUtilities.dp(16), AndroidUtilities.dp(8));
        contentView.addView(hero, hp);

        TextView emblem = new TextView(context);
        emblem.setText(love ? "♡  ✦  ♡" : "✦  ◈  ✦");
        emblem.setTextColor(love ? 0xFFFFA4B5 : 0xFFFFE6A0);
        emblem.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 25);
        emblem.setGravity(Gravity.CENTER);
        hero.addView(emblem, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView brand = new TextView(context);
        brand.setText(love ? "LOVE  MODE" : "GOLD  LUXE");
        brand.setTextColor(love ? 0xFFFFDCE3 : 0xFFFFE8AE);
        brand.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 25);
        brand.setTypeface(AndroidUtilities.bold());
        brand.setGravity(Gravity.CENTER);
        brand.setLetterSpacing(0.10f);
        LinearLayout.LayoutParams bp = LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT);
        bp.topMargin = AndroidUtilities.dp(7);
        hero.addView(brand, bp);

        TextView tagline = new TextView(context);
        tagline.setText(subtitle);
        tagline.setTextColor(love ? 0xFFE8B8C3 : 0xFFE4D2A5);
        tagline.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        tagline.setGravity(Gravity.CENTER);
        tagline.setLineSpacing(AndroidUtilities.dp(3), 1f);
        LinearLayout.LayoutParams tp = LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT);
        tp.topMargin = AndroidUtilities.dp(8);
        hero.addView(tagline, tp);

        TextView state = new TextView(context);
        state.setText(love ? "♥  ROMANTIC • DEEP • ELEGANT" : "✧  CHAMPAGNE • ONYX • TIMELESS");
        state.setTextColor(accent);
        state.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
        state.setGravity(Gravity.CENTER);
        state.setTypeface(AndroidUtilities.bold());
        state.setLetterSpacing(0.06f);
        android.graphics.drawable.GradientDrawable capsule = new android.graphics.drawable.GradientDrawable();
        capsule.setColor(love ? 0x443D0E1A : 0x443D2D0A);
        capsule.setCornerRadius(AndroidUtilities.dp(30));
        capsule.setStroke(AndroidUtilities.dp(1), love ? 0x99F06A82 : 0x99F4D27A);
        state.setBackground(capsule);
        state.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(8), AndroidUtilities.dp(14), AndroidUtilities.dp(8));
        LinearLayout.LayoutParams sp = LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT);
        sp.topMargin = AndroidUtilities.dp(16);
        hero.addView(state, sp);
    }

    //region 1. General

    private void buildGeneralSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramSetGeneral),
                LocaleController.getString(R.string.DrGramSetGeneralHint));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetMyProfile), v -> {
            Bundle args = new Bundle();
            args.putLong("user_id", getUserConfig().clientUserId);
            presentFragment(new ProfileActivity(args));
        }));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetAppearance), v ->
                chooseBrandMode()));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramAiToolsTitle), v ->
                presentFragment(new DrGramAIActivity())));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramQuickLinksTitle), v ->
                presentFragment(new DrGramQuickLinksActivity())));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramOpenToolsTitle), v ->
                presentFragment(new DrGramToolsActivity())));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetLanguage), v ->
                presentFragment(new LanguageSelectActivity())));
    }

    //endregion

    //region 1b. Luxury theme (Fix 8)

    private LinearLayout themeCardGold;
    private LinearLayout themeCardRed;
    private android.widget.SeekBar goldSlider;
    private android.widget.SeekBar redSlider;
    private android.widget.TextView goldSliderValue;
    private android.widget.TextView redSliderValue;
    private android.view.View goldSwatch;
    private android.view.View redSwatch;
    private boolean goldSliderDragging;
    private boolean redSliderDragging;

    /**
     * DrGram Fix 32: the custom-mode section was two tiny pills the user could
     * barely find (and even asked "where is it?!"). Rebuilt as two large cards
     * with descriptions plus a tuning panel: the amount of gold and the amount
     * of red of the two branded palettes are now adjustable with sliders.
     * Default is the light-gold look.
     */
    private void buildThemeSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramSetTheme),
                LocaleController.getString(R.string.DrGramSetThemeHint));

        LinearLayout cardsRow = new LinearLayout(context);
        cardsRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(AndroidUtilities.dp(14), AndroidUtilities.dp(4), AndroidUtilities.dp(14), AndroidUtilities.dp(6));
        contentView.addView(cardsRow, rowParams);

        themeCardGold = makeThemeCard(context, LocaleController.getString(R.string.DrGramSetThemeGold),
                LocaleController.getString(R.string.DrGramSetThemeGoldDesc), 0xFFF5B90D, false);
        themeCardRed = makeThemeCard(context, LocaleController.getString(R.string.DrGramSetThemeRed),
                LocaleController.getString(R.string.DrGramSetThemeRedDesc), 0xFFE63B52, true);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cardParams.setMargins(AndroidUtilities.dp(2), 0, AndroidUtilities.dp(2), 0);
        cardsRow.addView(themeCardGold, cardParams);
        cardsRow.addView(themeCardRed, cardParams);
        // DrGram Fix 32c: the two brand cards measured different heights whenever
        // one title/subtitle wrapped while the other didn't ("one small, one big"
        // in the user's screenshot). After the first layout pass, lock both cards
        // to the taller sibling's height so they always render as equal twins.
        cardsRow.post(() -> {
            try {
                int goldH = themeCardGold.getMeasuredHeight();
                int redH = themeCardRed.getMeasuredHeight();
                int target = Math.max(goldH, redH);
                if (target > 0 && (goldH != redH)) {
                    themeCardGold.setMinimumHeight(target);
                    themeCardRed.setMinimumHeight(target);
                    cardsRow.requestLayout();
                }
            } catch (Throwable t) {
                FileLog.e(t);
            }
        });

        themeCardGold.setOnClickListener(v -> applyBrandMode(false));
        themeCardRed.setOnClickListener(v -> applyBrandMode(true));

        buildTuningPanel(context);

        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramGalleryOpen), v -> {
            try {
                presentFragment(new DrGramStyleGalleryActivity());
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }));
        refreshThemePills();
    }

    private LinearLayout makeThemeCard(Context context, String title, String subtitle, int accent, boolean red) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.START);
        card.setClickable(true);
        card.setFocusable(true);
        card.setPadding(AndroidUtilities.dp(15), AndroidUtilities.dp(13), AndroidUtilities.dp(13), AndroidUtilities.dp(13));

        android.view.View dot = new android.view.View(context);
        android.graphics.drawable.GradientDrawable dotBg = new android.graphics.drawable.GradientDrawable();
        dotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dotBg.setColor(accent);
        dot.setBackgroundDrawable(dotBg);
        card.addView(dot, LayoutHelper.createLinear(16, 16, 0, 0, 0, 0));

        android.widget.TextView titleView = new android.widget.TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(accent);
        titleView.setText(title);
        titleView.setPadding(0, AndroidUtilities.dp(9), 0, 0);
        card.addView(titleView);

        android.widget.TextView subView = new android.widget.TextView(context);
        subView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        subView.setTextColor(0x99E4D2A5);
        subView.setText(subtitle);
        subView.setPadding(0, AndroidUtilities.dp(2), 0, 0);
        card.addView(subView);
        return card;
    }

    private void buildTuningPanel(Context context) {
        boolean love = DrGramThemeInstaller.isLoveMode();
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        // DrGram Fix 36: glass tuning panel
        panel.setBackground(DrGramGlassKit.panel(context, 18));
        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        panelParams.setMargins(AndroidUtilities.dp(16), AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(6));
        panel.setPadding(AndroidUtilities.dp(17), AndroidUtilities.dp(13), AndroidUtilities.dp(17), AndroidUtilities.dp(15));
        contentView.addView(panel, panelParams);

        // --- goldness slider (Gold Mode) ---
        panel.addView(makeSliderLabel(context, LocaleController.getString(R.string.DrGramThemeGoldness), 0xFFE9C55F));
        goldSwatch = new android.view.View(context);
        panel.addView(makeSwatchRow(context, goldSwatch, 0xFFE9C55F));
        goldSlider = makeSlider(context, DrGramThemeInstaller.getGoldness(), 0xFFF0BE3F);
        panel.addView(goldSlider);
        goldSliderValue = makeSliderValue(context);
        panel.addView(goldSliderValue);
        goldSlider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                if (goldSliderValue != null) {
                    goldSliderValue.setText(progress + "%");
                }
                if (goldSwatch != null) {
                    goldSwatch.setBackgroundDrawable(swatchDrawable(
                            org.telegram.messenger.DrGramThemeTuner.previewAccent(false, progress)));
                }
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {
                goldSliderDragging = true;
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                goldSliderDragging = false;
                applyIntensity(false, seekBar.getProgress());
            }
        });

        // --- redness slider (LOVE Mode) ---
        panel.addView(makeSliderLabel(context, LocaleController.getString(R.string.DrGramThemeRedness), 0xFFF0778C));
        redSwatch = new android.view.View(context);
        panel.addView(makeSwatchRow(context, redSwatch, 0xFFF0778C));
        redSlider = makeSlider(context, DrGramThemeInstaller.getRedness(), 0xFFE63B52);
        panel.addView(redSlider);
        redSliderValue = makeSliderValue(context);
        panel.addView(redSliderValue);
        redSlider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                if (redSliderValue != null) {
                    redSliderValue.setText(progress + "%");
                }
                if (redSwatch != null) {
                    redSwatch.setBackgroundDrawable(swatchDrawable(
                            org.telegram.messenger.DrGramThemeTuner.previewAccent(true, progress)));
                }
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {
                redSliderDragging = true;
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                redSliderDragging = false;
                applyIntensity(true, seekBar.getProgress());
            }
        });

        android.widget.TextView hint = new android.widget.TextView(context);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        hint.setTextColor(0x80E4D2A5);
        hint.setText(LocaleController.getString(R.string.DrGramThemeIntensityHint));
        hint.setPadding(0, AndroidUtilities.dp(6), 0, 0);
        panel.addView(hint);
    }

    private android.widget.TextView makeSliderLabel(Context context, String text, int color) {
        android.widget.TextView label = new android.widget.TextView(context);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        label.setTypeface(AndroidUtilities.bold());
        label.setTextColor(color);
        label.setText(text);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = AndroidUtilities.dp(4);
        label.setLayoutParams(params);
        return label;
    }

    private LinearLayout makeSwatchRow(Context context, android.view.View swatch, int fallbackColor) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = AndroidUtilities.dp(7);
        row.setLayoutParams(params);
        swatch.setBackgroundDrawable(swatchDrawable(fallbackColor));
        row.addView(swatch, LayoutHelper.createLinear(26, 26, 0, 0, 0, 0));
        android.widget.TextView caption = new android.widget.TextView(context);
        caption.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        caption.setTextColor(0x80E4D2A5);
        caption.setText(DrGramThemeInstaller.isLoveMode() ? "E63B52" : "F5B90D");
        caption.setPadding(AndroidUtilities.dp(9), 0, 0, 0);
        row.addView(caption, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private android.graphics.drawable.GradientDrawable swatchDrawable(int color) {
        android.graphics.drawable.GradientDrawable swatch = new android.graphics.drawable.GradientDrawable();
        swatch.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        swatch.setColor(color);
        swatch.setStroke(AndroidUtilities.dp(2), 0x66FFFFFF);
        return swatch;
    }

    private android.widget.SeekBar makeSlider(Context context, int value, int accent) {
        android.widget.SeekBar slider = new android.widget.SeekBar(context);
        slider.setMax(100);
        slider.setProgress(value);
        slider.setSplitTrack(false);
        android.graphics.drawable.GradientDrawable track = new android.graphics.drawable.GradientDrawable();
        track.setCornerRadius(AndroidUtilities.dp(3));
        track.setColor(0x2EFFFFFF);
        android.graphics.drawable.ClipDrawable progress = new android.graphics.drawable.ClipDrawable(
                track, android.view.Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        android.graphics.drawable.GradientDrawable thumb = new android.graphics.drawable.GradientDrawable();
        thumb.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        thumb.setColor(accent);
        thumb.setStroke(AndroidUtilities.dp(2), 0x73FFFFFF);
        thumb.setSize(AndroidUtilities.dp(22), AndroidUtilities.dp(22));
        slider.setProgressDrawable(progress);
        slider.setThumb(thumb);
        slider.setThumbOffset(AndroidUtilities.dp(3));
        slider.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(6), AndroidUtilities.dp(14), AndroidUtilities.dp(6));
        slider.setBackground(null);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = AndroidUtilities.dp(2);
        slider.setLayoutParams(params);
        return slider;
    }

    private android.widget.TextView makeSliderValue(Context context) {
        android.widget.TextView value = new android.widget.TextView(context);
        value.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        value.setTextColor(0x99E4D2A5);
        value.setGravity(Gravity.END);
        value.setPadding(0, 0, AndroidUtilities.dp(2), AndroidUtilities.dp(4));
        return value;
    }

    private void applyIntensity(boolean red, int value) {
        boolean applied = DrGramThemeInstaller.setIntensity(red, value);
        if (applied) {
            refreshBrandSurfaces();
            refreshThemePills();
            if (parentLayout != null) {
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        parentLayout.rebuildAllFragmentViews(true, true);
                    } catch (Throwable t) {
                        FileLog.e(t);
                    }
                });
            }
        } else if (getParentActivity() != null) {
            android.widget.Toast.makeText(getParentActivity(),
                    LocaleController.getString(R.string.DrGramSetThemeApplyFailed),
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void chooseBrandMode() {
        if (getParentActivity() == null) {
            return;
        }
        String[] modes = {
                LocaleController.getString(R.string.DrGramSetThemeGold),
                LocaleController.getString(R.string.DrGramSetThemeRed)
        };
        new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.DrGramSetTheme))
                .setItems(modes, (dialog, which) -> applyBrandMode(which == 1))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private void applyBrandMode(boolean loveMode) {
        boolean applied = DrGramThemeInstaller.setLoveMode(loveMode);
        refreshBrandSurfaces();
        refreshThemePills();
        if (applied && parentLayout != null) {
            // DrGram Fix 14 (hardening): rebuild off the click stack, guarded —
            // a fragment rebuild while a click handler is on the call stack can
            // destabilize the transaction state on some devices.
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    parentLayout.rebuildAllFragmentViews(true, true);
                } catch (Throwable t) {
                    FileLog.e(t);
                }
            });
        }
        if (!applied && getParentActivity() != null) {
            android.widget.Toast.makeText(getParentActivity(),
                    LocaleController.getString(R.string.DrGramSetThemeApplyFailed),
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private LinearLayout makeThemePill(Context context, String label, int accent, boolean red) {
        LinearLayout pill = new LinearLayout(context);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER);
        // These are actual mode switches, independent of Android's system theme.
        pill.setClickable(true);
        pill.setFocusable(true);

        android.widget.ImageView dot = new android.widget.ImageView(context);
        android.graphics.drawable.GradientDrawable dotBg = new android.graphics.drawable.GradientDrawable();
        dotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dotBg.setColor(accent);
        dot.setBackgroundDrawable(dotBg);
        pill.addView(dot, LayoutHelper.createLinear(14, 14, 0, 0, 0, 0));

        android.widget.TextView labelView = new android.widget.TextView(context);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        labelView.setTypeface(AndroidUtilities.bold());
        labelView.setTextColor(accent);
        labelView.setText(label);
        labelView.setPadding(AndroidUtilities.dp(8), 0, 0, 0);
        pill.addView(labelView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        return pill;
    }

    private void refreshThemePills() {
        if (themeCardGold == null || themeCardRed == null) {
            return;
        }
        boolean loveMode = DrGramThemeInstaller.isLoveMode();
        android.graphics.drawable.GradientDrawable goldBg = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                loveMode ? new int[] {0xFF231A0A, 0xFF1C1408} : new int[] {0xFF4A3810, 0xFF2B2009});
        goldBg.setCornerRadius(AndroidUtilities.dp(17));
        goldBg.setStroke(AndroidUtilities.dp(loveMode ? 1 : 2), 0xFFFFC94A);
        themeCardGold.setBackgroundDrawable(goldBg);
        android.graphics.drawable.GradientDrawable redBg = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                loveMode ? new int[] {0xFF3B1420, 0xFF250D15} : new int[] {0xFF221018, 0xFF190B10});
        redBg.setCornerRadius(AndroidUtilities.dp(17));
        redBg.setStroke(AndroidUtilities.dp(loveMode ? 2 : 1), 0xFFE63B52);
        themeCardRed.setBackgroundDrawable(redBg);
        // The thicker stroke marks the currently active mode.
        themeCardGold.setClickable(true);
        themeCardRed.setClickable(true);
        if (goldSlider != null) {
            goldSlider.setProgress(DrGramThemeInstaller.getGoldness());
        }
        if (redSlider != null) {
            redSlider.setProgress(DrGramThemeInstaller.getRedness());
        }
        if (goldSliderValue != null) {
            goldSliderValue.setText(DrGramThemeInstaller.getGoldness() + "%");
        }
        if (redSliderValue != null) {
            redSliderValue.setText(DrGramThemeInstaller.getRedness() + "%");
        }
        if (goldSwatch != null) {
            goldSwatch.setBackgroundDrawable(swatchDrawable(
                    org.telegram.messenger.DrGramThemeTuner.previewAccent(false, DrGramThemeInstaller.getGoldness())));
        }
        if (redSwatch != null) {
            redSwatch.setBackgroundDrawable(swatchDrawable(
                    org.telegram.messenger.DrGramThemeTuner.previewAccent(true, DrGramThemeInstaller.getRedness())));
        }
    }

    //endregion

    //region 2. Chat feel

    private void buildChatListSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramSetChatList),
                LocaleController.getString(R.string.DrGramSetChatListHint));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetChatListOpen), v ->
                presentFragment(new FiltersSetupActivity())));
    }

    private void buildConnectionSection(Context context) { // DrGram Fix 26: auto proxy
        addHeader(context, LocaleController.getString(R.string.DrGramSetConnection),
                LocaleController.getString(R.string.DrGramSetConnectionHint));

        Switch proxySwitch = new Switch(context);
        proxySwitch.setChecked(SharedConfig.drgramAutoProxy, false);
        contentView.addView(makeToggleRow(context, LocaleController.getString(R.string.DrGramSetAutoProxy), proxySwitch, () -> {
            SharedConfig.toggleDrgramAutoProxy();
            proxySwitch.setChecked(SharedConfig.drgramAutoProxy, false);
            if (SharedConfig.drgramAutoProxy) {
                org.telegram.messenger.DrGramAutoProxy.onAppOpen(); // apply right away
            }
        }));
    }

    private void buildGiftsSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramSetGifts),
                LocaleController.getString(R.string.DrGramSetGiftsHint));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetGiftsOpen), v ->
                UserSelectorBottomSheet.open(0, BirthdayController.getInstance(currentAccount).getState())));
    }

    private void buildChatSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramSetChat),
                LocaleController.getString(R.string.DrGramSetChatHint));

        Switch goldSwitch = new Switch(context);
        goldSwitch.setChecked(SharedConfig.drgramGoldChat, false);
        contentView.addView(makeToggleRow(context, LocaleController.getString(R.string.DrGramSetGoldChats), goldSwitch, () -> {
            SharedConfig.toggleDrgramGoldChat();
            goldSwitch.setChecked(SharedConfig.drgramGoldChat, false);
        }));

        Switch sparksSwitch = new Switch(context);
        sparksSwitch.setChecked(SharedConfig.drgramSendSparks, false);
        contentView.addView(makeToggleRow(context, LocaleController.getString(R.string.DrGramSetSparks), sparksSwitch, () -> {
            SharedConfig.toggleDrgramSendSparks();
            sparksSwitch.setChecked(SharedConfig.drgramSendSparks, false);
        }));

        Switch glassSwitch = new Switch(context);
        glassSwitch.setChecked(SharedConfig.drgramGlassUI, false);
        contentView.addView(makeToggleRow(context, LocaleController.getString(R.string.DrGramSetGlass), glassSwitch, () -> {
            SharedConfig.toggleDrgramGlassUI();
            glassSwitch.setChecked(SharedConfig.drgramGlassUI, false);
        }));

        Switch rainSwitch = new Switch(context);
        rainSwitch.setChecked(SharedConfig.drgramReactionRain, false);
        contentView.addView(makeToggleRow(context, LocaleController.getString(R.string.DrGramSetRain), rainSwitch, () -> {
            SharedConfig.toggleDrgramReactionRain();
            rainSwitch.setChecked(SharedConfig.drgramReactionRain, false);
        }));

        Switch membersBarSwitch = new Switch(context); // DrGram Fix 21
        membersBarSwitch.setChecked(SharedConfig.drgramMembersBar, false);
        contentView.addView(makeToggleRow(context, LocaleController.getString(R.string.DrGramSetMembersBar), membersBarSwitch, () -> {
            SharedConfig.toggleDrgramMembersBar();
            membersBarSwitch.setChecked(SharedConfig.drgramMembersBar, false);
        }));

        Switch jalaliSwitch = new Switch(context); // DrGram Fix 21
        jalaliSwitch.setChecked(SharedConfig.drgramJalaliDates, false);
        contentView.addView(makeToggleRow(context, LocaleController.getString(R.string.DrGramSetJalaliDates), jalaliSwitch, () -> {
            SharedConfig.toggleDrgramJalaliDates();
            jalaliSwitch.setChecked(SharedConfig.drgramJalaliDates, false);
        }));
    }

    //endregion

    //region 3. Auto relay

    private void buildAutoForwardSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramSetRelay),
                LocaleController.getString(R.string.DrGramSetRelayHint));

        autoForwardSwitch = new Switch(context);
        autoForwardSwitch.setChecked(SharedConfig.drgramAutoForward, false);
        contentView.addView(makeToggleRow(context, LocaleController.getString(R.string.DrGramSetRelayOn), autoForwardSwitch, () -> {
            SharedConfig.toggleDrgramAutoForward();
            autoForwardSwitch.setChecked(SharedConfig.drgramAutoForward, false);
        }));

        rulesContainer = new LinearLayout(context);
        rulesContainer.setOrientation(LinearLayout.VERTICAL);
        contentView.addView(rulesContainer);

        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetRelayAdd), v ->
                drgramStartAddRule()));
    }

    private void drgramStartAddRule() {
        if (getParentActivity() == null) {
            return;
        }
        drgramPickDialog(LocaleController.getString(R.string.DrGramSetRelayPickSource), 0L, dialogId ->
                drgramPickDialog(LocaleController.getString(R.string.DrGramSetRelayPickTarget), dialogId, targetId -> {
                    if (targetId == dialogId) {
                        android.widget.Toast.makeText(getParentActivity(), LocaleController.getString(R.string.DrGramSetRelaySame), android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    drgramAskMedia(media -> {
                        DrGramAutoForward.addRule(currentAccount, dialogId, targetId, media);
                        refreshDynamicRows();
                        android.widget.Toast.makeText(getParentActivity(), LocaleController.getString(R.string.DrGramSetRelayAdded), android.widget.Toast.LENGTH_SHORT).show();
                    });
                }));
    }

    /** DrGram Fix 24: plain callback — the JDK Consumer interface needs API 24 and made
     *  this page the only DrGram screen with a raw functional-interface dependency (crash on old devices). */
    private interface DrGramBoolCallback {
        void run(boolean value);
    }

    /** DrGram Fix 24: plain callback for the chat picker (see DrGramBoolCallback). */
    private interface DrGramPickCallback {
        void onPicked(long dialogId);
    }

    private void drgramAskMedia(DrGramBoolCallback callback) {
        if (getParentActivity() == null) {
            return;
        }
        String[] items = {
                LocaleController.getString(R.string.DrGramSetRelayTextOnly),
                LocaleController.getString(R.string.DrGramSetRelayWithMedia)
        };
        new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.DrGramSetRelayWhat))
                .setItems(items, (dialog, which) -> callback.run(which == 1))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    //endregion

    //region 4. Quick replies

    private void buildQuickRepliesSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramSetReplies),
                LocaleController.getString(R.string.DrGramSetRepliesHint));
        repliesContainer = new LinearLayout(context);
        repliesContainer.setOrientation(LinearLayout.VERTICAL);
        contentView.addView(repliesContainer);
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetRepliesAdd), v ->
                drgramEditReplyDialog(0, "", "")));
    }

    private void drgramEditReplyDialog(long id, String presetTitle, String presetText) {
        if (getParentActivity() == null) {
            return;
        }
        Context context = getParentActivity();
        // DrGram Fix 32: the old dialog used a near-invisible 10%-white field
        // background, zero spacing between the two fields and no inner padding —
        // the user could not tell where the fields were. Redesigned: solid
        // dark-gold fields with a bright stroke, real inner padding and a 12dp
        // gap, wrapped in a scroll view so the keyboard never hides the text.
        boolean love = DrGramThemeInstaller.isLoveMode();
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(10), AndroidUtilities.dp(22), AndroidUtilities.dp(4));
        android.graphics.drawable.GradientDrawable replyFieldBg = new android.graphics.drawable.GradientDrawable();
        replyFieldBg.setCornerRadius(AndroidUtilities.dp(14));
        replyFieldBg.setColor(love ? 0xFF2B121A : 0xFF2B2210);
        replyFieldBg.setStroke(AndroidUtilities.dp(1), love ? 0x8CF06A82 : 0x8CF4D27A);
        EditText titleField = new EditText(context);
        titleField.setHint(LocaleController.getString(R.string.DrGramSetRepliesName));
        titleField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        titleField.setSingleLine(true);
        titleField.setText(presetTitle);
        // DrGram Fix 14: guaranteed readable text on the dark luxury dialogs —
        // bare EditTexts resolved a dark framework color on the dark background.
        titleField.setTextColor(0xFFFFF6DF);
        titleField.setHintTextColor(0xCCB9AD95);
        titleField.setBackground(replyFieldBg);
        titleField.setPadding(AndroidUtilities.dp(15), AndroidUtilities.dp(13), AndroidUtilities.dp(15), AndroidUtilities.dp(13));
        EditText textField = new EditText(context);
        textField.setHint(LocaleController.getString(R.string.DrGramSetRepliesText));
        textField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        textField.setMinLines(3);
        textField.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        textField.setText(presetText);
        textField.setTextColor(0xFFFFF6DF);
        textField.setHintTextColor(0xCCB9AD95);
        textField.setBackground(replyFieldBg);
        textField.setPadding(AndroidUtilities.dp(15), AndroidUtilities.dp(13), AndroidUtilities.dp(15), AndroidUtilities.dp(13));
        box.addView(titleField, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textParams.topMargin = AndroidUtilities.dp(12);
        box.addView(textField, textParams);
        android.widget.ScrollView replyScroller = new android.widget.ScrollView(context);
        replyScroller.setVerticalScrollBarEnabled(false);
        replyScroller.setHorizontalScrollBarEnabled(false);
        replyScroller.addView(box, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(id == 0 ? LocaleController.getString(R.string.DrGramSetRepliesAdd) : LocaleController.getString(R.string.DrGramSetRepliesEdit))
                .setView(replyScroller)
                .setPositiveButton(LocaleController.getString(R.string.Save), (dialog, which) -> {
                    String title = titleField.getText().toString().trim();
                    String text = textField.getText().toString().trim();
                    if (text.isEmpty()) {
                        return;
                    }
                    if (id == 0) {
                        DrGramQuickReplies.add(title, text);
                    } else {
                        DrGramQuickReplies.update(id, title, text);
                    }
                    refreshDynamicRows();
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        if (id != 0) {
            builder.setNeutralButton(LocaleController.getString(R.string.Delete), (dialog, which) -> {
                DrGramQuickReplies.remove(id);
                refreshDynamicRows();
            });
        }
        builder.show();
    }

    private void buildAutoReplySection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramOpenToolsTitle),
                LocaleController.getString(R.string.DrGramOpenToolsHint));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSecretaryTitle), v ->
                presentFragment(new DrGramToolsActivity())));
    }

    //endregion

    //region 5. Pinned contact

    private void buildSpecialSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramSetSpecial),
                LocaleController.getString(R.string.DrGramSetSpecialHint));
        specialContactText = new TextView(context);
        specialContactText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        specialContactText.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        LinearLayout row = makeRow(context, LocaleController.getString(R.string.DrGramSetSpecialPick), specialContactText);
        row.setOnClickListener(v -> {
            if (SharedConfig.drgramSpecialContact != 0) {
                Bundle args = new Bundle();
                args.putLong("dialog_id", SharedConfig.drgramSpecialContact);
                presentFragment(new ChatActivity(args));
            } else {
                drgramPickSpecialContact();
            }
        });
        contentView.addView(row);
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetSpecialChange), v ->
                drgramPickSpecialContact()));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetSpecialClear), v -> {
            SharedConfig.setDrgramSpecialContact(0);
            refreshDynamicRows();
        }));
    }

    private void drgramPickSpecialContact() {
        drgramPickDialog(LocaleController.getString(R.string.DrGramSetSpecialPick), 0, dialogId -> {
            SharedConfig.setDrgramSpecialContact(dialogId);
            refreshDynamicRows();
        });
    }

    //endregion

    //region 6. Privacy

    private void buildPrivacySection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramSetPrivacy),
                LocaleController.getString(R.string.DrGramSetPrivacyHint));

        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetPasscode), v ->
                presentFragment(new PasscodeActivity(PasscodeActivity.TYPE_MANAGE_CODE_SETTINGS))));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetPrivacyNative), v ->
                presentFragment(new PrivacySettingsActivity())));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetVault), v ->
                presentFragment(new DrGramVaultActivity())));

        hideAdsSwitch = new Switch(context);
        hideAdsSwitch.setChecked(SharedConfig.drgramHideChannelAds, false);
        contentView.addView(makeToggleRow(context, LocaleController.getString(R.string.DrGramSetNoAds), hideAdsSwitch, () -> {
            SharedConfig.toggleDrgramHideChannelAds();
            hideAdsSwitch.setChecked(SharedConfig.drgramHideChannelAds, false);
        }));

        declineSecretSwitch = new Switch(context);
        declineSecretSwitch.setChecked(SharedConfig.drgramDeclineSecretChats, false);
        contentView.addView(makeToggleRow(context, LocaleController.getString(R.string.DrGramSetNoSecret), declineSecretSwitch, () -> {
            SharedConfig.toggleDrgramDeclineSecretChats();
            declineSecretSwitch.setChecked(SharedConfig.drgramDeclineSecretChats, false);
        }));
    }

    //endregion

    //region 7. Files and media

    private void buildMediaSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramSetMedia),
                LocaleController.getString(R.string.DrGramSetMediaHint));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetDownloads), v -> {
            Bundle args = new Bundle();
            args.putBoolean("drgramStartDownloads", true);
            presentFragment(new DialogsActivity(args));
        }));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramLinkExtractorTitle), v ->
                presentFragment(new DrGramLinkExtractorActivity())));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetStorage), v ->
                presentFragment(new CacheControlActivity())));
    }

    //endregion

    //region 8. Notifications

    private void buildNotifySection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramSetNotify),
                LocaleController.getString(R.string.DrGramSetNotifyHint));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetNotifyOpen), v ->
                presentFragment(new NotificationsSettingsActivity())));

    }

    //endregion

    //region 9. Accounts

    private void buildHotNotificationsSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramSetHotNotifications),
                LocaleController.getString(R.string.DrGramSetHotNotificationsHint));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramFakeNotificationsTitle), v ->
                presentFragment(new DrGramFakeNotificationsActivity())));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramToolsTitle), v ->
                presentFragment(new DrGramToolsActivity())));
    }

    private void buildAccountsSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramSetAccounts),
                LocaleController.getString(R.string.DrGramSetAccountsHint));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetAccountsSwitch), v ->
                drgramShowAccountSwitcher()));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramHiddenAccountsTitle), v ->
                drgramManageHiddenAccounts()));
    }

    private void drgramShowAccountSwitcher() {
        if (getParentActivity() == null) {
            return;
        }
        ArrayList<String> names = new ArrayList<>();
        final ArrayList<Integer> accounts = new ArrayList<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig config = UserConfig.getInstance(a);
            if (config.isClientActivated() && !DrGramHiddenAccounts.isHidden(a)) {
                names.add(ContactsController.formatName(config.getCurrentUser() == null ? "" : config.getCurrentUser().first_name,
                        config.getCurrentUser() == null ? "" : config.getCurrentUser().last_name)
                        + (a == currentAccount ? " ●" : ""));
                accounts.add(a);
            }
        }
        if (accounts.size() < 2) {
            android.widget.Toast.makeText(getParentActivity(), LocaleController.getString(R.string.DrGramSetAccountsOne), android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.DrGramSetAccountsSwitch))
                .setItems(names.toArray(new String[0]), (dialog, which) -> {
                    if (which >= 0 && which < accounts.size()) {
                        int target = accounts.get(which);
                        if (target != currentAccount && getParentActivity() instanceof LaunchActivity) {
                            ((LaunchActivity) getParentActivity()).switchToAccount(target, true);
                        }
                    }
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private void drgramManageHiddenAccounts() {
        if (getParentActivity() == null) {
            return;
        }
        ArrayList<Integer> accounts = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig config = UserConfig.getInstance(a);
            if (!config.isClientActivated() || config.getCurrentUser() == null) {
                continue;
            }
            accounts.add(a);
            String name = ContactsController.formatName(
                    config.getCurrentUser().first_name, config.getCurrentUser().last_name);
            if (a == currentAccount) {
                name += " ●  " + LocaleController.getString(R.string.DrGramHiddenAccountsActive);
            } else {
                name += DrGramHiddenAccounts.isHidden(a)
                        ? " — " + LocaleController.getString(R.string.DrGramHiddenAccountsHidden)
                        : " — " + LocaleController.getString(R.string.DrGramHiddenAccountsVisible);
            }
            names.add(name);
        }
        if (accounts.isEmpty()) {
            return;
        }
        new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.DrGramHiddenAccountsTitle))
                .setItems(names.toArray(new String[0]), (dialog, which) -> {
                    if (which < 0 || which >= accounts.size()) {
                        return;
                    }
                    int target = accounts.get(which);
                    if (target == currentAccount) {
                        android.widget.Toast.makeText(getParentActivity(),
                                LocaleController.getString(R.string.DrGramHiddenAccountsActiveWarning),
                                android.widget.Toast.LENGTH_LONG).show();
                        return;
                    }
                    boolean hide = !DrGramHiddenAccounts.isHidden(target);
                    if (DrGramHiddenAccounts.setHidden(target, hide)) {
                        android.widget.Toast.makeText(getParentActivity(),
                                LocaleController.getString(hide ? R.string.DrGramHiddenAccountsHiddenResult
                                        : R.string.DrGramHiddenAccountsVisibleResult),
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    //endregion

    //region 10. Contacts

    private void buildContactsSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramSetContacts),
                LocaleController.getString(R.string.DrGramSetContactsHint));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramPrivateContactsTitle), v ->
                presentFragment(new DrGramPrivateContactsActivity())));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetContactsOpen), v ->
                presentFragment(new ContactsActivity(new Bundle()))));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetClogOpen), v ->
                presentFragment(new DrGramContactLogActivity())));
    }

    //endregion

    //region 11. Favorites and hashtags

    private void buildFavTagsSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.DrGramSetFavTags),
                LocaleController.getString(R.string.DrGramSetFavTagsHint));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetExploreOpen), v ->
                presentFragment(new DrGramExploreActivity())));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetFavOpen), v ->
                presentFragment(new DrGramFavoritesActivity())));
        contentView.addView(makeActionRow(context, LocaleController.getString(R.string.DrGramSetTagsOpen), v ->
                presentFragment(new DrGramHashtagsActivity())));
    }

    //endregion

    //region shared builders + dynamic refresh

    private void refreshDynamicRows() {
        if (autoForwardSwitch != null) {
            autoForwardSwitch.setChecked(SharedConfig.drgramAutoForward, false);
        }
        if (hideAdsSwitch != null) {
            hideAdsSwitch.setChecked(SharedConfig.drgramHideChannelAds, false);
        }
        if (declineSecretSwitch != null) {
            declineSecretSwitch.setChecked(SharedConfig.drgramDeclineSecretChats, false);
        }
        if (rulesContainer != null) {
            rebuildRules();
        }
        if (repliesContainer != null) {
            rebuildReplies();
        }
        if (specialContactText != null) {
            long id = SharedConfig.drgramSpecialContact;
            specialContactText.setText(id == 0 ? LocaleController.getString(R.string.DrGramSetSpecialNone)
                    : DrGramAutoForward.describe(currentAccount, id));
        }
    }

    private void rebuildRules() {
        rulesContainer.removeAllViews();
        Context context = rulesContainer.getContext();
        ArrayList<DrGramAutoForward.Rule> rules = DrGramAutoForward.getRules(currentAccount);
        for (int a = 0; a < rules.size(); a++) {
            DrGramAutoForward.Rule rule = rules.get(a);
            String label = DrGramAutoForward.describe(currentAccount, rule.source)
                    + "  ⬅  " + DrGramAutoForward.describe(currentAccount, rule.target)
                    + (rule.includeMedia ? "" : "  · " + LocaleController.getString(R.string.DrGramSetRelayTextOnly));
            LinearLayout row = makeActionRow(context, label, v -> {
                if (getParentActivity() == null) {
                    return;
                }
                new AlertDialog.Builder(getParentActivity())
                        .setTitle(LocaleController.getString(R.string.DrGramSetRelayRemove))
                        .setMessage(label)
                        .setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) -> {
                            DrGramAutoForward.removeRule(currentAccount, rule.id);
                            rebuildRules();
                        })
                        .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                        .show();
            });
            rulesContainer.addView(row);
        }
        if (rules.isEmpty()) {
            rulesContainer.addView(makeStaticRow(context, LocaleController.getString(R.string.DrGramSetRelayEmpty)));
        }
    }

    private void rebuildReplies() {
        repliesContainer.removeAllViews();
        Context context = repliesContainer.getContext();
        ArrayList<DrGramQuickReplies.Item> items = DrGramQuickReplies.getAll();
        for (int a = 0; a < items.size(); a++) {
            DrGramQuickReplies.Item item = items.get(a);
            String label = item.title + "  —  " + item.text;
            if (label.length() > 60) {
                label = label.substring(0, 60) + "…";
            }
            final long id = item.id;
            final String title = item.title;
            final String text = item.text;
            repliesContainer.addView(makeActionRow(context, label, v -> drgramEditReplyDialog(id, title, text)));
        }
        if (items.isEmpty()) {
            repliesContainer.addView(makeStaticRow(context, LocaleController.getString(R.string.DrGramSetRepliesEmpty)));
        }
    }

    private void drgramPickDialog(String title, long exclude, DrGramPickCallback callback) {
        if (getParentActivity() == null) {
            return;
        }
        ArrayList<TLRPC.Dialog> dialogs = getMessagesController().getAllDialogs();
        ArrayList<String> names = new ArrayList<>();
        final ArrayList<Long> ids = new ArrayList<>();
        for (int a = 0; a < dialogs.size() && ids.size() < 40; a++) {
            TLRPC.Dialog dialog = dialogs.get(a);
            if (dialog == null || dialog.id == exclude || dialog instanceof TLRPC.TL_dialogFolder) {
                continue;
            }
            String name;
            if (dialog.id > 0) {
                TLRPC.User user = getMessagesController().getUser(dialog.id);
                if (user == null || UserObject.isUserSelf(user)) {
                    continue;
                }
                name = ContactsController.formatName(user.first_name, user.last_name);
            } else if (DialogObject.isEncryptedDialog(dialog.id)) {
                name = LocaleController.getString(R.string.DrGramSetRelaySecret);
            } else {
                TLRPC.Chat chat = getMessagesController().getChat(-dialog.id);
                if (chat == null || chat.left || chat.kicked) {
                    continue;
                }
                name = chat.title;
            }
            if (name == null || name.trim().isEmpty()) {
                continue;
            }
            names.add(name);
            ids.add(dialog.id);
        }
        if (ids.isEmpty()) {
            android.widget.Toast.makeText(getParentActivity(), LocaleController.getString(R.string.DrGramSetRelayNoChats), android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(getParentActivity())
                .setTitle(title)
                .setItems(names.toArray(new String[0]), (dialog, which) -> {
                    if (which >= 0 && which < ids.size()) {
                        callback.onPicked(ids.get(which));
                    }
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private TextView makeStaticRow(Context context, String text) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        view.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        view.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(6), AndroidUtilities.dp(21), AndroidUtilities.dp(6));
        view.setText(text);
        return view;
    }

    private void addHeader(Context context, String title, String hint) {
        // DrGram Fix 8: luxury section header — gold diamond + bold gold title
        TextView header = new TextView(context);
        header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        header.setTypeface(AndroidUtilities.bold());
        header.setTextColor(DrGramThemeInstaller.accentColor());
        header.setText("❖  " + title); // DrGram Fix 24: tintable luxury glyph (emoji broke the palette)
        header.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(22), AndroidUtilities.dp(21), AndroidUtilities.dp(2));
        contentView.addView(header);

        TextView hintView = new TextView(context);
        hintView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        hintView.setTextColor(DrGramThemeInstaller.isLoveMode() ? 0xFFD8A8B4 : 0xFFB9AD95); // DrGram Fix 24
        hintView.setPadding(AndroidUtilities.dp(21), 0, AndroidUtilities.dp(21), AndroidUtilities.dp(8));
        hintView.setText(hint);
        contentView.addView(hintView);
    }

    private void refreshBrandSurfaces() {
        if (contentView == null) {
            return;
        }
        boolean loveMode = DrGramThemeInstaller.isLoveMode();
        contentView.setBackground(DrGramGlassKit.pageGradient()); // DrGram Fix 36: glass canvas
        refreshBrandRows(contentView, loveMode);
    }

    private void refreshBrandRows(ViewGroup group, boolean loveMode) {
        int accent = DrGramThemeInstaller.accentColor();
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            if (child instanceof TextView && ((TextView) child).getText().toString().startsWith("❖")) {
                ((TextView) child).setTextColor(accent);
            }
            if (!(child instanceof ViewGroup)) {
                continue;
            }
            ViewGroup row = (ViewGroup) child;
            if (row != contentView && row.getChildCount() == 2 && row.getChildAt(0) instanceof TextView) {
                // DrGram Fix 36: mode switch keeps the glass look
                row.setBackgroundDrawable(DrGramGlassKit.rowRipple());
                row.setElevation(0);
                ((TextView) row.getChildAt(0)).setTextColor(DrGramGlassKit.ROW_TITLE);
            }
            refreshBrandRows(row, loveMode);
        }
    }

    private LinearLayout makeRow(Context context, String title, View valueView) {
        // DrGram Fix 8: luxury card row — rounded dark card, RTL-safe start alignment
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // DrGram Fix 36: flat glass row — no per-row borders or elevation
        row.setBackgroundDrawable(DrGramGlassKit.rowRipple());
        row.setPadding(AndroidUtilities.dp(17), AndroidUtilities.dp(14), AndroidUtilities.dp(17), AndroidUtilities.dp(14));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(AndroidUtilities.dp(16), AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(4));
        row.setLayoutParams(rowParams);

        TextView titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        titleView.setTextColor(0xFFFFF6DF);
        titleView.setText(title);
        titleView.setGravity(Gravity.START);
        row.addView(titleView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        valueParams.setMargins(AndroidUtilities.dp(8), 0, 0, 0);
        valueView.setLayoutParams(valueParams);
        row.addView(valueView);
        return row;
    }

    private LinearLayout makeActionRow(Context context, String title, View.OnClickListener listener) {
        LinearLayout row = makeRow(context, title, new TextView(context));
        row.setOnClickListener(listener);
        return row;
    }

    private LinearLayout makeToggleRow(Context context, String title, Switch toggle, Runnable action) {
        LinearLayout row = makeRow(context, title, toggle);
        row.setOnClickListener(v -> action.run());
        return row;
    }

    //endregion
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
        // DrGram Fix 24: inset handling is cosmetic — never fatal.
        try {
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
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }
}
