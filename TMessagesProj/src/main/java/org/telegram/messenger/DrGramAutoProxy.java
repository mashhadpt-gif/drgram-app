package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.utils.proxy.ProxySettings;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DrGram Fix 26 — automatic MTProto proxy (telegramvpn.org).
 * DrGram Fix 58 — multi-source, self-healing proxy finder.
 *
 * What the user asked for, exactly:
 *  1. "وقتی پینگ نداد / متصل نشد خودکار بره از چند تا سایت پروکسی mtproto
 *     پیدا کنه" — a connection watchdog checks every 5 s (Fix 60: instant,
 *     previously 15 s ticks with a ~30 s grace) and fires a full proxy cycle
 *     on the FIRST tick without a working connection.
 *  2. "از چند تا سایت" — candidates are fetched IN PARALLEL from three
 *     independent sources: the telegramvpn.org JSON API, the SoliSpirit/mtproto
 *     GitHub list (raw.githubusercontent.com — reachable in Iran, Iran-focused
 *     proxies) and mtpro.xyz. Results are merged, de-duplicated and
 *     latency-tested one by one with ConnectionsManager.checkProxy.
 *  3. "متصل شه تا زمانی که پینگ بده" — while the connection stays down, the
 *     watchdog re-runs the cycle back-to-back (at most every 8 s, Fix 60)
 *     until a proxy answers.
 *  4. "پروکسی‌هایی که پینگ ندارن هم از لیست حذف کنه" — after every successful
 *     connect the saved proxy list is pruned in the background: every entry
 *     that fails its ping check is removed via SharedConfig.deleteProxy
 *     (the currently active proxy is never touched by pruning).
 *
 * Untouched, as before: a manually picked proxy is never overridden by the
 * automatic path, and an active Android VPN pauses the feature when the
 * "VPN off" toggle is enabled (Fix 40).
 */
public class DrGramAutoProxy {

    // Independent list sources (Fix 58). One thread per source, all in parallel.
    private static final String[] LIST_URLS = {
            // JSON API behind telegramvpn.org: {proxies:[{server,port,url,ping}]}
            "https://api.telegramvpn.org/proxies?per=16&sort=ping&status=active",
            // Iran-focused t.me/proxy?...&secret=... line list, refreshed every 12h
            "https://raw.githubusercontent.com/SoliSpirit/mtproto/master/all_proxies.txt",
            // classic [{host,port,secret}] array (may be Cloudflare-blocked — harmless)
            "https://mtpro.xyz/api/?type=mtproto"
    };
    private static final String TAG_KEY = "drgramAutoProxyTag";
    private static final Pattern SECRET = Pattern.compile("secret=([A-Za-z0-9=_\\-]+)");
    private static final Pattern LINK_SERVER = Pattern.compile("[?&]server=([^&\\s]+)");
    private static final Pattern LINK_PORT = Pattern.compile("[?&]port=(\\d+)");
    private static final Pattern LINK_SECRET = Pattern.compile("[?&]secret=([A-Za-z0-9=_\\-]+)");
    private static final int MAX_CANDIDATES = 10;
    private static final long FETCH_JOIN_TIMEOUT_MS = 11_000;

    // Connection watchdog (Fix 58, instant in Fix 60).
    // Fix 60: the user asked for INSTANT proxy testing — "میخوام آنی تست کنه
    // متصل کنه پروکسی رو نه هر 30 ثانیه برسی کنه". So the watchdog now ticks
    // every 5 s, fires on the FIRST tick without a connection (worst case ~5 s
    // after the drop instead of ~30 s) and retries cycles back-to-back (8 s
    // floor, which a failed cycle itself always exceeds) until ping returns.
    private static final long WATCHDOG_PERIOD_MS = 5_000;
    private static final long CYCLE_MIN_INTERVAL_MS = 8_000;
    private static final int DOWN_TICKS_TO_TRIGGER = 1; // fire on the first down tick — instantly

    private static volatile boolean running;
    private static volatile boolean vpnWatcherRegistered;
    private static volatile boolean lastVpnState;
    private static volatile boolean watchdogRunning;
    private static int downTicks;
    private static long lastCycleFinish;
    private static final Handler WATCHDOG_HANDLER = new Handler(Looper.getMainLooper());

    private static void finish() {
        running = false;
        lastCycleFinish = SystemClock.elapsedRealtime();
    }

