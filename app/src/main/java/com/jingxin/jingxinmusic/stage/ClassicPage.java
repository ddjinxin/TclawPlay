package com.jingxin.jingxinmusic.stage;

import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.jingxin.jingxinmusic.view.LyricView;

/**
 * 经典风格页面（组件化第6步）
 *
 * 合并旧 PortraitClassicScene + LandscapeClassicScene：
 * - 竖屏：封面顶部居中（H25%+5.6%顶距）+ info_panel 全宽 + 占位撑开
 * - 横屏：左65%信息区 + 右35%封面区 + 歌名 52dp 顶距（顶栏高+8dp）
 * - 封面旋转、全屏歌词显隐、播放态联动均按旧逻辑迁移
 *
 * 与轮播页不同：经典页不借用底部三件（组件留在旧 info_panel 流，
 * 只做可见性/尺寸手术，与旧 Scene 行为字节级对齐）。
 * 底槽（皮肤宿主）暂沿用 rootLayout 背景层，XML 三槽留待步骤7统一。
 *
 * 唱片机页（RecordPage）继承本类，覆盖黑胶/唱臂逻辑。
 */
public class ClassicPage extends StagePage {

    public ClassicPage(com.jingxin.jingxinmusic.scene.CoverSceneHelper helper) {
        super(helper);
    }

    // ===== 布局所有权：经典页复用旧 info_panel 流（过渡期），无需 install =====

    @Override
    public void install(LayoutInflater inflater, ViewGroup host) {
        // 无自建视图：全部沿旧路径（install 幂等空实现，保持生命周期协议完整）
    }

    @Override
    public void enter() {
        boolean landscape = h.isLandscapeMode;
        h.hideImmersiveViews();
        h.overlayView.setVisibility(View.GONE);
        // 非沉浸遮罩（双色光晕+非沉浸时跳过，由 updateThemeUI 统一管理）
        if (!(h.bgSkinMode == 1 && h.coverStyle != h.COVER_STYLE_IMMERSIVE)) {
            h.whiteOverlay.setVisibility(View.VISIBLE);
            h.whiteOverlay.setAlpha(0.7f);
        }
        // 封面
        h.coverView.setVisibility(View.VISIBLE);
        setupCoverStyle();
        // 封面占位（竖屏需要，横屏不需要）
        boolean isFullLyric = h.lyricView != null
                && h.lyricView.getDisplayMode() == LyricView.DisplayMode.FULL;
        if (landscape) {
            h.coverPlaceholder.setVisibility(View.GONE);
        } else if (!isFullLyric) {
            h.callback.updateCoverPlaceholder();
        } else {
            h.coverPlaceholder.setVisibility(View.GONE);
        }
        // 歌名歌手
        h.tvSongName.setVisibility(View.VISIBLE);
        h.tvArtist.setVisibility(View.VISIBLE);
        h.callback.resetLyricMargin();
        // 封面层级恢复到 infoPanel 上方
        h.moveCoverAboveInfoPanel();
        // 主题与旋转恢复 + 差异化风格视觉（唱片机：黑胶+唱臂）
        h.callback.updateThemeUI();
        onStyleEnterView();
        if (h.isPlaying) {
            h.coverView.startRotation();
        }
    }

    /** 风格进入的差异化视觉（唱片机覆盖：黑胶+唱臂） */
    protected void onStyleEnterView() {
        // 经典无额外元素
    }

    /** 封面裁剪样式（唱片机覆盖：不裁剪） */
    protected void setupCoverStyle() {
        h.applyCircleCoverStyle();
    }

    @Override
    public void exit() {
        exitView(); // 差异化退出（唱片机：黑胶复位/唱臂归位）
    }

    /** 退出差异化视觉（唱片机覆盖：recordOnStyleExit） */
    protected void exitView() {
    }

    @Override
    public void layout(int width, int height) {
        boolean landscape = h.isLandscapeMode;
        h.applyButtonMargins(height, width, landscape);
        if (landscape) {
            layoutLandscape(width, height);
        } else {
            layoutPortrait(width, height);
        }
        // 频谱位置：圆环覆盖封面/非圆环底部（圆心由两分支各自计算后传入）
        h.applyProgressScaling(height, width);
        onLayoutTonearm(height, width);
    }

