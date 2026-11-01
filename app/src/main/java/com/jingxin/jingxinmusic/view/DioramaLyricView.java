package com.jingxin.jingxinmusic.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import com.jingxin.jingxinmusic.util.KrcParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 镜台歌词视图（移植 folia diorama：三维歌词飞越 + 逐句电影运镜，降级 2D 化渲染）
 *
 * folia 原版（React Three Fiber / WebGL）核心思想：
 * 1. 蜿蜒走廊（buildDioramaPath）：一只"乌龟"每句走一步（步距 8），航向按 seeded
 *    振荡 yaw(±0.5)/pitch(±0.32) 转弯爬升——走廊蜿蜒盘旋永不回头；每行获得局部
 *    坐标系帧（right=行进×世界上，up 补全），文字/相机全在行局部帧里构图
 * 2. 自由文字摆位（getDioramaTextPlacement）：每行不钉在路径中心——偏移/缩放/
 *    平面内 roll/微 yaw/构图 look 偏移按行 seed 决定，低频正弦+扰动让连续行流动
 * 3. 镜头语言（getDioramaShot / resolveShotOffset）：13 种电影运镜（推近/拉远/
 *    环绕/平移/摇臂/定格/涌动/螺旋/摆锤/掠过/大弧/悬浮/滑升）按行的性格
 *    （副歌/词数/段落起点/长音/断音）加权抽取，与前两行去重；表达为行局部帧
 *    中相机的 right/up/back 偏移
 * 4. 读头跟踪（CameraRig）：相机 SmoothDamp 追踪"读头"（唱词位置+tanh 饱和卡车
 *    平移）；行间位置/朝向阻尼过渡；唱读时朝向向文字平面"回正"（对齐权重按
 *    镜头种类设上限）；切歌 3.2s 电影飞行（弧线/侧倾/瞄准横扫一次性入镜）
 * 5. 降级 2D：世界数学（走廊/摆位/镜头/相机）全保留为真 3D，仅渲染时透视投影
 *    到 Canvas（文字按投影缩放/旋转/距离生命/景深雾）；几何编队与拍点涟漪第一版
 *    不做（用户拍板），只保留走廊尘埃粒子
 *
 * 线程：主线程（Choreographer 逐帧）；时间一律歌曲绝对秒（浮名同款约定）。
 */
public class DioramaLyricView extends View {

    // ===== cameraPath 常量（直译） =====
    private static final float STEP_DISTANCE = 8f;
    private static final float MAX_YAW = 0.5f;
    private static final float MAX_PITCH = 0.32f;
    private static final float HERO_DISTANCE = 5.2f;
    private static final float CAMERA_LIFT = 0.5f;
    private static final float SAFE_FRAME_FRACTION = 0.92f;
    private static final float SWAY_AMP = 0.4f;
    private static final float LIFT_AMP = 0.4f;
    private static final float DIST_AMP = 0.5f;
    private static final float SNAP_DISTANCE = 24f;
    private static final float TEXT_OFFSET_R = 1.8f;
    private static final float TEXT_OFFSET_U = 1.2f;
    private static final float TEXT_ROLL = 0.2f;
    private static final float TEXT_YAW = 0.16f;
    private static final float TEXT_LOOK = 1.1f;
    // dioramaSceneConstants（直译）
    private static final float TARGET_FRAME_WIDTH_FRACTION = 0.72f;
    private static final float MIN_FIT_SCALE = 0.28f;
    private static final float TEXT_DISSOLVE_START = 2.0f;
    private static final float TEXT_DISSOLVE_END = 0.9f;
    private static final float TEXT_FADE_IN_START = 32f;
    private static final float TEXT_FADE_IN_END = 40f;
    private static final float GRADIENT_HOLD_SECONDS = 0.35f;
    private static final float GRADIENT_TRAIL_SECONDS = 1.8f;
    // normal 子模式预设（DIORAMA_SUB_MODE_PRESETS 直译；tuning 全默认 1）
    private static final float MOVE_SCALE = 1f;
    private static final float DRIFT_SCALE = 0.62f;
    private static final float SMOOTH_TIME = 0.42f;
    private static final float WEAVE_SCALE = 1f;
    // transition（dioramaTransition 直译）
    private static final float TRANSITION_DURATION = 3.2f;
    private static final float TRANSITION_DISTANCE = 46f;
    private static final float TRANSITION_BANK = 0.14f;
    private static final float TRANSITION_AIM_SWEEP = 0.14f;
    private static final float FLIGHT_SMOOTH_TIME = 1.1f;
    private static final float FLIGHT_ARC_FRAC = 0.16f;
    // CameraRig 对齐常量（直译）
    private static final float ALIGN_LEAD_IN = 0.6f;
    private static final float ALIGN_SETTLE = 0.35f;
    private static final float ALIGN_RELEASE = 1.0f;
    private static final float ALIGN_RATE = 1.2f;
    private static final float HOLD_SETTLE_DELAY = 3f;
    private static final float HOLD_SETTLE_EASE = 5f;
    // 2D 投影
    private static final float FOV_V_DEG = 55f;
    private static final float WORLD_TEXT_H = 0.85f;   // 行文字世界高度
    private static final float FOG_FAR = 30f;          // 深度雾远平面

    // ===== 镜头种类（DioramaShotKind） =====
    private static final int SHOT_PUSH_IN = 0, SHOT_PULL_BACK = 1, SHOT_ORBIT = 2, SHOT_TRACK = 3,
            SHOT_CRANE = 4, SHOT_HOLD = 5, SHOT_SWELL = 6, SHOT_SPIRAL = 7, SHOT_PENDULUM = 8,
            SHOT_FLYBY = 9, SHOT_ARC = 10, SHOT_FLOAT = 11, SHOT_GLIDE = 12;
    private static final int[] SHOT_KINDS = {SHOT_PUSH_IN, SHOT_PULL_BACK, SHOT_ORBIT, SHOT_TRACK,
            SHOT_CRANE, SHOT_HOLD, SHOT_SWELL, SHOT_SPIRAL, SHOT_PENDULUM, SHOT_FLYBY,
            SHOT_ARC, SHOT_FLOAT, SHOT_GLIDE};
    private static final String[] SHOT_NAMES = {"pushIn", "pullBack", "orbit", "track", "crane",
            "hold", "swell", "spiral", "pendulum", "flyby", "arc", "float", "glide"};
    /** 回正上限（ALIGN_SHOT_CEILING 直译） */
    private static final float[] ALIGN_CEILING = {
            0.85f, // pushIn
            0.70f, // pullBack
            0.55f, // orbit
            0.85f, // track
            0.70f, // crane
            0.90f, // hold
            0.80f, // swell
            0.50f, // spiral
            0.60f, // pendulum
            0.50f, // flyby
            0.62f, // arc
            0.78f, // float
            0.72f, // glide
    };

    // ===== 数据结构 =====

    private static final class Vec3 {
        float x, y, z;

        Vec3() {}

        Vec3(float x, float y, float z) { this.x = x; this.y = y; this.z = z; }

