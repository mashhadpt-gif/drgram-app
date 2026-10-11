/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Storage Butler — keeps an eye on local media caches, reports their
 * sizes and sweeps them when asked. Purely local: no network, no uploads.
 */
package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

public class DrGramStorageButler {

    private static final String PREFS = "drgram_storage_butler";

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Keep original file names for media saved from chats (local preference). */
    public static boolean keepOriginalNames() {
        return prefs().getBoolean("keepOriginalNames", false);
    }

    public static void setKeepOriginalNames(boolean value) {
        prefs().edit().putBoolean("keepOriginalNames", value).apply();
    }

    private static File mediaDir(int type) {
        try {
            return FileLoader.getDirectory(type);
        } catch (Throwable t) {
            return null;
        }
    }

    public static long folderSize(File dir) {
        if (dir == null || !dir.exists()) {
            return 0;
        }
        long size = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    size += folderSize(f);
                } else {
                    size += f.length();
                }
            }
        }
        return size;
    }

    public static long imageCacheSize() {
        return folderSize(mediaDir(FileLoader.MEDIA_DIR_IMAGE));
    }

    public static long videoCacheSize() {
        return folderSize(mediaDir(FileLoader.MEDIA_DIR_VIDEO));
    }

    public static long audioCacheSize() {
        return folderSize(mediaDir(FileLoader.MEDIA_DIR_AUDIO));
    }

    public static long documentCacheSize() {
        return folderSize(mediaDir(FileLoader.MEDIA_DIR_DOCUMENT));
    }

    public static long totalCacheSize() {
        return imageCacheSize() + videoCacheSize() + audioCacheSize() + documentCacheSize();
    }

    /** Root folder where DrGram keeps its media (usually Android/data/.../files/Telegram). */
    public static File storageRoot() {
        File cache = mediaDir(FileLoader.MEDIA_DIR_CACHE);
        if (cache != null && cache.getParentFile() != null) {
            return cache.getParentFile();
        }
        return cache;
    }

    /** Deletes everything inside the given folder (keeps the folder itself). */
    public static long sweep(File dir) {
        if (dir == null || !dir.exists()) {
            return 0;
        }
        long freed = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    freed += sweep(f);
                    try {
                        f.delete();
                    } catch (Exception ignore) {
                    }
                } else {
                    freed += f.length();
                    try {
                        f.delete();
                    } catch (Exception ignore) {
                    }
                }
            }
        }
        return freed;
    }

    public static long sweepAll() {
        long freed = sweep(mediaDir(FileLoader.MEDIA_DIR_IMAGE))
                + sweep(mediaDir(FileLoader.MEDIA_DIR_VIDEO))
                + sweep(mediaDir(FileLoader.MEDIA_DIR_AUDIO))
                + sweep(mediaDir(FileLoader.MEDIA_DIR_DOCUMENT));
        try {
            ImageLoader.getInstance().clearMemory();
        } catch (Throwable ignore) {
        }
        return freed;
    }
}
