package com.jingxin.jingxinmusic.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import com.jingxin.jingxinmusic.util.CoverLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 底部封面流（星空漫游效果：播放列表封面从底部涌现的堆叠卡片）
 *
 * 视觉规格（对齐截图）：
 * - 5 张封面横排：当前居中放大，两侧缩小+轻微上移错落
 * - 卡片仅探出屏幕底部约 1/3（方形卡，下半沉入屏幕底）
 * - 底部向上黑色渐隐遮罩：卡片"沉入"星空下方
 * - 喂图：Song 列表 → CoverLoader 异步加载（缓存目录命中快）
 */
public class CoverFlowView extends View {

    public interface SongProvider {
        List<com.jingxin.jingxinmusic.model.Song> getPlaylist();
        int getCurrentIndex();
    }

    private static final int CARD_COUNT = 5; // 当前+左右各二
    private final Bitmap[] coverBitmaps = new Bitmap[CARD_COUNT];
    private final SongProvider provider;
    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int w, h;
    /** 封面加载线程池（独立组件自持；拒绝 shutdown 周期性杀池——淡出动画期间 detach/attach
     * 循环会永久关死线程池，后续加载全部 RejectedExecutionException 静默失败（封面"消失"真凶）。
     * 页面对象被 Manager 并弃后随 GC 回收；单线程池不 shutdown 不泄漏主线程） */
    private final ExecutorService coverExecutor = Executors.newSingleThreadExecutor();

    /** 仅用于首次布局后主动刷新；切歌经 notifySongChanged 驱动 */
    private int lastCenter = -2;

    // ===== 切歌轮转动画：全卡顺次向左（或向右）流动一格 =====
    // 视觉：旧位图集滑动（动画中加载新位图不影响画面），左移时左侧先动、
    // 右侧依次跟上；动画结束帧无缝切到新位图集（双缓冲不闪占位卡）
    private static final long ROLL_MS = 840L;    // 单卡滑动时长（实测再慢一倍，不显乱）
    private static final long ROLL_STAGGER_MS = 180L; // 相邻卡顺次延迟（拉开波浪）
    private long rollAnimStart = -1L;
    private int rollFromOffset = 0;  // 动画起点：+1=新队列从右侧来（全队左移），-1=反向
    private final Bitmap[] rollOldBitmaps = new Bitmap[CARD_COUNT]; // 动画帧用的旧队列位图

    /** 单卡滑动进度（0~1；含阶梯延迟：滑动方向前端的卡先动，顺次传到队尾） */
    private float rollProgress(int idx, long nowMs) {
        if (rollAnimStart < 0) return 1f;
        // 前端=运动方向来处：左移时左端(idx=0)先动；按与前端距离阶梯延迟
        int frontIdx = rollFromOffset > 0 ? 0 : CARD_COUNT - 1;
        int distance = Math.abs(idx - frontIdx);
        long delay = distance * ROLL_STAGGER_MS;
        float t = (nowMs - rollAnimStart - delay) / (float) ROLL_MS;
        return Math.max(0f, Math.min(1f, t));
    }

    /** 中心卡常驻微晃：慢周期轻微摇摆（摆角 ±0.9°，2.8s 周期；与预唱行抖动同族更柔）
     *  仅作用于中心卡，动画轮转中不叠加 */
    private float centerSwayRotation(long nowMs) {
        float t = (nowMs % 2800L) / 2800f;
        return 0.9f * (float) Math.sin(t * 2 * Math.PI);
    }

    /** 动画总窗口（含全部延迟） */
    private long rollTotalMs() {
        return ROLL_MS + ROLL_STAGGER_MS * (CARD_COUNT - 1);
    }

    public CoverFlowView(Context context, SongProvider provider) {
        super(context);
        this.provider = provider;
        setLayerType(LAYER_TYPE_SOFTWARE, null); // mask 渐隐需要
    }

