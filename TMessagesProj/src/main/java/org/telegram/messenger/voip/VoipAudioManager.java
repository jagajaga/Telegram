package org.telegram.messenger.voip;

import static android.content.Context.AUDIO_SERVICE;

import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;


import androidx.annotation.RequiresApi;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BatteryDiary;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

public class VoipAudioManager {

    private Boolean isSpeakerphoneOn;

    private VoipAudioManager() {

    }

    private static final class InstanceHolder {
        static final VoipAudioManager instance = new VoipAudioManager();
    }

    public static VoipAudioManager get() {
        return InstanceHolder.instance;
    }

    /**
     * Sets the speakerphone on or off asynchronously.
     * On Samsung devices {@link AudioManager#setSpeakerphoneOn} and {@link AudioManager#isSpeakerphoneOn} take too much time.
     */
    public void setSpeakerphoneOn(boolean on) {
        isSpeakerphoneOn = on;
        final AudioManager audioManager = getAudioManager();
        Utilities.globalQueue.postRunnable(() -> {
            audioManager.setSpeakerphoneOn(on);
        });
    }

    /**
     * Checks whether the speakerphone is on or off.
     * {@link AudioManager#isSpeakerphoneOn} is fast if {@link AudioManager#setSpeakerphoneOn} has not been called before.
     */
    public boolean isSpeakerphoneOn() {
        if (isSpeakerphoneOn == null) {
            AudioManager audioManager = getAudioManager();
            return audioManager.isSpeakerphoneOn();
        }
        return isSpeakerphoneOn;
    }

    /**
     * On Android 12+ Bluetooth call routing goes through {@link AudioManager#setCommunicationDevice}.
     * LE Audio headsets exist only there: they never connect the HFP profile, so the SCO API cannot reach them.
     */
    @RequiresApi(api = Build.VERSION_CODES.M)
    public static boolean isBluetoothDevice(AudioDeviceInfo device) {
        if (device == null) {
            return false;
        }
        int type = device.getType();
        if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && type == AudioDeviceInfo.TYPE_HEARING_AID) {
            return true;
        }
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER);
    }

    public AudioDeviceInfo findBluetoothDevice() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return null;
        }
        for (AudioDeviceInfo device : getAudioManager().getAvailableCommunicationDevices()) {
            if (isBluetoothDevice(device)) {
                return device;
            }
        }
        return null;
    }

    public boolean isBluetoothOn() {
        AudioManager audioManager = getAudioManager();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return isBluetoothDevice(audioManager.getCommunicationDevice());
        }
        return audioManager.isBluetoothScoOn();
    }

    private static final int START_RETRIES = 8;
    private static final long START_RETRY_DELAY = 250;
    private int startAttempt;
    private Runnable pendingStart;

    /**
     * Routes the call to the Bluetooth headset. On Android 12+ the headset's communication device
     * can appear a moment after the headset connects, and setCommunicationDevice() refuses while the
     * audio mode is not in-communication, so this retries for a couple of seconds, checks the result,
     * verifies the device stuck, and for classic (HFP) headsets falls back to the legacy SCO link.
     * Upstream tried once, ignored the result, and never retried: the call stayed on the earpiece.
     */
    public void startBluetooth() {
        final AudioManager audioManager = getAudioManager();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            cancelPendingStart();
            startAttempt = 0;
            startBluetoothModern(audioManager);
        } else {
            audioManager.startBluetoothSco();
        }
    }

    private void cancelPendingStart() {
        if (pendingStart != null) {
            AndroidUtilities.cancelRunOnUIThread(pendingStart);
            pendingStart = null;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private void scheduleRetry(AudioManager audioManager) {
        cancelPendingStart();
        AndroidUtilities.runOnUIThread(pendingStart = () -> {
            pendingStart = null;
            startBluetoothModern(audioManager);
        }, START_RETRY_DELAY);
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private void startBluetoothModern(AudioManager audioManager) {
        final AudioDeviceInfo device = findBluetoothDevice();
        if (device == null) {
            if (startAttempt++ < START_RETRIES) {
                if (startAttempt == 1) {
                    BatteryDiary.log("bluetooth: no Bluetooth communication device yet, retrying; available: " + describeCommunicationDevices());
                }
                scheduleRetry(audioManager);
            } else {
                BatteryDiary.log("bluetooth: gave up after " + START_RETRIES + " attempts; available: " + describeCommunicationDevices());
            }
            return;
        }
        isSpeakerphoneOn = false;
        final int attempt = startAttempt + 1;
        Utilities.globalQueue.postRunnable(() -> {
            if (audioManager.getMode() != AudioManager.MODE_IN_COMMUNICATION) {
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            }
            final boolean ok = audioManager.setCommunicationDevice(device);
            BatteryDiary.log("bluetooth: setCommunicationDevice(" + device.getProductName() + ", type " + device.getType() + ") -> " + ok + " (attempt " + attempt + ")");
            AndroidUtilities.runOnUIThread(() -> {
                if (!ok) {
                    if (startAttempt++ < START_RETRIES) {
                        scheduleRetry(audioManager);
                    } else if (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                        BatteryDiary.log("bluetooth: falling back to the legacy SCO link");
                        legacySco(audioManager);
                    }
                    return;
                }
                AndroidUtilities.runOnUIThread(() -> Utilities.globalQueue.postRunnable(() -> {
                    AudioDeviceInfo current = audioManager.getCommunicationDevice();
                    if (!isBluetoothDevice(current)) {
                        BatteryDiary.log("bluetooth: communication device did not stick, now " + (current == null ? "none" : "type " + current.getType()) + (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ? "; legacy SCO link" : ""));
                        if (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                            legacySco(audioManager);
                        }
                    }
                }), 1500);
            });
        });
    }

    private void legacySco(AudioManager audioManager) {
        try {
            audioManager.startBluetoothSco();
            audioManager.setBluetoothScoOn(true);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private String describeCommunicationDevices() {
        StringBuilder sb = new StringBuilder();
        try {
            for (AudioDeviceInfo d : getAudioManager().getAvailableCommunicationDevices()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(d.getProductName()).append("#").append(d.getType());
            }
        } catch (Throwable ignore) {
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    public void stopBluetooth() {
        final AudioManager audioManager = getAudioManager();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            cancelPendingStart();
            Utilities.globalQueue.postRunnable(() -> {
                if (isBluetoothDevice(audioManager.getCommunicationDevice())) {
                    audioManager.clearCommunicationDevice();
                }
            });
        } else {
            audioManager.stopBluetoothSco();
        }
    }

    public void setBluetoothOn(boolean on) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (on) {
                startBluetooth();
            } else {
                stopBluetooth();
            }
        } else {
            getAudioManager().setBluetoothScoOn(on);
        }
    }

    public void isBluetoothAndSpeakerOnAsync(Utilities.Callback2<Boolean, Boolean> onDone) {
        Utilities.globalQueue.postRunnable(() -> {
            AudioManager audioManager = getAudioManager();
            boolean isBluetoothOn = isBluetoothOn();
            boolean isSpeakerphoneOn = audioManager.isSpeakerphoneOn();
            AndroidUtilities.runOnUIThread(() -> onDone.run(isBluetoothOn, isSpeakerphoneOn));
        });
    }

    private AudioManager getAudioManager() {
        return (AudioManager) ApplicationLoader.applicationContext.getSystemService(AUDIO_SERVICE);
    }
}
