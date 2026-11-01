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

    /** 频谱数据（各页自持实例的分发入口，无频谱页用空桩） */
    public void feedSpectrumData(float[] magnitudes, float maxMag) {}

    // ===== 页面能力接口（各页按需覆写，Fragment 经 activePage() 多态分发） =====

    /** 控制条触摸隐藏态由宿主同步（无控制条页用空桩） */
    public void setControlsHiddenFromHost(boolean hidden) {}

    /** 收藏/下载键附加态变化刷新（无控制条页用空桩） */
    public void notifyControlExtrasChanged() {}

    /** 频谱样式同步到页自持实例（无频谱页用空桩） */
    public void applySpectrumStyle(int style) {}

    /** 页面销毁：清理子视图（宿主容器不动的共享组件除外） */
    public void destroy() {}

    /** 宿主（Fragment）前后台闸门：车机 ROM 退后台不下发窗口隐藏，帧链动画需宿主生命周期兜底（无动画背景页空桩） */
    public void setHostResumed(boolean resumed) {}

    // ===== 旧 Scene 接口兼容映射（双轨期 Fragment 统一调度用） =====

    public boolean shouldShowSpectrumButton(int spectrumStyle) { return true; }
    public boolean shouldRotateCover() { return false; }
    public boolean needsReloadCover() { return false; }
    public float getSpectrumHeightRatio() { return 0.10f; }
    public float getInfoPanelWidthRatio() { return 1.0f; }

    /** 兼容旧 Scene 的 onStyleEnter/onStyleExit（新架构风格逻辑已并入 enter/exit，占位保留调度调用） */
    public void onStyleEnter() {}
    public void onStyleExit() {}

    // ===== 宿主动作桥（页面协议一部分：页面只管视图，播放/弹窗/退出逻辑全部回调宿主） =====
    // 自 ClassicPageV2 沉降至基接口：沉浸等独立页不再 import 任何具体风格类
    public interface Actions {
        void togglePlayPause();
        void playPrevious();
        void playNext();
        void togglePlayOrder();
        void showHistoryDialog();
        void toggleFavorite();
        void saveBiliOffline();
        void showSpectrumPicker(View anchor);
        void showEffectPickerPopup(View anchor);
        void showLyricSearchPopup(View anchor);
        void openLyricEffect(); // 歌词动效键：进入歌词动效页（星空漫游等）
        boolean isFavorite();
        boolean isBiliSong();
    void exitPlayerPage(); // 返回键：退出播放页（与旧页语义一致）；装扮键切换走 outfit 循环
    void switchOutfitStyle(); // 装扮键：outfit 循环切换风格（第5风格进出循环）
    /** 绝对定位播放（队列索引）：拼贴墙点卡切歌用，默认空实现（旧风格页不走） */
    default void playSongAtPosition(int queueIndex) {}
    /** 直达歌词动效页（读上次记忆，无记忆用第一个）：拼贴墙点大封面进入，默认空实现 */
    default void openLyricEffectRemembered() {}
        // 进度条拖动（旧页 onSeek* 三回调：start暂停自动隐藏/to执行seek/stop恢复计时）
        void onSeekStart();
        void onSeekTo(int positionMs);
        void onSeekStop();
        boolean isControlsHidden(); // 当前精简模式态（旧页 controlsHidden，进 V2 时同步）
        boolean isBiliDownloadEnabled(); // B站下载键可用态（离线保存进行中禁用；默认 true）
        void onV2ControlsTouched(); // V2 内交互：向旧机制上报活动（重置隐藏计时）
    }
}
