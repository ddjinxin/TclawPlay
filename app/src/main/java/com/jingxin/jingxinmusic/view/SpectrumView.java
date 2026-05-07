package com.jingxin.jingxinmusic.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

/**
 * 频谱视图
 * 支持三种样式：竖条模式（128条）、圆点模式（64个）、波浪线模式（64点）
 * 点击切换样式
 */
public class SpectrumView extends View {
    
    private static final String TAG = "SpectrumView";
    
    // 频谱样式常量
    private static final int STYLE_BAR = 0;   // 竖条模式
    private static final int STYLE_DOT = 1;   // 圆点模式
    private static final int STYLE_WAVE = 2;  // 波浪线模式
    
    // 竖条模式的频谱条数量
    private static final int BAR_COUNT = 64;
    
    // 圆点/波浪线模式的分量数量
    private static final int DOT_COUNT = 64;
    
    // 当前数据分量数量（根据样式切换）
    private int currentCount = BAR_COUNT;
    
    // 当前样式
    private int currentStyle = STYLE_BAR;
    
    // 竖条宽度
    private float barWidth;
    
    // 间距
    private float barSpacing;
    
    // 频谱条高度数组
    private float[] barHeights;
    private float[] targetBarHeights;
    
    // 画笔
    private Paint barPaint;
    private Paint waveFillPaint;  // 波浪线填充画笔
    private Paint waveStrokePaint; // 波浪线描边画笔
    
    // 竖条模式金黄色（从下到上：深金 → 亮金）
    private int[] gradientColors = {
        Color.parseColor("#E6A817"),  // 深金
        Color.parseColor("#FFD54F")   // 亮金
    };
    
    // 夜间模式金黄色
    private int[] nightGradientColors = {
        Color.parseColor("#D4960F"),  // 深金（夜间稍暗）
        Color.parseColor("#FFCA28")   // 亮金
    };
    
    // 圆点/波浪线渐变颜色（蓝 -> 紫 -> 红）
    private int[] dotWaveColors = {
        Color.parseColor("#4FC3F7"),
        Color.parseColor("#AB47BC"),
        Color.parseColor("#FF5252")
    };
    
    // 圆点/波浪线夜间颜色
    private int[] dotWaveNightColors = {
        Color.parseColor("#4DD0E1"),
        Color.parseColor("#7E57C2"),
        Color.parseColor("#EF5350")
    };
    
    // 夜间模式
    private boolean isNightMode = false;
    
    // 是否正在播放
    private boolean isPlaying = false;
    
    // 动画驱动 Handler
    private Handler animHandler = new Handler(Looper.getMainLooper());
    private Runnable animRunnable;
    private static final int ANIM_INTERVAL_MS = 33; // ~30fps
    private boolean animRunning = false;
    
    public SpectrumView(Context context) {
        super(context);
        init();
    }
    
