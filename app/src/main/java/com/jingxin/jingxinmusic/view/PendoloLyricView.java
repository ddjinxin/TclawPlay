package com.jingxin.jingxinmusic.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import com.jingxin.jingxinmusic.util.KrcParser;

import java.util.Collections;
import java.util.List;

/**
 * 时计歌词轨道（PendoloLyric，移植 folia pendoloGeometry + PendoloActiveLyricSweep）
 *
 * 布局：歌词沿擒纵轮圆弧排布——当前行 0°（轮心正右水平），唱过的向上卷、未唱的向下卷；
 * 行间距按行高自适应 max(角步进,(h_prev+h_cur+24px)/2R)；只画右半圆 ±90°；
 * alpha=cos^2.5(角×0.75)×(1-|行距|×0.18) 下限 0.12；当前行 1.25×，邻行缩到 0.7。
 *
 * 棘轮（folia 全量）：RK4 弹簧追行索引（K360/D20/M0.8），轮角=+spring×步进、
 * 轨道补正=-(spring-target)×步进、文字再叠 65% 差速回摆（轮动字微滞）。
 * 扫字：基色 52% 打底 + 词时间推进亮色填充；软边走"离屏位图画一次 + Paint shader mask"
 * 两段式（像素级软边，等效 folia mask-image）；扫到即触发行辉光（shadowLayer）。
 * 长行不折行：超宽自动按比例缩字号放满可用宽（下限 45%），纯 LRC 按行时长整行扫。
 *
 * 副歌呈现（folia chorus presentation）：重复行检测标记副歌，当前行进入副歌时
 * 扫字染深(accentMix 0.32→0.58)+双层辉光增强+行背光软光斑，0.42s easeOut 渐入。
 */
public class PendoloLyricView extends View {

    private static final float ARC_ANGLE_DEG = 100f;        // 总弧角（folia 默认）
    private static final int VISIBLE_WINDOW = 9;            // 弧上可见行数（含当前）
    private static final float ANGLE_STEP_RAD =
            (float) (Math.toRadians(ARC_ANGLE_DEG) / (VISIBLE_WINDOW - 1));
    private static final float ACTIVE_SCALE = 1.25f;        // 当前行放大
    // 非焦点行整体缩层（folia 原版 max(0.7,1-d×0.08)，主动偏离提焦点对比 1.36→1.60）
    private static final float NEIGHBOR_SHRINK = 0.85f;
    private static final float NEIGHBOR_FLOOR = 0.60f;

    /** 非焦点行缩放：距离衰减 0.08/行 × 整体 0.85，地板 0.60 */
    private static float neighborScale(int distance) {
        return Math.max(NEIGHBOR_FLOOR, (1f - distance * 0.08f) * NEIGHBOR_SHRINK);
    }
    private static final float LYRIC_RING_OFFSET_RATIO = 0.06f; // 歌词环外移（min(w,h) 比例）
    private static final float TEXT_ROTATION_CORRECTION = 0.65f; // 文字差速回摆比（folia -0.65×轮角）

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private List<KrcParser.LyricLine> lines = Collections.emptyList();
    private int targetIndex = -1;       // 目标行（进度判定）
    private float springPos;            // 弹簧行进值
    private float springVel;
    private float springTarget;
    private static final float SPRING_K = 360f, SPRING_D = 20f, SPRING_M = 0.8f;

    private int colorPrimary = Color.rgb(0xC9, 0xE4, 0xFF);
    private int colorAccent = Color.rgb(0x6E, 0xE8, 0xD8);
    private int positionMs;

    // 副歌标记（重复行≥2 次近似 isChorus；KRC 无副歌元数据）
    private boolean[] chorusFlags = new boolean[0];
    // 副歌呈现动画：normal 档 haloOpacity 0.34 / 时长 0.42s（folia resolvePendoloChorusPresentation）
    private static final float CHORUS_HALO_OP = 0.34f;
    private static final long CHORUS_TRANSITION_MS = 420L;
    private boolean lastChorusOn = false;
    private long chorusChangeAtMs = Long.MIN_VALUE;

    private int layoutW, layoutH;
    private float centerX, centerY, lyricRadius, availableTextWidth;
    private float lineSize, lineSizeSmall;

    /** 机芯咬合回调（弹簧步进后推给机芯：轮角=+spring×步进） */
    public interface TickListener { void onTick(float springValue, float targetValue); }
    private TickListener tickListener;

