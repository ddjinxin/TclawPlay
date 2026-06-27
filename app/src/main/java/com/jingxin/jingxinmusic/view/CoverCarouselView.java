package com.jingxin.jingxinmusic.view;

import android.content.Context;
import android.graphics.Outline;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.jingxin.jingxinmusic.R;

import java.util.concurrent.ExecutorService;

/**
 * 5张封面卡片轮播组件
 *
 * 布局规则：
 * - 中间卡片完整显示，scale=1.0，最顶层(Z最高)
 * - 左一/右一 scale=0.85，被中间遮挡
 * - 左二/右二 scale=0.7，被左一/右一遮挡
 * - Z轴层级：左二/右二(底) → 左一/右一 → 中间(顶)
 * - 滑动/点击切歌：瞬间切换，无过渡动画
 */
public class CoverCarouselView extends FrameLayout {

    // 5张卡片的ImageView，索引0=左二, 1=左一, 2=中间, 3=右一, 4=右二
    private final ImageView[] cards = new ImageView[5];

    // 缩放参数
    private static final float CENTER_SCALE = 1.0f;
    private static final float SIDE_SCALE = 0.85f;
    private static final float FAR_SCALE = 0.70f;

    // 重叠比例，可配置
    private float overlapRatio = 0.30f;

    // 圆角dp
    private static final float CORNER_RADIUS_DP = 14f;

    private int coverSize;
    private float density;
    private ExecutorService executor;

    // 回调
    public interface OnSongChangeListener {
        void onSongChange(int delta);
    }
    private OnSongChangeListener onSongChangeListener;

    // ===== 触摸事件：滑动手势 =====

    private float touchDownX;
    private float touchDownY;
    private boolean isDragging;
    private int touchSlop;

    public CoverCarouselView(@NonNull Context context) {
        super(context);
        init(context);
    }

