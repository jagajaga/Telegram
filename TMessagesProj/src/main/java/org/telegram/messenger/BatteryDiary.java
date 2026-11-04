package org.telegram.messenger;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.TrafficStats;
import android.os.BatteryManager;
import android.os.Process;
import android.os.SystemClock;

import androidx.core.content.FileProvider;

import org.telegram.tgnet.ConnectionsManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.FileInputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * A plain-text diary of what the app does while nobody is looking at it, so battery questions
 * can be answered from a file instead of a guess: every foreground/background transition with
 * the CPU time, bytes and events of the session that just ended, every push and whether it woke
 * the connection, every file download started in the background, every prefetch batch, every
 * alarm, every connection-state change in the background, and every process start.
 * <p>
 * Lives in the logs directory as battery-diary.log (rotated at 2 MB). Settings → Data and
 * Storage → Battery can share it, and optionally sends a copy to Saved Messages once a day.
 */
public class BatteryDiary {

    private static final long MAX_SIZE = 2L * 1024 * 1024;
    private static final int MAX_DOWNLOAD_LINES_PER_SESSION = 40;
    private static final long AUTO_SEND_INTERVAL = 24L * 60 * 60 * 1000;

    private static final SimpleDateFormat FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private static final SimpleDateFormat DAY_FORMAT = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private static final DispatchQueue queue = new DispatchQueue("batteryDiary");

