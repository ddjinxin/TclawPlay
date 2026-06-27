package com.jingxin.jingxinmusic.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.OvershootInterpolator;

/**
 * 唱臂视图（唱片机风格）
 * 
 * 竖屏：pivot在封面右侧，唱臂向下延伸
 *   - 停止：0°（竖直，唱头远离封面中心）
 *   - 播放：+45°顺时针（唱头伸到封面上）
 * 
 * 横屏：pivot在封面上方，唱臂向下延伸
 *   - 播放：0°（竖直向下，唱头在封面上）
 *   - 停止：+45°顺时针（唱臂向右偏，离开封面）
 * 
 * 所有尺寸基于View自身宽高，不被固定dp限制
 */
public class TonearmView extends View {

    private static final float ANGLE_PLAY_PORTRAIT = 45f;  // 竖屏播放旋转角度
    private static final float ANGLE_PLAY_LANDSCAPE = 60f; // 横屏停止旋转角度
    private static final float ANGLE_STOP = 0f;

    private float currentAngle = ANGLE_STOP;
    private float targetAngle = ANGLE_STOP;
    private boolean isPlaying = false;
    private ValueAnimator armAnimator;

    private boolean isLandscape = false;
    // 唱臂绘制基准尺寸（基于封面大小，而非View大小）
    private int coverBasedUnit = 0;

    private Paint basePaint;
    private Paint armPaint;
    private Paint armHighlightPaint;
    private Paint jointPaint;
    private Paint headShellPaint;
    private Paint stylusPaint;
    private Paint counterWeightPaint;
    private Paint counterRingPaint;
    private Paint shadowPaint;

    private boolean isNightMode = true;

    public TonearmView(Context context) { super(context); init(); }
    public TonearmView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public TonearmView(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(); }

    private void init() {
        basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        basePaint.setStyle(Paint.Style.FILL);

        armPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        armPaint.setStyle(Paint.Style.STROKE);
        armPaint.setStrokeCap(Paint.Cap.ROUND);

        armHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        armHighlightPaint.setStyle(Paint.Style.STROKE);
        armHighlightPaint.setStrokeCap(Paint.Cap.ROUND);

        jointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        jointPaint.setStyle(Paint.Style.FILL);

        headShellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        headShellPaint.setStyle(Paint.Style.FILL);

        stylusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        stylusPaint.setStyle(Paint.Style.STROKE);
        stylusPaint.setStrokeCap(Paint.Cap.ROUND);

        counterWeightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        counterWeightPaint.setStyle(Paint.Style.FILL);

        counterRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        counterRingPaint.setStyle(Paint.Style.STROKE);
        counterRingPaint.setStrokeCap(Paint.Cap.ROUND);

        shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadowPaint.setStyle(Paint.Style.FILL);

        applyTheme();
    }

    private void applyTheme() {
        if (isNightMode) {
            basePaint.setColor(Color.parseColor("#8A8A8A"));
            armPaint.setColor(Color.parseColor("#C0C0C0"));
            armHighlightPaint.setColor(Color.parseColor("#E0E0E0"));
            jointPaint.setColor(Color.parseColor("#A0A0A0"));
            headShellPaint.setColor(Color.parseColor("#B0B0B0"));
            stylusPaint.setColor(Color.parseColor("#CCCCCC"));
            counterWeightPaint.setColor(Color.parseColor("#707070"));
            counterRingPaint.setColor(Color.parseColor("#555555"));
            shadowPaint.setColor(Color.parseColor("#20000000"));
        } else {
            basePaint.setColor(Color.parseColor("#606060"));
            armPaint.setColor(Color.parseColor("#888888"));
            armHighlightPaint.setColor(Color.parseColor("#AAAAAA"));
            jointPaint.setColor(Color.parseColor("#707070"));
            headShellPaint.setColor(Color.parseColor("#808080"));
            stylusPaint.setColor(Color.parseColor("#666666"));
            counterWeightPaint.setColor(Color.parseColor("#505050"));
            counterRingPaint.setColor(Color.parseColor("#404040"));
            shadowPaint.setColor(Color.parseColor("#15000000"));
        }
    }

