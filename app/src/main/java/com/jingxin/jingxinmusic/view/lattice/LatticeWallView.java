package com.jingxin.jingxinmusic.view.lattice;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.Layout;
import android.graphics.Typeface;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.LongSparseArray;
import android.view.Choreographer;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.OverScroller;

import androidx.annotation.Nullable;

import com.jingxin.jingxinmusic.model.Song;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * 拼贴墙 View（FOLIA Lattice PosterWall + 相机 + 卡片渲染的 Android 全自绘移植）
 *
 * 结构对照 FOLIA：
 * - 相机（A 方案）：屏幕 = 世界 × camScale + cam；scale 按视口宽度三档（FOLIA getScale 原值）；
 *   世界几何恒定 unit=128/gap=8；拖/惯性速度按 1/scale 折算世界单位
 * - 展开让位：单进度弹簧 0→1，块内 12 卡矩形在基础模板与让位表间插值
 *   （线性系统：单弹簧驱动插值 ≡ FOLIA 每卡同参弹簧的轨迹，位移不超块宽时帧差 <0.5 单位）
 * - 相机弹簧 + OverScroller 惯性；动效运行期才挂 Choreographer，静止即停帧
 * - 触摸：拖动平移 / 点击展开；点已展开卡=播放语义（当前曲暂停续播，他曲切歌）
 *
 * 性能红线（车机）：
 * - onDraw 零分配（Paint/Rect/列表/shader 全预建复用；徽章字符串按队列缓存）
 * - 不用 setShadowLayer 辉光（5.1 GPU 崩溃病灶），高亮走细描边
 * - StaticLayout 用构造器而非 Builder（Builder 是 API 23+，项目兼容 5.1）
 */
public class LatticeWallView extends View {

    /** 卡片动作桥（页面实现，转 Fragment Actions） */
    public interface OnTileAction {
        void onPlaySongAt(int queueIndex);
        void onTogglePlayPause();
        void onPlayPrevious();
        void onPlayNext();
    }

    /** 空白处点击回调（顶部 chrome 呼出备选路径，保留） */
    public interface OnEmptyTapListener {
        void onEmptyTap();
    }

    /** 展开大卡封面区点击（传输带外）：进入歌词动效页（FOLIA：点展开卡正文打开详情页） */
    public interface OnExpandedCoverTapListener {
        void onExpandedCoverTap();
    }

    /** 长按呼出顶栏回调（任何位置含封面上，450ms，不依赖空白） */
    public interface OnLongPressListener {
        void onLongPress(float sx, float sy);
    }

    /** 本次按下的长按是否已触发（抬手吞掉 tap，防“长按呼出→抬手误点卡切歌”） */
    private boolean longPressFired = false;

    // ===== 度量（FOLIA 方案 A：固定世界单位 + 相机缩放按视口宽度三档）=====
    // 世界几何恒定 unit=128/gap=8（FOLIA CELL_SIZE/GAP 原值）；屏幕观感由相机 scale 决定
    private static final float UNIT = 128f;
    private static final float GAP_PX = 8f;
    private static final float OVERSCAN_FACTOR = 0.25f; // overscan = 25% 视口（世界单位）
    /** 绘制缓冲（几何上限 + 展开保活余量） */
    private static final int MAX_DRAWN = LatticeGeometry.MAX_INSTANCES + 16;

    /** FOLIA getScale 原值：宽度三档（<640→0.52，<1100→0.64，否则 0.76） */
    private static float scaleOf(float viewportWidth) {
        return viewportWidth < 640f ? 0.52f : viewportWidth < 1100f ? 0.64f : 0.76f;
    }

    private float gap = GAP_PX;
    private float cellSize = UNIT;
    /** 相机缩放（世界→屏幕），onSizeChanged 按宽度定档；展开卡屏幕占比随宽高比浮动（FOLIA 同） */
    private float camScale = 0.76f;

    // 世界 ↔ 屏幕：屏幕 = 世界 × camScale + cam
    private float worldToScreenX(float wx) { return wx * camScale + camX; }
    private float worldToScreenY(float wy) { return wy * camScale + camY; }
    private float screenToWorldX(float sx) { return (sx - camX) / camScale; }
    private float screenToWorldY(float sy) { return (sy - camY) / camScale; }

    // ===== 数据 =====
    private List<Song> playlist = new ArrayList<>();
    private int currentIndex = -1;
    private LatticeGeometry.Cell cell;
    private final LatticeCoverCache coverCache;
    private final LongSparseArray<String> badgeStrings = new LongSparseArray<>();

    // ===== 相机 =====
    private float camX = 0f, camY = 0f;

    // ===== 展开状态 =====
    @Nullable private LatticeGeometry.Instance expanded;
    private int expandedQueueIndex = -1;
    private final float[] grownRects = new float[12 * 4]; // 让位矩形（按 blockSlot）
    private final float[] baseRects = new float[12 * 4];  // 基础矩形（按 blockSlot）
    private float expandProgress = 0f;

    // ===== 弹簧（相机 + 展开进度；与 FOLIA wallReflowMotion 同参：k300/c34 阻尼比≈0.98）=====
    private static final float CAM_K = 260f, CAM_C = 30f;
    private static final float EXP_K = 300f, EXP_C = 34f;
    private float camVX, camVY, camTX, camTY;
    private boolean camSpringActive = false;
    private float expV = 0f, expTarget = 0f;
    private boolean expActive = false;

