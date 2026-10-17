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
    /** enter 时的方向快照：横竖屏切换时同页面需重入（旧架构横竖是两个Scene实例
     * 合并后单实例，不重入则页面停留在旧方向形态：沉浸横→竖封面不重铺/渐变层残留等） */
    private boolean enteredAsLandscape = false;

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
        enteredAsLandscape = page.h.isLandscapeMode; // 记录本次进入方向
        page.enter();
        page.onBorrowComponents(); // 组件借用跟随进入（re-parent）
    }

    /** 横竖屏形态重入：同页面退出旧方向形态再进入新方向（内部使用，不触发借用归还批次外副作用） */
    private void reenterForOrientation() {
        page.exit();
        enteredAsLandscape = page.h.isLandscapeMode;
        page.enter();
        page.onBorrowComponents();
    }

    @Override
    public void exit() {
        page.exit();
    }

    @Override
    public void layout(int width, int height) {
        // 方向变化重入：页面横竖形态需要重建（等价旧架构切换两个Scene实例的完整退/进）
        if (installed && enteredAsLandscape != page.h.isLandscapeMode) {
            reenterForOrientation();
        }
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
        // 封面按页面差异化路由（经典裁剪/轮播模糊底/沉浸全屏铺底）
        if (bitmap != null) {
            page.setCover(bitmap);
        }
    }

    @Override
    public void onLyricModeChanged(boolean isFullScreen) {
        page.onLyricModeChanged(isFullScreen);
    }

    @Override
    public void onPlayingStateChanged(boolean isPlaying) {
        page.onPlayingStateChanged(isPlaying);
    }

    @Override
    public void onServiceResumed(boolean isPlaying) {
        page.onServiceResumed(isPlaying);
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
