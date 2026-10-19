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

    // ---- Orqa fonda sinxronlash ----
    // Server Premium'siz foydalanuvchiga story tugaganidan 24 soat o'tgach faqat reaksiya bosganlarni beradi.
    // Shuning uchun o'z storylarimiz ko'ruvchilarini shu muddat ichida o'zimiz yuklab, saqlab boramiz.

    private static final long SYNC_INTERVAL_MS = 30 * 60 * 1000L;
    private static final int VIEWS_WINDOW_SEC = 86400;

    private long lastSyncTime;
    private final java.util.HashSet<Integer> syncing = new java.util.HashSet<>();

    /** MessagesController.updateTimerProc'dan tez-tez chaqiriladi; ish faqat har 30 daqiqada bajariladi. */
    public void maybeSync() {
        if (!NeyroConfig.storyViewersAlways) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSyncTime < SYNC_INTERVAL_MS) {
            return;
        }
        lastSyncTime = now;
        org.telegram.messenger.AndroidUtilities.runOnUIThread(this::syncNow);
    }

    private void syncNow() {
        UserConfig userConfig = UserConfig.getInstance(account);
        if (!userConfig.isClientActivated() || userConfig.isPremium()) {
            return;
        }
        long selfId = userConfig.getClientUserId();
        int now = org.telegram.tgnet.ConnectionsManager.getInstance(account).getCurrentTime();
        TL_stories.PeerStories peerStories = MessagesController.getInstance(account).getStoriesController().getStories(selfId);
        if (peerStories != null) {
            for (TL_stories.StoryItem story : peerStories.stories) {
                if (story != null && story.id > 0 && !(story instanceof TL_stories.TL_storyItemDeleted)) {
                    track(selfId, story.id, story.expire_date);
                }
            }
        }
        SQLiteDatabase db = helper.getWritableDatabase();
        db.delete("tracked", "expire_date + ? < ?", new String[]{String.valueOf(VIEWS_WINDOW_SEC), String.valueOf(now)});
        try (Cursor c = db.rawQuery("SELECT dialog_id, story_id FROM tracked", null)) {
            while (c.moveToNext()) {
                syncStory(c.getLong(0), c.getInt(1), "");
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void track(long dialogId, int storyId, int expireDate) {
        ContentValues values = new ContentValues();
        values.put("dialog_id", dialogId);
        values.put("story_id", storyId);
        values.put("expire_date", expireDate);
        helper.getWritableDatabase().insertWithOnConflict("tracked", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private void syncStory(long dialogId, int storyId, String offset) {
        if (offset.isEmpty()) {
            if (syncing.contains(storyId)) {
                return;
            }
            syncing.add(storyId);
        }
        MessagesController controller = MessagesController.getInstance(account);
        TL_stories.TL_stories_getStoryViewsList req = new TL_stories.TL_stories_getStoryViewsList();
        req.peer = controller.getInputPeer(dialogId);
        req.id = storyId;
        req.q = "";
        req.just_contacts = false;
        req.reactions_first = false;
        req.limit = 100;
        req.offset = offset;
        org.telegram.tgnet.ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
            boolean more = false;
            if (response instanceof TL_stories.StoryViewsList) {
                TL_stories.StoryViewsList res = (TL_stories.StoryViewsList) response;
                controller.putUsers(res.users, false);
                MessagesStorage.getInstance(account).putUsersAndChats(res.users, res.chats, true, true);
                save(dialogId, storyId, res.views);
                more = !res.views.isEmpty() && res.next_offset != null && !res.next_offset.isEmpty();
                if (more) {
                    syncStory(dialogId, storyId, res.next_offset);
                }
            }
            if (!more) {
                syncing.remove(storyId);
            }
        }));
    }

    private static class Helper extends SQLiteOpenHelper {
        Helper(int account) {
            super(ApplicationLoader.applicationContext, "neyro_story_viewers_" + account + ".db", null, 2);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE viewers(dialog_id INTEGER, story_id INTEGER, user_id INTEGER, date INTEGER, data BLOB, PRIMARY KEY(dialog_id, story_id, user_id))");
            createTracked(db);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 2) {
                createTracked(db);
            }
        }

        private static void createTracked(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS tracked(dialog_id INTEGER, story_id INTEGER, expire_date INTEGER, PRIMARY KEY(dialog_id, story_id))");
        }
    }
}
