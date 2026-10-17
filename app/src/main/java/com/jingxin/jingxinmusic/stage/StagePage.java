package com.jingxin.jingxinmusic.stage;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.jingxin.jingxinmusic.scene.CoverSceneHelper;

/**
 * 播放风格页面基类（组件化第5步，新架构）
 *
 * 与旧 scene/CoverScene 的本质区别：
 * - 布局所有权：页面自己 inflate/管理自己的视图结构（旧 Scene 在共享View上做手术）
 * - 横竖屏：一个类内部处理（旧架构 4风格×2方向=8个类）
 * - 生命周期：install→enter/exit→layout→destroy，与旧 Scene 对齐便于双轨切换
 *
 * 过渡期设计：
 * - 共享核心组件（歌词/频谱/控制条等）仍是全局单实例，页面通过
 *   onBorrowComponents() 借用（re-parent 或定位），不复制；
 * - Helper 仍提供工具方法（calcTopBarHeight 等比例公式），页面按需调用，
 *   步骤7拆除 Helper 时这些公式将沉入本基类的 LayoutSupport。
 */
public abstract class StagePage {

    protected final CoverSceneHelper h; // 过渡期工具与共享组件引用来源

    protected StagePage(CoverSceneHelper helper) {
        this.h = helper;
    }

    /** 页面创建：inflate 自己的布局并挂到宿主容器 */
    public abstract void install(LayoutInflater inflater, ViewGroup host);

    /** 进入页面（对应旧 Scene.enter + onStyleEnter） */
    public abstract void enter();

    /** 离开页面（对应旧 Scene.exit + onStyleExit） */
    public abstract void exit();

    /** 尺寸布局（对应旧 Scene.layout，横竖屏内部自适应） */
    public abstract void layout(int width, int height);

    /** 歌词模式变化（对应旧 onLyricModeChanged） */
    public void onLyricModeChanged(boolean isFullScreen) {}

    /** 播放态变化（对应旧 onPlayingStateChanged：旋转启停/唱臂联动） */
    public void onPlayingStateChanged(boolean isPlaying) {}

    /** 服务恢复（对应旧 onServiceResumed，默认同播放态处理） */
    public void onServiceResumed(boolean isPlaying) {}

    /** 借用共享组件回调：页面就绪后 Framework 把单实例组件交给页面摆位 */
    public void onBorrowComponents() {}

    /** 封面位图（对应旧 Scene.setCover，各风格差异化：经典裁剪/轮播模糊底/沉浸全屏） */
    public void setCover(android.graphics.Bitmap bitmap) {}

    /** 页面销毁：清理子视图（宿主容器不动的共享组件除外） */
    public void destroy() {}

    // ===== 旧 Scene 接口兼容映射（双轨期 Fragment 统一调度用） =====

    public boolean shouldShowSpectrumButton(int spectrumStyle) { return true; }
    public boolean shouldRotateCover() { return false; }
    public boolean needsReloadCover() { return false; }
    public float getSpectrumHeightRatio() { return 0.10f; }
    public float getInfoPanelWidthRatio() { return 1.0f; }
}
