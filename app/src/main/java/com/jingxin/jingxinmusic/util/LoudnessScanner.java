package com.jingxin.jingxinmusic.util;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 响度预扫描器（方案 C 无标签兜底）：对没有 REPLAYGAIN 标签的本地歌曲做 RMS 快速估算
 *
 * 策略：MediaExtractor 解码出 PCM 后，均匀取 4 个 2 秒窗口（开头/⅓/⅔/结尾附近），
 * 求 RMS 能量均值并换算相对增益。参考响度 -16 LUFS（流媒体标准），
 * 增益 = -16 - (窗口RMS均值 dBFS)。钳位 ±12dB 防极端值。
 *
 * 结果缓存 gain_cache.json（路径→增益），App 重启后秒读。
 * 扫描在内部单线程串行执行，不阻塞播放；每次 playSong 只排一首歌的任务。
 */
public class LoudnessScanner {

    private static final String TAG = "LoudnessScanner";

    /** 参考响度（LUFS），流媒体常用标准 */
    private static final float REFERENCE_LUFS = -16f;
    /** 增益钳位范围 */
    private static final float MAX_GAIN_DB = 12f;
    private static final float MIN_GAIN_DB = -12f;
    /** 窗口数与窗口时长 */
    private static final int WINDOW_COUNT = 4;
    private static final long WINDOW_MS = 2000;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    /** 内存缓存（gain_cache.json 全量加载 + 扫描结果写回） */
    private final Map<String, Float> gainCache = new HashMap<>();
    private final File cacheFile;
    private boolean cacheLoaded = false;

    public interface GainCallback {
        void onGainReady(float gainDb);
    }

    public LoudnessScanner(File cacheDir) {
        cacheFile = new File(cacheDir, "gain_cache.json");
    }

    /**
     * 获取歌曲增益（入口，全部异步）：
     * 1. 内存/JSON 缓存命中 → 直接回调
     * 2. REPLAYGAIN 标签 → 直接回调（写缓存）
     * 3. 都没有 → RMS 扫描后回调（写缓存）
     * 任何失败回调 0（不增益不衰减，保证播放不受影响）
     */
    public void getGainAsync(String path, GainCallback callback) {
        if (path == null || shutdown.get()) {
            notify(callback, 0f);
            return;
        }
        // 本地文件才做（网络流/B站不做音量均衡）
        File file = new File(path);
        if (!file.exists()) {
            notify(callback, 0f);
            return;
        }
        submit(() -> {
            // 1. 缓存
            loadCacheIfNeeded();
            Float cached = gainCache.get(path);
            if (cached != null) {
                notify(callback, cached);
                return;
            }
            // 2. REPLAYGAIN 标签
            float tagGain = GainTagReader.readTrackGain(file);
            if (!Float.isNaN(tagGain)) {
                putCache(path, tagGain);
                notify(callback, tagGain);
                return;
            }
            // 3. RMS 扫描
            float scanned = scanRmsGain(file);
            if (Float.isNaN(scanned)) scanned = 0f;
            putCache(path, scanned);
            notify(callback, scanned);
        });
    }

    /** Service onDestroy 时调用，拒绝新任务 */
    public void shutdown() {
        shutdown.set(true);
        executor.shutdown();
    }

    // ==================== 内部（executor 线程） ====================