    private void layoutPortrait(int width, int height) {
        h.setLayoutInfoPanelFullWidth();
        // 歌名 topMargin = 屏高×1.6%
        LinearLayout.LayoutParams nameParams =
                (LinearLayout.LayoutParams) h.tvSongName.getLayoutParams();
        nameParams.topMargin = (int) (height * 0.016f);
        h.tvSongName.setLayoutParams(nameParams);
        centerTitles();
        float songNameSize = width * 0.048f;
        applyTitleSize(songNameSize);

        // 封面：顶部居中，25%屏高
        int coverSize = (int) (height * 0.25f);
        int coverMarginTop = (int) (height * 0.056f) + h.systemTopInset;
        FrameLayout.LayoutParams coverParams = new FrameLayout.LayoutParams(coverSize, coverSize);
        coverParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
        coverParams.topMargin = coverMarginTop;
        h.coverView.setLayoutParams(coverParams);

        // 封面占位（全屏歌词时藏）
        boolean isFullLyric = h.lyricView != null
                && h.lyricView.getDisplayMode() == LyricView.DisplayMode.FULL;
        if (!isFullLyric) {
            h.callback.updateCoverPlaceholder();
        } else {
            h.coverPlaceholder.setVisibility(View.GONE);
            h.coverView.setVisibility(View.GONE);
        }
        // 频谱位置（圆环覆盖封面用圆心）
        int coverCenterX = width / 2;
        int coverCenterY = coverMarginTop + coverSize / 2;
        h.applySpectrumPosition(false, coverCenterX, coverCenterY, coverSize, height, getSpectrumHeightRatio());
    }

    private void layoutLandscape(int width, int height) {
        h.layoutLandscapeBase(width, height);
        // 封面：右35%面板居中
        int rightPanelWidth = (int) (width * 0.35f);
        int coverSize = (int) (rightPanelWidth * 0.70f);
        FrameLayout.LayoutParams coverParams = new FrameLayout.LayoutParams(coverSize, coverSize);
        coverParams.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        int horizontalPadding = (rightPanelWidth - coverSize) / 2;
        coverParams.rightMargin = horizontalPadding;
        coverParams.leftMargin = 0;
        h.coverView.setLayoutParams(coverParams);
        // 频谱
        int coverCenterX = width - rightPanelWidth / 2;
        int coverCenterY = height / 2;
        h.applySpectrumPosition(false, coverCenterX, coverCenterY, coverSize, height, getSpectrumHeightRatio());
        // 横屏不需要占位
        h.coverPlaceholder.setVisibility(View.GONE);
    }

    /** 歌名/歌手居中（字号设置与方向相关，各分支自行调用） */
    private void centerTitles() {
        h.tvSongName.setGravity(Gravity.CENTER_HORIZONTAL);
        h.tvArtist.setGravity(Gravity.CENTER_HORIZONTAL);
    }

    private void applyTitleSize(float songNameSize) {
        h.tvSongName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, songNameSize);
        h.tvArtist.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, songNameSize * 0.7f);
    }

    /** 唱臂布局（唱片机覆盖） */
    protected void onLayoutTonearm(int width, int height) {
        // 经典无唱臂
    }

    @Override
    public void onLyricModeChanged(boolean isFullScreen) {
        if (h.isLandscapeMode) {
            // 横屏全屏歌词：封面保持显示，歌名歌手隐藏
            h.coverView.setVisibility(View.VISIBLE);
            h.tvSongName.setVisibility(View.GONE);
            h.tvArtist.setVisibility(View.GONE);
        } else if (isFullScreen) {
            // 竖屏全屏歌词：隐藏封面/歌名/歌手/圆环频谱
            h.coverView.setVisibility(View.GONE);
            h.tvSongName.setVisibility(View.GONE);
            h.tvArtist.setVisibility(View.GONE);
            h.coverPlaceholder.setVisibility(View.GONE);
            if (h.spectrumView != null && h.spectrumView.isCoverOverlayMode()) {
                h.spectrumView.setVisibility(View.GONE);
            }
        } else {
            h.coverView.setVisibility(View.VISIBLE);
            h.tvSongName.setVisibility(View.VISIBLE);
            h.tvArtist.setVisibility(View.VISIBLE);
            h.callback.updateCoverPlaceholder();
            if (h.spectrumView != null && h.spectrumView.isCoverOverlayMode()) {
                h.spectrumView.setVisibility(View.VISIBLE);
            }
        }
        if (!h.isLandscapeMode) {
            h.callback.updateCoverPlaceholder();
        }
        h.callback.resetLyricMargin();
    }

    // ===== 旧 Scene 兼容 =====

    @Override
    public void onPlayingStateChanged(boolean isPlaying) {
        if (isPlaying) {
            h.coverView.startRotation();
        } else {
            h.coverView.stopRotation();
        }
    }

    @Override
    public void onServiceResumed(boolean isPlaying) {
        onPlayingStateChanged(isPlaying);
    }

    @Override
    public boolean needsReloadCover() {
        return false;
    }

    @Override
    public float getInfoPanelWidthRatio() {
        return h.isLandscapeMode ? 0.65f : 1.0f;
    }

    @Override
    public float getSpectrumHeightRatio() {
        return 0.10f;
    }
}
