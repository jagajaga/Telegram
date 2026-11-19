package org.telegram.messenger.voip;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BatteryDiary;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Records a private call to an .m4a file: your microphone and the other side, mixed to mono,
 * AAC 48 kHz. The two streams are copied off WebRTC's audio threads (WebRtcAudioRecord and
 * WebRtcAudioTrack in org.webrtc.voiceengine) and mixed and encoded on a thread of their own, so
 * the call's audio path only pays for a buffer copy. Playout is the clock: every block of the
 * other side's audio is mixed with as much of your microphone as has arrived.
 * <p>
 * Finished recordings go to Recordings/Voice Recorder on the phone and to Saved Messages.
 * <p>
 * Crash safety: every encoded frame is also appended to a raw AAC (ADTS) backup next to the .m4a.
 * ADTS is self-framing, so a backup cut off at any point still plays up to the cut. A normal stop
 * deletes it; if the process dies mid-call, the next start finds it with its sidecar and saves it
 * as a recovered recording to the same two places.
 */
public class CallRecorder {

    private static final int OUT_RATE = 48000;
    private static final int BITRATE = 64000;
    private static final int MAX_MIC_BACKLOG = OUT_RATE; // 1 s

    private static volatile CallRecorder active;
    private static final DispatchQueue queue = new DispatchQueue("callRecorder");

    private final int account;
    private final long userId;
    private final String name;
    private final long startedAt = System.currentTimeMillis();
    private final long startedRealtime = SystemClock.elapsedRealtime();
    private final File file;

    private final Object lock = new Object();
    private final ArrayDeque<short[]> mic = new ArrayDeque<>();
    private int micSamples;

    private MediaCodec encoder;
    private MediaMuxer muxer;
    private int track = -1;
    private boolean muxerStarted;
    private long presentationUs;
    private boolean failed;

    private java.io.FileOutputStream backup;
    private final File backupFile;
    private final File metaFile;
    private static final String SUBFOLDER = "Voice Recorder";
    /* ---- public API, UI thread ---- */

    public static boolean isRecording() {
        return active != null;
    }

    public static long recordingStartedRealtime() {
        CallRecorder r = active;
        return r == null ? 0 : r.startedRealtime;
    }

    public static void toggle(VoIPService service) {
        if (active != null) {
            stop();
        } else {
            start(service);
        }
    }

