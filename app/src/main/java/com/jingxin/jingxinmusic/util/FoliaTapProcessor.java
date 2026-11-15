package com.jingxin.jingxinmusic.util;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException;

import java.nio.ByteBuffer;

/**
 * FOLIA 频谱采集处理器（Media3 AudioProcessor，注入 ExoPlayer 渲染链，竖条2 专用）。
 *
 * Chromium AnalyserNode（third_party/blink/renderer/modules/webaudio/realtime_analyser.cc）
 * 的公式级移植，全部常数以该源码为唯一依据：
 * - WriteInput：降混 mono → 环形缓冲（2×FFT 大小，与 kInputBufferSize 同构）
 * - DoFFTAnalysis：取最近 fftSize 样本 → Blackman 窗（alpha=0.16：
 *   w = 0.42 − 0.5·cos(2πx) + 0.08·cos(4πx)）→ FFT
 *   → mag = |X[k]|×(1/fftSize)（源码注释 "undo FFT scaling factor"）
 *   → 线性幅度域逐 bin EMA（smoothingTimeConstant=0.6，FOLIA 原值）
 * - GetByteFrequencyData：dB = 20·log10(mag) → byte = 255×(dB−min)/(max−min)，
 *   clamp [0,255]，min/maxDecibels = -100/-30（AnalyserNode 默认，FOLIA 未覆写）
 * - 分析节奏：Chromium 在 getByteFrequencyData 时间推进时更新，FOLIA 60fps RAF 每帧读
 *   → 本类按 sampleRate/60 攒 hop，每 1/60s 分析一次
 * - gainNode 语义（FOLIA 分析器挂音量后）：线性幅度乘 player 音量（AudioProcessor
 *   在 ExoPlayer 链内拿不到 AudioTrack 层的 setVolume 衰减，用幅度乘法精确等价）
 *
 * passthrough 保证：输出格式=输入格式，数据原样拷贝转发，零音染；
 * 竖条2 未启用时只透传不分析（分析开关走 volatile，UI 线程写/播放线程读）。
 */
public final class FoliaTapProcessor implements AudioProcessor {

    private static final int FFT_SIZE = 2048;                    // kDefaultFFTSize / FOLIA fftSize
    private static final int BIN_COUNT = FFT_SIZE / 2;           // frequencyBinCount（含 DC，与 getByteFrequencyData 同构）
    private static final double MIN_DB = -100.0;                 // AnalyserNode 默认 minDecibels
    private static final double MAX_DB = -30.0;                  // AnalyserNode 默认 maxDecibels
    private static final double SMOOTHING = 0.6;                 // FOLIA smoothingTimeConstant 原值
    private static final double MAGNITUDE_SCALE = 1.0 / FFT_SIZE;

    // ==================== 对外静态接口（UI 线程写 / 播放线程读） ====================

    public interface Listener {
        /** 播放线程回调：0..255 字节谱（长度 1024，下标 0 = DC，与 getByteFrequencyData 同构） */
        void onFoliaSpectrum(float[] byteSpectrum);
    }

    private static volatile boolean sEnabled = false;
    private static volatile float sVolume = 1f;
    private static volatile Listener sListener;

    /** 竖条2 样式开关（关闭时零分析开销） */
    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    /** 同步播放音量（FOLIA 分析器挂 gainNode 之后，频谱跟随实际听到的音量） */
    public static void setVolume(float volume) { sVolume = volume < 0f ? 0f : volume; }

    public static void setListener(Listener listener) { sListener = listener; }

    // ==================== AudioProcessor 状态 ====================

    private AudioFormat inputFormat = AudioFormat.NOT_SET;
    private boolean inputEnded = false;
    private ByteBuffer buffer = EMPTY_BUFFER;

    // ==================== 分析状态（播放线程私有） ====================

    private final float[] ring = new float[FFT_SIZE * 2];        // 环形缓冲（kInputBufferSize 同构）
    private int writeIndex;
    private int hopSamples = 735;                                 // sampleRate/60（Chromium 每次读取节奏）
    private int sinceHop = Integer.MAX_VALUE >> 1;                 // 攒 hop（初值大：等满首个 hop 再分析）
    private final float[] fftRe = new float[FFT_SIZE];
    private final float[] fftIm = new float[FFT_SIZE];
    private final float[] magnitudeSmooth = new float[BIN_COUNT]; // 线性幅度域 EMA 状态
    private final float[] byteOut = new float[BIN_COUNT];         // 0..255 输出（复用，回调方自行快照）
    private final double[] blackman = new double[FFT_SIZE];      // 窗系数缓存
    private boolean windowReady;
    private int[] bitRevTable;

    public FoliaTapProcessor() {}

