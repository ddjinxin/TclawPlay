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
 * 左侧55% — 遮罩色实心（光晕模式下不绘制，透出光晕）
 * 55%~65% — 水平渐变：遮罩色→透明（封面左缘融入）
 * 65%~90% — 完全透明（封面中段纯透出）
 * 90%~100% — 水平渐变：透明→遮罩色（封面右缘渐入）
 * 注：白天光晕下本组件被页面 GONE（静态色追不上逐帧光晕，过渡由封面左缘淡出接管）
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
     * 光晕模式：左侧55%实色不绘制（透出光晕），只保留封面边缘渐变带
     * （白天光晕下本组件由页面整体隐藏，过渡交给封面左缘淡出+动态光晕）
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

        int leftEnd = w - (int) (w * 0.45f);       // 左实色边界 55%（封面列左缘，同步加宽 45%）
        int gradEnd = leftEnd + (int) (w * 0.10f);  // 左渐变带终点（边界后 10% 宽渐隐）
        int gradRightStart = (int) (w * 0.90f);    // 右渐变带起点 90%

        // 左侧55%实色（光晕模式下不绘制，透出光晕）
        if (solidLeft) {
            paintSolid.setColor(overlayColor);
            canvas.drawRect(0, 0, leftEnd, h, paintSolid);
        }

        int transparentColor = 0x00000000 | (overlayColor & 0x00FFFFFF);

        // 55%~65%渐变：遮罩色→透明（封面左缘融入）
        paintGrad.setShader(new LinearGradient(leftEnd, 0, gradEnd, 0,
                overlayColor, transparentColor, Shader.TileMode.CLAMP));
        canvas.drawRect(leftEnd, 0, gradEnd, h, paintGrad);

        // 65%~90%：不绘制（封面中段纯透出，中间无渐变）

        // 90%~100%渐变：透明→遮罩色（封面右缘渐入遮罩）
        paintGrad.setShader(new LinearGradient(gradRightStart, 0, w, 0,
                transparentColor, overlayColor, Shader.TileMode.CLAMP));
        canvas.drawRect(gradRightStart, 0, w, h, paintGrad);
    }
}
