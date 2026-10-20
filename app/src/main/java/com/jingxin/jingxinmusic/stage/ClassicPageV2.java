package com.jingxin.jingxinmusic.stage;

/**
 * 经典风格壳（第5风格档位标签）
 *
 * 拆基说明：原"经典页身兼风格+基座二职"，共享基建（布局绑定/Hub订阅/背景四态/
 * 横竖屏公式/控制条联动/频谱管线）已整体平移至 StandardPageBase（无风格身份基座）。
 * 本类不覆写任何钩子——Base 出厂默认形态即经典骨架（classic XML + 圆形旋转封面
 * + 全部经典比例公式），所以经典档位 = Base 默认态。
 *
 * 修改去向：经典专属视觉差异长在本类；三风格共享的修改改 Base（影响面明确为经典/唱机/轮播）。
 * 兼容性：PlayerFragment 工厂/Actions 引用方仍可指向本类（历史公开名不变）。
 */
public class ClassicPageV2 extends StandardPageBase {

    public ClassicPageV2(com.jingxin.jingxinmusic.scene.CoverSceneHelper helper) {
        super(helper);
    }
}
