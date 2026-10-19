package uz.neyroaza.gram;

import static org.telegram.messenger.LocaleController.getString;

import android.os.Build;
import android.view.View;

import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.telegram.ui.LanguageSelectActivity;

import java.util.ArrayList;

/**
 * Sozlamalar → Neyrogram. Barcha qo'shimcha funksiyalar shu yerdan boshqariladi.
 */
public class NeyroSettingsActivity extends UniversalFragment {

    private static final int ID_GHOST_ALL = 1;
    private static final int ID_GHOST_ONLINE = 2;
    private static final int ID_GHOST_READ = 3;
    private static final int ID_GHOST_TYPING = 4;
    private static final int ID_GHOST_STORIES = 5;
    private static final int ID_SAVE_DELETED = 10;
    private static final int ID_SAVE_EDITS = 11;
    private static final int ID_FORWARD_NO_AUTHOR = 12;
    private static final int ID_FAST_DOWNLOAD = 20;
    private static final int ID_SYSTEM_FONT = 30;
    private static final int ID_TRANSLATE = 31;
    private static final int ID_HIDDEN_CHATS = 40;
    private static final int ID_CLEAR_DELETED = 50;
    private static final int ID_CLEAR_EDITS = 51;
    private static final int ID_GUIDE = 60;
    private static final int ID_STORY_VIEWERS = 70;
    private static final int ID_HIDDEN_STORIES = 71;
    private static final int ID_EMOJI_STATUS = 80;
    private static final int ID_SHARE_EMOJI_STATUS = 81;
    private static final int ID_APP_ICON = 82;

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.NeyroSettings);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asButton(ID_GUIDE, R.drawable.msg_help, getString(R.string.NeyroFeaturesGuide), getString(R.string.NeyroFeaturesGuideInfo)));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.NeyroGhostHeader)));
        items.add(UItem.asRippleCheck(ID_GHOST_ALL, getString(R.string.NeyroGhostAll)).setChecked(NeyroConfig.isGhostModeFull()));
        items.add(UItem.asButtonCheck(ID_GHOST_ONLINE, getString(R.string.NeyroGhostOnline), getString(R.string.NeyroGhostOnlineInfo)).setChecked(NeyroConfig.ghostHideOnline));
        items.add(UItem.asButtonCheck(ID_GHOST_READ, getString(R.string.NeyroGhostRead), getString(R.string.NeyroGhostReadInfo)).setChecked(NeyroConfig.ghostNoReadReceipts));
        items.add(UItem.asButtonCheck(ID_GHOST_TYPING, getString(R.string.NeyroGhostTyping), getString(R.string.NeyroGhostTypingInfo)).setChecked(NeyroConfig.ghostHideTyping));
        items.add(UItem.asButtonCheck(ID_GHOST_STORIES, getString(R.string.NeyroGhostStories), getString(R.string.NeyroGhostStoriesInfo)).setChecked(NeyroConfig.ghostHideStoryViews));
        items.add(UItem.asShadow(getString(R.string.NeyroGhostFooter)));

        items.add(UItem.asHeader(getString(R.string.NeyroMessagesHeader)));
        items.add(UItem.asButtonCheck(ID_SAVE_DELETED, getString(R.string.NeyroSaveDeleted), getString(R.string.NeyroSaveDeletedInfo)).setChecked(NeyroConfig.saveDeletedMessages));
        items.add(UItem.asButtonCheck(ID_SAVE_EDITS, getString(R.string.NeyroSaveEdits), getString(R.string.NeyroSaveEditsInfo)).setChecked(NeyroConfig.saveEditHistory));
        items.add(UItem.asButtonCheck(ID_FORWARD_NO_AUTHOR, getString(R.string.NeyroForwardNoAuthor), getString(R.string.NeyroForwardNoAuthorInfo)).setChecked(NeyroConfig.forwardWithoutAuthor));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.NeyroPrivacyHeader)));
        int hiddenCount = NeyroHiddenChats.getInstance(currentAccount).getHidden().size();
        items.add(UItem.asButton(ID_HIDDEN_CHATS, R.drawable.msg_archive_hide, getString(R.string.NeyroHiddenChats), hiddenCount > 0 ? String.valueOf(hiddenCount) : null));
        items.add(UItem.asShadow(getString(R.string.NeyroHiddenChatsFooter)));

        items.add(UItem.asHeader(getString(R.string.NeyroStoriesHeader)));
        items.add(UItem.asButtonCheck(ID_STORY_VIEWERS, getString(R.string.NeyroStoryViewersAlways), getString(R.string.NeyroStoryViewersAlwaysInfo)).setChecked(NeyroConfig.storyViewersAlways));
        int hiddenStoriesCount = NeyroHiddenStories.getInstance(currentAccount).getAll().size();
        items.add(UItem.asButton(ID_HIDDEN_STORIES, R.drawable.msg_block, getString(R.string.NeyroHiddenStories), hiddenStoriesCount > 0 ? String.valueOf(hiddenStoriesCount) : null));
        items.add(UItem.asShadow(getString(R.string.NeyroHiddenStoriesFooter)));

        items.add(UItem.asHeader(getString(R.string.NeyroEmojiStatusHeader)));
        items.add(UItem.asButtonCheck(ID_EMOJI_STATUS, getString(R.string.NeyroEmojiStatus), getString(R.string.NeyroEmojiStatusInfo)).setChecked(NeyroConfig.neyroEmojiStatus));
        items.add(UItem.asButtonCheck(ID_SHARE_EMOJI_STATUS, getString(R.string.NeyroShareEmojiStatus), getString(R.string.NeyroShareEmojiStatusInfo)).setChecked(NeyroConfig.shareEmojiStatus).setEnabled(NeyroConfig.neyroEmojiStatus));
        items.add(UItem.asButton(ID_APP_ICON, R.drawable.msg_palette, getString(R.string.NeyroAppIcon)));
        items.add(UItem.asShadow(getString(R.string.NeyroEmojiStatusFooter)));

        items.add(UItem.asHeader(getString(R.string.NeyroMediaHeader)));
        items.add(UItem.asButtonCheck(ID_FAST_DOWNLOAD, getString(R.string.NeyroFastDownload), getString(R.string.NeyroFastDownloadInfo)).setChecked(NeyroConfig.fastDownload));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.NeyroInterfaceHeader)));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            items.add(UItem.asCheck(ID_SYSTEM_FONT, getString(R.string.NeyroSystemFont)).setChecked(SharedConfig.useSystemBoldFont));
        }
        items.add(UItem.asButton(ID_TRANSLATE, R.drawable.msg_translate, getString(R.string.NeyroTranslateSettings)));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.NeyroDataHeader)));
        items.add(UItem.asButton(ID_CLEAR_DELETED, R.drawable.msg_delete, getString(R.string.NeyroClearDeleted)));
        items.add(UItem.asButton(ID_CLEAR_EDITS, R.drawable.msg_clear, getString(R.string.NeyroClearEdits)));
        items.add(UItem.asShadow(getString(R.string.NeyroAbout)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_GHOST_ALL:
                NeyroConfig.setGhostModeFull(!NeyroConfig.isGhostModeFull());
                break;
            case ID_GHOST_ONLINE:
                NeyroConfig.putBoolean("ghostHideOnline", NeyroConfig.ghostHideOnline = !NeyroConfig.ghostHideOnline);
                break;
            case ID_GHOST_READ:
                NeyroConfig.putBoolean("ghostNoReadReceipts", NeyroConfig.ghostNoReadReceipts = !NeyroConfig.ghostNoReadReceipts);
                break;
            case ID_GHOST_TYPING:
                NeyroConfig.putBoolean("ghostHideTyping", NeyroConfig.ghostHideTyping = !NeyroConfig.ghostHideTyping);
                break;
            case ID_GHOST_STORIES:
                NeyroConfig.putBoolean("ghostHideStoryViews", NeyroConfig.ghostHideStoryViews = !NeyroConfig.ghostHideStoryViews);
                break;
            case ID_SAVE_DELETED:
                NeyroConfig.putBoolean("saveDeletedMessages", NeyroConfig.saveDeletedMessages = !NeyroConfig.saveDeletedMessages);
                break;
            case ID_SAVE_EDITS:
                NeyroConfig.putBoolean("saveEditHistory", NeyroConfig.saveEditHistory = !NeyroConfig.saveEditHistory);
                break;
            case ID_FORWARD_NO_AUTHOR:
                NeyroConfig.putBoolean("forwardWithoutAuthor", NeyroConfig.forwardWithoutAuthor = !NeyroConfig.forwardWithoutAuthor);
                break;
            case ID_FAST_DOWNLOAD:
                NeyroConfig.putBoolean("fastDownload", NeyroConfig.fastDownload = !NeyroConfig.fastDownload);
                break;
            case ID_SYSTEM_FONT:
                SharedConfig.toggleUseSystemBoldFont();
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info, getString(R.string.NeyroRestartRequired)).show();
                break;
            case ID_GUIDE:
                presentFragment(new NeyroFeaturesActivity());
                return;
            case ID_STORY_VIEWERS:
                NeyroConfig.putBoolean("storyViewersAlways", NeyroConfig.storyViewersAlways = !NeyroConfig.storyViewersAlways);
                break;
            case ID_EMOJI_STATUS:
                NeyroConfig.putBoolean("neyroEmojiStatus", NeyroConfig.neyroEmojiStatus = !NeyroConfig.neyroEmojiStatus);
                NeyroEmojiStatus.getInstance(currentAccount).syncBio();
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info, getString(R.string.NeyroRestartRequired)).show();
                break;
            case ID_SHARE_EMOJI_STATUS:
                if (!NeyroConfig.neyroEmojiStatus) {
                    return;
                }
                NeyroConfig.putBoolean("shareEmojiStatus", NeyroConfig.shareEmojiStatus = !NeyroConfig.shareEmojiStatus);
                NeyroEmojiStatus.getInstance(currentAccount).syncBio();
                break;
            case ID_APP_ICON:
                presentFragment(new org.telegram.ui.ThemeActivity(org.telegram.ui.ThemeActivity.THEME_TYPE_BASIC));
                return;
            case ID_HIDDEN_STORIES:
                presentFragment(new NeyroHiddenStoriesActivity());
                return;
            case ID_TRANSLATE:
                presentFragment(new LanguageSelectActivity());
                return;
            case ID_HIDDEN_CHATS:
                NeyroHiddenChats.requestPin(this, () -> presentFragment(new NeyroHiddenChatsActivity()));
                return;
            case ID_CLEAR_DELETED:
            case ID_CLEAR_EDITS:
                confirmClear(item.id == ID_CLEAR_DELETED);
                return;
            default:
                return;
        }
        listView.adapter.update(true);
    }

    private void confirmClear(boolean deleted) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(getString(deleted ? R.string.NeyroClearDeleted : R.string.NeyroClearEdits));
        builder.setMessage(getString(R.string.NeyroClearConfirm));
        builder.setPositiveButton(getString(R.string.Delete), (d, w) -> {
            if (deleted) {
                NeyroDeletedMessages.getInstance(currentAccount).clear();
            } else {
                NeyroEditHistory.getInstance(currentAccount).clear();
            }
            BulletinFactory.of(this).createSimpleBulletin(R.raw.done, getString(R.string.NeyroCleared)).show();
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        dialog.redPositive();
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null) {
            listView.adapter.update(false);
        }
    }
}