    public void setNightMode(boolean night) {
        this.isNightMode = night;
        applyTheme();
        invalidate();
    }

    public void setLandscapeMode(boolean landscape) {
        this.isLandscape = landscape;
        invalidate();
    }

    /**
     * 设置封面尺寸，唱臂绘制基于此（而非View自身大小）
     * 竖屏和横屏使用不同比例
     */
    public void setCoverSize(int coverSize) {
        if (coverSize <= 0) return;
        // 竖屏：唱臂偏小，比例约1/18
        // 横屏：比例约1/21
        this.coverBasedUnit = isLandscape ? coverSize / 21 : coverSize / 18;
        invalidate();
    }

    public void setPlaying(boolean playing) {
        if (this.isPlaying == playing) return;
        this.isPlaying = playing;
        if (isLandscape) {
            // 横屏：播放=0°（落针），停止=60°（抬起）
            targetAngle = playing ? ANGLE_STOP : ANGLE_PLAY_LANDSCAPE;
        } else {
            // 竖屏：停止=0°（竖直），播放=45°（唱头伸到封面上）
            targetAngle = playing ? ANGLE_PLAY_PORTRAIT : ANGLE_STOP;
        }
        animateArm();
    }

    /**
     * 刷新唱臂角度（用于横竖屏切换后重新应用当前播放状态）
     */
    public void refreshAngle() {
        if (isLandscape) {
            targetAngle = isPlaying ? ANGLE_STOP : ANGLE_PLAY_LANDSCAPE;
        } else {
            targetAngle = isPlaying ? ANGLE_PLAY_PORTRAIT : ANGLE_STOP;
        }
        // 直接设置角度，不动画
        currentAngle = targetAngle;
        invalidate();
    }

