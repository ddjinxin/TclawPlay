package com.jingxin.jingxinmusic.scene;

import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.jingxin.jingxinmusic.R;

/**
 * 横屏经典模式
 * - 布局：左65%信息区 + 右35%封面区
 * - 封面：圆形旋转，右侧面板居中
 * - 歌名歌手：左面板顶部（52dp），字号随歌词动态调整
 * - 频谱高度：8%
 */
public class LandscapeClassicScene implements CoverScene {

    private final CoverSceneHelper h;

    public LandscapeClassicScene(CoverSceneHelper helper) {
        this.h = helper;
    }

    @Override
    public void enter() {
        // 隐藏沉浸相关
        h.immersiveOverlay.setVisibility(View.GONE);
        h.immersiveDarkOverlay.setVisibility(View.GONE);
        if (h.landscapeGradientOverlay != null) {
            h.landscapeGradientOverlay.setVisibility(View.GONE);
        }
        // 非沉浸遮罩
        h.callback.updateThemeUI();
        // 封面显示
        h.coverView.setVisibility(View.VISIBLE);
        h.coverView.setClipToOutline(true);
        h.coverView.setBackgroundResource(R.drawable.circle_cover_background);
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
        h.applyButtonMargins(height);
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

        // 频谱高度
        h.spectrumView.getLayoutParams().height = (int) (height * getSpectrumHeightRatio());

        // 横屏不需要封面占位
        h.coverPlaceholder.setVisibility(View.GONE);
    }

    @Override
    public void exit() {
        // 横屏经典切走时，恢复竖屏经典相关设置
    }

    @Override
    public void setCover(Bitmap bitmap) {
        h.applyClassicCover(bitmap, true);
    }

    @Override
    public void onLyricModeChanged(boolean isFullScreen) {
        if (isFullScreen) {
            // 横屏全屏歌词：封面保持显示，歌名歌手隐藏
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
}
