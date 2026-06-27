package com.jingxin.jingxinmusic.scene;

import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.jingxin.jingxinmusic.R;

/**
 * 竖屏唱片机模式
 * - 封面：黑胶唱片效果（PonyMusic预制位图），旋转
 * - 背景：模糊背景 + 白天渐变遮罩
 * - 唱臂：封面右侧，暂停0°垂直，播放45°落入黑胶
 * - 歌名歌手：封面下方居中
 * - info_panel：全宽
 * - 频谱高度：10%
 */
public class PortraitRecordScene implements CoverScene {

    private final CoverSceneHelper h;

    public PortraitRecordScene(CoverSceneHelper helper) {
        this.h = helper;
    }

    @Override
    public void enter() {
        // 隐藏沉浸相关 View
        h.immersiveOverlay.setVisibility(View.GONE);
        h.immersiveDarkOverlay.setVisibility(View.GONE);
        if (h.landscapeGradientOverlay != null && h.landscapeGradientOverlay.getParent() != null) {
            h.rootLayout.removeView(h.landscapeGradientOverlay);
        }
        // 显示非沉浸遮罩
        h.overlayView.setVisibility(View.GONE);
        h.whiteOverlay.setVisibility(View.VISIBLE);
        h.whiteOverlay.setAlpha(0.4f);
        // 封面：黑胶模式，不裁剪，无圆形边框
        h.coverView.setVisibility(View.VISIBLE);
        h.coverView.setClipToOutline(false);
        h.coverView.setBackground(null);
        h.coverView.setForeground(null);
        // 封面占位
        boolean isFullLyric = h.lyricView != null &&
                h.lyricView.getDisplayMode() == com.jingxin.jingxinmusic.view.LyricView.DisplayMode.FULL;
        if (!isFullLyric) {
            h.callback.updateCoverPlaceholder();
        } else {
            h.coverPlaceholder.setVisibility(View.GONE);
        }
        // 歌名歌手
        h.tvSongName.setVisibility(View.VISIBLE);
        h.tvArtist.setVisibility(View.VISIBLE);
        h.callback.resetLyricMargin();
        // 恢复封面层级到 infoPanel 上方
        h.moveCoverAboveInfoPanel();
        // 恢复主题
        h.callback.updateThemeUI();
        // 恢复旋转
        if (h.isPlaying) {
            h.coverView.startRotation();
        }
    }

    @Override
    public void layout(int width, int height) {
        // 顶部/底部按钮间距按可用高度比例
        h.applyButtonMargins(height, width, false);
        // info_panel 全宽
        FrameLayout.LayoutParams infoParams =
                (FrameLayout.LayoutParams) h.infoPanel.getLayoutParams();
        infoParams.width = FrameLayout.LayoutParams.MATCH_PARENT;
        infoParams.height = FrameLayout.LayoutParams.MATCH_PARENT;
        infoParams.gravity = Gravity.START;
        h.infoPanel.setLayoutParams(infoParams);
        if (h.infoPanel instanceof LinearLayout) {
            ((LinearLayout) h.infoPanel).setGravity(Gravity.CENTER_HORIZONTAL);
        }
        // 歌名 topMargin = 16dp
        LinearLayout.LayoutParams nameParams =
                (LinearLayout.LayoutParams) h.tvSongName.getLayoutParams();
        nameParams.topMargin = (int) (h.density * 16);
        h.tvSongName.setLayoutParams(nameParams);
        h.tvSongName.setGravity(Gravity.CENTER_HORIZONTAL);
        h.tvArtist.setGravity(Gravity.CENTER_HORIZONTAL);
        // 歌名字号：基于竖屏宽度独立计算
        float songNameSize = Math.max(32f, Math.min(60f, width * 0.048f));
        h.tvSongName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, songNameSize);
        h.tvArtist.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, songNameSize * 0.7f);
        // 封面：顶部居中，25%屏幕高度
        int coverSize = (int) (height * 0.25f);
        int coverMarginTop = (int) (h.density * 56);
        FrameLayout.LayoutParams coverParams =
                new FrameLayout.LayoutParams(coverSize, coverSize);
        coverParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
        coverParams.topMargin = coverMarginTop;
        h.coverView.setLayoutParams(coverParams);
        // 封面占位
        boolean isFullLyric = h.lyricView != null &&
                h.lyricView.getDisplayMode() == com.jingxin.jingxinmusic.view.LyricView.DisplayMode.FULL;
        if (!isFullLyric) {
            h.callback.updateCoverPlaceholder();
        } else {
            h.coverPlaceholder.setVisibility(View.GONE);
            h.coverView.setVisibility(View.GONE);
        }
        // 频谱位置：圆环模式覆盖封面，非圆环模式在底部
        int coverCenterX = width / 2;
        int coverCenterY = coverMarginTop + coverSize / 2;
        h.applySpectrumPosition(false, coverCenterX, coverCenterY, coverSize, height, getSpectrumHeightRatio());
        // 唱臂位置更新
        if (h.tonearmView != null) {
            h.tonearmView.setLandscapeMode(false);
            h.callback.updateTonearmPosition();
            h.tonearmView.refreshAngle();
        }
    }

    @Override
    public void exit() {
        // 隐藏唱臂
        if (h.tonearmView != null) {
            h.tonearmView.setVisibility(View.GONE);
        }
    }

    @Override
    public void setCover(Bitmap bitmap) {
        h.applyClassicCover(bitmap, false);
    }

    @Override
    public void onLyricModeChanged(boolean isFullScreen) {
        if (isFullScreen) {
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
        // 唱片机：唱臂动画 + 封面旋转
        if (h.tonearmView != null) {
            h.tonearmView.setPlaying(isPlaying);
        }
        if (isPlaying) {
            h.coverView.startRotation();
        } else {
            h.coverView.stopRotation();
        }
    }

    @Override
    public void onServiceResumed(boolean isPlaying) {
        // 从 mini 播放条恢复：同步唱臂状态和位置
        if (h.tonearmView != null) {
            h.tonearmView.setLandscapeMode(h.isLandscapeMode);
            h.tonearmView.setPlaying(isPlaying);
            h.tonearmView.refreshAngle();
            h.callback.updateTonearmPosition();
        }
    }

    @Override
    public boolean shouldShowSpectrumButton(int spectrumStyle) {
        // 唱片机模式所有频谱样式都可用
        return true;
    }

    @Override
    public boolean shouldRotateCover() {
        return true;
    }

    @Override
    public boolean needsReloadCover() {
        return true;
    }

    @Override
    public void onStyleEnter() {
        // 进入唱片机模式：启用黑胶 + 显示唱臂
        h.coverView.setVinylMode(true);
        if (h.tonearmView != null) {
            h.tonearmView.setVisibility(View.VISIBLE);
            h.tonearmView.setLandscapeMode(h.isLandscapeMode);
            h.tonearmView.setNightMode(h.isNightMode);
            boolean isCurrentlyPlaying = h.isPlaying;
            h.tonearmView.setPlaying(isCurrentlyPlaying);
            h.tonearmView.refreshAngle();
            h.callback.updateTonearmPosition();
        }
    }

    @Override
    public void onStyleExit() {
        // 退出唱片机模式：关闭黑胶 + 隐藏唱臂
        h.coverView.setVinylMode(false);
        if (h.tonearmView != null) {
            h.tonearmView.setVisibility(View.GONE);
        }
    }
}
