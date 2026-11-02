package com.jingxin.jingxinmusic.view.collage;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.LongSparseArray;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.OverScroller;

import androidx.annotation.Nullable;

import com.jingxin.jingxinmusic.model.Song;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * 拼贴墙 View（自研引擎：像素空间织块墙）
 *
 * 结构（与 {@link CollageLayout} 配合）：
 * - 像素空间直接布局：卡片坐标即内容像素，视口 = 内容平移 scrollX/scrollY；
 *   单元尺寸按视口宽度连续推导（无档位跳变）
 * - 织块平铺：10×10 织块无限延展，视图只收集可见织块，成本与队列长度无关
 * - 展开：被点卡锚定放大，同织块其余卡沿让位模式重排（单进度插值），
 *   相机弹簧飞至展开卡中心
 * - 动效运行期才挂 Choreographer，静止即停帧
 * - 触摸：拖动平移 / 惯性 / 点击展开；点已展开卡=播放语义（当前曲暂停续播，他曲切歌）
 *
 * 性能红线（车机）：
 * - onDraw 零分配（Paint/Rect/卡池/shader 全预建复用；徽章字符串按队列缓存）
 * - 不用 setShadowLayer 辉光（低配 GPU 崩溃病灶），LED 走细描边
 */
public class CollageWallView extends View {

    /** 卡片动作桥（页面实现，转 Fragment Actions） */
    public interface OnTileAction {
        void onPlaySongAt(int queueIndex);
        void onTogglePlayPause();
        void onPlayPrevious();
        void onPlayNext();
    }

    /** 空白处点击回调（顶部 chrome 呼出备选路径） */
    public interface OnEmptyTapListener {
        void onEmptyTap();
    }

    /** 展开大卡封面区点击（传输带外）：进入歌词动效页 */
    public interface OnExpandedCoverTapListener {
        void onExpandedCoverTap();
    }

    /** 长按呼出顶栏回调（任何位置含封面上，不依赖空白） */
    public interface OnLongPressListener {
        void onLongPress(float sx, float sy);
    }

    /** 本次按下的长按是否已触发（抬手吞掉 tap，防"长按呼出→抬手误点卡切歌"） */
    private boolean longPressFired = false;

    // ===== 度量（像素空间连续推导）=====
    /** 单元尺寸：视口宽度连续映射 + 钳制；跨设备过渡无跳变 */
    private static final float UNIT_MIN = 64f;
    private static final float UNIT_MAX = 104f;
    private static final float UNIT_PER_WIDTH = 1f / 13.5f;
    private float unitPx = 80f;
    private float gapPx = 5.6f;

    private float pitch() { return unitPx + gapPx; }
    private float regionPx() { return CollageLayout.REGION * pitch(); }

    /** 绘制实例上限（车机降载） */
    private static final int MAX_DRAWN = 192;

    // ===== 数据 =====
    private List<Song> playlist = new ArrayList<>();
    private int currentIndex = -1;
    private final CollageLayout layout = new CollageLayout();
    private final CollageCoverCache coverCache;
    private final LongSparseArray<String> badgeStrings = new LongSparseArray<>();

    // ===== 滚动（内容像素）=====
    private float scrollX = 0f, scrollY = 0f;

    // ===== 展开状态 =====
    private boolean hasExpanded = false;
    private int expandedRegionX, expandedRegionY, expandedLeaf, expandedQueueIndex = -1;
    private float expandProgress = 0f;

    // ===== 弹簧（相机飞行 + 展开进度；自定参数）=====
    private static final float CAM_K = 190f, CAM_C = 27f;
    private static final float EXP_K = 220f, EXP_C = 30f;
    private float camVX, camVY, camTX, camTY;
    private boolean camSpringActive = false;
    private float expV = 0f, expTarget = 0f;
    private boolean expActive = false;

    // ===== 惯性 =====
    private final OverScroller scroller;
    private boolean flinging = false;

