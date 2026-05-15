package com.jingxin.jingxinmusic.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * 横屏沉浸渐变过渡层（放在 coverView 上层）
 * 左侧65% — 遮罩色实心
 * 65%~85% — 水平渐变：遮罩色→透明
 * 右侧15% — 完全透明
 */
public class LandscapeGradientOverlay extends View {

    private Paint paintSolid;
    private Paint paintGrad;
    private int overlayColor;

    public LandscapeGradientOverlay(Context context) {
        super(context);
        init();
    }

    public LandscapeGradientOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public LandscapeGradientOverlay(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        paintSolid = new Paint(Paint.ANTI_ALIAS_FLAG);
        paintGrad = new Paint(Paint.ANTI_ALIAS_FLAG);
    }

    public void setOverlayColor(int color) {
        this.overlayColor = color;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        int leftEnd = w - (int) (w * 0.35f);
        int gradEnd = (int) (w * 0.85f);

        // 左侧65%实色
        paintSolid.setColor(overlayColor);
        canvas.drawRect(0, 0, leftEnd, h, paintSolid);

        // 65%~85%渐变：遮罩色→透明（保持RGB不变，只过渡alpha）
        int transparentColor = 0x00000000 | (overlayColor & 0x00FFFFFF);
        paintGrad.setShader(new LinearGradient(leftEnd, 0, gradEnd, 0,
                overlayColor, transparentColor, Shader.TileMode.CLAMP));
        canvas.drawRect(leftEnd, 0, gradEnd, h, paintGrad);

        // 右侧15%不绘制（透明）
    }
}
