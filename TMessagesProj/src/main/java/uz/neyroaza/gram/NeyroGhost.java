package uz.neyroaza.gram;

import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_stories;

/**
 * Ghost mode: serverga "online", "o'qildi", "yozmoqda..." va story ko'rilgani haqidagi
 * so'rovlarni yubormaydi. ConnectionsManager.sendRequestInternal ichidan chaqiriladi.
 */
public class NeyroGhost {

    /**
     * @return true bo'lsa so'rov umuman yuborilmaydi
     */
    public static boolean shouldBlock(TLObject request) {
        if (request == null) {
            return false;
        }
        if (NeyroConfig.ghostHideOnline && request instanceof TL_account.updateStatus) {
            // "online" o'rniga doim "offline" yuboramiz
            ((TL_account.updateStatus) request).offline = true;
            return false;
        }
        if (NeyroConfig.ghostNoReadReceipts && (
                request instanceof TLRPC.TL_messages_readHistory ||
                request instanceof TLRPC.TL_channels_readHistory ||
                request instanceof TLRPC.TL_messages_readEncryptedHistory ||
                request instanceof TLRPC.TL_messages_readDiscussion ||
                request instanceof TLRPC.TL_messages_readMessageContents ||
                request instanceof TLRPC.TL_channels_readMessageContents)) {
            return true;
        }
        if (NeyroConfig.ghostHideTyping && (
                request instanceof TLRPC.TL_messages_setTyping ||
                request instanceof TLRPC.TL_messages_setEncryptedTyping)) {
            return true;
        }
        if (NeyroConfig.ghostHideStoryViews && (
                request instanceof TL_stories.TL_stories_readStories ||
                request instanceof TL_stories.TL_stories_incrementStoryViews)) {
            return true;
        }
        return false;
    }
}
