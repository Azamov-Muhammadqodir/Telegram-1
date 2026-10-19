package uz.neyroaza.gram;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stories;

import java.util.ArrayList;
import java.util.List;

/**
 * O'z storylaringizni ko'rganlar ro'yxatini qurilmada saqlaydi.
 * Server Premium'siz foydalanuvchiga eski story ko'ruvchilarini bermay qo'ysa ham,
 * avval yuklangan ro'yxat shu yerdan ko'rsatiladi.
 */
public class NeyroStoryViewers {

    private static final NeyroStoryViewers[] instances = new NeyroStoryViewers[UserConfig.MAX_ACCOUNT_COUNT];

    public static NeyroStoryViewers getInstance(int account) {
        NeyroStoryViewers local = instances[account];
        if (local == null) {
            synchronized (NeyroStoryViewers.class) {
                local = instances[account];
                if (local == null) {
                    instances[account] = local = new NeyroStoryViewers(account);
                }
            }
        }
        return local;
    }

    private final int account;
    private final Helper helper;

    private NeyroStoryViewers(int account) {
        this.account = account;
        this.helper = new Helper(account);
    }

    public void save(long dialogId, int storyId, List<TL_stories.StoryView> views) {
        if (views == null || views.isEmpty()) {
            return;
        }
        try {
            SQLiteDatabase db = helper.getWritableDatabase();
            db.beginTransaction();
            try {
                for (TL_stories.StoryView view : views) {
                    if (!(view instanceof TL_stories.TL_storyView) || view.user_id == 0) {
                        continue;
                    }
                    SerializedData data = new SerializedData(view.getObjectSize());
                    view.serializeToStream(data);
                    ContentValues values = new ContentValues();
                    values.put("dialog_id", dialogId);
                    values.put("story_id", storyId);
                    values.put("user_id", view.user_id);
                    values.put("date", view.date);
                    values.put("data", data.toByteArray());
                    data.cleanup();
                    db.insertWithOnConflict("viewers", null, values, SQLiteDatabase.CONFLICT_REPLACE);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** Saqlangan ko'ruvchilar, eng yangisi birinchi. Foydalanuvchilar ma'lumoti bazadan yuklanadi. */
    public ArrayList<TL_stories.StoryView> get(long dialogId, int storyId) {
        ArrayList<TL_stories.StoryView> result = new ArrayList<>();
        try (Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT data FROM viewers WHERE dialog_id = ? AND story_id = ? ORDER BY date DESC",
                new String[]{String.valueOf(dialogId), String.valueOf(storyId)})) {
            MessagesController controller = MessagesController.getInstance(account);
            while (c.moveToNext()) {
                SerializedData data = new SerializedData(c.getBlob(0));
                TL_stories.StoryView view = TL_stories.StoryView.TLdeserialize(data, data.readInt32(false), false);
                data.cleanup();
                if (view == null) {
                    continue;
                }
                if (controller.getUser(view.user_id) == null) {
                    TLRPC.User user = MessagesStorage.getInstance(account).getUserSync(view.user_id);
                    if (user == null) {
                        continue;
                    }
                    controller.putUser(user, true);
                }
                result.add(view);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return result;
    }

    private static class Helper extends SQLiteOpenHelper {
        Helper(int account) {
            super(ApplicationLoader.applicationContext, "neyro_story_viewers_" + account + ".db", null, 1);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE viewers(dialog_id INTEGER, story_id INTEGER, user_id INTEGER, date INTEGER, data BLOB, PRIMARY KEY(dialog_id, story_id, user_id))");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        }
    }
}
