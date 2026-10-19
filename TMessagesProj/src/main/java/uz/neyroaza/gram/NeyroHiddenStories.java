package uz.neyroaza.gram;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Storylari butunlay yashirilgan odamlar: na asosiy storylar qatorida, na arxivda,
 * na avatar atrofidagi halqada ko'rinadi. Faqat shu qurilmada amal qiladi.
 */
public class NeyroHiddenStories {

    private static final NeyroHiddenStories[] instances = new NeyroHiddenStories[UserConfig.MAX_ACCOUNT_COUNT];

    public static NeyroHiddenStories getInstance(int account) {
        NeyroHiddenStories local = instances[account];
        if (local == null) {
            synchronized (NeyroHiddenStories.class) {
                local = instances[account];
                if (local == null) {
                    instances[account] = local = new NeyroHiddenStories(account);
                }
            }
        }
        return local;
    }

    private final SharedPreferences prefs;
    private final Set<Long> hidden = new HashSet<>();

    private NeyroHiddenStories(int account) {
        prefs = ApplicationLoader.applicationContext.getSharedPreferences("neyro_hidden_stories_" + account, Context.MODE_PRIVATE);
        for (String id : prefs.getStringSet("ids", new HashSet<>())) {
            try {
                hidden.add(Long.parseLong(id));
            } catch (NumberFormatException ignore) {
            }
        }
    }

    public boolean isHidden(long dialogId) {
        synchronized (hidden) {
            return !hidden.isEmpty() && hidden.contains(dialogId);
        }
    }

    public boolean isEmpty() {
        synchronized (hidden) {
            return hidden.isEmpty();
        }
    }

    public ArrayList<Long> getAll() {
        synchronized (hidden) {
            return new ArrayList<>(hidden);
        }
    }

    public void setHidden(long dialogId, boolean hide) {
        synchronized (hidden) {
            if (hide) {
                hidden.add(dialogId);
            } else {
                hidden.remove(dialogId);
            }
            HashSet<String> out = new HashSet<>();
            for (Long id : hidden) {
                out.add(String.valueOf(id));
            }
            prefs.edit().putStringSet("ids", out).apply();
        }
    }
}