    private static final Object sessionLock = new Object();
    private static boolean inBackground = true;
    private static boolean sessionStarted;
    private static long sessionStart;
    private static long sessionCpu;
    private static long sessionRx;
    private static long sessionTx;
    private static int sessionPushes;
    private static int sessionPushWakes;
    private static int sessionDownloads;
    private static int sessionPrefetched;
    private static int sessionAlarms;
    private static int sessionConnections;
    private static long foregroundAt;
    private static boolean connectedAfterForegroundLogged;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("battery", Context.MODE_PRIVATE);
    }

    public static File getFile() {
        return new File(AndroidUtilities.getLogsDir(), "battery-diary.log");
    }

    public static boolean autoSendEnabled() {
        return prefs().getBoolean("diaryAutoSend", true);
    }

    public static void setAutoSendEnabled(boolean value) {
        prefs().edit().putBoolean("diaryAutoSend", value).apply();
    }

    // ---- events ----

    public static void processStarted() {
        String importance = "";
        try {
            ActivityManager.RunningAppProcessInfo info = new ActivityManager.RunningAppProcessInfo();
            ActivityManager.getMyMemoryState(info);
            importance = info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ? "foreground" : "background (importance " + info.importance + ")";
        } catch (Throwable ignore) {
        }
        log("process start, " + importance);
        synchronized (sessionLock) {
            startSession();
        }
    }

    public static void onForeground() {
        synchronized (sessionLock) {
            if (!inBackground && sessionStarted) {
                return;
            }
            endSession("background");
            inBackground = false;
            startSession();
        }
        foregroundAt = SystemClock.elapsedRealtime();
        connectedAfterForegroundLogged = false;
        log("foreground");
        AndroidUtilities.runOnUIThread(BatteryPolicy::ensureCallsReachable, 1500);
        maybeAutoSend();
    }

    public static void onBackground() {
        synchronized (sessionLock) {
            if (inBackground && sessionStarted) {
                return;
            }
            endSession("foreground");
            inBackground = true;
            startSession();
        }
        log("background");
    }

    public static void push(String locKey, boolean wokeNetwork) {
        synchronized (sessionLock) {
            sessionPushes++;
            if (wokeNetwork) {
                sessionPushWakes++;
            }
        }
        log("push " + (locKey == null ? "?" : locKey) + (wokeNetwork ? " -> network wake" : " -> notification only"));
    }

    public static void download(String fileName, long size, int priority) {
        if (!ApplicationLoader.mainInterfacePaused) {
            return;
        }
        int n;
        synchronized (sessionLock) {
            n = ++sessionDownloads;
        }
        if (n <= MAX_DOWNLOAD_LINES_PER_SESSION) {
            log("download " + fileName + " (" + AndroidUtilities.formatFileSize(size) + ", priority " + priority + ")");
        } else if (n == MAX_DOWNLOAD_LINES_PER_SESSION + 1) {
            log("download … further downloads of this session not listed");
        }
    }

    public static void prefetch(int type, int count) {
        synchronized (sessionLock) {
            sessionPrefetched += count;
        }
        log("prefetch " + count + " item(s) of type " + type);
    }

    public static void alarm(String what) {
        synchronized (sessionLock) {
            sessionAlarms++;
        }
        log("alarm " + what);
    }

    public static void connection(int account, int state) {
        if (!ApplicationLoader.mainInterfacePaused) {
            if (!connectedAfterForegroundLogged && foregroundAt != 0 && state == ConnectionsManager.ConnectionStateConnected) {
                connectedAfterForegroundLogged = true;
                log("connected " + (SystemClock.elapsedRealtime() - foregroundAt) + " ms after foreground (account" + account + ")");
            } else if (!connectedAfterForegroundLogged && foregroundAt != 0 && SystemClock.elapsedRealtime() - foregroundAt < 30000) {
                log("connection account" + account + " " + connectionStateName(state) + " (" + (SystemClock.elapsedRealtime() - foregroundAt) + " ms after foreground)");
            }
            return;
        }
        synchronized (sessionLock) {
            sessionConnections++;
        }
        log("connection account" + account + " " + connectionStateName(state));
    }

    public static void log(String event) {
        final String line = FORMAT.format(new Date()) + " | " + (ApplicationLoader.mainInterfacePaused ? "bg" : "fg") + " | " + deviceState() + " | " + event + "\n";
        queue.postRunnable(() -> append(line));
    }

    // ---- sessions ----

    private static void startSession() {
        sessionStarted = true;
        sessionStart = SystemClock.elapsedRealtime();
        sessionCpu = Process.getElapsedCpuTime();
        sessionRx = TrafficStats.getUidRxBytes(Process.myUid());
        sessionTx = TrafficStats.getUidTxBytes(Process.myUid());
        sessionPushes = sessionPushWakes = sessionDownloads = sessionPrefetched = sessionAlarms = sessionConnections = 0;
    }

    private static void endSession(String kind) {
        if (!sessionStarted) {
            return;
        }
        long duration = SystemClock.elapsedRealtime() - sessionStart;
        long cpu = Process.getElapsedCpuTime() - sessionCpu;
        long rx = TrafficStats.getUidRxBytes(Process.myUid()) - sessionRx;
        long tx = TrafficStats.getUidTxBytes(Process.myUid()) - sessionTx;
        log(String.format(Locale.US, "%s session %s: cpu %d ms, rx %s, tx %s, pushes %d (woke network %d), background downloads %d, prefetched %d, alarms %d, connection changes %d",
            kind, formatDuration(duration), cpu, AndroidUtilities.formatFileSize(Math.max(0, rx)), AndroidUtilities.formatFileSize(Math.max(0, tx)),
            sessionPushes, sessionPushWakes, sessionDownloads, sessionPrefetched, sessionAlarms, sessionConnections));
    }

    // ---- export ----

    public static void share(Activity activity) {
        try {
            File file = getFile();
            if (activity == null || !file.exists()) {
                return;
            }
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", file));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(intent, "Battery diary"));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static void maybeAutoSend() {
        if (!autoSendEnabled()) {
            return;
        }
        final long last = prefs().getLong("diaryLastSend", 0);
        if (System.currentTimeMillis() - last < AUTO_SEND_INTERVAL) {
            return;
        }
        final int account = UserConfig.selectedAccount;
        if (!UserConfig.getInstance(account).isClientActivated()) {
            return;
        }
        prefs().edit().putLong("diaryLastSend", System.currentTimeMillis()).apply();
        queue.postRunnable(() -> {
            try {
                File file = getFile();
                if (!file.exists() || file.length() == 0) {
                    return;
                }
                File copy = new File(AndroidUtilities.getLogsDir(), "battery-diary-" + DAY_FORMAT.format(new Date()) + ".log");
                try (FileInputStream in = new FileInputStream(file); FileOutputStream out = new FileOutputStream(copy)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                }
                final String path = copy.getAbsolutePath();
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        SendMessagesHelper.prepareSendingDocument(AccountInstance.getInstance(account), path, path, null, "Battery diary " + DAY_FORMAT.format(new Date()), "text/plain",
                            UserConfig.getInstance(account).getClientUserId(), null, null, null, null, null, false, 0, null, null, false);
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                });
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    // ---- helpers ----

    private static void append(String line) {
        try {
            File file = getFile();
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
            if (file.length() > MAX_SIZE) {
                File old = new File(dir, "battery-diary.1.log");
                old.delete();
                file.renameTo(old);
            }
            try (FileWriter writer = new FileWriter(file, true)) {
                writer.write(line);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static String deviceState() {
        StringBuilder sb = new StringBuilder();
        try {
            Intent battery = ApplicationLoader.applicationContext.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery != null) {
                int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                sb.append(scale > 0 ? level * 100 / scale : level).append('%');
                if (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL) {
                    sb.append(" charging");
                }
            }
        } catch (Throwable ignore) {
        }
        try {
            if (ApplicationLoader.isConnectedToWiFi()) {
                sb.append(" wifi");
            } else if (ApplicationLoader.isRoaming()) {
                sb.append(" roaming");
            } else if (ApplicationLoader.isNetworkOnline()) {
                sb.append(" mobile");
            } else {
                sb.append(" offline");
            }
        } catch (Throwable ignore) {
        }
        if (BatteryPolicy.powerSaving()) {
            sb.append(" power-saving");
        }
        return sb.toString();
    }

    private static String connectionStateName(int state) {
        switch (state) {
            case ConnectionsManager.ConnectionStateConnecting:
                return "connecting";
            case ConnectionsManager.ConnectionStateWaitingForNetwork:
                return "waiting for network";
            case ConnectionsManager.ConnectionStateConnected:
                return "connected";
            case ConnectionsManager.ConnectionStateConnectingToProxy:
                return "connecting to proxy";
            case ConnectionsManager.ConnectionStateUpdating:
                return "updating";
            default:
                return "state " + state;
        }
    }

    private static String formatDuration(long ms) {
        long s = ms / 1000;
        if (s < 60) {
            return s + "s";
        }
        if (s < 3600) {
            return (s / 60) + "m " + (s % 60) + "s";
        }
        return (s / 3600) + "h " + ((s % 3600) / 60) + "m";
    }
}
