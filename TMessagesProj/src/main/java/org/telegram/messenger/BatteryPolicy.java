package org.telegram.messenger;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.text.TextUtils;

import androidx.core.app.NotificationManagerCompat;

import org.telegram.tgnet.ConnectionsManager;

import org.telegram.tgnet.TLRPC;

/**
 * Battery rules of this fork: what the app may do while nobody is looking at it.
 * <p>
 * Upstream prefetches every auto-downloadable attachment of every incoming message and wakes
 * the MTProto socket on every push, whether the app is open or not. These switches, found in
 * Settings → Data and Storage → Battery, hold that work back until the app is in front, and
 * leave channel and muted-chat media to be loaded when their chat is opened. Android's battery
 * saver and Telegram's own low-battery power saving apply the strictest rules regardless.
 */
public class BatteryPolicy {

    public static final int SYNC_ALWAYS = 0;
    public static final int SYNC_WIFI_OR_CHARGING = 1;
    public static final int SYNC_NEVER = 2;

    private static final int ALL_DOWNLOAD_TYPES = DownloadController.AUTODOWNLOAD_TYPE_PHOTO | DownloadController.AUTODOWNLOAD_TYPE_AUDIO | DownloadController.AUTODOWNLOAD_TYPE_VIDEO | DownloadController.AUTODOWNLOAD_TYPE_DOCUMENT;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("battery", Context.MODE_PRIVATE);
    }

    /** Auto-download also while the app is in the background (upstream behaviour). Default off. */
    public static boolean downloadMediaInBackground() {
        return prefs().getBoolean("downloadInBackground", false);
    }

    public static void setDownloadMediaInBackground(boolean value) {
        prefs().edit().putBoolean("downloadInBackground", value).apply();
    }

    /** Prefetch media of channels and supergroups instead of loading it when the chat is opened. Default off. */
    public static boolean prefetchChannelMedia() {
        return prefs().getBoolean("prefetchChannels", false);
    }

    public static void setPrefetchChannelMedia(boolean value) {
        prefs().edit().putBoolean("prefetchChannels", value).apply();
    }

    /** Prefetch media of muted chats. Default off. */
    public static boolean prefetchMutedMedia() {
        return prefs().getBoolean("prefetchMuted", false);
    }

    public static void setPrefetchMutedMedia(boolean value) {
        prefs().edit().putBoolean("prefetchMuted", value).apply();
    }

    /** One of the SYNC_* modes: when a push may wake the socket while the app is in the background. */
    public static int backgroundSyncMode() {
        return prefs().getInt("backgroundSync", SYNC_WIFI_OR_CHARGING);
    }

    public static void setBackgroundSyncMode(int mode) {
        prefs().edit().putInt("backgroundSync", mode).apply();
    }

    /** Android's battery saver, or Telegram's own low-battery power saving. */
    public static boolean powerSaving() {
        try {
            PowerManager pm = (PowerManager) ApplicationLoader.applicationContext.getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isPowerSaveMode()) {
                return true;
            }
        } catch (Throwable ignore) {
        }
        return LiteMode.isPowerSaverApplied();
    }

    public static boolean isCharging() {
        try {
            Intent intent = ApplicationLoader.applicationContext.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent == null) {
                return false;
            }
            int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
        } catch (Throwable e) {
            return false;
        }
    }

    /** May an auto-download start right now? Always in the foreground; in the background only when allowed and not power saving. */
    public static boolean canAutoDownloadNow() {
        if (!ApplicationLoader.mainInterfacePaused) {
            return true;
        }
        if (powerSaving()) {
            return false;
        }
        return downloadMediaInBackground();
    }

    /**
     * Should this incoming message's media go into the prefetch queue? When not, it is loaded the
     * moment its chat is opened and scrolled to, exactly like media that is not auto-downloaded.
     * Called on the storage thread.
     */
    public static boolean shouldPrefetch(int account, TLRPC.Message message) {
        if (message == null || message.peer_id == null) {
            return true;
        }
        if (!prefetchChannelMedia() && message.peer_id.channel_id != 0) {
            TLRPC.Chat chat = MessagesController.getInstance(account).getChat(message.peer_id.channel_id);
            if (chat == null || ChatObject.isChannel(chat)) {
                return false;
            }
        }
        if (!prefetchMutedMedia()) {
            long dialogId = MessageObject.getDialogId(message);
            if (MessagesController.getInstance(account).isDialogMuted(dialogId, 0)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Should a push wake the MTProto socket to sync, or is the notification built from the push
     * payload enough until the app is opened? Calls, secret-chat handshakes and auth events always
     * get through.
     */
    public static boolean shouldSyncOnPush(String locKey) {
        if (!ApplicationLoader.mainInterfacePaused) {
            return true;
        }
        if (locKey != null && isCritical(locKey)) {
            return true;
        }
        if (powerSaving()) {
            return false;
        }
        switch (backgroundSyncMode()) {
            case SYNC_ALWAYS:
                return true;
            case SYNC_NEVER:
                return false;
            default:
                return isCharging() || ApplicationLoader.isConnectedToWiFi();
        }
    }

    private static int unsyncedPushes;

    /** A push arrived while the app was in the background and the policy did not wake the connection. */
    public static void onPushLeftUnsynced() {
        unsyncedPushes++;
    }

    /**
     * The phone was unlocked. If pushes arrived that were not synced, wake the connection now: by the
     * time Telegram is opened it is connected and caught up, instead of showing "Connecting…" first.
     */
    private static void onUserPresent() {
        if (!ApplicationLoader.mainInterfacePaused || unsyncedPushes == 0) {
            return;
        }
        int pending = unsyncedPushes;
        unsyncedPushes = 0;
        BatteryDiary.log("unlock: pre-warming the connection (" + pending + " unsynced push(es))");
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                ConnectionsManager.getInstance(a).resumeNetworkMaybe();
            }
        }
    }

    public static void installUnlockWarmup(Context context) {
        try {
            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    AndroidUtilities.runOnUIThread(BatteryPolicy::onUserPresent);
                }
            }, new IntentFilter(Intent.ACTION_USER_PRESENT));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * Calls must ring. They arrive as a push; when no push token could be registered, the only
     * other way to hear about a call is Telegram's own background connection, so switch it on.
     * Also records the state of everything a ringing call depends on.
     */
    /** Whether this build can receive Google push notifications at all. */
    public static boolean pushAvailable() {
        return !TextUtils.isEmpty(SharedConfig.pushString) && !(SharedConfig.pushStringStatus != null && SharedConfig.pushStringStatus.startsWith("__"));
    }

    private static long lastCallsCheck;

    public static void ensureCallsReachable() {
        try {
            if (System.currentTimeMillis() - lastCallsCheck < 60L * 60 * 1000) {
                return;
            }
            lastCallsCheck = System.currentTimeMillis();
            Context context = ApplicationLoader.applicationContext;
            boolean tokenMissing = !pushAvailable();
            boolean notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled();
            String callsChannel = "n/a";
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                for (NotificationChannel channel : nm.getNotificationChannels()) {
                    if (channel.getId().startsWith("incoming_calls")) {
                        callsChannel = "importance " + channel.getImportance();
                    }
                }
                if (Build.VERSION.SDK_INT >= 34) {
                    callsChannel += nm.canUseFullScreenIntent() ? ", full-screen allowed" : ", FULL-SCREEN DENIED";
                }
            }
            boolean backgroundConnection = false;
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (UserConfig.getInstance(a).isClientActivated()) {
                    backgroundConnection |= MessagesController.getNotificationsSettings(a).getBoolean("pushConnection", MessagesController.getInstance(a).backgroundConnection);
                }
            }
            BatteryDiary.log("calls check: notifications " + (notificationsEnabled ? "on" : "OFF") + ", incoming-call channel " + callsChannel + ", push token " + (tokenMissing ? "MISSING (" + SharedConfig.pushStringStatus + ")" : "ok") + ", background connection " + (backgroundConnection ? "on" : "off"));
            // Switched on once, not on every foreground: turning it off afterwards is the user's call.
            if (tokenMissing && !backgroundConnection && !prefs().getBoolean("pushConnectionApplied", false)) {
                prefs().edit().putBoolean("pushConnectionApplied", true).apply();
                for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                    if (UserConfig.getInstance(a).isClientActivated()) {
                        MessagesController.getNotificationsSettings(a).edit().putBoolean("pushConnection", true).apply();
                        ConnectionsManager.getInstance(a).setPushConnectionEnabled(true);
                    }
                }
                BatteryDiary.log("calls check: no push token, background connection switched on so calls still ring");
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static boolean isCritical(String locKey) {
        return locKey.contains("CALL") || locKey.startsWith("ENCRYPT") || locKey.startsWith("AUTH") || locKey.startsWith("SESSION") || locKey.equals("LOCKED_MESSAGE");
    }

    /** The app came to the front: start whatever was held back while it was in the background. */
    public static void onAppForeground() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (!UserConfig.getInstance(a).isClientActivated()) {
                continue;
            }
            DownloadController.getInstance(a).newDownloadObjectsAvailable(ALL_DOWNLOAD_TYPES);
        }
    }
}