    private void animateArm() {
        if (armAnimator != null && armAnimator.isRunning()) {
            armAnimator.cancel();
        }
        armAnimator = ValueAnimator.ofFloat(currentAngle, targetAngle);
        armAnimator.setDuration(400);
        armAnimator.setInterpolator(new OvershootInterpolator(0.8f));
        armAnimator.addUpdateListener(anim -> {
            currentAngle = (float) anim.getAnimatedValue();
            invalidate();
        });
        armAnimator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        // 唱臂绘制基准：基于封面大小（coverBasedUnit），而非View大小
        // View可能很大（旋转空间），但唱臂大小应跟封面匹配
        float unit;
        if (coverBasedUnit > 0) {
            unit = coverBasedUnit;
        } else {
            // fallback：没设置coverSize时用View大小
            unit = Math.min(w, h) / 10f;
        }

        float baseRadius = 1.0f * unit;      // 底座半径
        float armLength = 5.5f * unit;        // 主臂杆长度
        float armWidth = 0.35f * unit;        // 臂杆粗细
        float highlightWidth = 0.12f * unit;  // 高光线粗细
        float jointRadius = 0.5f * unit;      // 关节半径
        float bendLength = 1.5f * unit;       // 弯折段长度
        float bendAngle = 35f;
        float headWidth = 0.8f * unit;        // 唱头壳宽
        float headHeight = 1.3f * unit;       // 唱头壳高
        float stylusLength = 0.6f * unit;     // 唱针长度
        float cwRadius = 0.7f * unit;         // 配重半径
        float cwDistance = 1.8f * unit;       // 配重到底座距离
        float cwRingOffset = 0.25f * unit;    // 配重环纹偏移

        // pivot 点位置
        float pivotX, pivotY;
        if (isLandscape) {
            // 横屏：pivot在View顶部居中，下移确保配重球完整显示
            // 配重顶到pivot距离 = cwDistance + cwRadius = 2.5*unit，留0.3*unit余量
            pivotX = w / 2f;
            pivotY = cwDistance + cwRadius + 0.3f * unit;
        } else {
            // 竖屏：pivot在右侧区域，下移确保配重球完整显示
            // 右边留足旋转空间：0.707*cwDistance + cwRadius ≈ 2*unit，再加余量
            pivotX = w - (0.707f * cwDistance + cwRadius + 0.5f * unit);
            pivotY = cwDistance + cwRadius + 0.3f * unit;
        }

        // ---- 阴影 ----
        canvas.save();
        canvas.translate(0.2f * unit, 0.3f * unit);
        drawArmShape(canvas, pivotX, pivotY, armLength, armWidth, jointRadius,
                bendLength, bendAngle, headWidth, headHeight, cwRadius, cwDistance,
                shadowPaint, shadowPaint, shadowPaint, shadowPaint, shadowPaint);
        canvas.restore();

        // ---- 唱臂本体 ----
        canvas.save();
        canvas.rotate(currentAngle, pivotX, pivotY);

        // 配重
        float cwX = pivotX;
        float cwY = pivotY - cwDistance;
        canvas.drawCircle(cwX, cwY, cwRadius, counterWeightPaint);
        counterRingPaint.setStrokeWidth(0.12f * unit);
        for (int i = 0; i < 3; i++) {
            float r = cwRadius - (i + 1) * cwRingOffset / 3f;
            if (r > 0) canvas.drawCircle(cwX, cwY, r, counterRingPaint);
        }
        // 配重连接杆
        armPaint.setStrokeWidth(armWidth * 0.7f);
        armPaint.setShader(null);
        armPaint.setColor(isNightMode ? Color.parseColor("#C0C0C0") : Color.parseColor("#888888"));
        canvas.drawLine(pivotX, pivotY, cwX, cwY + cwRadius, armPaint);

        // 主臂杆
        float armEndX = pivotX;
        float armEndY = pivotY + armLength;

        LinearGradient armGradient = new LinearGradient(
                pivotX, pivotY, armEndX, armEndY,
                isNightMode ? Color.parseColor("#D0D0D0") : Color.parseColor("#999999"),
                isNightMode ? Color.parseColor("#A0A0A0") : Color.parseColor("#777777"),
                Shader.TileMode.CLAMP);
        armPaint.setShader(armGradient);
        armPaint.setStrokeWidth(armWidth);
        canvas.drawLine(pivotX, pivotY, armEndX, armEndY, armPaint);
        armPaint.setShader(null);

        // 臂杆高光线
        armHighlightPaint.setStrokeWidth(highlightWidth);
        float hlOffset = 0.1f * unit;
        canvas.drawLine(pivotX - hlOffset, pivotY + 0.3f * unit,
                armEndX - hlOffset, armEndY - 0.3f * unit, armHighlightPaint);

        // 关节圆
        canvas.drawCircle(armEndX, armEndY, jointRadius, jointPaint);
        Paint jointDot = new Paint(Paint.ANTI_ALIAS_FLAG);
        jointDot.setColor(isNightMode ? Color.parseColor("#D0D0D0") : Color.parseColor("#AAAAAA"));
        canvas.drawCircle(armEndX, armEndY, jointRadius * 0.35f, jointDot);

        // 弯折段
        double bendRad = Math.toRadians(bendAngle);
        float bendEndX = armEndX + (float) Math.sin(bendRad) * bendLength;
        float bendEndY = armEndY + (float) Math.cos(bendRad) * bendLength;
        armPaint.setStrokeWidth(armWidth * 0.85f);
        armPaint.setShader(null);
        armPaint.setColor(isNightMode ? Color.parseColor("#B0B0B0") : Color.parseColor("#888888"));
        canvas.drawLine(armEndX, armEndY, bendEndX, bendEndY, armPaint);

        // 唱头壳
        canvas.save();
        canvas.translate(bendEndX, bendEndY);
        canvas.rotate(bendAngle);
        RectF headRect = new RectF(-headWidth / 2, 0, headWidth / 2, headHeight);
        LinearGradient headGradient = new LinearGradient(
                -headWidth / 2, 0, headWidth / 2, 0,
                isNightMode ? Color.parseColor("#C8C8C8") : Color.parseColor("#909090"),
                isNightMode ? Color.parseColor("#A0A0A0") : Color.parseColor("#707070"),
                Shader.TileMode.CLAMP);
        headShellPaint.setShader(headGradient);
        canvas.drawRoundRect(headRect, 0.15f * unit, 0.15f * unit, headShellPaint);
        headShellPaint.setShader(null);

        // 唱头壳标记线
        Paint markPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        markPaint.setColor(isNightMode ? Color.parseColor("#888888") : Color.parseColor("#666666"));
        markPaint.setStrokeWidth(0.08f * unit);
        canvas.drawLine(-headWidth / 4, headHeight * 0.3f, headWidth / 4, headHeight * 0.3f, markPaint);
        canvas.drawLine(-headWidth / 4, headHeight * 0.6f, headWidth / 4, headHeight * 0.6f, markPaint);

        // 唱针
        stylusPaint.setStrokeWidth(0.1f * unit);
        canvas.drawLine(0, headHeight, 0, headHeight + stylusLength, stylusPaint);

        canvas.restore(); // 唱头壳旋转
        canvas.restore(); // 唱臂整体旋转

        // 底座（最顶层）
        Paint baseShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        baseShadow.setColor(Color.parseColor("#30000000"));
        canvas.drawCircle(pivotX + 0.15f * unit, pivotY + 0.15f * unit, baseRadius + 0.15f * unit, baseShadow);

        LinearGradient baseGradient = new LinearGradient(
                pivotX - baseRadius, pivotY - baseRadius,
                pivotX + baseRadius, pivotY + baseRadius,
                isNightMode ? Color.parseColor("#B0B0B0") : Color.parseColor("#808080"),
                isNightMode ? Color.parseColor("#808080") : Color.parseColor("#505050"),
                Shader.TileMode.CLAMP);
        basePaint.setShader(baseGradient);
        canvas.drawCircle(pivotX, pivotY, baseRadius, basePaint);
        basePaint.setShader(null);

        Paint centerDot = new Paint(Paint.ANTI_ALIAS_FLAG);
        centerDot.setColor(isNightMode ? Color.parseColor("#606060") : Color.parseColor("#404040"));
        canvas.drawCircle(pivotX, pivotY, baseRadius * 0.35f, centerDot);

        Paint baseRing = new Paint(Paint.ANTI_ALIAS_FLAG);
        baseRing.setColor(isNightMode ? Color.parseColor("#909090") : Color.parseColor("#606060"));
        baseRing.setStyle(Paint.Style.STROKE);
        baseRing.setStrokeWidth(0.08f * unit);
        canvas.drawCircle(pivotX, pivotY, baseRadius * 0.65f, baseRing);
    }

