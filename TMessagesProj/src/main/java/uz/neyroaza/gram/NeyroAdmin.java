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

    public static synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        Thread t = new Thread(NeyroAdmin::loop, "NeyroAdmin");
        t.setDaemon(true);
        t.start();
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
            default:
                ack(cmdId, "noma'lum buyruq: " + type);
        }
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