    // ===== 帧链 =====
    // ===== 呼吸动效（当前曲/上一曲/下一曲副本在视野内时上下浮动微缩，科幻悬浮感）=====
    private static final float BREATH_PERIOD_MS = 2400f;
    private static final float BREATH_LIFT_UNIT = 0.022f; // 上浮幅度（×unit）
    private static final float BREATH_SHRINK = 0.015f;    // 峰值收缩幅度
    /** 三角色队列索引（切歌时重算）；相位按角色错开 → 波浪传递感 */
    private int roleCurrent = -1, rolePrev = -1, roleNext = -1;
    /** 本帧是否有呼吸卡可见（帧循环维持依据）与呼吸卡包围盒（内容坐标，脏区重绘用） */
    private boolean breathVisible = false;
    private final float[] breathDirty = new float[4];

    /** 令帧链持续运转的动画 */
    private boolean fullAnimActive() {
        return flinging || camSpringActive || expActive;
    }

    private final Choreographer choreographer = Choreographer.getInstance();
    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override public void doFrame(long timeNs) {
            if (!frameRunning) return;
            float dt = lastFrameNs == 0 ? 0.016f : Math.min(0.033f, (timeNs - lastFrameNs) / 1e9f);
            lastFrameNs = timeNs;
            boolean more = step(dt);
            // 呼吸卡在视野内：帧循环持续；仅呼吸时只重绘呼吸卡脏区（车机降载）
            if (breathVisible) more = true;
            if (fullAnimActive()) {
                invalidate();
            } else if (breathVisible) {
                invalidate((int) Math.floor(breathDirty[0] - scrollX) - 5,
                        (int) Math.floor(breathDirty[1] - scrollY) - 5,
                        (int) Math.ceil(breathDirty[2] - scrollX) + 5,
                        (int) Math.ceil(breathDirty[3] - scrollY) + 5);
            }
            if (more) choreographer.postFrameCallback(this);
            else frameRunning = false;
        }
    };
    private boolean frameRunning = false;
    private long lastFrameNs = 0;

    // ===== 手势 =====
    private final GestureDetector detector;

    // ===== 绘制池（零分配）=====
    /** 卡实例：织块坐标 + 叶位 + 队列索引 + 内容像素矩形 */
    private static final class Tile {
        int regionX, regionY, leaf, queueIndex;
        float x, y, w, h;
    }
    private final ArrayList<Tile> tilePool = new ArrayList<>(MAX_DRAWN);
    private int drawnCount = 0;
    private final float[] drawnRects = new float[MAX_DRAWN * 4];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint badgePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Rect srcRect = new Rect();
    private final RectF dstRect = new RectF();
    private final Rect badgeRect = new Rect();
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

    // ===== 展开卡凸出感（浮起柔影+顶棱高光；随展开进度渐进）=====
    private static final int LIFT_LAYERS = 6;       // 柔影同心环层数
    private final Path liftPath = new Path();
    /** 卡面对角渐变（左下 70% 黑 → 右上透明）缓存：矩形变化才重建，onDraw 零分配 */
    private LinearGradient diagShader;
    private float diagRectX, diagRectY, diagRectW, diagRectH;

    // 频谱动态跟随色（进度线与频谱同色；未注入时进度线用频谱竖条默认色计算）
    private int tintTop = 0, tintBottom = 0;

    /** 展开卡频谱带矩形（屏幕坐标，页面把真 SpectrumView 实例盖过来；环类样式时为 null） */
    @Nullable private float[] spectrumBand;
    /** 页面注入：频谱带变化回调（每帧展开/拖动后带位置变了要同步） */
    @Nullable private Runnable spectrumBandUpdater;

    public CollageWallView(Context context) { this(context, null); }

    public CollageWallView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CollageWallView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        scroller = new OverScroller(context);
        coverCache = new CollageCoverCache(context, (song, step, bitmap) -> postInvalidateOnAnimation());
        badgePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        detector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { longPressFired = false; return true; }

            @Override
            public void onLongPress(MotionEvent e) {
                // 长按呼出顶栏：全屏任意位置都算（卡间隙窄点不中），
                // 手指按住不移动不会触发 onScroll，拖动浏览不受影响
                longPressFired = true;
                if (longPressListener != null) {
                    longPressListener.onLongPress(e.getX(), e.getY());
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                }
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                cancelMotions();
                // onScroll 的 dx/dy 与手指位移反向（= 上次焦点 − 当前焦点）；
                // 本墙约定 屏幕 = 内容 − scroll，跟手即 scroll += distance
                scrollX += dx;
                scrollY += dy;
                invalidate();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                cancelMotions();
                scroller.fling(Math.round(scrollX), Math.round(scrollY),
                        (int) -vx, (int) -vy,
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
        if (hasExpanded && expandProgress > 0.5f) invalidate();
    }

    /** 频谱样式同步（页面把真值 SpectrumView 实例盖到频谱带，墙只算带矩形） */
    public void setSpectrumStyle(int style) {
        // 环类不画（页面也不会盖）：清带；否则展开时算带
        if (com.jingxin.jingxinmusic.view.SpectrumView.isOverlayStyle(style)) {
            spectrumBand = null;
        }
        invalidate();
    }

    /** 频谱动态跟随色（封面双色，页面注入；进度线与频谱同色用。0=回落默认） */
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
            hasExpanded = false;
            expandedQueueIndex = -1;
            layout.configure(0);
            invalidate();
            return;
        }
        if (countChanged) {
            layout.configure(playlist.size());
        }
        computeRoles();
        if (!entered) {
            // 首次进入：相机瞬移居中当前曲（无飞行，防"漂移"感），让位动画播放
            centerOnCurrentInstant();
            entered = true;
        } else if (indexChanged || !hasExpanded) {
            // 切歌：新当前曲飞入并展开
            focusCurrentAnimated();
        } else if (expandedQueueIndex < playlist.size()) {
            // 同曲数据刷新：展开位可能挪位（队列长度变化重排），保持对位
            int[] near = layout.nearestInstance(expandedQueueIndex,
                    scrollX, scrollY, regionPx(), regionPx(), getWidth(), getHeight());
            if (near != null && (near[0] != expandedRegionX || near[1] != expandedRegionY
                    || near[2] != expandedLeaf)) {
                expandedRegionX = near[0];
                expandedRegionY = near[1];
                expandedLeaf = near[2];
            }
        }
        invalidate();
    }

    /** 呼吸三角色：当前曲/上一曲/下一曲（环绕；单两首时退化） */
    private void computeRoles() {
        int n = playlist.size();
        roleCurrent = currentIndex;
        rolePrev = (n > 1 && currentIndex >= 0) ? (((currentIndex - 1) % n) + n) % n : -1;
        roleNext = (n > 2 && currentIndex >= 0) ? (currentIndex + 1) % n : -1;
    }

    // ===== 定位 =====

    /** 队列条目距视口中心最近的实例（织块坐标 + 叶位） */
    @Nullable
    private int[] nearestToCenter(int queueIndex) {
        if (playlist.isEmpty() || queueIndex < 0 || queueIndex >= playlist.size()) return null;
        return layout.nearestInstance(queueIndex, scrollX, scrollY,
                regionPx(), regionPx(), getWidth(), getHeight());
    }

    private void centerOnCurrentInstant() {
        int[] near = nearestToCenter(currentIndex);
        if (near == null) return;
        expandedRegionX = near[0];
        expandedRegionY = near[1];
        expandedLeaf = near[2];
        expandedQueueIndex = currentIndex;
        hasExpanded = true;
        expandProgress = 0f;
        expTarget = 1f;
        expV = 0f;
        expActive = true;
        scrollX = anchorCenterScreenX() - getWidth() / 2f;
        scrollY = anchorCenterScreenY() - getHeight() / 2f;
        cancelMotions();
        startFrameLoop();
    }

    private void focusCurrentAnimated() {
        int[] near = nearestToCenter(currentIndex);
        if (near == null) return;
        expandAt(near[0], near[1], near[2], currentIndex);
    }

    /** 展开锚中心（内容像素） */
    private float anchorCenterScreenX() {
        int[] grow = layout.reflowAt(expandedRegionX, expandedRegionY, expandedLeaf);
        return cellCenterPxX(expandedRegionX, grow[expandedLeaf * 4], grow[expandedLeaf * 4 + 2]);
    }

    private float anchorCenterScreenY() {
        int[] grow = layout.reflowAt(expandedRegionX, expandedRegionY, expandedLeaf);
        return cellCenterPxY(expandedRegionY, grow[expandedLeaf * 4 + 1], grow[expandedLeaf * 4 + 3]);
    }

    /** 单元格坐标 → 内容像素中心 */
    private float cellCenterPxX(int regionX, int cellX, int cellW) {
        return (regionX * CollageLayout.REGION + cellX + cellW / 2f) * pitch();
    }

    private float cellCenterPxY(int regionY, int cellY, int cellH) {
        return (regionY * CollageLayout.REGION + cellY + cellH / 2f) * pitch();
    }

    /** 展开某实例：让位插值 + 相机弹簧飞至展开卡中心 */
    private void expandAt(int regionX, int regionY, int leaf, int queueIndex) {
        expandedRegionX = regionX;
        expandedRegionY = regionY;
        expandedLeaf = leaf;
        expandedQueueIndex = queueIndex;
        hasExpanded = true;
        expTarget = 1f;
        if (expandProgress < 0.02f) expV = 0f;
        expActive = true;
        camTX = anchorCenterScreenX() - getWidth() / 2f;
        camTY = anchorCenterScreenY() - getHeight() / 2f;
        camVX = camVY = 0;
        camSpringActive = true;
        cancelFling();
        startFrameLoop();
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
        float cx = sx + scrollX, cy = sy + scrollY;
        for (int i = drawnCount - 1; i >= 0; i--) {
            float x = drawnRects[i * 4], y = drawnRects[i * 4 + 1];
            float w = drawnRects[i * 4 + 2], h = drawnRects[i * 4 + 3];
            if (cx >= x && cx <= x + w && cy >= y && cy <= y + h) {
                int hitRegionX = drawnRegionX(i), hitRegionY = drawnRegionY(i), hitLeaf = drawnLeaf(i);
                boolean hitExpanded = hasExpanded && hitRegionX == expandedRegionX
                        && hitRegionY == expandedRegionY && hitLeaf == expandedLeaf;
                if (hitExpanded) {
                    // 传输带（展开稳后生效）：三等分 ·上/播放暂停/下
                    if (expandProgress > 0.9f && action != null && cy >= y + h * 0.38f && cy <= y + h * 0.62f) {
                        if (cx < x + w / 3f) { action.onPlayPrevious(); return; }
                        if (cx > x + w * 2f / 3f) { action.onPlayNext(); return; }
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
                int hitQueue = drawnQueue(i);
                // 点非当前曲的卡：展开 + 立即切歌
                if (action != null && hitQueue != currentIndex) {
                    action.onPlaySongAt(hitQueue);
                }
                expandAt(hitRegionX, hitRegionY, hitLeaf, hitQueue);
                return;
            }
        }
        // 空白处：交给页面（临时呼出顶栏）
        if (emptyTapListener != null) emptyTapListener.onEmptyTap();
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
                scrollX = scroller.getCurrX();
                scrollY = scroller.getCurrY();
                more = true;
            } else {
                flinging = false;
            }
        }
        if (camSpringActive) {
            float ax = (camTX - scrollX) * CAM_K - camVX * CAM_C;
            float ay = (camTY - scrollY) * CAM_K - camVY * CAM_C;
            camVX += ax * dt;
            camVY += ay * dt;
            scrollX += camVX * dt;
            scrollY += camVY * dt;
            if (Math.abs(camTX - scrollX) < 0.5f && Math.abs(camTY - scrollY) < 0.5f
                    && Math.abs(camVX) < 2f && Math.abs(camVY) < 2f) {
                scrollX = camTX;
                scrollY = camTY;
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
        unitPx = Math.max(UNIT_MIN, Math.min(UNIT_MAX, w * UNIT_PER_WIDTH));
        gapPx = unitPx * 0.07f;
        buildVignette(w, h);
        scrimTop = Float.MIN_VALUE;
        // 尺寸/旋转变化：当前曲重定位（相机瞬移不飞行）
        if (entered && currentIndex >= 0 && !playlist.isEmpty()) {
            centerOnCurrentInstant();
        } else {
            invalidate();
        }
    }

    private void buildVignette(int w, int h) {
        vignette = new RadialGradient(w / 2f, h / 2f, Math.max(w, h) * 0.75f,
                new int[]{0x00000000, 0x00000000, 0x5C000000},
                new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP);
    }

    // ===== 卡池 =====

    private Tile obtainTile(int poolIndex) {
        while (tilePool.size() <= poolIndex) {
            tilePool.add(new Tile());
        }
        return tilePool.get(poolIndex);
    }

    private int drawnRegionX(int i) { return tilePool.get(i).regionX; }
    private int drawnRegionY(int i) { return tilePool.get(i).regionY; }
    private int drawnLeaf(int i) { return tilePool.get(i).leaf; }
    private int drawnQueue(int i) { return tilePool.get(i).queueIndex; }

    // ===== 绘制 =====

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        canvas.drawColor(night ? BG_NIGHT : BG_DAY);

        if (playlist.isEmpty()) {
            drawEmptyHint(canvas, w, h);
            return;
        }

        collectVisible(w, h);
        int n = drawnCount;
        breathVisible = false;

        canvas.save();
        canvas.translate(-scrollX, -scrollY);

        for (int i = 0; i < n; i++) {
            float x = drawnRects[i * 4], y = drawnRects[i * 4 + 1];
            float cw = drawnRects[i * 4 + 2], ch = drawnRects[i * 4 + 3];
            int regionX = drawnRegionX(i), regionY = drawnRegionY(i), leaf = drawnLeaf(i);
            boolean isExpandedCard = hasExpanded && regionX == expandedRegionX
                    && regionY == expandedRegionY && leaf == expandedLeaf;
            Song song = playlist.get(drawnQueue(i));

            // 呼吸卡：当前/上一曲/下一曲副本上下浮动+微缩（错相 1/3 周期 → 波浪传递感）；
            // 展开卡本体不晃（频谱/文字层是独立 View，不同步会脱节）
            if (!isExpandedCard) {
                int q = drawnQueue(i);
                float bPhase = -1f;
                if (q == roleCurrent) bPhase = 0f;
                else if (q == rolePrev) bPhase = 1f / 3f;
                else if (q == roleNext) bPhase = 2f / 3f;
                if (bPhase >= 0f) {
                    float p = ((SystemClock.uptimeMillis() % (long) BREATH_PERIOD_MS) / BREATH_PERIOD_MS + bPhase) % 1f;
                    float s = 0.5f - 0.5f * (float) Math.cos((float) (p * Math.PI * 2.0));
                    float lift = unitPx * BREATH_LIFT_UNIT * s;
                    float k = 1f - BREATH_SHRINK * s;
                    float nw = cw * k, nh = ch * k;
                    x = x + (cw - nw) * 0.5f;
                    y = y - lift + (ch - nh) * 0.5f;
                    cw = nw;
                    ch = nh;
                    drawnRects[i * 4] = x;
                    drawnRects[i * 4 + 1] = y;
                    drawnRects[i * 4 + 2] = cw;
                    drawnRects[i * 4 + 3] = ch;
                    if (!breathVisible) {
                        breathVisible = true;
                        breathDirty[0] = x;
                        breathDirty[1] = y;
                        breathDirty[2] = x + cw;
                        breathDirty[3] = y + ch;
                    } else {
                        if (x < breathDirty[0]) breathDirty[0] = x;
                        if (y < breathDirty[1]) breathDirty[1] = y;
                        if (x + cw > breathDirty[2]) breathDirty[2] = x + cw;
                        if (y + ch > breathDirty[3]) breathDirty[3] = y + ch;
                    }
                }
            }

            // 封面双档位：展开卡大图，小卡标准档
            Bitmap bitmap = null;
            if (isExpandedCard) {
                Bitmap big = coverCache.get(song, CollageCoverCache.STEP_EXPANDED);
                if (big == null) bitmap = coverCache.get(song, CollageCoverCache.STEP_CARD);
                else bitmap = big;
            } else {
                bitmap = coverCache.get(song, CollageCoverCache.STEP_CARD);
            }

            if (bitmap != null && !bitmap.isRecycled()) {
                fillSrcRect(srcRect, bitmap, cw, ch);
                dstRect.set(x, y, x + cw, y + ch);
                paint.setAlpha(255);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawBitmap(bitmap, srcRect, dstRect, paint);
            } else {
                paint.setColor(placeholderColor(drawnQueue(i)));
                canvas.drawRect(x, y, x + cw, y + ch, paint);
            }

            // 非展开卡压暗（夜更深）
            if (!isExpandedCard) {
                paint.setColor(night ? 0x59000000 : 0x40000000);
                canvas.drawRect(x, y, x + cw, y + ch, paint);
            }

            // 当前曲副本：LED 青细描边（展开完成后才标，避免动画期杂线）
            if (!isExpandedCard && drawnQueue(i) == currentIndex && expandProgress > 0.92f) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(2f, unitPx * 0.044f));
                paint.setColor(ACCENT);
                canvas.drawRect(x, y, x + cw, y + ch, paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setStrokeWidth(0);
            }

            // 序号徽章（非展开卡）
            if (!isExpandedCard) {
                drawBadge(canvas, drawnQueue(i), x, y);
            }

            paint.setAlpha(255);
        }

        // 展开卡凸出感：浮起柔影落在相邻卡上 + 卡面对角渐变 + 顶棱微高光
        if (hasExpanded && expandProgress > 0.02f) {
            drawLiftShadow(canvas, expandProgress);
        }

        // 展开卡文字层（scrim + 传输三键 + 频谱带 + 进度线）
        if (hasExpanded && expandProgress > 0.55f && expandedQueueIndex < playlist.size()) {
            drawExpandedText(canvas, expandProgress);
        }

        canvas.restore();

        // 边缘暗角（屏幕坐标系）
        if (vignette != null) {
            paint.setShader(vignette);
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
            paint.setAlpha(255);
        }

        // 呼吸卡可见且无其他动画运转：接管帧链持续呼吸（静止后滚入呼吸卡时冷启动）
        if (breathVisible && !frameRunning && !fullAnimActive()) {
            startFrameLoop();
        }
    }

    /** 收集视口内卡实例（含 overscan 外扩 + 展开卡保活），填充 tilePool/drawnRects */
    private void collectVisible(int w, int h) {
        drawnCount = 0;
        float pad = Math.max(w, h) * 0.35f;
        float left = scrollX - pad, top = scrollY - pad;
        float right = scrollX + w + pad, bottom = scrollY + h + pad;
        float rp = regionPx();
        float pitchPx = pitch();
        int fromRX = (int) Math.floor(left / rp), toRX = (int) Math.floor((right - 1) / rp);
        int fromRY = (int) Math.floor(top / rp), toRY = (int) Math.floor((bottom - 1) / rp);
        boolean expandedCollected = false;
        for (int ry = fromRY; ry <= toRY && drawnCount < MAX_DRAWN; ry++) {
            for (int rx = fromRX; rx <= toRX && drawnCount < MAX_DRAWN; rx++) {
                boolean isExpandRegion = hasExpanded && rx == expandedRegionX && ry == expandedRegionY;
                int[] basePat = layout.patternAt(rx, ry);
                int[] growPat = isExpandRegion
                        ? layout.reflowAt(rx, ry, expandedLeaf) : null;
                float t = isExpandRegion ? expandProgress : 0f;
                for (int leaf = 0; leaf < CollageLayout.LEAVES && drawnCount < MAX_DRAWN; leaf++) {
                    float bx = (rx * CollageLayout.REGION + basePat[leaf * 4]) * pitchPx;
                    float by = (ry * CollageLayout.REGION + basePat[leaf * 4 + 1]) * pitchPx;
                    float bw = basePat[leaf * 4 + 2] * pitchPx - gapPx;
                    float bh = basePat[leaf * 4 + 3] * pitchPx - gapPx;
                    if (growPat != null) {
                        float gx = (rx * CollageLayout.REGION + growPat[leaf * 4]) * pitchPx;
                        float gy = (ry * CollageLayout.REGION + growPat[leaf * 4 + 1]) * pitchPx;
                        float gw = growPat[leaf * 4 + 2] * pitchPx - gapPx;
                        float gh = growPat[leaf * 4 + 3] * pitchPx - gapPx;
                        bx = lerp(bx, gx, t);
                        by = lerp(by, gy, t);
                        bw = lerp(bw, gw, t);
                        bh = lerp(bh, gh, t);
                    }
                    Tile tile = obtainTile(drawnCount);
                    tile.regionX = rx;
                    tile.regionY = ry;
                    tile.leaf = leaf;
                    tile.queueIndex = layout.queueIndexOf(rx, ry, leaf);
                    tile.x = bx;
                    tile.y = by;
                    tile.w = bw;
                    tile.h = bh;
                    drawnRects[drawnCount * 4] = bx;
                    drawnRects[drawnCount * 4 + 1] = by;
                    drawnRects[drawnCount * 4 + 2] = bw;
                    drawnRects[drawnCount * 4 + 3] = bh;
                    drawnCount++;
                    if (isExpandRegion && leaf == expandedLeaf) expandedCollected = true;
                }
            }
        }
        if (hasExpanded && !expandedCollected && drawnCount < MAX_DRAWN) {
            // 展开卡所在织块不在视口（相机飞行期）：单独保活
            int[] grow = layout.reflowAt(expandedRegionX, expandedRegionY, expandedLeaf);
            int[] base = layout.patternAt(expandedRegionX, expandedRegionY);
            float t = expandProgress;
            float bx = lerp((expandedRegionX * CollageLayout.REGION + base[expandedLeaf * 4]) * pitchPx,
                    (expandedRegionX * CollageLayout.REGION + grow[expandedLeaf * 4]) * pitchPx, t);
            float by = lerp((expandedRegionY * CollageLayout.REGION + base[expandedLeaf * 4 + 1]) * pitchPx,
                    (expandedRegionY * CollageLayout.REGION + grow[expandedLeaf * 4 + 1]) * pitchPx, t);
            float bw = lerp(base[expandedLeaf * 4 + 2] * pitchPx - gapPx,
                    grow[expandedLeaf * 4 + 2] * pitchPx - gapPx, t);
            float bh = lerp(base[expandedLeaf * 4 + 3] * pitchPx - gapPx,
                    grow[expandedLeaf * 4 + 3] * pitchPx - gapPx, t);
            Tile tile = obtainTile(drawnCount);
            tile.regionX = expandedRegionX;
            tile.regionY = expandedRegionY;
            tile.leaf = expandedLeaf;
            tile.queueIndex = expandedQueueIndex;
            tile.x = bx;
            tile.y = by;
            tile.w = bw;
            tile.h = bh;
            drawnRects[drawnCount * 4] = bx;
            drawnRects[drawnCount * 4 + 1] = by;
            drawnRects[drawnCount * 4 + 2] = bw;
            drawnRects[drawnCount * 4 + 3] = bh;
            drawnCount++;
        }
    }

    /** 展开卡文案层：scrim + 传输三键 + 频谱带计算 + 进度线（文字层为页面侧 View） */
    private void drawExpandedText(Canvas canvas, float t) {
        int idx = indexOfExpandedDrawn();
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
        paint.setAlpha(255);

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
            paint.setAlpha(255);
        }
    }

    /** 进度线颜色：频谱 tint 主色（双色跟随封面）；无 tint 回落频谱默认色（夜青日金） */
    private int progressLineTint() {
        if (tintTop != 0) return tintTop;
        return night ? 0xFF00E5CC : 0xFFF2C14E;
    }

    /**
     * 展开卡凸出感三件套：全向递减柔影（整体下移模拟顶光）+ 卡面对角渐变
     * （左下70%黑→右上透明）+ 顶棱内微高光，强度随展开进度渐进。
     * 纯矩形分层环（EVEN_ODD）+ 线性渐变，无 shadowLayer，硬件加速安全；零分配。
     */
    private void drawLiftShadow(Canvas canvas, float t) {
        int idx = indexOfExpandedDrawn();
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
        // 卡面对角渐变：左下角 70% 黑 → 右上角透明（立体感；随展开淡入）
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
        paint.setAlpha(255);
    }

    /** 展开进度（1=展开到位；文字层淡入用） */
    public float getExpandProgress() { return hasExpanded ? expandProgress : 0f; }

    /** 展开卡对应队列索引（-1=无） */
    public int getExpandedQueueIndex() { return hasExpanded ? expandedQueueIndex : -1; }

    /** 展开卡在绘制集中的下标（-1=不在） */
    private int indexOfExpandedDrawn() {
        if (!hasExpanded) return -1;
        for (int i = 0; i < drawnCount; i++) {
            if (tilePool.get(i).regionX == expandedRegionX
                    && tilePool.get(i).regionY == expandedRegionY
                    && tilePool.get(i).leaf == expandedLeaf) {
                return i;
            }
        }
        return -1;
    }

    /** 展开卡矩形（屏幕坐标 l,t,r,b，复用缓存；无展开返回 null。页面立即消费，勿持有） */
    @Nullable
    public float[] getExpandedCardRect() {
        if (!hasExpanded) return null;
        int idx = indexOfExpandedDrawn();
        if (idx < 0) return null;
        if (cardRectCache == null) cardRectCache = new float[4];
        cardRectCache[0] = drawnRects[idx * 4] - scrollX;
        cardRectCache[1] = drawnRects[idx * 4 + 1] - scrollY;
        cardRectCache[2] = drawnRects[idx * 4] + drawnRects[idx * 4 + 2] - scrollX;
        cardRectCache[3] = drawnRects[idx * 4 + 1] + drawnRects[idx * 4 + 3] - scrollY;
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
        float left = x - scrollX, topS = y - scrollY;
        float right = left + cw, bottomS = topS + ch;
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
            // 频谱贴卡底无缝：底=卡底，顶=底-带高（12%卡高）
            float bBottom = y + ch;
            float bandH = ch * 0.12f;
            bandScratch[0] = left;
            bandScratch[1] = bBottom - bandH - scrollY;
            bandScratch[2] = right;
            bandScratch[3] = bBottom - scrollY;
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

    /** 取频谱带矩形（屏幕坐标 l,t,r,b）；无带（未展开/环类/无数据）返回 null */
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

    private void drawBadge(Canvas canvas, int queueIndex, float x, float y) {
        String text = badgeOf(queueIndex);
        badgePaint.setColor(0xFFFFFFFF);
        badgePaint.setTextSize(Math.max(11f, unitPx * 0.16f));
        badgePaint.getTextBounds(text, 0, text.length(), badgeRect);
        float pad = unitPx * 0.05f;
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
        float[] hsv = {(queueIndex * 53) % 360, 0.32f, night ? 0.14f : 0.22f};
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

    // ===== 触摸 =====

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean handled = detector.onTouchEvent(event);
        return handled || super.onTouchEvent(event);
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        // 呼吸是永久动画：页不可见时必须停帧链，防后台空转
        if (visibility != VISIBLE) {
            frameRunning = false;
            choreographer.removeFrameCallback(frameCallback);
        }
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
