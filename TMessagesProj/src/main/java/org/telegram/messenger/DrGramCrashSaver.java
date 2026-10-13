/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Fix 29: a tiny crash recorder. Wraps the default uncaught-exception
 * handler and appends every crash to files/drgram_crash_log.txt, so the user
 * can share the exact stack trace from DrGram Tools -> "Send last crash" —
 * no adb or logcat needed.
 */
package org.telegram.messenger;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class DrGramCrashSaver {

    private static final Object LOCK = new Object();
    private static final int MAX_LOG_CHARS = 190000;
    private static boolean installed;
    private static Thread.UncaughtExceptionHandler previous;

    private DrGramCrashSaver() {}

    public static void install() {
        try {
            synchronized (LOCK) {
                if (installed) {
                    return;
                }
                previous = Thread.getDefaultUncaughtExceptionHandler();
                Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
                    try {
                        append("CRASH", throwable);
                    } catch (Throwable ignore) {}
                    if (previous != null) {
                        previous.uncaughtException(thread, throwable);
                    }
                });
                installed = true;
            }
        } catch (Throwable ignore) {}
    }

    /** Records a caught (non-fatal) problem so it shows up in the same report. */
    public static void recordNonFatal(String tag, Throwable t) {
        try {
            append("NON-FATAL " + tag, t);
        } catch (Throwable ignore) {}
    }

    private static File logFile() {
        return new File(ApplicationLoader.applicationContext.getFilesDir(), "drgram_crash_log.txt");
    }

    private static void append(String kind, Throwable t) throws Exception {
        synchronized (LOCK) {
            StringBuilder sb = new StringBuilder();
            sb.append("==== LuxGram ").append(kind).append(" ====")
              .append('\n')
              .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
              .append(" | LuxGram v").append(BuildVars.BUILD_VERSION_STRING)
              .append(" | Android ").append(android.os.Build.VERSION.RELEASE)
              .append(" | ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
              .append(" | thread=").append(Thread.currentThread().getName())
              .append('\n');
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            sb.append(sw.toString()).append('\n');
            File f = logFile();
            String old = "";
            if (f.exists() && f.length() > 0) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                FileInputStream in = new FileInputStream(f);
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        bos.write(buf, 0, n);
                    }
                } finally {
                    try { in.close(); } catch (Throwable ignore) {}
                }
                old = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            }
            String combined = sb.toString() + old;
            if (combined.length() > MAX_LOG_CHARS) {
                combined = combined.substring(0, MAX_LOG_CHARS);
            }
            FileOutputStream out = new FileOutputStream(f, false);
            try {
                out.write(combined.getBytes(StandardCharsets.UTF_8));
            } finally {
                try { out.close(); } catch (Throwable ignore) {}
            }
        }
    }

    /** Newest-first crash report, or null when nothing was recorded. */
    public static String lastCrashText() {
        synchronized (LOCK) {
            try {
                File f = logFile();
                if (!f.exists() || f.length() == 0) {
                    return null;
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                FileInputStream in = new FileInputStream(f);
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        bos.write(buf, 0, n);
                    }
                } finally {
                    try { in.close(); } catch (Throwable ignore) {}
                }
                String text = new String(bos.toByteArray(), StandardCharsets.UTF_8).trim();
                if (text.isEmpty()) {
                    return null;
                }
                if (text.length() > 12000) {
                    text = text.substring(0, 12000) + "\n… (truncated)";
                }
                return text;
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
