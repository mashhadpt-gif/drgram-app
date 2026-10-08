/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Online Guard — friendly presence alerts.
 * Periodically refreshes the presence of a small user-chosen watch list and
 * posts a local notification whenever one of them comes online. Everything
 * runs on the device through the official API; the watch list is stored
 * only in local preferences.
 */
package org.telegram.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class DrGramOnlineGuard {

    private static final long POLL_INTERVAL_MS = 60 * 1000L;
    private static final long FIRST_POLL_DELAY_MS = 15 * 1000L;
    private static final String CHANNEL_ID = "drgram_online_guard";

    private static volatile boolean started;
    private static final HashSet<String> knownOnline = new HashSet<>();

    public static void onAppReady() {
        if (started) {
            return;
        }
        started = true;
        try {
            createChannel();
        } catch (Throwable ignore) {
        }
        AndroidUtilities.runOnUIThread(DrGramOnlineGuard::pollNow, FIRST_POLL_DELAY_MS);
    }

    //region watch list

    private static SharedPreferences prefs(int account) {
        String prefsName = account == 0 ? "drgram_guard" : "drgram_guard_" + account;
        return ApplicationLoader.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(int account) {
        return prefs(account).getBoolean("enabled", true);
    }

    public static void setEnabled(int account, boolean value) {
        prefs(account).edit().putBoolean("enabled", value).apply();
    }

    public static Set<String> getWatchedIds(int account) {
        return prefs(account).getStringSet("watched", new HashSet<>());
    }

    public static boolean isWatched(int account, long userId) {
        return getWatchedIds(account).contains(String.valueOf(userId));
    }

    public static void watch(int account, long userId) {
        Set<String> watched = new HashSet<>(getWatchedIds(account));
        watched.add(String.valueOf(userId));
        prefs(account).edit().putStringSet("watched", watched).apply();
    }

    public static void unwatch(int account, long userId) {
        Set<String> watched = new HashSet<>(getWatchedIds(account));
        watched.remove(String.valueOf(userId));
        prefs(account).edit().putStringSet("watched", watched).apply();
        knownOnline.remove(statusKey(account, userId));
    }

    //endregion

    private static void pollNow() {
        try {
            int account = UserConfig.selectedAccount;
            if (!UserConfig.getInstance(account).isClientActivated() || !isEnabled(account)) {
                scheduleNext();
                return;
            }
            Set<String> watched = getWatchedIds(account);
            if (watched.isEmpty()) {
                scheduleNext();
                return;
            }

            // evaluate cached statuses first so the alert fires instantly
            ArrayList<TLRPC.User> needRefresh = new ArrayList<>();
            for (String id : watched) {
                TLRPC.User user = MessagesController.getInstance(account).getUser(Long.parseLong(id));
                if (user == null) {
                    continue;
                }
                boolean onlineNow = user.status instanceof TLRPC.TL_userStatusOnline;
                handleStatus(account, user, onlineNow);
                needRefresh.add(user);
            }

            if (!needRefresh.isEmpty()) {
                TLRPC.TL_users_getUsers req = new TLRPC.TL_users_getUsers();
                for (int i = 0; i < needRefresh.size(); i++) {
                    TLRPC.InputUser inputUser = MessagesController.getInstance(account).getInputUser(needRefresh.get(i));
                    if (inputUser != null) {
                        req.id.add(inputUser);
                    }
                }
                ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
                    if (response instanceof Vector) {
                        ArrayList<Object> objects = ((Vector) response).objects;
                        AndroidUtilities.runOnUIThread(() -> {
                            for (int i = 0; i < objects.size(); i++) {
                                Object object = objects.get(i);
                                if (object instanceof TLRPC.User) {
                                    TLRPC.User user = (TLRPC.User) object;
                                    handleStatus(account, user, user.status instanceof TLRPC.TL_userStatusOnline);
                                }
                            }
                        });
                    }
                });
            }
        } catch (Throwable ignore) {
        }
        scheduleNext();
    }

    private static void scheduleNext() {
        AndroidUtilities.runOnUIThread(DrGramOnlineGuard::pollNow, POLL_INTERVAL_MS);
    }

    private static void handleStatus(int account, TLRPC.User user, boolean onlineNow) {
        if (user == null || user.bot) {
            return;
        }
        String key = statusKey(account, user.id);
        boolean wasOnline = knownOnline.contains(key);
        if (onlineNow) {
            knownOnline.add(key);
            if (!wasOnline && isWatched(account, user.id)) {
                postNotification(account, user);
            }
        } else {
            knownOnline.remove(key);
        }
    }

    private static String statusKey(int account, long userId) {
        return account + ":" + userId;
    }

    private static void postNotification(int account, TLRPC.User user) {
        try {
            Context context = ApplicationLoader.applicationContext;
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) {
                return;
            }
            String name = UserObject.getUserName(user);
            String title = context.getString(R.string.DrGramGuardNotificationTitle);
            String text = context.getString(R.string.DrGramGuardNotificationText, name);

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(context, CHANNEL_ID);
            } else {
                builder = new Notification.Builder(context);
            }
            builder.setSmallIcon(R.drawable.notification)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setAutoCancel(true);
            manager.notify(7_000_000 + (int) (user.id % 1_000_000L), builder.build());
        } catch (Throwable ignore) {
        }
    }

    private static void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        Context context = ApplicationLoader.applicationContext;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.DrGramGuardChannelName), NotificationManager.IMPORTANCE_DEFAULT);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }
}