    @Override
    public AudioFormat configure(AudioFormat inputFormat) throws UnhandledAudioFormatException {
        if (inputFormat.encoding != C.ENCODING_PCM_16BIT
                && inputFormat.encoding != C.ENCODING_PCM_FLOAT
                && inputFormat.encoding != C.ENCODING_PCM_24BIT) {
            throw new UnhandledAudioFormatException(inputFormat);
        }
        this.inputFormat = inputFormat;
        // Chromium 节奏：60fps 读取 → 每次分析的样本间隔 = 采样率/60
        hopSamples = Math.max(64, Math.round(inputFormat.sampleRate / 60f));
        // FoliaSpectrum 几何：bin 宽 = 采样率/fftSize（44.1k 下 ≈21.5Hz，与 FOLIA 环境一致）
        com.jingxin.jingxinmusic.view.FoliaSpectrum.setAudioGeometry(
                BIN_COUNT, inputFormat.sampleRate / (float) FFT_SIZE);
        return inputFormat; // 纯旁路：输出格式 = 输入格式
    }

    @Override
    public boolean isActive() {
        // 恒激活（与 SoftwareEqProcessor 同理）：保证链成员资格，开关在分析层内部消化
        return inputFormat != AudioFormat.NOT_SET;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        int remaining = inputBuffer.remaining();
        if (remaining == 0) { buffer = EMPTY_BUFFER; return; }
        // 旁路分析（peek：只读不消耗 position）
        analyze(inputBuffer);
        // passthrough 拷贝转发（不可持有框架 buffer 引用）
        ByteBuffer out = ByteBuffer.allocateDirect(remaining);
        out.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        out.put(inputBuffer);
        out.flip();
        buffer = out;
    }

    // ==================== 旁路分析（Chromium 直译） ====================

    private void analyze(ByteBuffer inputBuffer) {
        int ch = inputFormat.channelCount;
        boolean inFloat = inputFormat.encoding == C.ENCODING_PCM_FLOAT;
        boolean in24 = inputFormat.encoding == C.ENCODING_PCM_24BIT;
        int frameBytes = ch * (in24 ? 3 : inFloat ? 4 : 2);
        int pos = inputBuffer.position();
        // WriteInput：降混 mono 入环形缓冲；攒满 hop 触发一次 DoFFTAnalysis。
        // 降混矩阵按 W3C Web Audio §4.5（speakers，AnalyserNode 默认，Chromium SumFrom 同规则）：
        //   2→1: 0.5×(L+R)   4→1: 0.25×(L+R+SL+SR)
        //   5.1→1: √0.5×(L+R) + C + 0.5×(SL+SR)，LFE 丢弃（音乐低频在主声道，LFE 为效果轨）
        //   其余布局（3/5/7/8+）：规范无 mono 矩阵，回退算术平均
        boolean matrix51 = ch == 6;
        while (inputBuffer.remaining() >= frameBytes) {
            float mono;
            if (matrix51) {
                float l = readChannel(inputBuffer, in24, inFloat);
                float r = readChannel(inputBuffer, in24, inFloat);
                float c = readChannel(inputBuffer, in24, inFloat);
                readChannel(inputBuffer, in24, inFloat); // LFE（ch3）：丢弃不计入
                float sl = readChannel(inputBuffer, in24, inFloat);
                float sr = readChannel(inputBuffer, in24, inFloat);
                mono = 0.70710678f * (l + r) + c + 0.5f * (sl + sr);
            } else if (ch <= 8) {
                float sum = 0f;
                for (int c = 0; c < ch; c++) sum += readChannel(inputBuffer, in24, inFloat);
                mono = sum / ch; // 1ch 直通 / 2ch、4ch 平均与规范逐字一致
            } else {
                // 防御：超 8 声道布局直接流式平均，不缓存
                float sum = 0f;
                for (int c = 0; c < ch; c++) sum += readChannel(inputBuffer, in24, inFloat);
                mono = sum / ch;
            }
            ring[writeIndex] = mono;
            writeIndex = (writeIndex + 1) % ring.length;
            if (++sinceHop >= hopSamples) {
                sinceHop = 0;
                doFftAnalysis();
            }
        }
        inputBuffer.position(pos); // 复位，passthrough 仍读原始数据
    }

    /** 读单声道一个样本（16bit/float/24bit 三态解码） */
    private static float readChannel(ByteBuffer in, boolean in24, boolean inFloat) {
        if (in24) return readSample24(in);
        if (inFloat) return in.getFloat();
        return in.getShort() / 32768f;
    }

