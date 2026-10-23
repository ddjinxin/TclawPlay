package com.jingxin.jingxinmusic.view;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.Choreographer;
import android.view.View;

import com.jingxin.jingxinmusic.util.KrcParser;

import java.util.Collections;
import java.util.List;

/**
 * 回环歌词环（Claddagh，移植 folia claddagh）
 *
 * 结构：细长椭圆环（长轴 Rx=0.44 屏宽≤560dp、短轴 0.09Rx）逆时针倾斜 45° 横跨屏幕；
 * 字符沿环按弧角分布（字距 0.18em 基距+0.04em），当前唱到的字永远在环正前方
 * （D=1 最亮最大），环上其余字符沉入背景（模糊+缩小+降透明）。
 *
 * 换行：整环弹簧转 180°（K55/D14/M0.9 RK4），旧行绕到背面淡出、新行绕到正面；
 * 跨行跳转(|Δ|>1) 直接就位不转。字级时间驱动 wordOffset 连续推进（环随歌声缓转，
 * 间奏延伸到下一行开始前 0.34s 缓漂），背侧行 28% 词偏跟随+52% 轨道缓转。
 *
 * 深度混合：D=(cos+1)/2 前深后浅；焦点 F=行接近度×(1-|DIST|/0.48Rx)^1.8；
 * opacity=(0.35+0.65·D^1.5·(0.35+0.65F))·(0.68+0.32·D^1.9) 出场/边界/重叠多级衰减；
 * scale=(0.22+0.98·D^1.5)(1+0.65F)；blur=8dp·(1-D)(1-0.5F)；切线角可读化±38°。
 * 副歌行：字符奇偶交错 ±(0.06+0.12power) 字高、三重辉光（0.35/1/1.6R 内核混白）。
 * 扫字呈"闪光灯"硬切：进度×15 抬色 + 0.4 内闪光半径 pop 0.65。
 * 中心垂线：300×4dp 渐隐线随低频呼吸拉伸，副歌时辉光渐入。
 * 视图为软件层（背景环字符 BlurMaskFilter 景深模糊）。
 */
public class CladdaghLyricView extends View {

    // ===== folia 常量（claddaghLayout/timeline/RingLine 直译）=====
    private static final float MAX_ARC_SPAN = 4.25f;          // 单行最大弧角
    private static final float BASE_TRACKING_EM = 0.18f;     // 基础字距
    private static final float BACK_FOLLOW_RATIO = 0.28f;    // 背侧行词偏跟随比
    private static final float BACK_ORBIT_FOLLOW = 0.52f;    // 背侧轨道缓转比
    private static final float ENTRY_LEAD_S = 0.34f;         // 下行入场提前量
    private static final float BASE_FONT_DP = 72f;
    private static final float RADIUS_CAP_DP = 560f;
    private static final float MINOR_RATIO = 0.09f;          // 细短轴比
    private static final float TILT_DEG = 45f;               // 椭圆倾角（顺时针）
    private static final float FOCUS_SCALE_RATIO = 0.65f;    // 焦点放大比
    private static final float MAX_VISIBLE_DIST_RATIO = 0.48f;
    private static final float GAP_MS_PER_CHAR = 0.06f;      // 间隙字最小时长(s)
    /** 中心轴线（folia showAxisLine 设计，原版默认开）；观感突兀默认关 */
    private static final boolean SHOW_AXIS_LINE = false;

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private List<KrcParser.LyricLine> lines = Collections.emptyList();
    private boolean[] chorusFlags = new boolean[0];
    private int targetIndex = -2;         // 当前行索引（-2=未初始化，-1=歌前）
    private int renderBase = -1;          // 弹簧渲染基准行
    private int lastCenterIndex = -2;
    private float positionMs;
    private float lastTimeSec = -1f;
    private boolean holdResetFrame = false;

    // 换行弹簧：lineOffset（弧度）追 centerLineIndex*π
    private static final float SPRING_K = 55f, SPRING_D = 14f, SPRING_M = 0.9f;
    private float springPos = 0f, springVel = 0f;

    // 能量（半径呼吸/轴线/副歌交错/辉光增强）
    private float bassEnergy = 0f, bassSmooth = 0f;
    private float chorusGlow = 0f;

    private int colorPrimary = Color.rgb(0xC9, 0xE4, 0xFF);
    private int colorAccent = Color.rgb(0x6E, 0xE8, 0xD8);

    private int layoutW, layoutH;
    private float density = 1f, fontPx = 72f;
    private float rxBase = 360f;          // dp 基准半径（px）

