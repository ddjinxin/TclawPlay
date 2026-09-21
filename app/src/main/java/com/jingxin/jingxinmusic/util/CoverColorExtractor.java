package com.jingxin.jingxinmusic.util;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 封面双色提取器
 * 严格按 QQ 音乐 MagicColorAlgorithm + MagicColorUtil 反编译逻辑实现：
 *
 * 调用链：extract(bitmap) 对应 QQ 的 c(bitmap) → d(e(bitmap)) → a(result)
 *
 * 1. Algorithm.e(): 降采样 10x10 → HSV → 过滤(S>=0.12, V>=0.18) → 非均匀 8 色相桶 → 按桶大小降序
 * 2. Algorithm.b(): 取前两非空桶 → 各桶内选离(S=0.6,V=0.6)最近 → 钳制 S/V 到 [0.5,0.7]
 * 3. Util.d(): 双空→青绿兜底；单色→用第一色色相生成副色 HSV(h,0.16,1.0)；双色→直通
 * 4. Util.a(): 方向感知色相约束(单侧36°) → S+0.2, V-0.04
 */
public class CoverColorExtractor {

    public static class ColorPair {
        public final int top;
        public final int bottom;

        public ColorPair(int top, int bottom) {
            this.top = top;
            this.bottom = bottom;
        }
    }

    // 非均匀色相桶边界（归一化 0~1，对应 hue/360）
    // bucket 0: [0, 0.06)       = [0°, 21.6°)
    // bucket 1: [0.06, 0.11)   = [21.6°, 39.6°)
    // bucket 2: [0.11, 0.21)   = [39.6°, 75.6°)
    // bucket 3: [0.21, 0.43)   = [75.6°, 154.8°)
    // bucket 4: [0.43, 0.53)   = [154.8°, 190.8°)
    // bucket 5: [0.53, 0.71)   = [190.8°, 255.6°)
    // bucket 6: [0.71, 0.82)   = [255.6°, 295.2°)
    // bucket 7: [0.82, 0.91)   = [295.2°, 327.6°)
    // >= 0.91 → 归回 bucket 0
    private static final float[] BUCKET_BOUNDS = {0.06f, 0.11f, 0.21f, 0.43f, 0.53f, 0.71f, 0.82f, 0.91f};

    private static final float MIN_SATURATION = 0.12f;
    private static final float MIN_VALUE = 0.18f;
    private static final int ALPHA = 255; // QQ 用 TPOnInfoID 常量，实际效果等同 255

    // 青绿兜底色（QQ MagicColorUtil.f31807c）
    private static final int FALLBACK_TOP = Color.HSVToColor(new float[]{153f, 0.4f, 0.94f});
    private static final int FALLBACK_BOTTOM = Color.HSVToColor(new float[]{153f, 0.16f, 1.0f});

