package uz.neyroaza.gram;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.text.TextUtils;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Xabarlarning tahrirlar tarixi. Server "xabar tahrirlandi" update'ini yuborganda,
 * Telegram bazasidagi eski versiyani o'qib, o'zimizning alohida bazamizga yozib qo'yamiz.
 */
public class NeyroEditHistory {

    public static class Version {
        public final int date;
        public final String text;

        Version(int date, String text) {
            this.date = date;
            this.text = text;
        }
    }

    private static final NeyroEditHistory[] instances = new NeyroEditHistory[UserConfig.MAX_ACCOUNT_COUNT];

    public static NeyroEditHistory getInstance(int account) {
        NeyroEditHistory local = instances[account];
        if (local == null) {
            synchronized (NeyroEditHistory.class) {
                local = instances[account];
                if (local == null) {
                    instances[account] = local = new NeyroEditHistory(account);
                }
            }
        }
        return local;
    }

    private final int account;
    private final Helper helper;

    private NeyroEditHistory(int account) {
        this.account = account;
        this.helper = new Helper(account);
    }

    /**
     * MessagesController stage queue'dan, tahrir bazaga yozilishidan OLDIN chaqiriladi.
     * Storage queue tartibi eski versiyani o'qishimizni kafolatlaydi.
     */
    public void onMessageEdited(TLRPC.Message newMessage) {
        if (!NeyroConfig.saveEditHistory || newMessage == null || newMessage.id <= 0) {
            return;
        }
        final long dialogId = newMessage.dialog_id;
        final int messageId = newMessage.id;
        final String newText = newMessage.message;
        final MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> {
            SQLiteCursor cursor = null;
            try {
                cursor = storage.getDatabase().queryFinalized(String.format(Locale.US, "SELECT data FROM messages_v2 WHERE mid = %d AND uid = %d LIMIT 1", messageId, dialogId));
                if (cursor.next()) {
                    NativeByteBuffer data = cursor.byteBufferValue(0);
                    if (data != null) {
                        TLRPC.Message old = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                        data.reuse();
                        if (old != null && !TextUtils.equals(old.message, newText) && !TextUtils.isEmpty(old.message)) {
                            int date = old.edit_date != 0 ? old.edit_date : old.date;
                            insert(dialogId, messageId, date, old.message);
                        }
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    cursor.dispose();
                }
            }
        });
    }

    private void insert(long dialogId, int messageId, int date, String text) {
        ContentValues values = new ContentValues();
        values.put("dialog_id", dialogId);
        values.put("mid", messageId);
        values.put("date", date);
        values.put("text", text);
        helper.getWritableDatabase().insert("edits", null, values);
    }

    /** Eski versiyalar, eng birinchisidan boshlab. Sinxron, lekin juda yengil so'rov. */
    public ArrayList<Version> getHistory(MessageObject messageObject) {
        ArrayList<Version> result = new ArrayList<>();
        if (messageObject == null || messageObject.getId() <= 0) {
            return result;
        }
        try (Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT date, text FROM edits WHERE dialog_id = ? AND mid = ? ORDER BY _id ASC",
                new String[]{String.valueOf(messageObject.getDialogId()), String.valueOf(messageObject.getId())})) {
            while (c.moveToNext()) {
                result.add(new Version(c.getInt(0), c.getString(1)));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return result;
    }

    public boolean hasHistory(MessageObject messageObject) {
        if (messageObject == null || messageObject.getId() <= 0 || !messageObject.isEdited()) {
            return false;
        }
        try (Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT 1 FROM edits WHERE dialog_id = ? AND mid = ? LIMIT 1",
                new String[]{String.valueOf(messageObject.getDialogId()), String.valueOf(messageObject.getId())})) {
            return c.moveToFirst();
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    public void clear() {
        helper.getWritableDatabase().delete("edits", null, null);
    }

    private static class Helper extends SQLiteOpenHelper {
        Helper(int account) {
            super(ApplicationLoader.applicationContext, "neyro_edits_" + account + ".db", null, 1);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE edits(_id INTEGER PRIMARY KEY AUTOINCREMENT, dialog_id INTEGER, mid INTEGER, date INTEGER, text TEXT)");
            db.execSQL("CREATE INDEX edits_msg_idx ON edits(dialog_id, mid)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        }
    }
}
