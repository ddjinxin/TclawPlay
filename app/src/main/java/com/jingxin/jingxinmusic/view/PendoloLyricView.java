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

import java.util.ArrayList;
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
 * 长行自动断行（可用宽逐词折行，多行沿弧并列）；纯 LRC 按行时长整行扫。
 */
public class PendoloLyricView extends View {

    private static final float ARC_ANGLE_DEG = 100f;        // 总弧角（folia 默认）
    private static final int VISIBLE_WINDOW = 9;            // 弧上可见行数（含当前）
    private static final float ANGLE_STEP_RAD =
            (float) (Math.toRadians(ARC_ANGLE_DEG) / (VISIBLE_WINDOW - 1));
    private static final float ACTIVE_SCALE = 1.25f;        // 当前行放大
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
        wrapCache.clear();
        invalidate();
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
        float maxItemX = centerX + lyricRadius + minDim * 0.42f * 0.1f;
        availableTextWidth = Math.max(140f, Math.min(w * 0.46f, w - maxItemX - 48f));
        float ratio = w > h ? 0.034f : 0.055f;
        lineSize = Math.max(16f, w * ratio);
        lineSizeSmall = lineSize * 0.62f;
        wrapCache.clear();
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

    // ===== 折行（长行多行铺排，等效 pretext 排版的基本行为） =====

    private static final class WrapResult {
        final String[] rows;      // 折行后的行数组
        final float width;        // 最宽行宽
        WrapResult(String[] rows, float width) { this.rows = rows; this.width = width; }
    }
    private final java.util.HashMap<String, WrapResult> wrapCache = new java.util.HashMap<>();