    /**
     * 从封面 Bitmap 提取双色，对应 QQ 的完整链路：c(bitmap) → d(e(bitmap)) → a(result)
     */
    public static ColorPair extract(Bitmap bitmap) {
        if (bitmap == null) {
            return new ColorPair(FALLBACK_TOP, FALLBACK_BOTTOM);
        }

        // 1. 降采样到 10x10
        Bitmap sampled = scaleTo(bitmap, 10, 10);
        if (sampled == null) {
            return new ColorPair(FALLBACK_TOP, FALLBACK_BOTTOM);
        }

        // 2. 色相分桶 + 选色（Algorithm.b + Algorithm.e）
        float[] color1HSV = null;
        float[] color2HSV = null;

        List<List<float[]>> buckets = hueBuckets(sampled);
        if (sampled != bitmap) sampled.recycle();

        // 按桶大小降序排列
        List<List<float[]>> sorted = new ArrayList<>(buckets);
        Collections.sort(sorted, new Comparator<List<float[]>>() {
            @Override
            public int compare(List<float[]> a, List<float[]> b) {
                return Integer.compare(b.size(), a.size());
            }
        });

        // 取前两个非空桶
        List<float[]> firstBucket = null, secondBucket = null;
        for (List<float[]> bucket : sorted) {
            if (!bucket.isEmpty()) {
                if (firstBucket == null) {
                    firstBucket = bucket;
                } else if (secondBucket == null) {
                    secondBucket = bucket;
                    break;
                }
            }
        }

        if (firstBucket == null) {
            // 两桶都空 → 青绿兜底
            return new ColorPair(FALLBACK_TOP, FALLBACK_BOTTOM);
        }

        // 第一桶选离 (S=0.6, V=0.6) 最近的
        color1HSV = findClosest(firstBucket);
        clampSV(color1HSV);

        if (secondBucket == null) {
            // 只有第一桶非空
            // Algorithm.b 返回 (color1, null)
            // Util.d: 用第一色色相生成副色 HSV(h1, 0.16, 1.0)
            color2HSV = new float[]{color1HSV[0], 0.16f, 1.0f};
        } else {
            color2HSV = findClosest(secondBucket);
            clampSV(color2HSV);
        }

        // 3. Util.a: 方向感知色相约束 + S+0.2, V-0.04
        applyHueConstraintAndPostProcess(color1HSV, color2HSV);

        return new ColorPair(
                Color.HSVToColor(ALPHA, color1HSV),
                Color.HSVToColor(ALPHA, color2HSV)
        );
    }

    /**
     * 降采样到指定尺寸（对应 BitmapAlgorithms.a）
     */
    private static Bitmap scaleTo(Bitmap bitmap, int w, int h) {
        if (bitmap.getWidth() == w && bitmap.getHeight() == h) return bitmap;
        float sx = w / (float) bitmap.getWidth();
        float sy = h / (float) bitmap.getHeight();
        android.graphics.Matrix matrix = new android.graphics.Matrix();
        matrix.postScale(sx, sy);
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    /**
     * 色相分桶（对应 Algorithm.e）
     */
    private static List<List<float[]>> hueBuckets(Bitmap bitmap) {
        List<List<float[]>> buckets = new ArrayList<>(8);
        for (int i = 0; i < 8; i++) buckets.add(new ArrayList<float[]>());

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= 0 || height <= 0) return buckets;

        int total = width * height;
        // 采样步长：总像素 <= 10000 则 1，否则 sqrt(总像素/10000)
        int step = total <= 10000 ? 1 : (int) Math.sqrt(total / 10000.0);
        if (step < 1) step = 1;

        int[] pixels = new int[total];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        float[] hsv = new float[3];
        for (int y = 0; y < height; y += step) {
            int rowOffset = y * width;
            for (int x = 0; x < width; x += step) {
                Color.colorToHSV(pixels[rowOffset + x], hsv);
                if (hsv[1] >= MIN_SATURATION && hsv[2] >= MIN_VALUE) {
                    float normalized = hsv[0] / 360f;
                    int bucket = getBucket(normalized);
                    // 拷贝 hsv 数组，避免引用共享
                    buckets.get(bucket).add(new float[]{hsv[0], hsv[1], hsv[2]});
                }
            }
        }
        return buckets;
    }

    /**
     * 非均匀色相桶映射
     */
    private static int getBucket(float normalizedHue) {
        for (int i = 0; i < 8; i++) {
            if (normalizedHue < BUCKET_BOUNDS[i]) return i;
        }
        // >= 0.91 归回 bucket 0
        return 0;
    }

    /**
     * 在桶内找离 (S=0.6, V=0.6) 欧氏距离最近的像素
     * distance = sqrt((S-0.6)^2 + (V-0.6)^2)
     */
    private static float[] findClosest(List<float[]> bucket) {
        float[] best = null;
        float bestDist = Float.MAX_VALUE;
        for (float[] hsv : bucket) {
            float ds = hsv[1] - 0.6f;
            float dv = hsv[2] - 0.6f;
            float dist = (float) Math.sqrt(ds * ds + dv * dv);
            if (dist < bestDist) {
                bestDist = dist;
                best = hsv;
            }
        }
        return best != null ? new float[]{best[0], best[1], best[2]} : new float[]{0, 0, 0.25f};
    }