    /** 切歌/数据到达：重拉列表居中位并异步加载封面；center 变化时启动轮转动画 */
    public void notifySongChanged() {
        if (provider == null) return;
        List<com.jingxin.jingxinmusic.model.Song> list = provider.getPlaylist();
        int center = provider.getCurrentIndex();
        if (list == null || list.isEmpty() || center < 0 || center >= list.size()) {
            invalidate();
            return;
        }
        // 轮转方向：center+1（下一首）=全队左移（新卡从右补）；center-1=右移；
        // 首次进入/大跳/列表顺序变化=不动画直接落位。
        // 位图双缓冲：动画帧用旧位图（不闪占位卡）；新位图静默加载存槽、动画结束生效
        boolean rolling = false;
        if (lastCenter >= -1 && lastCenter < list.size() && center != lastCenter) {
            int delta = center - lastCenter;
            if (delta == 1 || delta == -1) {
                rollFromOffset = delta;
                rollAnimStart = System.currentTimeMillis();
                rolling = true;
                System.arraycopy(coverBitmaps, 0, rollOldBitmaps, 0, CARD_COUNT);
                // 溢出补位：左移时队尾(idx4)需要旧队列外一格的封面（旧 center+3），
                // 若在列表内则追载补进 rollOldBitmaps[4]，动画中右端滑入有图可显
                int oldIdx = lastCenter + 3;
                if (delta == 1 && oldIdx < list.size()) {
                    rollOldBitmaps[4] = null;
                    loadToRollSlot(list, oldIdx, 4);
                } else if (delta == -1) {
                    int oldIdx2 = lastCenter - 3;
                    if (oldIdx2 >= 0) {
                        rollOldBitmaps[0] = null;
                        loadToRollSlot(list, oldIdx2, 0);
                    }
                }
            } else {
                rollAnimStart = -1L;
            }
        } else {
            rollAnimStart = -1L;
        }
        lastCenter = center;
        if (rolling) {
            loadNewBitmapsSilently(list, center);
            postInvalidateOnAnimation();
            return; // 动画帧由 onDraw 驱动；结束后切新位图集
        }
        loadNewBitmapsSilently(list, center);
        invalidate();
    }

    /** 新队列位图静默加载：不动当前画面（轮转中 or 直接落位都用它） */
    private void loadNewBitmapsSilently(List<com.jingxin.jingxinmusic.model.Song> list, int center) {
        for (int i = 0; i < CARD_COUNT; i++) {
            int songIdx = center + (i - 2);
            if (songIdx >= 0 && songIdx < list.size()) {
                final int slot = i;
                final com.jingxin.jingxinmusic.model.Song song = list.get(songIdx);
                post(() -> {
                    if (getContext() == null) return;
                    int req = Math.max(w, h) / 2;
                    if (req <= 0) req = 300;
                    CoverLoader.load(getContext(), song, req, req, true, coverExecutor, new CoverLoader.CoverCallback() {
                        @Override
                        public void onCoverLoaded(Bitmap coverBitmap) {
                            if (coverBitmaps[slot] != coverBitmap
                                    && coverBitmaps[slot] != null && !coverBitmaps[slot].isRecycled()) {
                                coverBitmaps[slot].recycle();
                            }
                            coverBitmaps[slot] = coverBitmap;
                            postInvalidate();
                        }

                        @Override
                        public void onCoverFailed() {
                        }
                    });
                });
            }
        }
    }

