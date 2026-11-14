package com.jingxin.jingxinmusic.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import com.jingxin.jingxinmusic.R;
import com.jingxin.jingxinmusic.model.Song;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 电台星海层 v2（电台专用播放页核心自绘：底图标 + 居中台名 + 电波 + 星点台名群）
 *
 * 场景：
 * 1. 底部中央：当前电台收音机图标（播放中呼吸光环）
 * 2. 画面中部居中：当前电台文字名（大字）
 * 3. 电波：从底部图标向上朝台名连续发散的圆弧电波（暂停渐隐）
 * 4. 单击页面空白：其他电台名从星点放大浮现——随机区域、大小不等、
 *    透明度 0~50%，空中晃动+闪烁；5 秒后渐隐缩回星点融入星空
 * 5. 浮现期点击其他台名：直接换台——新台名漂到中间放大替代当前台，
 *    当前台名漂向新台原位缩成星点
 * 6. 单击当前台名=播放/暂停；长按当前台名=收藏；长按其他区域=顶栏（页面回调）
 *
 * 性能红线（车机）：onDraw 零分配（Paint/RectF 复用、文字宽度数据期预量）、
 * 无 setShadowLayer；动画仅 attached 期间跑 Choreographer。
 */
public class RadioUniverseView extends View {

    // ===== 回调（页面实现，转 Fragment Actions） =====

    public interface OnRadioAction {
        void onPlaySongAt(int queueIndex);
        void onTogglePlayPause();
        void onToggleFavorite();
    }

    public interface OnLongPressListener { void onLongPress(float x, float y); }

    // ===== 状态机 =====

    private static final int ST_HIDDEN = 0;   // 不显示
    private static final int ST_APPEAR = 1;   // 星点 → 放大浮现
    private static final int ST_FLOAT = 2;    // 漂浮（晃动+闪烁）
    private static final int ST_DISMISS = 3;  // 渐隐缩回星点

    private static final long APPEAR_MS = 650L;
    private static final long DISMISS_MS = 750L;
    private static final long SWAP_MS = 800L;          // 换台对调漂移动画
    private static final int MAX_FLOATERS = 24;        // 散开台名上限（防百台拥挤）
    private static final int MAX_SCATTER = 30;         // 电台清单上限散布数
    private static final float OVERLAP_LIMIT = 0.30f;  // 台名间交叉面积上限（占小矩形比例）

    // ===== 配色（LED 青 + 暖白星点，星空页同气质） =====

    private static final int COLOR_WAVE = 0xFFFFFFFF;  // 电波默认白（无封面时）
    private static final int COLOR_TITLE = 0xFFF0F8FF;  // 台名浅冰白
    private static final int COLOR_DOT = 0xFFEFE6D0;    // 星点暖白

    // ===== 漂浮台名 =====

    private static final class Floater {
        int queueIndex;
        float cx, cy;        // 漂浮锚点
        float size;          // 字号
        float maxAlpha;      // 0.1~0.5
        float textW;         // 数据期预量宽度
        float wobAX, wobAY;  // 晃动幅度
        float wobPeriod, wobPhase;
        float twkPeriod, twkPhase; // 闪烁
        int state;
        long stateStart;
    }

    /** 换台对调动画：新台 floater 位 → 中心放大；当前台名 → 新台原位缩为星点 */
    private static final class Swap {
        final int newIdx;
        final String newText, oldText;
        final float newFx, newFy, newFsize, newFalpha; // 新台起点快照
        final float oldTx, oldTy;                       // 老台终点（新台原位）
        final long start = System.currentTimeMillis();
        Swap(int ni, String nt, String ot, float fx, float fy, float fs, float fa,
             float tx, float ty) {
            newIdx = ni; newText = nt; oldText = ot;
            newFx = fx; newFy = fy; newFsize = fs; newFalpha = fa;
            oldTx = tx; oldTy = ty;
        }
    }

    private final List<Floater> floaters = new ArrayList<>();
    private final List<Song> playlist = new ArrayList<>();
    private int currentIndex = -1;

    private final Random random = new Random();
    private int layoutW, layoutH;
    private boolean playing = true;
    private Swap swap;