    /**
     * 钳制 S/V 到 [0.5, 0.7]（对应 Algorithm.a）
     * 公式：min(v*0.2+0.5, 0.7) 然后 max(., 0.5)
     * 数学上等价于 clamp(v*0.2+0.5, 0.5, 0.7)
     */
    private static void clampSV(float[] hsv) {
        hsv[1] = Math.min(hsv[1] * 0.2f + 0.5f, 0.7f);
        hsv[1] = Math.max(hsv[1], 0.5f);
        hsv[2] = Math.min(hsv[2] * 0.2f + 0.5f, 0.7f);
        hsv[2] = Math.max(hsv[2], 0.5f);
    }

    /**
     * 方向感知色相约束 + 饱和度明度后处理（对应 MagicColorUtil.a）
     *
     * 逻辑：
     *   if (h2 < h1) h2 += 360
     *   if (h2 < h1 + 180): h2 = min(h1 + 36, h2)
     *   else: h2 = max(h2, h1 + 360 - 36)
     *   if (h2 >= 360) h2 -= 360
     *   S += 0.2, V -= 0.04 (对两个颜色都做)
     */
    private static void applyHueConstraintAndPostProcess(float[] c1, float[] c2) {
        float h1 = c1[0];
        float h2 = c2[0];
        if (h2 < h1) h2 += 360;
        if (h2 < 180f + h1) {
            h2 = Math.min(h1 + 36f, h2);
        } else {
            h2 = Math.max(h2, h1 + 360f - 36f);
        }
        if (h2 >= 360f) h2 -= 360;
        c2[0] = h2;
        c1[1] += 0.2f;
        c1[2] -= 0.04f;
        c2[1] += 0.2f;
        c2[2] -= 0.04f;
    }

    // ===== 歌词高亮色色相映射（1:1 移植 QQ 音乐 q() 方法） =====

    /**
     * 根据封面提取色，按色相区间映射到预定义歌词高亮色。
     * 对应 QQ 音乐 LyricDropEffectPlayerInfoViewModel.q(color, isDay)。
     *
     * 白天（isDay=true）取浅色，夜间（isDay=false）取深色。
     * 灰色或低饱和色返回 #E4E4E4。
     *
     * @param coverColor 封面提取的主色（ARGB）
     * @param isDay      白天=true 用浅色，夜间=false 用深色
     * @return 歌词高亮色（ARGB）
     */
    public static int getLyricHighlightColor(int coverColor, boolean isDay) {
        float[] hsv = new float[3];
        Color.colorToHSV(coverColor, hsv);
        float h = hsv[0];
        float s = hsv[1];
        float v = hsv[2];

        // 灰色或极暗色 → 浅灰
        if (s <= 0.05f || v <= 0.05f) {
            return Color.parseColor("#E4E4E4");
        }

        int hue = (int) h;

        if (hue >= 21 && hue < 76) {
            return Color.parseColor(isDay ? "#FAD9AE" : "#D1984B");       // 橙黄
        }
        if (hue >= 76 && hue < 156) {
            return Color.parseColor(isDay ? "#BEFAC2" : "#5FA764");       // 黄绿
        }
        if (hue >= 156 && hue < 191) {
            return Color.parseColor(isDay ? "#BEF0FA" : "#2FB3CD");       // 青
        }
        if (hue >= 191 && hue < 257) {
            return Color.parseColor(isDay ? "#BED4FA" : "#7FAAFF");       // 蓝
        }
        if (hue >= 257 && hue < 296) {
            return Color.parseColor(isDay ? "#FABEF5" : "#B849AE");       // 紫
        }
        if (hue >= 296 && hue < 361) {
            return Color.parseColor(isDay ? "#FFC7F2" : "#873E76");      // 品红
        }
        if (hue >= 1 && hue < 21) {
            return Color.parseColor(isDay ? "#FFC7F2" : "#873E76");      // 红
        }
        return Color.parseColor("#E4E4E4");                               // 兜底
    }
}
