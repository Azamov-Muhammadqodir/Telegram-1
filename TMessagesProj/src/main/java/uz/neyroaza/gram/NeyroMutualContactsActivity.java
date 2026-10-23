package uz.neyroaza.gram;

import static org.telegram.messenger.LocaleController.getString;

import android.os.Bundle;
import android.view.View;

import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.Collections;

/**
 * Umumiy (o'zaro) kontaktlar.
 *
 * - "O'zaro": mening kontaktimdagi, meni ham o'z kontaktiga saqlagan odamlar
 *   (user.mutual_contact == true).
 * - "Faqat men qo'shganlar": men saqlaganman, lekin ular meni (hali) saqlamagan.
 *
 * Diqqat: Telegram API mening kontaktimda BO'LMAGAN, lekin meni saqlagan
 * odamlarning ro'yxatini bermaydi (maxfiylik). Buni ishonchli ko'rsatib bo'lmaydi.
 */
public class NeyroMutualContactsActivity extends UniversalFragment implements NotificationCenter.NotificationCenterDelegate {

    // Ko'rsatilgan foydalanuvchi id'lari — item.id shu ro'yxatdagi indeks.
    private final ArrayList<Long> shownIds = new ArrayList<>();

    @Override
    public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.contactsDidLoad);
        getContactsController().loadContacts(true, 0);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.contactsDidLoad);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.contactsDidLoad && listView != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.NeyroMutualContacts);
    }

    private static int nameCompare(TLRPC.User a, TLRPC.User b) {
        String na = UserObject.getUserName(a);
        String nb = UserObject.getUserName(b);
        if (na == null) na = "";
        if (nb == null) nb = "";
        return na.compareToIgnoreCase(nb);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        shownIds.clear();

        final ArrayList<TLRPC.User> mutual = new ArrayList<>();
        final ArrayList<TLRPC.User> oneWay = new ArrayList<>();
        final ArrayList<TLRPC.TL_contact> contacts = getContactsController().contacts;
        for (int i = 0; i < contacts.size(); i++) {
            TLRPC.TL_contact c = contacts.get(i);
            TLRPC.User user = getMessagesController().getUser(c.user_id);
            if (user == null || user.self || UserObject.isDeleted(user)) {
                continue;
            }
            boolean isMutual = user.mutual_contact || c.mutual;
            if (isMutual) {
                mutual.add(user);
            } else {
                oneWay.add(user);
            }
        }
        Collections.sort(mutual, NeyroMutualContactsActivity::nameCompare);
        Collections.sort(oneWay, NeyroMutualContactsActivity::nameCompare);

        if (mutual.isEmpty() && oneWay.isEmpty()) {
            items.add(UItem.asShadow(getString(R.string.NeyroMutualContactsEmpty)));
            return;
        }

        if (!mutual.isEmpty()) {
            items.add(UItem.asHeader(getString(R.string.NeyroMutualContactsSection) + "  ·  " + mutual.size()));
            for (int i = 0; i < mutual.size(); i++) {
                TLRPC.User user = mutual.get(i);
                items.add(UItem.asButton(shownIds.size(), R.drawable.msg_contacts, UserObject.getUserName(user)));
                shownIds.add(user.id);
            }
            items.add(UItem.asShadow(getString(R.string.NeyroMutualContactsSectionInfo)));
        }

        if (!oneWay.isEmpty()) {
            items.add(UItem.asHeader(getString(R.string.NeyroOneWayContactsSection) + "  ·  " + oneWay.size()));
            for (int i = 0; i < oneWay.size(); i++) {
                TLRPC.User user = oneWay.get(i);
                items.add(UItem.asButton(shownIds.size(), R.drawable.msg_openprofile, UserObject.getUserName(user)));
                shownIds.add(user.id);
            }
            items.add(UItem.asShadow(getString(R.string.NeyroOneWayContactsSectionInfo)));
        }

        items.add(UItem.asShadow(getString(R.string.NeyroReverseContactsNote)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id < 0 || item.id >= shownIds.size()) {
            return;
        }
        Bundle args = new Bundle();
        args.putLong("user_id", shownIds.get(item.id));
        presentFragment(new ChatActivity(args));
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