    // 模糊滤镜缓存（半径 0.5px 量化，背景环复用）
    private final SparseArray<BlurMaskFilter> blurCache = new SparseArray<>();
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // 行布局缓存：文本 → 字素时间轴+弧角
    private final java.util.HashMap<String, Spacing> spacingCache = new java.util.HashMap<>();

    /** 单行字素布局（时间轴为行内相对秒） */
    private static final class Spacing {
        final String text;
        final float[] nominalAngle;   // 居中弧角
        final float[] startSec;
        final float[] endSec;
        final float totalSpan;
        final int count;
        final float arcScale;
        final float visualLength;
        Spacing(String text, float[] a, float[] s, float[] e, float span, float arc, float vl) {
            this.text = text; this.nominalAngle = a; this.startSec = s; this.endSec = e;
            this.totalSpan = span; this.arcScale = arc; this.visualLength = vl;
            this.count = a.length;
        }
    }

    private boolean running = false;
    private long lastFrameNs = -1L;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!running) return;
            long deltaNs = lastFrameNs < 0 ? 0 : frameTimeNanos - lastFrameNs;
            lastFrameNs = frameTimeNanos;
            float dt = Math.min(0.05f, deltaNs / 1_000_000_000f);
            stepSpring(dt);
            bassSmooth += (bassEnergy - bassSmooth) * Math.min(1f, dt * 12f);
            invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public CladdaghLyricView(Context context) { super(context); init(); }
    public CladdaghLyricView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public CladdaghLyricView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr); init();
    }

    private void init() {
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true); // folia weight 700
        // 软件层：背景环字符 BlurMaskFilter 景深模糊需软件光栅
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    // ===== 对外接口（同族效果页一致）=====

    public void setLyricData(KrcParser.LyricData data) {
        this.lines = (data != null && data.lines != null) ? data.lines : Collections.emptyList();
        this.targetIndex = -2;
        this.renderBase = -1;
        this.lastCenterIndex = -2;
        springPos = 0f;
        springVel = 0f;
        spacingCache.clear();
        detectChorus();
        chorusGlow = 0f;
        invalidate();
    }

    public void updatePosition(int positionMs) {
        this.positionMs = positionMs;
        if (lines.isEmpty()) return;
        float tSec = positionMs / 1000f;
        int lo = 0, hi = lines.size() - 1, idx = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).startTime / 1000f <= tSec) { idx = mid; lo = mid + 1; }
            else hi = mid - 1;
        }
        if (idx != targetIndex) {
            // folia 换行逻辑：跳转 |Δ|>1 直接就位；相邻则弹簧转 180°，renderBase 立即跟进
            if (Math.abs(idx - lastCenterIndex) > 1) {
                springPos = idx * (float) Math.PI;
                springVel = 0f;
                renderBase = idx;
            } else {
                renderBase = idx;
            }
            lastCenterIndex = idx;
            targetIndex = idx;
        }
    }

    public void setColors(int primary, int accent) {
        if (primary != 0) colorPrimary = primary;
        if (accent != 0) colorAccent = accent;
        invalidate();
    }

    /** 低频能量 0~1（Manager onEnergy 注入） */
    public void setEnergy(float energy) {
        this.bassEnergy = clamp01(energy);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutW = w;
        layoutH = h;
        density = getResources().getDisplayMetrics().density;
        fontPx = BASE_FONT_DP * density;
        rxBase = Math.min(w * 0.44f, RADIUS_CAP_DP * density);
        spacingCache.clear();
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

    // ===== 弹簧（RK4，framer K55/D14/M0.9）=====

    private void stepSpring(float dt) {
        float target = Math.max(-1, targetIndex) * (float) Math.PI;
        float x = springPos, v = springVel;
        float h = dt / 2f;
        float k1x = v, k1v = accel(x, v, target);
        float k2x = v + h * k1v, k2v = accel(x + h * k1x, v + h * k1v, target);
        float k3x = v + h * k2v, k3v = accel(x + h * k2x, v + h * k2v, target);
        float k4x = v + dt * k3v, k4v = accel(x + dt * k3x, v + dt * k3v, target);
        springPos = x + (dt / 6f) * (k1x + 2f * k2x + 2f * k3x + k4x);
        springVel = v + (dt / 6f) * (k1v + 2f * k2v + 2f * k3v + k4v);
    }

    private float accel(float x, float v, float target) {
        return (-SPRING_K * (x - target) - SPRING_D * v) / SPRING_M;
    }

    // ===== 副歌检测（重复行≥2，与时计同法）=====

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

    // ===== 行布局（时间轴+弧角）=====

    private Spacing spacingFor(KrcParser.LyricLine line) {
        String text = line.text != null ? line.text : "";
        Spacing hit = spacingCache.get(text);
        if (hit != null) return hit;

        textPaint.setTextSize(fontPx);
        int n = text.length();
        float[] angles = new float[n];
        float[] starts = new float[n];
        float[] ends = new float[n];

        // 时间轴（buildLineGraphemeTimeline 直译）：词序匹配回整行，词内均匀分时，
        // 空隙/未匹配字零时长占位（随后 adjustGaps 借时），未解析尾字取最后已解析时刻
        float lineStart = line.startTime / 1000f;
        float lineDur = Math.max(0.2f, line.duration / 1000f);
        List<KrcParser.LyricWord> words = line.words;
        if (words != null && !words.isEmpty()) {
            for (int i = 0; i < n; i++) { starts[i] = Float.MIN_VALUE; ends[i] = Float.MIN_VALUE; }
            int cursor = 0;
            float lastResolved = lineStart;
            boolean[] resolved = new boolean[n];
            for (KrcParser.LyricWord word : words) {
                String wt = word.text != null ? word.text : "";
                if (wt.isEmpty()) continue;
                // 序列匹配：从 cursor 起找词首字符在行文本的位置（找不到退回 cursor 顺序接）
                int matched = indexOfSeq(text, wt, cursor);
                int start = matched >= 0 ? matched : cursor;
                int end = Math.min(start + wt.length(), n);
                // 词前空隙字：零时长占位在词开始时刻
                for (int g = cursor; g < start; g++) {
                    starts[g] = word.startTime / 1000f;
                    ends[g] = word.startTime / 1000f;
                }
                float wStart = word.startTime / 1000f;
                float wEnd = wStart + Math.max(0f, word.duration / 1000f);
                int wn = Math.max(0, end - start);
                float unit = wn > 0 ? (wEnd - wStart) / wn : 0f;
                for (int k = 0; k < wn; k++) {
                    starts[start + k] = wStart + unit * k;
                    ends[start + k] = k == wn - 1 ? wEnd : wStart + unit * (k + 1);
                    resolved[start + k] = true;
                    lastResolved = Math.max(lastResolved, ends[start + k]);
                }
                cursor = Math.max(cursor, end);
            }
            // 未解析尾字：零时长在最后已解析时刻
            for (int i = 0; i < n; i++) {
                if (!resolved[i]) {
                    starts[i] = lastResolved;
                    ends[i] = lastResolved;
                }
            }
        } else {
            // LRC：整行均匀分时（buildEvenGraphemeTimings 直译）
            float unit = lineDur / n;
            for (int i = 0; i < n; i++) {
                starts[i] = lineStart + unit * i;
                ends[i] = i == n - 1 ? lineStart + lineDur : lineStart + unit * (i + 1);
            }
        }
        // 时间保持**歌曲绝对秒**（wordOffset/renderEnd/扫字进度全绝对域）；
        // 间隙字最低 60ms 借时（adjustCladdaghTimeline 直译，行首= lineStart 行尾= lineStart+lineDur）
        adjustGaps(starts, ends, lineStart, lineStart + lineDur);

        // 弧角：累积字宽+基距，中心化，超弧收敛
        float tracking = fontPx * BASE_TRACKING_EM;
        float[] offsets = new float[n + 1];
        float total = 0f;
        for (int i = 0; i < n; i++) {
            offsets[i] = total;
            total += textPaint.measureText(text.substring(i, i + 1)) + (i < n - 1 ? tracking : 0f);
        }
        offsets[n] = total;
        float rSafe = Math.max(rxBase, fontPx * 2f);
        float totalSpan = total / rSafe;
        float arcScale = totalSpan > MAX_ARC_SPAN ? MAX_ARC_SPAN / totalSpan : 1f;
        if (n > 0) {
            for (int i = 0; i < n; i++) {
                float centerPx = (offsets[i] + offsets[i + 1]) / 2f;
                float startAngle = centerPx / rSafe;
                angles[i] = (startAngle - totalSpan / 2f) * arcScale;
            }
        }

        // 视觉长度（CJK 1 其他 0.5）
        float vl = 0f;
        for (int i = 0; i < n; i++) vl += isCJK(text.charAt(i)) ? 1f : 0.5f;

        Spacing sp = new Spacing(text, angles, starts, ends, totalSpan, arcScale, vl);
        spacingCache.put(text, sp);
        return sp;
    }

    /** 序列匹配：source 从 fromIndex 起找 target 连续子串（findGraphemeSequence 直译） */
    private static int indexOfSeq(String source, String target, int fromIndex) {
        int tn = target.length();
        if (tn == 0) return fromIndex;
        int sn = source.length();
        for (int i = fromIndex; i <= sn - tn; i++) {
            boolean matched = true;
            for (int j = 0; j < tn; j++) {
                if (source.charAt(i + j) != target.charAt(j)) { matched = false; break; }
            }
            if (matched) return i;
        }
        return -1;
    }

    /** 零时长字（间隙/空格）给 60ms，不足向邻字借（adjustCladdaghTimeline 直译；时间全绝对秒） */
    private static void adjustGaps(float[] starts, float[] ends, float lineStart, float lineEnd) {
        int n = starts.length;
        int i = 0;
        while (i < n) {
            boolean zero = ends[i] <= starts[i] + 1e-4f; // 零时长（含未初始化 MIN_VALUE 归化后由尾字处理）
            if (!zero) { i++; continue; }
            int j = i;
            while (j < n && ends[j] <= starts[j] + 1e-4f) j++;
            int gapCount = j - i;
            float gapStart = i > 0 ? ends[i - 1] : lineStart;
            float gapEnd = j < n ? starts[j] : lineEnd;
            float minNeeded = gapCount * GAP_MS_PER_CHAR;
            float duration = gapEnd - gapStart;
            if (duration < minNeeded) {
                float deficit = minNeeded - duration;
                if (i > 0 && j < n) {
                    float half = deficit / 2f;
                    float prevDur = ends[i - 1] - starts[i - 1];
                    float prevSteal = Math.min(half, Math.max(0f, prevDur - 0.04f));
                    float nextDur = ends[j] - starts[j];
                    float nextSteal = Math.min(deficit - prevSteal, Math.max(0f, nextDur - 0.04f));
                    gapStart -= prevSteal;
                    gapEnd += nextSteal;
                    ends[i - 1] = gapStart;
                    starts[j] = gapEnd;
                } else if (i > 0) {
                    float prevDur = ends[i - 1] - starts[i - 1];
                    float prevSteal = Math.min(deficit, Math.max(0f, prevDur - 0.04f));
                    gapStart -= prevSteal;
                    ends[i - 1] = gapStart;
                } else if (j < n) {
                    float nextDur = ends[j] - starts[j];
                    float nextSteal = Math.min(deficit, Math.max(0f, nextDur - 0.04f));
                    gapEnd += nextSteal;
                    starts[j] = gapEnd;
                }
                duration = gapEnd - gapStart;
            }
            float unit = duration > 0f ? duration / gapCount : 0f;
            for (int k = i; k < j; k++) {
                int idx = k - i;
                starts[k] = gapStart + unit * idx;
                ends[k] = gapStart + unit * (idx + 1);
            }
            i = j;
        }
    }

    // ===== 时间轴求值（claddaghTimeline 直译）=====

    /** 小数活跃下标（插值+末字后超额缓动） */
    private static float fractionalIndex(Spacing sp, float t, float renderEnd) {
        int n = sp.count;
        if (n == 0) return 0f;
        if (n == 1) {
            float dur = Math.max(0.2f, renderEnd - sp.startSec[0]);
            if (t <= sp.startSec[0]) return 0f;
            return (t - sp.startSec[0]) / dur;
        }
        if (t <= sp.startSec[0]) return 0f;
        int last = n - 1;
        if (t >= sp.startSec[last]) {
            if (renderEnd > sp.startSec[last]) {
                float p = clamp((t - sp.startSec[last]) / (renderEnd - sp.startSec[last]), 0f, 1f);
                return last + p * 2f;
            }
            float itemDur = sp.endSec[last] - sp.startSec[last];
            float gapDur = sp.startSec[last] - sp.startSec[last - 1];
            float stepDur = itemDur > 0f ? itemDur : (gapDur > 0f ? gapDur : 0.5f);
            return last + Math.min((t - sp.startSec[last]) / stepDur, 1.8f);
        }
        for (int i = 0; i < n - 1; i++) {
            float ts = sp.startSec[i], te = sp.startSec[i + 1];
            if (t >= ts && t < te) {
                if (te == ts) return i;
                return i + (t - ts) / (te - ts);
            }
        }
        return last;
    }

    /** 行词偏角：nominalAngle 在小数下标处插值，超额外推 */
    private static float wordOffset(Spacing sp, float t, float renderEnd) {
        int n = sp.count;
        if (n == 0) return 0f;
        float fi = fractionalIndex(sp, t, renderEnd);
        int last = n - 1;
        if (fi <= last) {
            int ip = (int) Math.floor(fi);
            float fp = fi - ip;
            float a = sp.nominalAngle[Math.min(ip, last)];
            float b = sp.nominalAngle[Math.min(ip + 1, last)];
            return a + (b - a) * fp;
        }
        float lastA = sp.nominalAngle[last];
        float prevA = sp.nominalAngle[Math.max(0, last - 1)];
        float step = lastA - prevA;
        return lastA + step * (fi - last);
    }

    /** 行播放进度 0~1 */
    private static float playbackProgress(Spacing sp, float t, float renderEnd) {
        if (sp.count == 0) return 0f;
        if (sp.count == 1) {
            float dur = Math.max(0.001f, renderEnd - sp.startSec[0]);
            return clamp((t - sp.startSec[0]) / dur, 0f, 1f);
        }
        return clamp(fractionalIndex(sp, t, renderEnd) / (sp.count - 1), 0f, 1f);
    }

    // ===== 绘制 =====

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (layoutW <= 0 || lines.isEmpty()) return;
        float t = positionMs / 1000f;

        // 播放重置（seek 回歌头）保持上一帧防闪烁（shouldHoldCladdaghFrame 直译）
        if (targetIndex > 0 && t <= 0.5f && lastTimeSec > 0f && t < lastTimeSec - 0.5f) {
            holdResetFrame = true;
        }
        if (holdResetFrame) {
            holdResetFrame = false;
            lastTimeSec = t;
            return; // 本帧沿用上一帧像素（不重绘）
        }
        lastTimeSec = t;

        int centerIdx = Math.max(-1, targetIndex);
        float power = clamp01(bassSmooth);

        // 副歌辉光渐入（0.05/帧 ≈ 330ms@60fps）
        float glowTarget = (centerIdx >= 0 && centerIdx < chorusFlags.length && chorusFlags[centerIdx]) ? 1f : 0f;
        if (Math.abs(glowTarget - chorusGlow) > 0.01f) {
            chorusGlow += Math.signum(glowTarget - chorusGlow) * 0.05f;
            chorusGlow = clamp01(chorusGlow);
        } else {
            chorusGlow = glowTarget;
        }

        // 半径呼吸（能量，normal 档 ×0.25 上限 1.25）
        float radiusScale = Math.min(1f + power * 0.25f, 1.25f);
        float rx = rxBase * radiusScale;

        // 轴线为 folia showAxisLine 设计（垂直主轴的渐隐呼吸线，原版默认开），默认关
        if (SHOW_AXIS_LINE) drawAxisLine(canvas, power);

        // 活跃行词偏（背侧跟随基准）
        KrcParser.LyricLine activeLine = (renderBase >= 0 && renderBase < lines.size()) ? lines.get(renderBase) : null;
        Spacing activeSp = activeLine != null ? spacingFor(activeLine) : null;
        float activeRenderEnd = renderEndFor(renderBase);
        float activeWordOff = activeSp != null && activeSp.count > 0
                ? wordOffset(activeSp, t, activeRenderEnd) : 0f;
        float activeProgress = activeSp != null && activeSp.count > 0
                ? playbackProgress(activeSp, t, activeRenderEnd) : 0f;
        float backOrbit = (float) Math.PI * BACK_ORBIT_FOLLOW
                * (1f - (float) Math.pow(1f - activeProgress, 1.35));

        // 渲染 renderBase-1 .. renderBase+2（过渡对+前行驻留）
        for (int li = renderBase - 1; li <= renderBase + 2; li++) {
            if (li < 0 || li >= lines.size()) continue;
            KrcParser.LyricLine line = lines.get(li);
            if (line == null || line.text == null || line.text.isEmpty()) continue;
            drawRingLine(canvas, li, centerIdx, t, rx, power,
                    activeWordOff, backOrbit);
        }
    }

    /** 行渲染结束点：下一行开始前 0.34s（间奏缓漂），兜底 +10s */
    private float renderEndFor(int lineIndex) {
        if (lineIndex < 0 || lineIndex >= lines.size()) return t0(lineIndex) + 10f;
        float ownEnd = t0(lineIndex) + Math.max(0.2f, lines.get(lineIndex).duration / 1000f);
        float nextStart = lineIndex + 1 < lines.size() ? t0(lineIndex + 1) - ENTRY_LEAD_S : ownEnd + 10f;
        return Math.max(ownEnd, nextStart);
    }

    private float t0(int lineIndex) {
        return lineIndex >= 0 && lineIndex < lines.size() ? lines.get(lineIndex).startTime / 1000f : 0f;
    }

    /** 环上一行：逐字素投影椭圆+深度混合（RingLine handler 直译） */
    private void drawRingLine(Canvas canvas, int lineIndex, int centerIdx, float t,
                              float rx, float power, float activeWordOff, float backOrbit) {
        KrcParser.LyricLine line = lines.get(lineIndex);
        Spacing sp = spacingFor(line);
        if (sp.count == 0) return;

        float lineOffset = springPos;
        float lineDiff = Math.abs(lineOffset - lineIndex * (float) Math.PI) / (float) Math.PI;

        // 换行入场渐显（下行早 0.34s 现身）
        boolean upcoming = lineIndex > centerIdx;
        float entryProg = 0f;
        if (upcoming) {
            float ls = t0(lineIndex);
            entryProg = clamp((t - (ls - ENTRY_LEAD_S)) / ENTRY_LEAD_S, 0f, 1f);
        }

        // 长行重叠缓解（当前行长>10 时下拉行透明/缩放）
        float lengthFade = 1f, lengthScale = 1f;
        if (lineIndex > centerIdx && centerIdx >= 0 && centerIdx < lines.size()) {
            Spacing curSp = spacingFor(lines.get(centerIdx));
            float curLen = curSp.visualLength;
            if (curLen > 10f) {
                float fadeStrength = clamp((curLen - 10f) / 8f, 0f, 1f);
                float targetStrength = clamp((sp.visualLength - 5f) / 5f, 0.4f, 1f);
                float combined = fadeStrength * targetStrength;
                float minOp = 1f - combined;
                float minScale = 1f - combined * 0.25f;
                float tp = clamp((lineDiff - 0.4f) / 0.5f, 0f, 1f);
                lengthFade = 1f - (1f - minOp) * tp;
                lengthScale = 1f - (1f - minScale) * tp;
            }
        }

        // 词偏：背/未到行加活跃行跟随
        float ownRenderEnd = renderEndFor(lineIndex);
        float wordOff = wordOffset(sp, t, ownRenderEnd);
        if (lineIndex >= centerIdx) {
            float backFactor = lineIndex > centerIdx ? 1f : clamp(lineDiff, 0f, 1f);
            wordOff += (activeWordOff * BACK_FOLLOW_RATIO + backOrbit) * backFactor;
        }

        boolean isChorus = lineIndex < chorusFlags.length && chorusFlags[lineIndex];
        float rMajor = rx;
        float rMinor = rx * MINOR_RATIO;
        float thetaRot = (float) Math.toRadians(-TILT_DEG);
        float cosR = (float) Math.cos(thetaRot), sinR = (float) Math.sin(thetaRot);
        float maxVisibleDist = rx * MAX_VISIBLE_DIST_RATIO;
        float cx = layoutW / 2f, cy = layoutH / 2f;
        float linePos = lineIndex * (float) Math.PI;
        float activeLineFactor = Math.max(0f, 1f - lineDiff);

        for (int i = 0; i < sp.count; i++) {
            String ch = sp.text.substring(i, i + 1);
            float theta = linePos + sp.nominalAngle[i];
            float psi = theta - lineOffset - wordOff;
            float deltaDist = psi * rx;

            float thetaCurve = psi; // deltaDist/rMajor, rMajor==rx
            float localCos = (float) Math.cos(thetaCurve);
            float D = (localCos + 1f) / 2f;
            float spacingFactor = 0.35f + 0.65f * (float) Math.pow(D, 1.2);

            float rawX = (float) Math.sin(thetaCurve) * rMajor * spacingFactor;
            float rawY = localCos * rMinor;
            if (isChorus) {
                float stagger = fontPx * (0.06f + power * 0.12f);
                rawY += (i % 2 == 0 ? 1f : -1f) * stagger;
            }

            float x = rawX * cosR - rawY * sinR;
            float y = rawX * sinR + rawY * cosR;

            // 切线角（可读化 ±90°）
            float tanX = localCos * rMajor;
            float tanY = -(float) Math.sin(thetaCurve) * rMinor;
            float rotTanX = tanX * cosR - tanY * sinR;
            float rotTanY = tanX * sinR + tanY * cosR;
            float tangentDeg = normalizeReadable((float) Math.toDegrees((float) Math.atan2(rotTanY, rotTanX)));
            float tiltAngle = clamp(tangentDeg * (0.4f + 0.6f * D), -38f, 38f);

            // 焦点 F
            float distRatio = Math.min(1f, Math.abs(deltaDist) / maxVisibleDist);
            float F = activeLineFactor * (float) Math.pow(1f - distRatio, 1.8f);

            // 透明度
            float distanceOpacity = 0.68f + 0.32f * (float) Math.pow(D, 1.9f);
            float opacity = (0.35f + 0.65f * (float) Math.pow(D, 1.5f) * (0.35f + 0.65f * F)) * distanceOpacity;
            opacity *= clamp(2f - lineDiff, 0f, 1f);                      // 视窗淡出
            if (lineIndex < centerIdx) opacity *= Math.max(0f, 1f - lineDiff); // 过去行消隐
            opacity *= lengthFade;
            if (upcoming) opacity *= 0.18f + 0.82f * entryProg;
            // 边界淡出：非焦点行严格留在背面
            if (lineDiff > 0.02f) {
                float bp = clamp((lineDiff - 0.3f) / 0.6f, 0f, 1f);
                float cosThresh = 1f - 1.2f * bp;
                if (localCos > cosThresh) {
                    opacity *= clamp(1f - (localCos - cosThresh) / 0.15f, 0f, 1f);
                }
            }
            if (opacity <= 0.006f) continue;

            // 缩放/模糊
            float scale = (0.22f + 0.98f * (float) Math.pow(D, 1.5f))
                    * (1f + FOCUS_SCALE_RATIO * F) * lengthScale * sp.arcScale;
            float blurDp = 8f * (1f - D) * (1f - 0.5f * F);

            // 扫字进度（闪光灯硬切）
            float cp = 0f;
            if (t >= sp.endSec[i]) cp = 1f;
            else if (t > sp.startSec[i]) {
                float dur = sp.endSec[i] - sp.startSec[i];
                cp = dur > 0f ? (t - sp.startSec[i]) / dur : 1f;
            }

            // 颜色：base(0.55)→highlight(1.0)
            float targetAlpha;
            int targetColor;
            float flashPop = 0f;
            if (cp >= 1f) {
                targetColor = colorAccent;
                targetAlpha = 1f;
            } else if (cp > 0f) {
                float pf = Math.min(1f, cp * 15f);
                targetAlpha = 0.55f + 0.45f * pf;
                targetColor = lerpColorRGB(colorPrimary, colorAccent, pf);
                if (cp < 0.4f) flashPop = (float) Math.pow(1f - cp / 0.4f, 2f) * 0.65f;
            } else {
                targetColor = colorPrimary;
                targetAlpha = 0.55f;
            }

            // 辉光（半径/pop/淡出阈值）
            float baseGlow = isChorus
                    ? (36f + power * 24f) * (float) Math.pow(F, 1.5f)
                    : 24f * (float) Math.pow(F, 2f);
            float glowRadius = baseGlow * (1f + flashPop);
            float shadowFade = clamp((glowRadius - 0.5f) / 2f, 0f, 1f);

            drawGlyph(canvas, ch, cx + x, cy + y, tiltAngle, scale, opacity,
                    targetColor, targetAlpha, glowRadius, shadowFade, blurDp, isChorus);
        }
    }

    /** 单字素：translate→rotate→textSize 缩放；辉光 shadowLayer（副歌三层）；模糊 MaskFilter */
    private void drawGlyph(Canvas canvas, String ch, float x, float y, float tilt,
                           float scale, float opacity, int color, float colorAlpha,
                           float glowRadius, float shadowFade, float blurDp, boolean chorus) {
        float size = fontPx * scale;
        if (size < 2f) return;
        int sc = canvas.save();
        canvas.translate(x, y);
        canvas.rotate(tilt);
        textPaint.setTextSize(size);
        float blurPx = blurDp * density;
        if (blurPx > 0.25f) {
            textPaint.setMaskFilter(blurFilter(blurPx));
        } else {
            textPaint.setMaskFilter(null);
        }

        android.graphics.Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = -(fm.ascent + fm.descent) / 2f;
        int alpha = (int) (clamp01(opacity * colorAlpha) * 255f);

        // 辉光层
        if (glowRadius > 0.5f && shadowFade > 0.01f) {
            float rPx = glowRadius * density;
            if (chorus) {
                // 外 1.6R → 中 1R(0.2 弱) → 内核 0.35R(混白) 三层
                textPaint.setShadowLayer(rPx * 1.6f, 0, 0, withAlpha(color, colorAlpha * shadowFade));
                textPaint.setColor(withAlpha(color, alpha));
                canvas.drawText(ch, 0, baseline, textPaint);
                textPaint.setShadowLayer(rPx, 0, 0, withAlpha(color, colorAlpha * shadowFade * 0.2f));
                canvas.drawText(ch, 0, baseline, textPaint);
                int inner = lerpColorRGB(color, colorPrimary, 0.35f); // mix(target,primary,0.65)
                textPaint.setShadowLayer(rPx * 0.35f, 0, 0, withAlpha(inner, shadowFade));
                canvas.drawText(ch, 0, baseline, textPaint);
            } else {
                textPaint.setShadowLayer(rPx, 0, 0, withAlpha(color, colorAlpha * shadowFade));
            }
        } else {
            textPaint.setShadowLayer(0, 0, 0, 0);
        }
        textPaint.setColor(withAlpha(color, alpha));
        canvas.drawText(ch, 0, baseline, textPaint);
        textPaint.setShadowLayer(0, 0, 0, 0);
        textPaint.setMaskFilter(null);
        canvas.restoreToCount(sc);
    }

    /** 中心垂线：300×4dp 渐隐线，随低频拉伸/变色，副歌辉光（轴垂直于主轴=+45°） */
    private void drawAxisLine(Canvas canvas, float power) {
        float cx = layoutW / 2f, cy = layoutH / 2f;
        float w = 300f * density, h = 4f * density;

        float bassSqr = power * power;
        float scaleX = 1f + bassSqr * 1.5f;
        float scaleY = 1f + bassSqr * 0.5f;
        float colorRatio = clamp((power - 0.02f) / 0.58f, 0f, 1f);
        int mixed = lerpColorRGB(colorPrimary, colorAccent, colorRatio);
        float mixedAlpha = 0.2f + 0.75f * colorRatio;

        int sc = canvas.save();
        canvas.translate(cx, cy);
        canvas.rotate(90f - TILT_DEG);
        canvas.scale(scaleX, scaleY);

        int cEdge = 0x00000000;
        int cMid = withAlpha(mixed, mixedAlpha);
        LinearGradient g = new LinearGradient(-w / 2f, 0f, w / 2f, 0f,
                new int[]{cEdge, cMid, cMid, cEdge},
                new float[]{0f, 0.2f, 0.8f, 1f}, Shader.TileMode.CLAMP);
        axisPaint.setShader(g);
        if (chorusGlow > 0.01f) {
            float glowSize = (4f + power * 12f) * chorusGlow * density;
            axisPaint.setShadowLayer(glowSize, 0, 0, withAlpha(mixed, chorusGlow));
        } else {
            axisPaint.setShadowLayer(0, 0, 0, 0);
        }
        canvas.drawRect(-w / 2f, -h / 2f, w / 2f, h / 2f, axisPaint);
        axisPaint.setShader(null);
        axisPaint.setShadowLayer(0, 0, 0, 0);
        canvas.restoreToCount(sc);
    }

    // ===== 工具 =====

    private static float normalizeReadable(float deg) {
        while (deg > 90f) deg -= 180f;
        while (deg < -90f) deg += 180f;
        return deg;
    }

    private static boolean isCJK(char c) {
        return (c >= 0x4E00 && c <= 0x9FA5) || (c >= 0x3040 && c <= 0x30FF) || (c >= 0xAC00 && c <= 0xD7AF);
    }

    private BlurMaskFilter blurFilter(float radiusPx) {
        int key = Math.round(radiusPx * 2f); // 0.5px 量化
        BlurMaskFilter f = blurCache.get(key);
        if (f == null) {
            f = new BlurMaskFilter(key / 2f, BlurMaskFilter.Blur.NORMAL);
            blurCache.put(key, f);
        }
        return f;
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    private static float clamp01(float v) { return v < 0f ? 0f : v > 1f ? 1f : v; }

    private static int lerpColorRGB(int from, int to, float t) {
        if (t <= 0f) return from;
        if (t >= 1f) return to;
        int r = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * t);
        int g = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * t);
        int b = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t);
        return Color.rgb(r, g, b);
    }

    private static int withAlpha(int color, float alpha) {
        int a = (int) (clamp01(alpha) * 255f);
        return (a << 24) | (color & 0xFFFFFF);
    }
}
