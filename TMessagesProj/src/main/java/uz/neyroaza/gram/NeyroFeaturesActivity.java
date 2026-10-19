package uz.neyroaza.gram;

import static org.telegram.messenger.LocaleController.getString;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.TypefaceSpan;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * Neyrogram qo'llanmasi: har bir funksiya nima qiladi va qayerda joylashgan.
 */
public class NeyroFeaturesActivity extends UniversalFragment {

    /** {sarlavha, tavsif, joylashuv} */
    private static final int[][] GHOST = {
            {R.string.NeyroGhostOnline, R.string.NeyroGuideGhostOnline, R.string.NeyroGuideWhereGhost},
            {R.string.NeyroGhostRead, R.string.NeyroGuideGhostRead, R.string.NeyroGuideWhereGhost},
            {R.string.NeyroGhostTyping, R.string.NeyroGuideGhostTyping, R.string.NeyroGuideWhereGhost},
            {R.string.NeyroGhostStories, R.string.NeyroGuideGhostStories, R.string.NeyroGuideWhereGhost},
    };

    private static final int[][] MESSAGES = {
            {R.string.NeyroSaveDeleted, R.string.NeyroGuideSaveDeleted, R.string.NeyroGuideWhereSaveDeleted},
            {R.string.NeyroSaveEdits, R.string.NeyroGuideSaveEdits, R.string.NeyroGuideWhereSaveEdits},
            {R.string.NeyroForwardNoAuthor, R.string.NeyroGuideForwardNoAuthor, R.string.NeyroGuideWhereMessages},
            {R.string.NeyroSaveSticker, R.string.NeyroGuideSaveSticker, R.string.NeyroGuideWhereSaveSticker},
    };

    private static final int[][] PRIVACY = {
            {R.string.NeyroHiddenChats, R.string.NeyroGuideHiddenChats, R.string.NeyroGuideWhereHiddenChats},
            {R.string.NeyroHiddenStories, R.string.NeyroGuideHiddenStories, R.string.NeyroGuideWhereHiddenStories},
    };

    private static final int[][] STORIES = {
            {R.string.NeyroStoryViewersAlways, R.string.NeyroGuideStoryViewers, R.string.NeyroGuideWhereStoryViewers},
    };

    private static final int[][] OTHER = {
            {R.string.NeyroFastDownload, R.string.NeyroGuideFastDownload, R.string.NeyroGuideWhereMedia},
            {R.string.NeyroGuideMultiAccountTitle, R.string.NeyroGuideMultiAccount, R.string.NeyroGuideWhereMultiAccount},
            {R.string.NeyroGuideLanguagesTitle, R.string.NeyroGuideLanguages, R.string.NeyroGuideWhereLanguages},
            {R.string.NeyroAppIcon, R.string.NeyroGuideAppIcon, R.string.NeyroGuideWhereAppIcon},
    };

    private static final int[][] EMOJI_STATUS = {
            {R.string.NeyroEmojiStatus, R.string.NeyroGuideEmojiStatus, R.string.NeyroGuideWhereEmojiStatus},
            {R.string.NeyroShareEmojiStatus, R.string.NeyroGuideShareEmojiStatus, R.string.NeyroGuideWhereShareEmojiStatus},
    };

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.NeyroFeaturesGuide);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asShadow(getString(R.string.NeyroGuideIntro)));
        addSection(items, R.string.NeyroGhostHeader, GHOST);
        addSection(items, R.string.NeyroMessagesHeader, MESSAGES);
        addSection(items, R.string.NeyroPrivacyHeader, PRIVACY);
        addSection(items, R.string.NeyroStoriesHeader, STORIES);
        addSection(items, R.string.NeyroEmojiStatusHeader, EMOJI_STATUS);
        addSection(items, R.string.NeyroGuideOtherHeader, OTHER);
    }

    private void addSection(ArrayList<UItem> items, int headerRes, int[][] features) {
        items.add(UItem.asHeader(getString(headerRes)));
        for (int[] feature : features) {
            items.add(UItem.asShadow(buildText(feature[0], feature[1], feature[2])));
        }
    }

    private CharSequence buildText(int titleRes, int descriptionRes, int whereRes) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(getString(titleRes));
        sb.setSpan(new TypefaceSpan(org.telegram.messenger.AndroidUtilities.bold()), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, getResourceProvider())), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append("\n").append(getString(descriptionRes)).append("\n");
        int whereStart = sb.length();
        sb.append("📍 ").append(getString(whereRes));
        sb.setSpan(new ForegroundColorSpan(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, getResourceProvider())), whereStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
