package com.jingxin.jingxinmusic.scene;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.jingxin.jingxinmusic.R;
import com.jingxin.jingxinmusic.service.MusicPlayerService;
import com.jingxin.jingxinmusic.util.BlurUtil;
import com.jingxin.jingxinmusic.util.ThemeColors;
import com.jingxin.jingxinmusic.view.CoverBorderGradientDrawable;
import com.jingxin.jingxinmusic.view.CoverCarouselAdapter;
import com.jingxin.jingxinmusic.view.CoverCarouselView;
import com.jingxin.jingxinmusic.view.ImmersiveOverlayView;
import com.jingxin.jingxinmusic.view.LandscapeGradientOverlay;
import com.jingxin.jingxinmusic.view.LyricView;
import com.jingxin.jingxinmusic.view.SpectrumView;


import java.util.concurrent.ExecutorService;

/**
 * CoverScene 的上下文，持有所有 View 引用和状态
 * 避免每个 Scene 实现类依赖 Activity
 */
public class CoverSceneHelper {

    // --- View 引用 ---
    public final FrameLayout rootLayout;
    public final ImageView blurBackground;
    // （旧共享封面 coverView 已随旧四风格页下线；V2 页自持封面）
    public final TextView tvSongName;
    public final TextView tvArtist;
    public final LyricView lyricView;
    public final SpectrumView spectrumView;
    public final SeekBar seekBar;
    public final TextView tvCurrentTime;
    public final TextView tvTotalTime;
    public final ImageView btnPlayPause;
    public final ImageView btnPrevious;
    public final ImageView btnNext;
    public final ImageView btnFavorite;
    public final View infoPanel;
    public final View coverPlaceholder;
    public final View overlayView;
    public final View whiteOverlay;
    public final View immersiveDarkOverlay;
    public final ImmersiveOverlayView immersiveOverlay;
    public final ImageView btnBack;
    public final ImageView btnSpectrum;
    public final ImageView btnOutfit;
    public final View topButtonsBar;
    public final View controlButtons;
    public final View progressLayout;
    public final LinearLayout rightButtonsGroup;
    // （旧共享唱臂 tonearmView 已随旧四风格页下线；V2 唱机页自持唱臂）


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
    public LandscapeGradientOverlay landscapeGradientOverlay;
    public ExecutorService executor;
    public final float density;

    // 系统状态栏实际高度（车机全屏时为0，手机有状态栏时为实际像素高度）
    public int systemTopInset = 0;

    public CoverSceneHelper(
            FrameLayout rootLayout,
            ImageView blurBackground,
            TextView tvSongName,
            TextView tvArtist,
            LyricView lyricView,
            SpectrumView spectrumView,
            SeekBar seekBar,
            TextView tvCurrentTime,
            TextView tvTotalTime,
            ImageView btnPlayPause,
            ImageView btnPrevious,
            ImageView btnNext,
            ImageView btnFavorite,
            View infoPanel,
            View coverPlaceholder,
            View overlayView,
            View whiteOverlay,
            View immersiveDarkOverlay,
            ImmersiveOverlayView immersiveOverlay,
            ImageView btnBack,
            ImageView btnSpectrum,
            ImageView btnOutfit,
            View topButtonsBar,
            View controlButtons,
            View progressLayout,
            LinearLayout rightButtonsGroup,
            float density) {
        this.rootLayout = rootLayout;
        this.blurBackground = blurBackground;
        this.tvSongName = tvSongName;
        this.tvArtist = tvArtist;
        this.lyricView = lyricView;
        this.spectrumView = spectrumView;
        this.seekBar = seekBar;
        this.tvCurrentTime = tvCurrentTime;
        this.tvTotalTime = tvTotalTime;
        this.btnPlayPause = btnPlayPause;
        this.btnPrevious = btnPrevious;
        this.btnNext = btnNext;
        this.btnFavorite = btnFavorite;
        this.infoPanel = infoPanel;
        this.coverPlaceholder = coverPlaceholder;
        this.overlayView = overlayView;
        this.whiteOverlay = whiteOverlay;
        this.immersiveDarkOverlay = immersiveDarkOverlay;
        this.immersiveOverlay = immersiveOverlay;
        this.btnBack = btnBack;
        this.btnSpectrum = btnSpectrum;
        this.btnOutfit = btnOutfit;
        this.topButtonsBar = topButtonsBar;
        this.controlButtons = controlButtons;
        this.progressLayout = progressLayout;
        this.rightButtonsGroup = rightButtonsGroup;
        this.density = density;
    }

    // ========== 通用工具方法 ==========

    /**
     * 根据可用高度按比例设置顶部和底部按钮间距
     * 顶部 marginTop = height × 1%，底部 marginBottom = height × 1%
     * 同时设置顶部按钮栏的左右padding和右侧按钮组宽度
     *
     * 竖屏：返回按钮左对齐底部第一个按钮，右3按钮占35%宽度右对齐底部最后一个按钮
     * 横屏：返回按钮左对齐底部第一个按钮，右3按钮占35%宽度，右边距=左边距（对称）
     */

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

    /** 设置底部按钮高度和 padding */
    private void resizeBottomButton(int viewId, int size, int padding) {
        resizeBottomButton(viewId, size, padding, 1f);
    }

    /** 设置底部按钮高度、padding、宽度权重（weight 越大分到的格子越宽） */
    private void resizeBottomButton(int viewId, int size, int padding, float weight) {
        ImageView btn = rootLayout.findViewById(viewId);
        if (btn != null) {
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) btn.getLayoutParams();
            p.height = size; p.weight = weight;
            btn.setLayoutParams(p);
            btn.setPadding(padding, padding, padding, padding);
        }
    }

    /** 值域约束 */
    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
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

    /**
     * 创建或复用 CoverBorderGradientDrawable
     */
    public CoverBorderGradientDrawable ensureCoverBorderGradient(int overlayColor) {
        float borderWidthPx = density * 40f;
        if (coverBorderGradient == null) {
            coverBorderGradient = new CoverBorderGradientDrawable(overlayColor, borderWidthPx);
        } else {
            coverBorderGradient.setOverlayColor(overlayColor);
        }
        return coverBorderGradient;
    }

    // ========== 唱片机模式辅助方法 ==========

}
