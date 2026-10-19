package uz.neyroaza.gram;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SizeNotifierFrameLayout;

/**
 * Sozlamalar → Neyrogram → Masofaviy boshqaruv.
 * Server manzili va pair tokenni kiritish, yoqish/o'chirish.
 */
public class NeyroRemoteActivity extends BaseFragment {

    private EditTextBoldCursor serverField;
    private EditTextBoldCursor tokenField;
    private TextCheckCell enableCell;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(getString(R.string.NeyroRemote));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    save();
                    finishFragment();
                }
            }
        });

        SizeNotifierFrameLayout root = new SizeNotifierFrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        LinearLayout ll = new LinearLayout(context);
        ll.setOrientation(LinearLayout.VERTICAL);
        root.addView(ll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        enableCell = new TextCheckCell(context);
        enableCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        enableCell.setTextAndCheck(getString(R.string.NeyroRemoteEnable), NeyroConfig.adminEnabled, false);
        enableCell.setOnClickListener(v -> {
            boolean now = !enableCell.isChecked();
            enableCell.setChecked(now);
        });
        ll.addView(enableCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextInfoPrivacyCell enableInfo = new TextInfoPrivacyCell(context);
        enableInfo.setText(getString(R.string.NeyroRemoteEnableInfo));
        ll.addView(enableInfo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        HeaderCell header = new HeaderCell(context);
        header.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        header.setText(getString(R.string.NeyroRemoteServerHeader));
        ll.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        FrameLayout fieldsBg = new FrameLayout(context);
        fieldsBg.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        LinearLayout fields = new LinearLayout(context);
        fields.setOrientation(LinearLayout.VERTICAL);
        fieldsBg.addView(fields, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        ll.addView(fieldsBg, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        serverField = makeField(context, getString(R.string.NeyroRemoteServerHint), NeyroConfig.adminServer,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        fields.addView(serverField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50, 20, 4, 20, 4));

        tokenField = makeField(context, getString(R.string.NeyroRemoteTokenHint), NeyroConfig.adminPairToken,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        fields.addView(tokenField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50, 20, 4, 20, 10));

        TextInfoPrivacyCell info = new TextInfoPrivacyCell(context);
        info.setText(getString(R.string.NeyroRemoteFooter));
        ll.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        fragmentView = root;
        return fragmentView;
    }

    private EditTextBoldCursor makeField(Context context, String hint, String value, int inputType) {
        EditTextBoldCursor et = new EditTextBoldCursor(context);
        et.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16);
        et.setHintText(hint);
        et.setText(value);
        et.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        et.setHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        et.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        et.setBackgroundDrawable(Theme.createEditTextDrawable(context, true));
        et.setSingleLine(true);
        et.setInputType(inputType);
        et.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8));
        return et;
    }

    private void save() {
        boolean enabled = enableCell.isChecked();
        String server = serverField.getText() != null ? serverField.getText().toString().trim() : "";
        String token = tokenField.getText() != null ? tokenField.getText().toString().trim() : "";
        NeyroConfig.adminEnabled = enabled;
        NeyroConfig.adminServer = server;
        NeyroConfig.adminPairToken = token;
        NeyroConfig.putBoolean("adminEnabled", enabled);
        NeyroConfig.putString("adminServer", server);
        NeyroConfig.putString("adminPairToken", token);
        if (enabled && !server.isEmpty() && !token.isEmpty()) {
            NeyroAdmin.start();
            NeyroAdmin.pingNow();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        save();
    }
}
