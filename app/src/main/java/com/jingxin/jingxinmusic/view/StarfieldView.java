package com.jingxin.jingxinmusic.view;

import android.content.Context;
import android.graphics.Bitmap;
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
 * 星空背景（星空漫游歌词效果的底幕）
 *
 * 视觉规格（对齐 QQ 音乐 Pad 星空漫游截图）+ 纵深增强：
 * - 深空渐变：中心纯黑 → 左右边缘深蓝黑（径向），缓存位图不逐帧重画
 * - 两团半透明灰蓝星云（左右边缘淡雾）
 * - 星点两层：多数细小白点 + 少量亮星；每颗独立闪烁（随机相位/周期 2~4s 正弦呼吸）
 * - 密度疏朗：约 1星/9000px²
 * - 纵深粒子层（从远到近）：粒子带 z 深度(1=最远→0=相机)匀速逼近，透视投影
 *   x/z 天然给出「远处几乎静止 → 近处加速飞驰」；远端小点融入背景星，
 *   近端拉径向拖尾线（近粗亮远细暗，头部微青 LED 色），出屏/过近重置回远端
 */
public class StarfieldView extends View {

    private static final class Star {
        float x, y;            // 相对比例 0~1
        float radius;          // 像素半径
        float baseAlpha;       // 基础透明度
        long twinklePeriodMs;  // 闪烁周期
        float phase;           // 相位
        boolean bright;        // 亮星（带十字光）
    }

    /** 纵深粒子：世界坐标(相对屏幕中心，单位 px) + 深度 z∈(0,1]，向相机飞 */
    private static final class DepthParticle {
        float x, y;
        float z;
        float prevSx, prevSy; // 上一帧投影点（拖尾用；NaN=刚重生不连线）
    }

