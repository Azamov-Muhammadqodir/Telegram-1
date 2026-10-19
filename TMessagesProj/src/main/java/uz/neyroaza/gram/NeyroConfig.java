package uz.neyroaza.gram;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/**
 * Neyrogram'ning barcha qo'shimcha sozlamalari. Hamma akkauntlar uchun umumiy.
 */
public class NeyroConfig {

    private static final String PREFS_NAME = "neyroconfig";

    // Ghost mode
    public static boolean ghostHideOnline;
    public static boolean ghostNoReadReceipts;
    public static boolean ghostHideTyping;
    public static boolean ghostHideStoryViews;

    // Xabarlar
    public static boolean saveDeletedMessages;
    public static boolean saveEditHistory;
    public static boolean forwardWithoutAuthor;

    // Media
    public static boolean fastDownload;

    private static boolean loaded;

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        SharedPreferences p = prefs();
        ghostHideOnline = p.getBoolean("ghostHideOnline", false);
        ghostNoReadReceipts = p.getBoolean("ghostNoReadReceipts", false);
        ghostHideTyping = p.getBoolean("ghostHideTyping", false);
        ghostHideStoryViews = p.getBoolean("ghostHideStoryViews", false);
        saveDeletedMessages = p.getBoolean("saveDeletedMessages", true);
        saveEditHistory = p.getBoolean("saveEditHistory", true);
        forwardWithoutAuthor = p.getBoolean("forwardWithoutAuthor", false);
        fastDownload = p.getBoolean("fastDownload", true);
        loaded = true;
    }

    public static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static void putBoolean(String key, boolean value) {
        prefs().edit().putBoolean(key, value).apply();
    }

    public static boolean isGhostModeFull() {
        return ghostHideOnline && ghostNoReadReceipts && ghostHideTyping && ghostHideStoryViews;
    }

    public static void setGhostModeFull(boolean enabled) {
        ghostHideOnline = ghostNoReadReceipts = ghostHideTyping = ghostHideStoryViews = enabled;
        prefs().edit()
                .putBoolean("ghostHideOnline", enabled)
                .putBoolean("ghostNoReadReceipts", enabled)
                .putBoolean("ghostHideTyping", enabled)
                .putBoolean("ghostHideStoryViews", enabled)
                .apply();
    }

    static {
        if (ApplicationLoader.applicationContext != null) {
            load();
        }
    }
}
