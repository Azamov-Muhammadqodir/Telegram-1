package uz.neyroaza.gram;

import static org.telegram.messenger.LocaleController.getString;

import android.view.View;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * Storylari butunlay yashirilgan odamlar ro'yxati. Bosilsa, storylari qaytariladi.
 */
public class NeyroHiddenStoriesActivity extends UniversalFragment {

    private final ArrayList<Long> dialogIds = new ArrayList<>();

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.NeyroHiddenStories);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        dialogIds.clear();
        dialogIds.addAll(NeyroHiddenStories.getInstance(currentAccount).getAll());
        if (dialogIds.isEmpty()) {
            items.add(UItem.asShadow(getString(R.string.NeyroHiddenStoriesEmpty)));
            return;
        }
        for (int i = 0; i < dialogIds.size(); i++) {
            items.add(UItem.asButton(i, R.drawable.msg_openprofile, getName(dialogIds.get(i))));
        }
        items.add(UItem.asShadow(getString(R.string.NeyroHiddenStoriesHint)));
    }

    private String getName(long dialogId) {
        if (DialogObject.isUserDialog(dialogId)) {
            TLRPC.User user = getMessagesController().getUser(dialogId);
            if (user != null) {
                return UserObject.getUserName(user);
            }
        } else {
            TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
            if (chat != null) {
                return chat.title;
            }
        }
        return String.valueOf(dialogId);
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id < 0 || item.id >= dialogIds.size()) {
            return;
        }
        long dialogId = dialogIds.get(item.id);
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(getName(dialogId));
        builder.setMessage(getString(R.string.NeyroUnhideStoriesConfirm));
        builder.setPositiveButton(getString(R.string.NeyroShowStories), (d, w) -> {
            getMessagesController().getStoriesController().neyroSetStoriesHidden(dialogId, false);
            listView.adapter.update(true);
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
