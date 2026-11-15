package com.jingxin.jingxinmusic.view;

import android.os.SystemClock;

/**
 * FOLIA 频谱核心（chthollyphile/folia-major · Monet AudioOverlay 完整移植）
 *
 * 数据入口：FoliaTapProcessor（渲染链 PCM 旁路）产出的 0..255 字节谱——
 * 与 Web Audio getByteFrequencyData 完全同构（含 DC 下标 0、长度 = fftSize/2），
 * dB 窗口/EMA/降混/Blackman/音量乘法全部在 tap 内按 Chromium realtime_analyser.cc 直译。
 *
 * 本类只做 FOLIA usePlaybackVisualizerBridge + Monet 采样层的直译：
 * - 五频带区间均值 + gamma：bass 20-150Hz γ1.8 / lowMid 150-400 γ2 /
 *   mid 400-1200 γ2 / vocal 1000-3500 γ1.5 / treble 3500-12000 γ2
 *   audioPower = (bass原始+lowMid原始)/2 再 γ3（索引 = floor(hz/21.5) 动态 binHz 化）
 * - raw 真谱采样（sampleRawSpectrumProfile 直译）：MIN_BIN=6（≈129Hz）下限、
 *   expm1 对数轴、动态三角窗（中央半径2两端5、权重保底0.1）、频率相关噪声门
 *   16+(1-x)·12、可变gamma 1.8+0.4(1-x)、低频补偿 0.52+0.62x、presence 0.92+√x·0.14
 * - anchor 假谱兜底（无数据/空闲）：5频带→11锚点→log10(1+9x)插值→contour 正弦调制
 * - 空闲呼吸（FOLIA 原样）：bands = (sin(now/2000)+1)×20 持续动画
 */
public final class FoliaSpectrum {

    /** Monet AudioOverlay.BAR_COUNT 原值 */
    public static final int BAR_COUNT = 72;
    /** FOLIA MIN_RAW_SPECTRUM_BIN=6 的频率语义（6×21.5Hz） */
    private static final float RAW_SKIP_HZ = 129f;

    // ===== 采集几何（FoliaTapProcessor.configure 时设置；bin 宽 = 采样率/fftSize） =====
    private static volatile int sBinCount = 1024;      // frequencyBinCount（含 DC）
    private static volatile float sBinHz = 21.53f;     // 44100/2048

    public static void setAudioGeometry(int binCount, float binHz) {
        if (binCount > 0 && binHz > 0f) {
            sBinCount = binCount;
            sBinHz = binHz;
        }
    }

    // ===== 实例状态（主线程：tap 回调经 PlayerFragment post 后在此消费） =====
    private float[] byteSpectrum;   // 平滑后字节谱 0..255（tap 快照引用；下标 0 = DC）
    private int specLen;            // 有效谱长
    private int minBin;             // FOLIA MIN_RAW_SPECTRUM_BIN 的动态 bin 位（≈129Hz）
    private boolean hasSpectrum;    // 是否有有效数据（空闲呼吸 = false）

    private final float[] bands = new float[5];  // gamma 后频带 0..255（bass/lowMid/mid/vocal/treble）
    private float power;                          // 0..255
    private float rawBassMean, rawLowMidMean;    // gamma 前原始均值（power 用）

    public FoliaSpectrum() {
        applyGeometry();
    }

    /** 几何变化后重算派生量；不清平滑谱（tap 的 EMA 保证连续性） */
    private void applyGeometry() {
        minBin = Math.max(0, Math.round(RAW_SKIP_HZ / sBinHz));
    }

    /** 空闲切换（setPlaying(false) → 呼吸态；收到数据自动回真谱态） */
    public void setSpectrumActive(boolean active) {
        if (!active) hasSpectrum = false;
    }

    // ==================== 数据帧：字节谱 → 五频带（FOLIA updateLoop 直译） ====================

    /**
     * 每分析帧调用。byteSpectrum 与 getByteFrequencyData 同构（0..255，含 DC 下标 0），
     * 调用方持有快照（PlayerFragment 四缓冲轮换），本类直接存引用不拷贝。
     */
    public void updateByteSpectrum(float[] byteSpectrum) {
        if (byteSpectrum == null || byteSpectrum.length == 0) return;
        this.byteSpectrum = byteSpectrum;
        if (specLen != byteSpectrum.length) {
            specLen = byteSpectrum.length;
            applyGeometry();
        }
        hasSpectrum = true;
        rawBassMean = energyBytes(20f, 150f);
        rawLowMidMean = energyBytes(150f, 400f);
        bands[0] = process(rawBassMean, 1.8f);
        bands[1] = process(rawLowMidMean, 2f);
        bands[2] = process(energyBytes(400f, 1200f), 2f);
        bands[3] = process(energyBytes(1000f, 3500f), 1.5f);
        bands[4] = process(energyBytes(3500f, 12000f), 2f);
        power = process((rawBassMean + rawLowMidMean) / 2f, 3f);
    }

    /** FOLIA process：归一 + gamma 提亮，返回 0..255 */
    private static float process(float value, float boost) {
        float normalized = value / 255f;
        if (normalized < 0f) normalized = 0f;
        return (float) Math.pow(normalized, boost) * 255f;
    }

    /** FOLIA getEnergy：[floor(min/21.5), floor(max/21.5)] 字节均值（含 DC 同构，无平移） */
    private float energyBytes(float minHz, float maxHz) {
        if (byteSpectrum == null) return 0f;
        float binHz = sBinHz;
        int start = (int) (minHz / binHz);
        int end = (int) (maxHz / binHz);
        if (start < 0) start = 0;
        if (end >= specLen) end = specLen - 1;
        if (end < start) return 0f;
        float sum = 0f;
        for (int i = start; i <= end; i++) sum += byteSpectrum[i];
        return sum / (end - start + 1);
    }

