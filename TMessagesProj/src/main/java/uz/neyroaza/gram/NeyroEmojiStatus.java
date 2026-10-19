package uz.neyroaza.gram;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;

import java.util.HashMap;
import java.util.Map;

/**
 * Premium'siz emoji-status.
 *
 * O'z statusimiz: serverga yuborilmaydi (Premium talab qiladi), qurilmada saqlanadi va
 * bio oxiriga ko'rinmas marker sifatida yoziladi: U+2063 + 16 ta variation selector (U+FE00..U+FE0F),
 * har biri document_id'ning 4 bitini bildiradi. Oddiy Telegram'da bu belgilar ko'rinmaydi.
 *
 * Boshqalarning statusi: ularning bio'sidagi marker o'qiladi (UserFull yuklanganda) va saqlanadi.
 * Ko'rsatish uchun User.emoji_status lokal ravishda to'ldiriladi.
 */
public class NeyroEmojiStatus {

    private static final String TAG = "NeyroEmojiStatus";
    private static final char MARKER = '⁣';
    private static final char VS_BASE = '︀';
    private static final int MARKER_LENGTH = 17;

    private static final NeyroEmojiStatus[] instances = new NeyroEmojiStatus[UserConfig.MAX_ACCOUNT_COUNT];

    public static NeyroEmojiStatus getInstance(int account) {
        NeyroEmojiStatus local = instances[account];
        if (local == null) {
            synchronized (NeyroEmojiStatus.class) {
                local = instances[account];
                if (local == null) {
                    instances[account] = local = new NeyroEmojiStatus(account);
                }
            }
        }
        return local;
    }

    private final int account;
    private final SharedPreferences prefs;
    private long ownDocumentId;
    private final Map<Long, Long> remote = new HashMap<>();

