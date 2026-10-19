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
 * 左侧65% — 遮罩色实心（光晕模式下不绘制，透出光晕）
 * 65%~85% — 水平渐变：遮罩色→透明
 * 右侧15% — 完全透明
 */
public class LandscapeGradientOverlay extends View {

    private Paint paintSolid;
    private Paint paintGrad;
    private int overlayColor;
    private boolean solidLeft = true;

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

    /**
     * 光晕模式：左侧65%实色不绘制（透出光晕），只保留封面边缘渐变带
     */
    public void setSolidLeft(boolean solid) {
        if (this.solidLeft != solid) {
            this.solidLeft = solid;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        int leftEnd = w - (int) (w * 0.35f);       // 左实色边界 65%
        int gradEnd = (int) (w * 0.75f);           // 左渐变带终点 75%
        int gradRightStart = (int) (w * 0.90f);    // 右渐变带起点 90%

        // 左侧65%实色（光晕模式下不绘制，透出光晕）
        if (solidLeft) {
            paintSolid.setColor(overlayColor);
            canvas.drawRect(0, 0, leftEnd, h, paintSolid);
        }

        int transparentColor = 0x00000000 | (overlayColor & 0x00FFFFFF);

        // 65%~75%渐变：遮罩色→透明（封面左缘融入）
        paintGrad.setShader(new LinearGradient(leftEnd, 0, gradEnd, 0,
                overlayColor, transparentColor, Shader.TileMode.CLAMP));
        canvas.drawRect(leftEnd, 0, gradEnd, h, paintGrad);

        // 75%~90%：不绘制（封面中段纯透出，中间无渐变）

        // 90%~100%渐变：透明→遮罩色（封面右缘渐入遮罩）
        paintGrad.setShader(new LinearGradient(gradRightStart, 0, w, 0,
                transparentColor, overlayColor, Shader.TileMode.CLAMP));
        canvas.drawRect(gradRightStart, 0, w, h, paintGrad);
    }
}
