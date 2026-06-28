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
 *
 * 唱片机模式（PortraitRecordScene）继承此类，覆盖黑胶/唱臂相关逻辑
 */
public class PortraitClassicScene implements CoverScene {

    protected final CoverSceneHelper h;

    public PortraitClassicScene(CoverSceneHelper helper) {
        this.h = helper;
    }

    @Override
    public void enter() {
        // 隐藏沉浸相关 View
        h.hideImmersiveViews();
        // 显示非沉浸遮罩
        h.overlayView.setVisibility(View.GONE);
        h.whiteOverlay.setVisibility(View.VISIBLE);
        h.whiteOverlay.setAlpha(0.4f);
        // 封面
        h.coverView.setVisibility(View.VISIBLE);
        setupCoverStyle();
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

    /**
     * 设置封面裁剪样式，子类可覆盖（唱片机模式不裁剪）
     */
    protected void setupCoverStyle() {
        h.coverView.setClipToOutline(true);
        h.coverView.setBackgroundResource(R.drawable.circle_cover_background);
        h.coverView.setForeground(null);
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
        // 歌名字号
        float songNameSize = Math.max(32f, Math.min(50f, width * 0.048f));
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
        // 频谱位置
        int coverCenterX = width / 2;
        int coverCenterY = coverMarginTop + coverSize / 2;
        h.applySpectrumPosition(false, coverCenterX, coverCenterY, coverSize, height, getSpectrumHeightRatio());
        // 唱臂位置（唱片机子类覆盖此方法添加唱臂逻辑）
        onLayoutTonearm();
    }

    /**
     * 布局唱臂，唱片机子类覆盖
     */
    protected void onLayoutTonearm() {
        // 经典模式无唱臂
    }

    @Override
    public void exit() {
        // 经典模式无额外退出逻辑
    }

    @Override
    public void setCover(Bitmap bitmap) {
        h.applyClassicCover(bitmap, false);
    }

    @Override
    public void onLyricModeChanged(boolean isFullScreen) {
        if (isFullScreen) {
            // 竖屏全屏歌词：隐藏封面，歌名歌手，圆环类频谱
            h.coverView.setVisibility(View.GONE);
            h.tvSongName.setVisibility(View.GONE);
            h.tvArtist.setVisibility(View.GONE);
            h.coverPlaceholder.setVisibility(View.GONE);
            if (h.spectrumView != null && h.spectrumView.isCoverOverlayMode()) {
                h.spectrumView.setVisibility(View.GONE);
            }
        } else {
            // 恢复
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
        if (isPlaying) {
            h.coverView.startRotation();
        } else {
            h.coverView.stopRotation();
        }
    }

    @Override
    public void onServiceResumed(boolean isPlaying) {
        // 经典竖屏无特殊恢复逻辑
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
        return false;
    }

    @Override
    public void onStyleEnter() {
        // 经典模式无额外初始化
    }

    @Override
    public void onStyleExit() {
        // 经典模式无额外清理
    }
}