    private NeyroEmojiStatus(int account) {
        this.account = account;
        prefs = ApplicationLoader.applicationContext.getSharedPreferences("neyro_emoji_status_" + account, Context.MODE_PRIVATE);
        ownDocumentId = prefs.getLong("own", 0);
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (e.getKey().startsWith("u") && e.getValue() instanceof Long) {
                try {
                    remote.put(Long.parseLong(e.getKey().substring(1)), (Long) e.getValue());
                } catch (NumberFormatException ignore) {
                }
            }
        }
    }

    // ---- marker ----

    public static String encode(long documentId) {
        StringBuilder sb = new StringBuilder(MARKER_LENGTH);
        sb.append(MARKER);
        for (int i = 15; i >= 0; i--) {
            sb.append((char) (VS_BASE + ((documentId >>> (i * 4)) & 0xF)));
        }
        return sb.toString();
    }

    /** @return document_id yoki 0 */
    public static long decode(String about) {
        if (about == null) {
            return 0;
        }
        int idx = about.lastIndexOf(MARKER);
        if (idx < 0 || about.length() - idx < MARKER_LENGTH) {
            return 0;
        }
        long value = 0;
        for (int i = 1; i < MARKER_LENGTH; i++) {
            int nibble = about.charAt(idx + i) - VS_BASE;
            if (nibble < 0 || nibble > 15) {
                return 0;
            }
            value = (value << 4) | nibble;
        }
        return value;
    }

    public static String strip(String about) {
        if (about == null) {
            return "";
        }
        int idx = about.lastIndexOf(MARKER);
        return idx < 0 ? about : about.substring(0, idx);
    }

    // ---- ko'rsatish ----

    private boolean enabled() {
        return NeyroConfig.neyroEmojiStatus;
    }

    /** isPremiumUser uchun: status chizilishi uchun foydalanuvchini "premium ko'rinishida" ko'rsatish kerakmi. */
    public boolean grantsPremiumLook(TLRPC.User user) {
        if (!enabled() || user == null) {
            return false;
        }
        if (user.id == UserConfig.getInstance(account).getClientUserId()) {
            return true;
        }
        synchronized (remote) {
            return remote.containsKey(user.id);
        }
    }

    /** MessagesController.putUser ichidan: lokal statusni User obyektiga qo'yadi. */
    public void applyTo(TLRPC.User user) {
        if (!enabled() || user == null || user.premium) {
            return;
        }
        long documentId;
        if (user.id == UserConfig.getInstance(account).getClientUserId()) {
            documentId = ownDocumentId;
        } else {
            synchronized (remote) {
                Long id = remote.get(user.id);
                documentId = id == null ? 0 : id;
            }
        }
        if (documentId != 0) {
            TLRPC.TL_emojiStatus status = new TLRPC.TL_emojiStatus();
            status.document_id = documentId;
            user.emoji_status = status;
            user.flags |= 1073741824;
        }
    }

    /** UserFull yuklanganda: boshqa foydalanuvchining bio'sidagi markerni o'qiydi. */
    public void onUserFull(TLRPC.UserFull userFull) {
        if (userFull == null || userFull.id == UserConfig.getInstance(account).getClientUserId()) {
            return;
        }
        long documentId = decode(userFull.about);
        Long previous;
        synchronized (remote) {
            previous = documentId != 0 ? remote.put(userFull.id, documentId) : remote.remove(userFull.id);
        }
        if (previous != null && previous == documentId || previous == null && documentId == 0) {
            return;
        }
        if (documentId != 0) {
            prefs.edit().putLong("u" + userFull.id, documentId).apply();
        } else {
            prefs.edit().remove("u" + userFull.id).apply();
        }
        final long userId = userFull.id;
        final long newId = documentId;
        AndroidUtilities.runOnUIThread(() -> {
            TLRPC.User user = MessagesController.getInstance(account).getUser(userId);
            if (user != null && !user.premium) {
                if (newId != 0) {
                    applyTo(user);
                } else {
                    user.emoji_status = null;
                }
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_EMOJI_STATUS);
            }
        });
    }

    // ---- o'z statusini o'rnatish ----

    /**
     * MessagesController.updateEmojiStatus'dan Premium'siz foydalanuvchi uchun chaqiriladi.
     * @return true bo'lsa status shu yerda qayta ishlandi va serverga yuborilmasligi kerak
     */
    public boolean handleOwnStatus(TLRPC.EmojiStatus newStatus) {
        if (!enabled() || UserConfig.getInstance(account).isPremium()) {
            return false;
        }
        long documentId;
        if (newStatus instanceof TLRPC.TL_emojiStatus) {
            documentId = ((TLRPC.TL_emojiStatus) newStatus).document_id;
        } else if (newStatus == null || newStatus instanceof TLRPC.TL_emojiStatusEmpty) {
            documentId = 0;
        } else {
            return false;
        }
        ownDocumentId = documentId;
        prefs.edit().putLong("own", documentId).apply();
        if (NeyroConfig.shareEmojiStatus || documentId == 0) {
            publishToBio(documentId);
        }
        return true;
    }

    public long getOwnDocumentId() {
        return ownDocumentId;
    }

    /** "Boshqalarga ko'rsatish" sozlamasi o'zgarganda bio markerini yangilaydi. */
    public void syncBio() {
        publishToBio(NeyroConfig.neyroEmojiStatus && NeyroConfig.shareEmojiStatus ? ownDocumentId : 0);
    }

    private void publishToBio(long documentId) {
        TLRPC.TL_users_getFullUser req = new TLRPC.TL_users_getFullUser();
        req.id = new TLRPC.TL_inputUserSelf();
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            if (!(response instanceof TLRPC.TL_users_userFull)) {
                return;
            }
            String about = ((TLRPC.TL_users_userFull) response).full_user.about;
            String base = strip(about);
            String newAbout = documentId != 0 ? base + encode(documentId) : base;
            if (newAbout.equals(about == null ? "" : about)) {
                return;
            }
            MessagesController controller = MessagesController.getInstance(account);
            int limit = UserConfig.getInstance(account).isPremium() ? controller.aboutLengthLimitPremium : controller.aboutLengthLimitDefault;
            if (newAbout.length() > limit) {
                Log.i(TAG, "bio too long for marker: " + newAbout.length() + " > " + limit);
                AndroidUtilities.runOnUIThread(() -> android.widget.Toast.makeText(ApplicationLoader.applicationContext,
                        org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.NeyroEmojiStatusBioFull), android.widget.Toast.LENGTH_LONG).show());
                return;
            }
            TL_account.updateProfile update = new TL_account.updateProfile();
            update.flags |= 4;
            update.about = newAbout;
            ConnectionsManager.getInstance(account).sendRequest(update, (res, err) -> {
                Log.i(TAG, "bio marker update " + (err == null ? "ok" : err.text));
                if (err == null) {
                    AndroidUtilities.runOnUIThread(() -> {
                        TLRPC.UserFull own = controller.getUserFull(UserConfig.getInstance(account).getClientUserId());
                        if (own != null) {
                            own.about = newAbout;
                        }
                    });
                }
            });
        });
    }
}