        Vec3 set(float x, float y, float z) { this.x = x; this.y = y; this.z = z; return this; }

        Vec3 copy(Vec3 o) { x = o.x; y = o.y; z = o.z; return this; }
    }

    private static final class Frame {
        final Vec3 position = new Vec3();
        final Vec3 forward = new Vec3();
        final Vec3 right = new Vec3();
        final Vec3 up = new Vec3();
    }

    /** 一行文字的摆位（DioramaTextPlacement 直译） */
    private static final class Placement {
        float offsetR, offsetU, scale, roll, yaw, lookR;
    }

    /** 走廊段（CorridorSegment 简化）：一首歌一条走廊 */
    private static final class Segment {
        long seed;
        List<KrcParser.LyricLine> lines = new ArrayList<>();
        List<Frame> frames = new ArrayList<>();
        Vec3 origin = new Vec3();
    }

    // ===== 状态 =====

    private final List<KrcParser.LyricLine> lines = Collections.emptyList();
    private boolean[] chorusFlags = new boolean[0];
    private List<Segment> segments = new ArrayList<>();
    private long songSeed = 0L;
    private int transitionEpoch = 0;

    // 相机状态（CameraRig refs 直译）
    private final Vec3 pos = new Vec3(0f, 0.6f, 9f);
    private final Vec3 posVel = new Vec3();
    private final Vec3 look = new Vec3(0f, 0f, -10f);
    private final Vec3 lookVel = new Vec3();
    private boolean camInit = false;
    private boolean lookInit = false;
    private float alignWeight = 0f;
    // 切歌飞行（transition flight）
    private boolean flying = false;
    private int flightEpoch = 0;
    private float flightStartWall;
    private final Vec3 flightPerp = new Vec3(1f, 0f, 0f);
    private float flightSide = 1f;
    private float flightLen = 1f;
    private float arcX, arcY, arcZ; // 本帧弧偏移（渲染层叠加）

