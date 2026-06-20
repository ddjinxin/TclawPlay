package com.jingxin.jingxinmusic.scene;

import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.jingxin.jingxinmusic.R;

/**
 * 竖屏经典模式
 * - 封面：顶部居中圆形旋转封面
 * - 背景：模糊背景 + 白天渐变遮罩
 * - 歌名歌手：封面下方居中
 * - info_panel：全宽
 * - 频谱高度：10%
 */
public class PortraitClassicScene implements CoverScene {

    private final CoverSceneHelper h;

    public PortraitClassicScene(CoverSceneHelper helper) {
        this.h = helper;
    }

    @Override
    public void enter() {
        // 隐藏沉浸相关 View
        h.immersiveOverlay.setVisibility(View.GONE);
        h.immersiveDarkOverlay.setVisibility(View.GONE);
        if (h.landscapeGradientOverlay != null) {
            h.landscapeGradientOverlay.setVisibility(View.GONE);
        }
        // 显示非沉浸遮罩
        h.overlayView.setVisibility(View.GONE);
        h.whiteOverlay.setVisibility(View.VISIBLE);
        h.whiteOverlay.setAlpha(0.4f);
        // 封面：圆形旋转
        h.coverView.setVisibility(View.VISIBLE);
        h.coverView.setClipToOutline(true);
        h.coverView.setBackgroundResource(R.drawable.circle_cover_background);
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
        h.applyButtonMargins(height);
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
        // 歌名字号：基于竖屏宽度独立计算，避免横屏→竖屏切换时LyricView尚未resize导致字号错误
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
    }

    @Override
    public void exit() {
        // 竖屏经典是默认模式，exit 不需要特别处理
    }

    @Override
    public void setCover(Bitmap bitmap) {
        h.applyClassicCover(bitmap, false);
    }

    @Override
    public void onLyricModeChanged(boolean isFullScreen) {
        if (isFullScreen) {
            // 竖屏全屏歌词：隐藏封面，歌名歌手
            h.coverView.setVisibility(View.GONE);
            h.tvSongName.setVisibility(View.GONE);
            h.tvArtist.setVisibility(View.GONE);
            h.coverPlaceholder.setVisibility(View.GONE);
        } else {
            // 恢复
            h.coverView.setVisibility(View.VISIBLE);
            h.tvSongName.setVisibility(View.VISIBLE);
            h.tvArtist.setVisibility(View.VISIBLE);
            h.callback.updateCoverPlaceholder();
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
}
