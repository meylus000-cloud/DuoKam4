package com.duocam.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Запис H.264 + AAC у MP4 (Movies/DuoCam). Відео береться з поверхні кодера, на яку малює GlRenderer,
 * тому зміна камери в процесі запису не створює жодного розриву у файлі.
 */
final class Recorder {

    private static final String TAG = "DuoCam.Rec";
    private static final int SAMPLE_RATE = 44100;

    private final Context ctx;
    private final GlRenderer renderer;
    private final boolean wantAudio;
    private final boolean portrait;

    private MediaCodec venc;
    private MediaCodec aenc;
    private MediaMuxer muxer;
    private AudioRecord ar;
    private ParcelFileDescriptor pfd;
    private Uri uri;
    private Surface encSurface;
    private Thread vThread;
    private Thread aThread;

    private volatile boolean audioRun;
    private final Object muxLock = new Object();
    private int vTrack = -1;
    private int aTrack = -1;
    private boolean muxStarted;
    private boolean hasAudio;
    private boolean stopFlag;
    private boolean anySample;
    private long recStartNs;
    private boolean stopped;

    Recorder(Context ctx, GlRenderer renderer, boolean wantAudio, boolean portrait) {
        this.ctx = ctx.getApplicationContext();
        this.renderer = renderer;
        this.wantAudio = wantAudio;
        this.portrait = portrait;
    }

    void start() throws IOException {
        ContentResolver res = ctx.getContentResolver();
        String name = "DuoCam_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        cv.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/DuoCam");
        cv.put(MediaStore.Video.Media.IS_PENDING, 1);
        uri = res.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new IOException("Не вдалося створити файл у галереї");
        pfd = res.openFileDescriptor(uri, "rw");
        if (pfd == null) throw new IOException("Не вдалося відкрити файл");

        try {
            muxer = new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            int vw = portrait ? GlRenderer.VIDEO_H : GlRenderer.VIDEO_W;
            int vh = portrait ? GlRenderer.VIDEO_W : GlRenderer.VIDEO_H;
            MediaFormat vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, vw, vh);
            vf.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            vf.setInteger(MediaFormat.KEY_BIT_RATE, 16_000_000);
            vf.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            vf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            venc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            venc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encSurface = venc.createInputSurface();
            venc.start();

            if (wantAudio) {
                try {
                    initAudio();
                    hasAudio = true;
                } catch (Exception e) {
                    Log.w(TAG, "audio disabled", e);
                    releaseAudio();
                    hasAudio = false;
                }
            }

            recStartNs = SystemClock.elapsedRealtimeNanos();
            if (!renderer.startEncoding(encSurface, recStartNs, portrait)) {
                throw new IOException("Не вдалося підключити кодер до OpenGL");
            }