    private void drawArmShape(Canvas canvas, float pivotX, float pivotY,
                              float armLength, float armWidth, float jointRadius,
                              float bendLength, float bendAngle,
                              float headWidth, float headHeight,
                              float cwRadius, float cwDistance,
                              Paint armP, Paint jointP, Paint headP, Paint cwP, Paint stylusP) {
        canvas.save();
        canvas.rotate(currentAngle, pivotX, pivotY);

        float armEndX = pivotX;
        float armEndY = pivotY + armLength;
        float cwY = pivotY - cwDistance;

        canvas.drawCircle(pivotX, cwY, cwRadius, cwP);
        armP.setStrokeWidth(armWidth + 2);
        canvas.drawLine(pivotX, pivotY, armEndX, armEndY, armP);
        canvas.drawCircle(armEndX, armEndY, jointRadius, jointP);

        double bendRad = Math.toRadians(bendAngle);
        float bendEndX = armEndX + (float) Math.sin(bendRad) * bendLength;
        float bendEndY = armEndY + (float) Math.cos(bendRad) * bendLength;
        canvas.drawLine(armEndX, armEndY, bendEndX, bendEndY, armP);

        canvas.save();
        canvas.translate(bendEndX, bendEndY);
        canvas.rotate(bendAngle);
        RectF headRect = new RectF(-headWidth / 2, 0, headWidth / 2, headHeight);
        canvas.drawRoundRect(headRect, 1, 1, headP);
        canvas.restore();

        canvas.restore();
    }
}
