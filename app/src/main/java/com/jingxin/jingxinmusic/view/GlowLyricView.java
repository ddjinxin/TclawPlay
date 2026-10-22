package com.jingxin.jingxinmusic.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import com.jingxin.jingxinmusic.util.KrcParser;

import java.util.Collections;
import java.util.List;

/**
 * 流光歌词（第二种歌词动效·移植 folia classic 模式）
 *
 * 灵魂：逐词三态机 waiting→active→passed，词随音点亮——
 * - waiting：不可见预摆位（alpha 0、scale 0.5、预转角）
 * - active：欠阻尼弹簧弹到 base×1.4、alpha 快速到 1、切高亮色；
 *   词内按字素均分时长逐字点亮双光晕（内圈 0.7 alpha + 外圈满强度，辉光层与正文分离，光大而不糊字）
 * - passed：0.5s 回落 alpha 0.82 / scale base / 颜色回基色（0.8s），并 5s 缓慢转 passedRotate
 *
 * folia 全要素保留：行级随机散布三档（CALM/NORMAL/CHAOTIC）、词宽防重叠（active 1.4× 溢出预留）、
 * 行容器呼吸浮动（y±(10/14/18px)、周期 8.5/7/5.8s）、行转场缩放+模糊
 * （API31+ RenderNode+RenderEffect 真模糊、低版本直接绘制）、自适应时序分档（micro/short/normal）,
 * 间奏行（……）dot 放大静置。（副歌涟漪已砍：本地化后视觉过重）
 *
 * 驱动：posMs 驱动词三态（纯函数式，seek 兼容），systemMs 驱动涟漪/呼吸/转场曲线。
 */
public class GlowLyricView extends View {

    /** 动效强度三档（folia theme.animationIntensity 全保留；默认 NORMAL 贴合极简科技风） */
    public enum Intensity { CALM, NORMAL, CHAOTIC }

    private static final int COLOR_BASE = Color.rgb(0xB9, 0xD7, 0xF2);    // 词基色（冷银，兜底）
    private static final int COLOR_CURRENT = Color.rgb(0xC9, 0xE4, 0xFF); // 高亮色（播放页高亮同源，兜底）

    // 自适应时序阈值（folia renderHints：micro<100ms 瞬时 / short<180ms 轻量 / normal 完整）
    private static final long MICRO_MS = 100L, SHORT_MS = 180L;
    private static final long LOOKAHEAD_NORMAL = 150L, LOOKAHEAD_FAST = 80L, LOOKAHEAD_INSTANT = 30L;
    private static final long MIN_DISPLAY_NORMAL = 100L, MIN_DISPLAY_FAST = 120L, MIN_DISPLAY_INSTANT = 80L;

    private static final long LINE_EXIT_MS = 300L;  // 旧行退场（folia exit 0.3s）

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private List<KrcParser.LyricLine> lines = Collections.emptyList();
    private int currentIndex = -1;
    private int colorHighlight = COLOR_CURRENT;
    private Intensity intensity = Intensity.NORMAL;
    private int positionMs;

    // ===== 当前行的词配置（确定性随机：seed = line.startTime，换行/重绘不重排） =====
    private static final class WordConf {
        KrcParser.LyricWord word;   // null = 纯 LRC 伪词（整行一个）
        String text;
        float cx, cy;              // 安放中心（含随机散布）
        float driftX, driftY;      // waiting 预摆位漂移（spring 起点用）
        float baseScale;           // 各自稳定 scale
        float rotate;              // 基准转角
        float passedRotate;        // passed 后 5s 缓转目标（相对基准）
        int state;                 // 0=waiting 1=active 2=passed（上一帧）
        long stateChangeMs;        // 最近状态切换时刻（system 轴）
        float lastScale, lastAlpha, lastRotate; // 退场定格快照
    }
    private WordConf[] wordConfs = new WordConf[0];
    private float lineTextSize;
    private long lineEnterMs = -1L;          // 新行入场起点（-1 = micro 无行动画）
    private long lineEnterDuration = 420L;
    private int lineTransitionClass;         // 0=normal 1=fast 2=micro

