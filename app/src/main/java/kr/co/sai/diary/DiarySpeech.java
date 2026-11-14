package kr.co.sai.diary;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

final class DiarySpeech {
    private static final Executor EXECUTOR = Executors.newSingleThreadExecutor();
    private static final int SAMPLE_RATE = 16_000;
    private static final int MAX_SAMPLES = SAMPLE_RATE * 60;
    private static final String MODEL_FILE = "ggml-tiny-q5_1.bin";

    interface Listener {
        void onRecording();
        void onTranscribing();
        void onResult(String text, String error);
    }

    private final Context context;
    private final Listener listener;
    private volatile boolean stopRequested;
    private volatile boolean cancelled;

    DiarySpeech(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void start() {
        EXECUTOR.execute(this::recordAndTranscribe);
    }

    void finish() {
        stopRequested = true;
    }

    void cancel() {
        cancelled = true;
        finish();
    }

    @SuppressWarnings("unused") // Called by whisper.cpp through JNI.
    private boolean isCancelled() {
        return cancelled;
    }

    private void recordAndTranscribe() {
        try {
            if (cancelled) return;
            short[] audio = recordAudio();
            if (cancelled) return;
            if (audio.length < SAMPLE_RATE / 4 || !containsSignal(audio)) {
                listener.onResult(null, "들린 음성이 없어요. 마이크 입력을 확인하고 다시 말해 주세요.");
                return;
            }

            listener.onTranscribing();
            System.loadLibrary("sai_speech");
            File model = installModel();
            if (cancelled) return;
            int threads = Math.min(4, Runtime.getRuntime().availableProcessors());
            byte[] result = transcribe(model.getAbsolutePath(), audio, threads);
            if (cancelled) return;
            if (result == null) throw new IOException("Speech inference failed");
            String text = new String(result, StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) {
                listener.onResult(null, "음성을 알아듣지 못했어요. 다시 말해 주세요.");
            } else {
                listener.onResult(text, null);
            }
        } catch (IOException | RuntimeException | LinkageError error) {
            if (!cancelled) {
                android.util.Log.w("SaiSpeech", "Unable to process diary audio", error);
                listener.onResult(null, "음성을 처리하지 못했어요. 다시 시도해 주세요.");
            }
        }
    }

    private short[] recordAudio() throws IOException {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Microphone permission is unavailable");
        }
        int bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (bufferSize <= 0) throw new IOException("Audio input format unavailable");

        short[] samples = new short[MAX_SAMPLES];
        int size = 0;
        AudioRecord recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(bufferSize, 3_200));
        try {
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IOException("Audio input unavailable");
            }
            if (stopRequested) return new short[0];
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IOException("Audio input did not start");
            }
            listener.onRecording();
            while (!stopRequested && size < samples.length) {
                int count = recorder.read(samples, size, Math.min(1_600, samples.length - size));
                if (count <= 0) throw new IOException("Unable to read audio: " + count);
                size += count;
            }
        } finally {
            try {
                if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) recorder.stop();
            } finally {
                recorder.release();
            }
        }
        return Arrays.copyOf(samples, size);
    }

    private static boolean containsSignal(short[] samples) {
        for (short sample : samples) {
            if (Math.abs((int) sample) >= 64) return true;
        }
        return false;
    }

    private File installModel() throws IOException {
        File target = new File(context.getFilesDir(), MODEL_FILE);
        if (target.isFile()) return target;

        File staged = File.createTempFile("sai-whisper-", ".bin", context.getFilesDir());
        try {
            try (InputStream input = context.getAssets().open("speech/" + MODEL_FILE);
                 FileOutputStream output = new FileOutputStream(staged)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            if (!staged.renameTo(target)) throw new IOException("Unable to install speech model");
            return target;
        } finally {
            staged.delete();
        }
    }

    private native byte[] transcribe(String modelPath, short[] samples, int threads);
}
