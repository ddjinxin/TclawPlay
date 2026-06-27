package com.jingxin.jingxinmusic.scene;

import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.jingxin.jingxinmusic.R;

/**
 * 横屏唱片机模式
 * - 布局：左65%信息区 + 右35%封面区
 * - 封面：黑胶唱片效果（PonyMusic预制位图），旋转
 * - 唱臂：封面顶部居中，播放0°垂直下落，停止60°抬起
 * - 歌名歌手：左面板顶部（52dp）
 * - 频谱高度：10%
 */
public class LandscapeRecordScene implements CoverScene {

    private final CoverSceneHelper h;

    public LandscapeRecordScene(CoverSceneHelper helper) {
        this.h = helper;
    }

    @Override
    public void enter() {
        // 隐藏沉浸相关
        h.immersiveOverlay.setVisibility(View.GONE);
        h.immersiveDarkOverlay.setVisibility(View.GONE);
        if (h.landscapeGradientOverlay != null && h.landscapeGradientOverlay.getParent() != null) {
            h.rootLayout.removeView(h.landscapeGradientOverlay);
        }
        // 非沉浸遮罩
        h.callback.updateThemeUI();
        // 封面：黑胶模式，不裁剪，无圆形边框
        h.coverView.setVisibility(View.VISIBLE);
        h.coverView.setClipToOutline(false);
        h.coverView.setBackground(null);
        h.coverView.setForeground(null);
        // 不需要封面占位
        h.coverPlaceholder.setVisibility(View.GONE);
        // 歌名歌手
        h.tvSongName.setVisibility(View.VISIBLE);
        h.tvArtist.setVisibility(View.VISIBLE);
        h.callback.resetLyricMargin();
        // 恢复封面层级到 infoPanel 上方
        h.moveCoverAboveInfoPanel();
        // 恢复旋转
        if (h.isPlaying) {
            h.coverView.startRotation();
        }
    }

    @Override
    public void layout(int width, int height) {
        // 顶部/底部按钮间距按可用高度比例
        h.applyButtonMargins(height, width, true);
        // 横屏公共布局
        h.layoutLandscapeBase(width);

        // 封面布局：右35%面板居中
        int rightPanelWidth = (int) (width * 0.35f);
        int coverSize = (int) (rightPanelWidth * 0.70f);
        FrameLayout.LayoutParams coverParams =
                new FrameLayout.LayoutParams(coverSize, coverSize);
        coverParams.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        int horizontalPadding = (rightPanelWidth - coverSize) / 2;
        coverParams.rightMargin = horizontalPadding;
        coverParams.leftMargin = 0;
        h.coverView.setLayoutParams(coverParams);

        // 频谱位置：圆环模式覆盖封面，非圆环模式在底部
        int coverCenterX = width - rightPanelWidth / 2;
        int coverCenterY = height / 2;
        h.applySpectrumPosition(false, coverCenterX, coverCenterY, coverSize, height, getSpectrumHeightRatio());

        // 横屏不需要封面占位
        h.coverPlaceholder.setVisibility(View.GONE);

        // 唱臂位置更新
        if (h.tonearmView != null) {
            h.tonearmView.setLandscapeMode(true);
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
        h.applyClassicCover(bitmap, true);
    }

    @Override
    public void onLyricModeChanged(boolean isFullScreen) {
        if (isFullScreen) {
            h.coverView.setVisibility(View.VISIBLE);
            h.tvSongName.setVisibility(View.GONE);
            h.tvArtist.setVisibility(View.GONE);
        } else {
            h.coverView.setVisibility(View.VISIBLE);
            h.tvSongName.setVisibility(View.VISIBLE);
            h.tvArtist.setVisibility(View.VISIBLE);
        }
        h.callback.resetLyricMargin();
    }

    @Override
    public float getInfoPanelWidthRatio() {
        return 0.65f;
    }

    @Override
    public int getSongNameTopMarginDp() {
        return 52;
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
