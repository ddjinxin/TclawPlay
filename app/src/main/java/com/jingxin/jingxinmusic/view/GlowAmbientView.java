package com.jingxin.jingxinmusic.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 氛围底（GlowEffectPage / PendoloEffectPage 共用，双模式）
 *
 * 模式一 MODE_MOTES（流光页）：封面主色调的"由远及近"光尘——
 * 视点散射 + 深度 z^1.6 easeIn（近大远小/近快远慢），临镜淡出回视点重生；
 * 密度 1/22000px²，大小两档 75/25，固定种子 20261022L。
 *
 * 模式二 MODE_STREAKS（时计页）：流窜的能量丝——细长圆头光线带，
 * 起点屏外、方向总体水平（±15° 错落交替，呼应齿轮方向感）、正弦侧摆（丝带感），
 * 生命两端软渐入渐出；互相错开速度（单程 6~14s）、长度（15%~45% 屏宽）、宽度（0.8~1.8dp）；
 * alpha 峰值 22%~42%（线是结构感元素，不抢齿轮）。
 *
 * 共通：颜色 = 封面主色 lighten 浅亮版；屏底超暗主色光雾（4%~6%，12s 呼吸）；
 * Choreographer 随 attach/detach 启停，固定种子每次进入同一片。
 */
public class GlowAmbientView extends View {

    private static final long SEED = 20261022L;

    /** 模式：光尘隧道（流光页） / 流窜线条（时计页） */
    public static final int MODE_MOTES = 0;
    public static final int MODE_STREAKS = 1;
    private int mode = MODE_MOTES;

    // ===== 模式一：光尘 =====
    private static final class Mote {
        float ox, oy;      // 相对视点的初始方向偏移
        float z;           // 深度 0=最远 1=最近
        float zSpeed;
        float radiusBase;
        float alphaMax;
        float wobA, wobW, wobPhase;
        float twinkleW, twinklePhase;
    }
    private final List<Mote> motes = new ArrayList<>();

    // ===== 模式二：线条 =====
    private static final class Streak {
        float startX, startY;    // 起点（屏外）
        float angleRad;          // 行进方向角
        float speed;             // px/s
        float length;            // 线长 px
        float width;             // 线宽 px
        float life, lifeTotal;   // 生命进度 0~1 / 总时长 s
        float swayA, swayW, swayPhase; // 侧摆（丝带感）
        float alphaMax;
        float drift;             // 方向角慢漂
    }
    private final List<Streak> streaks = new ArrayList<>();

    private final Paint motePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint streakPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hazePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random recycleRandom = new Random();

    /** 主色（默认浅蓝白，注入封面主色后 lighten） */
    private int tintColor = Color.rgb(0x9E, 0xC4, 0xF0);
    private int tintRaw;

    private int layoutW, layoutH;
    private long lastFrameNs = -1L;
    private boolean running = false;
    private long startMs;

