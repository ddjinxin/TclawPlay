package com.jingxin.jingxinmusic.scene;

import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.View;
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
import com.jingxin.jingxinmusic.view.ImmersiveOverlayView;
import com.jingxin.jingxinmusic.view.LandscapeGradientOverlay;
import com.jingxin.jingxinmusic.view.LyricView;
import com.jingxin.jingxinmusic.view.RotatingCoverView;
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
    public final RotatingCoverView coverView;
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
    public final ImageView btnTheme;
    public final View topButtonsBar;
    public final View controlButtons;

    // --- 可变状态 ---
    public boolean isNightMode;
    public boolean isPlaying;
    public MusicPlayerService.MusicPlayerBinder playerBinder;
    public CoverBorderGradientDrawable coverBorderGradient;
    public LandscapeGradientOverlay landscapeGradientOverlay;
    public ExecutorService executor;
    public final float density;

    // --- 回调接口 ---
    public interface Callback {
        void loadCover();
        void updateCoverPlaceholder();
        void resetLyricMargin();
        void updateImmersiveLyricMargin(boolean isFullScreen);
        void updateThemeUI();
        void extractAndApplyDominantColor(Bitmap bitmap);
    }
    public Callback callback;

    public CoverSceneHelper(
            FrameLayout rootLayout,
            ImageView blurBackground,
            RotatingCoverView coverView,
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
            ImageView btnTheme,
            View topButtonsBar,
            View controlButtons,
            float density) {
        this.rootLayout = rootLayout;
        this.blurBackground = blurBackground;
        this.coverView = coverView;
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
        this.btnTheme = btnTheme;
        this.topButtonsBar = topButtonsBar;
        this.controlButtons = controlButtons;
        this.density = density;
    }

    // ========== 通用工具方法 ==========

    /**
     * 根据可用高度按比例设置顶部和底部按钮间距
     * 顶部 marginTop = height × 4%，底部 marginBottom = height × 6%
     */
    public void applyButtonMargins(int height) {
        int topMargin = Math.max(4, (int) (height * 0.01f));
        int bottomMargin = Math.max(8, (int) (height * 0.01f));

        // 顶部按钮栏
        if (topButtonsBar != null) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) topButtonsBar.getLayoutParams();
            params.topMargin = topMargin;
            topButtonsBar.setLayoutParams(params);
        }

        // 底部按钮容器
        if (controlButtons != null) {
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) controlButtons.getLayoutParams();
            params.bottomMargin = bottomMargin;
            controlButtons.setLayoutParams(params);
        }
    }

    /**
     * 获取根 FrameLayout 的实际宽度
     */
    public int getLayoutWidth() {
        int pw = rootLayout.getWidth();
        if (pw > 0) return pw;
        return rootLayout.getResources().getDisplayMetrics().widthPixels;
    }

    /**
     * 获取根 FrameLayout 的实际可用高度
     */
    public int getAvailableScreenHeight() {
        int ph = rootLayout.getHeight();
        if (ph > 0) return ph;
        int height = rootLayout.getResources().getDisplayMetrics().heightPixels;
        // 减去系统栏
        int resourceId = rootLayout.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) height -= rootLayout.getResources().getDimensionPixelSize(resourceId);
        int navResourceId = rootLayout.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        if (navResourceId > 0) height -= rootLayout.getResources().getDimensionPixelSize(navResourceId);
        return Math.max(height, rootLayout.getResources().getDisplayMetrics().heightPixels);
    }

    /**
     * 排列三层层级：immersiveOverlay(0) → coverView(1) → landscapeGradientOverlay(2)
     */
    public void moveCoverBelowOverlay() {
        rootLayout.removeView(immersiveOverlay);
        rootLayout.addView(immersiveOverlay, 0);
        rootLayout.removeView(coverView);
        rootLayout.addView(coverView, 1);
        if (landscapeGradientOverlay != null) {
            rootLayout.removeView(landscapeGradientOverlay);
            rootLayout.addView(landscapeGradientOverlay, 2);
        }
    }

    /**
     * 将 coverView 恢复到 infoPanel 上层
     */
    public void moveCoverAboveInfoPanel() {
        int coverIndex = rootLayout.indexOfChild(coverView);
        int infoIndex = rootLayout.indexOfChild(infoPanel);
        int targetIndex = infoIndex + 1;
        if (coverIndex != targetIndex) {
            rootLayout.removeView(coverView);
            rootLayout.addView(coverView, targetIndex);
        }
    }

    /**
     * 竖屏沉浸：恢复 immersiveOverlay 到 blurBackground 之上
     */
    public void restoreOverlayHierarchy() {
        int blurIndex = rootLayout.indexOfChild(blurBackground);
        int overlayIndex = rootLayout.indexOfChild(immersiveOverlay);
        if (overlayIndex < blurIndex) {
            rootLayout.removeView(immersiveOverlay);
            rootLayout.addView(immersiveOverlay, blurIndex + 1);
        }
        int newOverlayIndex = rootLayout.indexOfChild(immersiveOverlay);
        int darkIndex = rootLayout.indexOfChild(immersiveDarkOverlay);
        if (darkIndex >= 0 && darkIndex < newOverlayIndex) {
            rootLayout.removeView(immersiveDarkOverlay);
            rootLayout.addView(immersiveDarkOverlay, newOverlayIndex + 1);
        }
        if (landscapeGradientOverlay != null) {
            landscapeGradientOverlay.setVisibility(android.view.View.GONE);
        }
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

    /**
     * 横屏公共布局：info_panel 左65% + 歌名/歌手定位 + 字号动态调整
     * 横屏经典和横屏沉浸共用
     */
    public void layoutLandscapeBase(int width) {
        // info_panel 占左 65%
        int infoWidth = (int) (width * 0.65f);
        FrameLayout.LayoutParams infoParams =
                (FrameLayout.LayoutParams) infoPanel.getLayoutParams();
        infoParams.width = infoWidth;
        infoParams.height = FrameLayout.LayoutParams.MATCH_PARENT;
        infoParams.gravity = Gravity.START;
        infoPanel.setLayoutParams(infoParams);
        if (infoPanel instanceof LinearLayout) {
            ((LinearLayout) infoPanel).setGravity(Gravity.CENTER_HORIZONTAL);
        }

        // 歌名 topMargin = 52dp
        LinearLayout.LayoutParams nameParams =
                (LinearLayout.LayoutParams) tvSongName.getLayoutParams();
        nameParams.topMargin = (int) (density * 52);
        tvSongName.setLayoutParams(nameParams);
        tvSongName.setGravity(Gravity.CENTER_HORIZONTAL);
        tvArtist.setGravity(Gravity.CENTER_HORIZONTAL);

        // 歌名字号：基于横屏信息区宽度独立计算，避免竖屏→横屏切换时LyricView尚未resize导致字号错误
        float songNameSize = Math.max(32f, Math.min(60f, infoWidth * 0.048f));
        float artistSize = songNameSize * 0.7f;
        tvSongName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, songNameSize);
        tvArtist.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, artistSize);
    }

    /**
     * 非沉浸模式下设置封面：旋转封面 + 模糊背景
     */
    public void applyClassicCover(Bitmap bitmap, boolean isLandscape) {
        boolean isFullLyric = lyricView != null &&
                lyricView.getDisplayMode() == LyricView.DisplayMode.FULL;
        // 竖屏全屏歌词时隐藏封面
        if (!(isFullLyric && !isLandscape)) {
            coverView.setVisibility(android.view.View.VISIBLE);
        }
        coverView.setImageBitmap(bitmap);
        blurBackground.setScaleType(ImageView.ScaleType.CENTER_CROP);
        executor.execute(() -> {
            Bitmap blurred = BlurUtil.blur(rootLayout.getContext(), bitmap, 10f);
            if (blurred != null) {
                rootLayout.post(() -> {
                    blurBackground.setImageBitmap(blurred);
                    blurBackground.setAlpha(0.5f);
                    blurBackground.setVisibility(android.view.View.VISIBLE);
                    if (!isNightMode) {
                        whiteOverlay.setVisibility(android.view.View.VISIBLE);
                        whiteOverlay.setAlpha(0.4f);
                    }
                });
            }
        });
    }
}
