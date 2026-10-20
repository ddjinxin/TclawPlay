package com.jingxin.jingxinmusic.stage;

import android.view.View;
import android.widget.FrameLayout;

import com.jingxin.jingxinmusic.R;
import com.jingxin.jingxinmusic.view.RotatingCoverView;
import com.jingxin.jingxinmusic.view.TonearmView;

/**
 * 唱片机风格页面 V2（第7风格，完全自持实例）
 *
 * 继承 StandardPageBase（拆基后，基座无风格身份；对齐旧 RecordPage extends ClassicPage 结构）：
 * 布局/染色/背景四态/订阅/精简模式全复用父类，本类只叠加唱片机专属：
 * - 封面：黑胶模式（不用 setStyle 四形态，走 setVinylMode 原路径——黑胶由组件内部渲染）
 * - 唱臂：TonearmView 竖/横双坐标公式（自 Fragment.updateTonearmPosition 逐行同源移植，
 *   绝对坐标定位改为 V2 页面根坐标系）；播放态摆角联动
 * - 全屏歌词：竖屏藏唱臂（黑胶在下方），横屏保留唱臂（旧 RecordPage 行为）
 * - 进 V2 重灌封面（needsReloadCover=true，黑胶需重绘）
 *
 * 坑防御（批次1/2复盘）：
 * - 黑胶封面 setVinylMode 与 setStyle 互斥：onCoverArrived 覆盖为黑胶路径
 * - 唱臂定位需封面完成布局：post 延迟实测坐标（非 guess）
 * - 横屏封面居中基准已由父类 post 修正，唱臂坐标系用封面真实 getLocationOnScreen
 */
public class RecordPageV2 extends StandardPageBase {

    // 唱臂（竖/横双实例）
    private TonearmView tonearm;
    private TonearmView tonearmL;
    private boolean tonearmBound = false;

    public RecordPageV2(com.jingxin.jingxinmusic.scene.CoverSceneHelper helper) {
        super(helper);
    }

    @Override
    public void install(android.view.LayoutInflater inflater, android.view.ViewGroup host) {
        super.install(inflater, host);
        if (tonearmBound) return;
        // 唱臂挂到页面根（竖/横各自根内，绝对坐标定位——挂 rootLayout 同层会跨页污染，故挂本页根）
        tonearm = new TonearmView(inflater.getContext());
        tonearm.setId(View.generateViewId());
        ((android.view.ViewGroup) portraitRoot).addView(tonearm, new FrameLayout.LayoutParams(0, 0));
        tonearmL = new TonearmView(inflater.getContext());
        tonearmL.setId(View.generateViewId());
        ((android.view.ViewGroup) landscapeRoot).addView(tonearmL, new FrameLayout.LayoutParams(0, 0));
        tonearmBound = true;
    }

    /** 唱片机独立 XML（骨架现阶段与经典版一致；后期完善只动这两个文件不碰其他风格） */
    @Override
    protected int providePortraitLayout() { return R.layout.stage_page_record_v2; }

    @Override
    protected int provideLandscapeLayout() { return R.layout.stage_page_record_v2_land; }

    @Override
    public void enter() {
        super.enter();
        // 黑胶形态（覆盖父类 onCoverArrived 里的 CIRCLE）
        restoreVinylIfCoverPresent();
        // 唱臂完整初始化：exit 时 GONE 的两个实例都要恢复状态；
        // 摆角/播放态同步（横竖重入不会触发 onPlayingStateChanged，就地重放当前态）
        boolean landscape = h.isLandscapeMode;
        boolean fullPortrait = !landscape && lyric != null
                && lyric.getDisplayMode() == com.jingxin.jingxinmusic.view.LyricView.DisplayMode.FULL;
        for (TonearmView t : new TonearmView[]{tonearm, tonearmL}) {
            if (t == null) continue;
            boolean isCurrentDirection = (t == tonearmL) == landscape;
            boolean active = isCurrentDirection && !(t == tonearm && fullPortrait);
            if (active) {
                t.setVisibility(View.VISIBLE);
                t.setLandscapeMode(t == tonearmL);
                t.setPlaying(h.isPlaying);
                t.refreshAngle();
            } else {
                t.setVisibility(View.GONE);
            }
        }
        applyTonearmAfterLayout(landscape);
    }

