package com.jingxin.jingxinmusic.scene;

import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/**
 * 竖屏多封面轮播模式
 * - 封面：5张卡片叠加，中间最大，两侧递减，可滑动翻页
 * - 横竖屏结构统一：封面在上方，info_panel 全宽
 * - 歌词：仅双行/多行，无全屏模式
 * - 频谱：仅底部柱状/波浪，禁用圆形频谱
 */
public class PortraitCarouselScene extends AbstractCarouselScene {

    public PortraitCarouselScene(CoverSceneHelper helper) {
        super(helper);
    }

    @Override
    public void enter() {
        // 隐藏沉浸相关 View
        h.hideImmersiveViews();
        // 非沉浸遮罩
        h.overlayView.setVisibility(View.GONE);
        h.whiteOverlay.setVisibility(View.VISIBLE);
        h.whiteOverlay.setAlpha(0.7f);
        // 关键：rootLayout 不裁剪子View，叠加溢出可见
        h.rootLayout.setClipChildren(false);
        h.rootLayout.setClipToPadding(false);
        // 隐藏旋转封面，显示轮播封面
        h.coverView.setVisibility(View.GONE);
        h.ensureCarouselView();
        h.carouselView.setVisibility(View.VISIBLE);
        // 显示封面占位（轮播模式需要占位撑开歌名位置）
        h.coverPlaceholder.setVisibility(View.VISIBLE);
        // 歌名歌手
        h.tvSongName.setVisibility(View.VISIBLE);
        h.tvArtist.setVisibility(View.VISIBLE);
        h.callback.resetLyricMargin();
        // 恢复封面层级（carousel 在 infoPanel 上方）
        h.moveCarouselAboveInfoPanel();
        // 恢复主题
        h.callback.updateThemeUI();
        // 竖屏轮播：恢复歌词显示上一行（三行模式），确保不为全屏
        if (h.lyricView != null) {
            h.lyricView.setShowPrevLine(true);
            com.jingxin.jingxinmusic.view.LyricView.DisplayMode mode = h.lyricView.getDisplayMode();
            if (mode == com.jingxin.jingxinmusic.view.LyricView.DisplayMode.FULL) {
                h.lyricView.setDisplayMode(com.jingxin.jingxinmusic.view.LyricView.DisplayMode.MULTI_LINE);
                h.callback.resetLyricMargin();
            }
        }
        // 启动中间封面轻微晃动
        h.carouselView.startSwayAnimation();
    }

    @Override
    public void layout(int width, int height) {
        // 顶部/底部按钮间距
        h.applyButtonMargins(height, width, false);
        // info_panel 全宽（轮播模式横竖屏统一）
        h.setLayoutInfoPanelFullWidth();
        // 歌名 topMargin = 26dp
        LinearLayout.LayoutParams nameParams =
                (LinearLayout.LayoutParams) h.tvSongName.getLayoutParams();
        nameParams.topMargin = (int) (h.density * 26);
        h.tvSongName.setLayoutParams(nameParams);
        h.tvSongName.setGravity(Gravity.CENTER_HORIZONTAL);
        h.tvArtist.setGravity(Gravity.CENTER_HORIZONTAL);
        // 歌名字号
        float songNameSize = Math.max(32f, Math.min(50f, width * 0.048f));
        h.tvSongName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, songNameSize);
        h.tvArtist.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, songNameSize * 0.7f);
        // 轮播封面区域：在顶部按钮栏下方
        int topBarHeight = (h.topButtonsBar != null && h.topButtonsBar.getHeight() > 0)
                ? h.topButtonsBar.getHeight() : (int) (h.density * 56);
        // 中间卡片尺寸：和经典模式封面一致，height * 25%
        int coverSize = (int) (height * 0.25f);
        // 轮播容器高度 = coverSize + 上下边距
        int carouselHeight = coverSize + (int) (h.density * 16);
        int carouselTopMargin = h.systemTopInset + topBarHeight + (int) (h.density * 10);
        if (h.carouselView != null) {
            h.carouselView.setCoverSize(coverSize);
            h.carouselView.setOverlapRatio(0.70f);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) h.carouselView.getLayoutParams();
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT;
            lp.height = carouselHeight;
            lp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
            lp.topMargin = carouselTopMargin;
            h.carouselView.setLayoutParams(lp);
            // 通知 carouselView 重新布局卡片
            h.carouselView.requestLayoutCards();
        }
        // 封面占位：紧贴轮播容器底部，不留额外间距
        h.coverPlaceholder.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams placeholderParams =
                (LinearLayout.LayoutParams) h.coverPlaceholder.getLayoutParams();
        placeholderParams.height = carouselTopMargin + carouselHeight;
        placeholderParams.width = 1;
        h.coverPlaceholder.setLayoutParams(placeholderParams);
        // 频谱位置：仅底部，不用圆形
        if (h.spectrumView != null && h.spectrumView.getParent() == h.rootLayout) {
            h.moveSpectrumToBottom();
        }
        if (h.spectrumView != null) {
            h.spectrumView.getLayoutParams().height = (int) (height * getSpectrumHeightRatio());
        }
    }

    @Override
    public void onLyricModeChanged(boolean isFullScreen) {
        // 轮播竖屏：不支持全屏歌词，只在双行和多行之间切换
        h.tvSongName.setVisibility(View.VISIBLE);
        h.tvArtist.setVisibility(View.VISIBLE);
        if (isFullScreen) {
            // 拒绝全屏，降级为多行
            if (h.lyricView != null) {
                h.lyricView.setDisplayMode(com.jingxin.jingxinmusic.view.LyricView.DisplayMode.MULTI_LINE);
            }
            h.callback.resetLyricMargin();
        } else {
            h.callback.resetLyricMargin();
        }
    }

    @Override
    public float getSpectrumHeightRatio() {
        return 0.10f;
    }
}