    /** DoFFTAnalysis + GetByteFrequencyData 直译（播放线程，每 1/60s 一次） */
    private void doFftAnalysis() {
        // 取最近 fftSize 样本（最老→最新）并加 Blackman 窗
        if (!windowReady) {
            for (int i = 0; i < FFT_SIZE; i++) {
                double x = (double) i / FFT_SIZE;
                blackman[i] = 0.42 - 0.5 * Math.cos(2 * Math.PI * x)
                        + 0.08 * Math.cos(4 * Math.PI * x);
            }
            bitRevTable = buildBitRevTable(FFT_SIZE);
            windowReady = true;
        }
        int start = (writeIndex - FFT_SIZE + ring.length) % ring.length;
        for (int i = 0; i < FFT_SIZE; i++) {
            fftRe[i] = (float) (ring[(start + i) % ring.length] * blackman[i]);
            fftIm[i] = 0f;
        }
        fft(fftRe, fftIm, bitRevTable);

        // mag = |X[k]|×(1/fftSize)×volume（gainNode 语义）→ 线性域 EMA → 读出转 dB/byte
        float volume = sVolume;
        Listener listener = sListener;
        boolean notify = sEnabled && listener != null;
        for (int k = 0; k < BIN_COUNT; k++) {
            float mag = (float) (Math.sqrt((double) fftRe[k] * fftRe[k]
                    + (double) fftIm[k] * fftIm[k]) * MAGNITUDE_SCALE) * volume;
            // Chromium DoFFTAnalysis：k*prev + (1-k)*cur（线性幅度域）
            float smoothed = (float) (SMOOTHING * magnitudeSmooth[k] + (1 - SMOOTHING) * mag);
            if (Float.isNaN(smoothed) || Float.isInfinite(smoothed)) smoothed = 0f; // EnsureFinite
            magnitudeSmooth[k] = smoothed;
            if (notify) {
                // Chromium GetByteFrequencyData：255×(dB−min)/(max−min)，clamp [0,255]
                if (smoothed <= 0f) {
                    byteOut[k] = 0f;
                } else {
                    double db = 20.0 * Math.log10(smoothed);
                    double scaled = 255.0 * (db - MIN_DB) / (MAX_DB - MIN_DB);
                    byteOut[k] = scaled < 0f ? 0f : (scaled > 255f ? 255f : (float) scaled);
                }
            }
        }
        if (notify) listener.onFoliaSpectrum(byteOut);
    }

    /** 就地 Cooley-Tukey FFT（实序列输入：虚部置 0），与 AudioRecord 降级链同实现模式 */
    private static void fft(float[] re, float[] im, int[] bitRev) {
        int n = re.length;
        for (int i = 0; i < n; i++) {
            int j = bitRev[i];
            if (j > i) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            int half = len >> 1;
            double step = -2.0 * Math.PI / len;
            for (int i = 0; i < n; i += len) {
                for (int j = 0; j < half; j++) {
                    double angle = step * j;
                    float wr = (float) Math.cos(angle);
                    float wi = (float) Math.sin(angle);
                    int a = i + j, b = i + j + half;
                    float tr = wr * re[b] - wi * im[b];
                    float ti = wr * im[b] + wi * re[b];
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                }
            }
        }
    }

    private static int[] buildBitRevTable(int n) {
        int bits = 31 - Integer.numberOfLeadingZeros(n);
        int[] table = new int[n];
        for (int i = 0; i < n; i++) {
            int rev = 0, val = i;
            for (int b = 0; b < bits; b++) {
                rev = (rev << 1) | (val & 1);
                val >>= 1;
            }
            table[i] = rev;
        }
        return table;
    }

    /** 读一个 24bit packed 小端样本 → float [-1,1)（与 SoftwareEqProcessor 同实现） */
    private static float readSample24(ByteBuffer in) {
        int b0 = in.get() & 0xFF;
        int b1 = in.get() & 0xFF;
        int b2 = in.get();
        int v = b0 | (b1 << 8) | (b2 << 16);
        return v / 8388608f; // 2^23
    }

    // ==================== AudioProcessor 其余接口（SoftwareEqProcessor 同模式） ====================

    @Override
    public void queueEndOfStream() { inputEnded = true; }

    @Override
    public ByteBuffer getOutput() {
        ByteBuffer out = buffer;
        buffer = EMPTY_BUFFER;
        return out;
    }

    @Override
    public boolean isEnded() { return inputEnded && buffer == EMPTY_BUFFER; }

    @Override
    public void flush() {
        buffer = EMPTY_BUFFER;
        inputEnded = false;
        // Chromium Analyser：seek 后环形缓冲与平滑状态继续（它不重置）；此处置 hop 重攒
        sinceHop = Integer.MAX_VALUE >> 1;
    }

    @Override
    public void reset() {
        buffer = EMPTY_BUFFER;
        inputEnded = false;
        inputFormat = AudioFormat.NOT_SET;
    }
}