    /** 封面已就位时立即切黑胶（封面 bitmap 由父类订阅灌入） */
    private void restoreVinylIfCoverPresent() {
        if (h.executor == null) return;
        // 黑胶模式必须作用于有图状态才不闪默认图标盘：封面 drawable 非空才开
        if (cover != null && cover.getDrawable() != null) cover.setVinylMode(true);
        if (coverL != null && coverL.getDrawable() != null) coverL.setVinylMode(true);
    }

    /** 封面到达 hook 覆盖：父类先完成 CIRCLE/旋转/模糊背景，本类追加黑胶接管+唱臂定位 */
    @Override
    protected void onCoverArrived(android.graphics.Bitmap bitmap, boolean landscape) {
        super.onCoverArrived(bitmap, landscape);
        // 黑胶接管（覆盖父类 setStyle(CIRCLE)；bitmap 已由父类 setImageBitmap）
        cover.setVinylMode(true);
        coverL.setVinylMode(true);
        applyTonearmAfterLayout(landscape);
    }

    @Override
    public void layout(int width, int height) {
        super.layout(width, height);
        if (h.isLandscapeMode) relayoutLandscapeCoverForTonearm(width, height);
        applyTonearmAfterLayout(h.isLandscapeMode);
    }

    /**
     * 唱片机横屏封面位置修正：父类在"变矮列"内居中（底部三件全宽压缩），封面中心比旧版
     * （全高居中）高约 126px，唱臂臂长固定导致支点被挤进顶栏。
     * 本方法把封面改为在"顶栏+唱臂预留高度"之下的区间居中：
     *   封面top = topBarBelow + armReserve + (列高 - topBarBelow - armReserve - coverSize)/2
     * armReserve = pivot上方配重+安全 ≈ 2.8*unit + 8dp（unit=coverSize/21）
     */
    private void relayoutLandscapeCoverForTonearm(int width, int height) {
        if (coverL == null || coverCol == null) return;
        int rightPanelWidth = (int) (width * 0.35f);
        int coverSize = (int) (rightPanelWidth * 0.70f);
        float unit = coverSize / 21f;
        int topBarH = h.calcTopBarHeight(height, width, true);
        int topBarBelow = topBarH + (int) (h.density * 8);
        int armReserve = (int) ((cwDist() + cwRad() + 0.3f + 0.35f) * unit) + (int) (h.density * 8) + 12; // +12px 安全余量：确保配重球整体在顶栏按钮下方
        coverCol.post(() -> {
            int colH = coverCol.getHeight();
            if (colH > 0) {
                int top = topBarBelow + armReserve
                        + Math.max(0, (colH - topBarBelow - armReserve - coverSize) / 2);
                android.widget.FrameLayout.LayoutParams p =
                        (android.widget.FrameLayout.LayoutParams) coverL.getLayoutParams();
                if (p.topMargin != top || p.width != coverSize) {
                    p.width = coverSize;
                    p.height = coverSize;
                    p.gravity = android.view.Gravity.CENTER_HORIZONTAL;
                    p.topMargin = top;
                    coverL.setLayoutParams(p);
                }
                // 圆环圆心同步封面真实中心（父类 post 按经典公式算圆心，与本封面位置脱节；
                // 底部三件隐藏/恢复后列高变化，此处重申保证圆环始终贴封面外沿）
                updateRingCenter(coverCol.getWidth() / 2, top + coverSize / 2, coverSize);
            }
        });
    }

    /** 唱臂配重臂距常量（与 applyTonearmFor 一致） */
    private float cwDist() { return 1.8f; }
    private float cwRad() { return 0.7f; }

    @Override
    public void onPlayingStateChanged(boolean isPlaying) {
        super.onPlayingStateChanged(isPlaying);
        TonearmView t = h.isLandscapeMode ? tonearmL : tonearm;
        if (t != null) {
            t.setLandscapeMode(h.isLandscapeMode);
            t.setPlaying(isPlaying);
            t.refreshAngle();
        }
    }