    // 旧行退场：词布局定格快照（folia popLayout：旧行放大+模糊淡出，新行同时进入）
    private WordConf[] exitConfs;
    private long exitStartMs;
    private float exitTextSize;

    private int layoutW, layoutH;
    private float textSizeCurrent, textSizeNext;

    public GlowLyricView(Context context) { super(context); init(); }
    public GlowLyricView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public GlowLyricView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr); init();
    }

    private void init() {
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    // ===== 对外接口（与 StarLyricView 同构，EffectPage 零适配） =====

    public void setLyricData(KrcParser.LyricData data) {
        this.lines = (data != null && data.lines != null) ? data.lines : Collections.emptyList();
        this.currentIndex = -1;
        exitConfs = null;
        requestLayout();
        invalidate();
    }

    /** 进度回调：换行判定（二分找 currentTime >= startTime 的最后一行） */
    public void updatePosition(int positionMs) {
        this.positionMs = positionMs;
        if (lines.isEmpty()) {
            if (currentIndex != -1) { currentIndex = -1; exitConfs = null; invalidate(); }
            return;
        }
        int lo = 0, hi = lines.size() - 1, idx = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).startTime <= positionMs) { idx = mid; lo = mid + 1; }
            else hi = mid - 1;
        }
        if (idx != currentIndex) onLineChanged(idx);
        postInvalidateOnAnimation();
    }

    public void setHighlightColor(int color) {
        if (color != 0) this.colorHighlight = color;
        invalidate();
    }

    public void setIntensity(Intensity i) {
        if (i == null) return;
        this.intensity = i;
        if (currentIndex >= 0 && currentIndex < lines.size()) layoutLine(currentIndex);
        exitConfs = null;
        invalidate();
    }

    // ===== 行切换 =====

    private void onLineChanged(int newIndex) {
        // 旧行退场快照：定格当前词视觉参数（外推放大+模糊淡出）
        if (wordConfs.length > 0 && exitConfs == null) {
            exitConfs = wordConfs;
            exitStartMs = System.currentTimeMillis();
            exitTextSize = lineTextSize;
        }
        currentIndex = newIndex;
        if (newIndex < 0 || newIndex >= lines.size()) {
            wordConfs = new WordConf[0];
            invalidate();
            return;
        }
        layoutLine(newIndex);
        KrcParser.LyricLine line = lines.get(newIndex);
        long dur = Math.max(line.duration, 0);
        if (lineTransitionClass == 2) {          // micro：无行动画
            lineEnterMs = -1L;
        } else if (lineTransitionClass == 1) {   // short：轻量档
            lineEnterMs = System.currentTimeMillis();
            lineEnterDuration = Math.max(45L, Math.min(60L, dur * 45 / 100));
        } else {                                 // normal：clamp(dur×0.34, 220~420)
            lineEnterMs = System.currentTimeMillis();
            lineEnterDuration = Math.min(420L, Math.max(220L, Math.max(dur, 120L) * 34 / 100));
        }
        invalidate();
    }

    /** 布局一行：词配置 + 防重叠间距（active 1.4× 溢出预留）+ 确定性随机散布 */
    private void layoutLine(int lineIdx) {
        KrcParser.LyricLine line = lines.get(lineIdx);
        long dur = Math.max(line.duration, 0);
        lineTransitionClass = dur < MICRO_MS ? 2 : dur < SHORT_MS ? 1 : 0;

        boolean hasWords = line.words != null && !line.words.isEmpty();
        boolean interlude = line.text != null && line.text.matches("[\\.·。…]+");

        float density = getResources().getDisplayMetrics().density;
        boolean chaotic = intensity == Intensity.CHAOTIC;
        boolean calm = intensity == Intensity.CALM;
        float spread = chaotic ? 60f * density : calm ? 0f : 20f * density;
        float rotRange = chaotic ? 30f : calm ? 0f : 5f;

        // 纯 LRC：整行一个伪词（无逐字数据，退化为整行呈现，词级三态按行时间走）
        if (!hasWords) {
            float size = fitTextSize(line.text, textSizeCurrent);
            lineTextSize = size;
            WordConf c = new WordConf();
            c.word = null;
            c.text = line.text != null ? line.text : "";
            c.cx = layoutW / 2f;
            c.cy = currentLineY();
            c.driftX = 0; c.driftY = -30f * density;
            c.baseScale = interlude ? 1.5f : 1f;
            c.rotate = 0; c.passedRotate = 0;
            c.state = 0; c.stateChangeMs = System.currentTimeMillis();
            wordConfs = new WordConf[]{ c };
            return;
        }

        List<KrcParser.LyricWord> words = line.words;
        int n = words.size();
        long seed = line.startTime;

        // 目标字号 → 测宽 → 总宽（含防重叠 gap）→ 超宽收缩一轮
        float size = textSizeCurrent;
        textPaint.setTextSize(size);
        float[] widths = new float[n];
        float[] scales = new float[n];
        for (int i = 0; i < n; i++) {
            widths[i] = textPaint.measureText(words.get(i).text);
            scales[i] = interlude ? 1.5f
                    : chaotic ? 0.8f + rand(seed, i, 4) * 0.6f
                    : 1.1f + rand(seed, i, 4) * 0.2f;
        }
        float gapBase = 0.05f * size;
        float spacing = 0.7f;
        float total = measureTotal(widths, scales, gapBase, spacing, size, chaotic);
        if (total > layoutW * 0.94f && !interlude) {
            float shrink = layoutW * 0.94f / total;
            float newSize = Math.max(textSizeNext * 1.1f, size * shrink);
            if (newSize < size) {
                size = newSize;
                textPaint.setTextSize(size);
                for (int i = 0; i < n; i++) widths[i] = textPaint.measureText(words.get(i).text);
                total = measureTotal(widths, scales, gapBase * (size / textSizeCurrent), spacing, size, chaotic);
            }
        }
        lineTextSize = size;

        // 行排版锚点（folia justify：calm/normal 居中；chaotic 2/5 概率偏置起/终点）
        float startX;
        if (!chaotic || rand(seed, 99, 1) < 0.6f) startX = (layoutW - total) / 2f;
        else if (rand(seed, 99, 2) < 0.5f) startX = layoutW * 0.12f;
        else startX = layoutW * 0.88f - total;

        float lineCenterY = currentLineY();

        wordConfs = new WordConf[n];
        float x = startX;
        for (int i = 0; i < n; i++) {
            KrcParser.LyricWord w = words.get(i);
            float gapAfter = (i + 1 < n) ? gapAfter(widths, scales, i, gapBase, spacing, size, chaotic) : 0f;
            float dx = (rand(seed, i, 1) - 0.5f) * spread * 2f;
            float dy = (rand(seed, i, 2) - 0.5f) * spread * 1.2f;

            WordConf c = new WordConf();
            c.word = w;
            c.text = w.text;
            c.cx = x + widths[i] / 2f + dx;
            c.cy = lineCenterY + dy;
            c.driftX = (rand(seed, i, 5) - 0.5f) * 80f * density;   // waiting 漂移（spring 起点）
            c.driftY = (rand(seed, i, 6) - 0.5f) * 60f * density - 20f * density;
            c.baseScale = scales[i];
            c.rotate = interlude ? 0f : (rand(seed, i, 3) - 0.5f) * rotRange * 2f;
            c.passedRotate = interlude ? 0f : (rand(seed, i, 8) - 0.5f) * 30f;
            // chaotic 独立浮动（flex-start/end 的水平外推）
            if (chaotic && rand(seed, i, 7) > 0.75f) {
                c.cx += (rand(seed, i, 8) > 0.5f ? -1f : 1f) * spread * 1.4f;
            }
            c.state = 0;
            c.stateChangeMs = System.currentTimeMillis();
            wordConfs[i] = c;
            x += widths[i] + gapAfter;
        }
    }

    /** 行总宽：词宽 + 防重叠 gap（active 峰值溢出半宽之和，folia 公式） */
    private static float measureTotal(float[] widths, float[] scales, float gapBase,
                                      float spacing, float size, boolean chaotic) {
        float total = 0f;
        for (int i = 0; i < widths.length; i++) {
            total += widths[i] + (i + 1 < widths.length
                    ? gapAfter(widths, scales, i, gapBase, spacing, size, chaotic) : 0f);
        }
        return total;
    }

    private static float gapAfter(float[] widths, float[] scales, int i, float gapBase,
                                  float spacing, float size, boolean chaotic) {
        float sI = scales[i] * 1.4f, sNext = scales[i + 1] * 1.4f;
        float overflow = (widths[i] * (sI - 1f) / 2f + widths[i + 1] * (sNext - 1f) / 2f + gapBase) * spacing;
        float minGap = (chaotic ? 0.08f : 0.12f) * size * spacing;
        return Math.max(overflow, minGap);
    }

    /** folia 确定性随机：sin 哈希到 [0,1)（seed = line.startTime，重绘不重排） */
    private static float rand(long seed, int index, int offset) {
        double v = Math.sin((seed + index) * 12.9898 + offset * 78.233) * 43758.5453;
        return (float) (v - Math.floor(v));
    }

    // ===== 布局基准 =====

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutW = w; layoutH = h;
        textSizeCurrent = currentTextSizePx();
        textSizeNext = textSizeCurrent * 0.55f;
        if (currentIndex >= 0 && currentIndex < lines.size()) layoutLine(currentIndex);
    }

    private float currentTextSizePx() {
        float ratio = layoutW > layoutH ? 0.048f : 0.08f;
        return Math.max(18f, layoutW * ratio);
    }

    private boolean isPortrait() { return layoutH > layoutW; }

    /** 词中心线：垂直 42% 屏高（高于星空页 50%——下方留出封面流呼吸位） */
    private float currentLineY() {
        return layoutH * 0.42f;
    }

    private float fitTextSize(String text, float targetSize) {
        if (text == null || text.isEmpty() || layoutW <= 0) return targetSize;
        float avail = layoutW * 0.94f;
        textPaint.setTextSize(targetSize);
        float measured = textPaint.measureText(text);
        if (measured <= avail) return targetSize;
        return Math.max(textSizeNext * 1.1f, targetSize * (avail / measured));
    }

    // ===== 绘制 =====

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (layoutW <= 0) return;
        long now = System.currentTimeMillis();

        // 旧行退场：定格参数外推——放大 1.1×、二次淡出、模糊（folia exit: scale1.1/blur20/0.3s）
        if (exitConfs != null) {
            float t = (now - exitStartMs) / (float) LINE_EXIT_MS;
            if (t >= 1f) {
                exitConfs = null;
            } else {
                float fade = (1f - t) * (1f - t);
                float exScale = 1f + 0.1f * t;
                textPaint.setTextSize(exitTextSize);
                textPaint.setFakeBoldText(false);
                for (WordConf c : exitConfs) {
                    if (c.lastAlpha <= 0.01f) continue;
                    int a = (int) (c.lastAlpha * fade * 255);
                    textPaint.setColor((a << 24) | (COLOR_BASE & 0xFFFFFF));
                    int sc = canvas.save();
                    canvas.rotate(c.lastRotate, c.cx, c.cy);
                    canvas.scale(c.lastScale * exScale, c.lastScale * exScale, c.cx, c.cy);
                    canvas.drawText(c.text, c.cx, c.cy + baselineFromCenter(exitTextSize), textPaint);
                    canvas.restoreToCount(sc);
                }
                postInvalidateOnAnimation();
            }
        }

        // 当前行
        if (currentIndex >= 0 && currentIndex < lines.size() && wordConfs.length > 0) {
            drawLine(canvas, now);
        }

        postInvalidateOnAnimation(); // 呼吸/辉光常驻
    }

    private void drawLine(Canvas canvas, long now) {
        float density = getResources().getDisplayMetrics().density;

        // 行入场进度（normal：alpha 0→1 / scale 0.9→1 / blur 10→0；fast 更轻；micro 无）
        float enterT = 1f;
        float containerScale = 1f, containerAlpha = 1f;
        float enterBlur = 0f;
        if (lineEnterMs > 0) {
            enterT = (now - lineEnterMs) / (float) lineEnterDuration;
            if (enterT >= 1f) { enterT = 1f; lineEnterMs = -1L; }
            else postInvalidateOnAnimation();
        }
        if (enterT < 1f && lineTransitionClass != 2) {
            boolean fast = lineTransitionClass == 1;
            containerAlpha = fast ? 0.35f + 0.65f * enterT : enterT;
            containerScale = fast ? 0.96f + 0.04f * enterT : 0.9f + 0.1f * enterT;
            enterBlur = (fast ? 4f : 10f) * (1f - enterT) * density;
        }

        // 行容器呼吸浮动（folia breathingFloat：y ±(10/14/18px)、scale ±1%、周期 8.5/7/5.8s）
        float breathAmp = (intensity == Intensity.CHAOTIC ? 18f : intensity == Intensity.CALM ? 10f : 14f) * density;
        float breathDur = intensity == Intensity.CHAOTIC ? 5800f : intensity == Intensity.CALM ? 8500f : 7000f;
        float phase = (now % (long) breathDur) / breathDur;
        float breathY = (float) Math.sin(phase * Math.PI * 2) * breathAmp;
        float breathScale = 1f + 0.01f * (float) Math.sin(phase * Math.PI * 2);

        // 容器变换：入口 scale × 呼吸 scale，绕行中心；入场期整行柔和：
        // API31+ RenderNode 离屏录制 + createBlurEffect 真模糊（folia enter blur 10→0）；低版本直接绘制
        float lineCY = currentLineY();
        int sc = canvas.save();
        float totalScale = containerScale * breathScale;
        canvas.scale(totalScale, totalScale, layoutW / 2f, lineCY);
        canvas.translate(0, breathY);

        textPaint.setTextSize(lineTextSize);
        textPaint.setFakeBoldText(false);

        boolean layered = enterBlur > 0.5f && android.os.Build.VERSION.SDK_INT >= 31;
        if (layered) {
            if (blurNode == null) blurNode = new android.graphics.RenderNode("glowLineEnter");
            int top = (int) (lineCY - lineTextSize * 2f), bottom = (int) (lineCY + lineTextSize * 2f);
            blurNode.setPosition(0, top, layoutW, bottom);
            Canvas rc = blurNode.beginRecording();
            // 关键：录制画布是节点相对坐标，先反平移把全局坐标换算回来
            // （不这么做整行会被下移 top 像素+底部被节点区域裁切——即"行首字下沉一半再跳回"）
            rc.save();
            rc.translate(0, -top);
            for (WordConf c : wordConfs) drawWord(rc, c, now, containerAlpha);
            rc.restore();
            blurNode.endRecording();
            blurNode.setRenderEffect(RenderEffect.createBlurEffect(enterBlur, enterBlur, Shader.TileMode.CLAMP));
            canvas.drawRenderNode(blurNode);
        } else {
            for (WordConf c : wordConfs) drawWord(canvas, c, now, containerAlpha);
        }

        canvas.restoreToCount(sc);
    }

    private android.graphics.RenderNode blurNode;

    /** 单词三态 + 字素辉光绘制；同时写入定格快照（供退场外推） */
    private void drawWord(Canvas canvas, WordConf c, long now, float containerAlpha) {
        float alpha, scale, rotate;
        int color;

        int st = computeState(c);
        if (st != c.state) {
            c.state = st;
            c.stateChangeMs = now;
        }
        long el = now - c.stateChangeMs;

        if (c.word == null) {
            // 纯 LRC 伪词：整行按行时间三态（行首→行尾）
            KrcParser.LyricLine line = lines.get(currentIndex);
            long start = line.startTime, end = start + Math.max(line.duration, 1L);
            float pos01 = positionMs < start ? 0f
                    : positionMs > end ? 1f : (positionMs - start) / (float) (end - start);
            if (pos01 <= 0f) { c.lastAlpha = 0; return; }
            float glow = (float) Math.sin(Math.min(1f, pos01) * Math.PI); // 行全程 0→1→0 光晕
            alpha = Math.min(1f, 0.12f + glow * 1.25f);
            scale = 1f;
            rotate = 0f;
            color = colorHighlight;
            paintWordSimple(canvas, c, alpha * containerAlpha, scale, rotate, color, glow);
            return;
        }

        switch (st) {
            case 0: // waiting：完全不可见（folia opacity 0）
                c.lastAlpha = 0f;
                return;
            case 1: { // active：spring 0.5→base×1.4（欠阻尼 ζ0.55/ω16），alpha 快速 100ms，颜色渐进
                float s = springProgress(el);
                scale = 0.5f + (c.baseScale * 1.4f - 0.5f) * Math.min(1f, s);
                alpha = Math.min(1f, el / 100f);
                rotate = c.rotate + 20f * (1f - Math.min(1f, el / 400f)); // 预转角 20° 快速回基准
                // 位置 spring：drift 摆位 → 安放位
                float px = (float) Math.exp(-el / 260f);
                float dx = c.driftX * px, dy = c.driftY * px;
                float dur = Math.max(1L, activeEndOf(c) - c.word.startTime);
                float colorT = Math.min(1f, el / dur); // 颜色按演唱时长渐进
                color = lerpColor(COLOR_BASE, colorHighlight, colorT);
                paintWord(canvas, c, dx, dy, alpha * containerAlpha, scale, rotate, color, el);
                return;
            }
            default: { // passed：0.5s 回 alpha 0.82 / scale base；颜色 0.8s 回基色；5s 缓转
                float t = Math.min(1f, el / 500f);
                float e = 1f - (1f - t) * (1f - t);
                scale = c.baseScale * 1.4f + (c.baseScale - c.baseScale * 1.4f) * e;
                alpha = 1f + (0.82f - 1f) * e;
                float ct = Math.min(1f, el / 800f);
                color = lerpColor(colorHighlight, COLOR_BASE, ct * ct * (3f - 2f * ct));
                rotate = c.rotate + c.passedRotate * Math.min(1f, el / 5000f);
                paintWord(canvas, c, 0f, 0f, alpha * containerAlpha, scale, rotate, color, -1L);
                return;
            }
        }
    }

    private long activeEndOf(WordConf c) {
        long min = lineTransitionClass == 2 ? MIN_DISPLAY_INSTANT
                : lineTransitionClass == 1 ? MIN_DISPLAY_FAST : MIN_DISPLAY_NORMAL;
        return Math.max(c.word.startTime + c.word.duration, c.word.startTime + min);
    }

    private int computeState(WordConf c) {
        long look = lineTransitionClass == 2 ? LOOKAHEAD_INSTANT
                : lineTransitionClass == 1 ? LOOKAHEAD_FAST : LOOKAHEAD_NORMAL;
        if (c.word == null) return positionMs >= lines.get(currentIndex).startTime ? 1 : 0;
        if (positionMs < c.word.startTime - look) return 0;
        if (positionMs <= activeEndOf(c)) return 1;
        return 2;
    }

    /** 简单词绘制（纯 LRC 行）：基色/高亮 + 行级光晕 */
    private void paintWordSimple(Canvas canvas, WordConf c, float alpha, float scale,
                                 float rotate, int color, float glow) {
        int a = (int) (Math.min(1f, alpha) * 255);
        if (a <= 2) { c.lastAlpha = 0f; return; }
        textPaint.setColor((a << 24) | (color & 0xFFFFFF));
        clearGlow();
        if (glow > 0.02f) textPaint.setShadowLayer(lineTextSize * 0.4f * glow, 0, 0,
                ((int) (glow * 200) << 24) | (color & 0xFFFFFF));
        int sc = canvas.save();
        if (rotate != 0f) canvas.rotate(rotate, c.cx, c.cy);
        if (scale != 1f) canvas.scale(scale, scale, c.cx, c.cy);
        canvas.drawText(c.text, c.cx, c.cy + baselineFromCenter(lineTextSize), textPaint);
        canvas.restoreToCount(sc);
        c.lastAlpha = alpha; c.lastScale = scale; c.lastRotate = rotate;
    }

    /**
     * 词三态绘制（translate→scale→rotate→三层文字）：
     * 辉光层（内 0.7α + 外满 α，半径随字素时间正弦 ramp）与正文层分离——光大而不糊字（folia 双 drop-shadow 拟合）。
     * activeEl >= 0 时按字素逐字点亮；否则（passed）仅正文。
     */
    private void paintWord(Canvas canvas, WordConf c, float dx, float dy, float alpha,
                           float scale, float rotate, int color, long activeEl) {
        int a = (int) (Math.min(1f, alpha) * 255);
        if (a <= 2) { c.lastAlpha = 0f; return; }

        textPaint.setTextSize(lineTextSize);
        int sc = canvas.save();
        canvas.translate(c.cx + dx, c.cy + dy);
        canvas.scale(scale, scale);
        if (rotate != 0f) canvas.rotate(rotate);

        float baseline = baselineFromCenter(lineTextSize);

        // 字素均分时长（folia buildEvenGraphemeTimings）
        int charCount = c.text.length();
        long wStart = c.word.startTime;
        long wDur = Math.max(1L, activeEndOf(c) - wStart);
        float rInner = lineTextSize * 0.35f, rOuter = lineTextSize * 0.6f;

        if (activeEl >= 0 && charCount > 0) {
            // 辉光两遍：内圈（α0.7）/外圈（α1）——每个字独立的正弦 ramp
            for (int pass = 0; pass < 2; pass++) {
                float radius = pass == 0 ? rInner : rOuter;
                float glowAlphaScale = pass == 0 ? 0.7f : 1f;
                float x = -textPaint.measureText(c.text) / 2f;
                for (int i = 0; i < charCount; i++) {
                    String ch = String.valueOf(c.text.charAt(i));
                    float cw = textPaint.measureText(ch);
                    long cs = wStart + wDur * i / charCount;
                    long ce = wStart + wDur * (i + 1) / charCount;
                    float tChar = positionMs <= cs ? 0f
                            : positionMs >= ce ? 1f : (positionMs - cs) / (float) (ce - cs);
                    float g = (float) Math.sin(tChar * Math.PI) * glowAlphaScale; // 0→1→0
                    if (g < 0.02f) { x += cw; continue; }
                    int ga = (int) (g * 235);
                    textPaint.setShadowLayer(radius, 0, 0, (ga << 24) | (color & 0xFFFFFF));
                    textPaint.setColor(0); // 辉光层文字本体透明（shadow 照常投影）
                    canvas.drawText(ch, x + cw / 2f, baseline, textPaint);
                    x += cw;
                }
            }
        }

        // 正文层：干净字形（scale 下文字清晰，光只来自 shadow 层）
        clearGlow();
        textPaint.setColor((a << 24) | (color & 0xFFFFFF));
        canvas.drawText(c.text, 0, baseline, textPaint);

        canvas.restoreToCount(sc);
        c.lastAlpha = alpha; c.lastScale = scale; c.lastRotate = rotate;
    }

    private void clearGlow() {
        textPaint.setShadowLayer(0, 0, 0, 0);
    }

    /** 文字中心 → 基线的偏移（按当前字号字面度量，词中心定位零误差） */
    private float baselineFromCenter(float size) {
        textPaint.setTextSize(size);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        return -(fm.ascent + fm.descent) * 0.5f - fm.descent;
    }

    /** 欠阻尼弹簧（ζ=0.55/ω=16，与 StarLyricView 同手感）：1 内收敛 */
    private static float springProgress(long elapsedMs) {
        float t = elapsedMs / 1000f;
        if (t <= 0f) return 0f;
        if (t >= 0.6f) return 1f;
        double zeta = 0.55, omega = 16.0, wd = omega * Math.sqrt(1 - zeta * zeta);
        double v = 1 - Math.exp(-zeta * omega * t)
                * (Math.cos(wd * t) + (zeta * omega / wd) * Math.sin(wd * t));
        return (float) Math.max(0, Math.min(1.15, v));
    }

    private static int lerpColor(int from, int to, float t) {
        if (t <= 0f) return from;
        if (t >= 1f) return to;
        int fa = (from >> 24) & 0xFF, fr = (from >> 16) & 0xFF, fg = (from >> 8) & 0xFF, fb = from & 0xFF;
        int ta = (to >> 24) & 0xFF, tr = (to >> 16) & 0xFF, tg = (to >> 8) & 0xFF, tb = to & 0xFF;
        return ((fa + (int) ((ta - fa) * t)) << 24)
                | ((fr + (int) ((tr - fr) * t)) << 16)
                | ((fg + (int) ((tg - fg) * t)) << 8)
                | (fb + (int) ((tb - fb) * t));
    }
}
