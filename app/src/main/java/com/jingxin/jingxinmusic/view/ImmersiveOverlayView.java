package com.jingxin.jingxinmusic.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import com.jingxin.jingxinmusic.util.ThemeColors;

/**
 * 沉浸封面模式的渐变遮罩层
 *
 * 竖屏模式（从上到下）：
 * 1. 顶部遮罩 (0-37dp实色, 37-62dp渐变)
 * 2. 封面可见区 (15%-28%)：不绘制，封面原图透出
 * 3. 过渡区 (28%-43%)：透明→主色调渐变
 * 4. 主色调区 (43%-100%)：主色调实色
 *
 * 横屏模式：全屏主色调实色遮罩 + 封面四边渐变过渡
 */
public class ImmersiveOverlayView extends View {

    private Paint paintTop;        // 顶部遮罩
    private Paint paintTransition; // 过渡区
    private Paint paintDominant;   // 主色调填充

    // 封面主色调（从封面提取）
    private int dominantColor = ThemeColors.DOMINANT_DEFAULT;

    // 是否白天模式
    private boolean isNight = true;

    // 是否全屏歌词模式
    private boolean fullScreenMode = false;

    // 是否横屏模式
    private boolean landscapeMode = false;

    // 是否光晕模式：遮罩色用光晕底色（白天白/夜间深），不用封面主色
    private boolean auroraMode = false;

    public ImmersiveOverlayView(Context context) {
        super(context);
        init();
    }

    public ImmersiveOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ImmersiveOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        paintTop = new Paint(Paint.ANTI_ALIAS_FLAG);
        paintTransition = new Paint(Paint.ANTI_ALIAS_FLAG);
        paintDominant = new Paint(Paint.ANTI_ALIAS_FLAG);

        setNightMode(true);
    }

    public void setNightMode(boolean isNight) {
        this.isNight = isNight;
        invalidate();
    }

    public void setDominantColor(int color) {
        this.dominantColor = color;
        invalidate();
    }

    public int getDominantColor() {
        return this.dominantColor;
    }

    public void setFullScreenMode(boolean fullScreen) {
        this.fullScreenMode = fullScreen;
        invalidate();
    }

    public void setLandscapeMode(boolean landscape) {
        this.landscapeMode = landscape;
        invalidate();
    }

    public void setAuroraMode(boolean aurora) {
        if (this.auroraMode != aurora) {
            this.auroraMode = aurora;
            invalidate();
        }
    }

    /**
     * 实际绘制遮罩色：光晕模式用光晕底色（白天白/夜间深），否则用封面主色
     */
    private int getEffectiveOverlayColor() {
        if (auroraMode) {
            return isNight ? AuroraGradientView.BG_NIGHT : AuroraGradientView.BG_DAY;
        }
        return getOverlayColor();
    }

    /**
     * 实际绘制透明色：光晕模式纯透明，否则保留原白天透明浅蓝
     */
    private int getEffectiveTransparentColor() {
        if (auroraMode) {
            return 0x00000000;
        }
        return getTransparentColor();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        if (landscapeMode) {
            drawLandscapeGradient(canvas, w, h);
        } else if (fullScreenMode) {
            drawFullScreenGradient(canvas, w, h);
        } else {
            drawNormalGradient(canvas, w, h);
        }
    }

    public int getOverlayColor() {
        if (isNight) {
            return 0xFF000000 | (dominantColor & 0x00FFFFFF);
        } else {
            // 白天模式：封面主色向浅蓝色偏移50%
            int shiftTarget = ThemeColors.OVERLAY_DAY_SHIFT;
            int blueR = (shiftTarget >> 16) & 0xFF;
            int blueG = (shiftTarget >> 8) & 0xFF;
            int blueB = shiftTarget & 0xFF;
            int r = (int) ((dominantColor >> 16 & 0xFF) * 0.5f + blueR * 0.5f);
            int g = (int) ((dominantColor >> 8 & 0xFF) * 0.5f + blueG * 0.5f);
            int b = (int) ((dominantColor & 0xFF) * 0.5f + blueB * 0.5f);
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }
    }

    private int getTransparentColor() {
        return isNight ? 0x00000000 : (0x00000000 | (ThemeColors.OVERLAY_DAY_SHIFT & 0x00FFFFFF)); // 白天：透明浅蓝
    }

    /**
     * 横屏沉浸模式（底层面）：左边55%主色调实色，右边不绘制
     */
    private void drawLandscapeGradient(Canvas canvas, int w, int h) {
        int overlayColor = getEffectiveOverlayColor();
        // 用 w - (int)(w * 0.45f) 计算，与封面左边缘算法一致，避免浮点取整差1px露出黑线
        int leftEnd = w - (int) (w * 0.45f);
        paintDominant.setColor(overlayColor);
        paintDominant.setStyle(Paint.Style.FILL);
        canvas.drawRect(0, 0, leftEnd + 1, h, paintDominant); // +1px 与封面左缘重叠消取整亮缝（对齐时被封面盖住，无视觉变化）
        // 右边45%不绘制（透明），封面不受底层遮罩影响
    }

    private void drawNormalGradient(Canvas canvas, int w, int h) {
        int overlayColor = getEffectiveOverlayColor();
        int transparentColor = getEffectiveTransparentColor();

        // 0~15%：遮罩色→透明垂直线性渐变（顶部压色渐隐）
        int topEnd = (int) (h * 0.15f);
        paintTop.setShader(new LinearGradient(0, 0, 0, topEnd,
                overlayColor, transparentColor, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, topEnd, paintTop);

        // 15%~33%：不绘制（封面中段纯透出，中间无渐变）
        // 33%~43%：透明→主色调垂直线性渐变（封面下缘融入）
        int transStart = (int) (h * 0.33f);
        int transEnd = (int) (h * 0.43f);
        paintTransition.setShader(new LinearGradient(0, transStart, 0, transEnd,
                transparentColor, overlayColor, Shader.TileMode.CLAMP));
        canvas.drawRect(0, transStart, w, transEnd, paintTransition);

        // 43%~100%：主色调实色
        paintDominant.setColor(overlayColor);
        paintDominant.setStyle(Paint.Style.FILL);
        canvas.drawRect(0, transEnd, w, h, paintDominant);
    }

    private void drawFullScreenGradient(Canvas canvas, int w, int h) {
        int overlayColor = getEffectiveOverlayColor();

        int transStart = (int) (h * 0.25f);
        int transEnd = (int) (h * 0.38f);
        paintTransition.setShader(new LinearGradient(0, transStart, 0, transEnd,
                getEffectiveTransparentColor(), overlayColor, Shader.TileMode.CLAMP));
        canvas.drawRect(0, transStart, w, transEnd, paintTransition);

        paintDominant.setColor(overlayColor);
        paintDominant.setStyle(Paint.Style.FILL);
        canvas.drawRect(0, transEnd, w, h, paintDominant);
    }
}
