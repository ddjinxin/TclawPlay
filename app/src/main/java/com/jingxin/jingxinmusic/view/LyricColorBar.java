package com.jingxin.jingxinmusic.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 水平渐变色条，点击/滑动选择颜色（HSV 色相 0~360）
 */
public class LyricColorBar extends View {

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint indicatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF roundRect = new RectF();

    private int selectedColor;
    private float selectedRatio;   // 0~1，对应色条位置
    private OnColorChangeListener listener;

    private static final float CORNER_RADIUS = 16f;

    public interface OnColorChangeListener {
        void onColorChanged(int color);
    }

    public void setOnColorChangeListener(OnColorChangeListener l) {
        this.listener = l;
    }

    public void setColor(int color) {
        this.selectedColor = color;
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        // 反算位置时排除接近灰色的颜色（S<0.1），保持位置不变
        if (hsv[1] >= 0.1f) {
            selectedRatio = hsv[0] / 360f;
        } else if (hsv[2] >= 0.9f) {
            // 白色映射到末尾
            selectedRatio = 1f;
        } else {
            selectedRatio = 0f;
        }
        invalidate();
    }

    public int getColor() {
        return selectedColor;
    }

    public LyricColorBar(Context context) {
        super(context);
        init();
    }

    public LyricColorBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        indicatorPaint.setColor(0xFFFFFFFF);
        indicatorPaint.setStyle(Paint.Style.STROKE);
        indicatorPaint.setStrokeWidth(3f);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        int[] colors = new int[13];
        for (int i = 0; i <= 12; i++) {
            colors[i] = Color.HSVToColor(new float[]{i * 30f, 1f, 1f});
        }
        barPaint.setShader(new LinearGradient(0, 0, w, 0, colors, null, Shader.TileMode.CLAMP));
        roundRect.set(2, 2, w - 2, h - 2);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawRoundRect(roundRect, CORNER_RADIUS, CORNER_RADIUS, barPaint);

        // 选中位置指示器：竖线 + 上下白色圆弧标记
        float x = 2 + selectedRatio * (getWidth() - 4);
        float top = 2;
        float bottom = getHeight() - 2;

        // 白色竖线
        indicatorPaint.setStyle(Paint.Style.STROKE);
        canvas.drawLine(x, top + 4, x, bottom - 4, indicatorPaint);

        // 顶部小圆点
        indicatorPaint.setStyle(Paint.Style.FILL);
        indicatorPaint.setColor(0xFFFFFFFF);
        canvas.drawCircle(x, (top + bottom) / 2f, 6f, indicatorPaint);
        // 中心透出选中色
        indicatorPaint.setColor(selectedColor);
        canvas.drawCircle(x, (top + bottom) / 2f, 3f, indicatorPaint);
        indicatorPaint.setColor(0xFFFFFFFF);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (getWidth() <= 0) return false;
        float ratio = (event.getX() - 2) / (getWidth() - 4);
        ratio = Math.max(0f, Math.min(1f, ratio));
        if (ratio != selectedRatio) {
            selectedRatio = ratio;
            int hue = (int) (ratio * 360f);
            selectedColor = Color.HSVToColor(new float[]{hue, 1f, 1f});
            invalidate();
            if (listener != null) listener.onColorChanged(selectedColor);
        }
        if (event.getAction() == MotionEvent.ACTION_UP) {
            getParent().requestDisallowInterceptTouchEvent(false);
        } else {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        return true;
    }
}
