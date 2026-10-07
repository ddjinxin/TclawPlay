package com.jingxin.jingxinmusic.util;

import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException;
import androidx.media3.common.C;

import java.nio.ByteBuffer;

/**
 * 软件 EQ 音频处理器（Media3 AudioProcessor，注入 ExoPlayer 渲染链）。
 *
 * 背景：车机厂商 EQ 效果器（NXP 等）在多声道输出上处理时静音，参数落地但无声。
 * 方案：解码后 PCM 上自己做 EQ，输出通路与"关闭音效"完全一致，所有设备表现统一。
 *
 * DSP 结构（每声道独立级联，浮点直接 II 转置型）：
 * - 5 个 peaking biquad（60/230/1000/3500/14000Hz，Q=1.0）
 * - 低音增强：60Hz lowShelf +11dB（等效系统 BassBoost 80% 听感）
 * - 全景环绕：M/S 制式立体声扩展（侧向分量 1.8 倍），单声道时自动跳过
 *
 * 直通保证：preset=关闭 / 未配置 / 非立体声 PCM 时 isActive()=false，零开销零音染。
 */
public final class SoftwareEqProcessor implements AudioProcessor {

    // ==================== 预设参数（与 AudioEffectManager 预设索引一致） ====================
    /** EQ 预设曲线（dB），列对应 EQ_FREQS。0=不启用该段。 */
    private static final float[][] EQ_CURVES_DB = {
            {0, 0, 0, 0, 0},          // 关闭（占位，走直通）
            {0, 3, 7.5f, 6, 0},       // 人声
            {6, 3, 0, 3, 4.5f},       // 流行
            {9, 6, -3, 4.5f, 9},      // 摇滚
            {3, 0, 0, 0, 3},          // 古典
            {0, 0, 0, 0, 0},          // 低音增强（走 shelf）
            {0, 0, 0, 0, 0},          // 全景环绕（走 M/S）
    };
    private static final float[] EQ_FREQS = {60f, 230f, 1000f, 3500f, 14000f};
    private static final float EQ_Q = 1.0f;
    private static final float BASS_SHELF_GAIN_DB = 11f;   // 低音增强
    private static final float SURROUND_SIDE_GAIN = 1.8f; // 全景环绕侧向增益

    // ==================== AudioProcessor 状态 ====================
    private AudioFormat inputFormat = AudioFormat.NOT_SET;
    private AudioFormat outputFormat = AudioFormat.NOT_SET;
    private boolean inputEnded = false;
    private ByteBuffer buffer = EMPTY_BUFFER;

    // ==================== DSP 状态 ====================
    private float[] b0, b1, b2, a1, a2;        // biquad 系数（每段）
    private float[][] z1, z2;                  // 每声道每段延迟状态 [ch][band]
    private float[][] shelfCoef, shelfZ;      // 低音 shelf 系数/状态
    private volatile int preset = 0;          // 当前预设（volatile：UI 线程写，播放线程读）
    private int appliedPreset = -1;          // 已重算系数的预设（-1 强制首次重算）
    private int channels = 0, sampleRate = 0;

    public SoftwareEqProcessor() {}

    /** 设置预设（任意线程，volatile 写入；参数在 flush/首次处理时生效） */
    public void setPreset(int preset) {
        this.preset = preset;
    }

    @Override
    public AudioFormat configure(AudioFormat inputFormat) throws UnhandledAudioFormatException {
        if (inputFormat.encoding != C.ENCODING_PCM_16BIT
                && inputFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw new UnhandledAudioFormatException(inputFormat);
        }
        this.inputFormat = inputFormat;
        this.outputFormat = inputFormat; // 直通格式，只改幅度不改结构
        return outputFormat;
    }