    private final List<Star> stars = new ArrayList<>();
    private final List<DepthParticle> depthParticles = new ArrayList<>();
    private final Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint depthPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();
    private Paint bgCachePaint;
    private Bitmap bgCache;
    private int cachedW, cachedH;
    private boolean running = false;
    private long animStartMs;
    private long lastFrameNanos;
    private float depthDt = 1f / 60f;
    private boolean depthParticlesEnabled = true;
    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (running) {
                // 纵深粒子推进用真实 dt（上限 50ms 防跳帧瞬移）
                if (lastFrameNanos != 0L) {
                    depthDt = clamp((frameTimeNanos - lastFrameNanos) / 1_000_000_000f, 1f / 120f, 0.05f);
                }
                lastFrameNanos = frameTimeNanos;
                // 全量 invalidate：onDraw 先画缓存背景位图（快）再画星点层
                invalidate();
                Choreographer.getInstance().postFrameCallback(this);
            } else {
                lastFrameNanos = 0L;
            }
        }
    };

    public StarfieldView(Context context) { super(context); init(); }
    public StarfieldView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public StarfieldView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr); init();
    }

    private void init() { setLayerType(LAYER_TYPE_NONE, null); }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            spawnStars(w, h);
            spawnDepthParticles(w, h);
            buildBgCache(w, h);
        }
    }

    /** 星点生成：疏朗两层（97% 微小白点 + 3% 亮星） */
    private void spawnStars(int w, int h) {
        stars.clear();
        random.setSeed(20261020L); // 固定种子：星空形态稳定（每次进入同款星空）
        int count = Math.max(40, w * h / 9000);
        float density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < count; i++) {
            Star s = new Star();
            s.x = random.nextFloat();
            s.y = random.nextFloat();
            s.bright = random.nextInt(100) < 3;
            s.radius = s.bright
                    ? (1.8f + random.nextFloat() * 1.4f) * density
                    : (0.6f + random.nextFloat() * 0.9f) * density;
            s.baseAlpha = s.bright ? 0.9f : 0.35f + random.nextFloat() * 0.4f;
            s.twinklePeriodMs = 2000L + (long) (random.nextFloat() * 2000L);
            s.phase = random.nextFloat() * (float) (2 * Math.PI);
            stars.add(s);
        }
    }

    /** 纵深粒子生成（沿用同一 random 状态：进入同款分布） */
    private void spawnDepthParticles(int w, int h) {
        depthParticles.clear();
        int count = Math.max(60, w * h / 26000);
        for (int i = 0; i < count; i++) {
            DepthParticle p = new DepthParticle();
            respawnParticle(p, w, h, true);
            depthParticles.add(p);
        }
    }

    private void respawnParticle(DepthParticle p, int w, int h, boolean anywhere) {
        // anywhere=true 初始铺满整段深度；运行中重生回最远端
        p.z = anywhere ? 0.08f + random.nextFloat() * 0.92f : 1f;
        p.x = (random.nextFloat() * 2f - 1f) * w * 0.62f;
        p.y = (random.nextFloat() * 2f - 1f) * h * 0.62f;
        p.prevSx = Float.NaN;
        p.prevSy = Float.NaN;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** 背景缓存：深空渐变 + 两团星云（静态，尺寸变时重建） */
    private void buildBgCache(int w, int h) {
        if (bgCache != null) { bgCache.recycle(); bgCache = null; }
        bgCache = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bgCache);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        // 深空基底：中心深黑 → 边缘深蓝黑（径向）
        p.setShader(new android.graphics.RadialGradient(
                w / 2f, h * 0.42f, Math.max(w, h) * 0.75f,
                Color.rgb(2, 4, 10), Color.rgb(14, 26, 48),
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);

        // 左右两团半透明灰蓝星云（径向淡雾）
        drawNebula(c, p, w * 0.10f, h * 0.30f, Math.max(w, h) * 0.38f,
                Color.argb(46, 96, 128, 168));
        drawNebula(c, p, w * 0.90f, h * 0.62f, Math.max(w, h) * 0.36f,
                Color.argb(40, 88, 110, 150));
    }

    private void drawNebula(Canvas c, Paint p, float cx, float cy, float r, int color) {
        p.setShader(new RadialGradient(cx, cy, r, color, Color.argb(0, 0, 0, 0),
                Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, p);
        p.setShader(null);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        if (bgCache != null && !bgCache.isRecycled()) {
            canvas.drawBitmap(bgCache, 0, 0, null);
        }

        long t = (System.currentTimeMillis() - animStartMs);
        for (Star s : stars) {
            float twinkle = 0.55f + 0.45f
                    * (float) Math.sin(2 * Math.PI * (t % s.twinklePeriodMs) / s.twinklePeriodMs + s.phase);
            int alpha = (int) (s.baseAlpha * twinkle * 255);
            if (alpha <= 3) continue;
            starPaint.setColor(alpha << 24 | 0xFFFFFF); // 白星

            float px = s.x * w, py = s.y * h;
            if (s.bright) {
                // 亮星带十字微光
                canvas.drawCircle(px, py, s.radius, starPaint);
                int cross = (int) (alpha * 0.5f);
                starPaint.setColor(cross << 24 | 0xFFFFFF);
                canvas.drawLine(px - s.radius * 3.2f, py, px + s.radius * 3.2f, py, starPaint);
                canvas.drawLine(px, py - s.radius * 3.2f, px, py + s.radius * 3.2f, starPaint);
            } else {
                starPaint.setAntiAlias(true);
                canvas.drawCircle(px, py, s.radius, starPaint);
            }
        }

        if (depthParticlesEnabled) {
            drawDepthParticles(canvas, w, h);
        }
    }

    /** 纵深粒子：透视投影 sx=cx+x/z；匀 dz 下屏幕速度 ∝ 1/z² → 远静近驰；近端拖尾径向线 */
    private void drawDepthParticles(Canvas canvas, int w, int h) {
        final float cx = w * 0.5f, cy = h * 0.5f;
        final float density = getResources().getDisplayMetrics().density;
        final float speed = 0.16f; // 全程 ~6s 巡航穿越（非跳跃感）
        float dt = depthDt;

        for (int i = 0; i < depthParticles.size(); i++) {
            DepthParticle p = depthParticles.get(i);
            p.z -= speed * dt;

            float sx = cx + p.x / p.z;
            float sy = cy + p.y / p.z;

            // 过近或出屏(留 24px 缓冲)：重置回远端
            if (p.z <= 0.06f || sx < -24f || sx > w + 24f || sy < -24f || sy > h + 24f) {
                respawnParticle(p, w, h, false);
                continue;
            }

            float nearness = 1f - p.z; // 0 远 → 1 近
            int alpha = (int) (clamp(0.28f + nearness * 0.68f, 0f, 1f) * 255);

            if (!Float.isNaN(p.prevSx)) {
                // 中近端拖尾线（径向向外）：粗细/亮度随近度
                depthPaint.setStrokeCap(Paint.Cap.ROUND);
                depthPaint.setStrokeWidth(clamp(0.6f + nearness * 2.4f, 0.6f, 3f) * density);
                // 近端头部微青（LED 青偏好），远端融背景白
                int tint = nearness > 0.62f ? 60 : 0;
                int r = 255 - tint, g = 255, b = 255;
                depthPaint.setColor((alpha << 24) | (r << 16) | (g << 8) | b);
                canvas.drawLine(p.prevSx, p.prevSy, sx, sy, depthPaint);
            } else if (p.z > 0.72f) {
                // 远端静态小点（与背景星融合）
                depthPaint.setStrokeWidth(0f);
                int dim = (int) ((alpha * 0.55f));
                depthPaint.setColor((Math.max(dim, 3) << 24) | 0xFFFFFF);
                canvas.drawPoint(sx, sy, depthPaint);
            }

            p.prevSx = sx;
            p.prevSy = sy;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stop();
    }

    private void start() {
        if (running) return;
        running = true;
        animStartMs = System.currentTimeMillis();
        lastFrameNanos = 0L;
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    private void stop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
    }
}