    public SpectrumView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    public SpectrumView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs);
        init();
    }
    
    /**
     * 初始化
     */
    private void init() {
        barHeights = new float[BAR_COUNT];
        targetBarHeights = new float[BAR_COUNT];
        
        // 竖条/圆点画笔
        barPaint = new Paint();
        barPaint.setAntiAlias(true);
        barPaint.setStyle(Paint.Style.FILL);
        
        // 波浪线填充画笔
        waveFillPaint = new Paint();
        waveFillPaint.setAntiAlias(true);
        waveFillPaint.setStyle(Paint.Style.FILL);
        
        // 波浪线描边画笔
        waveStrokePaint = new Paint();
        waveStrokePaint.setAntiAlias(true);
        waveStrokePaint.setStyle(Paint.Style.STROKE);
        waveStrokePaint.setStrokeWidth(3f);
        waveStrokePaint.setStrokeJoin(Paint.Join.ROUND);
        waveStrokePaint.setStrokeCap(Paint.Cap.ROUND);
        
        barSpacing = 4f;
        
        animRunnable = new Runnable() {
            @Override
            public void run() {
                if (isPlaying && animRunning) {
                    for (int i = 0; i < currentCount; i++) {
                        targetBarHeights[i] *= 0.98f;
                        float minVal = currentStyle == STYLE_BAR ? 4f : 6f;
                        if (targetBarHeights[i] < minVal) {
                            targetBarHeights[i] = minVal;
                        }
                    }
                    invalidate();
                    animHandler.postDelayed(this, ANIM_INTERVAL_MS);
                }
            }
        };
    }
    
    /**
     * 切换频谱样式：竖条 → 圆点 → 波浪线 → 竖条
     */
    private void switchStyle() {
        switch (currentStyle) {
            case STYLE_BAR:
                currentStyle = STYLE_DOT;
                currentCount = DOT_COUNT;
                Log.d(TAG, "切换到圆点模式");
                break;
            case STYLE_DOT:
                currentStyle = STYLE_WAVE;
                currentCount = DOT_COUNT;
                Log.d(TAG, "切换到波浪线模式");
                break;
            case STYLE_WAVE:
            default:
                currentStyle = STYLE_BAR;
                currentCount = BAR_COUNT;
                Log.d(TAG, "切换到竖条模式");
                break;
        }
        float[] newHeights = new float[currentCount];
        float[] newTargets = new float[currentCount];
        barHeights = newHeights;
        targetBarHeights = newTargets;
        requestLayout();
        postInvalidate();
    }
    
    // 是否显示频谱
    private boolean visible = true;
    
    // 双击检测
    private long lastClickTime = 0;
    private static final int DOUBLE_CLICK_INTERVAL = 300; // 300ms 内两次点击视为双击
    
    @Override
    public boolean performClick() {
        super.performClick();
        
        long now = System.currentTimeMillis();
        if (now - lastClickTime < DOUBLE_CLICK_INTERVAL) {
            // 双击：切换频谱显隐
            visible = !visible;
            setVisibility(visible ? VISIBLE : GONE);
            Log.d(TAG, "双击：频谱" + (visible ? "显示" : "隐藏"));
            lastClickTime = 0; // 重置，避免三击触发
        } else {
            // 单击：切换样式
            switchStyle();
            lastClickTime = now;
        }
        return true;
    }
    
    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
            performClick();
        }
        return true;
    }
    
    /**
     * 设置夜间模式
     */
    public void setNightMode(boolean night) {
        isNightMode = night;
        postInvalidate();
    }
    
    /**
     * 启动动画循环
     */
    private void startAnimation() {
        if (!animRunning) {
            animRunning = true;
            animHandler.post(animRunnable);
        }
    }
    
    /**
     * 停止动画循环
     */
    private void stopAnimation() {
        animRunning = false;
        animHandler.removeCallbacks(animRunnable);
    }
    
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float totalSpacing = barSpacing * (currentCount - 1);
        barWidth = (w - totalSpacing) / currentCount;
        // 更新波浪线填充渐变
        int[] colors = isNightMode ? nightGradientColors : gradientColors;
        waveFillPaint.setShader(new LinearGradient(0, 0, 0, h, colors[0], colors[1], Shader.TileMode.CLAMP));
        waveStrokePaint.setColor(colors[0]);
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        if (!isPlaying) {
            drawStatic(canvas);
            return;
        }
        
        switch (currentStyle) {
            case STYLE_DOT:
                drawDots(canvas);
                break;
            case STYLE_WAVE:
                drawWave(canvas);
                break;
            default:
                drawBars(canvas);
                break;
        }
    }
    
    /**
     * 绘制静态频谱（未播放时）
     */
    private void drawStatic(Canvas canvas) {
        boolean isGold = currentStyle == STYLE_BAR;
        int staticColor = getGradientColor(0.05f, isGold);
        
        switch (currentStyle) {
            case STYLE_DOT: {
                int[] colors = isNightMode ? dotWaveNightColors : dotWaveColors;
                float spacing = getWidth() / DOT_COUNT;
                barPaint.setColor(staticColor);
                for (int i = 0; i < DOT_COUNT; i++) {
                    float x = i * spacing + spacing / 2;
                    float radius = 3f;
                    canvas.drawCircle(x, getHeight() - radius, radius, barPaint);
                }
                break;
            }
            case STYLE_WAVE: {
                // 波浪线静态：画一条平直线
                int[] waveColors = isNightMode ? dotWaveNightColors : dotWaveColors;
                float spacing = getWidth() / DOT_COUNT;
                waveFillPaint.setShader(new LinearGradient(0, 0, 0, getHeight(), waveColors[0], waveColors[1], Shader.TileMode.CLAMP));
                waveFillPaint.setColor(Color.argb(80, Color.red(waveColors[0]), Color.green(waveColors[0]), Color.blue(waveColors[0])));
                Path path = new Path();
                path.moveTo(0, getHeight());
                for (int i = 0; i < DOT_COUNT; i++) {
                    float x = i * spacing + spacing / 2;
                    path.lineTo(x, getHeight() - 4f);
                }
                path.lineTo(getWidth(), getHeight());
                path.close();
                canvas.drawPath(path, waveFillPaint);
                break;
            }
            default: {
                barPaint.setColor(staticColor);
                for (int i = 0; i < BAR_COUNT; i++) {
                    float x = i * (barWidth + barSpacing);
                    canvas.drawRect(x, getHeight() - 8f, x + barWidth, getHeight(), barPaint);
                }
                break;
            }
        }
    }
    
    /**
     * 绘制竖条频谱（金黄色）
     */
    private void drawBars(Canvas canvas) {
        int[] colors = isNightMode ? nightGradientColors : gradientColors;
        for (int i = 0; i < currentCount; i++) {
            barHeights[i] += (targetBarHeights[i] - barHeights[i]) * 0.3f;
            
            float x = i * (barWidth + barSpacing);
            float height = barHeights[i];
            float y = getHeight() - height;
            
            float ratio = height / getHeight();
            // 金黄色渐变：底部深金 → 顶部亮金
            float t = Math.min(ratio, 1.0f);
            barPaint.setColor(interpolateColor(colors[0], colors[1], t));
            
            canvas.drawRoundRect(x, y, x + barWidth, getHeight(), 2f, 2f, barPaint);
        }
    }
    
    /**
     * 绘制圆点频谱（64个圆点，对称悬浮跳动，中间高两边低）
     */
    private void drawDots(Canvas canvas) {
        float viewWidth = getWidth();
        float viewHeight = getHeight();
        float spacing = viewWidth / DOT_COUNT;
        float radius = spacing / 2 * 0.9f;
        float minH = radius * 2;
        float maxH = viewHeight * 0.9f;
        float centerIndex = (DOT_COUNT - 1) / 2f;
        int[] colors = isNightMode ? nightGradientColors : gradientColors;
        
        for (int i = 0; i < currentCount; i++) {
            barHeights[i] += (targetBarHeights[i] - barHeights[i]) * 0.3f;
            
            float x = i * spacing + spacing / 2;
            float height = Math.max(minH, barHeights[i]);
            // 对称权重：中间保持100%，两端压低到20%，形成山丘形状
            float distFromCenter = Math.abs(i - centerIndex) / centerIndex;
            float weight = 1.0f - distFromCenter * 0.8f;
            height *= weight;
            if (height > maxH) height = maxH;
            float cy = viewHeight - height;
            
            // 颜色：低处金色，高处红色，根据跳动高度渐变
            float heightRatio = height / maxH;
            int color;
            if (heightRatio < 0.5f) {
                color = interpolateColor(colors[0], colors[1], heightRatio * 2);
            } else {
                color = interpolateColor(colors[1], Color.parseColor("#FF5252"), (heightRatio - 0.5f) * 2);
            }
            barPaint.setColor(color);
            
            canvas.drawCircle(x, cy, radius, barPaint);
        }
    }
    
    /**
     * 绘制波浪线频谱（64点贝塞尔曲线 + 渐变填充）
     */
    private void drawWave(Canvas canvas) {
        float viewWidth = getWidth();
        float viewHeight = getHeight();
        float spacing = viewWidth / DOT_COUNT;
        float maxH = viewHeight * 0.9f;
        
        int[] colors = isNightMode ? dotWaveNightColors : dotWaveColors;
        
        // 更新平滑高度
        for (int i = 0; i < currentCount; i++) {
            barHeights[i] += (targetBarHeights[i] - barHeights[i]) * 0.3f;
        }
        
        // 计算每个数据点的坐标
        float[] pointsX = new float[currentCount];
        float[] pointsY = new float[currentCount];
        for (int i = 0; i < currentCount; i++) {
            pointsX[i] = i * spacing + spacing / 2;
            float h = Math.max(6f, barHeights[i]);
            if (h > maxH) h = maxH;
            pointsY[i] = viewHeight - h;
        }
        
        // 构建贝塞尔曲线路径（填充区域：曲线 → 右下角 → 左下角）
        Path fillPath = new Path();
        fillPath.moveTo(0, viewHeight);
        fillPath.lineTo(pointsX[0], pointsY[0]);
        for (int i = 0; i < currentCount - 1; i++) {
            // 用中点作为控制点，实现平滑曲线
            float midX = (pointsX[i] + pointsX[i + 1]) / 2;
            float midY = (pointsY[i] + pointsY[i + 1]) / 2;
            if (i == 0) {
                fillPath.quadTo(pointsX[i], pointsY[i], midX, midY);
            } else {
                fillPath.quadTo(pointsX[i], pointsY[i], midX, midY);
            }
        }
        // 最后一个点
        fillPath.quadTo(pointsX[currentCount - 1], pointsY[currentCount - 1],
                pointsX[currentCount - 1], pointsY[currentCount - 1]);
        fillPath.lineTo(viewWidth, viewHeight);
        fillPath.close();
        
        // 绘制渐变填充：深金 → 亮金（从上到下）
        int[] goldColors = isNightMode ? nightGradientColors : gradientColors;
        waveFillPaint.setShader(new LinearGradient(0, 0, 0, viewHeight,
                Color.argb(120, Color.red(goldColors[0]), Color.green(goldColors[0]), Color.blue(goldColors[0])),
                Color.argb(30, Color.red(goldColors[1]), Color.green(goldColors[1]), Color.blue(goldColors[1])),
                Shader.TileMode.CLAMP));
        canvas.drawPath(fillPath, waveFillPaint);
        
        // 构建描边路径（只画曲线，不封底）
        Path strokePath = new Path();
        strokePath.moveTo(pointsX[0], pointsY[0]);
        for (int i = 0; i < currentCount - 1; i++) {
            float midX = (pointsX[i] + pointsX[i + 1]) / 2;
            float midY = (pointsY[i] + pointsY[i + 1]) / 2;
            strokePath.quadTo(pointsX[i], pointsY[i], midX, midY);
        }
        strokePath.quadTo(pointsX[currentCount - 1], pointsY[currentCount - 1],
                pointsX[currentCount - 1], pointsY[currentCount - 1]);
        
        // 绘制曲线描边（金黄色渐变）
        waveStrokePaint.setShader(new LinearGradient(0, 0, viewWidth, 0, goldColors[0], goldColors[1], Shader.TileMode.CLAMP));
        canvas.drawPath(strokePath, waveStrokePaint);
    }
    
    /**
     * 根据比例获取渐变颜色
     * @param ratio 高度比例 0~1
     * @param useGoldStyle 是否使用金色样式（竖条模式）
     */
    private int getGradientColor(float ratio, boolean useGoldStyle) {
        int[] colors;
        if (useGoldStyle) {
            colors = isNightMode ? nightGradientColors : gradientColors;
            // 金色：两色渐变（深金 → 亮金）
            float t = Math.min(ratio, 1.0f);
            return interpolateColor(colors[0], colors[1], t);
        } else {
            colors = isNightMode ? dotWaveNightColors : dotWaveColors;
            if (ratio <= 0.33f) {
                return colors[0];
            } else if (ratio <= 0.66f) {
                float t = (ratio - 0.33f) / 0.33f;
                return interpolateColor(colors[0], colors[1], t);
            } else {
                float t = (ratio - 0.66f) / 0.34f;
                return interpolateColor(colors[1], colors[2], t);
            }
        }
    }
    
    /**
     * 颜色插值
     */
    private int interpolateColor(int color1, int color2, float t) {
        int r1 = Color.red(color1);
        int g1 = Color.green(color1);
        int b1 = Color.blue(color1);
        
        int r2 = Color.red(color2);
        int g2 = Color.green(color2);
        int b2 = Color.blue(color2);
        
        int r = (int) (r1 + (r2 - r1) * t);
        int g = (int) (g1 + (g2 - g1) * t);
        int b = (int) ((b2 - b1) * t);
        
        return Color.rgb(r, g, b);
    }

    /**
     * 用 DFT 幅度数据更新频谱
     */
    public void updateDTFMagnitudes(float[] magnitudes, float maxMag) {
        if (magnitudes == null || magnitudes.length == 0) return;
        
        float maxHeight = getHeight() * 0.9f;
        
        for (int i = 0; i < currentCount && i < magnitudes.length; i++) {
            float offset = ((i * 17 + 7) % 11 - 5) * 0.4f;
            float mag = magnitudes[i] + offset;
            float height;
            if (mag < 2) {
                height = maxHeight * 0.05f;
            } else if (mag < 5) {
                height = maxHeight * 0.15f;
            } else if (mag < 10) {
                height = maxHeight * 0.30f;
            } else if (mag < 20) {
                height = maxHeight * 0.50f;
            } else if (mag < 40) {
                height = maxHeight * 0.72f;
            } else {
                height = maxHeight;
            }
            float jitter = (float) (Math.random() * 0.15f - 0.075f);
            height *= (1.0f + jitter);
            
            float minVal = currentStyle == STYLE_BAR ? 4f : 6f;
            targetBarHeights[i] = Math.max(minVal, height);
        }
        
        if (isPlaying && !animRunning) {
            startAnimation();
        }
        postInvalidate();
    }
    
    /**
     * 设置播放状态
     */
    public void setPlaying(boolean playing) {
        this.isPlaying = playing;
        if (playing) {
            startAnimation();
        } else {
            stopAnimation();
            float minVal = currentStyle == STYLE_BAR ? 8f : 4f;
            for (int i = 0; i < currentCount; i++) {
                targetBarHeights[i] = minVal;
                barHeights[i] = minVal;
            }
        }
        postInvalidate();
    }
}
