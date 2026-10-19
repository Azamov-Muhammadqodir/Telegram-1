package uz.neyroaza.gram;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;

/**
 * ChatActivity'dagi Neyrogram menyu bandlari uchun yordamchi metodlar.
 */
public class NeyroChatHelper {

    public static void showEditHistory(BaseFragment fragment, MessageObject messageObject) {
        if (fragment == null || fragment.getParentActivity() == null || messageObject == null) {
            return;
        }
        ArrayList<NeyroEditHistory.Version> versions = NeyroEditHistory.getInstance(fragment.getCurrentAccount()).getHistory(messageObject);
        SpannableStringBuilder text = new SpannableStringBuilder();
        for (int i = 0; i < versions.size(); i++) {
            NeyroEditHistory.Version version = versions.get(i);
            appendVersion(text, LocaleController.formatDateTime(version.date, true), version.text);
        }
        appendVersion(text, LocaleController.getString(R.string.NeyroEditHistoryCurrent), messageObject.messageOwner.message);

        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.NeyroEditHistory));
        builder.setMessage(text);
        builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        fragment.showDialog(builder.create());
    }

    private static void appendVersion(SpannableStringBuilder sb, String title, String body) {
        if (sb.length() > 0) {
            sb.append("\n\n");
        }
        int start = sb.length();
        sb.append(title);
        sb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append("\n").append(body == null ? "" : body);
    }

    public static boolean canSaveSticker(MessageObject messageObject) {
        return messageObject != null && (messageObject.isSticker() || messageObject.isAnimatedSticker() || messageObject.isVideoSticker());
    }

    /**
     * Oddiy stikerni PNG qilib galereyaga, animatsion/video stikerni asl fayl ko'rinishida Downloads'ga saqlaydi.
     */
    public static void saveSticker(BaseFragment fragment, MessageObject messageObject) {
        if (fragment == null || fragment.getParentActivity() == null || messageObject == null) {
            return;
        }
        File file = getFile(fragment.getCurrentAccount(), messageObject);
        if (file == null) {
            BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.NeyroStickerNotDownloaded)).show();
            return;
        }
        final android.content.Context context = fragment.getParentActivity();
        if (messageObject.isAnimatedSticker() || messageObject.isVideoSticker()) {
            String name = messageObject.getDocumentName();
            if (name == null || name.isEmpty()) {
                name = "sticker_" + messageObject.getId() + (messageObject.isVideoSticker() ? ".webm" : ".tgs");
            }
            MediaController.saveFile(file.getAbsolutePath(), context, 2, name, messageObject.getMimeType());
            BulletinFactory.of(fragment).createDownloadBulletin(BulletinFactory.FileType.UNKNOWN).show();
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
                if (bitmap == null) {
                    return;
                }
                File out = new File(AndroidUtilities.getCacheDir(), "neyro_sticker_" + messageObject.getId() + ".png");
                try (FileOutputStream stream = new FileOutputStream(out)) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
                }
                bitmap.recycle();
                AndroidUtilities.runOnUIThread(() -> {
                    MediaController.saveFile(out.getAbsolutePath(), context, 0, null, "image/png");
                    BulletinFactory.of(fragment).createDownloadBulletin(BulletinFactory.FileType.PHOTO).show();
                });
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    private static File getFile(int account, MessageObject messageObject) {
        String attachPath = messageObject.messageOwner.attachPath;
        if (attachPath != null && !attachPath.isEmpty()) {
            File f = new File(attachPath);
            if (f.exists()) {
                return f;
            }
        }
        File f = FileLoader.getInstance(account).getPathToMessage(messageObject.messageOwner);
        return f != null && f.exists() ? f : null;
    }
}