    private boolean running = false;
    private long lastFrameNs = -1L;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!running) return;
            long deltaNs = lastFrameNs < 0 ? 0 : frameTimeNanos - lastFrameNs;
            lastFrameNs = frameTimeNanos;
            float dt = Math.min(0.032f, deltaNs / 1_000_000_000f); // RK4 小步长更稳
            stepSpring(dt);
            invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public PendoloLyricView(Context context) { super(context); init(); }
    public PendoloLyricView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public PendoloLyricView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr); init();
    }

    private void init() {
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    // ===== 对外接口（同族效果页一致） =====

    public void setTickListener(TickListener l) { this.tickListener = l; }

    public void setLyricData(KrcParser.LyricData data) {
        this.lines = (data != null && data.lines != null) ? data.lines : Collections.emptyList();
        this.targetIndex = -1;
        springPos = 0f;
        springVel = 0f;
        springTarget = 0f;
        fitCache.clear();
        detectChorus();
        lastChorusOn = false;
        chorusChangeAtMs = Long.MIN_VALUE;
        invalidate();
    }

    /** 副歌近似：行文本 normalize 后出现≥2 次即副歌行（空行/间奏排除） */
    private void detectChorus() {
        chorusFlags = new boolean[lines.size()];
        if (lines.size() < 3) return;
        java.util.HashMap<String, Integer> counts = new java.util.HashMap<>();
        for (KrcParser.LyricLine line : lines) {
            String t = line != null && line.text != null ? line.text.trim() : "";
            if (t.isEmpty() || t.contains("间奏") || t.contains("纯音乐") || t.contains("独奏")) continue;
            Integer c = counts.get(t);
            counts.put(t, c != null ? c + 1 : 1);
        }
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).text != null ? lines.get(i).text.trim() : "";
            Integer c = counts.get(t);
            chorusFlags[i] = c != null && c >= 2;
        }
    }

    public void updatePosition(int positionMs) {
        this.positionMs = positionMs;
        if (lines.isEmpty()) {
            if (targetIndex != -1) { targetIndex = -1; invalidate(); }
            return;
        }
        int lo = 0, hi = lines.size() - 1, idx = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).startTime <= positionMs) { idx = mid; lo = mid + 1; }
            else hi = mid - 1;
        }
        if (idx != targetIndex) {
            targetIndex = idx;
            springTarget = Math.max(0, idx); // 索引即弹簧目标（负=歌前，钳 0）
        }
    }

    public void setColors(int primary, int accent) {
        if (primary != 0) colorPrimary = primary;
        if (accent != 0) colorAccent = accent;
        invalidate();
    }

    // ===== 弹簧（RK4：帧率无关，质量对标 framer-motion useSpring） =====

    private void stepSpring(float dt) {
        // 半隐式欧拉在 dt 尖峰时会发散；RK4 子步积分保持轨迹稳定
        float x = springPos, v = springVel;
        float h = dt / 2f;
        float k1x = v, k1v = accel(x, v);
        float k2x = v + h * k1v, k2v = accel(x + h * k1x, v + h * k1v);
        float k3x = v + h * k2v, k3v = accel(x + h * k2x, v + h * k2v);
        float k4x = v + dt * k3v, k4v = accel(x + dt * k3x, v + dt * k3v);
        springPos = x + (dt / 6f) * (k1x + 2f * k2x + 2f * k3x + k4x);
        springVel = v + (dt / 6f) * (k1v + 2f * k2v + 2f * k3v + k4v);
        if (tickListener != null) tickListener.onTick(springPos, springTarget);
    }

    private float accel(float x, float v) {
        return (-SPRING_K * (x - springTarget) - SPRING_D * v) / SPRING_M;
    }

    // ===== 布局 =====

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutW = w; layoutH = h;
        centerX = 0f;
        centerY = h * 0.5f;
        float minDim = Math.min(w, h);
        // folia：主轮 0.42 min(w,h)，歌词环再外移 6%
        lyricRadius = minDim * 0.42f + minDim * LYRIC_RING_OFFSET_RATIO;
        // 单行宽上限 = 0° 行弧点为锚的几何极限：左到屏幕边 24px、右到横屏竖排封面链(220px)/竖屏边 24px
        float rightReserve = (w > h) ? 220f : 24f;
        availableTextWidth = Math.max(140f,
                2f * Math.min(lyricRadius - 24f, w - lyricRadius - rightReserve));
        float ratio = w > h ? 0.034f : 0.055f;
        lineSize = Math.max(16f, w * ratio);
        lineSizeSmall = lineSize * 0.62f;
        fitCache.clear();
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

    // ===== 单行自适应缩字（永不折行：超宽按比例缩小字号放满可用宽） =====

    private final java.util.HashMap<String, Float> fitCache = new java.util.HashMap<>();

    /** 缩字号至单行放下：两次测量校正；下限 45% 原字号 & 12px（超长行兜底） */
    private float fitSize(String text, float size) {
        String key = availableTextWidth + "|" + size + "|" + text;
        Float hit = fitCache.get(key);
        if (hit != null) return hit;
        float floor = Math.max(12f, size * 0.45f);
        float fit = size;
        textPaint.setTextSize(size);
        float w = textPaint.measureText(text);
        if (w > availableTextWidth && w > 0f) {
            fit = Math.max(floor, size * availableTextWidth / w);
            textPaint.setTextSize(fit);
            float w2 = textPaint.measureText(text);
            if (w2 > availableTextWidth && w2 > 0f) {
                fit = Math.max(floor, fit * availableTextWidth / w2);
            }
        }
        fitCache.put(key, fit);
        return fit;
    }

    // ===== 绘制 =====

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (layoutW <= 0) return;

        if (lines.isEmpty() || targetIndex < 0) return;

        // 副歌呈现动画进度（0~1，0.42s easeOut 双向）
        boolean chorusNow = targetIndex < chorusFlags.length && chorusFlags[targetIndex];
        if (chorusNow != lastChorusOn) {
            lastChorusOn = chorusNow;
            chorusChangeAtMs = android.os.SystemClock.uptimeMillis();
        }
        float chorusAnim = 0f;
        if (chorusChangeAtMs != Long.MIN_VALUE) {
            float t = Math.min(1f,
                    (android.os.SystemClock.uptimeMillis() - chorusChangeAtMs) / (float) CHORUS_TRANSITION_MS);
            chorusAnim = chorusNow ? 1f - (1f - t) * (1f - t) : (1f - t) * (1f - t);
        }

        int centerRef = Math.max(0, Math.min(lines.size() - 1, targetIndex));
        int windowStart = Math.max(0, centerRef - 8);
        int windowEnd = Math.min(lines.size() - 1, centerRef + 8);

        // 行高/宽度数组：每行字号含 scale（active 1.25×，邻行递减 0.08/行到 0.7 下限），
        // 超宽自动缩字（单行不折行）；行间距随缩后字号自适应
        int win = windowEnd - windowStart + 1;
        float[] heights = new float[win];
        float[] widths = new float[win];
        for (int i = windowStart; i <= windowEnd; i++) {
            int d0 = Math.abs(i - targetIndex);
            float scale0 = (i != targetIndex) ? neighborScale(d0) : ACTIVE_SCALE;
            String text = lines.get(i).text;
            float size = fitSize(text, lineSize * scale0);
            textPaint.setTextSize(size);
            widths[i - windowStart] = textPaint.measureText(text);
            heights[i - windowStart] = size * 1.35f;
        }

        // 角度链：中心 0°，相邻按行高自适应
        float[] angles = new float[win];
        int anchor = targetIndex - windowStart;
        if (anchor >= 0 && anchor < angles.length) {
            angles[anchor] = 0f;
            for (int i = anchor + 1; i < angles.length; i++) {
                float spacing = Math.max(ANGLE_STEP_RAD,
                        (heights[i - 1] + heights[i] + 24f) / (2f * lyricRadius));
                angles[i] = angles[i - 1] + spacing;
            }
            for (int i = anchor - 1; i >= 0; i--) {
                float spacing = Math.max(ANGLE_STEP_RAD,
                        (heights[i + 1] + heights[i] + 24f) / (2f * lyricRadius));
                angles[i] = angles[i + 1] - spacing;
            }
        }

        // 棘轮三重角：轨道 = 角度链+轨道补正；active 文字反向回摆 35%（folia textRotationCorrection 0.65 差速）
        float trackOffset = (springPos - springTarget) * ANGLE_STEP_RAD;

        for (int i = windowStart; i <= windowEnd; i++) {
            KrcParser.LyricLine line = lines.get(i);
            if (line == null || line.text == null || line.text.isEmpty()) continue;
            float rawAngle = angles[i - windowStart] + trackOffset;
            if (Math.abs(rawAngle) >= Math.PI / 2) continue; // 只画右半圆

            float x = centerX + lyricRadius * (float) Math.cos(rawAngle);
            float y = centerY + lyricRadius * (float) Math.sin(rawAngle);

            boolean isActive = i == targetIndex;
            int distance = Math.abs(i - targetIndex);

            float alpha = Math.max(0.12f,
                    (float) Math.pow(Math.cos(rawAngle * 0.75), 2.5) * (1f - distance * 0.18f));
            float scale = isActive ? ACTIVE_SCALE : neighborScale(distance);

            drawArcLine(canvas, line, x, y,
                    rawAngle - trackOffset * (isActive ? TEXT_ROTATION_CORRECTION : 0f),
                    isActive, alpha, scale, isActive ? chorusAnim : 0f);
        }
    }

    /** 弧上一行：文本沿切线旋转；超宽缩字单行铺排；当前行扫字+辉光；chorusAnim 副歌呈现进度 0~1 */
    private void drawArcLine(Canvas canvas, KrcParser.LyricLine line, float x, float y,
                             float angle, boolean isActive, float alpha, float scale, float chorusAnim) {
        String text = line.text;
        float size = fitSize(text, lineSize * scale); // scale 已含 active 1.25×（防双重放大）
        textPaint.setTextSize(size);

        int sc = canvas.save();
        canvas.rotate((float) Math.toDegrees(angle), x, y);

        android.graphics.Paint.FontMetrics fm = textPaint.getFontMetrics();
        float lineH = size * 1.35f;
        float baseline = y - (fm.ascent + fm.descent) * 0.5f; // 垂直居中于弧点
        float total = textPaint.measureText(text);

        if (!isActive) {
            textPaint.setColor(withAlpha(colorPrimary, alpha * 0.52f));
            textPaint.setShader(null);
            textPaint.setShadowLayer(0, 0, 0, 0);
            canvas.drawText(text, x, baseline, textPaint);
            canvas.restoreToCount(sc);
            return;
        }

        // 当前行：52% 基色打底
        int cBase = withAlpha(colorPrimary, alpha * 0.52f);
        textPaint.setColor(cBase);
        textPaint.setShader(null);
        textPaint.setShadowLayer(0, 0, 0, 0);
        canvas.drawText(text, x, baseline, textPaint);

        // 副歌呈现：扫字染深 fill=mix(primary,accent,0.32+0.26×anim)+双层辉光+行背光（folia accentMix/chorus glow/halo）
        int fillColor = lerpColor(colorPrimary, colorAccent, 0.32f + 0.26f * chorusAnim);

        if (chorusAnim > 0.01f) {
            drawChorusHalo(canvas, x, y, total, lineH, chorusAnim, alpha);
        }
        // 扫字亮色层：填充宽按词时间推进
        List<KrcParser.LyricWord> words = line.words;
        float fillWidth;
        long lineEnd = line.startTime + Math.max(line.duration, 1L);
        if (words != null && !words.isEmpty()) {
            fillWidth = computeFillWidth(words);
        } else {
            float t = (positionMs - line.startTime) / (float) (lineEnd - line.startTime);
            fillWidth = total * clamp01(t);
        }
        if (fillWidth > 0.5f) {
            float startX = x - total / 2f;
            float edge = Math.min(Math.max(size * 0.42f, 8f), 16f);
            float fill = Math.min(fillWidth, total);
            float textTop = baseline + fm.ascent - 6f, textBottom = baseline + fm.descent + 6f;

            // 行辉光：扫过区域带 shadowLayer 辉光；副歌时双层（外层 accent + 内层 fill，folia 双 drop-shadow）
            boolean chorus = chorusAnim > 0.01f;
            if (chorus) {
                textPaint.setShadowLayer(size * (0.28f + 0.34f * chorusAnim), 0, 0,
                        withAlpha(colorAccent, alpha * (0.6f + 0.15f * chorusAnim)));
            } else {
                textPaint.setShadowLayer(size * 0.28f, 0, 0, withAlpha(colorAccent, alpha * 0.85f));
            }

            // 已扫满段：硬边 + 满亮色（副歌扫字色=fillColor 染深）
            if (fill > edge) {
                int sc2 = canvas.save();
                android.graphics.Path solid = new android.graphics.Path();
                solid.addRect(startX - 6f, textTop,
                        startX + fill - edge, textBottom, android.graphics.Path.Direction.CW);
                canvas.clipPath(solid);
                if (!chorus) {
                    textPaint.setColor(withAlpha(colorAccent, alpha));
                    canvas.drawText(text, x, baseline, textPaint);
                } else {
                    // 外层大辉光（fillColor 染色）
                    textPaint.setShadowLayer(size * (0.3f + 0.32f * chorusAnim), 0, 0,
                            withAlpha(fillColor, alpha * 0.72f));
                    textPaint.setColor(withAlpha(fillColor, alpha));
                    canvas.drawText(text, x, baseline, textPaint);
                    // 内层核亮
                    textPaint.setShadowLayer(size * 0.18f, 0, 0,
                            withAlpha(fillColor, alpha * 0.42f));
                    canvas.drawText(text, x, baseline, textPaint);
                }
                canvas.restoreToCount(sc2);
            }
            // 前锋软边：edge 段满亮 → 透明的像素级渐隐（shader 直接作用于文字，等效 mask）
            int sc3 = canvas.save();
            android.graphics.Path soft = new android.graphics.Path();
            soft.addRect(startX + fill - edge, textTop,
                    startX + fill + edge, textBottom, android.graphics.Path.Direction.CW);
            canvas.clipPath(soft);
            int sweepColor = chorus ? fillColor : colorAccent;
            textPaint.setShader(new LinearGradient(startX + fill - edge, 0f,
                    startX + fill + edge, 0f,
                    withAlpha(sweepColor, alpha), 0x00000000, Shader.TileMode.CLAMP));
            textPaint.setColor(withAlpha(sweepColor, alpha));
            canvas.drawText(text, x, baseline, textPaint);
            textPaint.setShader(null);
            canvas.restoreToCount(sc3);

            textPaint.setShadowLayer(0, 0, 0, 0);
        }
        canvas.restoreToCount(sc);
    }

    /** 按词时间算已扫宽度（词内线性插值） */
    private float computeFillWidth(List<KrcParser.LyricWord> words) {
        float total = 0f;
        float[] widths = new float[words.size()];
        for (int i = 0; i < words.size(); i++) {
            widths[i] = textPaint.measureText(words.get(i).text);
            total += widths[i];
        }
        float fill = 0f;
        for (int i = 0; i < words.size(); i++) {
            KrcParser.LyricWord w = words.get(i);
            long end = w.startTime + Math.max(w.duration, 1L);
            if (positionMs >= end) {
                fill += widths[i];
            } else if (positionMs > w.startTime) {
                float t = (positionMs - w.startTime) / (float) (end - w.startTime);
                fill += widths[i] * t;
                break;
            } else {
                break;
            }
        }
        return Math.min(fill, total);
    }

    /** 副歌行背光软光斑（folia chorus halo 直译：42% 处径向渐变，0.14/0.035 双 alpha 带→透明） */
    private void drawChorusHalo(Canvas canvas, float x, float y, float totalW, float blockH,
                                float anim, float alpha) {
        if (totalW <= 0f) return;
        float op = CHORUS_HALO_OP * anim * alpha;
        if (op < 0.01f) return;
        float gx = x - totalW / 2f + totalW * 0.42f;
        float r = Math.max(totalW * 0.62f, sizeForHalo());
        int c1 = withAlpha(colorAccent, 0.14f * op);
        int c2 = withAlpha(colorAccent, 0.035f * op);
        android.graphics.RadialGradient g = new android.graphics.RadialGradient(
                0f, 0f, r,
                new int[]{c1, c2, 0x00000000},
                new float[]{0f, 0.55f, 0.82f},
                Shader.TileMode.CLAMP);
        Paint hp = new Paint(Paint.ANTI_ALIAS_FLAG);
        hp.setShader(g);
        hp.setStyle(Paint.Style.FILL);
        int sc = canvas.save();
        canvas.translate(gx, y);
        // 椭圆：横拉伸铺满行宽，竖向收敛到块高（folia rounded-2xl inset -0.7em/-1.1em 的椭圆近似）
        canvas.scale(1f, Math.max(0.42f, Math.min(1f, blockH / (r * 1.2f))));
        canvas.drawCircle(0f, 0f, r, hp);
        canvas.restoreToCount(sc);
    }

    private float sizeForHalo() { return lineSize * ACTIVE_SCALE; }

    private static int lerpColor(int from, int to, float t) {
        if (t <= 0f) return from;
        if (t >= 1f) return to;
        int r = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * t);
        int g = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * t);
        int b = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t);
        return Color.rgb(r, g, b);
    }

    private static float clamp01(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

    private static int withAlpha(int color, float alpha) {
        int a = (int) (Math.min(1f, Math.max(0f, alpha)) * 255);
        return (a << 24) | (color & 0xFFFFFF);
    }
}
