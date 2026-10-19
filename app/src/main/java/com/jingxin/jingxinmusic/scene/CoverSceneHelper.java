package com.jingxin.jingxinmusic.scene;

import android.widget.FrameLayout;

import com.jingxin.jingxinmusic.service.MusicPlayerService;
import com.jingxin.jingxinmusic.view.CoverBorderGradientDrawable;
import com.jingxin.jingxinmusic.view.SpectrumView;

import java.util.concurrent.ExecutorService;

/**
 * CoverScene 的上下文，持有跨页/跨模块共享的状态与工具方法
 * V2 页自持全部组件；helper 仅保留仍被跨模块引用的状态。
 */
public class CoverSceneHelper {

    // --- 共享引用 ---
    public final FrameLayout rootLayout;       // V2 页 install 宿主 + 布局基准
    public final SpectrumView spectrumView;    // 频谱真值锚（prefs 同步源/数据泵宿主）

    // --- 可变状态 ---
    public boolean isNightMode;
    public boolean isPlaying;
    public boolean isLandscapeMode;
    public int bgSkinMode = 0; // 0=默认模式, 1=双色光晕
    public static final int COVER_STYLE_CLASSIC = 0;
    public static final int COVER_STYLE_IMMERSIVE = 1;
    public int coverStyle = COVER_STYLE_CLASSIC;
    public MusicPlayerService.MusicPlayerBinder playerBinder;
    public CoverBorderGradientDrawable coverBorderGradient;
    public ExecutorService executor;
    public final float density;

    // 系统状态栏实际高度（车机全屏时为0，手机有状态栏时为实际像素高度）
    public int systemTopInset = 0;

    public CoverSceneHelper(FrameLayout rootLayout, SpectrumView spectrumView, float density) {
        this.rootLayout = rootLayout;
        this.spectrumView = spectrumView;
        this.density = density;
    }

    /**
     * 纯比例计算顶部按钮栏高度（不依赖 getHeight()，避免横竖屏切换首帧读到旧值）
     * = topMargin(systemTopInset + height×1%) + topBtnSize(按三档比例×aspectFactor×1.3)
     */
    public int calcTopBarHeight(int height, int width, boolean isLandscape) {
        int topMargin = systemTopInset + Math.max(4, (int) (height * 0.01f));
        float rBack = (height < 700) ? 0.048f : (height <= 1200) ? 0.055f : 0.060f;
        float rBackTop = isLandscape ? rBack + 0.005f : rBack;
        float aspectFactor = 1f + Math.max(0f, (width / (float) height - 1.78f) / 3f);
        int topBtnSize = (int) (height * rBackTop * aspectFactor * 1.3f);
        return topMargin + topBtnSize;
    }

    /**
     * 获取根 FrameLayout 的实际宽度
     */
    public int getLayoutWidth() {
        int pw = rootLayout.getWidth();
        if (pw > 0) return pw;
        // 悬浮模式下优先取乐酷发送的悬浮区域宽度
        int floatW = com.jingxin.jingxinmusic.floatwindow.LecoFloatManager.getInstance().getFloatWidth();
        if (floatW > 0) return floatW;
        return rootLayout.getResources().getDisplayMetrics().widthPixels;
    }

    /**
     * 获取根 FrameLayout 的实际可用高度
     */
    public int getAvailableScreenHeight() {
        int ph = rootLayout.getHeight();
        if (ph > 0) return ph;
        // 悬浮模式下优先取乐酷发送的悬浮区域高度
        int floatH = com.jingxin.jingxinmusic.floatwindow.LecoFloatManager.getInstance().getFloatHeight();
        if (floatH > 0) return floatH;
        int height = rootLayout.getResources().getDisplayMetrics().heightPixels;
        // 减去系统栏
        int resourceId = rootLayout.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) height -= rootLayout.getResources().getDimensionPixelSize(resourceId);
        int navResourceId = rootLayout.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        if (navResourceId > 0) height -= rootLayout.getResources().getDimensionPixelSize(navResourceId);
        return Math.max(height, rootLayout.getResources().getDisplayMetrics().heightPixels);
    }
}