    /** 按可用宽逐"块"折行（块=连续 ASCII 词 或 单个 CJK/标点字），折不满整块放下一行 */
    private WrapResult wrap(String text, float size) {
        String key = size + "|" + text;
        WrapResult hit = wrapCache.get(key);
        if (hit != null) return hit;
        textPaint.setTextSize(size);
        List<String> rows = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        // 先切块
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int len = Character.charCount(cp);
            boolean asciiWord = cp < 0x2E80;
            if (asciiWord) {
                int j = i;
                while (j < text.length()) {
                    int c2 = text.codePointAt(j);
                    if (c2 >= 0x2E80 || c2 == ' ') break;
                    j += Character.charCount(c2);
                }
                if (j == i) j = i + 1; // 空格/遇首字符即停：至少消费一字符，防 i 不前进死循环（OOM 元凶）
                chunks.add(text.substring(i, j));
                i = j;
            } else {
                chunks.add(text.substring(i, i + len));
                i += len;
            }
        }
        float maxW = 0f;
        for (String chunk : chunks) {
            float cw = textPaint.measureText(chunk);
            float curW = cur.length() == 0 ? 0 : textPaint.measureText(cur.toString());
            if (cur.length() > 0 && curW + cw > availableTextWidth) {
                rows.add(cur.toString());
                maxW = Math.max(maxW, curW);
                cur.setLength(0);
            }
            cur.append(chunk);
        }
        if (cur.length() > 0) {
            float curW = textPaint.measureText(cur.toString());
            rows.add(cur.toString());
            maxW = Math.max(maxW, curW);
        }
        WrapResult r = new WrapResult(rows.toArray(new String[0]), maxW);
        wrapCache.put(key, r);
        return r;
    }

    // ===== 绘制 =====

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (layoutW <= 0) return;

        if (lines.isEmpty() || targetIndex < 0) return;

        int centerRef = Math.max(0, Math.min(lines.size() - 1, targetIndex));
        int windowStart = Math.max(0, centerRef - 8);
        int windowEnd = Math.min(lines.size() - 1, centerRef + 8);

        // 行高/宽度数组：字号含每行 scale（active 1.25×，邻行递减 0.08/行到 0.7 下限），
        // 行间距与折行宽同步缩放（修正：小字行的行距间隙不再偏大）
        int win = windowEnd - windowStart + 1;
        float[] heights = new float[win];
        float[] widths = new float[win];
        for (int i = windowStart; i <= windowEnd; i++) {
            int d0 = Math.abs(i - targetIndex);
            float scale0 = (i != targetIndex) ? Math.max(0.7f, 1f - d0 * 0.08f) : ACTIVE_SCALE;
            float size = lineSize * scale0;
            WrapResult wr = wrap(lines.get(i).text, size);
            widths[i - windowStart] = wr.width;
            heights[i - windowStart] = wr.rows.length * size * 1.35f;
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
            float scale = isActive ? ACTIVE_SCALE : Math.max(0.7f, 1f - distance * 0.08f);

            drawArcLine(canvas, line, x, y,
                    rawAngle - trackOffset * (isActive ? TEXT_ROTATION_CORRECTION : 0f),
                    isActive, alpha, scale);
        }
    }

    /** 弧上一行：文本沿切线旋转；多行折行时行块沿径向并列；当前行扫字+辉光 */
    private void drawArcLine(Canvas canvas, KrcParser.LyricLine line, float x, float y,
                             float angle, boolean isActive, float alpha, float scale) {
        float size = lineSize * scale; // scale 已含 active 1.25×（防双重放大）
        textPaint.setTextSize(size);
        WrapResult wr = wrap(line.text, size);

        int sc = canvas.save();
        canvas.rotate((float) Math.toDegrees(angle), x, y);

        android.graphics.Paint.FontMetrics fm = textPaint.getFontMetrics();
        float lineH = size * 1.35f;
        float blockH = wr.rows.length * lineH;
        float topBaseline = y - blockH / 2f - (fm.ascent + fm.descent) * 0.5f - fm.ascent;

        if (!isActive) {
            int c = withAlpha(colorPrimary, alpha * 0.52f);
            textPaint.setColor(c);
            textPaint.setShader(null);
            textPaint.setShadowLayer(0, 0, 0, 0);
            for (int r = 0; r < wr.rows.length; r++) {
                canvas.drawText(wr.rows[r], x, topBaseline + r * lineH, textPaint);
            }
            canvas.restoreToCount(sc);
            return;
        }

        // 当前行：52% 基色打底（多行一样铺底）
        int cBase = withAlpha(colorPrimary, alpha * 0.52f);
        textPaint.setColor(cBase);
        textPaint.setShader(null);
        textPaint.setShadowLayer(0, 0, 0, 0);
        for (int r = 0; r < wr.rows.length; r++) {
            canvas.drawText(wr.rows[r], x, topBaseline + r * lineH, textPaint);
        }

        // 扫字亮色层：填充宽按词时间推进（跨折行行连续累计宽度）
        List<KrcParser.LyricWord> words = line.words;
        float fillWidth;
        long lineEnd = line.startTime + Math.max(line.duration, 1L);
        if (words != null && !words.isEmpty()) {
            fillWidth = computeFillWidth(words);
        } else {
            float t = (positionMs - line.startTime) / (float) (lineEnd - line.startTime);
            fillWidth = widthsTotal(wr, size) * clamp01(t);
        }
        if (fillWidth > 0.5f) {
            float total = widthsTotal(wr, size);
            float startX = x - total / 2f; // 连续排：扫总宽跨折行
            float remaining = fillWidth;
            float rowBaseY = topBaseline;
            float consumed = 0f;
            for (int r = 0; r < wr.rows.length && remaining > 0.5f; r++) {
                float rowW = textPaint.measureText(wr.rows[r]);
                float rowFill = Math.min(remaining, rowW);
                float rowStartX = startX + consumed;
                float edge = Math.min(Math.max(size * 0.42f, 8f), 16f);
                float textTop = rowBaseY + fm.ascent - 6f, textBottom = rowBaseY + fm.descent + 6f;

                // 行辉光：扫过区域带 shadowLayer 辉光（等效 enableLineGlow 的 active 段）
                textPaint.setShadowLayer(size * 0.28f, 0, 0, withAlpha(colorAccent, alpha * 0.85f));

                // 已扫满段：硬边 + 满亮色
                if (rowFill > edge) {
                    int sc2 = canvas.save();
                    android.graphics.Path solid = new android.graphics.Path();
                    solid.addRect(rowStartX - 6f, textTop,
                            rowStartX + rowFill - edge, textBottom, android.graphics.Path.Direction.CW);
                    canvas.clipPath(solid);
                    textPaint.setColor(withAlpha(colorAccent, alpha));
                    canvas.drawText(wr.rows[r], x, rowBaseY, textPaint);
                    canvas.restoreToCount(sc2);
                }
                // 前锋软边：edge 段满亮 → 透明的像素级渐隐（shader 直接作用于文字，等效 mask）
                int sc3 = canvas.save();
                android.graphics.Path soft = new android.graphics.Path();
                soft.addRect(rowStartX + rowFill - edge, textTop,
                        rowStartX + rowFill + edge, textBottom, android.graphics.Path.Direction.CW);
                canvas.clipPath(soft);
                textPaint.setShader(new LinearGradient(rowStartX + rowFill - edge, 0f,
                        rowStartX + rowFill + edge, 0f,
                        withAlpha(colorAccent, alpha), 0x00000000, Shader.TileMode.CLAMP));
                textPaint.setColor(withAlpha(colorAccent, alpha));
                canvas.drawText(wr.rows[r], x, rowBaseY, textPaint);
                textPaint.setShader(null);
                canvas.restoreToCount(sc3);

                textPaint.setShadowLayer(0, 0, 0, 0);
                remaining -= rowW;
                consumed += rowW;
                rowBaseY += lineH;
            }
        }
        canvas.restoreToCount(sc);
    }

    /** 折行总宽（各行之和；扫字累计基准） */
    private float widthsTotal(WrapResult wr, float size) {
        if (wr.rows.length <= 0) return 0f;
        if (wr.rows.length == 1) return wr.width;
        float t = 0f;
        for (String row : wr.rows) t += textPaint.measureText(row);
        return t;
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

    private static float clamp01(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

    private static int withAlpha(int color, float alpha) {
        int a = (int) (Math.min(1f, Math.max(0f, alpha)) * 255);
        return (a << 24) | (color & 0xFFFFFF);
    }
}