    /** 旧队列溢出格补载：动画中右端滑入的卡要有图（存 rollOldBitmaps[slot]） */
    private void loadToRollSlot(List<com.jingxin.jingxinmusic.model.Song> list, int songIdx, int slot) {
        if (songIdx < 0 || songIdx >= list.size()) return;
        final com.jingxin.jingxinmusic.model.Song song = list.get(songIdx);
        post(() -> {
            if (getContext() == null) return;
            int req = Math.max(w, h) / 2;
            if (req <= 0) req = 300;
            CoverLoader.load(getContext(), song, req, req, true, coverExecutor, new CoverLoader.CoverCallback() {
                @Override
                public void onCoverLoaded(Bitmap coverBitmap) {
                    rollOldBitmaps[slot] = coverBitmap;
                    postInvalidate();
                }

                @Override
                public void onCoverFailed() {
                }
            });
        });
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // 不 shutdown 线程池：见字段注释（淡入淡出动画的 attach/detach 循环曾致池死→封面消失）
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldw, int oldh) {
        super.onSizeChanged(width, height, oldw, oldh);
        w = width; h = height;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (w <= 0 || h <= 0) return;

        float density = getResources().getDisplayMetrics().density;
        // 整体宽：横屏 50% 屏宽（参考图）；竖屏 90%（窄屏放大补偿），重叠 30%：
        // 总宽 = 卡宽 + 4×卡宽×(1-0.30) => 卡宽 = 整体宽 / (1 + 4×0.70) = W / 3.8
        // 垂直位置受屏高约束防溢出
        boolean portrait = h > w;
        float flowW = w * (portrait ? 0.90f : 0.50f);
        float cardSize = Math.min(flowW / 3.8f, h * 0.30f);
        float overlap = cardSize * 0.30f;      // 水平重叠量
        float step = cardSize - overlap;        // 卡中心距（等大；选中放大另计）
        float cx = w / 2f;

        // 中间卡完整显示且底边贴住屏幕底（页底基准）；左右侧卡以此为基准逐步下沉
        float baseBottom = h;
        // 阳弧：两侧卡逐步下降（每档 20% 卡高）+ 旋转（内层 5°，外层 10°，左逆/右顺）
        float dropPerStep = cardSize * 0.20f;

        // 绘制顺序（Z 层级，后画=在上）：最外两张(0,4) → 次外两张(1,3) → 中心卡(2)——
        // 中心压住两侧内层，内层各压住外层（参考图堆叠方向：越靠中心越靠前）
        long nowMs = System.currentTimeMillis();
        boolean rolling = rollAnimStart >= 0;
        if (rolling && nowMs - rollAnimStart > rollTotalMs()) {
            rolling = false;
            rollAnimStart = -1L; // 动画窗口结束：后续帧直接画新位图集（已静默加载就位）
        }
        int[] drawOrder = new int[]{0, 4, 1, 3, 2};
        for (int idx : drawOrder) {
            int offset = idx - 2; // 0=当前
            boolean isCenter = offset == 0;
            // 轮转补正：动画中每卡从旧位（offset - rollFromOffset）滑向新位 offset
            float visualOffset = offset;
            if (rolling) {
                float t = rollProgress(idx, nowMs);
                float eased = t * t * (3f - 2f * t); // smoothstep
                visualOffset = (offset - rollFromOffset) + rollFromOffset * eased;
                if (t < 1f) postInvalidateOnAnimation();
            }
            // 位图源：动画帧用旧队列位图（双缓冲，不闪占位卡）；结束后用新位图
            Bitmap src = rolling ? rollOldBitmaps[idx] : coverBitmaps[idx];
            // 中心卡透视放大 1.18 → 缩小 10%（实测反馈）：1.18 × 0.90 ≈ 1.06；
            // 轮转中大小随 visualOffset 距离插值（滑动中自动缩放过渡）
            float centerScale = 1.06f;
            float scale = Math.max(0.92f, 1f + (centerScale - 1f)
                    * Math.max(0f, 1f - Math.abs(visualOffset)));
            float size = cardSize * scale;
            float px = cx + visualOffset * step;
            // 阳弧落位：中心卡底边锚定屏幕底（完整显示、贴底，不下沉补偿）；
            // 侧卡以中心卡基准位置为参照逐步下沉（|visualOffset| 越大越低）
            float py = baseBottom + Math.abs(visualOffset) * dropPerStep;
            // 旋转：中心 0°，内层 ∓5°，外层 ∓10°（左逆时针=负，右顺时针=正）；按视觉位置插值
            float rotMag = Math.abs(visualOffset) <= 1f ? Math.abs(visualOffset) * 5f
                    : 5f + (Math.abs(visualOffset) - 1f) * 5f;
            float rotation = Math.abs(visualOffset) < 0.02f ? 0f
                    : Math.signum(visualOffset) * rotMag;

            // 中心卡常驻微晃（轮转动画中不叠加，避免双重摆动）
            boolean centerSway = !rolling && Math.abs(visualOffset) < 0.02f;
            if (centerSway) {
                rotation += centerSwayRotation(nowMs);
                postInvalidateOnAnimation(); // 微晃持续帧
            }

            drawCard(canvas, src, px, py, size, rotation, isCenter && !rolling, density);
        }

        // 底部向上黑色渐隐遮罩：仅盖往下探的侧卡下缘，中心卡区（baseBottom 以上）不受影响
        float maskTop = baseBottom - h * 0.02f;
        if (h - maskTop > 2f) {
            maskPaint.setShader(new LinearGradient(0, maskTop, 0, h,
                    Color.argb(0, 2, 4, 10), Color.argb(235, 2, 4, 10), Shader.TileMode.CLAMP));
            canvas.drawRect(0, maskTop, w, h, maskPaint);
        }
    }

