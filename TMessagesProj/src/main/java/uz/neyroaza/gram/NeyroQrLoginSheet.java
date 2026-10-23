package uz.neyroaza.gram;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.TelegramQRCodeWriter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.HashMap;

/**
 * QR orqali login: ekranda tg://login?token=... QR ko'rsatiladi, user uni boshqa qurilmadagi
 * Telegram bilan (Sozlamalar → Qurilmalar → Qurilmani ulash) skanerlaydi. Raqam va kod kerak emas.
 * Rasmiy Telegram sessiyasi ko'chirilmaydi — server bu qurilma uchun yangi sessiya ochadi.
 */
public class NeyroQrLoginSheet extends BottomSheet implements NotificationCenter.NotificationCenterDelegate {

    public interface Delegate {
        void onAuthorized(TLRPC.TL_auth_authorization authorization);
        void onPasswordNeeded();
    }

    private static final String LINK_PREFIX = "tg://login?token=";
    private static final int REQUEST_FLAGS = ConnectionsManager.RequestFlagFailOnServerErrors | ConnectionsManager.RequestFlagWithoutLogin | ConnectionsManager.RequestFlagEnableUnauthorized;

    private final int account;
    private final Delegate delegate;
    private final ImageView qrView;
    private final TextView statusView;

    private int reqId;
    private boolean finished;
    private final Runnable refreshRunnable = this::exportToken;

    public NeyroQrLoginSheet(Context context, int account, Delegate delegate) {
        super(context, false);
        this.account = account;
        this.delegate = delegate;
        fixNavigationBar();

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        titleView.setGravity(Gravity.CENTER);
        titleView.setText(getString(R.string.NeyroQrLoginTitle));
        layout.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 20, 24, 0));

        // QR doim oq fonda qora — qorong'i temada ham kamera yaxshi o'qishi uchun
        FrameLayout qrContainer = new FrameLayout(context);
        GradientDrawable qrBackground = new GradientDrawable();
        qrBackground.setColor(0xffffffff);
        qrBackground.setCornerRadius(dp(16));
        qrContainer.setBackground(qrBackground);
        qrContainer.setPadding(dp(12), dp(12), dp(12), dp(12));
        qrView = new ImageView(context);
        qrView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qrContainer.addView(qrView, LayoutHelper.createFrame(216, 216));
        layout.addView(qrContainer, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 20, 0, 0));

        TextView stepsView = new TextView(context);
        stepsView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        stepsView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        stepsView.setLineSpacing(dp(4), 1.0f);
        stepsView.setText(getString(R.string.NeyroQrLoginSteps));
        layout.addView(stepsView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 20, 24, 0));

        statusView = new TextView(context);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        statusView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        statusView.setGravity(Gravity.CENTER);
        statusView.setText(getString(R.string.NeyroQrLoginWaiting));
        layout.addView(statusView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 16, 24, 20));

        setCustomView(layout);

        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.loginTokenUpdated);
        exportToken();
    }

    private ConnectionsManager getConnectionsManager() {
        return ConnectionsManager.getInstance(account);
    }

    private void exportToken() {
        if (finished) {
            return;
        }
        AndroidUtilities.cancelRunOnUIThread(refreshRunnable);
        if (reqId != 0) {
            getConnectionsManager().cancelRequest(reqId, false);
            reqId = 0;
        }
        TLRPC.TL_auth_exportLoginToken req = new TLRPC.TL_auth_exportLoginToken();
        req.api_id = BuildVars.APP_ID;
        req.api_hash = BuildVars.APP_HASH;
        // allaqachon shu ilovaga kirgan akkauntlar bilan qayta kirib bo'lmasin
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig userConfig = UserConfig.getInstance(a);
            if (userConfig.isClientActivated()) {
                req.except_ids.add(userConfig.getClientUserId());
            }
        }
        reqId = getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            reqId = 0;
            processResponse(response, error);
        }), REQUEST_FLAGS);
    }

    private void importToken(TLRPC.TL_auth_loginTokenMigrateTo migrateTo) {
        // akkaunt boshqa DC'da — o'sha DC'ga o'tib tokenni import qilamiz (PasskeysController'dagi kabi)
        getConnectionsManager().setDefaultDatacenterId(migrateTo.dc_id);
        TLRPC.TL_auth_importLoginToken req = new TLRPC.TL_auth_importLoginToken();
        req.token = migrateTo.token;
        reqId = getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            reqId = 0;
            processResponse(response, error);
        }), null, null, REQUEST_FLAGS, migrateTo.dc_id, ConnectionsManager.ConnectionTypeGeneric, true);
    }

    private void processResponse(TLObject response, TLRPC.TL_error error) {
        if (finished) {
            return;
        }
        if (error != null) {
            if (error.text != null && error.text.contains("SESSION_PASSWORD_NEEDED")) {
                finish();
                delegate.onPasswordNeeded();
                return;
            }
            statusView.setText(TextUtils.isEmpty(error.text) ? getString(R.string.NeyroQrLoginError) : error.text);
            AndroidUtilities.runOnUIThread(refreshRunnable, 5000);
        } else if (response instanceof TLRPC.TL_auth_loginToken) {
            TLRPC.TL_auth_loginToken loginToken = (TLRPC.TL_auth_loginToken) response;
            showQr(LINK_PREFIX + Base64.encodeToString(loginToken.token, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING));
            statusView.setText(getString(R.string.NeyroQrLoginWaiting));
            // token muddati tugashidan oldin yangilaymiz; bu skanerlanganini tekshirish uchun ham zaxira
            int seconds = loginToken.expires - getConnectionsManager().getCurrentTime();
            AndroidUtilities.runOnUIThread(refreshRunnable, Math.max(5, Math.min(seconds, 30)) * 1000L);
        } else if (response instanceof TLRPC.TL_auth_loginTokenMigrateTo) {
            importToken((TLRPC.TL_auth_loginTokenMigrateTo) response);
        } else if (response instanceof TLRPC.TL_auth_loginTokenSuccess) {
            TLRPC.auth_Authorization authorization = ((TLRPC.TL_auth_loginTokenSuccess) response).authorization;
            if (authorization instanceof TLRPC.TL_auth_authorization) {
                finish();
                delegate.onAuthorized((TLRPC.TL_auth_authorization) authorization);
            } else {
                statusView.setText(getString(R.string.NeyroQrLoginError));
            }
        }
    }

    private void showQr(String link) {
        try {
            HashMap<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 0);
            int size = dp(216);
            Bitmap bitmap = new TelegramQRCodeWriter().encode(link, size, size, hints, null);
            qrView.setImageBitmap(bitmap);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void finish() {
        finished = true;
        dismiss();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.loginTokenUpdated) {
            // boshqa qurilmada skanerlandi — tokenni qayta so'rasak, server avtorizatsiyani qaytaradi
            exportToken();
        }
    }

    @Override
    public void dismiss() {
        super.dismiss();
        finished = true;
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.loginTokenUpdated);
        AndroidUtilities.cancelRunOnUIThread(refreshRunnable);
        if (reqId != 0) {
            getConnectionsManager().cancelRequest(reqId, false);
            reqId = 0;
        }
    }
}
