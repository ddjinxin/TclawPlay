package com.jingxin.jingxinmusic.scene;

import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.jingxin.jingxinmusic.view.CoverCarouselAdapter;
import com.jingxin.jingxinmusic.view.CoverCarouselView;

/**
 * 横屏多封面轮播模式
 * - 与竖屏结构完全一致：封面在上方，info_panel 全宽
 * - 仅尺寸参数不同（卡片更大）
 * - 歌词：仅双行/多行，无全屏模式
 * - 频谱：仅底部柱状/波浪，禁用圆形频谱
 */
public class LandscapeCarouselScene implements CoverScene {

    private final CoverSceneHelper h;

    public LandscapeCarouselScene(CoverSceneHelper helper) {
        this.h = helper;
    }

    @Override
    public void enter() {
        // 隐藏沉浸相关 View
        h.hideImmersiveViews();
        // 非沉浸遮罩
        h.callback.updateThemeUI();
        // 关键：rootLayout 不裁剪子View
        h.rootLayout.setClipChildren(false);
        h.rootLayout.setClipToPadding(false);
        // 隐藏旋转封面，显示轮播封面
        h.coverView.setVisibility(View.GONE);
        h.ensureCarouselView();
        h.carouselView.setVisibility(View.VISIBLE);
        // 立即设好 infoPanel 全宽（前一个模式可能是65%宽度，必须提前覆盖）
        FrameLayout.LayoutParams infoParams =
                (FrameLayout.LayoutParams) h.infoPanel.getLayoutParams();
        infoParams.width = FrameLayout.LayoutParams.MATCH_PARENT;
        infoParams.height = FrameLayout.LayoutParams.MATCH_PARENT;
        infoParams.gravity = Gravity.START;
        h.infoPanel.setLayoutParams(infoParams);
        if (h.infoPanel instanceof LinearLayout) {
            ((LinearLayout) h.infoPanel).setGravity(Gravity.CENTER_HORIZONTAL);
        }
        // 显示封面占位，并预设高度防止闪跳（精确值在 layout() 中更新）
        h.coverPlaceholder.setVisibility(View.VISIBLE);
        int estimatedTopBarHeight = (h.topButtonsBar != null && h.topButtonsBar.getHeight() > 0)
                ? h.topButtonsBar.getHeight() : (int) (h.density * 56);
        int estimatedCoverSize = (int) (h.rootLayout.getHeight() * 0.25f);
        if (estimatedCoverSize > 0) {
            LinearLayout.LayoutParams placeholderParams =
                    (LinearLayout.LayoutParams) h.coverPlaceholder.getLayoutParams();
            placeholderParams.height = estimatedTopBarHeight + estimatedCoverSize;
            placeholderParams.width = 1;
            h.coverPlaceholder.setLayoutParams(placeholderParams);
        }
        // 立即把歌名topMargin设为10dp（轮播靠coverPlaceholder撑位置）
        LinearLayout.LayoutParams nameParams =
                (LinearLayout.LayoutParams) h.tvSongName.getLayoutParams();
        nameParams.topMargin = (int) (h.density * 10);
        h.tvSongName.setLayoutParams(nameParams);
        // 歌名歌手
        h.tvSongName.setVisibility(View.VISIBLE);
        h.tvArtist.setVisibility(View.VISIBLE);
        h.callback.resetLyricMargin();
        // 恢复封面层级
        h.moveCarouselAboveInfoPanel();
        // 确保歌词为双行模式，且不显示上一行（真正双行，垂直居中）
        if (h.lyricView != null) {
            if (h.lyricView.getDisplayMode() != com.jingxin.jingxinmusic.view.LyricView.DisplayMode.DOUBLE_LINE) {
                h.lyricView.setDisplayMode(com.jingxin.jingxinmusic.view.LyricView.DisplayMode.DOUBLE_LINE);
            }
            h.lyricView.setShowPrevLine(false);
            h.callback.resetLyricMargin();
        }
        // 启动中间封面轻微晃动
        h.carouselView.startSwayAnimation();
    }