            vThread = new Thread(this::videoLoop, "DuoCam-VideoDrain");
            vThread.start();
            if (hasAudio) {
                audioRun = true;
                ar.startRecording();
                aThread = new Thread(this::audioLoop, "DuoCam-Audio");
                aThread.start();
            }
        } catch (IOException | RuntimeException e) {
            abort();
            throw e instanceof IOException ? (IOException) e : new IOException(e.getMessage(), e);
        }
    }

    private void initAudio() {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        ar = new AudioRecord(MediaRecorder.AudioSource.CAMCORDER, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf, 16384) * 2);
        if (ar.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("AudioRecord");

        MediaFormat af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1);
        af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        af.setInteger(MediaFormat.KEY_BIT_RATE, 128_000);
        af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        try {
            aenc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            aenc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            aenc.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- потоки

    private void videoLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (true) {
                int idx = venc.dequeueOutputBuffer(info, 10_000);
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue;
                } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    addTrack(venc.getOutputFormat(), true);
                } else if (idx >= 0) {
                    ByteBuffer buf = venc.getOutputBuffer(idx);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (info.size > 0 && buf != null) {
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        writeSample(vTrack, buf, info);
                    }
                    venc.releaseOutputBuffer(idx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "videoLoop", e);
        }
    }

    private void audioLoop() {
        byte[] pcm = new byte[4096];
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long totalSamples = 0;
        long firstNs = -1;
        long lastPtsUs = 0;
        boolean eosSent = false;
        boolean outDone = false;
        try {
            while (!outDone) {
                if (!eosSent) {
                    if (audioRun) {
                        int n = ar.read(pcm, 0, pcm.length);
                        if (n > 0) {
                            if (firstNs < 0) {
                                firstNs = SystemClock.elapsedRealtimeNanos()
                                        - (long) ((n / 2) * 1e9 / SAMPLE_RATE);
                            }
                            long baseUs = Math.max(0L, (firstNs - recStartNs) / 1000L);
                            int off = 0;
                            while (off < n) {
                                int idx = aenc.dequeueInputBuffer(10_000);
                                if (idx < 0) {
                                    if (drainAudio(info)) { outDone = true; break; }
                                    continue;
                                }
                                ByteBuffer ib = aenc.getInputBuffer(idx);
                                ib.clear();
                                int len = Math.min(ib.remaining(), n - off);
                                ib.put(pcm, off, len);
                                long ptsUs = baseUs + totalSamples * 1_000_000L / SAMPLE_RATE;
                                lastPtsUs = ptsUs;
                                aenc.queueInputBuffer(idx, 0, len, ptsUs, 0);
                                off += len;
                                totalSamples += len / 2;
                            }
                        }
                    } else {
                        int idx = aenc.dequeueInputBuffer(10_000);
                        if (idx >= 0) {
                            aenc.queueInputBuffer(idx, 0, 0, lastPtsUs + 1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            eosSent = true;
                        }
                    }
                }
                if (!outDone && drainAudio(info)) outDone = true;
            }
        } catch (Exception e) {
            Log.w(TAG, "audioLoop", e);
        }
    }

    /** @return true, якщо кодер віддав EOS */
    private boolean drainAudio(MediaCodec.BufferInfo info) {
        while (true) {
            int o = aenc.dequeueOutputBuffer(info, 0);
            if (o == MediaCodec.INFO_TRY_AGAIN_LATER) return false;
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                addTrack(aenc.getOutputFormat(), false);
            } else if (o >= 0) {
                ByteBuffer buf = aenc.getOutputBuffer(o);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (info.size > 0 && buf != null) {
                    buf.position(info.offset);
                    buf.limit(info.offset + info.size);
                    writeSample(aTrack, buf, info);
                }
                aenc.releaseOutputBuffer(o, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return true;
            }
        }
    }

    private void addTrack(MediaFormat f, boolean video) {
        synchronized (muxLock) {
            int idx = muxer.addTrack(f);
            if (video) vTrack = idx;
            else aTrack = idx;
            if (vTrack >= 0 && (!hasAudio || aTrack >= 0)) {
                muxer.start();
                muxStarted = true;
                muxLock.notifyAll();
            }
        }
    }

    private void writeSample(int track, ByteBuffer buf, MediaCodec.BufferInfo info) {
        synchronized (muxLock) {
            long deadline = SystemClock.elapsedRealtime() + 5000;
            while (!muxStarted && !stopFlag && SystemClock.elapsedRealtime() < deadline) {
                try {
                    muxLock.wait(200);
                } catch (InterruptedException e) {
                    return;
                }
            }
            if (!muxStarted || track < 0) return;
            try {
                muxer.writeSampleData(track, buf, info);
                anySample = true;
            } catch (Exception e) {
                Log.w(TAG, "writeSample", e);
            }
        }
    }

    // ---------------------------------------------------------------- зупинка

    /** Блокує до завершення файлу. Повертає Uri збереженого відео або null. */
    Uri stop() {
        if (stopped) return null;
        stopped = true;

        renderer.stopEncoding();
        try {
            if (venc != null) venc.signalEndOfInputStream();
        } catch (Exception e) {
            Log.w(TAG, "signalEOS", e);
        }
        audioRun = false;
        joinQuietly(vThread);
        joinQuietly(aThread);

        boolean ok;
        synchronized (muxLock) {
            stopFlag = true;
            muxLock.notifyAll();
            ok = muxStarted && anySample;
            try {
                if (muxStarted) muxer.stop();
            } catch (Exception e) {
                Log.w(TAG, "muxer.stop", e);
                ok = false;
            }
        }
        cleanup();
        return finishMediaStore(ok);
    }

    private void abort() {
        stopped = true;
        try { renderer.stopEncoding(); } catch (Exception ignored) { }
        audioRun = false;
        synchronized (muxLock) {
            stopFlag = true;
            muxLock.notifyAll();
        }
        cleanup();
        finishMediaStore(false);
    }

    private void cleanup() {
        try { if (muxer != null) muxer.release(); } catch (Exception ignored) { }
        muxer = null;
        try {
            if (venc != null) {
                venc.stop();
                venc.release();
            }
        } catch (Exception ignored) { }
        venc = null;
        if (encSurface != null) {
            encSurface.release();
            encSurface = null;
        }
        releaseAudio();
        try { if (pfd != null) pfd.close(); } catch (IOException ignored) { }
        pfd = null;
    }

    private void releaseAudio() {
        try {
            if (ar != null) {
                if (ar.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) ar.stop();
                ar.release();
            }
        } catch (Exception ignored) { }
        ar = null;
        try {
            if (aenc != null) {
                aenc.stop();
                aenc.release();
            }
        } catch (Exception ignored) { }
        aenc = null;
    }

    private Uri finishMediaStore(boolean ok) {
        if (uri == null) return null;
        ContentResolver res = ctx.getContentResolver();
        try {
            if (ok) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Video.Media.IS_PENDING, 0);
                res.update(uri, cv, null, null);
                return uri;
            } else {
                res.delete(uri, null, null);
                return null;
            }
        } catch (Exception e) {
            Log.w(TAG, "finishMediaStore", e);
            return ok ? uri : null;
        }
    }

    private static void joinQuietly(Thread t) {
        if (t == null) return;
        try {
            t.join(4000);
        } catch (InterruptedException ignored) { }
    }
}
