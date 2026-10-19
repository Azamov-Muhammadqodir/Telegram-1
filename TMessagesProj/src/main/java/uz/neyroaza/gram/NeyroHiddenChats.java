package uz.neyroaza.gram;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.Gravity;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Yashirin chatlar: chatlar ro'yxatidan butunlay olib tashlanadi va faqat PIN kod bilan
 * ochiladigan "Yashirin chatlar" bo'limida ko'rinadi. PIN barcha akkauntlar uchun bitta.
 */
public class NeyroHiddenChats {

    private static final NeyroHiddenChats[] instances = new NeyroHiddenChats[UserConfig.MAX_ACCOUNT_COUNT];

    public static NeyroHiddenChats getInstance(int account) {
        NeyroHiddenChats local = instances[account];
        if (local == null) {
            synchronized (NeyroHiddenChats.class) {
                local = instances[account];
                if (local == null) {
                    instances[account] = local = new NeyroHiddenChats(account);
                }
            }
        }
        return local;
    }

    private final int account;
    private final SharedPreferences prefs;
    private final Set<Long> hidden = new HashSet<>();

    private NeyroHiddenChats(int account) {
        this.account = account;
        prefs = ApplicationLoader.applicationContext.getSharedPreferences("neyro_hidden_" + account, Context.MODE_PRIVATE);
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

    public ArrayList<Long> getHidden() {
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
        MessagesController.getInstance(account).sortDialogs(null);
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.neyroHiddenChatsChanged);
    }

    // ---- PIN ----

    private static SharedPreferences pinPrefs() {
        return NeyroConfig.prefs();
    }

    public static boolean hasPin() {
        return pinPrefs().getString("hiddenPinHash", null) != null;
    }

    private static String hash(String pin) {
        byte[] bytes = Utilities.computeSHA256(("neyrogram:" + pin).getBytes());
        return Utilities.bytesToHex(bytes);
    }

    private static boolean checkPin(String pin) {
        String saved = pinPrefs().getString("hiddenPinHash", null);
        return saved != null && saved.equals(hash(pin));
    }

    private static void savePin(String pin) {
        pinPrefs().edit().putString("hiddenPinHash", hash(pin)).apply();
    }

    /**
     * PIN so'raydi. PIN hali o'rnatilmagan bo'lsa, yangisini yaratishni taklif qiladi.
     */
    public static void requestPin(BaseFragment fragment, Runnable onSuccess) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        final boolean create = !hasPin();
        Context context = fragment.getParentActivity();
        AlertDialog.Builder builder = new AlertDialog.Builder(context, fragment.getResourceProvider());
        builder.setTitle(LocaleController.getString(create ? R.string.NeyroHiddenCreatePin : R.string.NeyroHiddenEnterPin));
        if (create) {
            builder.setMessage(LocaleController.getString(R.string.NeyroHiddenCreatePinInfo));
        }

        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(18);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, fragment.getResourceProvider()));
        editText.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, fragment.getResourceProvider()));
        editText.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField), Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated), Theme.getColor(Theme.key_text_RedRegular));
        editText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        editText.setGravity(Gravity.CENTER);
        editText.setHint("••••");
        editText.setSingleLine(true);
        editText.setBackground(null);
        editText.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8));

        FrameLayout container = new FrameLayout(context);
        container.addView(editText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 24, 6, 24, 0));
        builder.setView(container);

        builder.setPositiveButton(LocaleController.getString(R.string.OK), (dialog, which) -> {
            String pin = editText.getText() == null ? "" : editText.getText().toString().trim();
            if (create) {
                if (pin.length() < 4) {
                    BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.NeyroHiddenPinTooShort)).show();
                    return;
                }
                savePin(pin);
                onSuccess.run();
            } else if (checkPin(pin)) {
                onSuccess.run();
            } else {
                BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.NeyroHiddenWrongPin)).show();
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
        });
        fragment.showDialog(dialog);
    }
}
