package com.jingxin.jingxinmusic.util;

import android.graphics.Color;

/**
 * 颜色工具类
 * 统一 LyricView、MiniFloatService、MainActivity 中的颜色计算逻辑
 */
public class ColorUtil {

    /**
     * 颜色线性混合
     * @param c1 颜色1（progress=1时为纯色1）
     * @param c2 颜色2（progress=0时为纯色2）
     * @param progress 混合比例 [0, 1]
     * @return 混合后颜色
     */
    public static int blendColor(int c1, int c2, float progress) {
        int r = (int) (Color.red(c1) * progress + Color.red(c2) * (1 - progress));
        int g = (int) (Color.green(c1) * progress + Color.green(c2) * (1 - progress));
        int b = (int) (Color.blue(c1) * progress + Color.blue(c2) * (1 - progress));
        return Color.rgb(r, g, b);
    }

    /**
     * 调整颜色透明度
     * @param color 原始ARGB颜色
     * @param factor 透明度倍率 [0, 1]
     * @return 调整后的颜色
     */
    public static int adjustAlpha(int color, float factor) {
        int a = Math.round(((color >> 24) & 0xFF) * factor);
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
