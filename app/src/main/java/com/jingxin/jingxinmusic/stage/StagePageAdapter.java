package com.jingxin.jingxinmusic.stage;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import com.jingxin.jingxinmusic.scene.CoverScene;
import com.jingxin.jingxinmusic.scene.CoverSceneHelper;

/**
 * 风格页面适配器（双轨期）
 *
 * 把新架构 StagePage 包成旧 CoverScene 接口，让 PlayerFragment 的
 * currentScene 调度零改动地路由到新页面。
 * 步骤6其余风格迁完后，Fragment 直接改持有 StagePage，本类退役。
 */
public class StagePageAdapter implements CoverScene {

    private final StagePage page;
    private boolean installed = false;

    public StagePageAdapter(StagePage page) {
        this.page = page;
    }

    public StagePage getPage() {
        return page;
    }

    @Override
    public void enter() {
        // install 惰性执行：首次进入时页面自建并挂到宿主
        if (!installed) {
            page.install(LayoutInflater.from(page.h.rootLayout.getContext()),
                    (ViewGroup) page.h.rootLayout);
            installed = true;
        }
        page.enter();
        page.onBorrowComponents(); // 组件借用跟随进入（re-parent）
    }

    @Override
    public void exit() {
        page.exit();
    }

    @Override
    public void layout(int width, int height) {
        page.layout(width, height);
    }

    @Override
    public void onStyleEnter() {
        // 新架构：风格进入逻辑已并入 enter()，无独立阶段
    }

    @Override
    public void onStyleExit() {
        // 新架构：风格退出逻辑已并入 exit()
    }

    @Override
    public void setCover(android.graphics.Bitmap bitmap) {
        // 轮播封面沿旧路径：applyBlurBackground 由 Fragment/Helper 处理，
        // 页面不单独持有封面（卡片封面由 carouselAdapter 数据驱动）
    }

    @Override
    public void onLyricModeChanged(boolean isFullScreen) {
        page.onLyricModeChanged(isFullScreen);
    }

    @Override
    public float getInfoPanelWidthRatio() {
        return page.getInfoPanelWidthRatio();
    }

    @Override
    public int getSongNameTopMarginDp() {
        return 16;
    }

    @Override
    public boolean shouldShowSpectrumButton(int spectrumStyle) {
        return page.shouldShowSpectrumButton(spectrumStyle);
    }

    @Override
    public boolean shouldRotateCover() {
        return page.shouldRotateCover();
    }

    @Override
    public boolean needsReloadCover() {
        return page.needsReloadCover();
    }

    @Override
    public float getSpectrumHeightRatio() {
        return page.getSpectrumHeightRatio();
    }
}