    // ===== 惯性 =====
    private final OverScroller scroller;
    private boolean flinging = false;

    // ===== 帧链 =====
    private final Choreographer choreographer = Choreographer.getInstance();
    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override public void doFrame(long timeNs) {
            if (!frameRunning) return;
            float dt = lastFrameNs == 0 ? 0.016f : Math.min(0.033f, (timeNs - lastFrameNs) / 1e9f);
            lastFrameNs = timeNs;
            boolean more = step(dt);
            invalidate();
            if (more) choreographer.postFrameCallback(this);
            else frameRunning = false;
        }
    };
    private boolean frameRunning = false;
    private long lastFrameNs = 0;

    // ===== 手势 =====
    private final GestureDetector detector;

    // ===== 绘制预分配 =====
    private final ArrayList<LatticeGeometry.Instance> drawn = new ArrayList<>(128);
    private final float[] drawnRects = new float[MAX_DRAWN * 4];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint badgePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Rect srcRect = new Rect();
    private final RectF dstRect = new RectF();
    private final Rect badgeRect = new Rect();
    private final float[] expandedTmp = new float[4];
    // 展开 scrim（缓存，矩形变化才重建）
    private LinearGradient scrimShader;
    private float scrimTop = Float.MIN_VALUE, scrimBottom = Float.MIN_VALUE;

    // ===== 主题 =====
    private static final int ACCENT = 0xFF00E5CC;          // LED 青
    private static final int BG_DAY = 0xFF10131A, BG_NIGHT = 0xFF06080D;
    private boolean night = false;
    private Shader vignette;

    private boolean entered = false; // 首次定位完成
    @Nullable private OnTileAction action;
    @Nullable private OnEmptyTapListener emptyTapListener;
    @Nullable private OnLongPressListener longPressListener;
    @Nullable private OnExpandedCoverTapListener expandedCoverTapListener;

    // 播放态/进度（展开卡传输条与进度线用）
    private boolean playing = false;
    private int progressPos = 0, progressDur = 0;
    private final Path glyphPath = new Path();
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF zoneRect = new RectF();

    // ===== 展开卡凸出感（浮起柔影+顶棱高光；随展开进度渐进）=====
    private static final int LIFT_LAYERS = 6;       // 柔影同心环层数
    private final Path liftPath = new Path();
    /** 卡面对角渐变（左下 50% 黑 → 右上透明）缓存：矩形变化才重建，onDraw 零分配 */
    private LinearGradient diagShader;
    private float diagRectX, diagRectY, diagRectW, diagRectH;

    // 频谱动态跟随色（进度线与频谱同色；未注入时进度线用频谱竖条默认色计算）
    private int tintTop = 0, tintBottom = 0;

    /** 展开卡频谱带矩形（屏幕坐标，页面把真 SpectrumView 实例盖过来；环类样式时为 null） */
    @Nullable private float[] spectrumBand;
    /** 页面注入：频谱带变化回调（每帧展开/拖动后带位置变了要同步） */
    @Nullable private Runnable spectrumBandUpdater;

    public LatticeWallView(Context context) { this(context, null); }

    public LatticeWallView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public LatticeWallView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        scroller = new OverScroller(context);
        coverCache = new LatticeCoverCache(context, (song, step, bitmap) -> postInvalidateOnAnimation());
        badgePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        detector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { longPressFired = false; return true; }

            @Override
            public void onLongPress(MotionEvent e) {
                // 长按呼出顶栏：不依赖空白（卡间隙太窄点不中），全屏任意位置都算，
                // 手指按住不移动不会触发 onScroll，拖动浏览不受影响
                longPressFired = true;
                if (longPressListener != null) {
                    longPressListener.onLongPress(e.getX(), e.getY());
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                }
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                cancelMotions();
                // 拖动量是屏幕像素，除以 scale 转世界单位
                camX -= dx;
                camY -= dy;
                invalidate();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                cancelMotions();
                // 惯性速度屏幕像素/s，转世界单位后驱动 fling
                float k = 1f / camScale;
                scroller.fling(Math.round(camX), Math.round(camY),
                        (int) (-vx * k), (int) (-vy * k),
                        Integer.MIN_VALUE, Integer.MAX_VALUE,
                        Integer.MIN_VALUE, Integer.MAX_VALUE);
                flinging = true;
                startFrameLoop();
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (longPressFired) { longPressFired = false; return true; } // 长按抬手不当作点击
                handleTap(e.getX(), e.getY());
                return true;
            }
        });
    }

    public void setExecutor(@Nullable ExecutorService executor) {
        coverCache.setExecutor(executor);
    }

    public void setOnTileAction(@Nullable OnTileAction action) { this.action = action; }

    public void setOnEmptyTapListener(@Nullable OnEmptyTapListener l) { this.emptyTapListener = l; }

    public void setOnLongPressListener(@Nullable OnLongPressListener l) { this.longPressListener = l; }

    public void setOnExpandedCoverTapListener(@Nullable OnExpandedCoverTapListener l) { this.expandedCoverTapListener = l; }

    public void setPlaying(boolean playing) {
        if (this.playing != playing) {
            this.playing = playing;
            invalidate();
        }
    }

    /** 进度（毫秒），展开卡底部 LED 进度线 */
    public void setProgress(int positionMs, int durationMs) {
        this.progressPos = positionMs;
        this.progressDur = durationMs;
        if (expanded != null && expandProgress > 0.5f) invalidate();
    }

    /** 频谱样式同步（页面把真值 SpectrumView 实例盖到频谱带，墙只算带矩形） */
    public void setSpectrumStyle(int style) {
        // 环类不画（页面也不会盖）：清带；否则展开时算带
        if (com.jingxin.jingxinmusic.view.SpectrumView.isOverlayStyle(style)) {
            spectrumBand = null;
        }
        invalidate();
    }

    /** 频谱动态跟随色（封面双色，页面注入；进度线与频谱同色用。null=回落默认） */
    public void setSpectrumTint(int colorTop, int colorBottom) {
        this.tintTop = colorTop;
        this.tintBottom = colorBottom;
        invalidate();
    }

    /** 页面注入频谱带同步回调 */
    public void setSpectrumBandUpdater(@Nullable Runnable updater) {
        this.spectrumBandUpdater = updater;
    }

    public void setNight(boolean night) {
        this.night = night;
        invalidate();
    }

    // ===== 数据入口 =====

    /** 队列数据（切歌/列表变化由页面推送） */
    public void setQueueData(List<Song> list, int index) {
        boolean countChanged = list == null || list.size() != playlist.size();
        boolean indexChanged = index != currentIndex;
        playlist = list != null ? list : new ArrayList<>();
        currentIndex = Math.max(-1, index);
        badgeStrings.clear();
        if (playlist.isEmpty()) {
            expanded = null;
            expandedQueueIndex = -1;
            invalidate();
            return;
        }
        if (countChanged || cell == null) {
            cell = LatticeGeometry.cellOf(playlist.size(), cellSize, gap);
        }
        if (!entered) {
            // 首次进入：相机瞬移居中当前曲（无飞行，防"漂移"感——FOLIA instant=true）
            centerOnCurrentInstant();
            entered = true;
        } else if (indexChanged || expanded == null) {
            // 切歌：新当前曲飞入并展开
            focusCurrentAnimated();
        } else if (expandedQueueIndex < playlist.size()) {
            // 同曲数据刷新：展开卡座标可能挪位，保持对位
            LatticeGeometry.Instance nearest = nearestToCenter(expandedQueueIndex);
            if (nearest != null && !sameInstance(nearest, expanded)) {
                expanded = nearest;
                expandedQueueIndex = nearest.queueIndex;
                computeGrownRects();
                    }
        }
        invalidate();
    }

    // ===== 定位 =====

    /** 视口中心的世界坐标（屏幕反推：世界 = (屏幕 - cam) / scale） */
    private float viewWorldX() { return screenToWorldX(getWidth() / 2f); }
    private float viewWorldY() { return screenToWorldY(getHeight() / 2f); }

    /** 世界矩形中心居屏所需相机平移（cam = 屏幕中心 - 世界中心×scale） */
    private float camXForCenter(float worldCx) { return getWidth() / 2f - worldCx * camScale; }
    private float camYForCenter(float worldCy) { return getHeight() / 2f - worldCy * camScale; }

    private LatticeGeometry.Instance nearestToCenter(int queueIndex) {
        if (cell == null || playlist.isEmpty() || queueIndex < 0 || queueIndex >= playlist.size()) return null;
        return LatticeGeometry.nearestOf(cell, playlist.size(), queueIndex,
                viewWorldX(), viewWorldY(), cellSize, gap);
    }

    private void centerOnCurrentInstant() {
        LatticeGeometry.Instance inst = nearestToCenter(currentIndex);
        if (inst == null) return;
        expanded = inst;
        expandedQueueIndex = inst.queueIndex;
        computeGrownRects();
        expandProgress = 0f;
        expTarget = 1f;
        expV = 0f;
        expActive = true;
        grownRectOf(inst, expandedTmp);
        camX = camXForCenter(expandedTmp[0] + expandedTmp[2] / 2f);
        camY = camYForCenter(expandedTmp[1] + expandedTmp[3] / 2f);
        cancelMotions();
        startFrameLoop();
    }

    private void focusCurrentAnimated() {
        LatticeGeometry.Instance inst = nearestToCenter(currentIndex);
        if (inst == null) return;
        expandInstance(inst);
    }

    /** 展开某实例：让位插值 + 相机弹簧飞至展开卡中心 */
    private void expandInstance(LatticeGeometry.Instance inst) {
        expanded = inst;
        expandedQueueIndex = inst.queueIndex;
        computeGrownRects();
        expTarget = 1f;
        if (expandProgress < 0.02f) expV = 0f;
        expActive = true;
        grownRectOf(inst, expandedTmp);
        camTX = camXForCenter(expandedTmp[0] + expandedTmp[2] / 2f);
        camTY = camYForCenter(expandedTmp[1] + expandedTmp[3] / 2f);
        camVX = camVY = 0;
        camSpringActive = true;
        cancelFling();
        startFrameLoop();
    }

    /** 展开实例的让位矩形 → out[4] */
    private void grownRectOf(LatticeGeometry.Instance inst, float[] out) {
        out[0] = grownRects[inst.blockSlot * 4];
        out[1] = grownRects[inst.blockSlot * 4 + 1];
        out[2] = grownRects[inst.blockSlot * 4 + 2];
        out[3] = grownRects[inst.blockSlot * 4 + 3];
    }

    /** 展开块 12 槽让位矩形 + 基础矩形 */
    private void computeGrownRects() {
        if (expanded == null || cell == null) return;
        LatticeGeometry.expandedBlockRects(cell, expanded, cellSize, gap, grownRects, 0);
        int col = LatticeGeometry.columnOf(expanded, cell);
        int row = LatticeGeometry.rowOf(expanded, cell);
        int localColumn = col - expanded.repeatX * cell.blocksPerRow;
        int localRow = row - expanded.repeatY * cell.blockRows;
        LatticeTemplates.Slot[] tmpl = LatticeTemplates.templateOf(localColumn, localRow);
        float pitch = cellSize + gap;
        float originX = expanded.repeatX * cell.cellWidth + localColumn * cell.blockWidth;
        float originY = expanded.repeatY * cell.cellHeight + localRow * cell.blockHeight;
        for (int s = 0; s < LatticeTemplates.SLOTS_PER_BLOCK; s++) {
            LatticeTemplates.Slot slot = tmpl[s];
            baseRects[s * 4] = originX + slot.x * pitch;
            baseRects[s * 4 + 1] = originY + slot.y * pitch;
            baseRects[s * 4 + 2] = slot.cols * pitch - gap;
            baseRects[s * 4 + 3] = slot.rows * pitch - gap;
        }
        scrimTop = Float.MIN_VALUE; // 几何变了，scrim 待重建
    }

    private void cancelMotions() {
        cancelFling();
        camSpringActive = false;
        camVX = camVY = 0;
    }

    private void cancelFling() {
        if (flinging) {
            scroller.abortAnimation();
            flinging = false;
        }
    }

    // ===== 点击 =====

    private void handleTap(float sx, float sy) {
        float wx = screenToWorldX(sx), wy = screenToWorldY(sy);
        for (int i = Math.min(drawn.size(), MAX_DRAWN) - 1; i >= 0; i--) {
            float x = drawnRects[i * 4], y = drawnRects[i * 4 + 1];
            float w = drawnRects[i * 4 + 2], h = drawnRects[i * 4 + 3];
            if (wx >= x && wx <= x + w && wy >= y && wy <= y + h) {
                LatticeGeometry.Instance hit = drawn.get(i);
                if (expanded != null && sameInstance(hit, expanded)) {
                    // 传输带（展开稳后生效）：三等分 ·上/播放暂停/下
                    if (expandProgress > 0.9f && action != null && wy >= y + h * 0.38f && wy <= y + h * 0.62f) {
                        if (wx < x + w / 3f) { action.onPlayPrevious(); return; }
                        if (wx > x + w * 2f / 3f) { action.onPlayNext(); return; }
                        action.onTogglePlayPause();
                        return;
                    }
                    // 带外封面区：进入歌词动效页（点大封面正文 != 点其他卡切歌）
                    if (expandProgress > 0.9f && expandedCoverTapListener != null) {
                        expandedCoverTapListener.onExpandedCoverTap();
                        return;
                    }
                    return; // 带外但展开未稳（动画中）：忽略
                }
                // 点非当前曲的卡：展开 + 立即切歌
                if (action != null && hit.queueIndex != currentIndex) {
                    action.onPlaySongAt(hit.queueIndex);
                }
                expandInstance(hit);
                return;
            }
        }
        // 空白处：交给页面（临时呼出顶栏）
        if (emptyTapListener != null) emptyTapListener.onEmptyTap();
    }

    private boolean sameInstance(LatticeGeometry.Instance a, LatticeGeometry.Instance b) {
        return a.cellSlot == b.cellSlot && a.repeatX == b.repeatX && a.repeatY == b.repeatY;
    }

    // ===== 帧链 =====

    private void startFrameLoop() {
        if (frameRunning) return;
        frameRunning = true;
        lastFrameNs = 0;
        choreographer.postFrameCallback(frameCallback);
    }

    /** @return 动画是否仍在进行 */
    private boolean step(float dt) {
        boolean more = false;
        if (flinging) {
            if (scroller.computeScrollOffset()) {
                camX = scroller.getCurrX();
                camY = scroller.getCurrY();
                more = true;
            } else {
                flinging = false;
            }
        }
        if (camSpringActive) {
            float ax = (camTX - camX) * CAM_K - camVX * CAM_C;
            float ay = (camTY - camY) * CAM_K - camVY * CAM_C;
            camVX += ax * dt;
            camVY += ay * dt;
            camX += camVX * dt;
            camY += camVY * dt;
            if (Math.abs(camTX - camX) < 0.5f && Math.abs(camTY - camY) < 0.5f
                    && Math.abs(camVX) < 2f && Math.abs(camVY) < 2f) {
                camX = camTX;
                camY = camTY;
                camSpringActive = false;
            } else {
                more = true;
            }
        }
        if (expActive) {
            float a = (expTarget - expandProgress) * EXP_K - expV * EXP_C;
            expV += a * dt;
            expandProgress += expV * dt;
            if (Math.abs(expTarget - expandProgress) < 0.002f && Math.abs(expV) < 0.02f) {
                expandProgress = expTarget;
                expActive = false;
            } else {
                more = true;
            }
        }
        return more;
    }

    // ===== 尺寸 =====

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w <= 0 || h <= 0) return;
        // FOLIA 方案 A：世界几何恒定，相机缩放按视口宽度三档
        boolean scaleChanged = camScale != scaleOf(w);
        camScale = scaleOf(w);
        gap = GAP_PX;
        cellSize = UNIT;
        if (!playlist.isEmpty()) {
            cell = LatticeGeometry.cellOf(playlist.size(), cellSize, gap);
        }
        buildVignette(w, h);
        scrimTop = Float.MIN_VALUE;
        // 尺寸/旋转/scale 档位变化：当前曲重定位（相机瞬移不飞行）
        if (entered && currentIndex >= 0 && !playlist.isEmpty()) {
            centerOnCurrentInstant();
        } else if (scaleChanged) {
            invalidate();
        }
    }

    private void buildVignette(int w, int h) {
        vignette = new RadialGradient(w / 2f, h / 2f, Math.max(w, h) * 0.75f,
                new int[]{0x00000000, 0x00000000, 0x5C000000},
                new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP);
    }

    // ===== 绘制 =====

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        canvas.drawColor(night ? BG_NIGHT : BG_DAY);

        if (cell == null || playlist.isEmpty()) {
            drawEmptyHint(canvas, w, h);
            return;
        }

        // 视口裁剪收集 + 展开实例保活（FOLIA drawn 逻辑）。
        // 世界视口 = 屏幕视口反推：(0..w - cam)/scale
        float worldLeft = screenToWorldX(0), worldTop = screenToWorldY(0);
        float worldRight = screenToWorldX(w), worldBottom = screenToWorldY(h);
        float overscan = Math.max(w, h) / camScale * OVERSCAN_FACTOR; // 世界单位
        LatticeGeometry.collectNear(cell, playlist.size(),
                worldLeft, worldTop, worldRight, worldBottom,
                overscan, cellSize, gap, drawn);
        if (expanded != null && !containsInstance(drawn, expanded) && drawn.size() < MAX_DRAWN) {
            drawn.add(expanded);
        }
        int n = Math.min(drawn.size(), MAX_DRAWN);

        // 每卡矩形（展开块内插值）
        for (int i = 0; i < n; i++) {
            LatticeGeometry.Instance in = drawn.get(i);
            if (expanded != null && sameBlock(in, expanded)) {
                float t = expandProgress;
                int s = in.blockSlot;
                drawnRects[i * 4] = lerp(baseRects[s * 4], grownRects[s * 4], t);
                drawnRects[i * 4 + 1] = lerp(baseRects[s * 4 + 1], grownRects[s * 4 + 1], t);
                drawnRects[i * 4 + 2] = lerp(baseRects[s * 4 + 2], grownRects[s * 4 + 2], t);
                drawnRects[i * 4 + 3] = lerp(baseRects[s * 4 + 3], grownRects[s * 4 + 3], t);
            } else {
                drawnRects[i * 4] = in.x;
                drawnRects[i * 4 + 1] = in.y;
                drawnRects[i * 4 + 2] = in.width;
                drawnRects[i * 4 + 3] = in.height;
            }
        }

        canvas.save();
        canvas.translate(camX, camY);
        canvas.scale(camScale, camScale);

        // 渲染剔除：世界坐标矩形 vs 世界坐标视口（屏幕反推）
        float vl = worldLeft - overscan, vt = worldTop - overscan;
        float vr = worldRight + overscan, vb = worldBottom + overscan;
        for (int i = 0; i < n; i++) {
            LatticeGeometry.Instance in = drawn.get(i);
            float x = drawnRects[i * 4], y = drawnRects[i * 4 + 1];
            float cw = drawnRects[i * 4 + 2], ch = drawnRects[i * 4 + 3];
            if (x + cw <= vl || x >= vr) continue;
            if (y + ch <= vt || y >= vb) continue;

            boolean isExpandedCard = expanded != null && sameInstance(in, expanded);
            Song song = playlist.get(in.queueIndex);

            // 封面双档位：展开卡大图，小卡标准档
            Bitmap bitmap = null;
            if (isExpandedCard) {
                Bitmap big = coverCache.get(song, LatticeCoverCache.STEP_EXPANDED);
                if (big == null) bitmap = coverCache.get(song, LatticeCoverCache.STEP_CARD);
                else bitmap = big;
            } else {
                bitmap = coverCache.get(song, LatticeCoverCache.STEP_CARD);
            }

            if (bitmap != null && !bitmap.isRecycled()) {
                fillSrcRect(srcRect, bitmap, cw, ch);
                dstRect.set(x, y, x + cw, y + ch);
                paint.setAlpha(255);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawBitmap(bitmap, srcRect, dstRect, paint);
            } else {
                paint.setColor(placeholderColor(in.queueIndex));
                canvas.drawRect(x, y, x + cw, y + ch, paint);
            }

            // 非展开卡压暗（FOLIA poster tint，夜更深）
            if (!isExpandedCard) {
                paint.setColor(night ? 0x5E000000 : 0x42000000);
                canvas.drawRect(x, y, x + cw, y + ch, paint);
            }

            // 当前曲副本：LED 青细描边（展开完成后才标，避免动画期杂线）
            if (!isExpandedCard && in.queueIndex == currentIndex && expandProgress > 0.92f) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(2f, cellSize * 0.022f));
                paint.setColor(ACCENT);
                canvas.drawRect(x, y, x + cw, y + ch, paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setStrokeWidth(0);
            }

            // 序号徽章（仅单格卡）
            if (!isExpandedCard && Math.min(cw, ch) > cellSize * 0.55f) {
                drawBadge(canvas, in.queueIndex, x, y);
            }
        }

        // 展开卡凸出感：浮起柔影落在相邻卡上 + 顶棱微高光（画在所有卡之后，浮在墙上语义）
        if (expanded != null && expandProgress > 0.02f) {
            drawLiftShadow(canvas, expandProgress);
        }

        // 展开卡文字
        if (expanded != null && expandProgress > 0.55f && expandedQueueIndex < playlist.size()) {
            drawExpandedText(canvas, expandProgress);
        }

        canvas.restore();

        // 边缘暗角（屏幕坐标系）
        if (vignette != null) {
            paint.setShader(vignette);
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
        }
    }

    /** 展开卡文案与传输带：scrim + 传输三键 + 频谱带计算 + 歌名歌手（带上层）+ 进度线 */
    private void drawExpandedText(Canvas canvas, float t) {
        int idx = indexOfDrawn(expanded);
        if (idx < 0) return;
        float x = drawnRects[idx * 4], y = drawnRects[idx * 4 + 1];
        float cw = drawnRects[idx * 4 + 2], ch = drawnRects[idx * 4 + 3];
        float alpha = Math.min(1f, (t - 0.55f) / 0.35f);
        // scrim（缓存：只在几何变化时重建，onDraw 零分配）
        float top = y + ch * 0.66f, bottom = y + ch;
        if (scrimShader == null || top != scrimTop || bottom != scrimBottom) {
            scrimShader = new LinearGradient(0, top, 0, bottom,
                    0x00000000, 0xB3000000, Shader.TileMode.CLAMP);
            scrimTop = top;
            scrimBottom = bottom;
        }
        paint.setShader(scrimShader);
        canvas.drawRect(x, top, x + cw, bottom, paint);
        paint.setShader(null);

        // 中央传输三键（展开稳后淡入）
        if (t > 0.9f) {
            drawTransport(canvas, x, y, cw, ch, alpha);
        }

        // 展开卡矩形/频谱带计算：真 SpectrumView 与文字层（页面侧 View，均高于墙 canvas）
        // 需要屏幕坐标，变化时回调页面同步
        updateSpectrumBand(t, x, y, cw, ch);

        // 卡顶 LED 进度线：颜色与频谱一致（频谱 tint 主色；无 tint 回落频谱竖条默认色）
        if (t > 0.9f) {
            float lineH = Math.max(3f, ch * 0.008f);
            float lineBottom = y + lineH;
            paint.setColor(0x33FFFFFF);
            canvas.drawRect(x, y, x + cw, lineBottom, paint);
            if (progressDur > 0) {
                float frac = Math.min(1f, progressPos / (float) progressDur);
                paint.setColor(progressLineTint());
                canvas.drawRect(x, y, x + cw * frac, lineBottom, paint);
            }
        }
    }

    /** 进度线颜色：频谱 tint 主色（双色跟随封面）；无 tint 回落频谱默认色（夜首青，昼金色系） */
    private int progressLineTint() {
        if (tintTop != 0) return tintTop;
        // 默认态与 SpectrumView 竖条静态色同源：昼=金色渐变近低端、夜=首色
        return night ? 0xFF00E5CC : 0xFFF2C14E;
    }

    /**
     * 展开卡凸出感三件套：全向递减柔影（整体下移模拟顶光）+ 卡面对角渐变（左下50%黑→右上透明）+ 顶棱内微高光，强度随展开进度渐进。
     * 纯矩形分层环（EVEN_ODD）+ 线性渐变，无 shadowLayer，硬件加速安全；零分配。
     */
    private void drawLiftShadow(Canvas canvas, float t) {
        int idx = indexOfDrawn(expanded);
        if (idx < 0) return;
        float x = drawnRects[idx * 4], y = drawnRects[idx * 4 + 1];
        float cw = drawnRects[idx * 4 + 2], ch = drawnRects[idx * 4 + 3];
        float maxPad = ch * 0.02f * t;   // 最外扩散（约卡高 2%）
        float drop = ch * 0.006f * t;    // 整体下移（顶光方向，随扩散等比）
        float baseA = night ? 0.16f : 0.11f; // 夜间更浓
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
        liftPath.setFillType(Path.FillType.EVEN_ODD);
        // 从外到内叠层，透明度平方递增：近浓远淡柔影
        for (int i = 0; i < LIFT_LAYERS; i++) {
            float fr = 1f - i / (float) LIFT_LAYERS;   // 外 1 → 内 1/6
            float pad = maxPad * fr + 1.5f;
            liftPath.reset();
            liftPath.addRect(x - pad, y - pad + drop, x + cw + pad, y + ch + pad + drop, Path.Direction.CCW);
            liftPath.addRect(x, y, x + cw, y + ch, Path.Direction.CCW);
            paint.setColor(Math.round(baseA * fr * fr * 255f) << 24);
            canvas.drawPath(liftPath, paint);
        }
        // 卡面对角渐变：左下角 50% 黑 → 右上角透明（立体感；随展开淡入）
        if (diagShader == null || x != diagRectX || y != diagRectY
                || cw != diagRectW || ch != diagRectH) {
            diagRectX = x; diagRectY = y; diagRectW = cw; diagRectH = ch;
            diagShader = new LinearGradient(x, y + ch, x + cw, y,
                    0xB3000000, 0x00000000, Shader.TileMode.CLAMP);
        }
        paint.setShader(diagShader);
        paint.setAlpha(Math.round(255f * t));
        canvas.drawRect(x, y, x + cw, y + ch, paint);
        paint.setShader(null);
        paint.setAlpha(255);
        // 顶棱内高光：光从上方打在卡片棱上，与柔影呼应
        float hh = Math.max(1.2f, ch * 0.0025f);
        paint.setColor((Math.round(0x17 * t) << 24) | 0xFFFFFF);
        canvas.drawRect(x, y, x + cw, y + hh, paint);
    }

    /** 展开进度（1=展开到位；文字层淡入用） */
    public float getExpandProgress() { return expanded != null ? expandProgress : 0f; }

    /** 展开卡对应队列索引（-1=无） */
    public int getExpandedQueueIndex() { return expandedQueueIndex; }

    /** 展开卡矩形（屏幕坐标 l,t,r,b，复用缓存；无展开返回 null。页面立即消费，勿持有） */
    @Nullable
    public float[] getExpandedCardRect() {
        if (expanded == null) return null;
        int idx = indexOfDrawn(expanded);
        if (idx < 0) return null;
        if (cardRectCache == null) cardRectCache = new float[4];
        cardRectCache[0] = worldToScreenX(drawnRects[idx * 4]);
        cardRectCache[1] = worldToScreenY(drawnRects[idx * 4 + 1]);
        cardRectCache[2] = worldToScreenX(drawnRects[idx * 4] + drawnRects[idx * 4 + 2]);
        cardRectCache[3] = worldToScreenY(drawnRects[idx * 4 + 1] + drawnRects[idx * 4 + 3]);
        return cardRectCache;
    }

    private float[] cardRectCache;

    /**
     * 展开卡频谱带：区域=传输带下缘至进度线上方，宽度=整卡宽。
     * 频谱与歌名文字都是页面侧独立 View（频谱 z=15、文字 z=20，均高于墙 canvas，
     * 墙内后绘压不过 View 层序），墙只算矩形：卡矩形或带矩形变化时回调页面同步。
     */
    private void updateSpectrumBand(float t, float x, float y, float cw, float ch) {
        // 卡矩形（屏幕坐标）变化检测：文字层随卡走（环类模式带恒 null 但文字仍要跟）
        float left = worldToScreenX(x), topS = worldToScreenY(y);
        float right = worldToScreenX(x + cw), bottomS = worldToScreenY(y + ch);
        boolean cardChanged = lastCardRect == null
                || Math.abs(lastCardRect[0] - left) > 0.5f || Math.abs(lastCardRect[1] - topS) > 0.5f
                || Math.abs(lastCardRect[2] - right) > 0.5f || Math.abs(lastCardRect[3] - bottomS) > 0.5f;
        if (cardChanged) {
            if (lastCardRect == null) lastCardRect = new float[4];
            lastCardRect[0] = left; lastCardRect[1] = topS;
            lastCardRect[2] = right; lastCardRect[3] = bottomS;
        }

        float[] next = null;
        if (t > 0.9f
                && !com.jingxin.jingxinmusic.view.SpectrumView.isOverlayStyle(
                        com.jingxin.jingxinmusic.view.SpectrumView.getCurrentStyleStatic())) {
            if (bandScratch == null) bandScratch = new float[4];
            // 频谱贴卡底无缝：底=卡底，顶=底-带高（12%卡高，世界系恒定随scale缩放）
            float bBottomW = y + ch;
            float bandH = ch * 0.12f;
            float bTopW = bBottomW - bandH;
            float bTop = worldToScreenY(bTopW);
            float bBottom = worldToScreenY(bBottomW);
            bandScratch[0] = left; bandScratch[1] = bTop;
            bandScratch[2] = right; bandScratch[3] = bBottom;
            next = bandScratch;
        }
        boolean bandChanged = !sameRect(next, spectrumBand);
        if (cardChanged || bandChanged) {
            spectrumBand = next == null ? null : next.clone();
            if (spectrumBandUpdater != null) spectrumBandUpdater.run();
        }
    }

    private float[] lastCardRect;
    private float[] bandScratch;

    private static boolean sameRect(float[] a, float[] b) {
        if (a == null || b == null) return a == b;
        return Math.abs(a[0] - b[0]) < 0.5f && Math.abs(a[1] - b[1]) < 0.5f
                && Math.abs(a[2] - b[2]) < 0.5f && Math.abs(a[3] - b[3]) < 0.5f;
    }

    /**
     * 取频谱带矩形（屏幕坐标 l,t,r,b）；无带（未展开/环类/无数据）返回 null。
     */
    @Nullable
    public float[] getSpectrumBand() {
        return spectrumBand;
    }

    /** 中央传输三键：上/播放暂停/下（纯路径绘制，暂停时中央换两竖条） */
    private void drawTransport(Canvas canvas, float x, float y, float cw, float ch, float alpha) {
        float cy = y + ch * 0.5f;
        float g = Math.min(cw, ch) * 0.065f;    // 图形高
        float gw = g * 0.95f;                   // 三角宽
        float cx1 = x + cw / 6f;                // 左键中心（第一等分中点）
        float cx2 = x + cw / 2f;                // 中键中心
        float cx3 = x + cw * 5f / 6f;           // 右键中心
        // 图形=点按动作（通例）：播放中显示暂停键，暂停中显示播放键
        boolean showPause = playing; // 播放中 → ⏸；暂停 → ▶
        // 三键恒定白，透明度 30%（淡入插值后峰值 77/255）
        glyphPaint.setColor(Color.argb((int) (alpha * 77), 255, 255, 255));

        // 上一曲：左指三角 + 左侧竖条
        glyphPath.rewind();
        glyphPath.moveTo(cx1 + gw / 2f, cy - g / 2f);
        glyphPath.lineTo(cx1 - gw / 2f, cy);
        glyphPath.lineTo(cx1 + gw / 2f, cy + g / 2f);
        glyphPath.close();
        canvas.drawPath(glyphPath, glyphPaint);
        canvas.drawRect(cx1 - gw * 0.85f - g * 0.12f, cy - g / 2f,
                cx1 - gw * 0.85f + g * 0.06f, cy + g / 2f, glyphPaint);

        // 中键：播放中=暂停双竖条（下一动作暂停）/ 暂停中=播放三角（下一动作播放）
        if (showPause) {
            canvas.drawRect(cx2 - g * 0.34f, cy - g * 0.6f, cx2 - g * 0.06f, cy + g * 0.6f, glyphPaint);
            canvas.drawRect(cx2 + g * 0.06f, cy - g * 0.6f, cx2 + g * 0.34f, cy + g * 0.6f, glyphPaint);
        } else {
            glyphPath.rewind();
            glyphPath.moveTo(cx2 - gw / 3f, cy - g * 0.6f);
            glyphPath.lineTo(cx2 + gw * 0.7f, cy);
            glyphPath.lineTo(cx2 - gw / 3f, cy + g * 0.6f);
            glyphPath.close();
            canvas.drawPath(glyphPath, glyphPaint);
        }

        // 下一曲：右指三角 + 右侧竖条
        glyphPath.rewind();
        glyphPath.moveTo(cx3 - gw / 2f, cy - g / 2f);
        glyphPath.lineTo(cx3 + gw / 2f, cy);
        glyphPath.lineTo(cx3 - gw / 2f, cy + g / 2f);
        glyphPath.close();
        canvas.drawPath(glyphPath, glyphPaint);
        canvas.drawRect(cx3 + gw * 0.85f - g * 0.06f, cy - g / 2f,
                cx3 + gw * 0.85f + g * 0.12f, cy + g / 2f, glyphPaint);
    }

    private int indexOfDrawn(@Nullable LatticeGeometry.Instance target) {
        if (target == null) return -1;
        int n = Math.min(drawn.size(), MAX_DRAWN);
        for (int i = 0; i < n; i++) {
            if (sameInstance(drawn.get(i), target)) return i;
        }
        return -1;
    }

    private void drawBadge(Canvas canvas, int queueIndex, float x, float y) {
        String text = badgeOf(queueIndex);
        badgePaint.setColor(0xFFFFFFFF);
        badgePaint.setTextSize(Math.max(11f, cellSize * 0.16f));
        badgePaint.getTextBounds(text, 0, text.length(), badgeRect);
        float pad = cellSize * 0.05f;
        paint.setColor(0x8C000000);
        canvas.drawRect(x, y, x + badgeRect.width() + pad * 2, y + badgeRect.height() + pad * 2, paint);
        canvas.drawText(text, x + pad, y + pad + badgeRect.height(), badgePaint);
    }

    private String badgeOf(int queueIndex) {
        long key = queueIndex + 1;
        String s = badgeStrings.get(key);
        if (s == null) {
            s = String.valueOf(key);
            badgeStrings.put(key, s);
        }
        return s;
    }

    private void drawEmptyHint(Canvas canvas, int w, int h) {
        badgePaint.setColor(0x80FFFFFF);
        badgePaint.setTextSize(Math.max(14f, h * 0.03f));
        String hint = "播放队列为空";
        canvas.drawText(hint, (w - badgePaint.measureText(hint)) / 2f, h / 2f, badgePaint);
    }

    private int placeholderColor(int queueIndex) {
        float[] hsv = {(queueIndex * 47) % 360, 0.32f, night ? 0.14f : 0.22f};
        return Color.HSVToColor(hsv);
    }

    /** 中心裁剪：位图按目标长宽比取最大内接矩形（左上对齐绘制版） */
    private void fillSrcRect(Rect src, Bitmap bitmap, float dw, float dh) {
        int bw = bitmap.getWidth(), bh = bitmap.getHeight();
        float dstRatio = dw / dh, bmpRatio = (float) bw / bh;
        if (bmpRatio > dstRatio) {
            int cut = Math.round(bw - bh * dstRatio);
            src.set(cut / 2, 0, bw - cut / 2, bh);
        } else {
            int cut = Math.round(bh - bw / dstRatio);
            src.set(0, cut / 2, bw, bh - cut / 2);
        }
    }

    private boolean sameBlock(LatticeGeometry.Instance a, LatticeGeometry.Instance b) {
        if (cell == null) return false;
        return LatticeGeometry.columnOf(a, cell) == LatticeGeometry.columnOf(b, cell)
                && LatticeGeometry.rowOf(a, cell) == LatticeGeometry.rowOf(b, cell);
    }

    private boolean containsInstance(ArrayList<LatticeGeometry.Instance> list, LatticeGeometry.Instance target) {
        for (LatticeGeometry.Instance in : list) {
            if (sameInstance(in, target)) return true;
        }
        return false;
    }

    // ===== 触摸 =====

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean handled = detector.onTouchEvent(event);
        return handled || super.onTouchEvent(event);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        frameRunning = false;
        choreographer.removeFrameCallback(frameCallback);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
