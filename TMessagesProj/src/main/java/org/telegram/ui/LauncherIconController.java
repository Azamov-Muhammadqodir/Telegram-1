package org.telegram.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;

public class LauncherIconController {
    public static void tryFixLauncherIconIfNeeded() {
        for (LauncherIcon icon : LauncherIcon.values()) {
            if (isEnabled(icon)) {
                return;
            }
        }

        setIcon(LauncherIcon.DEFAULT);
    }

    public static boolean isEnabled(LauncherIcon icon) {
        Context ctx = ApplicationLoader.applicationContext;
        int i = ctx.getPackageManager().getComponentEnabledSetting(icon.getComponentName(ctx));
        return i == PackageManager.COMPONENT_ENABLED_STATE_ENABLED || i == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && icon == LauncherIcon.DEFAULT;
    }

    public static void setIcon(LauncherIcon icon) {
        Context ctx = ApplicationLoader.applicationContext;
        PackageManager pm = ctx.getPackageManager();
        for (LauncherIcon i : LauncherIcon.values()) {
            pm.setComponentEnabledSetting(i.getComponentName(ctx), i == icon ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED :
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        }
    }

    public enum LauncherIcon {
        // Neyrogram: Telegram ikonkalari o'rniga Neyrogram rang variantlari, hammasi Premium'siz ochiq
        // (alias nomlari manifest bilan bog'liq, shuning uchun o'zgartirilmagan)
        DEFAULT("DefaultIcon", R.drawable.neyro_icon_bg_default, R.drawable.neyro_icon_fg_default, R.string.NeyroIconDefault),
        VINTAGE("VintageIcon", R.drawable.neyro_icon_bg_sunset, R.drawable.neyro_icon_fg_sunset, R.string.NeyroIconSunset),
        AQUA("AquaIcon", R.drawable.neyro_icon_bg_ocean, R.drawable.neyro_icon_fg_ocean, R.string.NeyroIconOcean),
        PREMIUM("PremiumIcon", R.drawable.neyro_icon_bg_gold, R.drawable.neyro_icon_fg_gold, R.string.NeyroIconGold),
        TURBO("TurboIcon", R.drawable.neyro_icon_bg_neon, R.drawable.neyro_icon_fg_neon, R.string.NeyroIconNeon),
        NOX("NoxIcon", R.drawable.neyro_icon_bg_midnight, R.drawable.neyro_icon_fg_midnight, R.string.NeyroIconMidnight);

        public final String key;
        public final int background;
        public final int foreground;
        public final int title;
        public final boolean premium;

        private ComponentName componentName;

        public ComponentName getComponentName(Context ctx) {
            if (componentName == null) {
                componentName = new ComponentName(ctx.getPackageName(), "org.telegram.messenger." + key);
            }
            return componentName;
        }

        LauncherIcon(String key, int background, int foreground, int title) {
            this(key, background, foreground, title, false);
        }

        LauncherIcon(String key, int background, int foreground, int title, boolean premium) {
            this.key = key;
            this.background = background;
            this.foreground = foreground;
            this.title = title;
            this.premium = premium;
        }
    }
}
