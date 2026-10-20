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

    public CoverFlowView(Context context, SongProvider provider) {
        super(context);
        this.provider = provider;
        setLayerType(LAYER_TYPE_SOFTWARE, null); // mask 渐隐需要
    }

    /** 切歌/数据到达：重拉列表居中位并异步加载封面 */
    public void notifySongChanged() {
        if (provider == null) return;
        List<com.jingxin.jingxinmusic.model.Song> list = provider.getPlaylist();
        int center = provider.getCurrentIndex();
        if (list == null || list.isEmpty() || center < 0 || center >= list.size()) {
            invalidate();
            return;
        }
        lastCenter = center;
        for (int i = 0; i < CARD_COUNT; i++) {
            int songIdx = center + (i - 2); // i=2 为当前
            coverBitmaps[i] = null;
            if (songIdx >= 0 && songIdx < list.size()) {
                final int slot = i;
                final com.jingxin.jingxinmusic.model.Song song = list.get(songIdx);
                post(() -> {
                    if (getContext() == null) return;
                    // 尺寸按当前卡片需加载的最大值
                    int req = Math.max(w, h) / 2;
                    if (req <= 0) req = 300;
                    CoverLoader.load(getContext(), song, req, req, true, coverExecutor, new CoverLoader.CoverCallback() {
                        @Override
                        public void onCoverLoaded(Bitmap coverBitmap) {
                            if (coverBitmaps[slot] != null && !coverBitmaps[slot].isRecycled()
                                    && coverBitmaps[slot] != coverBitmap) {
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
        invalidate();
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
        // 整体宽 50% 屏宽，5 卡重叠 30%：总宽 = 卡宽 + 4×卡宽×(1-0.30)
        // => 卡宽 = 整体宽 / (1 + 4×0.70) = W50% / 3.8；垂直位置受屏高约束防溢出
        float flowW = w * 0.50f;
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
        int[] drawOrder = new int[]{0, 4, 1, 3, 2};
        for (int idx : drawOrder) {
            int offset = idx - 2; // 0=当前
            boolean isCenter = offset == 0;
            // 中心卡透视放大 1.18 → 缩小 10%（实测反馈）：1.18 × 0.90 ≈ 1.06
            float scale = isCenter ? 1.06f : 1f;
            float size = cardSize * scale;
            float px = cx + offset * step;
            // 阳弧落位：中心卡底边锚定屏幕底（完整显示、贴底，不下沉补偿）；
            // 侧卡以中心卡基准位置为参照逐步下沉（|offset| 越大越低）
            float py = isCenter ? baseBottom
                    : baseBottom + Math.abs(offset) * dropPerStep;
            // 旋转：中心 0°，内层 ∓5°，外层 ∓10°（左逆时针=负，右顺时针=正）
            float rotation = isCenter ? 0f : (offset < 0 ? -1f : 1f) * (Math.abs(offset) == 1 ? 5f : 10f);

            drawCard(canvas, coverBitmaps[idx], px, py, size, rotation, isCenter, density);
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