    /**
     * 恒激活：Media3 在流 configure 时用 isActive() 决定链成员资格，
     * 若依赖 preset 则「播放开始后才切预设」会永远不在链上（queueInput 不被调用）。
     * 直通判断由 queueInput 内部负责（preset<=0 时零拷贝透传）。
     */
    @Override
    public boolean isActive() {
        return inputFormat != AudioFormat.NOT_SET;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        int remaining = inputBuffer.remaining();
        if (remaining == 0) { buffer = EMPTY_BUFFER; return; }
        int ch = inputFormat.channelCount;
        boolean isFloat = inputFormat.encoding == C.ENCODING_PCM_FLOAT;
        int frameBytes = ch * (isFloat ? 4 : 2);
        int frames = remaining / frameBytes;
        if (frames == 0) { buffer = EMPTY_BUFFER; return; }
        int need = frames * frameBytes;
        // Media3 PCM 为小端（native byte order），allocateDirect 默认大端，
        // 必须显式设为 LITTLE_ENDIAN，否则 getShort/getFloat/putShort 字节序错乱 → 杂音
        ByteBuffer out = ByteBuffer.allocateDirect(need);
        out.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int oldLimit = inputBuffer.limit();
        inputBuffer.limit(inputBuffer.position() + need);
        if (preset <= 0 || ch > 2) {
            // 直通 copy（必须拷贝，不可持有框架 buffer 引用——框架会复用该 buffer）
            out.put(inputBuffer);
        } else {
            // 懒初始化 DSP（首次处理 / preset 或采样率或声道变化时重算系数）
            int sr = inputFormat.sampleRate;
            if (preset != appliedPreset || sr != sampleRate || ch != channels) {
                sampleRate = sr;
                channels = ch;
                appliedPreset = preset;
                computeCoefficients();
            }
            processPcm(inputBuffer, out);
        }
        inputBuffer.limit(oldLimit);
        out.flip();
        buffer = out;
    }

    private ByteBuffer allocateEmpty(int capacity) {
        ByteBuffer b = ByteBuffer.allocateDirect(capacity);
        b.flip();
        return b;
    }

    /** 核心：逐帧逐声道滤波。encoding 由 inputFormat 决定，帧内样本交错。 */
    private void processPcm(ByteBuffer in, ByteBuffer out) {
        boolean isFloat = inputFormat.encoding == C.ENCODING_PCM_FLOAT;
        boolean mono = channels == 1;
        int frameBytes = channels * (isFloat ? 4 : 2);
        while (in.remaining() >= frameBytes) {
            // 读一帧
            float l, r;
            if (isFloat) {
                l = in.getFloat();
                r = mono ? l : in.getFloat();
            } else {
                l = in.getShort() / 32768f;
                r = mono ? l : in.getShort() / 32768f;
            }
            // M/S 环绕
            if (preset == 6 && !mono) {
                float m = (l + r) * 0.5f;
                float s = (l - r) * 0.5f * SURROUND_SIDE_GAIN;
                l = m + s;
                r = m - s;
            }
            // EQ 滤波
            if (preset >= 1 && preset <= 4) {
                l = runCascade(l, 0);
                if (!mono) r = runCascade(r, 1);
            } else if (preset == 5) { // 低音增强
                l = runShelf(l, 0);
                if (!mono) r = runShelf(r, 1);
            }
            // 防溢出 clamp
            l = clamp(l); r = clamp(r);
            // 写一帧
            if (isFloat) {
                out.putFloat(l);
                if (!mono) out.putFloat(r);
            } else {
                out.putShort((short) (int) (l * 32767f));
                if (!mono) out.putShort((short) (int) (r * 32767f));
            }
        }
    }

    /** 级联 5 段 biquad（Direct Form II Transposed），返回输出样本。 */
    private float runCascade(float x, int ch) {
        float[] zz1 = z1[ch], zz2 = z2[ch];
        for (int i = 0; i < 5; i++) {
            float y = b0[i] * x + zz1[i];
            zz1[i] = b1[i] * x - a1[i] * y + zz2[i];
            zz2[i] = b2[i] * x - a2[i] * y;
            x = y;
        }
        return x;
    }

    /** 单个 low shelf（低音增强）。 */
    private float runShelf(float x, int ch) {
        float y = shelfCoef[0][0] * x + shelfZ[ch][0];
        shelfZ[ch][0] = shelfCoef[0][1] * x - shelfCoef[1][0] * y + shelfZ[ch][1];
        shelfZ[ch][1] = shelfCoef[0][2] * x - shelfCoef[1][1] * y;
        return y;
    }