    // 中心台名几何（数据期/尺寸期收敛）
    private String title = "";
    private float titleSize, titleW, titleCx, titleCy; // cy=视觉中心

    // 底部图标几何
    private float iconCx, iconCy, iconSize;
    private Bitmap coverBitmap;    // 电台封面（有则圆封面旋转；无则默认图标不转）
    private float coverAngle;      // 封面旋转角
    private long lastFrameMs = -1L;
    private final Path circlePath = new Path();
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final float COVER_ROT_DEG_PER_S = 90f; // 封面转速（4s 一圈）

    // 电波参数
    private float waveAlpha;                       // 暂停渐隐因子 0~1
    private int waveColor = COLOR_WAVE;            // 电波色（取色到达后覆盖；默认青）
    private static final int WAVE_COUNT = 5;       // 同屏弧数
    private static final long WAVE_CYCLE_MS = 6400L;

    // ===== 预建绘制资源（onDraw 零分配红线） =====

    private final Bitmap radioBitmap;
    private final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wavePath = new Path(); // 锯齿弧路径（预建复用；drawWaves 重写不改新建）
    private final RectF arcRect = new RectF();
    private final RectF bmpRect = new RectF();

    private OnRadioAction radioAction;
    private OnLongPressListener longPressListener;

    private final GestureDetector gestureDetector;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            if (isShown()) {
                invalidate();
                Choreographer.getInstance().postFrameCallback(this);
            }
        }
    };

    public RadioUniverseView(Context context) { this(context, null); }

    public RadioUniverseView(Context context, AttributeSet attrs) {
        super(context, attrs);
        radioBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.ic_radio_station);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL));
        wavePaint.setStyle(Paint.Style.STROKE);
        setClickable(true);
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapUp(MotionEvent e) {
                handleTap(e.getX(), e.getY());
                return true;
            }

            @Override public void onLongPress(MotionEvent e) {
                handleLongPress(e.getX(), e.getY());
            }

            @Override public boolean onDown(MotionEvent e) { return true; }
        });
    }

    // ===== 对外接口 =====

    public void setOnRadioAction(OnRadioAction a) { this.radioAction = a; }

    public void setOnLongPressListener(OnLongPressListener l) { this.longPressListener = l; }

    public void setPlaying(boolean isPlaying) {
        if (playing != isPlaying) { playing = isPlaying; invalidate(); }
    }

    /** 是否有封面（页面据此决定电波取色是否可用；无封面恒白） */
    private boolean coverPresent = false;

    /** 电波色注入（页面桥调：电台封面取色到达时同步）；无封面时拒绝上色保持白色 */
    public void setWaveColor(int argb) {
        if (!coverPresent) return;
        if (argb != 0 && argb != waveColor) {
            waveColor = argb;
            buildTitleGlow(); // 辉光色跟随封面取色
            invalidate();
        }
    }

    /** 封面注入：有封面→底部转成圆形封面旋转，电波色由封面取色；null→默认图标+电波白色 */
    public void setCover(android.graphics.Bitmap bitmap) {
        if (coverBitmap == bitmap) return;
        coverBitmap = bitmap;
        coverAngle = 0f;
        coverPresent = bitmap != null;
        if (bitmap == null) {
            // 无封面：电波恢复默认白色（重置取色覆盖，辉光同步重建）
            if (waveColor != COLOR_WAVE) {
                waveColor = COLOR_WAVE;
                buildTitleGlow();
            }
        }
        invalidate();
    }

    /** 当前台名中心（供页面收藏脉冲定位；无数据返回 null） */
    public float[] getCurrentStationCenter() {
        if (currentIndex < 0) return null;
        return new float[]{titleCx, titleCy};
    }

    /**
     * 队列/当前台刷新（enter/切台广播/服务就绪）。
     * 首灌静默；换台对调动画目标到达时只做数据校正（不打断动画、不清散开群）；
     * 外部切台（悬浮窗/遥控）收掉散开群。
     */
    public void setQueueData(List<Song> list, int index) {
        int oldIdx = currentIndex;
        boolean first = playlist.isEmpty() || currentIndex < 0;
        playlist.clear();
        if (list != null) playlist.addAll(list);
        currentIndex = (list != null && index >= 0 && index < list.size()) ? index : -1;
        refreshTitle();
        if (first) {
            floaters.clear();
            return;
        }        if (swap != null && currentIndex == swap.newIdx) {
            hideFloaterOf(currentIndex); // 它已成为当前台，从散开群退场
            return;
        }
        if (currentIndex != oldIdx) dismissAll(); // 外部切台：散开群收回
        pruneFloaters();                          // 清单变化：失效台名退场
    }

    // ===== 几何收敛 =====

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) return;
        boolean wide = w > h;
        // 漂浮锚点按比例迁移（横竖/悬浮 resize 不闪跳）
        if (layoutW > 0 && layoutH > 0) {
            float sx = w / (float) layoutW, sy = h / (float) layoutH;
            for (Floater f : floaters) { f.cx *= sx; f.cy *= sy; }
        }
        layoutW = w;
        layoutH = h;
        iconSize = Math.min(w, h) * (wide ? 0.13f : 0.15f);
        float iconMargin = iconSize * 0.62f; // 左下角边距
        iconCx = iconMargin;
        iconCy = h - iconMargin;
        circlePath.reset();
        circlePath.addCircle(iconCx, iconCy, iconSize * 0.5f, Path.Direction.CW);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(getResources().getDisplayMetrics().density);
        ringPaint.setColor(0x33FFFFFF);
        titleCx = w / 2f;
        titleCy = h * 0.42f;
        refreshTitle();
    }

    /** 台名/字号收敛（长名自适应缩字号；字号=页面中部大字的一半规格） */
    // ===== 中心台名辉光（离屏预渲染位图；onDraw 只 drawBitmap，无 setShadowLayer） =====

    private Bitmap titleGlowBitmap;  // 当前台名 + 青色外发光
    private float titleGlowCx, titleGlowCy; // 位图中心（绘制对齐用）

    /** 台名/字号收敛（长名自适应缩字号；字号=页面中部大字的一半规格） */
    private void refreshTitle() {
        if (layoutW <= 0) return;
        title = titleOf(currentIndex);
        boolean wide = layoutW > layoutH;
        float target = Math.max(13f, layoutW * (wide ? 0.028f : 0.042f));
        textPaint.setTextSize(target);
        float measured = textPaint.measureText(title);
        float avail = layoutW * 0.9f;
        titleSize = measured <= avail ? target : Math.max(target * 0.5f, target * avail / measured);
        textPaint.setTextSize(titleSize);
        titleW = textPaint.measureText(title);
        buildTitleGlow();
    }

    /** 离屏渲染台名辉光位图：文字带 BlurMaskFilter 青色外发光 + 实心主体（文字/尺寸/辉光色变化时重建） */
    private void buildTitleGlow() {
        if (title.isEmpty() || layoutW <= 0) return;
        float blur = titleSize * 0.38f;
        float padH = blur + 6f;
        float padV = titleSize * 0.55f + blur;
        int w = (int) (titleW + padH * 2f);
        int h = (int) (titleSize + padV * 2f);
        if (w <= 0 || h <= 0) return;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(titleSize);
        Paint.FontMetrics fm = p.getFontMetrics();
        float base = h / 2f - (fm.ascent + fm.descent) / 2f;
        // 辉光层：BlurMaskFilter 外发光（色随电波取色/封面，无取色用默认青）
        p.setColor(waveColor & 0xFFFFFF);
        p.setMaskFilter(new BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL));
        c.drawText(title, w / 2f, base, p);
        // 主体层：实文字叠在辉光上
        p.setMaskFilter(null);
        p.setColor(0xFFF0F8FF);
        c.drawText(title, w / 2f, base, p);
        if (titleGlowBitmap != null) titleGlowBitmap.recycle();
        titleGlowBitmap = bmp;
        titleGlowCx = w / 2f;
        titleGlowCy = h / 2f;
    }

    private String titleOf(int i) {
        if (i < 0 || i >= playlist.size()) return "";
        Song s = playlist.get(i);
        return s != null && s.title != null ? s.title : "";
    }

    // ===== 散开（单击空白触发） =====

    /** 散开：其他台名从星点放大浮现（随机区域/大小/透明度），5s 后集体收回 */
    private void scatter() {
        if (swap != null || currentIndex < 0 || playlist.size() <= 1) return;
        floaters.clear();
        int n = playlist.size() - 1;
        int count = Math.min(MAX_FLOATERS, n);
        // 抽样（台多时随机挑一批；打乱索引池）
        List<Integer> pool = new ArrayList<>(n);
        for (int i = 0; i < playlist.size(); i++) if (i != currentIndex) pool.add(i);
        java.util.Collections.shuffle(pool, random);
        boolean wide = layoutW > layoutH;
        float baseSize = Math.max(14f, layoutW * (wide ? 0.028f : 0.038f));
        float density = getResources().getDisplayMetrics().density;
        float pad = 10f * density;
        for (int p = 0; p < count && p < pool.size(); p++) {
            Floater f = new Floater();
            f.queueIndex = pool.get(p);
            f.size = baseSize * (0.45f + random.nextFloat() * 0.75f);   // 大小不等
            f.maxAlpha = 0.10f + random.nextFloat() * 0.40f;             // 10%~50%
            textPaint.setTextSize(f.size);
            String t = titleOf(f.queueIndex);
            f.textW = textPaint.measureText(t);
            // 晃动/闪烁参数先行（重叠判定需要晃动余量）
            f.wobAX = (2f + random.nextFloat() * 4f) * density;
            f.wobAY = (1.5f + random.nextFloat() * 3f) * density;
            f.wobPeriod = 2600f + random.nextFloat() * 2600f;
            f.wobPhase = random.nextFloat() * (float) (2 * Math.PI);
            f.twkPeriod = 900f + random.nextFloat() * 1600f;
            f.twkPhase = random.nextFloat() * (float) (2 * Math.PI);
            // 随机位置（整个页面范围；避开中心台名区/底部图标区，
            // 且与其他漂浮台名交叉面积不超小矩形 30%；多次尝试失败则跳过该台）
            float fx = 0, fy = 0;
            boolean ok = false;
            for (int tryI = 0; tryI < 24; tryI++) {
                float hw = f.textW / 2f + pad, hh = f.size + pad;
                fx = hw + pad + random.nextFloat() * Math.max(1f, layoutW - 2 * hw - 2 * pad);
                fy = hh + random.nextFloat() * Math.max(1f, layoutH - 2 * hh);
                if (hitsTitleArea(fx, fy, 1.45f) || hitsIconArea(fx, fy, 1.5f)) continue;
                if (overlapsAny(f, fx, fy)) continue;
                ok = true;
                break;
            }
            if (!ok) continue; // 全部尝试都破约束则不放该台（页面空间有限优先保规则）
            f.cx = fx;
            f.cy = fy;
            f.state = ST_APPEAR;
            f.stateStart = System.currentTimeMillis();
            floaters.add(f);
        }
        invalidate();
    }

    /**
     * 台名互斥：候选位置与其他漂浮台名的交叉面积是否超过小矩形的 30%。
     * 判定矩形含晃动幅度余量（漂浮中动态位置同样不破例）。
     */
    private boolean overlapsAny(Floater cand, float cx, float cy) {
        float l1 = cx - cand.textW / 2f - cand.wobAX;
        float r1 = cx + cand.textW / 2f + cand.wobAX;
        float t1 = cy - cand.size * 0.7f - cand.wobAY;
        float b1 = cy + cand.size * 0.7f + cand.wobAY;
        for (Floater o : floaters) {
            if (o.state == ST_HIDDEN) continue;
            float l2 = o.cx - o.textW / 2f - o.wobAX;
            float r2 = o.cx + o.textW / 2f + o.wobAX;
            float t2 = o.cy - o.size * 0.7f - o.wobAY;
            float b2 = o.cy + o.size * 0.7f + o.wobAY;
            float ow = Math.min(r1, r2) - Math.max(l1, l2);
            float oh = Math.min(b1, b2) - Math.max(t1, t2);
            if (ow <= 0f || oh <= 0f) continue; // 无交叉
            float inter = ow * oh;
            float smaller = Math.min((r1 - l1) * (b1 - t1), (r2 - l2) * (b2 - t2));
            if (smaller > 0f && inter / smaller > OVERLAP_LIMIT) return true;
        }
        return false;
    }

    /** 中心台名矩形命中判定（scale=扩放余量倍数） */
    private boolean hitsTitleArea(float x, float y, float scale) {
        float hw = titleW * 0.5f * scale, hh = titleSize * scale;
        return Math.abs(x - titleCx) <= hw && Math.abs(y - titleCy) <= hh;
    }

    /** 底部图标圆命中判定 */
    private boolean hitsIconArea(float x, float y, float scale) {
        float r = iconSize * 0.75f * scale + iconSize * 0.2f;
        float dx = x - iconCx, dy = y - iconCy;
        return dx * dx + dy * dy <= r * r;
    }

    /** 5s 寿期到 / 外部切台：散开群全体收回（渐隐缩回星点） */
    private void dismissAll() {
        long now = System.currentTimeMillis();
        for (Floater f : floaters) {
            if (f.state == ST_APPEAR || f.state == ST_FLOAT) {
                f.state = ST_DISMISS;
                f.stateStart = now;
            }
        }
    }

    private void hideFloaterOf(int queueIndex) {
        for (Floater f : floaters) {
            if (f.queueIndex == queueIndex && f.state != ST_HIDDEN) {
                f.state = ST_HIDDEN;
            }
        }
    }

    /** 清单变化：queueIndex 越界的台名退场 */
    private void pruneFloaters() {
        for (Floater f : floaters) {
            if (f.queueIndex >= playlist.size()) f.state = ST_HIDDEN;
        }
    }

    // ===== 绘制 =====

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (layoutW <= 0 || layoutH <= 0) return;
        long now = System.currentTimeMillis();

        // 电波淡入淡出（暂停渐隐）
        waveAlpha += ((playing ? 1f : 0f) - waveAlpha) * 0.12f;

        drawWaves(canvas, now);
        drawIcon(canvas, now);
        drawFloaters(canvas, now);

        if (swap != null) drawSwap(canvas, now);
        else drawTitle(canvas, now);

        postInvalidateOnAnimation();
    }

    /** 锯齿弧分段数（弧向齿数；齿数适中太密会变虚线） */
    private static final int WAVE_TEETH = 28;
    /** 锯齿深度（占半径比例；齿尖向外/基线向内的振幅） */
    private static final float WAVE_TOOTH_DEPTH = 0.030f;

    /** 电波：图标→中心台名方向的连续锯齿弧（半径推进+透明度衰减；最远端 0.8×台名距） */
    private void drawWaves(Canvas canvas, long now) {
        if (waveAlpha < 0.02f) return;
        float r0 = iconSize * 0.75f;
        // 终点=台名中心：辐射范围缩减 20%（终点距图标 = 图标→台名距离 × 0.8）
        float dx = titleCx - iconCx, dy = titleCy - iconCy;
        float r1 = Math.max(r0 * 1.4f, (float) Math.sqrt(dx * dx + dy * dy) * 0.8f);
        // 弧扇区朝向=左下→右上（指向台名；canvas 角度 0°=右，逆时针为负）
        float centerDeg = (float) Math.toDegrees(Math.atan2(dy, dx));
        float startDeg = centerDeg - 70f;
        float sweepDeg = 140f;
        float tooth = r0 * WAVE_TOOTH_DEPTH + 1.5f; // 齿深近似恒定（小半径时退化小）
        float stroke = 2f * getResources().getDisplayMetrics().density;
        wavePaint.setStrokeWidth(stroke);
        wavePaint.setStyle(Paint.Style.STROKE);
        wavePaint.setStrokeJoin(Paint.Join.ROUND); // 锯齿折点圆润（车机 GPU 快线段）
        for (int i = 0; i < WAVE_COUNT; i++) {
            float ph = ((now % WAVE_CYCLE_MS) / (float) WAVE_CYCLE_MS + i / (float) WAVE_COUNT) % 1f;
            float r = r0 + (r1 - r0) * ph;
            int a = (int) ((1f - ph) * 0.5f * waveAlpha * 255);
            if (a <= 4) continue;
            wavePaint.setColor(a << 24 | (waveColor & 0xFFFFFF));
            // 锯齿弧：分段折线，齿尖（r+tooth）与齿谷（r-tooth）交替
            wavePath.reset();
            for (int s = 0; s <= WAVE_TEETH; s++) {
                float frac = s / (float) WAVE_TEETH;
                float deg = startDeg + sweepDeg * frac;
                // 奇数段在齿尖、偶数段在齿谷（两端闭合在基线半径）
                float rr = (s == 0 || s == WAVE_TEETH) ? r
                        : ((s % 2 == 1) ? r + tooth : r - tooth);
                double rad = Math.toRadians(deg);
                float x = iconCx + (float) (rr * Math.cos(rad));
                float y = iconCy + (float) (rr * Math.sin(rad));
                if (s == 0) wavePath.moveTo(x, y);
                else wavePath.lineTo(x, y);
            }
            canvas.drawPath(wavePath, wavePaint);
        }
    }

    /** 底部图标：有封面→圆形封面旋转（暂停停转）；无封面→默认收音机图标（不转） */
    private void drawIcon(Canvas canvas, long now) {
        float dt = (lastFrameMs < 0) ? 0f : (now - lastFrameMs) / 1000f;
        lastFrameMs = now;
        if (coverBitmap != null && !coverBitmap.isRecycled()) {
            if (playing && dt > 0f) coverAngle += dt * COVER_ROT_DEG_PER_S;
            int sc = canvas.save();
            canvas.clipPath(circlePath);
            canvas.rotate(coverAngle, iconCx, iconCy);
            float d = iconSize * 0.5f;
            bmpRect.set(iconCx - d, iconCy - d, iconCx + d, iconCy + d);
            bmpPaint.setAlpha(255);
            canvas.drawBitmap(coverBitmap, null, bmpRect, bmpPaint);
            canvas.restoreToCount(sc);
            // 细描边环（盖 clip 边缘锯齿，兼作界框）
            canvas.drawCircle(iconCx, iconCy, iconSize * 0.5f, ringPaint);
        } else {
            bmpPaint.setAlpha(playing ? 255 : 150);
            bmpRect.set(iconCx - iconSize / 2f, iconCy - iconSize / 2f,
                    iconCx + iconSize / 2f, iconCy + iconSize / 2f);
            canvas.drawBitmap(radioBitmap, null, bmpRect, bmpPaint);
        }
    }

    /** 漂浮台名群：星点→浮现→漂浮（晃动+闪烁）→收回星点 */
    private void drawFloaters(Canvas canvas, long now) {
        float density = getResources().getDisplayMetrics().density;
        for (Floater f : floaters) {
            if (f.state == ST_HIDDEN) continue;
            long el = now - f.stateStart;
            String text = titleOf(f.queueIndex);
            if (text.isEmpty()) { f.state = ST_HIDDEN; continue; }

            // 晃动（正弦复合轨迹）
            float wob = (float) Math.sin(2 * Math.PI * (now % (long) f.wobPeriod) / f.wobPeriod + f.wobPhase);
            float px = f.cx + wob * f.wobAX;
            float py = f.cy + (float) Math.cos(2 * Math.PI * (now % (long) f.wobPeriod)
                    / f.wobPeriod + f.wobPhase * 1.7f) * f.wobAY;

            if (f.state == ST_APPEAR) {
                float t = Math.min(1f, el / (float) APPEAR_MS);
                float e = easeOut(t);
                // 星点渐灭（让位于文字）
                if (t < 1f) drawDot(canvas, px, py, 1f - e);
                if (e > 0.05f) {
                    drawFloaterText(canvas, text, px, py, f.size, f.maxAlpha * e, true, e);
                }
                if (t >= 1f) { f.state = ST_FLOAT; f.stateStart = now; }
            } else if (f.state == ST_FLOAT) {
                // 闪烁：alpha 在 maxAlpha 的 55%~100% 波动
                float tw = 0.55f + 0.45f * (float) (0.5 + 0.5
                        * Math.sin(2 * Math.PI * (now % (long) f.twkPeriod) / f.twkPeriod + f.twkPhase));
                drawFloaterText(canvas, text, px, py, f.size, f.maxAlpha * tw, false, 1f);
            } else { // ST_DISMISS
                float t = Math.min(1f, el / (float) DISMISS_MS);
                float e = easeOut(t);
                drawFloaterText(canvas, text, px, py, f.size, f.maxAlpha * (1f - e), true, 1f - 0.88f * e);
                // 尾段：星点亮起再随文字一起淡出（融入星空）
                float dotT = Math.min(1f, t * 1.8f);
                if (dotT < 1f) drawDot(canvas, px, py, dotT * (1f - Math.max(0f, (t - 0.55f) / 0.45f)));
                if (t >= 1f) f.state = ST_HIDDEN;
            }
        }
    }

    /** 台名文字（scale=绘制缩放；APPEAR/DISMISS 传值，FLOAT 传 1） */
    private void drawFloaterText(Canvas canvas, String text, float px, float py,
                                 float size, float alpha, boolean scaled, float scale) {
        int a = (int) (Math.max(0f, Math.min(1f, alpha)) * 255);
        if (a <= 3) return;
        textPaint.setTextSize(size);
        textPaint.setColor(a << 24 | (COLOR_TITLE & 0xFFFFFF));
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = py + (fm.descent - fm.ascent) / 2f - fm.descent;
        if (scaled) {
            int sc = canvas.save();
            canvas.scale(Math.max(0.06f, scale), Math.max(0.06f, scale), px, py);
            canvas.drawText(text, px, baseline, textPaint);
            canvas.restoreToCount(sc);
        } else {
            canvas.drawText(text, px, baseline, textPaint);
        }
    }

    /** 星点（小亮圆，暖白） */
    private void drawDot(Canvas canvas, float x, float y, float alpha) {
        int a = (int) (Math.max(0f, Math.min(1f, alpha)) * 230);
        if (a <= 3) return;
        dotPaint.setColor(a << 24 | (COLOR_DOT & 0xFFFFFF));
        canvas.drawCircle(x, y, 2.2f * getResources().getDisplayMetrics().density, dotPaint);
    }

    private static final long TITLE_CYCLE_MS = 4200L; // 中心台名渐隐循环周期

    /** 中心台名：透明度 100%→1%→100% 连续往返循环（无缩放；无休止段），swap 动画期间不画 */
    private void drawTitle(Canvas canvas, long now) {
        if (title.isEmpty()) return;
        // 相位 0→1：透明度 100% 起 → 降到 1%（ph=0.5）→ 回到 100%
        float ph = (now % TITLE_CYCLE_MS) / (float) TITLE_CYCLE_MS;
        float amb = 1f - 0.99f * (0.5f - 0.5f * (float) Math.cos(2 * Math.PI * ph));
        // 淡出段（透明度降到 ~15% 时）星点接棒，文字始终保留 ≥1% 残留
        if (amb < 0.15f) drawDot(canvas, titleCx, titleCy, 1f - amb / 0.15f);
        if (titleGlowBitmap == null || titleGlowBitmap.isRecycled()) return;
        // 辉光位图整体透明度随循环波动（含辉光一起渐隐）
        bmpPaint.setAlpha((int) (amb * (playing ? 1f : 0.8f) * 255));
        bmpRect.set(titleCx - titleGlowCx, titleCy - titleGlowCy,
                titleCx + titleGlowCx, titleCy + titleGlowCy);
        canvas.drawBitmap(titleGlowBitmap, null, bmpRect, bmpPaint);
    }

    /** 换台对调：新台名 floater 位→中心放大实化；老台名 中心→新台原位缩成星点 */
    private void drawSwap(Canvas canvas, long now) {
        float t = Math.min(1f, (now - swap.start) / (float) SWAP_MS);
        float e = easeOut(t);

        // 新台：漂向中心，字号→标题字号，alpha→1
        float nx = lerp(swap.newFx, titleCx, e);
        float ny = lerp(swap.newFy, titleCy, e);
        float ns = lerp(swap.newFsize, titleSize, e);
        float na = lerp(swap.newFalpha, 1f, e);
        drawFloaterText(canvas, swap.newText, nx, ny, ns, na, true, 1f);

        // 老台：漂向新台原位，字号缩到星点，alpha→0；尾段星点亮灭
        float ox = lerp(titleCx, swap.oldTx, e);
        float oy = lerp(titleCy, swap.oldTy, e);
        float os = lerp(titleSize, 3f, e);
        float oa = 1f - e;
        drawFloaterText(canvas, swap.oldText, ox, oy, os, oa, true, Math.max(0.06f, 1f - e));
        float dotT = Math.min(1f, t * 1.8f);
        if (dotT < 1f) drawDot(canvas, ox, oy,
                dotT * (1f - Math.max(0f, (t - 0.55f) / 0.45f)));

        if (t >= 1f) {
            currentIndex = swap.newIdx;
            refreshTitle();
            swap = null;
        }
    }

    // ===== 触摸 =====

    private void handleTap(float x, float y) {
        if (layoutW <= 0) return;
        if (swap != null) return; // 对调动画中忽略
        Integer hit = hitFloater(x, y);
        if (hit != null) {        // 点其他台名：对调换台
            Floater target = floaterOf(hit);
            if (target != null) {
                startSwap(target);
                if (radioAction != null) radioAction.onPlaySongAt(hit);
            }
            return;
        }
        if (hitsTitleArea(x, y, 1.2f)) { // 点当前台名：播放/暂停
            if (radioAction != null) radioAction.onTogglePlayPause();
            return;
        }
        if (hitsIconArea(x, y, 2.2f)) {  // 点底部图标：同样播放/暂停
            if (radioAction != null) radioAction.onTogglePlayPause();
            return;
        }
        // 空白：已散开则本次单击收回渐隐；未散开则散开
        if (hasActiveFloaters()) dismissAll();
        else scatter();
    }

    /** 是否存在仍处于活跃（浮现/漂浮）的散开台名 */
    private boolean hasActiveFloaters() {
        for (Floater f : floaters) {
            if (f.state == ST_APPEAR || f.state == ST_FLOAT) return true;
        }
        return false;
    }

    private void handleLongPress(float x, float y) {
        if (swap != null) return;
        if (hitsTitleArea(x, y, 1.2f) || hitsIconArea(x, y, 2.2f)) {
            if (radioAction != null) radioAction.onToggleFavorite();
        } else if (longPressListener != null) {
            longPressListener.onLongPress(x, y); // 顶栏呼出
        }
    }

    /** 对调动画启动（乐观：不等广播，本地即刻起飞） */
    private void startSwap(Floater target) {
        long now = System.currentTimeMillis();
        String newText = titleOf(target.queueIndex);
        swap = new Swap(target.queueIndex, newText, title,
                target.cx, target.cy, target.size, target.maxAlpha,
                target.cx, target.cy);
        hideFloaterOf(target.queueIndex);
        // 其余散开群保留漂浮（直到再次单击空白才收回）
        invalidate();
    }

    private Integer hitFloater(float x, float y) {
        float density = getResources().getDisplayMetrics().density;
        float pad = 10f * density;
        Integer best = null;
        float bestD = Float.MAX_VALUE;
        for (Floater f : floaters) {
            if (f.state != ST_APPEAR && f.state != ST_FLOAT) continue;
            float hw = f.textW / 2f + pad, hh = f.size * 0.8f + pad;
            if (Math.abs(x - f.cx) <= hw && Math.abs(y - f.cy) <= hh) {
                float d = (x - f.cx) * (x - f.cx) + (y - f.cy) * (y - f.cy);
                if (d < bestD) { bestD = d; best = f.queueIndex; }
            }
        }
        return best;
    }

    private Floater floaterOf(int queueIndex) {
        for (Floater f : floaters) if (f.queueIndex == queueIndex) return f;
        return null;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return gestureDetector.onTouchEvent(event);
    }

    // ===== 工具 =====

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    private static float easeOut(float t) { return 1f - (1f - t) * (1f - t); }

    // ===== 帧循环 =====

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Choreographer.getInstance().removeFrameCallback(frameCallback);
    }
}