    @Override
    public void onServiceResumed(boolean isPlaying) {
        super.onServiceResumed(isPlaying);
        TonearmView t = h.isLandscapeMode ? tonearmL : tonearm;
        if (t != null) {
            t.setLandscapeMode(h.isLandscapeMode);
            t.setPlaying(isPlaying);
            t.refreshAngle();
        }
    }

    @Override
    public void onLyricModeChanged(boolean isFullScreen) {
        super.onLyricModeChanged(isFullScreen);
        // 旧 RecordPage 行为：竖屏全屏歌词藏唱臂（黑胶封面也藏，由父类处理）；横屏保留唱臂
        if (!h.isLandscapeMode) {
            TonearmView t = tonearm;
            boolean full = lyric != null
                    && lyric.getDisplayMode() == com.jingxin.jingxinmusic.view.LyricView.DisplayMode.FULL;
            if (t != null) {
                t.setVisibility(full ? View.GONE : View.VISIBLE);
                if (!full) {
                    t.setLandscapeMode(false);
                    t.refreshAngle();
                }
            }
        } else {
            TonearmView t = tonearmL;
            if (t != null) {
                t.setLandscapeMode(true);
                t.refreshAngle();
            }
        }
    }

    @Override
    public boolean needsReloadCover() {
        return true; // 黑胶封面需重载（对齐旧页）
    }

    // ===== 唱臂定位（公式逐行自 Fragment.updateTonearmPosition 同源移植，坐标系=本页根） =====

    /**
     * 布局稳定后定位唱臂。时序链（确定性）：
     * layout() → 父类封面 post 修正（topMargin 收敛）→ 本 post 挂封面 view 再延一帧 →
     * 读取封面最终坐标 → 定位。此前挂在页面根 post，可能抢在封面修正前读到中间态坐标
     * （首帧封面在(0,0)导致唱臂 topMargin 负值顶出屏幕外）。
     */
    private void applyTonearmAfterLayout(boolean landscape) {
        android.view.View coverV = landscape ? coverL : cover;
        if (coverV == null) return;
        coverV.post(() -> coverV.post(() ->
                applyTonearmFor(coverV, landscape ? tonearmL : tonearm, landscape)));
    }