    @Override
    public void layout(int width, int height) {
        // 顶部/底部按钮间距
        h.applyButtonMargins(height, width, true);
        // info_panel 全宽（轮播模式横竖屏统一！）
        FrameLayout.LayoutParams infoParams =
                (FrameLayout.LayoutParams) h.infoPanel.getLayoutParams();
        infoParams.width = FrameLayout.LayoutParams.MATCH_PARENT;
        infoParams.height = FrameLayout.LayoutParams.MATCH_PARENT;
        infoParams.gravity = Gravity.START;
        h.infoPanel.setLayoutParams(infoParams);
        if (h.infoPanel instanceof LinearLayout) {
            ((LinearLayout) h.infoPanel).setGravity(Gravity.CENTER_HORIZONTAL);
        }
        // 歌名 topMargin = 10dp（与轮播封面保持间距）
        LinearLayout.LayoutParams nameParams =
                (LinearLayout.LayoutParams) h.tvSongName.getLayoutParams();
        nameParams.topMargin = (int) (h.density * 10);
        h.tvSongName.setLayoutParams(nameParams);
        h.tvSongName.setGravity(Gravity.CENTER_HORIZONTAL);
        h.tvArtist.setGravity(Gravity.CENTER_HORIZONTAL);
        // 歌名字号：和经典横屏一致，基于65%宽度计算
        float infoWidth = width * 0.65f;
        float songNameSize = Math.max(32f, Math.min(50f, infoWidth * 0.048f));
        h.tvSongName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, songNameSize);
        h.tvArtist.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, songNameSize * 0.7f);
        // 歌词宽度限制为65%屏幕宽，确保字号和经典横屏一致
        if (h.lyricView != null) {
            LinearLayout.LayoutParams lyricParams = (LinearLayout.LayoutParams) h.lyricView.getLayoutParams();
            lyricParams.width = (int) infoWidth;
            lyricParams.gravity = Gravity.CENTER_HORIZONTAL;
            h.lyricView.setLayoutParams(lyricParams);
        }
        // 轮播封面区域：在顶部按钮栏下方
        int topBarHeight = (h.topButtonsBar != null && h.topButtonsBar.getHeight() > 0)
                ? h.topButtonsBar.getHeight() : (int) (h.density * 56);
        // 中间卡片尺寸：height * 30%
        int coverSize = (int) (height * 0.30f);
        // 轮播容器高度 = coverSize（无多余边距）
        int carouselHeight = coverSize;
        if (h.carouselView != null) {
            h.carouselView.setCoverSize(coverSize);
            h.carouselView.setOverlapRatio(0.30f);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) h.carouselView.getLayoutParams();
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT;
            lp.height = carouselHeight;
            lp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
            lp.topMargin = topBarHeight;
            h.carouselView.setLayoutParams(lp);
            // 通知 carouselView 重新布局卡片
            h.carouselView.requestLayoutCards();
        }
        // 封面占位：需要包含topBarHeight，才能把歌名推到封面下方
        h.coverPlaceholder.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams placeholderParams =
                (LinearLayout.LayoutParams) h.coverPlaceholder.getLayoutParams();
        placeholderParams.height = topBarHeight + carouselHeight;
        placeholderParams.width = 1;
        h.coverPlaceholder.setLayoutParams(placeholderParams);
        // 频谱位置：仅底部
        if (h.spectrumView != null && h.spectrumView.getParent() == h.rootLayout) {
            h.moveSpectrumToBottom();
        }
        if (h.spectrumView != null) {
            h.spectrumView.getLayoutParams().height = (int) (height * getSpectrumHeightRatio());
        }
    }

    @Override
    public void exit() {
        if (h.carouselView != null) {
            h.carouselView.stopSwayAnimation();
            h.carouselView.setVisibility(View.GONE);
        }
        h.rootLayout.setClipChildren(true);
        // 恢复歌词默认状态
        if (h.lyricView != null) {
            h.lyricView.setShowPrevLine(true);
        }
    }

    @Override
    public void setCover(Bitmap bitmap) {
        h.applyBlurBackground(bitmap);
    }

    @Override
    public void onLyricModeChanged(boolean isFullScreen) {
        // 横屏轮播：歌词锁定双行，禁止切换
        h.tvSongName.setVisibility(View.VISIBLE);
        h.tvArtist.setVisibility(View.VISIBLE);
        if (h.lyricView != null && h.lyricView.getDisplayMode() != com.jingxin.jingxinmusic.view.LyricView.DisplayMode.DOUBLE_LINE) {
            h.lyricView.setDisplayMode(com.jingxin.jingxinmusic.view.LyricView.DisplayMode.DOUBLE_LINE);
        }
        h.callback.resetLyricMargin();
    }

    @Override
    public float getInfoPanelWidthRatio() {
        return 1.0f;
    }

    @Override
    public int getSongNameTopMarginDp() {
        return 16;
    }

    @Override
    public float getSpectrumHeightRatio() {
        return 0.10f;
    }

    @Override
    public void onPlayingStateChanged(boolean isPlaying) {
        // 轮播无旋转动画
    }

    @Override
    public void onServiceResumed(boolean isPlaying) {
        // 轮播无特殊恢复逻辑
    }

    @Override
    public boolean shouldShowSpectrumButton(int spectrumStyle) {
        return !com.jingxin.jingxinmusic.view.SpectrumView.isOverlayStyle(spectrumStyle);
    }

    @Override
    public boolean shouldRotateCover() {
        return false;
    }

    @Override
    public boolean needsReloadCover() {
        return true;
    }

    @Override
    public void onStyleEnter() {
        h.ensureCarouselView();
        h.carouselView.setVisibility(View.VISIBLE);
        // 禁用圆形频谱
        if (h.spectrumView != null && h.spectrumView.isCoverOverlayMode()) {
            while (h.spectrumView.isCoverOverlayMode()) {
                h.spectrumView.switchStyle();
            }
        }
    }

    @Override
    public void onStyleExit() {
        if (h.carouselView != null) {
            h.carouselView.stopSwayAnimation();
            h.carouselView.setVisibility(View.GONE);
        }
    }
}
