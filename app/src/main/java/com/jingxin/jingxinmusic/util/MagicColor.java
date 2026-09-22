package com.jingxin.jingxinmusic.util;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * QQ Music 同款"魔法色"算法（GlobalActivityBgManager + MagicColorAlgorithm + DefineRankListMagicColor）
 *
 * 管线：封面 70x70 → HSV 8 桶分桶取主色 → 色相查 10 档预设色表 → 浅色/深色
 *
 * 取色规则（MagicColorAlgorithm.c）：
 *   1. 过滤 S<0.12 或 V<0.18 的像素
 *   2. 按 H/360 分 8 桶，按像素数降序，取最大桶
 *   3. 桶内选 distance=√((S-0.6)²+(V-0.6)²) 最小的像素
 *   4. 收敛 S'=V'=clamp(原值×0.2+0.5, 0.5, 0.7)
 *
 * 查表规则（DefineRankListMagicColor）：主色 HSV 的 S>0.06 且 V>0.1 时按 H 落区间，
 * 否则用兜底档；light 模式取浅色列。
 */
public final class MagicColor {

    /** 10 档色相区间表：{minH, maxH, 浅色, 深色}（QQ Music DefineRankListMagicColor 原表） */
    private static final int[][] HUE_TABLE = {
            {0,   0,  0xFFAEF2B0, 0xFF3D7F3E},   // 兜底（0°/330-360°）
            {0,   20, 0xFFFABEBE, 0xFF893D3D},
            {21,  40, 0xFFFAD4B9, 0xFF913E19},
            {41,  75, 0xFFFFE7AF, 0xFF8D6200},
            {76,  155, 0xFFAEF2B0, 0xFF3D7F3E},
            {156, 190, 0xFFA8F0D3, 0xFF3E8568},
            {191, 256, 0xFF96D9FA, 0xFF2D6987},
            {257, 295, 0xFF876DC7, 0xFF3D2578},
            {296, 329, 0xFFCC8FCC, 0xFF772277},
            {330, 360, 0xFFFABEBE, 0xFF893D3D},
    };

    private MagicColor() {}

    /**
     * 从封面位图提取魔法色（QQ Music MagicColorAlgorithm.c 同款逻辑）
     *
     * @param bitmap 封面位图（建议先缩到 70x70）
     * @return 提取出的 HSV 主色（RGB 值），失败返回 -1
     */
    public static int extractDominant(Bitmap bitmap) {
        if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) return -1;

        // HSV 8 桶分桶
        List<List<float[]>> buckets = new ArrayList<>(8);
        for (int i = 0; i < 8; i++) buckets.add(new ArrayList<float[]>());

        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);

        float[] hsv = new float[3];
        for (int p : pixels) {
            Color.colorToHSV(p, hsv);
            if (hsv[1] < 0.12f || hsv[2] < 0.18f) continue;
            float f = hsv[0] / 360f;
            if (f < 0.06f) buckets.get(0).add(hsv.clone());
            else if (f < 0.11f) buckets.get(1).add(hsv.clone());
            else if (f < 0.21f) buckets.get(2).add(hsv.clone());
            else if (f < 0.43f) buckets.get(3).add(hsv.clone());
            else if (f < 0.53f) buckets.get(4).add(hsv.clone());
            else if (f < 0.71f) buckets.get(5).add(hsv.clone());
            else if (f < 0.82f) buckets.get(6).add(hsv.clone());
            else if (f < 0.91f) buckets.get(7).add(hsv.clone());
            else buckets.get(0).add(hsv.clone());
        }

        // 按桶大小降序
        Collections.sort(buckets, new Comparator<List<float[]>>() {
            @Override
            public int compare(List<float[]> a, List<float[]> b) {
                return Integer.compare(b.size(), a.size());
            }
        });

        List<float[]> top = buckets.get(0);
        if (top.isEmpty()) return -1;

        // 最大桶内找离 (S=0.6, V=0.6) 最近的
        float[] best = null;
        float bestDist = Float.MAX_VALUE;
        for (float[] c : top) {
            float d = (float) Math.sqrt((c[1] - 0.6f) * (c[1] - 0.6f) + (c[2] - 0.6f) * (c[2] - 0.6f));
            if (d < bestDist) {
                bestDist = d;
                best = c;
            }
        }
        if (best == null) return -1;

        // 收敛 S、V
        float s = Math.min(Math.max(best[1] * 0.2f + 0.5f, 0.5f), 0.7f);
        float v = Math.min(Math.max(best[2] * 0.2f + 0.5f, 0.5f), 0.7f);
        return Color.HSVToColor(new float[]{best[0], s, v});
    }

    /**
     * 色相查表取预设色（QQ Music DefineRankListMagicColor.b 同款逻辑）
     *
     * @param dominant extractDominant 返回的主色
     * @param light    true 取浅色列（白天），false 取深色列（夜间）
     * @return 查表色
     */
    public static int mapColor(int dominant, boolean light) {
        float[] hsv = new float[3];
        Color.colorToHSV(dominant, hsv);

        if (hsv[1] > 0.06f && hsv[2] > 0.1f) {
            int hue = (int) hsv[0];
            // 跳过兜底档（index 0），按区间匹配
            for (int i = 1; i < HUE_TABLE.length; i++) {
                int[] row = HUE_TABLE[i];
                if (hue >= row[0] && hue <= row[1]) {
                    return light ? row[2] : row[3];
                }
            }
            // 无匹配（理论上不会到，330-360 有兜底档）
            return light ? HUE_TABLE[0][2] : HUE_TABLE[0][3];
        }
        // 低饱和/低明度 → 兜底绿色
        return light ? HUE_TABLE[0][2] : HUE_TABLE[0][3];
    }
}
