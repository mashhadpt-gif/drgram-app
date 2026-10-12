package org.telegram.messenger;

import android.content.SharedPreferences;
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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DrGram Fix 26 — automatic MTProto proxy (telegramvpn.org).
 *
 * On every app open (if the feature toggle is on) it:
 *  1. keeps a user-chosen manual proxy untouched;
 *  2. if the last auto proxy is still alive (checkProxy ping > 0), keeps it;
 *  3. otherwise fetches the live list from api.telegramvpn.org (the JSON API
 *     behind telegramvpn.org), sorts by reported ping and latency-tests the
 *     top candidates one by one with ConnectionsManager.checkProxy;
 *  4. silently connects the first candidate that answers, and shows a toast.
 */
public class DrGramAutoProxy {

    private static final String LIST_URL = "https://api.telegramvpn.org/proxies?per=12&sort=ping&status=active";
    private static final String TAG_KEY = "drgramAutoProxyTag";
    private static final Pattern SECRET = Pattern.compile("secret=([A-Za-z0-9=_\\-]+)");
    private static final int MAX_CANDIDATES = 6;

    private static volatile boolean running;

    private static void finish() {
        running = false;
    }

    /** Call from the UI thread (LaunchActivity, delayed a few seconds). */
    public static void onAppOpen() {
        try {
            if (running || !SharedConfig.drgramAutoProxy) {
                return;
            }
            if (SharedConfig.isProxyEnabled() && SharedConfig.currentProxy != null
                    && !isDrgramAutoProxy(SharedConfig.currentProxy)) {
                return; // user picked a proxy manually — never override
            }
            running = true;
            // watchdog: never stay locked after a hung check
            AndroidUtilities.runOnUIThread(() -> running = false, 90_000);

            SharedConfig.ProxyInfo existing = SharedConfig.currentProxy;
            if (existing != null && isDrgramAutoProxy(existing)) {
                ConnectionsManager.getInstance(UserConfig.selectedAccount).checkProxy(existing.settings, time -> {
                    if (time > 0) {
                        finish(); // still alive, nothing to do
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

    private static void fetchAndPick() {
        try {
            Utilities.globalQueue.postRunnable(() -> {
                List<SharedConfig.ProxyInfo> candidates = fetchCandidates();
                AndroidUtilities.runOnUIThread(() -> testChain(candidates, 0));
            });
        } catch (Throwable t) {
            running = false;
            FileLog.e(t);
        }
    }

    /** Background thread: download + parse the JSON proxy list (sorted by ping). */
    private static List<SharedConfig.ProxyInfo> fetchCandidates() {
        List<SharedConfig.ProxyInfo> list = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            URL url = new URL(LIST_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "DrGram");
            conn.setRequestProperty("Accept", "application/json");
            InputStream is = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            is.close();
            JSONObject root = new JSONObject(bos.toString("UTF-8"));
            JSONArray arr = root.optJSONArray("proxies");
            if (arr != null) {
                for (int i = 0; i < arr.length() && list.size() < MAX_CANDIDATES * 2; i++) {
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
                    if (TextUtils.isEmpty(server) || port <= 0 || port > 65535 || TextUtils.isEmpty(secret)) {
                        continue;
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
                    info.ping = o.optLong("ping", Long.MAX_VALUE);
                    list.add(info);
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignore) {}
            }
        }
        Collections.sort(list, (a, b) -> Long.compare(a.ping, b.ping));
        if (list.size() > MAX_CANDIDATES) {
            list = list.subList(0, MAX_CANDIDATES);
        }
        return list;
    }

    /** UI thread: latency-test candidates one by one, first answer wins. */
    private static void testChain(List<SharedConfig.ProxyInfo> candidates, final int index) {
        if (index >= candidates.size()) {
            finish(); // none answered this round — will retry next app open
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
                            LocaleController.formatString("DrGramAutoProxyOn", R.string.DrGramAutoProxyOn, label),
                            Toast.LENGTH_LONG).show();
                } catch (Throwable ignore) {}
            });
        } catch (Throwable t) {
            FileLog.e(t);
        }
        finish();
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
