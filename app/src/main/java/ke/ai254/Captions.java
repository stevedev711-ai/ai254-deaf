package ke.ai254;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

final class Captions {
    static { System.loadLibrary("ai254"); }
    private static native long open(String path);
    private static native void close(long context);
    private static native byte[] decode(long context, short[] pcm, boolean swahili);

    private volatile boolean stopped;
    private AudioRecord microphone;

    synchronized void stop() {
        stopped = true;
        if (microphone != null && microphone.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            microphone.stop();
        }
    }

    void run(File model, boolean swahili, Consumer<String> caption) {
        long context = open(model.getAbsolutePath());
        if (context == 0) throw new IllegalArgumentException("Could not load the Whisper model");
        try {
            int minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IllegalStateException("16 kHz microphone capture is unavailable");
            synchronized (this) {
                if (stopped) return;
                microphone = new AudioRecord(MediaRecorder.AudioSource.MIC, 16000,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minimum, 384000));
                microphone.startRecording();
            }
            short[] segment = new short[64000];
            while (!stopped) {
                int offset = 0;
                while (offset < segment.length && !stopped) {
                    int count = microphone.read(segment, offset, segment.length - offset);
                    if (count < 0 && !stopped) throw new IllegalStateException("Microphone read failed: " + count);
                    if (count > 0) offset += count;
                }
                if (stopped) break;
                byte[] text = decode(context, segment, swahili);
                if (text == null && !stopped) throw new IllegalStateException("Whisper inference failed");
                if (text != null && !stopped) caption.accept(new String(text, StandardCharsets.UTF_8).strip());
            }
        } finally {
            synchronized (this) {
                if (microphone != null) {
                    microphone.release();
                    microphone = null;
                }
            }
            close(context);
        }
    }
}