    private void applyTonearmFor(android.view.View coverV, TonearmView tonearmV, boolean landscape) {
        if (coverV == null || tonearmV == null) return;
        int coverW = coverV.getWidth();
        int coverH = coverV.getHeight();
        if (coverW <= 0 || coverH <= 0) return;
        // 终态校验：封面宽必须已收敛到代码设定值（列宽*0.70 横屏 / 屏高0.25 竖屏）。
        // 首帧布局中间态（match_parent 未约束）会算出错误 topMargin 并固化——
        // "首次进入唱臂错位、切歌后恢复"即此因（切歌时布局已稳定）。未收敛则再等一帧。
        if (landscape) {
            int expectW = (int) (coverV.getWidth() > 0 && coverV.getParent() instanceof android.view.ViewGroup
                    ? ((android.view.ViewGroup) coverV.getParent()).getWidth() * 0.70f : 0);
            if (expectW > 0 && Math.abs(coverW - expectW) > Math.max(4, expectW * 0.02f)) {
                coverV.post(() -> applyTonearmFor(coverV, tonearmV, landscape));
                return;
            }
        }

        // 绝对坐标（相对本页根；与 Fragment 版同方法，但 root=本页根视图）
        int[] coverLoc = new int[2];
        int[] rootLoc = new int[2];
        coverV.getLocationOnScreen(coverLoc);
        (landscape ? landscapeRoot : portraitRoot).getLocationOnScreen(rootLoc);
        float coverCenterX = coverLoc[0] - rootLoc[0] + coverW / 2f;
        float coverCenterY = coverLoc[1] - rootLoc[1] + coverH / 2f;

        // 布局中间态防御：封面尚未进入可视区（顶端为负/超小）时本次不定位，等下一帧
        if (coverCenterY < needleReservedTop(coverW)) return;

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tonearmV.getLayoutParams();
        lp.gravity = 0;

        // ---- 唱臂几何常量（与 TonearmView.onDraw / Fragment 版一致） ----
        float unit = landscape ? coverW / 21f : coverW / 18f;
        float armLength = 5.5f * unit;
        float bendLength = 1.5f * unit;
        float bendAngle = 35f;
        float headHeight = 1.3f * unit;
        float stylusLength = 0.6f * unit;
        float cwDistance = 1.8f * unit;
        float cwRadius = 0.7f * unit;
        float pivotOffsetY = cwDistance + cwRadius + 0.3f * unit;
        float pivotRightSpace = 0.707f * cwDistance + cwRadius + 0.5f * unit;

        double bendRad = Math.toRadians(bendAngle);
        float totalBendDist = bendLength + headHeight + stylusLength;
        float needleDx = (float) Math.sin(bendRad) * totalBendDist;
        float needleDy = armLength + (float) Math.cos(bendRad) * totalBendDist;

        float vinylRadius = coverW / 2f;
        float coverRadius = coverW * 2f / 3f / 2f;
        float needleTargetR = coverRadius + (vinylRadius - coverRadius) * (landscape ? 0.3f : 0.5f);

        if (landscape) {
            float pivotScreenX = coverCenterX;
            // 旧版公式原样（封面位置已由 layoutTonearmCover 下移修正，支点自然位于顶栏下方）
            float pivotScreenY = coverCenterY - needleTargetR - needleDy;
            int armW = (int) (coverW * 1.2f);
            int armH = (int) (pivotOffsetY + armLength + totalBendDist + cwDistance + cwRadius + unit);
            lp.width = armW;
            lp.height = armH;
            lp.leftMargin = (int) (pivotScreenX - armW / 2f);
            lp.topMargin = (int) (pivotScreenY - pivotOffsetY);
        } else {
            float playAngle = 45f;
            double playRad = Math.toRadians(playAngle);
            float rotatedNeedleDy = (float) (-needleDx * Math.sin(playRad) + needleDy * Math.cos(playRad));
            float needleTargetY = coverCenterY + needleTargetR * 0.5f;
            float pivotScreenX = (coverLoc[0] - rootLoc[0]) + coverW + needleTargetR * 0.35f;
            float pivotScreenY = needleTargetY - rotatedNeedleDy - unit;
            int armW = (int) (coverW + pivotRightSpace + cwDistance + cwRadius + unit);
            int armH = (int) (pivotOffsetY + armLength + totalBendDist + cwDistance + cwRadius + unit);
            float pivotViewX = armW - pivotRightSpace;
            lp.width = armW;
            lp.height = armH;
            lp.leftMargin = (int) (pivotScreenX - pivotViewX);
            lp.topMargin = (int) (pivotScreenY - pivotOffsetY);
        }

        tonearmV.setCoverSize(coverW);
        tonearmV.setLandscapeMode(landscape);
        tonearmV.setLayoutParams(lp);
        if (tonearmV.getVisibility() != View.VISIBLE) {
            tonearmV.setVisibility(View.VISIBLE);
        }
        tonearmV.bringToFront();
    }

    /** 唱臂 pivot 上方需要预留的空间近似值（防布局中间态负 topMargin 固化到屏幕外） */
    private float needleReservedTop(int coverW) {
        float unit = h.isLandscapeMode ? coverW / 21f : coverW / 18f;
        return -2f * unit; // 允许 pivot 略高于 0（负 topMargin 小量可接受），过大说明中间态
    }

    @Override
    public void exit() {
        super.exit();
        // 黑胶复位（防止残留到其他风格视觉——经典V2是同一对象？否：本类独立实例）
        if (cover != null) cover.setVinylMode(false);
        if (coverL != null) coverL.setVinylMode(false);
        if (tonearm != null) tonearm.setVisibility(View.GONE);
        if (tonearmL != null) tonearmL.setVisibility(View.GONE);
    }
}
