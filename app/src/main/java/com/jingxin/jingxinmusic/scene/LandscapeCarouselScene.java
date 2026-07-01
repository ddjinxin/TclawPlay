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
        int estimatedTopBarHeight = Math.max(
                (h.topButtonsBar != null && h.topButtonsBar.getHeight() > 0)
                        ? h.topButtonsBar.getHeight() : (int) (h.density * 48),
                (int) (h.rootLayout.getHeight() * 0.06f));
        int estimatedCoverSize = (int) (h.rootLayout.getHeight() * 0.28f);
        if (estimatedCoverSize > 0) {
            LinearLayout.LayoutParams placeholderParams =
                    (LinearLayout.LayoutParams) h.coverPlaceholder.getLayoutParams();
            placeholderParams.height = estimatedTopBarHeight + estimatedCoverSize;
            placeholderParams.width = 1;
            h.coverPlaceholder.setLayoutParams(placeholderParams);
        }
        // 立即把歌名topMargin设为比例值（轮播靠coverPlaceholder撑位置）
        LinearLayout.LayoutParams nameParams =
                (LinearLayout.LayoutParams) h.tvSongName.getLayoutParams();
        nameParams.topMargin = (int) (h.rootLayout.getHeight() * 0.02f);
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
        // 顶部/底部按钮间距（比例分配，适配低高度横屏）
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
        // === 全比例高度分配（适配低高度横屏） ===
        // 顶部栏高度：6% 比例，但不小于实际按钮高度（避免遮挡）
        int actualTopBarHeight = (h.topButtonsBar != null && h.topButtonsBar.getHeight() > 0)
                ? h.topButtonsBar.getHeight() : (int) (h.density * 48);
        int topBarHeight = Math.max(actualTopBarHeight, (int) (height * 0.06f));
        // 歌名 topMargin：1.5% 比例
        int songNameMargin = Math.max(4, (int) (height * 0.015f));
        LinearLayout.LayoutParams nameParams =
                (LinearLayout.LayoutParams) h.tvSongName.getLayoutParams();
        nameParams.topMargin = songNameMargin;
        h.tvSongName.setLayoutParams(nameParams);
        h.tvSongName.setGravity(Gravity.CENTER_HORIZONTAL);
        h.tvArtist.setGravity(Gravity.CENTER_HORIZONTAL);
        // 歌手 topMargin：1% 比例
        if (h.tvArtist != null) {
            LinearLayout.LayoutParams artistParams =
                    (LinearLayout.LayoutParams) h.tvArtist.getLayoutParams();
            artistParams.topMargin = Math.max(2, (int) (height * 0.01f));
            h.tvArtist.setLayoutParams(artistParams);
        }
        // 歌名字号：基于65%宽度，同时受高度约束（横屏低高度时字号不能太大）
        float infoWidth = width * 0.65f;
        float songNameSizeByWidth = Math.max(32f, Math.min(50f, infoWidth * 0.048f));
        // 高度约束：歌名+歌手+间距合计不超过 H×8%
        // 歌名约占60%，歌手约占42%（0.7倍），间距约2.5%
        float songNameMaxForHeight = height * 0.08f * 0.55f;
        float songNameSize = Math.min(songNameSizeByWidth, songNameMaxForHeight);
        songNameSize = Math.max(24f, songNameSize); // 下限24px，保证可读
        h.tvSongName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, songNameSize);
        h.tvArtist.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, songNameSize * 0.7f);
        // 歌词：marginTop 用 1.5% 比例，字号和行距由 Scene 直接控制（与歌名字号关联）
        if (h.lyricView != null) {
            LinearLayout.LayoutParams lyricParams = (LinearLayout.LayoutParams) h.lyricView.getLayoutParams();
            lyricParams.width = (int) infoWidth;
            lyricParams.gravity = Gravity.CENTER_HORIZONTAL;
            int lyricTopMargin = Math.max(4, (int) (height * 0.015f));
            lyricParams.topMargin = lyricTopMargin;
            h.lyricView.setLayoutParams(lyricParams);
            // 歌词区域可用高度 = 总高 - 顶栏 - 歌名区域(~8%) - 歌词marginTop - 频谱(8%+1%) - 进度(1.5%) - 底部按钮(~3%)
            float usedHeight = topBarHeight + height * 0.08f + lyricTopMargin + height * 0.08f + height * 0.01f + height * 0.015f + height * 0.03f;
            float lyricAreaHeight = Math.max(40f, height - usedHeight);
            // 歌词字号与歌名字号关联：当前行 = 歌名 × 1.2，普通行 = 歌名 × 0.9
            float lyricCurrentSize = songNameSize * 1.2f;
            float lyricNormalSize = songNameSize * 0.9f;
            // 行距默认 = normalSize，双行歌词+行距不超出可用高度
            float lyricLineSpacing = lyricNormalSize;
            float totalLyricH = lyricCurrentSize + lyricNormalSize + lyricLineSpacing;
            if (totalLyricH > lyricAreaHeight) {
                // 先压缩行距（最低 normalSize × 0.3）
                lyricLineSpacing = Math.max(lyricNormalSize * 0.3f, lyricAreaHeight - lyricCurrentSize - lyricNormalSize);
                totalLyricH = lyricCurrentSize + lyricNormalSize + lyricLineSpacing;
            }
            if (totalLyricH > lyricAreaHeight) {
                // 行距压到极限还不够，等比缩小字号
                float scale = lyricAreaHeight / totalLyricH;
                lyricCurrentSize *= scale;
                lyricNormalSize *= scale;
                lyricLineSpacing = lyricAreaHeight - lyricCurrentSize - lyricNormalSize;
            }
            h.lyricView.setSceneTextSizes(lyricCurrentSize, lyricNormalSize, lyricLineSpacing);
        }
        // 轮播封面区域：28% 比例（原30%，为歌词腾出空间）
        int coverSize = (int) (height * 0.28f);
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
        // 频谱：marginBottom 用 1% 比例
        if (h.spectrumView != null && h.spectrumView.getParent() == h.rootLayout) {
            h.moveSpectrumToBottom();
        }
        if (h.spectrumView != null) {
            h.spectrumView.getLayoutParams().height = (int) (height * getSpectrumHeightRatio());
            LinearLayout.LayoutParams specParams = (LinearLayout.LayoutParams) h.spectrumView.getLayoutParams();
            specParams.bottomMargin = Math.max(2, (int) (height * 0.01f));
            h.spectrumView.setLayoutParams(specParams);
        }
        // 进度条：marginBottom 用 1.5% 比例
        if (h.controlButtons != null && h.controlButtons.getParent() instanceof LinearLayout) {
            // progress_layout 在 control_buttons 之前，设它的 marginBottom
        }
        if (h.progressLayout != null) {
            LinearLayout.LayoutParams progressParams = (LinearLayout.LayoutParams) h.progressLayout.getLayoutParams();
            progressParams.bottomMargin = Math.max(2, (int) (height * 0.015f));
            h.progressLayout.setLayoutParams(progressParams);
        }
        // 底部按钮：marginBottom 已由 applyButtonMargins 设为 H×1%，无需重复
    }

    @Override
    public void exit() {
        if (h.carouselView != null) {
            h.carouselView.stopSwayAnimation();
            h.carouselView.setVisibility(View.GONE);
        }
        h.rootLayout.setClipChildren(true);
        // 恢复歌词默认状态（清除高度约束，不影响竖屏等其他模式）
        if (h.lyricView != null) {
            h.lyricView.setShowPrevLine(true);
            h.lyricView.clearSceneTextSizes();
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
        return 0.08f;
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
