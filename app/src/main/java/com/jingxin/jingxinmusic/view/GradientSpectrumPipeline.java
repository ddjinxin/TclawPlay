package com.jingxin.jingxinmusic.view;

/**
 * 渐变柱状频谱数据管线（独立工作）。
 *
 * 处理链（均为教科书级通用 DSP，无第三方代码来源）：
 *   相邻 bin 取峰合并（降分辨率） → 峰值比例增益 → 线性放大+限幅 →
 *   对称镜像(低频居中) → 高斯卷积平滑 → 线性插值。
 *
 * 数值参数全部为本工程自选（见下方常量），与具体柱状结构解耦，
 * 供 SpectrumView 的渐变柱状样式消费（绘制端只读 {@link #display()}）。
 *
 * 缓冲管理：所有内部数组严格按当前渲染柱宽对齐（柱宽变化时统一重建），
 * 杜绝视图变宽/旋转/页切换时的越界。
 */
public final class GradientSpectrumPipeline {

    // ==================== 参数（本工程自选） ====================
    /** 数据消费闸门：两次喂帧最小间隔（低于此忽略新帧） */
    private static final long GATE_MS = 50L;
    /** 峰值增益系数：弱值按峰值比例抬升的增益上限 */
    private static final float GAIN = 4.5f;
    /** 线性放大系数 */
    private static final float SCALE = 4.5f;
    /** 参与峰值增益的阈值比例（低于峰值×此值不增益） */
    private static final float PEAK_RATIO = 0.25f;
    /** 高斯平滑标准差 */
    private static final float SMOOTH_SIGMA = 1.6f;
    /** 单段线性插值时长 */
    private static final long ANIM_MS = 50L;

    // ---------------- 运行时缓冲（按当前柱宽对齐） ----------------
    private float[] mergedBin;     // 合并后的低频 bin（n）
    private float[] targetPeak;    // 增益+镜像+平滑后的目标（2n）
    private float[] animPrev;      // 本段插值起点（2n）
    private float[] displayVal;    // 当前显示值（2n，插值输出）
    private long lastFeedAt = -1L;
    private long animStartAt;

    // ==================== 对外接口 ====================

    /**
     * 喂入一帧原始频谱（幂等：命中闸门时忽略），并重置线性插值动画。
     *
     * @param magnitudes 原始 DFT 幅度
     * @param binCount   期望的渲染 bin 数（管线内部镜像成 2 倍柱）
     * @param capHeight  柱高上限（即条带高度像素）
     */
    public void feed(float[] magnitudes, int binCount, float capHeight) {
        long now = System.currentTimeMillis();
        if (now - lastFeedAt < GATE_MS) return;
        lastFeedAt = now;

        int n = Math.min(binCount, magnitudes.length / 2);
        if (n < 1) n = 1;
        int size = n * 2;
        ensureCapacity(n, size);

        // 1) 相邻 bin 取峰合并（降低频分辨率）
        for (int i = 0; i < n; i++) {
            float a = magnitudes[i * 2];
            float b = (i * 2 + 1 < magnitudes.length) ? magnitudes[i * 2 + 1] : a;
            mergedBin[i] = Math.max(a, b);
        }
        // 2) 峰值
        float max = 0f;
        for (int i = 0; i < n; i++) if (mergedBin[i] > max) max = mergedBin[i];

        // 3) 峰值比例增益 + 线性放大 + 限幅
        float threshold = PEAK_RATIO * max;
        for (int i = 0; i < n; i++) {
            float v = mergedBin[i];
            if (v != 0f && v >= threshold) v = (max / v) * GAIN + v;
            v *= SCALE;
            if (v > capHeight) v = capHeight;
            mergedBin[i] = v;
        }
        // 4) 对称镜像：左半倒序、右半正序，低频居中
        for (int i = 0; i < n; i++) {
            targetPeak[n - 1 - i] = mergedBin[i];
            targetPeak[n + i] = mergedBin[i];
        }
        // 5) 高斯平滑（就地）
        gaussianSmooth(targetPeak, size, SMOOTH_SIGMA);

        // 6) 过渡：以当前显示值为起点，向新目标线性插值（长度变化则首帧直显）
        if (animPrev == null || displayVal == null) {
            animPrev = targetPeak.clone();
        } else {
            System.arraycopy(displayVal, 0, animPrev, 0, size);
        }
        animStartAt = now;
    }

    /** 推进插值动画一帧；返回是否仍在过渡（供调用方决定重绘频率） */
    public boolean advance() {
        if (displayVal == null || animPrev == null) return false;
        float t = (System.currentTimeMillis() - animStartAt) / (float) ANIM_MS;
        if (t > 1f) t = 1f;
        else if (t < 0f) t = 0f;
        for (int i = 0; i < displayVal.length; i++) {
            displayVal[i] = animPrev[i] + (targetPeak[i] - animPrev[i]) * t;
        }
        return t < 1f;
    }

    /** 当前显示值数组（长度=渲染柱数） */
    public float[] display() {
        return displayVal;
    }

    // ==================== 内部 ====================

    private void ensureCapacity(int n, int size) {
        if (mergedBin == null || mergedBin.length != n) mergedBin = new float[n];
        if (targetPeak == null || targetPeak.length != size) {
            targetPeak = new float[size];
            displayVal = new float[size];
            animPrev = null;   // 尺寸变化：无动画起点，首帧直显
        }
        if (displayVal == null || displayVal.length != size) displayVal = new float[size];
    }

    /** 高斯卷积平滑（一维）：全核归一 + 边缘越界不补权重（两缘自然衰减） */
    private static void gaussianSmooth(float[] data, int len, float sigma) {
        if (len < 3) return;
        int kernelSize = (int) (6 * sigma);
        if (kernelSize % 2 == 0) kernelSize++;
        float[] kernel = new float[kernelSize];
        float sum = 0f;
        int half = kernelSize / 2;
        for (int i = 0; i < kernelSize; i++) {
            float x = i - half;
            kernel[i] = (float) Math.exp(-(x * x) / (2 * sigma * sigma));
            sum += kernel[i];
        }
        float[] src = new float[len];
        System.arraycopy(data, 0, src, 0, len);
        for (int i = 0; i < len; i++) {
            float acc = 0f, w = 0f;
            for (int k = 0; k < kernelSize; k++) {
                int j = i + k - half;
                if (j < 0 || j >= len) continue;   // 边缘越界不补权重
                acc += src[j] * kernel[k];
                w += kernel[k];
            }
            data[i] = w > 0f ? acc / w : data[i];
        }
    }
}