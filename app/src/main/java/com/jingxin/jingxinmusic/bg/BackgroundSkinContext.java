package com.jingxin.jingxinmusic.bg;

import android.widget.FrameLayout;
import android.widget.ImageView;
import android.view.View;

import com.jingxin.jingxinmusic.scene.CoverSceneHelper;
import com.jingxin.jingxinmusic.view.AuroraGradientView;
import com.jingxin.jingxinmusic.view.CoverBorderGradientDrawable;
import com.jingxin.jingxinmusic.view.ImmersiveOverlayView;
import com.jingxin.jingxinmusic.view.RotatingCoverView;
import com.jingxin.jingxinmusic.view.SpectrumView;

/**
 * 背景皮肤运行上下文（组件化第3步）
 *
 * 皮肤渲染所需的 View 引用与共享对象，由 PlayerFragment 在视图与
 * CoverSceneHelper 就绪后装配一次；皮肤只读使用。
 * 可变项仅 coverBorderGradient（与 Fragment 字段同源，syncSceneState 同步）。
 */
public final class BackgroundSkinContext {

    /** 播放页根布局（底色宿主 + 光晕层级手术宿主，即原 mRootView/root_layout） */
    public final FrameLayout rootLayout;
    /** 双色光晕 View（null=不可用，皮肤选择时回落默认皮肤，语义同原 if 条件） */
    public final AuroraGradientView aurora;
    /** 沉浸封面铺底 */
    public final ImageView blurBackground;
    /** 43% 渐变遮罩（沉浸模式核心遮罩，光晕竖屏沉浸同样复用） */
    public final ImmersiveOverlayView immersiveOverlay;
    /** 夜间压暗层 */
    public final View immersiveDarkOverlay;
    /** 白天渐变遮罩 */
    public final View whiteOverlay;
    /** 夜间半透明遮罩 */
    public final View overlayView;
    /** 封面（横屏沉浸下为层级手术参与者） */
    public final RotatingCoverView coverView;
    /** 频谱（退出光晕模式恢复默认配色） */
    public final SpectrumView spectrumView;
    /** 场景辅助（渐变过渡层/封面边缘渐变/infoPanel 引用来源） */
    public final CoverSceneHelper sceneHelper;
    /** 封面边缘渐变（横屏沉浸遮罩配色；与 Fragment 字段双向同步的同一对象） */
    public volatile CoverBorderGradientDrawable coverBorderGradient;

    public BackgroundSkinContext(FrameLayout rootLayout, AuroraGradientView aurora,
                                 ImageView blurBackground, ImmersiveOverlayView immersiveOverlay,
                                 View immersiveDarkOverlay, View whiteOverlay, View overlayView,
                                 RotatingCoverView coverView, SpectrumView spectrumView,
                                 CoverSceneHelper sceneHelper) {
        this.rootLayout = rootLayout;
        this.aurora = aurora;
        this.blurBackground = blurBackground;
        this.immersiveOverlay = immersiveOverlay;
        this.immersiveDarkOverlay = immersiveDarkOverlay;
        this.whiteOverlay = whiteOverlay;
        this.overlayView = overlayView;
        this.coverView = coverView;
        this.spectrumView = spectrumView;
        this.sceneHelper = sceneHelper;
    }
}