    private static float clamp(float v) {
        return v > 1f ? 1f : (v < -1f ? -1f : v);
    }

    /** 预设/采样率变化时重算全部系数（播放线程 configure 后调用）。 */
    private void computeCoefficients() {
        if (sampleRate <= 0 || preset <= 0) return;
        if (b0 == null) {
            b0 = new float[5]; b1 = new float[5]; b2 = new float[5];
            a1 = new float[5]; a2 = new float[5];
            z1 = new float[2][5]; z2 = new float[2][5];
            shelfCoef = new float[2][3]; shelfZ = new float[2][2];
        }
        // 预设/格式变化时清零所有滤波器状态（避免旧能量残留导致输出跳变/杂音）
        if (z1 != null) {
            for (float[] row : z1) java.util.Arrays.fill(row, 0f);
            for (float[] row : z2) java.util.Arrays.fill(row, 0f);
            for (float[] row : shelfZ) java.util.Arrays.fill(row, 0f);
        }
        if (preset >= 1 && preset <= 4) {
            float[] gains = EQ_CURVES_DB[preset];
            for (int i = 0; i < 5; i++) {
                designPeaking(gains[i], EQ_FREQS[i], i);
            }
        } else if (preset == 5) {
            designLowShelf(BASS_SHELF_GAIN_DB, 60f);
        } else if (preset == 6) {
            // 环绕无 IIR，M/S 在 processPcm 内联处理
        }
    }

    /** RBJ peaking EQ 系数设计。gain=0 时该段为数字直通（b0=1,b1=b2=a1=a2=0）。 */
    private void designPeaking(float gainDb, float freq, int idx) {
        float A = (float) Math.pow(10, gainDb / 40f); // 平方根增益
        float w0 = (float) (2 * Math.PI * freq / sampleRate);
        float alpha = (float) (Math.sin(w0) / (2 * EQ_Q));
        float cw = (float) Math.cos(w0);
        float a0 = 1 + alpha / A;
        b0[idx] = (1 + alpha * A) / a0;
        b1[idx] = (-2 * cw) / a0;
        b2[idx] = (1 - alpha * A) / a0;
        a1[idx] = (-2 * cw) / a0;
        a2[idx] = (1 - alpha / A) / a0;
    }

    /** RBJ low shelf 系数设计（0.9 倍中心频率过渡）。 */
    private void designLowShelf(float gainDb, float freq) {
        float A = (float) Math.pow(10, gainDb / 40f);
        float w0 = (float) (2 * Math.PI * freq * 0.9 / sampleRate);
        float sqA = (float) Math.sqrt(A);
        float cw = (float) Math.cos(w0);
        float sw = (float) Math.sin(w0);
        float t = 2 * sqA * 1.0f; // S=1（标准 shelf 斜率）
        float beta = sw * t;
        float a0 = (A + 1) + (A - 1) * cw + beta;
        shelfCoef[0][0] = A * ((A + 1) - (A - 1) * cw + beta) / a0;
        shelfCoef[0][1] = 2 * A * ((A - 1) - (A + 1) * cw) / a0;
        shelfCoef[0][2] = A * ((A + 1) - (A - 1) * cw - beta) / a0;
        shelfCoef[1][0] = -2 * ((A - 1) + (A + 1) * cw) / a0;
        shelfCoef[1][1] = ((A + 1) + (A - 1) * cw - beta) / a0;
    }

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
        // DSP 状态清零（seek/discontinuity 后避免残留能量冲击）
        if (z1 != null) {
            for (float[] row : z1) java.util.Arrays.fill(row, 0f);
            for (float[] row : z2) java.util.Arrays.fill(row, 0f);
            for (float[] row : shelfZ) java.util.Arrays.fill(row, 0f);
        }
    }

    @Override
    public void reset() {
        buffer = EMPTY_BUFFER;
        inputEnded = false;
        inputFormat = AudioFormat.NOT_SET;
        outputFormat = AudioFormat.NOT_SET;
    }
}