    /** 画一张圆角卡（锚点 px=水平中心, py=卡片底边；绕卡中心转 rotation 度） */
    private void drawCard(Canvas canvas, Bitmap bmp, float px, float py, float size,
                          float rotation, boolean isCenter, float density) {
        if (rotation != 0f) {
            // 绕卡片中心旋转后绘制
            float centerX = px, centerY = py - size / 2f;
            int sc = canvas.save();
            canvas.rotate(rotation, centerX, centerY);
            drawCardInner(canvas, bmp, px, py, size, isCenter, density);
            canvas.restoreToCount(sc);
        } else {
            drawCardInner(canvas, bmp, px, py, size, isCenter, density);
        }
    }

    private void drawCardInner(Canvas canvas, Bitmap bmp, float px, float py, float size,
                               boolean isCenter, float density) {
        float left = px - size / 2f, top = py - size;
        // 层叠阴影
        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(Color.argb(isCenter ? 120 : 64, 0, 0, 0));
        shadow.setShadowLayer(isCenter ? 12f * density : 6f * density, 0f, 4f * density,
                Color.argb(isCenter ? 150 : 80, 0, 0, 0));
        canvas.drawRoundRect(new RectF(left, top, left + size, top + size),
                size * 0.08f, size * 0.08f, shadow);

        Path clip = new Path();
        RectF r = new RectF(left, top, left + size, top + size);
        clip.addRoundRect(r, size * 0.08f, size * 0.08f, Path.Direction.CW);
        int sc = canvas.save();
        canvas.clipPath(clip);
        if (bmp != null && !bmp.isRecycled()) {
            // 等比裁切居中绘制
            float bw = bmp.getWidth(), bh = bmp.getHeight();
            float scale = Math.max(size / bw, size / bh);
            float dw = bw * scale, dh = bh * scale;
            canvas.drawBitmap(bmp, null,
                    new RectF(left + (size - dw) / 2f, top + (size - dh) / 2f,
                            left + (size + dw) / 2f, top + (size + dh) / 2f), cardPaint);
        } else {
            // 占位：星空上可见的暗蓝描边空卡（原灰色与星空同色致"卡消失"错觉）
            cardPaint.setColor(Color.argb(200, 10, 16, 30));
            canvas.drawRect(r, cardPaint);
            Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(size * 0.015f);
            stroke.setColor(Color.argb(150, 90, 110, 150));
            canvas.drawRoundRect(r, size * 0.08f, size * 0.08f, stroke);
        }
        canvas.restoreToCount(sc);
    }
}