    public CoverCarouselView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public CoverCarouselView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context);
    }

    private void init(Context context) {
        density = context.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setClipChildren(false);
        setClipToPadding(false);

        int cornerPx = (int) (CORNER_RADIUS_DP * density);
        ViewOutlineProvider roundOutline = new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerPx);
            }
        };

        // 创建5张卡片，添加顺序决定Z轴：先add在下层
        int[] addOrder = {0, 4, 1, 3, 2};
        for (int idx : addOrder) {
            ImageView iv = new ImageView(context);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageResource(R.drawable.ic_music_icon);
            iv.setOutlineProvider(roundOutline);
            iv.setClipToOutline(true);
            iv.setElevation((int) (6 * density));
            iv.setTag(idx);
            iv.setClickable(true);
            iv.setOnClickListener(v -> {
                int cardIdx = (int) v.getTag();
                if (cardIdx != 2 && onSongChangeListener != null) {
                    int delta;
                    if (cardIdx == 0) delta = -2;
                    else if (cardIdx == 1) delta = -1;
                    else if (cardIdx == 3) delta = 1;
                    else delta = 2;
                    onSongChangeListener.onSongChange(delta);
                }
            });
            cards[idx] = iv;
            addView(iv);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDownX = ev.getX();
                touchDownY = ev.getY();
                isDragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!isDragging) {
                    float dx = Math.abs(ev.getX() - touchDownX);
                    float dy = Math.abs(ev.getY() - touchDownY);
                    if (dx > touchSlop && dx > dy) {
                        isDragging = true;
                    }
                }
                break;
        }
        return isDragging;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDownX = ev.getX();
                touchDownY = ev.getY();
                isDragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!isDragging) {
                    float dx = Math.abs(ev.getX() - touchDownX);
                    float dy = Math.abs(ev.getY() - touchDownY);
                    if (dx > touchSlop && dx > dy) {
                        isDragging = true;
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (isDragging && onSongChangeListener != null) {
                    float dx = ev.getX() - touchDownX;
                    if (Math.abs(dx) > touchSlop) {
                        onSongChangeListener.onSongChange(dx < 0 ? 1 : -1);
                    }
                }
                isDragging = false;
                return true;
        }
        return super.onTouchEvent(ev);
    }

    private float getBaseScale(int index) {
        if (index == 2) return CENTER_SCALE;
        if (index == 1 || index == 3) return SIDE_SCALE;
        return FAR_SCALE;
    }

    private float getBaseAlpha(int index) {
        if (index == 2) return 1.0f;
        if (index == 1 || index == 3) return 0.85f;
        return 0.7f;
    }

    private float getBaseZ(int index) {
        if (index == 2) return 3f;
        if (index == 1 || index == 3) return 2f;
        return 1f;
    }

    // ===== 公共API =====

    public void setCoverSize(int size) {
        this.coverSize = size;
    }

    public int getCoverSize() {
        return coverSize;
    }

    public void setExecutor(ExecutorService executor) {
        this.executor = executor;
    }

    public void setOnSongChangeListener(OnSongChangeListener listener) {
        this.onSongChangeListener = listener;
    }

    public void setOverlapRatio(float ratio) {
        this.overlapRatio = Math.max(0f, Math.min(0.8f, ratio));
    }

    /**
     * 重新布局5张卡片的位置和缩放
     */
    public void layoutCards() {
        if (coverSize <= 0 || getWidth() <= 0) return;

        int centerVisualW = (int) (coverSize * CENTER_SCALE);
        int sideVisualW = (int) (coverSize * SIDE_SCALE);
        int farVisualW = (int) (coverSize * FAR_SCALE);

        int sideVisibleW = (int) (sideVisualW * (1f - overlapRatio));
        int farVisibleW = (int) (farVisualW * (1f - overlapRatio));

        int totalWidth = farVisibleW + sideVisibleW + centerVisualW + sideVisibleW + farVisibleW;
        int viewWidth = getWidth();
        int startX = (viewWidth - totalWidth) / 2;

        int viewHeight = getHeight();
        int centerY = viewHeight / 2;

        float left2Left = startX;
        float left2CenterX = left2Left + farVisualW / 2f;

        float left1Left = startX + farVisibleW;
        float left1CenterX = left1Left + sideVisualW / 2f;

        float centerLeft = startX + farVisibleW + sideVisibleW;
        float centerCenterX = centerLeft + centerVisualW / 2f;

        float right1Left = centerLeft + centerVisualW - (int)(sideVisualW * overlapRatio);
        float right1CenterX = right1Left + sideVisualW / 2f;

        float right2Left = right1Left + sideVisualW - (int)(farVisualW * overlapRatio);
        float right2CenterX = right2Left + farVisualW / 2f;

        layoutCard(cards[0], left2CenterX, centerY, coverSize, FAR_SCALE);
        layoutCard(cards[1], left1CenterX, centerY, coverSize, SIDE_SCALE);
        layoutCard(cards[2], centerCenterX, centerY, coverSize, CENTER_SCALE);
        layoutCard(cards[3], right1CenterX, centerY, coverSize, SIDE_SCALE);
        layoutCard(cards[4], right2CenterX, centerY, coverSize, FAR_SCALE);

        // Z轴和透明度
        for (int i = 0; i < 5; i++) {
            cards[i].setTranslationZ(getBaseZ(i));
            cards[i].setAlpha(getBaseAlpha(i));
        }
    }

    private void layoutCard(ImageView card, float centerX, float centerY, int size, float scale) {
        int left = (int) (centerX - size / 2f);
        int top = (int) (centerY - size / 2f);

        MarginLayoutParams lp = (MarginLayoutParams) card.getLayoutParams();
        if (lp == null) {
            lp = new MarginLayoutParams(size, size);
        }
        lp.width = size;
        lp.height = size;
        lp.leftMargin = left;
        lp.topMargin = top;
        card.setLayoutParams(lp);
        card.setPivotX(size / 2f);
        card.setPivotY(size / 2f);
        card.setScaleX(scale);
        card.setScaleY(scale);
        // 重置拖拽偏移
        card.setTranslationX(0);
    }

    public ImageView[] getCards() {
        return cards;
    }

    public void requestLayoutCards() {
        post(() -> layoutCards());
    }
}
