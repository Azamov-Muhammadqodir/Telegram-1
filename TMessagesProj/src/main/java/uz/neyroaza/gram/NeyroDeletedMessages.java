package uz.neyroaza.gram;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Boshqalar o'chirgan xabarlarni saqlab qoladi.
 * Server "o'chirildi" update'ini yuborganda xabar lokal bazadan o'chirilmaydi,
 * faqat shu yerda "o'chirilgan" deb belgilanadi va chatda belgi bilan ko'rsatiladi.
 *
 * Kalit: channelId (kanal/supergroup uchun) yoki 0 (shaxsiy chat va oddiy guruh), + xabar id.
 */
public class NeyroDeletedMessages {

    private static final NeyroDeletedMessages[] instances = new NeyroDeletedMessages[UserConfig.MAX_ACCOUNT_COUNT];

    public static NeyroDeletedMessages getInstance(int account) {
        NeyroDeletedMessages local = instances[account];
        if (local == null) {
            synchronized (NeyroDeletedMessages.class) {
                local = instances[account];
                if (local == null) {
                    instances[account] = local = new NeyroDeletedMessages(account);
                }
            }
        }
        return local;
    }

    private final int account;
    private final Set<String> deleted;
    private final SharedPreferences prefs;

    private NeyroDeletedMessages(int account) {
        this.account = account;
        prefs = ApplicationLoader.applicationContext.getSharedPreferences("neyro_deleted_" + account, Context.MODE_PRIVATE);
        deleted = new HashSet<>(prefs.getStringSet("ids", new HashSet<>()));
    }

    private static String key(long channelId, int messageId) {
        return channelId + "_" + messageId;
    }

    /**
     * Server o'chirish update'ini yubordi. dialogKey: kanal uchun -channelId, aks holda 0.
     * Stage queue'dan chaqiriladi.
     */
    public void onServerDeleted(long dialogKey, ArrayList<Integer> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return;
        }
        long channelId = dialogKey < 0 ? -dialogKey : 0;
        synchronized (deleted) {
            for (int i = 0; i < messageIds.size(); i++) {
                deleted.add(key(channelId, messageIds.get(i)));
            }
            prefs.edit().putStringSet("ids", new HashSet<>(deleted)).apply();
        }
        final ArrayList<Integer> ids = new ArrayList<>(messageIds);
        AndroidUtilities.runOnUIThread(() ->
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.neyroMessagesMarkedDeleted, dialogKey, ids));
    }

    public boolean isDeleted(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null || messageObject.getId() <= 0) {
            return false;
        }
        TLRPC.Peer peer = messageObject.messageOwner.peer_id;
        long channelId = peer != null ? peer.channel_id : 0;
        synchronized (deleted) {
            return !deleted.isEmpty() && deleted.contains(key(channelId, messageObject.getId()));
        }
    }

    public int count() {
        synchronized (deleted) {
            return deleted.size();
        }
    }

    public void clear() {
        synchronized (deleted) {
            deleted.clear();
            prefs.edit().clear().apply();
        }
    }
}
