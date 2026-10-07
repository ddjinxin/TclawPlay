package com.jingxin.jingxinmusic.scene;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
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
import com.jingxin.jingxinmusic.view.CoverCarouselAdapter;
import com.jingxin.jingxinmusic.view.CoverCarouselView;
import com.jingxin.jingxinmusic.view.ImmersiveOverlayView;
import com.jingxin.jingxinmusic.view.LandscapeGradientOverlay;
import com.jingxin.jingxinmusic.view.LyricView;
import com.jingxin.jingxinmusic.view.RotatingCoverView;
import com.jingxin.jingxinmusic.view.SpectrumView;
import com.jingxin.jingxinmusic.view.TonearmView;

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
    public final View topButtonsBar;
    public final View controlButtons;
    public final View progressLayout;
    public final LinearLayout rightButtonsGroup;
    public final TonearmView tonearmView;

    // --- 动态创建的 View（轮播模式） ---
    public CoverCarouselView carouselView;
    public CoverCarouselAdapter carouselAdapter;

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

    // --- 回调接口 ---
    public interface Callback {
        void loadCover();
        void updateCoverPlaceholder();
        void resetLyricMargin();
        void updateImmersiveLyricMargin(boolean isFullScreen);
        void updateThemeUI();
        void extractAndApplyDominantColor(Bitmap bitmap);
        void updateTonearmPosition();
        void markTonearmNeedsUpdate();
        void playSongAt(int position);
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
            View topButtonsBar,
            View controlButtons,
            View progressLayout,
            LinearLayout rightButtonsGroup,
            TonearmView tonearmView,
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
        this.topButtonsBar = topButtonsBar;
        this.controlButtons = controlButtons;
        this.progressLayout = progressLayout;
        this.rightButtonsGroup = rightButtonsGroup;
        this.tonearmView = tonearmView;
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

    public void applyButtonMargins(int height, int width, boolean isLandscape) {
        // 顶部 marginTop = 状态栏实际高度 + height × 1%（车机全屏无状态栏时 inset=0，不影响）
        int topMargin = systemTopInset + Math.max(4, (int) (height * 0.01f));
        int bottomMargin = Math.max(8, (int) (height * 0.01f));
        int sidePadding = (int) (density * 8); // 与底部按钮区 marginStart/End 一致

        // 按钮高度按屏幕高度比例缩放 + 宽高比修正
        // 1. 按屏高像素分三档定基础比例（横屏顶部按钮在此基础上+0.5%，底部按钮不动）
        // 2. 宽高比修正系数: 以16:9(1.78)为基准, 超宽屏按钮高度补偿
        //    aspectFactor = 1 + max(0, (w/h - 1.78) / 3), 平滑过渡无跳变
        // 底部按钮分三类比例：上一曲/下一曲 +21%（1.1×1.1）、播放暂停 +50%、其他不变
        float rBack, rRight, rPlay;
        float rPrevNext;   // 上一曲/下一曲：普通按钮 × 1.21
        float rPlayBig;     // 播放/暂停：原始播放比例 × 1.5（先×1.25再×1.2）
        if (height < 700) {
            rBack = 0.048f; rRight = 0.043f; rPlay = 0.058f;
            rPrevNext = 0.048f * 1.21f;   // 0.05808
            rPlayBig = 0.058f * 1.5f;     // 0.087
        } else if (height <= 1200) {
            rBack = 0.055f; rRight = 0.050f; rPlay = 0.067f;
            rPrevNext = 0.055f * 1.21f;   // 0.06655
            rPlayBig = 0.067f * 1.5f;     // 0.1005
        } else {
            rBack = 0.060f; rRight = 0.055f; rPlay = 0.075f;
            rPrevNext = 0.060f * 1.21f;   // 0.0726
            rPlayBig = 0.075f * 1.5f;     // 0.1125
        }
        // 横屏：返回按钮和右侧三按钮各+0.5%屏高，底部按钮维持原比例
        float rBackTop = isLandscape ? rBack + 0.005f : rBack;
        float rRightTop = isLandscape ? rRight + 0.005f : rRight;
        float aspectFactor = 1f + Math.max(0f, (width / (float) height - 1.78f) / 3f);
        int topBtnSize = (int) (height * rBackTop * aspectFactor * 1.3f); // 返回按钮调大30%
        int topRightBtnSize = (int) (height * rRightTop * aspectFactor);
        int bottomBtnSize = (int) (height * rBack * aspectFactor);
        int prevNextBtnSize = (int) (height * rPrevNext * aspectFactor);
        int playBtnSize = (int) (height * rPlayBig * aspectFactor);

        // 顶部按钮栏
        if (topButtonsBar != null) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) topButtonsBar.getLayoutParams();
            params.topMargin = topMargin;
            topButtonsBar.setLayoutParams(params);
            topButtonsBar.setPadding(sidePadding, 0, sidePadding, 0);
        }

        // 返回按钮（父容器是 LinearLayout）
        if (btnBack != null) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(topBtnSize, topBtnSize);
            btnBack.setLayoutParams(params);
            int backPadding = (int)(topBtnSize / 1.3f * 0.15f);
            btnBack.setPadding(backPadding, backPadding, backPadding, backPadding);
        }

        // 右侧3按钮组：占页面宽度35%
        if (rightButtonsGroup != null) {
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) rightButtonsGroup.getLayoutParams();
            params.width = (int) (width * 0.35f);
            params.height = topRightBtnSize;
            params.weight = 0;
            rightButtonsGroup.setLayoutParams(params);
        }

        // 顶部右侧三按钮高度+padding（横屏无padding，竖屏保留22%）
        int topRightPadding = isLandscape ? (int)(topRightBtnSize * 0.05f) : (int)(topRightBtnSize * 0.22f);
        if (btnSpectrum != null) {
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) btnSpectrum.getLayoutParams();
            p.height = topRightBtnSize; p.weight = 1;
            btnSpectrum.setLayoutParams(p);
            btnSpectrum.setPadding(topRightPadding, topRightPadding, topRightPadding, topRightPadding);
        }
        if (btnOutfit != null) {
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) btnOutfit.getLayoutParams();
            p.height = topRightBtnSize; p.weight = 1;
            btnOutfit.setLayoutParams(p);
            btnOutfit.setPadding(topRightPadding, topRightPadding, topRightPadding, topRightPadding);
        }
        // 歌词搜索按钮也在右侧组里
        ImageView btnLyricSearch = rootLayout.findViewById(R.id.lyric_search_button);
        if (btnLyricSearch != null) {
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) btnLyricSearch.getLayoutParams();
            p.height = topRightBtnSize; p.weight = 1;
            btnLyricSearch.setLayoutParams(p);
            btnLyricSearch.setPadding(topRightPadding, topRightPadding, topRightPadding, topRightPadding);
        }

        // 音效按钮（返回键右侧，与右侧三按钮同规格）
        ImageView btnEffect = rootLayout.findViewById(R.id.effect_button);
        if (btnEffect != null) {
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) btnEffect.getLayoutParams();
            p.height = topRightBtnSize; p.weight = 1;
            btnEffect.setLayoutParams(p);
            btnEffect.setPadding(topRightPadding, topRightPadding, topRightPadding, topRightPadding);
        }

        // 底部按钮容器
        if (controlButtons != null) {
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) controlButtons.getLayoutParams();
            params.bottomMargin = bottomMargin;
            controlButtons.setLayoutParams(params);
        }

        // 底部按钮高度+padding
        // padding 统一基于普通按钮尺寸，不随上一曲/下一曲/播放暂停的比例放大而变
        // 这样放大的按钮图标区域 = 按钮高 - 固定padding，视觉上图标真正变大
        int bottomPadding6 = (int)(bottomBtnSize * 0.15f);  // 原 6dp 按钮
        int bottomPadding8 = (int)(bottomBtnSize * 0.20f);  // 原 8dp 按钮
        // 上一曲/下一曲 weight：竖屏=1.5（格子加宽，图标不被宽度钳制），横屏=1（保持原状）
        float prevNextWeight = isLandscape ? 1f : 1.5f;
        resizeBottomButton(R.id.previous_button, prevNextBtnSize, bottomPadding6, prevNextWeight);
        // 播放键 weight：竖屏=2（格子宽度加倍，图标不被宽度钳制），横屏=1（保持原状）
        resizeBottomButton(R.id.play_pause_button, playBtnSize, bottomPadding6, isLandscape ? 1f : 2f);
        resizeBottomButton(R.id.next_button, prevNextBtnSize, bottomPadding6, prevNextWeight);
        resizeBottomButton(R.id.play_order_button, bottomBtnSize, bottomPadding6);
        resizeBottomButton(R.id.history_button, bottomBtnSize, bottomPadding8);
        resizeBottomButton(R.id.mode_button, bottomBtnSize, bottomPadding8);
        resizeBottomButton(R.id.download_button, bottomBtnSize, bottomPadding8);
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
        if (landscapeGradientOverlay != null && landscapeGradientOverlay.getParent() != null) {
            rootLayout.removeView(landscapeGradientOverlay);
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
    public void layoutLandscapeBase(int width, int height) {
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

        // 歌名 topMargin：横屏动态计算（返回按钮放大后高度 + 8dp 间距）
        LinearLayout.LayoutParams nameParams =
                (LinearLayout.LayoutParams) tvSongName.getLayoutParams();
        nameParams.topMargin = calcTopBarHeight(height, width, true) + (int) (density * 8);
        tvSongName.setLayoutParams(nameParams);
        tvSongName.setGravity(Gravity.CENTER_HORIZONTAL);
        tvArtist.setGravity(Gravity.CENTER_HORIZONTAL);

        // 歌名字号：基于横屏信息区宽度独立计算，避免竖屏→横屏切换时LyricView尚未resize导致字号错误
        float songNameSize = infoWidth * 0.048f;
        float artistSize = songNameSize * 0.7f;
        tvSongName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, songNameSize);
        tvArtist.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, artistSize);
        // 横屏进度条比例缩放
        applyProgressScaling(height, width);
    }

    /**
     * 进度条比例缩放：圆球（屏高2%）+ 时间文字（屏高2%）+ 轨道高度（屏高0.5%）
     * 横竖屏通用，超宽屏按 aspectFactor 补偿
     */
    public void applyProgressScaling(int height, int width) {
        float aspectFactor = 1f + Math.max(0f, (width / (float) height - 1.78f) / 3f);
        // 圆球直径 = 屏高 × 2%
        int thumbSize = Math.max(8, (int) (height * 0.02f * aspectFactor));
        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        // 光晕模式：圆球跟随按钮色（白天黑/夜间白），其他模式保持黄色
        if (bgSkinMode == 1) {
            thumb.setColor(isNightMode ? ThemeColors.nightTextPrimary() : ThemeColors.dayTextPrimary());
        } else {
            thumb.setColor(Color.parseColor("#FFD54F"));
        }
        thumb.setSize(thumbSize, thumbSize);
        seekBar.setThumb(thumb);
        seekBar.setThumbOffset(thumbSize / 2);
        // 轨道高度 = 屏高 × 0.5%（setMinHeight 在 Android 8.1 不存在，改用 track drawable 高度）
        // bounds 必须按系统 updateThumbAndTrackPos 同款公式垂直居中，
        // 否则 top=0 会把轨道贴顶绘制，切换模式时与圆球错位（轨道上跳）
        int trackHeight = Math.max(2, (int) (height * 0.005f * aspectFactor));
        android.graphics.drawable.Drawable trackDrawable = seekBar.getProgressDrawable();
        if (trackDrawable != null) {
            int trackWidth = seekBar.getWidth() - seekBar.getPaddingLeft() - seekBar.getPaddingRight();
            if (trackWidth <= 0) trackWidth = 1080;
            int paddedHeight = seekBar.getHeight() - seekBar.getPaddingTop() - seekBar.getPaddingBottom();
            int sysTrackHeight = Math.min(trackHeight, Math.max(0, paddedHeight));
            int trackOffset = Math.max(0, (paddedHeight - sysTrackHeight) / 2);
            trackDrawable.setBounds(0, trackOffset, trackWidth, trackOffset + sysTrackHeight);
        }
        // 时间文字 = 屏高 × 2%
        float timeTextSize = height * 0.02f * aspectFactor;
        tvCurrentTime.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, timeTextSize);
        tvTotalTime.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, timeTextSize);
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
        // 双色光晕+非沉浸模式：跳过模糊背景，由光晕 View 接管
        if (bgSkinMode == 1 && coverStyle != COVER_STYLE_IMMERSIVE) return;
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
                        whiteOverlay.setAlpha(0.7f);
                    }
                });
            }
        });
    }

    /**
     * 设置 infoPanel 全宽布局（轮播/竖屏模式统一调用）
     */
    public void setLayoutInfoPanelFullWidth() {
        FrameLayout.LayoutParams infoParams =
                (FrameLayout.LayoutParams) infoPanel.getLayoutParams();
        infoParams.width = FrameLayout.LayoutParams.MATCH_PARENT;
        infoParams.height = FrameLayout.LayoutParams.MATCH_PARENT;
        infoParams.gravity = Gravity.START;
        infoPanel.setLayoutParams(infoParams);
        if (infoPanel instanceof LinearLayout) {
            ((LinearLayout) infoPanel).setGravity(Gravity.CENTER_HORIZONTAL);
        }
    }

    /**
     * 设置封面为圆形裁剪样式（经典/沉浸恢复时统一调用）
     */
    public void applyCircleCoverStyle() {
        coverView.setClipToOutline(true);
        coverView.setBackgroundResource(R.drawable.circle_cover_background);
        com.jingxin.jingxinmusic.util.CompatUtil.setForegroundCompat(coverView, null);
    }

    // ========== 圆环频谱位置切换 ==========

    /**
     * 将 SpectrumView 移动到 rootLayout 全屏覆盖（圆环模式时调用）
     * SpectrumView 从 info_panel 移出，铺满 rootLayout，不被裁剪
     */
    public void moveSpectrumToCover() {
        if (spectrumView == null || rootLayout == null) return;
        // 避免重复移动
        if (spectrumView.getParent() == rootLayout) return;
        // 先从 info_panel 移除
        if (spectrumView.getParent() != null) {
            ((android.view.ViewGroup) spectrumView.getParent()).removeView(spectrumView);
        }
        // 添加到 rootLayout，铺满全屏（透明背景，不遮挡其他内容）
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        rootLayout.addView(spectrumView, params);
        // 确保 SpectrumView 在 coverView 之上但在按钮之下
        int spectrumIndex = rootLayout.indexOfChild(spectrumView);
        int topBarIndex = rootLayout.indexOfChild(topButtonsBar);
        if (topBarIndex >= 0 && spectrumIndex > topBarIndex) {
            rootLayout.removeView(spectrumView);
            rootLayout.addView(spectrumView, topBarIndex);
        }
        // 通知 SpectrumView 重新计算布局
        spectrumView.requestLayout();
    }

    /**
     * 将 SpectrumView 移回 info_panel 底部（非圆环模式时调用）
     */
    public void moveSpectrumToBottom() {
        if (spectrumView == null || infoPanel == null) return;
        // 避免重复移动
        if (spectrumView.getParent() == infoPanel) return;
        // 先从 rootLayout 移除
        if (spectrumView.getParent() != null) {
            ((android.view.ViewGroup) spectrumView.getParent()).removeView(spectrumView);
        }
        // 添加回 info_panel，放到歌词和进度条之间（频谱原本在歌词后面）
        if (infoPanel instanceof LinearLayout) {
            LinearLayout panel = (LinearLayout) infoPanel;
            // 频谱在 lyric_view 之后、progress_layout 之前
            int lyricIndex = -1;
            for (int i = 0; i < panel.getChildCount(); i++) {
                if (panel.getChildAt(i).getId() == R.id.lyric_view) {
                    lyricIndex = i;
                    break;
                }
            }
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (int) (density * 150)); // 默认150dp高度
            if (lyricIndex >= 0 && lyricIndex + 1 < panel.getChildCount()) {
                panel.addView(spectrumView, lyricIndex + 1, params);
            } else {
                panel.addView(spectrumView, params);
            }
        }
        spectrumView.requestLayout();
    }

    /**
     * 更新圆环模式 SpectrumView 的封面位置信息
     * SpectrumView 铺满 rootLayout，圆心/半径通过 setCoverCenter 传入
     */
    public void layoutSpectrumRing(int coverCenterX, int coverCenterY, int coverSize) {
        if (spectrumView == null) return;
        float radius = coverSize / 2f;
        spectrumView.setCoverCenter(coverCenterX, coverCenterY, radius);
    }

    /**
     * 根据频谱模式统一处理 SpectrumView 位置
     * 经典模式下：圆环→移到rootLayout全屏覆盖，非圆环→移回info_panel底部
     * 沉浸模式下：SpectrumView始终在info_panel底部
     *
     * @param isImmersive 是否沉浸模式
     * @param coverCenterX 封面圆心X（相对rootLayout）
     * @param coverCenterY 封面圆心Y（相对rootLayout）
     * @param coverSize 封面尺寸
     * @param height 可用高度（用于设置底部频谱高度）
     * @param spectrumHeightRatio 频谱高度比例
     */
    public void applySpectrumPosition(boolean isImmersive, int coverCenterX, int coverCenterY,
                                       int coverSize, int height, float spectrumHeightRatio) {
        boolean isCoverOverlay = spectrumView != null && spectrumView.isCoverOverlayMode();
        
        if (isCoverOverlay && !isImmersive) {
            // 经典模式 + 圆环/扩散圆环：频谱铺满rootLayout，设置封面位置
            moveSpectrumToCover();
            layoutSpectrumRing(coverCenterX, coverCenterY, coverSize);
        } else {
            // 非圆环/扩散圆环 或 沉浸模式：频谱在info_panel底部
            if (spectrumView != null && spectrumView.getParent() == rootLayout) {
                // 之前在rootLayout上（圆环/扩散圆环模式），移回来
                moveSpectrumToBottom();
            }
            if (spectrumView != null) {
                spectrumView.getLayoutParams().height = (int) (height * spectrumHeightRatio);
            }
        }
    }

    /**
     * 隐藏沉浸相关 View（非沉浸模式 enter 时统一调用）
     */
    public void hideImmersiveViews() {
        immersiveOverlay.setVisibility(View.GONE);
        immersiveDarkOverlay.setVisibility(View.GONE);
        if (landscapeGradientOverlay != null && landscapeGradientOverlay.getParent() != null) {
            rootLayout.removeView(landscapeGradientOverlay);
        }
    }

    /**
     * 确保 carouselView 和 carouselAdapter 已创建并添加到 rootLayout
     */
    public void ensureCarouselView() {
        if (carouselView == null) {
            carouselView = new CoverCarouselView(rootLayout.getContext());
            carouselView.setId(View.generateViewId());
            carouselView.setExecutor(executor);
            carouselAdapter = new CoverCarouselAdapter(rootLayout.getContext(), executor);
            carouselView.setOnSongChangeListener(delta -> callback.playSongAt(delta));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
            rootLayout.addView(carouselView, lp);
        }
    }

    /**
     * 将 carouselView 移到 infoPanel 上方
     */
    public void moveCarouselAboveInfoPanel() {
        if (carouselView == null) return;
        int carouselIdx = rootLayout.indexOfChild(carouselView);
        int infoIdx = rootLayout.indexOfChild(infoPanel);
        if (infoIdx >= 0 && carouselIdx != infoIdx + 1) {
            rootLayout.removeView(carouselView);
            rootLayout.addView(carouselView, infoIdx + 1);
        }
    }

    /**
     * 轮播模式：仅更新模糊背景（封面由 carousel adapter 自行管理）
     */
    public void applyBlurBackground(Bitmap bitmap) {
        // 双色光晕+非沉浸模式：跳过模糊背景
        if (bgSkinMode == 1 && coverStyle != COVER_STYLE_IMMERSIVE) return;
        blurBackground.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        executor.execute(() -> {
            Bitmap blurred = com.jingxin.jingxinmusic.util.BlurUtil.blur(
                    rootLayout.getContext(), bitmap, 10f);
            if (blurred != null) {
                rootLayout.post(() -> {
                    blurBackground.setImageBitmap(blurred);
                    blurBackground.setAlpha(0.5f);
                    blurBackground.setVisibility(View.VISIBLE);
                     if (!isNightMode) {
                         whiteOverlay.setVisibility(View.VISIBLE);
                         whiteOverlay.setAlpha(0.7f);
                     }
                 });
             }
         });
     }

    // ========== 唱片机模式辅助方法 ==========

    /** 唱片机模式：设置封面样式（不裁剪，无圆形边框） */
    public void recordSetupCoverStyle() {
        coverView.setClipToOutline(false);
        coverView.setBackground(null);
        com.jingxin.jingxinmusic.util.CompatUtil.setForegroundCompat(coverView, null);
    }

    /** 唱片机模式：布局唱臂 */
    public void recordLayoutTonearm() {
        if (tonearmView != null) {
            tonearmView.setLandscapeMode(isLandscapeMode);
            callback.markTonearmNeedsUpdate();
            tonearmView.refreshAngle();
        }
    }

    /** 唱片机模式：退出时隐藏唱臂 */
    public void recordExit() {
        if (tonearmView != null) {
            tonearmView.setVisibility(View.GONE);
        }
    }

    /** 唱片机模式：播放状态变更（唱臂动画 + 封面旋转） */
    public void recordOnPlayingStateChanged(boolean isPlaying) {
        if (tonearmView != null) {
            tonearmView.setPlaying(isPlaying);
        }
        if (isPlaying) {
            coverView.startRotation();
        } else {
            coverView.stopRotation();
        }
    }

    /** 唱片机模式：从 mini 播放条恢复时同步唱臂状态 */
    public void recordOnServiceResumed(boolean isPlaying) {
        if (tonearmView != null) {
            tonearmView.setLandscapeMode(isLandscapeMode);
            tonearmView.setPlaying(isPlaying);
            tonearmView.refreshAngle();
            callback.markTonearmNeedsUpdate();
        }
    }

    /** 唱片机模式：进入时初始化黑胶 + 唱臂 */
    public void recordOnStyleEnter() {
        coverView.setVinylMode(true);
        if (tonearmView != null) {
            // 竖屏全屏歌词时唱臂隐藏（横屏全屏设计保留唱臂）
            boolean fullLyricPortrait = !isLandscapeMode && lyricView != null &&
                    lyricView.getDisplayMode() == LyricView.DisplayMode.FULL;
            tonearmView.setVisibility(fullLyricPortrait ? View.GONE : View.VISIBLE);
            tonearmView.setLandscapeMode(isLandscapeMode);
            tonearmView.setNightMode(isNightMode);
            tonearmView.setPlaying(isPlaying);
            tonearmView.refreshAngle();
            callback.markTonearmNeedsUpdate();
        }
    }

    /** 唱片机模式：退出时关闭黑胶 + 隐藏唱臂 */
    public void recordOnStyleExit() {
        coverView.setVinylMode(false);
        if (tonearmView != null) {
            tonearmView.setVisibility(View.GONE);
        }
    }
}
