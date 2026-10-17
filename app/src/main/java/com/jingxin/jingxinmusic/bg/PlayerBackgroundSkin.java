package com.jingxin.jingxinmusic.bg;

/**
 * 播放页背景皮肤策略（组件化第3步）
 *
 * 皮肤 = 一套背景渲染规则（底色/遮罩/层级）。
 * 与封面风格正交：皮肤管"背景怎么画"，风格页面管"内容怎么摆"，
 * 两者经 BackgroundSkinController 分发，互相不感知。
 *
 * 扩展方式：新增皮肤 = 实现本接口 + Controller 注册一行。
 */
public interface PlayerBackgroundSkin {

    /**
     * 应用背景渲染（原 PlayerFragment.updateThemeUI 背景段逐分支迁移）
     *
     * @param ctx        皮肤上下文（View 引用与共享对象）
     * @param coverStyle 封面风格（0=经典 1=沉浸 2=唱片机 3=轮播，同 CoverSceneHelper 常量）
     * @param landscape  是否横屏
     * @param night      是否夜间模式
     */
    void apply(BackgroundSkinContext ctx, int coverStyle, boolean landscape, boolean night);
}
