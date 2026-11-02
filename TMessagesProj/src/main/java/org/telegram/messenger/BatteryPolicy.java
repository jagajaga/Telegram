package org.telegram.messenger;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.PowerManager;

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
