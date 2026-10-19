package uz.neyroaza.gram;

import static org.telegram.messenger.LocaleController.getString;

import android.os.Bundle;
import android.view.View;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * PIN bilan ochiladigan yashirin chatlar ro'yxati.
 * Bosilsa chat ochiladi, uzoq bosilsa yashirinlikdan chiqariladi.
 */
public class NeyroHiddenChatsActivity extends UniversalFragment {

    private final ArrayList<Long> dialogIds = new ArrayList<>();

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.NeyroHiddenChats);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        dialogIds.clear();
        dialogIds.addAll(NeyroHiddenChats.getInstance(currentAccount).getHidden());
        if (dialogIds.isEmpty()) {
            items.add(UItem.asShadow(getString(R.string.NeyroHiddenChatsEmpty)));
            return;
        }
        for (int i = 0; i < dialogIds.size(); i++) {
            items.add(UItem.asButton(i, R.drawable.msg_openprofile, getDialogName(dialogIds.get(i))));
        }
        items.add(UItem.asShadow(getString(R.string.NeyroHiddenChatsHint)));
    }

    private String getDialogName(long dialogId) {
        if (DialogObject.isUserDialog(dialogId)) {
            TLRPC.User user = getMessagesController().getUser(dialogId);
            if (user != null) {
                return UserObject.getUserName(user);
            }
        } else if (DialogObject.isChatDialog(dialogId)) {
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
        Bundle args = new Bundle();
        if (DialogObject.isUserDialog(dialogId)) {
            args.putLong("user_id", dialogId);
        } else {
            args.putLong("chat_id", -dialogId);
        }
        presentFragment(new ChatActivity(args));
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (item.id < 0 || item.id >= dialogIds.size()) {
            return false;
        }
        long dialogId = dialogIds.get(item.id);
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(getDialogName(dialogId));
        builder.setMessage(getString(R.string.NeyroUnhideChatConfirm));
        builder.setPositiveButton(getString(R.string.NeyroUnhideChat), (d, w) -> {
            NeyroHiddenChats.getInstance(currentAccount).setHidden(dialogId, false);
            listView.adapter.update(true);
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
        return true;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null) {
            listView.adapter.update(false);
        }
    }
}
