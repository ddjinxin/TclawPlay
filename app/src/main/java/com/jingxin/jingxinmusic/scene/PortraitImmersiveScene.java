package com.jingxin.jingxinmusic.scene;

import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.jingxin.jingxinmusic.R;

/**
 * 竖屏沉浸模式
 * - 封面：原图铺背景（blurBackground 用 CENTER_CROP），旋转封面隐藏
 * - 遮罩：immersiveOverlay 渐变覆盖上半部
 * - 歌名歌手：推到遮罩区（43%高度位置）
 * - info_panel：全宽
 * - 频谱高度：10%
 */
public class PortraitImmersiveScene implements CoverScene {

    private final CoverSceneHelper h;

    public PortraitImmersiveScene(CoverSceneHelper helper) {
        this.h = helper;
    }

    @Override
    public void enter() {
        // 沉浸遮罩
        h.immersiveOverlay.setLandscapeMode(false);
        h.immersiveOverlay.setNightMode(h.isNightMode);
        h.immersiveOverlay.setVisibility(View.VISIBLE);
        // 隐藏非沉浸遮罩
        h.overlayView.setVisibility(View.GONE);
        h.whiteOverlay.setVisibility(View.GONE);
        // 隐藏封面占位
        h.coverPlaceholder.setVisibility(View.GONE);
        // 原图铺背景
        h.blurBackground.setVisibility(View.VISIBLE);
        h.blurBackground.setAlpha(1.0f);
        h.blurBackground.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        // 旋转封面隐藏
        h.coverView.setVisibility(View.GONE);
        h.coverView.setClipToOutline(true);
        h.coverView.setBackgroundResource(R.drawable.circle_cover_background);
        h.coverView.setForeground(null);
        // 夜间暗层
        h.immersiveDarkOverlay.setVisibility(h.isNightMode ? View.VISIBLE : View.GONE);
        // 恢复层级（immersiveOverlay 在 blurBackground 之上）
        h.restoreOverlayHierarchy();
        // 歌名歌手移到遮罩区
        h.tvSongName.setVisibility(View.VISIBLE);
        h.tvArtist.setVisibility(View.VISIBLE);
        boolean isFull = h.lyricView != null &&
                h.lyricView.getDisplayMode() == com.jingxin.jingxinmusic.view.LyricView.DisplayMode.FULL;
        if (isFull) {
            h.callback.resetLyricMargin();
        } else {
            h.callback.updateImmersiveLyricMargin(false);
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
        // immersiveOverlay 同步
        h.immersiveOverlay.setLandscapeMode(false);
        // 歌名歌手位置
        h.tvSongName.setGravity(Gravity.CENTER_HORIZONTAL);
        h.tvArtist.setGravity(Gravity.CENTER_HORIZONTAL);
        // 歌名字号：基于竖屏宽度独立计算，避免横屏→竖屏切换时LyricView尚未resize导致字号错误
        float songNameSize = Math.max(32f, Math.min(60f, width * 0.048f));
        h.tvSongName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, songNameSize);
        h.tvArtist.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, songNameSize * 0.7f);
        // 歌名推到遮罩区（43% 高度）
        boolean isFull = h.lyricView != null &&
                h.lyricView.getDisplayMode() == com.jingxin.jingxinmusic.view.LyricView.DisplayMode.FULL;
        if (isFull) {
            h.callback.resetLyricMargin();
        } else {
            h.callback.updateImmersiveLyricMargin(false);
        }
        // 频谱高度
        h.spectrumView.getLayoutParams().height = (int) (height * getSpectrumHeightRatio());
    }

    @Override
    public void exit() {
        // 退出沉浸恢复经典
        h.immersiveOverlay.setVisibility(View.GONE);
        h.immersiveOverlay.setLandscapeMode(false);
        h.immersiveDarkOverlay.setVisibility(View.GONE);
        h.blurBackground.setVisibility(View.GONE);
        h.coverView.setVisibility(View.VISIBLE);
        // 恢复圆形裁剪
        h.coverView.setClipToOutline(true);
        h.coverView.setBackgroundResource(R.drawable.circle_cover_background);
        h.coverView.setForeground(null);
        // 恢复封面层级
        h.moveCoverAboveInfoPanel();
        // 恢复歌词 margin
        h.callback.resetLyricMargin();
        // 恢复封面占位
        h.callback.updateCoverPlaceholder();
        // 恢复非沉浸遮罩
        h.callback.updateThemeUI();
        // 恢复旋转
        if (h.isPlaying) {
            h.coverView.startRotation();
        }
    }

    @Override
    public void setCover(Bitmap bitmap) {
        // 竖屏沉浸：原图铺背景，CENTER_CROP 自适应缩放铺满
        h.blurBackground.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        h.blurBackground.setVisibility(View.VISIBLE);
        h.blurBackground.setAlpha(1.0f);
        h.blurBackground.setImageBitmap(bitmap);
        h.coverView.setVisibility(View.GONE);
        // 提取主色调
        h.callback.extractAndApplyDominantColor(bitmap);
    }

    @Override
    public void onLyricModeChanged(boolean isFullScreen) {
        h.immersiveOverlay.setVisibility(View.VISIBLE);
        h.overlayView.setVisibility(View.GONE);
        h.whiteOverlay.setVisibility(View.GONE);
        h.tvSongName.setVisibility(View.VISIBLE);
        h.tvArtist.setVisibility(View.VISIBLE);
        h.coverPlaceholder.setVisibility(View.GONE);
        h.coverView.setVisibility(View.GONE);
        if (isFullScreen) {
            h.immersiveOverlay.setFullScreenMode(true);
            h.callback.resetLyricMargin();
        } else {
            h.immersiveOverlay.setFullScreenMode(false);
            h.blurBackground.setAlpha(1.0f);
            h.callback.updateImmersiveLyricMargin(false);
        }
    }

    @Override
    public float getInfoPanelWidthRatio() {
        return 1.0f;
    }

    @Override
    public int getSongNameTopMarginDp() {
        return -1; // 竖屏沉浸使用屏幕43%计算，不用固定dp
    }

    @Override
    public float getSpectrumHeightRatio() {
        return 0.10f;
    }
}
