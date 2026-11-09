package com.jingxin.jingxinmusic.util;

import android.view.View;

import com.jingxin.jingxinmusic.floatwindow.LecoFloatManager;

/**
 * 顶部状态栏避让（单一事实源，MainFragment / PlayerFragment 共用）
 *
 * 只补偿窗口"真实伸入状态栏区域"的重叠量，系统已垫下的设备测得 0，绝不双重避让：
 * - 悬浮窗覆盖窗口：用原始 insets（overlay insets 相对悬浮区域自身，相交才有值，天然即重叠量）
 * - 普通窗口：max(0, insets − 根视图距屏幕顶部距离)
 *   · Android 15+ 边到边（窗口从 y=0 铺满）→ 自动避让整条状态栏
 *   · 系统已把窗口垫在状态栏下方（rootTop ≥ insets）→ 0
 *   · 半沉浸 ROM（窗口伸入一部分）→ 只补偿实际重叠的那段
 */
public final class StatusBarAvoid {

    private StatusBarAvoid() {}

    /**
     * @param rootView 根布局（getLocationOnScreen 量窗口实际被垫下多少）
     * @param rawTopInset insets 回调给出的顶部原始值
     * @return 应额外下移的像素值
     */
    public static int effectiveTopInset(View rootView, int rawTopInset) {
        if (rawTopInset == 0) return 0;
        if (LecoFloatManager.getInstance().isFloating()) return rawTopInset;
        int rootTopOnScreen = 0;
        if (rootView != null && rootView.isAttachedToWindow()) {
            int[] loc = new int[2];
            rootView.getLocationOnScreen(loc);
            rootTopOnScreen = Math.max(0, loc[1]);
        }
        return Math.max(0, rawTopInset - rootTopOnScreen);
    }
}
