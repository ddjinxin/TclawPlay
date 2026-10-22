package com.jingxin.jingxinmusic.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

/**
 * 时计机芯（PendoloClockwork，移植 folia pendolo 的 Canvas2D 线框路径）
 *
 * 部件（照搬 pendoloClockworkDraw2d）：主擒纵轮(36齿+辐条轮+机刻纹+铆钉)、太阳轮、
 * 行星轮×3、左上摆轮(20齿+游丝+宝石)、左下传动轮(24齿+日内瓦条纹)、秒轮(每秒一齿)、
 * 介轮、同心参考环+外圈60刻度+焦轴基准线与箭头。
 *
 * 运动：主动轮角 = 棘轮弹簧(tickIndex×角度步进)；摆轮慢速常转 + 低频能量摆游丝；
 * 秒轮按播放秒数走齿；介轮快转；其余按 folia 联动比反转/加速。
 * 齿轮 Path 静态缓存（齿形不变只有旋转），stroke 纯线框零位图。
 */
public class PendoloClockworkView extends View {

    /** 低频能量提供者（BassEnergy 注入；null 时游丝不振，机芯照走） */
    public interface BassProvider { float getBassEnergy(); }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private BassProvider bassProvider;
    private int primaryColor = Color.rgb(0xC9, 0xE4, 0xFF);   // 主题主色（兜底）
    private int accentColor = Color.rgb(0x6E, 0xE8, 0xD8);    // 强调色（兜底）

    private int layoutW, layoutH;
    private float centerX, centerY, baseRadius;

    // 棘轮弹簧（欠阻尼，追目标行索引）：folia stiffness 180×snappiness2.0 / damping 20 / mass 0.8
    private float springPos, springVel;
    private float springTarget;
    private static final float SPRING_K = 360f, SPRING_D = 20f, SPRING_M = 0.8f;

    // 摆轮相位（慢速常转用）
    private long lastFrameNs = -1L;
    private float phase;
    private boolean running = false;