    /** True when any Android VPN is the active network (DrGram Fix 40). */
    public static boolean isVpnActive() {
        try {
            ConnectivityManager cm = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }
            Network active = cm.getActiveNetwork();
            if (active == null) {
                return false;
            }
            NetworkCapabilities caps = cm.getNetworkCapabilities(active);
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * DrGram Fix 40: manual "تعویض خودکار پروکسی" — the user explicitly asks for
     * the best auto-found proxy right now. Unlike onAppOpen this overrides a
     * manually picked proxy, because the tap IS the user's intent.
     */
    public static void forceSwitch() {
        try {
            if (running) {
                return;
            }
            if (SharedConfig.drgramAutoProxyVpnOff && isVpnActive()) {
                AndroidUtilities.runOnUIThread(DrGramAutoProxy::toastVpnActive);
                return;
            }
            startCycle();
        } catch (Throwable t) {
            running = false;
            FileLog.e(t);
        }
    }

    private static void toastVpnActive() {
        toast("DrGramProxyVpnActive");
    }

    private static void toast(String key) {
        try {
            Toast.makeText(ApplicationLoader.applicationContext, LocaleController.getString(key, org.telegram.messenger.R.string.DrGramProxyVpnActive), Toast.LENGTH_SHORT).show();
        } catch (Throwable ignore) {}
    }

    /**
     * DrGram Fix 40: while the VPN toggle is on, an active Android VPN switches
     * the auto-applied MTProto proxy back off (and keeps onAppOpen away).
     * Registered once, acts on transitions only.
     */
    public static void registerVpnWatcher() {
        if (vpnWatcherRegistered) {
            return;
        }
        try {
            ConnectivityManager cm = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return;
            }
            vpnWatcherRegistered = true;
            lastVpnState = isVpnActive();
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                    try {
                        boolean vpn = capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
                        if (vpn == lastVpnState) {
                            return;
                        }
                        lastVpnState = vpn;
                        if (!vpn || !SharedConfig.drgramAutoProxyVpnOff) {
                            return;
                        }
                        AndroidUtilities.runOnUIThread(() -> {
                            try {
                                if (!SharedConfig.drgramAutoProxyVpnOff || !SharedConfig.isProxyEnabled()) {
                                    return;
                                }
                                if (SharedConfig.currentProxy != null && !isDrgramAutoProxy(SharedConfig.currentProxy)) {
                                    return; // a manual proxy stays untouched
                                }
                                SharedPreferences preferences = MessagesController.getGlobalMainSettings();
                                preferences.edit().putBoolean("proxy_enabled", false).commit();
                                ConnectionsManager.setProxySettings(false, null);
                                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
                                toast("DrGramProxyVpnProxyOff");
                            } catch (Throwable t) {
                                FileLog.e(t);
                            }
                        });
                    } catch (Throwable t) {
                        FileLog.e(t);
                    }
                }
            });
        } catch (Throwable t) {
            vpnWatcherRegistered = false;
            FileLog.e(t);
        }
    }

    /** Call from the UI thread (LaunchActivity, delayed a few seconds). */
    public static void onAppOpen() {
        try {
            registerVpnWatcher();
            if (running || !SharedConfig.drgramAutoProxy) {
                return;
            }
            if (SharedConfig.drgramAutoProxyVpnOff && isVpnActive()) {
                return; // VPN is handling connectivity — leave the proxy alone
            }
            if (SharedConfig.isProxyEnabled() && SharedConfig.currentProxy != null
                    && !isDrgramAutoProxy(SharedConfig.currentProxy)) {
                return; // user picked a proxy manually — never override
            }
            running = true;
            // watchdog: never stay locked after a hung check
            AndroidUtilities.runOnUIThread(() -> running = false, 180_000);

            SharedConfig.ProxyInfo existing = SharedConfig.currentProxy;
            if (existing != null && isDrgramAutoProxy(existing)) {
                ConnectionsManager.getInstance(UserConfig.selectedAccount).checkProxy(existing.settings, time -> {
                    if (time > 0) {
                        finish(); // still alive, nothing to do
                        pruneDeadProxies(); // Fix 58: keep the list clean while we are online
                    } else {
                        fetchAndPick();
                    }
                });
            } else {
                fetchAndPick();
            }
        } catch (Throwable t) {
            running = false;
            FileLog.e(t);
        }
    }

    // ===================== Fix 58: connection watchdog =====================

    /**
     * Starts the periodic connection watchdog. Call once from ApplicationLoader.
     * Every 5 s (Fix 60): if the auto-proxy feature is on, no manual proxy is
     * set and Telegram is not connected, a full proxy cycle (multi-source
     * fetch → latency-test → connect → prune) fires INSTANTLY — no 30 s
     * observation window. While the connection stays down the cycle repeats
     * back-to-back — at most once per 8 s — until something answers with a
     * ping.
     */
    public static void startWatchdog() {
        if (watchdogRunning) {
            return;
        }
        watchdogRunning = true;
        WATCHDOG_HANDLER.postDelayed(DrGramAutoProxy::watchdogTick, WATCHDOG_PERIOD_MS);
    }

    private static void watchdogTick() {
        try {
            if (SharedConfig.drgramAutoProxy && !running) {
                boolean vpnBlock = SharedConfig.drgramAutoProxyVpnOff && isVpnActive();
                boolean manualBlock = SharedConfig.isProxyEnabled() && SharedConfig.currentProxy != null
                        && !isDrgramAutoProxy(SharedConfig.currentProxy);
                if (!vpnBlock && !manualBlock) {
                    int state = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
                    if (state == ConnectionsManager.ConnectionStateConnected) {
                        downTicks = 0;
                    } else {
                        downTicks++;
                        if (downTicks >= DOWN_TICKS_TO_TRIGGER
                                && SystemClock.elapsedRealtime() - lastCycleFinish >= CYCLE_MIN_INTERVAL_MS) {
                            downTicks = 0;
                            startCycle(); // "متصل شه تا زمانی که پینگ بده" — keep retrying
                        }
                    }
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        WATCHDOG_HANDLER.postDelayed(DrGramAutoProxy::watchdogTick, WATCHDOG_PERIOD_MS);
    }

    /** Full automatic cycle: fetch from all sources in parallel, then test. */
    private static void startCycle() {
        if (running) {
            return;
        }
        try {
            running = true;
            AndroidUtilities.runOnUIThread(() -> running = false, 180_000); // unlock worst case: 10 candidates
            fetchAndPick();
        } catch (Throwable t) {
            running = false;
            FileLog.e(t);
        }
    }

    private static void fetchAndPick() {
        try {
            Utilities.globalQueue.postRunnable(() -> {
                List<SharedConfig.ProxyInfo> candidates = fetchCandidates();
                AndroidUtilities.runOnUIThread(() -> {
                    if (candidates.isEmpty()) {
                        // every source failed (offline / filtered) — the watchdog
                        // will retry while the connection stays down
                        toast("DrGramProxyEmpty");
                        finish();
                    } else {
                        try {
                            Toast.makeText(ApplicationLoader.applicationContext,
                                    LocaleController.formatString("DrGramProxyTesting", org.telegram.messenger.R.string.DrGramProxyTesting, candidates.size()),
                                    Toast.LENGTH_SHORT).show();
                        } catch (Throwable ignore) {}
                        testChain(candidates, 0);
                    }
                });
            });
        } catch (Throwable t) {
            running = false;
            FileLog.e(t);
        }
    }

    // ===================== Fix 58: multi-source fetch =====================

    /** Background thread: download from ALL sources in parallel, merge + dedup. */
    private static List<SharedConfig.ProxyInfo> fetchCandidates() {
        final List<SharedConfig.ProxyInfo> merged = Collections.synchronizedList(new ArrayList<>());
        Thread[] threads = new Thread[LIST_URLS.length];
        for (int i = 0; i < LIST_URLS.length; i++) {
            final String url = LIST_URLS[i];
            final int source = i;
            threads[i] = new Thread(() -> {
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(7000);
                    conn.setReadTimeout(8000);
                    conn.setInstanceFollowRedirects(true);
                    conn.setRequestProperty("User-Agent", "DrGram");
                    conn.setRequestProperty("Accept", "application/json, text/plain, */*");
                    InputStream is = conn.getInputStream();
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        bos.write(buf, 0, n);
                    }
                    is.close();
                    String body = bos.toString("UTF-8").trim();
                    if (body.startsWith("{")) {
                        parseTelegramVpn(body, merged, source);
                    } else if (body.startsWith("[")) {
                        parseJsonArray(body, merged, source);
                    } else {
                        parseProxyLines(body, merged, source);
                    }
                } catch (Throwable t) {
                    FileLog.e(t);
                } finally {
                    if (conn != null) {
                        try { conn.disconnect(); } catch (Throwable ignore) {}
                    }
                }
            });
            threads[i].start();
        }
        long deadline = SystemClock.elapsedRealtime() + FETCH_JOIN_TIMEOUT_MS;
        for (Thread t : threads) {
            long remain = deadline - SystemClock.elapsedRealtime();
            if (remain <= 0) {
                break;
            }
            try {
                t.join(remain);
            } catch (InterruptedException ignore) {}
        }
        // merge: dedup by server:port:secret, reported ping first, cap
        ArrayList<SharedConfig.ProxyInfo> out = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        synchronized (merged) {
            for (SharedConfig.ProxyInfo info : merged) {
                String key = info.settings.getAddress() + ":" + info.settings.getPort() + ":" + info.settings.getSecret();
                if (seen.add(key)) {
                    out.add(info);
                }
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(a.ping, b.ping));
        if (out.size() > MAX_CANDIDATES) {
            out = new ArrayList<>(out.subList(0, MAX_CANDIDATES));
        }
        return out;
    }

    private static SharedConfig.ProxyInfo buildMtproto(String server, int port, String secret, long ping) {
        if (TextUtils.isEmpty(server) || port <= 0 || port > 65535 || TextUtils.isEmpty(secret)) {
            return null;
        }
        SharedConfig.ProxyInfo info = new SharedConfig.ProxyInfo(
                ProxySettings.builder()
                        .setType(ProxySettings.Type.MTPROTO)
                        .setAddress(server)
                        .setPort(port)
                        .setUser("")
                        .setPassword("")
                        .setSecret(secret)
                        .build());
        info.ping = ping;
        return info;
    }

    /** telegramvpn.org shape: {"proxies":[{server,port,url:"tg://proxy?...&secret=..."}]} */
    private static void parseTelegramVpn(String body, List<SharedConfig.ProxyInfo> out, int source) {
        try {
            JSONObject root = new JSONObject(body);
            JSONArray arr = root.optJSONArray("proxies");
            if (arr == null) {
                return;
            }
            for (int i = 0; i < arr.length() && out.size() < MAX_CANDIDATES * 3; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null || !"mtproto".equals(o.optString("type"))) {
                    continue;
                }
                String server = o.optString("server");
                int port = o.optInt("port");
                String link = o.optString("url");
                String secret = "";
                Matcher m = SECRET.matcher(link);
                if (m.find()) {
                    secret = m.group(1);
                }
                SharedConfig.ProxyInfo info = buildMtproto(server, port, secret, o.optLong("ping", Long.MAX_VALUE));
                if (info != null) {
                    out.add(info);
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    /** mtpro.xyz shape: [{"host":"1.2.3.4","port":443,"secret":"..."}] */
    private static void parseJsonArray(String body, List<SharedConfig.ProxyInfo> out, int source) {
        try {
            JSONArray arr = new JSONArray(body);
            for (int i = 0; i < arr.length() && out.size() < MAX_CANDIDATES * 3; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String host = o.optString("host", o.optString("server", o.optString("ip")));
                int port = o.optInt("port");
                String secret = o.optString("secret");
                SharedConfig.ProxyInfo info = buildMtproto(host, port, secret, 150);
                if (info != null) {
                    out.add(info);
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    /** Plain-text lists: t.me/proxy / tg://proxy links, one per line. */
    private static void parseProxyLines(String body, List<SharedConfig.ProxyInfo> out, int source) {
        try {
            String[] lines = body.split("\\n");
            for (int i = 0; i < lines.length && out.size() < MAX_CANDIDATES * 3; i++) {
                String line = lines[i].trim();
                if (line.length() < 12 || (!line.contains("proxy") && !line.contains("PROXY") && !line.contains("Proxy"))) {
                    continue;
                }
                Matcher s = LINK_SERVER.matcher(line);
                Matcher p = LINK_PORT.matcher(line);
                Matcher sec = LINK_SECRET.matcher(line);
                if (!s.find() || !p.find() || !sec.find()) {
                    continue;
                }
                SharedConfig.ProxyInfo info = buildMtproto(s.group(1), Integer.parseInt(p.group(1)), sec.group(1), 100);
                if (info != null) {
                    out.add(info);
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    /** UI thread: latency-test candidates one by one, first answer wins. */
    private static void testChain(List<SharedConfig.ProxyInfo> candidates, final int index) {
        if (index >= candidates.size()) {
            finish(); // none answered this round — the watchdog retries while offline
            return;
        }
        SharedConfig.ProxyInfo info = candidates.get(index);
        try {
            ConnectionsManager.getInstance(UserConfig.selectedAccount).checkProxy(info.settings, time -> {
                if (running && time > 0) {
                    apply(info, time);
                } else {
                    testChain(candidates, index + 1);
                }
            });
        } catch (Throwable t) {
            FileLog.e(t);
            testChain(candidates, index + 1);
        }
    }

    private static void apply(SharedConfig.ProxyInfo info, long ping) {
        try {
            SharedConfig.addProxy(info);
            SharedConfig.currentProxy = info;
            SharedPreferences preferences = MessagesController.getGlobalMainSettings();
            SharedPreferences.Editor editor = preferences.edit();
            editor.putBoolean("proxy_enabled", true);
            info.settings.toSharedPreferences(editor);
            editor.putString(TAG_KEY, info.settings.getAddress() + ":" + info.settings.getPort());
            editor.commit();
            ConnectionsManager.setProxySettings(true, info.settings);
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);

            String label = info.settings.getAddress() + " · " + ping + " ms";
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    Toast.makeText(ApplicationLoader.applicationContext,
                            LocaleController.formatString("DrGramAutoProxyOn", org.telegram.messenger.R.string.DrGramAutoProxyOn, label),
                            Toast.LENGTH_LONG).show();
                } catch (Throwable ignore) {}
            });
        } catch (Throwable t) {
            FileLog.e(t);
        }
        finish();
        pruneDeadProxies(); // Fix 58: "پینگ ندارن از لیست حذف شن"
    }

    // ===================== Fix 58: prune dead proxies =====================

    /**
     * Removes every saved proxy that currently has no ping. Runs in the
     * background, one checkProxy at a time; the currently active proxy is
     * skipped (it was just proven alive by the apply path) and entries checked
     * successfully in the last 2 minutes are trusted.
     */
    private static void pruneDeadProxies() {
        try {
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    ArrayList<SharedConfig.ProxyInfo> snapshot = new ArrayList<>(SharedConfig.proxyList);
                    pruneChain(snapshot, 0);
                } catch (Throwable t) {
                    FileLog.e(t);
                }
            });
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static void pruneChain(final ArrayList<SharedConfig.ProxyInfo> list, final int index) {
        if (index >= list.size()) {
            return;
        }
        final SharedConfig.ProxyInfo info = list.get(index);
        if (info == SharedConfig.currentProxy || info.checking
                || (info.available && SystemClock.elapsedRealtime() - info.availableCheckTime < 2 * 60 * 1000)) {
            pruneChain(list, index + 1);
            return;
        }
        try {
            info.checking = true;
            ConnectionsManager.getInstance(UserConfig.selectedAccount).checkProxy(info.settings, time -> AndroidUtilities.runOnUIThread(() -> {
                try {
                    info.checking = false;
                    info.availableCheckTime = SystemClock.elapsedRealtime();
                    if (time <= 0) {
                        info.available = false;
                        info.ping = 0;
                        if (info != SharedConfig.currentProxy) {
                            SharedConfig.deleteProxy(info); // dead — out of the list
                        }
                    } else {
                        info.ping = time;
                        info.available = true;
                    }
                } catch (Throwable t) {
                    FileLog.e(t);
                }
                pruneChain(list, index + 1);
            }));
        } catch (Throwable t) {
            info.checking = false;
            FileLog.e(t);
            pruneChain(list, index + 1);
        }
    }

    /** True when this proxy was last applied by the auto-proxy feature. */
    public static boolean isDrgramAutoProxy(SharedConfig.ProxyInfo info) {
        if (info == null) {
            return false;
        }
        try {
            String tag = MessagesController.getGlobalMainSettings().getString(TAG_KEY, "");
            return !TextUtils.isEmpty(tag)
                    && tag.equals(info.settings.getAddress() + ":" + info.settings.getPort());
        } catch (Throwable t) {
            return false;
        }
    }
}