    // Choreographer 驱动（同 StarfieldView：持有回调字段，attach/detach 启停）
    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!running) return;
            long deltaNs = lastFrameNs < 0 ? 0 : frameTimeNanos - lastFrameNs;
            lastFrameNs = frameTimeNanos;
            float dt = Math.min(0.064f, deltaNs / 1_000_000_000f); // 钳制防跳帧瞬移
            if (mode == MODE_STREAKS) stepStreaks(dt);
            else stepMotes(dt);
            invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public GlowAmbientView(Context context) { super(context); }
    public GlowAmbientView(Context context, AttributeSet attrs) { super(context, attrs); }
    public GlowAmbientView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    // ===== 对外接口 =====

    /** 切模式（MODE_MOTES / MODE_STREAKS）：重生成粒子群 */
    public void setMode(int m) {
        if (m != MODE_MOTES && m != MODE_STREAKS || m == mode) return;
        this.mode = m;
        if (layoutW > 0) spawn();
        invalidate();
    }

    /** 注入封面主色（上/下色，粒子走浅亮版） */
    public void setTintColors(int top, int bottom) {
        if (top != 0) tintRaw = top;
        else if (bottom != 0) tintRaw = bottom;
        tintColor = lighten(tintRaw != 0 ? tintRaw : tintColor);
        invalidate();
    }

    /** 提亮到浅色：黑底上应发亮——亮度拉到 72%+、降饱和（保色相发柔） */
    private static int lighten(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = hsv[1] * 0.55f;
        hsv[2] = Math.max(hsv[2], 0.72f);
        return Color.HSVToColor(hsv);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutW = w; layoutH = h;
        spawn();
    }

    /** 按当前模式生成粒子群（固定种子） */
    private void spawn() {
        motes.clear();
        streaks.clear();
        if (layoutW <= 0 || layoutH <= 0) return;
        if (mode == MODE_STREAKS) spawnStreaks();
        else spawnMotes();
    }

    // ===== 模式一：光尘 =====

    private void spawnMotes() {
        float density = getResources().getDisplayMetrics().density;
        Random r = new Random(SEED);
        int count = Math.max(12, (int) (layoutW * layoutH / 22000f));
        for (int i = 0; i < count; i++) {
            Mote m = new Mote();
            m.ox = (r.nextFloat() - 0.5f) * 2f * layoutW * 0.70f;
            m.oy = (r.nextFloat() - 0.5f) * 2f * layoutH * 1.05f;
            m.z = r.nextFloat() * 0.8f;
            m.zSpeed = 1f / (7f + r.nextFloat() * 11f);
            boolean big = r.nextFloat() < 0.25f;
            m.radiusBase = (big ? 3.0f + r.nextFloat() * 1.2f : 1.2f + r.nextFloat() * 1.4f) * density;
            m.alphaMax = 0.28f + r.nextFloat() * 0.24f;
            m.wobA = density * (2f + r.nextFloat() * 6f);
            m.wobW = 0.15f + r.nextFloat() * 0.3f;
            m.wobPhase = r.nextFloat() * (float) (Math.PI * 2);
            m.twinkleW = 0.4f + r.nextFloat() * 0.8f;
            m.twinklePhase = r.nextFloat() * (float) (Math.PI * 2);
            motes.add(m);
        }
    }

    private void stepMotes(float dt) {
        for (int i = 0; i < motes.size(); i++) {
            Mote m = motes.get(i);
            m.z += m.zSpeed * dt;
            if (m.z >= 1f) recycleMote(m);
        }
    }

    /** 掠过镜头后回收：重掷方向/呼吸（z 归零，从深处再次出发） */
    private void recycleMote(Mote m) {
        m.ox = (recycleRandom.nextFloat() - 0.5f) * 2f * layoutW * 0.70f;
        m.oy = (recycleRandom.nextFloat() - 0.5f) * 2f * layoutH * 1.05f;
        m.z = 0f;
        m.zSpeed = 1f / (7f + recycleRandom.nextFloat() * 11f);
        m.wobPhase = recycleRandom.nextFloat() * (float) Math.PI * 2;
        m.twinklePhase = recycleRandom.nextFloat() * (float) Math.PI * 2;
    }

    // ===== 模式二：线条 =====

    private void spawnStreaks() {
        float density = getResources().getDisplayMetrics().density;
        Random r = new Random(SEED);
        int count = 14;
        for (int i = 0; i < count; i++) {
            Streak s = new Streak();
            initStreak(s, r, true, density);
            s.life = r.nextFloat() * 0.7f; // 初始错开相位，画面一进来就有层次
            streaks.add(s);
        }
    }

    /** 初始化一条线（fromLeft 起始侧；横穿后重生换侧） */
    private void initStreak(Streak s, Random r, boolean fromLeft, float density) {
        // 起点在左/右屏外（垂直位置避开上 12% 歌名区，12%~100% 均布）
        s.startY = layoutH * (0.12f + r.nextFloat() * 0.88f);
        float totalTravel = layoutW * (1.1f + r.nextFloat() * 0.35f); // 行程（含屏外余量）
        float lifeTotal = 6f + r.nextFloat() * 8f; // 单程 6~14s
        s.angleRad = (r.nextFloat() - 0.5f) * 2f * (float) Math.toRadians(15f); // ±15° 错落
        if (fromLeft) {
            s.startX = -layoutW * 0.05f - r.nextFloat() * layoutW * 0.2f;
            s.speed = totalTravel / lifeTotal; // px/s
        } else {
            s.startX = layoutW * (1.05f + r.nextFloat() * 0.2f);
            s.angleRad = (float) Math.PI - s.angleRad; // 镜像：向左行进
            s.speed = totalTravel / lifeTotal;
        }
        s.length = layoutW * (0.15f + r.nextFloat() * 0.30f);
        s.width = (0.8f + r.nextFloat() * 1.0f) * density;
        s.life = 0f;
        s.lifeTotal = lifeTotal;
        s.swayA = (3f + r.nextFloat() * 9f) * density; // 侧摆幅度
        s.swayW = 0.35f + r.nextFloat() * 0.5f;
        s.swayPhase = r.nextFloat() * (float) (Math.PI * 2);
        s.alphaMax = 0.22f + r.nextFloat() * 0.20f;
        s.drift = (r.nextFloat() - 0.5f) * 0.06f; // 方向慢漂 rad/s
    }

    private void stepStreaks(float dt) {
        for (int i = 0; i < streaks.size(); i++) {
            Streak s = streaks.get(i);
            s.life += dt;
            s.angleRad += s.drift * dt;
            float t = s.life / s.lifeTotal;
            if (t >= 1f) {
                // 重生：换侧出发（左右交替更有对流感）
                boolean fromLeft = s.startX < 0;
                initStreak(s, recycleRandom, !fromLeft, getResources().getDisplayMetrics().density);
            }
        }
    }

    // ===== 生命周期 =====

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        running = true;
        startMs = System.currentTimeMillis();
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
        if (layoutW <= 0 || layoutH <= 0) return;
        float ts = (System.currentTimeMillis() - startMs) / 1000f;

        // 屏底超暗主色光雾（4%~6% 呼吸，12s 周期；用原始主色）
        float haze = 0.05f + 0.01f * (float) Math.sin(ts * (float) (Math.PI * 2 / 12.0));
        int hazeColor = tintRaw != 0 ? tintRaw : tintColor;
        hazePaint.setShader(new RadialGradient(
                layoutW / 2f, layoutH * 1.15f, layoutH * 0.85f,
                ((int) (haze * 255) << 24) | (hazeColor & 0xFFFFFF),
                0x00000000, Shader.TileMode.CLAMP));
        canvas.drawRect(0, layoutH * 0.35f, layoutW, layoutH, hazePaint);

        if (mode == MODE_STREAKS) drawStreaks(canvas, ts);
        else drawMotes(canvas, ts);
    }

    /** 光尘隧道：视点在屏中偏上（40%），z 越大离镜头越近 */
    private void drawMotes(Canvas canvas, float ts) {
        float vpX = layoutW * 0.5f, vpY = layoutH * 0.40f;
        for (int i = 0; i < motes.size(); i++) {
            Mote m = motes.get(i);
            float z = m.z;
            // 位移扩展：easeIn z^1.6 —— 近大远小/近快远慢（景深感）；上限 1.75 覆盖对角
            float g = 0.22f + 1.75f * (float) Math.pow(z, 1.6f);
            float wob = (float) Math.sin(m.wobW * ts + m.wobPhase) * m.wobA * g;
            float x = vpX + m.ox * g + wob;
            float y = vpY + m.oy * g + wob * 0.6f;
            float radius = m.radiusBase * (0.35f + 1.15f * z);
            float fadeIn = Math.min(1f, z / 0.16f);
            float fadeOut = Math.min(1f, (1f - z) / 0.14f);
            float tw = 0.65f + 0.35f * (float) Math.sin(m.twinkleW * ts + m.twinklePhase);
            int a = (int) (m.alphaMax * fadeIn * fadeOut * tw * 255);
            if (a <= 3) continue;
            motePaint.setShader(new RadialGradient(x, y, radius * 2.2f,
                    (a << 24) | (tintColor & 0xFFFFFF), 0x00000000, Shader.TileMode.CLAMP));
            canvas.drawCircle(x, y, radius * 2.2f, motePaint);
        }
    }

    /**
     * 流窜线条：头部沿方向行进，尾段带正弦侧摆（丝带感）；
     * 生命前 12%/后 12% 软渐入出，线身从尾到头 alpha 渐增强（头亮尾暗，运动方向感）。
     */
    private void drawStreaks(Canvas canvas, float ts) {
        float density = getResources().getDisplayMetrics().density;
        streakPaint.setStyle(Paint.Style.STROKE);
        streakPaint.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < streaks.size(); i++) {
            Streak s = streaks.get(i);
            float t = s.life / s.lifeTotal;
            if (t >= 1f) continue;
            float fadeIn = Math.min(1f, t / 0.12f);
            float fadeOut = Math.min(1f, (1f - t) / 0.12f);
            float env = fadeIn * fadeOut;
            if (env <= 0.02f) continue;
            int a = (int) (s.alphaMax * env * 255);
            if (a <= 3) continue;

            // 头部位置（起点 + 行程）
            float travel = s.speed * (s.lifeTotal * Math.min(1f, t));
            float hx = s.startX + (float) Math.cos(s.angleRad) * travel;
            float hy = s.startY + (float) Math.sin(s.angleRad) * travel;
            // 尾部（反方向拉长）+ 侧摆：头少尾多（丝带拖尾感）
            float tx = hx - (float) Math.cos(s.angleRad) * s.length;
            float ty = hy - (float) Math.sin(s.angleRad) * s.length;
            float swHead = (float) Math.sin(s.swayW * ts + s.swayPhase) * s.swayA * 0.3f;
            float swTail = (float) Math.sin(s.swayW * ts + s.swayPhase - 0.9f) * s.swayA;
            float nx = -(float) Math.sin(s.angleRad), ny = (float) Math.cos(s.angleRad); // 法向
            // 尾段三段折线近似弧（尾摆大、中段半摆、头近直）——避免每帧建 Path
            float m1x = (tx * 2f + hx) / 3f + nx * swTail * 0.75f;
            float m1y = (ty * 2f + hy) / 3f + ny * swTail * 0.75f;
            float m2x = (tx + hx * 2f) / 3f + nx * swTail * 0.35f;
            float m2y = (ty + hy * 2f) / 3f + ny * swTail * 0.35f;

            // 双层：宽而淡的辉光层 + 细而亮的核心层（发光丝效果）
            streakPaint.setStrokeWidth(s.width + 2.5f * density);
            streakPaint.setColor((a / 3) << 24 | (tintColor & 0xFFFFFF));
            canvas.drawLine(tx + nx * swTail, ty + ny * swTail, m1x, m1y, streakPaint);
            canvas.drawLine(m1x, m1y, m2x, m2y, streakPaint);
            canvas.drawLine(m2x, m2y, hx + nx * swHead, hy + ny * swHead, streakPaint);

            streakPaint.setStrokeWidth(s.width);
            streakPaint.setColor((a << 24) | (tintColor & 0xFFFFFF));
            canvas.drawLine(tx + nx * swTail, ty + ny * swTail, m1x, m1y, streakPaint);
            canvas.drawLine(m1x, m1y, m2x, m2y, streakPaint);
            canvas.drawLine(m2x, m2y, hx + nx * swHead, hy + ny * swHead, streakPaint);
        }
    }
}