    // 进度（秒轮）
    private long positionMs = -1L;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!running) return;
            long deltaNs = lastFrameNs < 0 ? 0 : frameTimeNanos - lastFrameNs;
            lastFrameNs = frameTimeNanos;
            float dt = Math.min(0.064f, deltaNs / 1_000_000_000f);
            step(dt);
            invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public PendoloClockworkView(Context context) { super(context); init(); }
    public PendoloClockworkView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public PendoloClockworkView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr); init();
    }

    private void init() {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    // ===== 对外接口 =====

    public void setColors(int primary, int accent) {
        if (primary != 0) primaryColor = primary;
        if (accent != 0) accentColor = accent;
        invalidate();
    }

    public void setBassProvider(BassProvider p) { this.bassProvider = p; }

    /** 歌词行索引目标（换行棘轮的驱动源；LyricView 每换行调用） */
    public void setTickTarget(float target) {
        this.springTarget = target;
    }

    /** 播放进度（秒轮走齿） */
    public void setPositionMs(long positionMs) { this.positionMs = positionMs; }

    // ===== 弹簧步进 =====

    private void step(float dt) {
        // 半隐式欧拉：欠阻尼弹簧追目标（角步进由文本端换算，这里只出弹簧值）
        float f = -SPRING_K * (springPos - springTarget) - SPRING_D * springVel;
        springVel += (f / SPRING_M) * dt;
        springPos += springVel * dt;
        phase += dt;
    }

    /** 当前棘轮弹簧值（LyricView 布局用同一值保持轮与轨道咬合） */
    public float getSpringTick() { return springPos; }

    // ===== 布局 =====

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutW = w; layoutH = h;
        // folia 默认：轮心 x=0% 屏宽（左边缘）、y=50%；主轮半径 42% min(w,h)
        centerX = 0f;
        centerY = h * 0.5f;
        baseRadius = Math.min(w, h) * 0.42f;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        running = true;
        lastFrameNs = -1L;
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        running = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
    }

    // ===== 绘制 =====

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (baseRadius <= 0) return;

        float m = 1f; // subtle 档装饰透明度乘子（folia subtle≈0.55，full=1；保持 subtle）
        int pA10 = withAlpha(primaryColor, 0.10f * m), pA15 = withAlpha(primaryColor, 0.15f * m);
        int pA18 = withAlpha(primaryColor, 0.18f * m), pA25 = withAlpha(primaryColor, 0.25f * m);
        int pA28 = withAlpha(primaryColor, 0.28f * m), pA32 = withAlpha(primaryColor, 0.32f * m);
        int pA42 = withAlpha(primaryColor, 0.42f * m);
        int aA35 = withAlpha(accentColor, 0.35f * m), aA58 = withAlpha(accentColor, 0.58f * m);
        int aA72 = withAlpha(accentColor, 0.72f * m);
        int mainFill = withAlpha(accentColor, 0.08f * m);

        float escapementAngle = springPos * ANGLE_STEP_RAD; // 棘轮角 = 弹簧×角步进
        float bass = bassProvider != null ? clamp01(bassProvider.getBassEnergy()) : 0f;

        // 1. 同心参考环（5 圈）+ 外圈 60 刻度（每 5 一长刻）
        paint.setStrokeWidth(1f);
        paint.setColor(pA15);
        float[] rings = {0.3f, 0.6f, 0.85f, 1.15f, 1.4f};
        for (float rf : rings) canvas.drawCircle(centerX, centerY, baseRadius * rf, paint);
        float tickR = baseRadius * 1.15f;
        for (int i = 0; i < 60; i++) {
            float a = (i * (float) (Math.PI * 2)) / 60f + escapementAngle * 0.2f;
            boolean major = i % 5 == 0;
            float len = major ? 12f : 6f;
            paint.setColor(major ? aA35 : pA15);
            paint.setStrokeWidth(major ? 1.5f : 1f);
            canvas.drawLine(
                    centerX + tickR * cos(a), centerY + tickR * sin(a),
                    centerX + (tickR + len) * cos(a), centerY + (tickR + len) * sin(a), paint);
        }

        // 2. 主擒纵轮：36 齿外圈 + 辐条轮 + 双倒角环 + 机刻纹 + 铆钉
        paint.setStrokeWidth(2.2f);
        drawGear(canvas, centerX, centerY, baseRadius + 8f, 36, 10f, escapementAngle, aA58, mainFill);
        paint.setStrokeWidth(1.8f);
        drawSpokedWheel(canvas, centerX, centerY, baseRadius * 0.2f, baseRadius * 0.85f, 6, escapementAngle, pA42);
        paint.setStrokeWidth(0.8f);
        paint.setColor(pA18);
        canvas.drawCircle(centerX, centerY, baseRadius * 0.88f, paint);
        canvas.drawCircle(centerX, centerY, baseRadius * 0.92f, paint);
        // 机刻纹：48 根长短交替放射线
        float gIn = baseRadius * 0.62f, gOut = baseRadius * 0.83f;
        for (int i = 0; i < 48; i++) {
            float a = (i * (float) (Math.PI * 2)) / 48f;
            float inner = i % 2 == 0 ? gIn : gIn + (gOut - gIn) * 0.3f;
            paint.setColor(i % 2 == 0 ? pA10 : withAlpha(primaryColor, 0.07f * m));
            paint.setStrokeWidth(0.7f);
            canvas.drawLine(
                    centerX + inner * cos(a + escapementAngle), centerY + inner * sin(a + escapementAngle),
                    centerX + gOut * cos(a + escapementAngle), centerY + gOut * sin(a + escapementAngle), paint);
        }
        // 铆钉 12 颗
        paint.setColor(withAlpha(primaryColor, 0.25f * m));
        paint.setStrokeWidth(0.8f);
        float rivetR = baseRadius * 0.96f;
        for (int i = 0; i < 12; i++) {
            float a = (i * (float) (Math.PI * 2)) / 12f + escapementAngle;
            canvas.drawCircle(centerX + rivetR * cos(a), centerY + rivetR * sin(a), 2.2f, paint);
        }

        // 3. 太阳轮（中心 12 齿，反转 2.5×）+ 中心宝石
        paint.setStrokeWidth(2.1f);
        drawGear(canvas, centerX, centerY, baseRadius * 0.22f, 12, 6f,
                -escapementAngle * 2.5f, aA72, null);
        drawJewel(canvas, centerX, centerY, 4.5f, 2.5f);

        // 4. 行星轮 ×3（轨道 0.52R，反转 3× + 相位差）
        float orbitR = baseRadius * 0.52f, planetR = baseRadius * 0.13f;
        float orbitBase = escapementAngle * 0.4f;
        for (int i = 0; i < 3; i++) {
            float pa = orbitBase + (i * (float) (Math.PI * 2)) / 3f;
            float px = centerX + orbitR * cos(pa), py = centerY + orbitR * sin(pa);
            paint.setStrokeWidth(1.7f);
            drawGear(canvas, px, py, planetR, 14, 5f,
                    -escapementAngle * 3f + i * 0.5f, withAlpha(primaryColor, 0.24f * m), null);
            drawJewel(canvas, px, py, 3.5f, 1.8f);
        }

        // 5. 摆轮（左上，0.28R，慢速常转）+ 游丝（低频摆动）+ 宝石
        float bR = baseRadius * 0.28f;
        float bCx = centerX + baseRadius * 0.30f - bR * 1.05f, bCy = centerY - baseRadius * 0.62f;
        float balanceAngle = phase * 0.1f;
        paint.setStrokeWidth(2.1f);
        drawGear(canvas, bCx, bCy, bR, 20, 7f, balanceAngle, aA72, withAlpha(accentColor, 0.10f * m));
        paint.setColor(pA32);
        paint.setStrokeWidth(1.8f);
        canvas.drawCircle(bCx, bCy, bR * 0.52f, paint);
        drawHairspring(canvas, bCx, bCy, bR * 0.56f, bR * 0.88f, 3.5f,
                bass * 0.6f + balanceAngle * 0.3f, withAlpha(accentColor, 0.30f * m));
        drawJewel(canvas, bCx, bCy, 3.5f, 1.8f);

        // 6. 传动轮（左下 0.34R，-1.4×）+ 辐条 + 日内瓦条纹
        float tR = baseRadius * 0.34f;
        float tCx = centerX + baseRadius * 0.14f, tCy = centerY + baseRadius * 0.72f;
        float transAngle = -escapementAngle * 1.4f;
        paint.setStrokeWidth(1.8f);
        drawGear(canvas, tCx, tCy, tR, 24, 7f, transAngle, pA42, null);
        drawSpokedWheel(canvas, tCx, tCy, tR * 0.25f, tR * 0.85f, 5, transAngle, pA32);
        drawGenevaStripes(canvas, tCx, tCy, tR, transAngle, pA10, withAlpha(primaryColor, 0.06f * m));
        drawJewel(canvas, tCx, tCy, 3.5f, 1.8f);

        // 7. 秒轮（每播放秒走一齿）+ 辐条
        long sec = positionMs > 0 ? positionMs / 1000L : 0L;
        float sR = baseRadius * 0.16f;
        float sCx = centerX + baseRadius * 0.62f, sCy = centerY + baseRadius * 0.46f;
        float secondAngle = (sec % 15) * ((float) (Math.PI * 2) / 15f); // 15 齿轮，秒走一齿
        paint.setStrokeWidth(1.8f);
        drawGear(canvas, sCx, sCy, sR, 15, 5f, secondAngle, aA58, withAlpha(accentColor, 0.06f * m));
        drawSpokedWheel(canvas, sCx, sCy, sR * 0.28f, sR * 0.76f, 4, -secondAngle, pA32);
        drawJewel(canvas, sCx, sCy, 2.8f, 1.5f);

        // 8. 介轮（0.08R，2.2× 快转）
        float iR = baseRadius * 0.08f;
        float iCx = centerX + baseRadius * 0.42f, iCy = centerY + baseRadius * 0.66f;
        paint.setStrokeWidth(1.3f);
        drawGear(canvas, iCx, iCy, iR, 10, 3.5f, escapementAngle * 2.2f, pA28, null);
        paint.setColor(pA18);
        paint.setStrokeWidth(0.8f);
        canvas.drawCircle(iCx, iCy, iR * 0.45f, paint);
        drawJewel(canvas, iCx, iCy, 2.2f, 1.2f);

        // 9. 焦轴基准线 + 箭头（0° 水平向右）
        paint.setColor(aA72);
        paint.setStrokeWidth(2f);
        canvas.drawLine(centerX + baseRadius * 0.8f, centerY, centerX + baseRadius * 1.30f, centerY, paint);
        paint.setStyle(Paint.Style.FILL);
        path.reset();
        path.moveTo(centerX + baseRadius * 1.30f, centerY - 4f);
        path.lineTo(centerX + baseRadius * 1.30f + 8f, centerY);
        path.lineTo(centerX + baseRadius * 1.30f, centerY + 4f);
        path.close();
        paint.setColor(aA72);
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.STROKE);
    }

    // ===== 齿轮/辐条/游丝/日内瓦 =====

    /** 角度步进：100° 弧 9 行可见窗（与 LyricView 共用同一常量来源） */
    public static final float ANGLE_STEP_RAD =
            (float) (Math.toRadians(100f) / 8f);

    /** 梯形齿齿轮（folia drawGearTeeth 直译；齿形 Path 复用） */
    private void drawGear(Canvas canvas, float cx, float cy, float radius, int teeth,
                          float toothDepth, float rotation, int strokeColor, Integer fillColor) {
        float innerR = radius - toothDepth, outerR = radius;
        float perTooth = (float) (Math.PI * 2) / teeth;
        path.reset();
        for (int i = 0; i < teeth; i++) {
            float base = i * perTooth;
            float a0 = base - perTooth * 0.22f, a1 = base - perTooth * 0.12f;
            float a2 = base + perTooth * 0.12f, a3 = base + perTooth * 0.22f;
            float x0 = cx + innerR * cos(a0 + rotation), y0 = cy + innerR * sin(a0 + rotation);
            float x1 = cx + outerR * cos(a1 + rotation), y1 = cy + outerR * sin(a1 + rotation);
            float x2 = cx + outerR * cos(a2 + rotation), y2 = cy + outerR * sin(a2 + rotation);
            float x3 = cx + innerR * cos(a3 + rotation), y3 = cy + innerR * sin(a3 + rotation);
            if (i == 0) path.moveTo(x0, y0); else path.lineTo(x0, y0);
            path.lineTo(x1, y1);
            path.lineTo(x2, y2);
            path.lineTo(x3, y3);
        }
        path.close();
        if (fillColor != null) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(fillColor);
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.STROKE);
        }
        paint.setColor(strokeColor);
        canvas.drawPath(path, paint);
    }

    /** 辐条轮：轮毂+轮辋+辐条+减重孔（folia drawSpokedWheel 直译） */
    private void drawSpokedWheel(Canvas canvas, float cx, float cy, float hubR, float rimR,
                                 int spokes, float rotation, int color) {
        paint.setColor(color);
        canvas.drawCircle(cx, cy, hubR, paint);
        canvas.drawCircle(cx, cy, rimR, paint);
        float step = (float) (Math.PI * 2) / spokes;
        for (int i = 0; i < spokes; i++) {
            float a = i * step + rotation;
            canvas.drawLine(cx + hubR * cos(a), cy + hubR * sin(a),
                    cx + rimR * cos(a), cy + rimR * sin(a), paint);
        }
        float midR = (hubR + rimR) * 0.5f, holeR = (rimR - hubR) * 0.22f;
        for (int i = 0; i < spokes; i++) {
            float a = i * step + step * 0.5f + rotation;
            canvas.drawCircle(cx + midR * cos(a), cy + midR * sin(a), holeR, paint);
        }
    }

    /** 游丝：阿基米德螺线（r = start + (end-start)·t^0.9），头部随 oscillation 摆动 */
    private void drawHairspring(Canvas canvas, float cx, float cy, float startR, float endR,
                                float coils, float oscillation, int color) {
        paint.setColor(color);
        paint.setStrokeWidth(0.8f);
        int steps = (int) (coils * 60);
        path.reset();
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            float angle = t * coils * (float) Math.PI * 2f;
            float r = startR + (endR - startR) * (float) Math.pow(t, 0.9);
            float x = cx + r * cos(angle + oscillation), y = cy + r * sin(angle + oscillation);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        canvas.drawPath(path, paint);
    }

    /** 日内瓦条纹：9 条平行线裁剪在轮面内（save/clipRect 用 canvas.clipPath） */
    private void drawGenevaStripes(Canvas canvas, float cx, float cy, float r, float rotation,
                                   int c1, int c2) {
        int sc = canvas.save();
        path.reset();
        canvas.rotate(rotation, cx, cy);
        path.addCircle(cx, cy, r * 0.80f, Path.Direction.CW);
        canvas.clipPath(path);
        float span = r * 1.6f, stepLen = span / 10f;
        for (int i = 1; i <= 9; i++) {
            float yOff = -span * 0.5f + i * stepLen;
            paint.setColor(i % 2 == 0 ? c1 : c2);
            paint.setStrokeWidth(0.7f);
            canvas.drawLine(cx - r, cy + yOff, cx + r, cy + yOff, paint);
        }
        canvas.restoreToCount(sc);
    }

    /** 宝石轴承：双环 */
    private void drawJewel(Canvas canvas, float cx, float cy, float outerR, float innerR) {
        paint.setColor(withAlpha(accentColor, 0.65f));
        paint.setStrokeWidth(outerR * 0.28f);
        canvas.drawCircle(cx, cy, outerR, paint);
        paint.setColor(withAlpha(accentColor, 0.28f));
        paint.setStrokeWidth(innerR * 0.35f);
        canvas.drawCircle(cx, cy, innerR, paint);
    }

    private static float cos(float a) { return (float) Math.cos(a); }
    private static float sin(float a) { return (float) Math.sin(a); }
    private static float clamp01(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

    private static int withAlpha(int color, float alpha) {
        int a = (int) (Math.min(1f, Math.max(0f, alpha)) * 255);
        return (a << 24) | (color & 0xFFFFFF);
    }
}