    private int positionMs = -1;    private int colorPrimary = Color.rgb(0xE8, 0xEE, 0xF6);
    private int colorAccent = Color.rgb(0x6E, 0xE8, 0xD8);
    private int fogColor = Color.rgb(2, 4, 10);

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private boolean running = false;
    private long lastFrameNs = -1L;
    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!running) return;
            long deltaNs = lastFrameNs < 0 ? 0 : frameTimeNanos - lastFrameNs;
            lastFrameNs = frameTimeNanos;
            float dt = Math.min(0.05f, Math.max(0.001f, deltaNs / 1_000_000_000f));
            step(dt);
            invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public DioramaLyricView(Context context) { super(context); init(); }
    public DioramaLyricView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public DioramaLyricView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr); init();
    }

    private void init() {
        // 默认硬件加速：UNISOC uis7862a 车机软层位图合成路径失效，镜台歌词整层不显示
        // （同页星空/封面流硬加速层正常，2026-11 用户实测）；文字 setShadowLayer 辉光
        // 硬加速原生支持，无其他软层专属依赖，去软层零视觉差
    }

    // ===== 对外接口（与浮名/星空同构） =====

    public void setLyricData(KrcParser.LyricData data, long seed) {
        List<KrcParser.LyricLine> newLines =
                data != null && data.lines != null ? data.lines : Collections.<KrcParser.LyricLine>emptyList();
        detectChorus(newLines);
        // 新段：spawn 在旧段尾部远方（pickTransitionOffset 语义），epoch 触发相机飞行
        Segment seg = new Segment();
        seg.seed = seed;
        seg.lines.addAll(newLines);
        Vec3 origin = new Vec3(0f, 0f, 0f);
        if (!segments.isEmpty()) {
            Segment prev = segments.get(segments.size() - 1);
            Vec3 tail = prev.frames.isEmpty() ? prev.origin : prev.frames.get(prev.frames.size() - 1).position;
            Vec3 off = pickTransitionOffset(seed, transitionEpoch + 1);
            origin.set(tail.x + off.x, tail.y + off.y, tail.z + off.z);
        }
        seg.origin.copy(origin);
        buildSegmentFrames(seg);
        segments.add(seg);
        transitionEpoch++;
        pruneSegments();
        this.songSeed = seed;
        invalidate();
    }

    public void updatePosition(int positionMs) { this.positionMs = positionMs; }

    public void setAccentColor(int accent) {
        if (accent != 0) colorAccent = accent;
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

    // ===== 副歌检测（与浮名同族：文本出现≥2 次即副歌） =====

    private void detectChorus(List<KrcParser.LyricLine> ls) {
        chorusFlags = new boolean[ls.size()];
        java.util.HashMap<String, Integer> counts = new java.util.HashMap<>();
        for (KrcParser.LyricLine l : ls) {
            String t = l.text != null ? l.text.trim() : "";
            if (t.isEmpty()) continue;
            Integer c = counts.get(t);
            counts.put(t, c == null ? 1 : c + 1);
        }
        for (int i = 0; i < ls.size(); i++) {
            String t = ls.get(i).text != null ? ls.get(i).text.trim() : "";
            Integer c = counts.get(t);
            chorusFlags[i] = c != null && c >= 2;
        }
    }

    private void buildSegmentFrames(Segment seg) {
        seg.frames.clear();
        buildDioramaPath(seg.frames, Math.max(seg.lines.size(), 1), seg.seed, seg.origin);
    }

    private void pruneSegments() {
        if (segments.size() <= 2) return;
        segments.remove(0);
    }

    // ===== 纯数学：seed / 随机（cameraPath 直译） =====

    /** FNV 确定性 seed → [0,1)（seededUnit） */
    private static float seededUnit(long seed) {
        // hashSeed：FNV-1a 变体
        long h = (seed & 0xFFFFFFFFL) ^ 0x811C9DC5L;
        h *= 0x01000193L; h &= 0xFFFFFFFFL;
        h ^= (h >>> 13);
        h *= 0x7F4A7C15L; h &= 0xFFFFFFFFL;
        h ^= (h >>> 16);
        return (h & 0xFFFFFFL) / (float) 0x1000000L;
    }

    private static long hashSeedOf(long seed) {
        // 与 seededUnit 同源，返回整数哈希（供二次 salt）
        long h = (seed & 0xFFFFFFFFL) ^ 0x811C9DC5L;
        h *= 0x01000193L; h &= 0xFFFFFFFFL;
        h ^= (h >>> 13);
        h *= 0x7F4A7C15L; h &= 0xFFFFFFFFL;
        h ^= (h >>> 16);
        return h;
    }

    // ===== 纯数学：蜿蜒走廊（buildDioramaPath 直译） =====

    private static void buildDioramaPath(List<Frame> out, int count, long seed, Vec3 origin) {
        float base = hashSeedOf(seed) % 100000L / 100000f;
        int n = Math.max(count, 1);
        List<Vec3> positions = new ArrayList<>(n + 1);
        float px = 0f, py = 0f, pz = 0f;
        for (int i = 0; i <= n; i++) {
            positions.add(new Vec3(px, py, pz));
            float yaw = MAX_YAW * ((float) (Math.sin(i * 0.23 + base * 0.017) * 0.62
                    + Math.sin(i * 0.11 + base * 0.041 + 1.3) * 0.38));
            float pitch = MAX_PITCH * (float) Math.sin(i * 0.17 + base * 0.029 + 0.7);
            float cp = (float) Math.cos(pitch);
            float dx = (float) (Math.sin(yaw) * cp), dy = (float) Math.sin(pitch), dz = -(float) (Math.cos(yaw) * cp);
            px += dx * STEP_DISTANCE;
            py += dy * STEP_DISTANCE;
            pz += dz * STEP_DISTANCE;
        }
        out.clear();
        for (int i = 0; i < n; i++) {
            Frame f = new Frame();
            Vec3 a = positions.get(i), b = positions.get(i + 1);
            // forward = norm(b - a)
            float fx = b.x - a.x, fy = b.y - a.y, fz = b.z - a.z;
            float fl = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
            if (fl < 1e-6f) { fx = 0; fy = 0; fz = -1; fl = 1; }
            f.forward.set(fx / fl, fy / fl, fz / fl);
            // right = norm(forward × worldUp)：fwd=(0,0,-1) 时 = (+1,0,0)，与文字阅读方向一致
            float rx = -f.forward.z, ry = 0f, rz = f.forward.x;
            float rl = (float) Math.sqrt(rx * rx + rz * rz);
            if (rl < 1e-6f) { rx = 1; rz = 0; rl = 1; }
            f.right.set(rx / rl, ry, rz / rl);
            // up = right × forward
            float ux = f.right.y * f.forward.z - f.right.z * f.forward.y;
            float uy = f.right.z * f.forward.x - f.right.x * f.forward.z;
            float uz = f.right.x * f.forward.y - f.right.y * f.forward.x;
            float ul = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
            if (ul < 1e-6f) { ux = 0; uy = 1; uz = 0; ul = 1; }
            f.up.set(ux / ul, uy / ul, uz / ul);
            f.position.set(a.x + origin.x, a.y + origin.y, a.z + origin.z);
            out.add(f);
        }
    }

    /** composeLocal：帧局部 (right, up, depth) 偏移 → 世界点 */
    private static void composeLocal(Frame f, float right, float up, float depth, Vec3 out) {
        out.set(f.position.x + f.right.x * right + f.up.x * up + f.forward.x * depth,
                f.position.y + f.right.y * right + f.up.y * up + f.forward.y * depth,
                f.position.z + f.right.z * right + f.up.z * up + f.forward.z * depth);
    }

    /** frameHalfWidth（直译）：距离 d 处可见半宽（垂直 fov 下） */
    private static float frameHalfWidth(float distance, float fovDeg, float aspect) {
        return (float) (distance * Math.tan(Math.toRadians(fovDeg) / 2) * aspect);
    }

    /** tanh 饱和卡车平移（resolveReadHeadTruck 直译） */
    private static float resolveReadHeadTruck(float wordProgress, float trackWidth, float visibleHalfWidth) {
        if (trackWidth <= 0f) return 0f;
        float wordOffset = (clamp01(wordProgress) - 0.5f) * trackWidth;
        float allowed = Math.max(Math.abs(visibleHalfWidth - trackWidth * 0.5f), 0.001f);
        return allowed * (float) Math.tanh(wordOffset / allowed);
    }

    // ===== 纯数学：文字摆位（getDioramaTextPlacement 直译） =====

    private static void textPlacement(int lineIndex, long seed, Placement out) {
        float base = hashSeedOf(seed) % 100000L / 100000f;
        long h = hashSeedOf(seed);
        int i = Math.max(lineIndex, 0);
        float jitterR = (seededUnit(h + i * 11 + 2) - 0.5f) * 0.6f;
        float jitterU = (seededUnit(h + i * 11 + 4) - 0.5f) * 0.6f;
        float wanderR = (float) (Math.sin(i * 0.47 + base * 0.019) * 0.7 + jitterR);
        float wanderU = (float) (Math.sin(i * 0.29 + base * 0.027 + 0.9) * 0.7 + jitterU);
        out.offsetR = wanderR * TEXT_OFFSET_R * WEAVE_SCALE;
        out.offsetU = wanderU * TEXT_OFFSET_U * WEAVE_SCALE;
        out.scale = 0.82f + seededUnit(h + i * 11 + 5) * 0.46f;
        out.roll = (seededUnit(h + i * 11 + 6) - 0.5f) * 2f * TEXT_ROLL * WEAVE_SCALE;
        out.yaw = (seededUnit(h + i * 11 + 7) - 0.5f) * 2f * TEXT_YAW * WEAVE_SCALE;
        out.lookR = (seededUnit(h + i * 11 + 8) - 0.5f) * 2f * TEXT_LOOK * WEAVE_SCALE;
    }

    // ===== 纯数学：镜头语言（computeShotWeights / getDioramaShot 直译） =====

    private static boolean isChorusLine(int lineIndex, List<KrcParser.LyricLine> ls, boolean[] flags) {
        return lineIndex >= 0 && lineIndex < flags.length && flags[lineIndex];
    }

    private static float[] computeShotWeights(int lineIndex, List<KrcParser.LyricLine> ls, boolean[] flags) {
        KrcParser.LyricLine line = lineIndex >= 0 && lineIndex < ls.size() ? ls.get(lineIndex) : null;
        int wordCount = line != null && line.words != null ? line.words.size() : 0;
        boolean isChorus = isChorusLine(lineIndex, ls, flags);
        long sum = 0; int noteCount = 0; float maxNote = 0f;
        if (line != null && line.words != null) {
            for (KrcParser.LyricWord w : line.words) {
                float d = Math.max(0, w.duration) / 1000f;
                if (d > 0) { maxNote = Math.max(maxNote, d); sum += d; noteCount++; }
            }
        }
        float avgNote = noteCount > 0 ? sum / (float) noteCount : 0f;
        boolean hasLongNote = maxNote >= 1.3f;
        boolean staccato = noteCount >= 4 && avgNote > 0 && avgNote <= 0.34f;

        float[] w = new float[]{1, 1, 1, 1, 1, 1, 0.85f, 0.9f, 1, 0.9f, 1, 0.9f, 1};
        if (isChorus) {
            w[SHOT_ORBIT] += 2.4f;
            w[SHOT_PUSH_IN] += 1.8f;
            w[SHOT_SPIRAL] += 1.2f;
            w[SHOT_CRANE] += 0.8f;
            w[SHOT_FLYBY] += 0.6f;
            w[SHOT_ARC] += 1.0f;
            w[SHOT_GLIDE] += 0.6f;
            w[SHOT_HOLD] -= 0.6f;
            w[SHOT_TRACK] -= 0.2f;
        } else {
            w[SHOT_TRACK] += 1.1f;
            w[SHOT_CRANE] += 0.9f;
            w[SHOT_HOLD] += 0.9f;
            w[SHOT_PENDULUM] += 0.8f;
            w[SHOT_FLOAT] += 0.8f;
            w[SHOT_GLIDE] += 0.6f;
            w[SHOT_ARC] += 0.4f;
        }
        if (wordCount >= 8) {
            w[SHOT_TRACK] += 1.6f;
            w[SHOT_PULL_BACK] += 1;
            w[SHOT_ORBIT] -= 0.4f;
            w[SHOT_PUSH_IN] -= 0.3f;
        } else if (wordCount <= 3) {
            w[SHOT_PUSH_IN] += 1.4f;
            w[SHOT_ORBIT] += 0.9f;
            w[SHOT_HOLD] += 0.5f;
            w[SHOT_TRACK] -= 0.8f;
        }
        if (hasLongNote) {
            w[SHOT_SWELL] += 2.4f;
            w[SHOT_SPIRAL] += 1.0f;
            w[SHOT_FLOAT] += 1.2f;
            w[SHOT_PENDULUM] += 0.6f;
            w[SHOT_ORBIT] += 0.8f;
            w[SHOT_ARC] += 0.6f;
            w[SHOT_HOLD] += 0.3f;
            w[SHOT_TRACK] -= 0.5f;
        }
        if (staccato) {
            w[SHOT_TRACK] += 1.4f;
            w[SHOT_FLYBY] += 1.5f;
            w[SHOT_PUSH_IN] += 0.8f;
            w[SHOT_SWELL] -= 1.6f;
            w[SHOT_HOLD] -= 0.8f;
            w[SHOT_CRANE] -= 0.4f;
        }
        for (int k = 0; k < w.length; k++) w[k] = Math.max(0.05f, w[k]);
        return w;
    }

    private static int weightedPickShot(float[] weights, float u) {
        float total = 0;
        for (float w : weights) total += w;
        float acc = clamp01(u) * total;
        for (int k = 0; k < SHOT_KINDS.length; k++) {
            acc -= weights[SHOT_KINDS[k]];
            if (acc <= 0) return SHOT_KINDS[k];
        }
        return SHOT_HOLD;
    }

    private static int basePickShot(int lineIndex, List<KrcParser.LyricLine> ls, long seed, boolean[] flags) {
        if (lineIndex < 0 || lineIndex >= ls.size()) return SHOT_HOLD;
        float u = seededUnit(hashSeedOf(seed) + lineIndex * 101 + 7);
        return weightedPickShot(computeShotWeights(lineIndex, ls, flags), u);
    }

    /** 镜头抽取（前两行去重防聚集，直译） */
    private static int dioramaShot(int lineIndex, List<KrcParser.LyricLine> ls, long seed, boolean[] flags) {
        int pick = basePickShot(lineIndex, ls, seed, flags);
        Integer prev1 = lineIndex > 0 ? basePickShot(lineIndex - 1, ls, seed, flags) : null;
        Integer prev2 = lineIndex > 1 ? basePickShot(lineIndex - 2, ls, seed, flags) : null;
        if ((prev1 != null && pick == prev1) || (prev2 != null && pick == prev2)) {
            List<Integer> pool = new ArrayList<>();
            for (int k : SHOT_KINDS) {
                if (k != pick && (prev1 == null || k != prev1) && (prev2 == null || k != prev2)) pool.add(k);
            }
            if (pool.isEmpty()) {
                for (int k : SHOT_KINDS) if (k != pick) pool.add(k);
            }
            float alt = seededUnit(hashSeedOf(seed) + lineIndex * 53 + 19);
            if (!pool.isEmpty()) return pool.get((int) (clamp01(alt) * pool.size()) % pool.size());
        }
        return pick;
    }

    /** 镜头方向（getShotDir 直译） */
    private static int shotDir(int lineIndex, long seed) {
        return seededUnit(hashSeedOf(seed) + lineIndex * 7 + 3) < 0.5f ? -1 : 1;
    }

    /** smoothDamp（Unity 直译；out[0]=value out[1]=velocity） */
    private static float[] smoothDamp(float current, float target, float vel, float smoothTime, float dt) {
        float st = Math.max(0.0001f, smoothTime);
        float omega = 2f / st;
        float x = omega * dt;
        float expV = 1f / (1f + x + 0.48f * x * x + 0.235f * x * x * x);
        float change = current - target;
        float tmp = (vel + omega * change) * dt;
        float nv = (vel - omega * tmp) * expV;
        float nvalue = target + (change + tmp) * expV;
        if (target - current > 0f == nvalue > target) {
            nvalue = target;
            nv = 0f;
        }
        return new float[]{nvalue, nv};
    }

    private static float clamp01(float v) { return v < 0f ? 0f : v > 1f ? 1f : v; }

    /** easeInOut smoothstep */
    private static float easeInOut(float p) {
        float c = clamp01(p);
        return c * c * (3f - 2f * c);
    }

    private static float smoothstep01(float t) {
        float c = clamp01(t);
        return c * c * (3f - 2f * c);
    }

    /** 镜头偏移（resolveShotOffset 直译；out[0..2] = right, up, back） */
    private static float[] resolveShotOffset(int kind, float progress, float wordProgress,
                                             float hero, float lift, float moveScale,
                                             int lineIndex, long seed) {
        float e = easeInOut(progress);
        int dir = shotDir(lineIndex, seed);
        float right = 0f, up = lift, back = hero;
        switch (kind) {
            case SHOT_PUSH_IN:
                back = hero * (1.5f - 0.8f * e);
                up = lift * (1.2f - 0.5f * e) + 0.15f;
                break;
            case SHOT_PULL_BACK:
                back = hero * (0.85f + 1.15f * e);
                up = lift + 1.7f * e;
                break;
            case SHOT_ORBIT: {
                float theta = dir * 0.55f * (2 * e - 1);
                right = hero * (float) Math.sin(theta);
                back = hero * (float) Math.cos(theta);
                up = lift + 0.4f;
                break;
            }
            case SHOT_TRACK:
                right = dir * hero * 0.34f * (float) Math.sin(Math.PI * clamp01(wordProgress));
                back = hero * 1.06f;
                up = lift + 0.12f;
                break;
            case SHOT_CRANE: {
                float from = dir > 0 ? -1.5f : 2.4f;
                float to = dir > 0 ? 2.4f : -1.4f;
                up = from + (to - from) * e;
                back = hero * 1.05f;
                break;
            }
            case SHOT_SWELL: {
                float theta = dir * 0.4f * e;
                right = hero * (float) Math.sin(theta) * 1.1f;
                back = hero * (1.0f + 0.35f * e);
                up = lift + 1.2f * e + 0.3f;
                break;
            }
            case SHOT_SPIRAL: {
                float theta = dir * 0.7f * (2 * e - 1);
                right = hero * (float) Math.sin(theta) * 0.85f;
                back = hero * (1.05f - 0.1f * e);
                up = lift - 0.8f + 2.4f * e;
                break;
            }
            case SHOT_PENDULUM:
                right = dir * hero * 0.45f * (2 * e - 1);
                up = lift + 0.9f - 0.9f * (float) Math.sin((float) Math.PI * e);
                back = hero * 1.02f;
                break;
            case SHOT_FLYBY:
                right = dir * hero * (0.7f - 1.4f * e);
                back = hero * (0.78f + 0.12f * (float) Math.sin((float) Math.PI * e));
                up = lift + 0.05f;
                break;
            case SHOT_ARC: {
                float theta = dir * 0.5f * (float) Math.sin((float) Math.PI * e);
                right = hero * (float) Math.sin(theta) * 1.3f;
                back = hero * (1.05f + 0.12f * (1f - (float) Math.cos(theta)));
                up = lift + 0.5f * (float) Math.sin((float) Math.PI * e);
                break;
            }
            case SHOT_FLOAT:
                right = dir * hero * 0.16f * (float) Math.sin(e * (float) Math.PI * 2);
                up = lift + 0.6f + 0.5f * (float) Math.sin(e * (float) Math.PI * 2 + 1.0f);
                back = hero * (1.05f + 0.06f * (float) Math.sin(e * (float) Math.PI));
                break;
            case SHOT_GLIDE:
                right = dir * hero * 0.22f * e;
                up = lift + 1.4f * e;
                back = hero * (1.15f - 0.25f * e);
                break;
            case SHOT_HOLD:
            default:
                up = lift + 0.3f * (float) Math.sin(progress * Math.PI);
                back = hero;
                break;
        }
        float m = moveScale;
        return new float[]{right * m, lift + (up - lift) * m, hero + (back - hero) * m};
    }

    /** 长停释放（resolveHoldSettle 直译） */
    private static float resolveHoldSettle(float secondsHeld) {
        float t = clamp01((secondsHeld - HOLD_SETTLE_DELAY) / HOLD_SETTLE_EASE);
        return 1f - t * t * (3f - 2f * t);
    }

    /** 漂浮（resolveCameraDrift 直译；out: swayX swayY lift dist） */
    private static float[] cameraDrift(float time, long seed, float scale) {
        float ph = hashSeedOf(seed) % 100000L / 100000f * 0.017f;
        float swayX = (float) (Math.sin(time * 0.11 + ph) * 0.6 + Math.sin(time * 0.047 + ph * 1.7) * 0.4) * SWAY_AMP * scale;
        float swayY = (float) Math.sin(time * 0.09 + ph * 0.7) * SWAY_AMP * 0.5f * scale;
        float lift = (float) (Math.sin(time * 0.13 + ph * 1.3) * 0.6 + Math.sin(time * 0.061 + ph * 2.1) * 0.4) * LIFT_AMP * scale;
        float dist = (float) Math.sin(time * 0.05 + ph * 1.1) * DIST_AMP * scale;
        return new float[]{swayX, swayY, lift, dist};
    }

    // ===== 切歌飞行（dioramaTransition 直译） =====

    private static Vec3 pickTransitionOffset(long seed, int epoch) {
        long base = hashSeedOf(seed) + epoch * 131;
        float azimuth = seededUnit(base + 1) * (float) Math.PI * 2f;
        float elevation = (seededUnit(base + 2) - 0.35f) * 1.3f;
        float ce = (float) Math.cos(elevation);
        float dx = (float) (Math.sin(azimuth) * ce);
        float dy = (float) Math.sin(elevation);
        float dz = -(float) Math.abs(Math.cos(azimuth) * ce) - 0.35f;
        float l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (l < 1e-3f) l = 1e-3f;
        return new Vec3(dx / l * TRANSITION_DISTANCE, dy / l * TRANSITION_DISTANCE, dz / l * TRANSITION_DISTANCE);
    }

    private static float transitionEase(float t) {
        float c = Math.min(1f, Math.max(0f, t));
        return c * c * c * (c * (c * 6f - 15f) + 10f);
    }

    private static int flightSide(long seed, int epoch) {
        return seededUnit(hashSeedOf(seed) + epoch * 17) < 0.5f ? -1 : 1;
    }

    // ===== 词进度 / 行结束（renderHints 简化为词数据驱动） =====

    /** 词进度（0..1）：KRC 词时间线性推进，返回已唱字符数（小数） */
    private static float wordProgressOf(KrcParser.LyricLine line, float now, int clusterCount) {
        if (line == null) return 0f;
        if (line.words == null || line.words.isEmpty()) {
            // 无词 LRC：行内均匀
            float start = line.startTime / 1000f;
            float end = renderEndOf(line);
            float p = clamp01((now - start) / Math.max(end - start, 0.18f));
            return p * Math.max(clusterCount, 1);
        }
        float acc = 0f;
        int charsBefore = 0;
        for (KrcParser.LyricWord w : line.words) {
            String wt = w.text != null ? w.text : "";
            int len = Math.max(1, wt.length());
            float wStart = w.startTime / 1000f;
            float wEnd = wStart + Math.max(0.001f, w.duration / 1000f);
            if (now >= wEnd) {
                charsBefore += len;
                acc = charsBefore;
            } else if (now > wStart) {
                float p = clamp01((now - wStart) / Math.max(wEnd - wStart, 0.08f));
                return charsBefore + p * len;
            } else {
                break;
            }
        }
        return acc;
    }

    private static float renderEndOf(KrcParser.LyricLine line) {
        if (line == null) return 0f;
        float end = (line.startTime + Math.max(line.duration, 1)) / 1000f;
        if (line.words != null && !line.words.isEmpty()) {
            KrcParser.LyricWord last = line.words.get(line.words.size() - 1);
            float lastEnd = (last.startTime + Math.max(last.duration, 0)) / 1000f;
            end = Math.max(end, lastEnd);
        }
        return end;
    }

    // ===== 相机步进（CameraRig useFrame 直译） =====

    private void step(float dt) {
        Segment seg = segments.isEmpty() ? null : segments.get(segments.size() - 1);
        if (seg == null || getWidth() <= 0 || getHeight() <= 0) return;
        if (seg.lines.isEmpty() || seg.frames.isEmpty()) return;
        float now = positionMs / 1000f;
        int w = getWidth(), h = getHeight();
        float aspect = w / (float) h;

        // 当前行索引（时间线性查找）
        int lineIndex = 0;
        for (int i = 0; i < seg.lines.size(); i++) {
            if (seg.lines.get(i).startTime / 1000f <= now) lineIndex = i;
            else break;
        }
        KrcParser.LyricLine line = seg.lines.get(lineIndex);
        Frame frame = seg.frames.get(Math.min(lineIndex, seg.frames.size() - 1));
        float renderEnd = renderEndOf(line);
        float start = line.startTime / 1000f;
        float span = renderEnd - start;
        float progress = span > 0 ? clamp01((now - start) / span) : (now >= start ? 1f : 0f);
        int textLen = line.text != null ? line.text.length() : 0;
        float wordProgressChars = wordProgressOf(line, now, textLen);
        float wordProgress = textLen > 0 ? clamp01(wordProgressChars / textLen) : 0f;

        float visibleHalf = frameHalfWidth(HERO_DISTANCE, FOV_V_DEG, aspect) * SAFE_FRAME_FRACTION;
        float settle = resolveHoldSettle(now - renderEnd);

        // 摆位 + 读头
        Placement pl = new Placement();
        textPlacement(lineIndex, seg.seed, pl);
        float trackWidth = textLen * WORLD_TEXT_H; // 文字世界宽近似（每字≈字高）
        float truck = resolveReadHeadTruck(wordProgress, trackWidth, visibleHalf) * settle;
        float headR = pl.offsetR + truck;
        float headU = pl.offsetU;
        Vec3 base = new Vec3();
        base.set(frame.position.x + frame.right.x * headR + frame.up.x * headU,
                frame.position.y + frame.right.y * headR + frame.up.y * headU,
                frame.position.z + frame.right.z * headR + frame.up.z * headU);

        // 镜头 + 漂浮 → 位姿
        int shot = dioramaShot(lineIndex, seg.lines, seg.seed, chorusFlags);
        float[] off = resolveShotOffset(shot, progress, wordProgress, HERO_DISTANCE, CAMERA_LIFT,
                MOVE_SCALE * settle, lineIndex, seg.seed);
        float[] drift = cameraDrift(now, seg.seed, DRIFT_SCALE);
        float offRight = off[0] + drift[0];
        float offUp = off[1] + drift[1] + drift[2];
        float offBack = off[2] + drift[3];
        Vec3 pose = new Vec3();
        pose.set(base.x + frame.right.x * offRight + frame.up.x * offUp - frame.forward.x * offBack,
                base.y + frame.right.y * offRight + frame.up.y * offUp - frame.forward.y * offBack,
                base.z + frame.right.z * offRight + frame.up.z * offUp - frame.forward.z * offBack);

        // 切歌飞行（epoch 变化触发；弧线/侧倾/瞄准横扫；wall clock 驱动不受切歌时间重置影响）
        if (transitionEpoch != flightEpoch && transitionEpoch > 0) {
            flightEpoch = transitionEpoch;
            flying = true;
            flightStartWall = android.os.SystemClock.elapsedRealtime() / 1000f;
            float pfx = pose.z - pos.z, pfz = -(pose.x - pos.x);
            float plen = (float) Math.sqrt(pfx * pfx + pfz * pfz);
            if (plen < 1e-3f) { pfx = 1f; pfz = 0f; plen = 1f; }
            flightPerp.set(pfx / plen, 0f, pfz / plen);
            flightSide = flightSide(seg.seed, transitionEpoch);
            flightLen = (float) Math.sqrt((pose.x - pos.x) * (pose.x - pos.x)
                    + (pose.y - pos.y) * (pose.y - pos.y)
                    + (pose.z - pos.z) * (pose.z - pos.z));
        }
        boolean isFlying = false;
        float te = 1f;
        if (flying) {
            float wall = android.os.SystemClock.elapsedRealtime() / 1000f;
            te = (wall - flightStartWall) / TRANSITION_DURATION;
            if (te >= 1f) flying = false;
            else if (te > 0f) isFlying = true;
        }
        float followSmooth = isFlying
                ? FLIGHT_SMOOTH_TIME + (SMOOTH_TIME - FLIGHT_SMOOTH_TIME) * transitionEase(te)
                : SMOOTH_TIME;
        float swayEnv = isFlying ? (float) Math.pow(Math.sin(Math.PI * te), 2) : 0f;
        float arcMag = swayEnv * flightLen * FLIGHT_ARC_FRAC;
        float sweepMag = swayEnv * flightLen * TRANSITION_AIM_SWEEP * flightSide;
        float bank = swayEnv * TRANSITION_BANK * flightSide;

        // SmoothDamp 相机位置（大跳非飞行时 snap）
        float posGap = (float) Math.sqrt((pose.x - pos.x) * (pose.x - pos.x)
                + (pose.y - pos.y) * (pose.y - pos.y)
                + (pose.z - pos.z) * (pose.z - pos.z));
        boolean snapped = !isFlying && posGap > SNAP_DISTANCE;
        if (!camInit || snapped) {
            pos.copy(pose);
            posVel.set(0f, 0f, 0f);
            camInit = true;
        } else {
            float[] sx = smoothDamp(pos.x, pose.x, posVel.x, followSmooth, dt);
            float[] sy = smoothDamp(pos.y, pose.y, posVel.y, followSmooth, dt);
            float[] sz = smoothDamp(pos.z, pose.z, posVel.z, followSmooth, dt);
            pos.set(sx[0], sy[0], sz[0]);
            posVel.set(sx[1], sy[1], sz[1]);
        }
        // 弧线视觉偏移（不写回 pos，供渲染层叠加）
        arcX = flightPerp.x * arcMag;
        arcY = flightPerp.y * arcMag + arcMag * 0.5f;
        arcZ = flightPerp.z * arcMag;

        // look：读头 + lookR 构图偏移 + 飞行横扫（snap 跟随位置大跳）
        float lookOff = pl.lookR * settle;
        float lx = base.x + frame.right.x * lookOff + flightPerp.x * sweepMag;
        float ly = base.y + frame.right.y * lookOff + flightPerp.y * sweepMag;
        float lz = base.z + frame.right.z * lookOff + flightPerp.z * sweepMag;
        float lookGap = (float) Math.sqrt((lx - look.x) * (lx - look.x)
                + (ly - look.y) * (ly - look.y)
                + (lz - look.z) * (lz - look.z));
        boolean lookSnap = snapped || !lookInit || (!isFlying && lookGap > SNAP_DISTANCE);
        if (lookSnap) {
            look.set(lx, ly, lz);
            lookVel.set(0f, 0f, 0f);
            lookInit = true;
        } else {
            float[] ax = smoothDamp(look.x, lx, lookVel.x, followSmooth, dt);
            float[] ay = smoothDamp(look.y, ly, lookVel.y, followSmooth, dt);
            float[] az = smoothDamp(look.z, lz, lookVel.z, followSmooth, dt);
            look.set(ax[0], ay[0], az[0]);
            lookVel.set(ax[1], ay[1], az[1]);
        }

        // 对齐权重（回正）：readNeed 平滑升降 × 镜头上限
        float readNeed = 0f;
        float rampIn = smoothstep01((now - (start - ALIGN_LEAD_IN)) / (ALIGN_LEAD_IN + ALIGN_SETTLE));
        float rampOut = 1f - smoothstep01((now - renderEnd) / ALIGN_RELEASE);
        readNeed = Math.min(rampIn, rampOut) * ALIGN_CEILING[shot];
        alignWeight += (readNeed - alignWeight) * (1f - (float) Math.exp(-ALIGN_RATE * dt));

        // 本帧渲染状态缓存
        renderLineIndex = lineIndex;
        renderShot = shot;
        renderBank = bank;
    }

    // ===== 渲染状态（step → onDraw） =====
    private int renderLineIndex = -1;
    private int renderShot = SHOT_HOLD;
    private float renderBank = 0f;

    // ===== 相机基（lookAt 基） =====
    private final Vec3 camRight = new Vec3(1f, 0f, 0f);
    private final Vec3 camUp = new Vec3(0f, 1f, 0f);
    private final Vec3 camFwd = new Vec3(0f, 0f, -1f);

    private void buildCameraBasis(float cx, float cy, float cz) {
        float fx = look.x - cx, fy = look.y - cy, fz = look.z - cz;
        float fl = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (fl < 1e-5f) { fx = 0; fy = 0; fz = -1; fl = 1; }
        camFwd.set(fx / fl, fy / fl, fz / fl);
        // camRight = norm(camFwd × worldUp)（右手系：fwd=(0,0,-1) 时 right=+x）
        float rx = -camFwd.z, ry = 0f, rz = camFwd.x;
        float rl = (float) Math.sqrt(rx * rx + rz * rz);
        if (rl < 1e-5f) { rx = 1; rz = 0; rl = 1; }
        camRight.set(rx / rl, 0f, rz / rl);
        float ux = camRight.y * camFwd.z - camRight.z * camFwd.y;
        float uy = camRight.z * camFwd.x - camRight.x * camFwd.z;
        float uz = camRight.x * camFwd.y - camRight.y * camFwd.x;
        camUp.set(ux, uy, uz);
    }

    /** 透视投影：out[0]=sx out[1]=sy out[2]=深度（<0 在背后） */
    private void project(float px, float py, float pz, float f, float[] out) {
        float vx = px - (pos.x + arcX), vy = py - (pos.y + arcY), vz = pz - (pos.z + arcZ);
        float z = vx * camFwd.x + vy * camFwd.y + vz * camFwd.z;
        float x = vx * camRight.x + vy * camRight.y + vz * camRight.z;
        float y = vx * camUp.x + vy * camUp.y + vz * camUp.z;
        if (z < 0.05f) { out[2] = -1f; return; }
        out[0] = getWidth() * 0.5f + x / z * f;
        out[1] = getHeight() * 0.5f - y / z * f;
        out[2] = z;
    }

    /** 距离生命（resolveTextLife 直译：远端雾生 / 近端穿镜溶解） */
    private static float textLife(float dist) {
        float farT = clamp01((TEXT_FADE_IN_END - dist) / (TEXT_FADE_IN_END - TEXT_FADE_IN_START));
        float nearT = clamp01((dist - TEXT_DISSOLVE_END) / (TEXT_DISSOLVE_START - TEXT_DISSOLVE_END));
        return smoothstep01(farT) * smoothstep01(nearT);
    }

    private static int mixRGB(int from, int to, float t) {
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

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (segments.isEmpty()) return;
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float now = positionMs / 1000f;
        Segment seg = segments.get(segments.size() - 1);
        if (seg.lines.isEmpty() || seg.frames.isEmpty()) return;

        // 相机基：位置含飞行弧偏移（与 step 中一致）
        buildCameraBasis(pos.x + arcX, pos.y + arcY, pos.z + arcZ);
        float f = (h * 0.5f) / (float) Math.tan(Math.toRadians(FOV_V_DEG) / 2);

        // 飞行侧倾（bank）：全画面绕屏幕中心转（视觉层叠加，不进相机态）
        int bankSc = canvas.save();
        if (renderBank != 0f) {
            canvas.rotate((float) Math.toDegrees(renderBank), w * 0.5f, h * 0.5f);
        }

        // ---- 尘埃粒子（走廊 mote：环绕当前行 frame 的 seeded 散点，前后 ±3 行） ----
        drawMotes(canvas, seg, f, now);

        // ---- 歌词行平面（当前行前 2 后 6 的窗口；从远到近：远的先画） ----
        int cur = Math.max(0, renderLineIndex);
        for (int k = Math.min(seg.lines.size() - 1, cur + 2); k >= Math.max(0, cur - 6); k--) {
            drawLine(canvas, seg, k, f, now, k == cur);
        }
        canvas.restoreToCount(bankSc);
        textPaint.setShadowLayer(0, 0, 0, 0);
    }

    /** 一行文字（DioramaScene 文字平面：摆位/fit/生命/跟唱染色） */
    private void drawLine(Canvas canvas, Segment seg, int lineIndex, float f, float now, boolean isActiveLine) {
        KrcParser.LyricLine line = seg.lines.get(lineIndex);
        String text = line.text;
        if (text == null || text.trim().isEmpty()) return;
        Frame frame = seg.frames.get(Math.min(lineIndex, seg.frames.size() - 1));
        Placement pl = new Placement();
        textPlacement(lineIndex, seg.seed, pl);

        // 文字中心世界点
        Vec3 center = new Vec3();
        composeLocal(frame, pl.offsetR, pl.offsetU, 0f, center);
        float[] pc = new float[3];
        project(center.x, center.y, center.z, f, pc);
        if (pc[2] < 0f) return;
        float dist = pc[2];

        // 距离生命
        float life = textLife(dist);
        if (life <= 0.002f) return;
        // 非当前行：确定性随机 10%~60% 错落衰减（同句每次同值，逐行各异）
        if (!isActiveLine) {
            life *= 0.10f + seededUnit(hashSeedOf(seg.seed) + lineIndex * 3 + 11) * 0.50f;
        }

        // fit scale（resolveFrameFitScale 直译；世界宽 = 字数 × 字高近似）
        float worldW = text.length() * WORLD_TEXT_H * 1.02f;
        float frameW = 2f * dist * (float) Math.tan(Math.toRadians(FOV_V_DEG) / 2)
                * (getWidth() / (float) getHeight());
        float fit = worldW > 0 && dist > 0 ? Math.min(1f, Math.max(MIN_FIT_SCALE,
                frameW * TARGET_FRAME_WIDTH_FRACTION / worldW)) : 1f;

        // 屏幕角度：行平面 right 方向投影（沿局部 right 平移 1 单位的参考点；
        // atan2 用屏幕坐标 y 向下、顺时针为正，与 canvas.rotate 约定一致）
        Vec3 tipR = new Vec3();
        composeLocal(frame, pl.offsetR + 1f, pl.offsetU, 0f, tipR);
        float[] pt = new float[3];
        project(tipR.x, tipR.y, tipR.z, f, pt);
        if (pt[2] < 0f) return;
        float rawAngle = (float) Math.atan2(pt[1] - pc[1], pt[0] - pc[0]);
        // 平面内 roll：+roll 在相机视角为逆时针 → 屏幕顺时针角取负
        float rollScreen = -pl.roll;
        // 对齐：唱到的行按权重回正水平
        float screenAngle = mixAngle(rawAngle + rollScreen, 0f, isActiveLine ? alignWeight : 0f);
        // yaw 视角缩短（近似透视厚度）
        float yawShrink = (float) Math.cos(pl.yaw);

        // 字号：世界高度 × 投影比例
        float textPx = WORLD_TEXT_H * pl.scale * fit * f / dist;
        if (textPx < 2f) return;
        textPaint.setTextSize(textPx);

        // 深度雾（远端融入雾色）
        float fog = clamp01(dist / FOG_FAR);
        int baseColor = mixRGB(colorPrimary, fogColor, fog * 0.72f);

        // 词级跟唱染色（resolveGradientEnergy：词唱过染 accent，hold 0.35s 后 1.8s 衰减）
        if (line.words != null && !line.words.isEmpty()) {
            drawWordsByTiming(canvas, line, text, pc[0], pc[1], textPx, screenAngle,
                    yawShrink, baseColor, life, now, lineIndex, seg);
        } else {
            // LRC 整行均匀高亮
            float start = line.startTime / 1000f;
            float p = clamp01((now - start) / Math.max(renderEndOf(line) - start, 0.18f));
            int c = mixRGB(baseColor, colorAccent, p);
            drawRotatedText(canvas, text, pc[0], pc[1], screenAngle, withAlpha(c, life));
        }
    }

    /** KRC 词时间驱动逐词染色绘制 */
    private void drawWordsByTiming(Canvas canvas, KrcParser.LyricLine line, String text,
                                   float cx, float cy, float textPx, float angle, float yawShrink,
                                   int baseColor, float life, float now, int lineIndex, Segment seg) {
        int sc = canvas.save();
        canvas.translate(cx, cy);
        canvas.rotate((float) Math.toDegrees(angle));

        float fontScalePx = textPx * yawShrink;
        textPaint.setTextSize(fontScalePx);
        float textW = textPaint.measureText(text);
        float x = -textW * 0.5f;

        // 词区间（含尾随空白并入）
        List<int[]> ranges = wordCharRanges(line, text);
        int cursor = 0;
        for (int ri = 0; ri < ranges.size(); ri++) {
            int[] rg = ranges.get(ri);
            KrcParser.LyricWord word = line.words.get(ri);
            float segW = textPaint.measureText(text, rg[0], rg[1]);
            float wStart = word.startTime / 1000f;
            float wEnd = wStart + Math.max(0.001f, word.duration / 1000f);
            // 渐变跟唱 energy
            float energy;
            if (now <= wStart) energy = 0f;
            else if (now < wEnd) energy = smoothstep01(clamp01((now - wStart) / Math.max(wEnd - wStart, 0.001f)));
            else {
                float since = now - wEnd;
                if (since <= GRADIENT_HOLD_SECONDS) energy = 1f;
                else energy = 1f - smoothstep01(clamp01((since - GRADIENT_HOLD_SECONDS) / GRADIENT_TRAIL_SECONDS));
            }
            int c = mixRGB(baseColor, mixRGB(colorAccent, baseColor, 0.15f), energy);
            // 当前词微辉光
            if (energy > 0.01f) {
                textPaint.setShadowLayer(textPx * 0.22f * energy, 0, 0,
                        withAlpha(colorAccent, 0.5f * energy * life));
            } else {
                textPaint.setShadowLayer(0, 0, 0, 0);
            }
            textPaint.setColor(withAlpha(c, life));
            canvas.drawText(text, rg[0], rg[1], x, 0f, textPaint);
            x += segW;
            cursor = rg[1];
        }
        // 词区间未覆盖的尾部（防御）
        if (cursor < text.length()) {
            textPaint.setShadowLayer(0, 0, 0, 0);
            textPaint.setColor(withAlpha(baseColor, life));
            canvas.drawText(text, cursor, text.length(), x, 0f, textPaint);
        }
        textPaint.setShadowLayer(0, 0, 0, 0);
        canvas.restoreToCount(sc);
    }

    private void drawRotatedText(Canvas canvas, String text, float cx, float cy,
                                 float angle, int color) {
        int sc = canvas.save();
        canvas.translate(cx, cy);
        canvas.rotate((float) Math.toDegrees(angle));
        textPaint.setShadowLayer(0, 0, 0, 0);
        textPaint.setColor(color);
        float tw = textPaint.measureText(text);
        canvas.drawText(text, -tw * 0.5f, 0f, textPaint);
        canvas.restoreToCount(sc);
    }

    private static List<int[]> wordCharRanges(KrcParser.LyricLine line, String text) {
        List<int[]> ranges = new ArrayList<>();
        int n = text.length();
        int cursor = 0;
        List<KrcParser.LyricWord> words = line.words;
        if (words == null) {
            ranges.add(new int[]{0, n});
            return ranges;
        }
        for (KrcParser.LyricWord w : words) {
            String wt = w.text != null ? w.text : "";
            if (wt.isEmpty()) continue;
            int start = Math.min(cursor, n);
            int end = Math.min(start + wt.length(), n);
            while (end < n && Character.isWhitespace(text.charAt(end))) end++;
            if (end > start) ranges.add(new int[]{start, end});
            cursor = end;
        }
        if (ranges.isEmpty()) ranges.add(new int[]{0, Math.max(0, n)});
        return ranges;
    }

    /** 角度插值（走最短弧） */
    private static float mixAngle(float a, float b, float t) {
        float d = ((b - a + (float) Math.PI) % ((float) Math.PI * 2f) + (float) Math.PI * 2f) % ((float) Math.PI * 2f) - (float) Math.PI;
        return a + d * clamp01(t);
    }

    /** 走廊尘埃（background mote 降级：当前行 frame 前后 ±3 行 seeded 散点） */
    private void drawMotes(Canvas canvas, Segment seg, float f, float now) {
        int cur = Math.max(0, renderLineIndex);
        for (int li = Math.max(0, cur - 2); li <= Math.min(seg.frames.size() - 1, cur + 3); li++) {
            Frame fr = seg.frames.get(li);
            long base = hashSeedOf(seg.seed + li * 31);
            int count = 26;
            for (int i = 0; i < count; i++) {
                float r = (seededUnit(base + i * 3) - 0.5f) * 22f;      // 环向 ±11
                float u = (seededUnit(base + i * 3 + 1) - 0.5f) * 9f;   // 径向 ±4.5
                float d = (seededUnit(base + i * 3 + 2) - 0.5f) * 16f;  // 深度 ±8
                // 慢漂移
                float sway = (float) Math.sin(now * 0.31 + seededUnit(base + i) * 6.28f) * 0.22f;
                Vec3 p = new Vec3();
                composeLocal(fr, r + sway, u + sway * 0.4f, d, p);
                float[] pr = new float[3];
                project(p.x, p.y, p.z, f, pr);
                if (pr[2] < 0.1f || pr[2] > TEXT_FADE_IN_END) continue;
                float life = clamp01((TEXT_FADE_IN_END - pr[2]) / (TEXT_FADE_IN_END - 26f))
                        * clamp01((pr[2] - 0.5f) / 1.2f);
                if (life <= 0.01f) continue;
                float size = clamp01(1f - pr[2] / TEXT_FADE_IN_END) * 2.4f + 0.4f;
                float fog = clamp01(pr[2] / FOG_FAR);
                int c = withAlpha(mixRGB(colorPrimary, fogColor, fog * 0.85f), life * 0.5f);
                particlePaint.setColor(c);
                canvas.drawPoint(pr[0], pr[1], particlePaint);
            }
        }
    }
}