    private void submit(Runnable task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void notify(GainCallback cb, float gain) {
        if (cb != null) cb.onGainReady(gain);
    }

    private void loadCacheIfNeeded() {
        if (cacheLoaded) return;
        cacheLoaded = true;
        try {
            String content = FileUtil.readFile(cacheFile);
            if (content != null && !content.isEmpty()) {
                JSONObject obj = new JSONObject(content);
                java.util.Iterator<String> keys = obj.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    gainCache.put(key, (float) obj.getDouble(key));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "增益缓存读取失败（忽略，重建）: " + e.getMessage());
        }
    }

    private void putCache(String path, float gain) {
        synchronized (gainCache) {
            gainCache.put(path, gain);
            // 全量写回（歌曲数最多几千，JSON 体积小，写盘可接受）
            try {
                JSONObject obj = new JSONObject();
                for (Map.Entry<String, Float> e : gainCache.entrySet()) {
                    obj.put(e.getKey(), (double) e.getValue());
                }
                FileUtil.writeFile(cacheFile, obj.toString());
            } catch (Exception e) {
                Log.w(TAG, "增益缓存写入失败（忽略）: " + e.getMessage());
            }
        }
    }

    /**
     * RMS 扫描：MediaExtractor 解码 PCM，取 4 个 2 秒窗口能量均值换算增益。
     * 失败返回 NaN（调用方置 0）。
     */
    private float scanRmsGain(File file) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            int audioTrack = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrack = i;
                    format = f;
                    break;
                }
            }
            if (audioTrack < 0 || format == null) return Float.NaN;

            long durationMs = format.containsKey(MediaFormat.KEY_DURATION)
                    ? format.getLong(MediaFormat.KEY_DURATION) / 1000 : 0;
            if (durationMs < WINDOW_MS) return Float.NaN; // 太短不扫

            extractor.selectTrack(audioTrack);

            // 4 个窗口中心位置均匀分布（避开开头/结尾 5% 的淡入淡出区）
            long usable = durationMs - durationMs / 10;
            long firstCenter = durationMs / 20 + WINDOW_MS / 2;
            double sumSquares = 0;
            long totalSamples = 0;
            int windowsDone = 0;

            MediaCodecHelper decoder = null;
            for (int w = 0; w < WINDOW_COUNT; w++) {
                long center = firstCenter + (long) ((double) usable * w / WINDOW_COUNT);
                long start = Math.max(0, center - WINDOW_MS / 2);
                if (start + WINDOW_MS > durationMs) start = Math.max(0, durationMs - WINDOW_MS - durationMs / 20);
                extractor.seekTo(start * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

                double windowSquares = 0;
                long windowSamples = 0;
                ByteBuffer buf = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN);
                try {
                    decoder = new MediaCodecHelper(extractor, format);
                    long windowEndUs = (start + WINDOW_MS) * 1000;
                    long timeoutUs = 3000;
                    long nowUs = start * 1000;
                    while (nowUs < windowEndUs) {
                        int outState = decoder.dequeueOutput(buf, timeoutUs);
                        if (outState == MediaCodecHelper.OUTPUT_FORMAT_CHANGED
                                || outState == MediaCodecHelper.OUTPUT_TRY_AGAIN) {
                            continue;
                        }
                        if (outState == MediaCodecHelper.OUTPUT_EOS) break;
                        if (outState == MediaCodecHelper.OUTPUT_OK) {
                            buf.rewind();
                            int shorts = buf.remaining() / 2;
                            for (int i = 0; i < shorts; i++) {
                                float s = buf.getShort() / 32768f;
                                windowSquares += s * s;
                            }
                            windowSamples += shorts;
                            nowUs = decoder.getLastPresentationUs();
                        }
                    }
                } finally {
                    if (decoder != null) {
                        decoder.release();
                        decoder = null;
                    }
                }
                if (windowSamples > 0) {
                    sumSquares += windowSquares;
                    totalSamples += windowSamples;
                    windowsDone++;
                }
            }

            if (windowsDone == 0 || totalSamples == 0) return Float.NaN;
            double rms = Math.sqrt(sumSquares / totalSamples);
            if (rms <= 0) return Float.NaN;
            double rmsDb = 20 * Math.log10(rms);
            // RMS 相对满幅的 dBFS 加偏移近似 LUFS（纯 RMS 无加权，误差可接受，
            // 目的只是拉平歌与歌之间的相对音量，不追求绝对响度精确）
            float gain = (float) (REFERENCE_LUFS - rmsDb - 3f); // -3 偏移：RMS 通常高于综合响度
            return Math.max(MIN_GAIN_DB, Math.min(MAX_GAIN_DB, gain));
        } catch (Throwable t) {
            Log.w(TAG, "RMS扫描失败（置0）: " + t.getMessage());
            return Float.NaN;
        } finally {
            try {
                extractor.release();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * MediaCodec 同步解码薄封装（输入喂 sample、输出收 PCM），
     * 只为 RMS 扫描服务，异常全部上抛由 scanRmsGain 兜底。
     */
    private static class MediaCodecHelper {
        static final int OUTPUT_OK = 1;
        static final int OUTPUT_EOS = 2;
        static final int OUTPUT_FORMAT_CHANGED = 3;
        static final int OUTPUT_TRY_AGAIN = 0;

        private final android.media.MediaCodec codec;
        private final MediaExtractor extractor;
        private long lastPresentationUs = 0;
        private boolean inputEos = false;

        MediaCodecHelper(MediaExtractor extractor, MediaFormat format) throws Exception {
            String mime = format.getString(MediaFormat.KEY_MIME);
            this.extractor = extractor;
            codec = android.media.MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();
        }

        long getLastPresentationUs() {
            return lastPresentationUs;
        }

        /**
         * 推一轮数据：喂一个 sample（input），尝试取一个输出 PCM 到 out。
         * 返回输出状态。
         */
        int dequeueOutput(ByteBuffer out, long timeoutUs) throws Exception {
            // 输入侧：未到 EOS 且能拿到 input buffer 就喂
            if (!inputEos) {
                int inIdx = codec.dequeueInputBuffer(timeoutUs);
                if (inIdx >= 0) {
                    ByteBuffer in = codec.getInputBuffer(inIdx);
                    int size = extractor.readSampleData(in, 0);
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0,
                                android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputEos = true;
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }
            // 输出侧
            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
            int outIdx = codec.dequeueOutputBuffer(info, timeoutUs);
            if (outIdx >= 0) {
                ByteBuffer src = codec.getOutputBuffer(outIdx);
                int old = out.position();
                int len = Math.min(info.size, out.remaining());
                src.position(info.offset);
                src.limit(info.offset + len);
                out.put(src);
                out.position(old);
                lastPresentationUs = info.presentationTimeUs;
                codec.releaseOutputBuffer(outIdx, false);
                return OUTPUT_OK;
            }
            if (outIdx == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                return OUTPUT_FORMAT_CHANGED;
            }
            if (outIdx == android.media.MediaCodec.INFO_TRY_AGAIN_LATER) {
                return OUTPUT_TRY_AGAIN;
            }
            // INFO_OUTPUT_BUFFERS_CHANGED 已废弃，同 TRY_AGAIN 处理
            return OUTPUT_TRY_AGAIN;
        }

        void release() {
            try {
                codec.stop();
            } catch (Exception ignored) {
            }
            try {
                codec.release();
            } catch (Exception ignored) {
            }
        }
    }
}