    // ==================== 柱采样：raw 真谱 / anchor 假谱 ====================

    /**
     * 归一化谱位 x∈[0,1] → 柱高值 0..1。
     * 有数据走 FOLIA sampleRawSpectrumProfile；无数据（含空闲呼吸）走 sampleSpectrumProfile。
     */
    public float sampleBar(float x) {
        if (x < 0f) x = 0f;
        else if (x > 1f) x = 1f;
        if (hasSpectrum) return sampleRaw(x);
        return sampleAnchor(x, breathBands());
    }

    /** 综合能量（绘制端 energy×0.04 项）；FOLIA：min(1, max(0.08, power/255))，空闲用呼吸值 */
    public float getEnergy() {
        float e = hasSpectrum ? power : breathValue();
        return Math.min(1f, Math.max(0.08f, e / 255f));
    }

    /** FOLIA sampleRawSpectrumProfile 直译（谱含 DC，索引原位） */
    private float sampleRaw(float x) {
        if (byteSpectrum == null || specLen <= minBin) return 0f;
        int usable = Math.max(1, specLen - minBin);
        float mapped = (float) Math.expm1(x * Math.log(usable + 1.0));
        float center = minBin + mapped;
        float edgeSoftness = 1f - Math.abs(x - 0.5f) * 2f;
        int windowRadius = Math.max(1, Math.round(5f - edgeSoftness * 3f));
        int start = Math.max(0, (int) Math.floor(center - windowRadius));
        int end = Math.min(specLen - 1, (int) Math.ceil(center + windowRadius));

        float weightedSum = 0f, weightTotal = 0f;
        for (int i = start; i <= end; i++) {
            float distance = Math.abs(i - center);
            float weight = Math.max(0.1f, 1f - distance / (windowRadius + 1f));
            weightedSum += byteSpectrum[i] * weight;
            weightTotal += weight;
        }
        float averaged = weightTotal > 0f ? weightedSum / weightTotal : 0f;

        // 频率相关噪声门（低频扣 28、高频扣 16）
        float noiseFloor = 16f + (1f - x) * 12f;
        float normalized = Math.max(0f, Math.min(1f, (averaged - noiseFloor) / (255f - noiseFloor)));

        // 可变 gamma 压底噪 → 低频补偿 → presence 抬升
        float powerGamma = 1.8f + (1f - x) * 0.4f;
        float processed = (float) Math.pow(normalized, powerGamma);
        float lowFrequencyCompensation = 0.52f + x * 0.62f;
        float presenceLift = 0.92f + (float) Math.sqrt(x) * 0.14f;
        return Math.max(0f, Math.min(1f, processed * lowFrequencyCompensation * presenceLift));
    }

    /** FOLIA buildSpectrumAnchors + sampleSpectrumProfile 直译（锚点复用缓冲，onDraw 零分配） */
    private final float[] anchorBuf = new float[11];

    private float sampleAnchor(float x, float[] bands01) {
        float bass = bands01[0], lowMid = bands01[1], mid = bands01[2], vocal = bands01[3], treble = bands01[4];
        float[] anchors = anchorBuf;
        anchors[0] = bass * 1.08f;
        anchors[1] = bass * 0.96f;
        anchors[2] = bass * 0.74f + lowMid * 0.26f;
        anchors[3] = lowMid;
        anchors[4] = lowMid * 0.58f + mid * 0.42f;
        anchors[5] = mid;
        anchors[6] = mid * 0.42f + vocal * 0.58f;
        anchors[7] = vocal;
        anchors[8] = vocal * 0.48f + treble * 0.52f;
        anchors[9] = treble;
        anchors[10] = treble * 0.82f;
        for (int i = 0; i < anchors.length; i++) {
            if (anchors[i] < 0f) anchors[i] = 0f;
            else if (anchors[i] > 1f) anchors[i] = 1f;
        }
        float logIndex = (float) Math.log10(1f + x * 9f);
        float scaledIndex = logIndex * (anchors.length - 1);
        int lowerIndex = (int) Math.floor(scaledIndex);
        int upperIndex = Math.min(lowerIndex + 1, anchors.length - 1);
        float interpolation = scaledIndex - lowerIndex;
        float lowerValue = anchors[lowerIndex];
        float upperValue = anchors[upperIndex];
        float interpolated = lowerValue + (upperValue - lowerValue) * interpolation;
        // contour：连续响应曲线调制
        float contour =
                0.92f
                + (float) Math.sin(x * Math.PI * 3.4 - Math.PI * 0.3) * 0.08f
                + (float) Math.sin(x * Math.PI * 9.2 + Math.PI * 0.2) * 0.035f;
        return Math.max(0f, Math.min(1f, interpolated * contour));
    }

    // ==================== 空闲呼吸（FOLIA 原样：持续正弦起伏） ====================

    private static float breathValue() {
        double time = SystemClock.uptimeMillis() / 2000.0;
        return (float) ((Math.sin(time) + 1.0) * 20.0);   // 0..40（0..255 域）
    }

    private final float[] breathBuf = new float[5];

    /** 呼吸 bands（0..1 域，同值填充，FOLIA 五频带同写一个 breath） */
    private float[] breathBands() {
        float v = breathValue() / 255f;
        for (int i = 0; i < 5; i++) breathBuf[i] = v;
        return breathBuf;
    }
}