    public static void start(VoIPService service) {
        if (active != null || service == null || service.getUser() == null || service.privateCall == null) {
            return;
        }
        TLRPC.User user = service.getUser();
        String name = ContactsController.formatName(user.first_name, user.last_name);
        CallRecorder recorder = new CallRecorder(service.getAccount(), user.id, name);
        active = recorder;
        queue.postRunnable(recorder::open);
        BatteryDiary.log("call recording: started");
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.callRecordingChanged);
    }

    public static void stop() {
        CallRecorder recorder = active;
        if (recorder == null) {
            return;
        }
        active = null;
        queue.postRunnable(recorder::finish);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.callRecordingChanged);
    }

    /* ---- automatic recording per contact ---- */

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("callrec", Context.MODE_PRIVATE);
    }

    public static Set<String> autoUsers() {
        return new HashSet<>(prefs().getStringSet("auto", new HashSet<>()));
    }

    public static boolean isAuto(long userId) {
        return autoUsers().contains(String.valueOf(userId));
    }

    public static void setAuto(long userId, boolean auto) {
        Set<String> set = autoUsers();
        if (auto) {
            set.add(String.valueOf(userId));
        } else {
            set.remove(String.valueOf(userId));
        }
        prefs().edit().putStringSet("auto", set).apply();
    }

    /** Called when a call reaches "established" and when it ends. */
    public static void onCallState(VoIPService service, int state) {
        if (state == VoIPService.STATE_ESTABLISHED) {
            if (active == null && service.getUser() != null && isAuto(service.getUser().id)) {
                start(service);
            }
        } else if (state == VoIPService.STATE_ENDED || state == VoIPService.STATE_HANGING_UP || state == VoIPService.STATE_FAILED || state == VoIPService.STATE_BUSY) {
            stop();
        }
    }

    /* ---- audio taps, audio threads ---- */

    public static void onMicPcm(ByteBuffer buffer, int bytes, int sampleRate, int channels) {
        CallRecorder r = active;
        if (r != null) {
            short[] mono = toMono(buffer, bytes, sampleRate, channels);
            synchronized (r.lock) {
                r.mic.addLast(mono);
                r.micSamples += mono.length;
                while (r.micSamples > MAX_MIC_BACKLOG && !r.mic.isEmpty()) {
                    r.micSamples -= r.mic.removeFirst().length;
                }
            }
        }
    }

    public static void onSpeakerPcm(ByteBuffer buffer, int bytes, int sampleRate, int channels) {
        CallRecorder r = active;
        if (r != null) {
            short[] mono = toMono(buffer, bytes, sampleRate, channels);
            queue.postRunnable(() -> r.mixAndEncode(mono));
        }
    }

    /** 16-bit PCM → mono at 48 kHz (averaging channels, linear resampling when needed). */
    private static short[] toMono(ByteBuffer src, int bytes, int sampleRate, int channels) {
        ByteBuffer b = src.duplicate().order(ByteOrder.nativeOrder());
        b.position(0);
        int frames = bytes / 2 / Math.max(1, channels);
        short[] mono = new short[frames];
        for (int i = 0; i < frames; i++) {
            int sum = 0;
            for (int c = 0; c < channels; c++) {
                sum += b.getShort((i * channels + c) * 2);
            }
            mono[i] = (short) (sum / Math.max(1, channels));
        }
        if (sampleRate == OUT_RATE || sampleRate <= 0) {
            return mono;
        }
        int outFrames = (int) ((long) frames * OUT_RATE / sampleRate);
        short[] out = new short[outFrames];
        for (int i = 0; i < outFrames; i++) {
            float pos = (float) i * sampleRate / OUT_RATE;
            int i0 = Math.min(frames - 1, (int) pos);
            int i1 = Math.min(frames - 1, i0 + 1);
            float t = pos - i0;
            out[i] = (short) (mono[i0] + (mono[i1] - mono[i0]) * t);
        }
        return out;
    }

    /* ---- encoding, recorder thread ---- */

    private CallRecorder(int account, long userId, String name) {
        this.account = account;
        this.userId = userId;
        this.name = name;
        File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "call_recordings");
        dir.mkdirs();
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date(startedAt));
        String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        this.file = new File(dir, "Call " + (safe.isEmpty() ? String.valueOf(userId) : safe) + " " + stamp + ".m4a");
        this.backupFile = new File(dir, file.getName().replace(".m4a", ".aac.part"));
        this.metaFile = new File(dir, file.getName().replace(".m4a", ".meta"));
    }

    private void open() {
        try {
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, OUT_RATE, 1);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            muxer = new MediaMuxer(file.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            try (java.io.FileWriter w = new java.io.FileWriter(metaFile)) {
                w.write(account + "\n" + userId + "\n" + startedAt + "\n" + name);
            }
            backup = new java.io.FileOutputStream(backupFile);
        } catch (Throwable e) {
            FileLog.e(e);
            failed = true;
            BatteryDiary.log("call recording: encoder failed to start: " + e);
        }
    }

    private void mixAndEncode(short[] speaker) {
        if (failed || encoder == null) {
            return;
        }
        short[] out = new short[speaker.length];
        int filled = 0;
        synchronized (lock) {
            while (filled < out.length && !mic.isEmpty()) {
                short[] head = mic.peekFirst();
                int take = Math.min(head.length, out.length - filled);
                System.arraycopy(head, 0, out, filled, take);
                filled += take;
                micSamples -= take;
                if (take == head.length) {
                    mic.removeFirst();
                } else {
                    short[] rest = new short[head.length - take];
                    System.arraycopy(head, take, rest, 0, rest.length);
                    mic.removeFirst();
                    mic.addFirst(rest);
                }
            }
        }
        for (int i = 0; i < out.length; i++) {
            int v = out[i] + speaker[i];
            out[i] = (short) Math.max(-32768, Math.min(32767, v));
        }
        feed(out, false);
    }

    private void feed(short[] pcm, boolean endOfStream) {
        try {
            int offset = 0;
            do {
                int inIndex = encoder.dequeueInputBuffer(10000);
                if (inIndex < 0) {
                    drain(false);
                    continue;
                }
                ByteBuffer in = encoder.getInputBuffer(inIndex);
                in.clear();
                int samples = Math.min(pcm.length - offset, in.remaining() / 2);
                ByteBuffer le = in.order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < samples; i++) {
                    le.putShort(pcm[offset + i]);
                }
                offset += samples;
                boolean last = endOfStream && offset >= pcm.length;
                encoder.queueInputBuffer(inIndex, 0, samples * 2, presentationUs, last ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                presentationUs += samples * 1000000L / OUT_RATE;
                drain(last);
            } while (offset < pcm.length);
        } catch (Throwable e) {
            FileLog.e(e);
            failed = true;
        }
    }

    private void drain(boolean untilEnd) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int outIndex = encoder.dequeueOutputBuffer(info, untilEnd ? 10000 : 0);
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!untilEnd) {
                    return;
                }
                continue;
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = muxer.addTrack(encoder.getOutputFormat());
                muxer.start();
                muxerStarted = true;
                continue;
            } else if (outIndex < 0) {
                continue;
            }
            ByteBuffer out = encoder.getOutputBuffer(outIndex);
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                info.size = 0;
            }
            if (info.size > 0 && muxerStarted) {
                out.position(info.offset);
                out.limit(info.offset + info.size);
                writeBackupFrame(out, info.size);
                out.position(info.offset);
                out.limit(info.offset + info.size);
                muxer.writeSampleData(track, out, info);
            }
            encoder.releaseOutputBuffer(outIndex, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                return;
            }
        }
    }

    /** One AAC frame with its 7-byte ADTS header (AAC LC, 48 kHz, mono). Written straight to the file, no buffering. */
    private void writeBackupFrame(ByteBuffer frame, int size) {
        if (backup == null) {
            return;
        }
        try {
            int len = size + 7;
            byte[] packet = new byte[len];
            packet[0] = (byte) 0xFF;
            packet[1] = (byte) 0xF1;
            packet[2] = (byte) ((1 << 6) | (3 << 2) | (1 >> 2)); // LC, 48 kHz, mono
            packet[3] = (byte) (((1 & 3) << 6) | (len >> 11));
            packet[4] = (byte) ((len & 0x7FF) >> 3);
            packet[5] = (byte) (((len & 7) << 5) | 0x1F);
            packet[6] = (byte) 0xFC;
            frame.get(packet, 7, size);
            backup.write(packet);
        } catch (Throwable e) {
            FileLog.e(e);
            closeBackup();
        }
    }

    private void closeBackup() {
        try {
            if (backup != null) {
                backup.close();
            }
        } catch (Throwable ignore) {
        }
        backup = null;
    }

    /** Called once at app start: saves recordings that a killed process never finished. */
    public static void recoverLeftovers() {
        queue.postRunnable(() -> {
            File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "call_recordings");
            File[] parts = dir.listFiles((d, n) -> n.endsWith(".aac.part"));
            if (parts == null) {
                return;
            }
            for (File part : parts) {
                if (active != null && part.equals(active.backupFile)) {
                    continue;
                }
                File meta = new File(dir, part.getName().replace(".aac.part", ".meta"));
                File m4a = new File(dir, part.getName().replace(".aac.part", ".m4a"));
                m4a.delete(); // never finalized, unplayable
                if (part.length() < 2000) {
                    part.delete();
                    meta.delete();
                    continue;
                }
                int account = UserConfig.selectedAccount;
                long startedAt = part.lastModified();
                String name = "";
                try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(meta))) {
                    account = Integer.parseInt(r.readLine());
                    r.readLine();
                    startedAt = Long.parseLong(r.readLine());
                    name = r.readLine();
                } catch (Throwable ignore) {
                }
                File recovered = new File(dir, part.getName().replace(".aac.part", ".aac"));
                part.renameTo(recovered);
                meta.delete();
                // ~1/48 s per frame is not stored; estimate the length from the bitrate
                long durationMs = recovered.length() * 8000L / BITRATE;
                BatteryDiary.log("call recording: recovered " + (durationMs / 1000) + " s after the app was closed mid-call");
                saveToRecordings(recovered, "audio/aac");
                sendToSavedMessages(account, recovered, "Call with " + name + ", " + new SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(new Date(startedAt)) + ", ~" + AndroidUtilities.formatShortDuration((int) (durationMs / 1000)) + " (recovered)", "audio/aac");
            }
        });
    }

    private static void sendToSavedMessages(int account, File f, String caption, String mime) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                if (!UserConfig.getInstance(account).isClientActivated()) {
                    return;
                }
                long self = UserConfig.getInstance(account).getClientUserId();
                SendMessagesHelper.prepareSendingDocument(AccountInstance.getInstance(account), f.getAbsolutePath(), f.getAbsolutePath(), null, caption, mime, self, null, null, null, null, null, false, 0, null, null, false);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    private void finish() {
        long durationMs = presentationUs / 1000;
        try {
            if (encoder != null && !failed) {
                feed(new short[0], true);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        try {
            if (encoder != null) {
                encoder.stop();
                encoder.release();
            }
        } catch (Throwable ignore) {
        }
        try {
            if (muxer != null) {
                if (muxerStarted) {
                    muxer.stop();
                }
                muxer.release();
            }
        } catch (Throwable e) {
            FileLog.e(e);
            failed = true;
        }
        closeBackup();
        String caption = "Call with " + name + ", " + new SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(new Date(startedAt)) + ", " + AndroidUtilities.formatShortDuration((int) (durationMs / 1000));
        if (durationMs < 1000) {
            BatteryDiary.log("call recording: nothing saved (too short)");
            file.delete();
            backupFile.delete();
            metaFile.delete();
            return;
        }
        if (failed || !muxerStarted) {
            // the .m4a could not be finalized; the raw AAC backup still holds the call
            file.delete();
            File aac = new File(file.getParentFile(), file.getName().replace(".m4a", ".aac"));
            if (backupFile.length() > 2000 && backupFile.renameTo(aac)) {
                metaFile.delete();
                BatteryDiary.log("call recording: m4a failed, saved the AAC backup instead");
                saveToRecordings(aac, "audio/aac");
                sendToSavedMessages(account, aac, caption, "audio/aac");
            } else {
                BatteryDiary.log("call recording: nothing saved (encoder error)");
                backupFile.delete();
                metaFile.delete();
            }
            return;
        }
        backupFile.delete();
        metaFile.delete();
        BatteryDiary.log("call recording: saved " + (durationMs / 1000) + " s, " + AndroidUtilities.formatFileSize(file.length()));
        saveToRecordings(file, "audio/mp4");
        sendToSavedMessages(account, file, caption, "audio/mp4");
    }

    /** A copy in Recordings/Voice Recorder, next to the phone's own voice recordings. */
    private static void saveToRecordings(File file, String mime) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, file.getName());
                values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                String dir = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? Environment.DIRECTORY_RECORDINGS : Environment.DIRECTORY_MUSIC;
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, dir + "/" + SUBFOLDER);
                values.put(MediaStore.Audio.Media.IS_PENDING, 1);
                Uri collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                Uri uri = ApplicationLoader.applicationContext.getContentResolver().insert(collection, values);
                if (uri == null) {
                    return;
                }
                try (OutputStream os = ApplicationLoader.applicationContext.getContentResolver().openOutputStream(uri); FileInputStream is = new FileInputStream(file)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = is.read(buf)) > 0) {
                        os.write(buf, 0, n);
                    }
                }
                values.clear();
                values.put(MediaStore.Audio.Media.IS_PENDING, 0);
                ApplicationLoader.applicationContext.getContentResolver().update(uri, values, null, null);
            } else {
                File dir = new File(ApplicationLoader.applicationContext.getExternalFilesDir(null), "Recordings/" + SUBFOLDER);
                dir.mkdirs();
                AndroidUtilities.copyFile(file, new File(dir, file.getName()));
            }
        } catch (Throwable e) {
            FileLog.e(e);
            BatteryDiary.log("call recording: could not save to Recordings: " + e);
        }
    }
}
