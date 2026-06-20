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
 * 支持三种样式：竖条模式、圆点模式、波浪线模式
 * 点击切换样式
 */
public class SpectrumView extends View {
    
    private static final String TAG = "SpectrumView";
    
    // 频谱样式常量
    private static final int STYLE_BAR = 0;          // 竖条模式
    private static final int STYLE_DOT = 1;          // 圆点模式
    private static final int STYLE_WAVE = 2;         // 波浪线模式
    
    // 竖条模式：每根柱目标占用像素（含间距），据此动态计算柱数
    private static final float BAR_PIXELS_PER_BAR = 15f;
    private static final int BAR_COUNT_MIN = 32;
    private static final int BAR_COUNT_MAX = 256;
    
    // 圆点/波浪线模式的分量数量（固定不变）
    private static final int DOT_COUNT = 64;
    
    // 当前数据分量数量（根据样式和宽度动态变化）
    private int currentCount = 128;
    // 竖条模式当前柱数（仅在 STYLE_BAR 时使用）
    private int barCount = 128;
    
    // 当前样式
    private int currentStyle = STYLE_BAR;
    
    // 竖条宽度
    private float barWidth;
    
    // 间距
    private float barSpacing;
    
    // 频谱条高度数组
    private float[] barHeights;
    private float[] targetBarHeights;
    
    // 峰值指示器
    private float[] peakHeights;       // 峰值帽当前高度
    private float[] peakDecayTimers;   // 峰值保持计时器（帧数）
    private static final float PEAK_HOLD_FRAMES = 12f; // 峰值保持帧数（60fps下约200ms）
    private static final float PEAK_FALL_SPEED = 1.5f;  // 峰值帽下落速度（像素/帧）
    private Paint peakPaint;
    
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
    private static final int ANIM_INTERVAL_MS = 16; // ~60fps
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
        barHeights = new float[currentCount];
        targetBarHeights = new float[currentCount];
        peakHeights = new float[currentCount];
        peakDecayTimers = new float[currentCount];
        
        // 竖条/圆点画笔
        barPaint = new Paint();
        barPaint.setAntiAlias(true);
        barPaint.setStyle(Paint.Style.FILL);
        
        // 峰值帽画笔
        peakPaint = new Paint();
        peakPaint.setAntiAlias(true);
        peakPaint.setStyle(Paint.Style.FILL);
        
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
                        // 重力衰减：高度越大下落越快，模拟物理重力效果
                        float fallSpeed = targetBarHeights[i] * 0.12f;
                        targetBarHeights[i] -= fallSpeed;
                        float minVal;
                        switch (currentStyle) {
                            case STYLE_BAR: minVal = 4f; break;
                            default: minVal = 6f; break;
                        }
                        if (targetBarHeights[i] < minVal) {
                            targetBarHeights[i] = minVal;
                        }
                        
                        // 峰值指示器更新
                        if (barHeights[i] >= peakHeights[i]) {
                            // 新峰值：更新并重置保持计时器
                            peakHeights[i] = barHeights[i];
                            peakDecayTimers[i] = PEAK_HOLD_FRAMES;
                        } else if (peakDecayTimers[i] > 0) {
                            // 保持期：峰值帽不动
                            peakDecayTimers[i]--;
                        } else {
                            // 衰减期：峰值帽缓慢下落
                            peakHeights[i] -= PEAK_FALL_SPEED;
                            if (peakHeights[i] < minVal) {
                                peakHeights[i] = minVal;
                            }
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
    public void switchStyle() {
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
                currentCount = barCount;
                Log.d(TAG, "切换到竖条模式，barCount=" + barCount);
                break;
        }
        rebuildArrays();
        requestLayout();
        postInvalidate();
    }
    
    // 是否显示频谱
    private boolean visible = true;
    
    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        visible = (visibility == VISIBLE);
    }
    
    /**
     * 切换频谱显隐（供外部按钮调用）
     */
    public void toggleVisibility() {
        visible = !visible;
        super.setVisibility(visible ? VISIBLE : GONE);
        Log.d(TAG, "频谱" + (visible ? "显示" : "隐藏"));
    }
    
    /**
     * 频谱是否可见
     */
    public boolean isSpectrumVisible() {
        return visible;
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
        
        // 竖条模式：根据宽度动态计算柱数
        if (w > 0) {
            int newBarCount = Math.max(BAR_COUNT_MIN, Math.min(BAR_COUNT_MAX, Math.round(w / BAR_PIXELS_PER_BAR)));
            if (newBarCount != barCount) {
                barCount = newBarCount;
                if (currentStyle == STYLE_BAR) {
                    currentCount = barCount;
                    rebuildArrays();
                }
            }
        }
        
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
                for (int i = 0; i < currentCount; i++) {
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
            barHeights[i] += (targetBarHeights[i] - barHeights[i]) * 0.5f;
            
            float x = i * (barWidth + barSpacing);
            float height = barHeights[i];
            float y = getHeight() - height;
            
            float ratio = height / getHeight();
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
        
        int peakDotColor = isNightMode ? Color.parseColor("#FFCA28") : Color.parseColor("#FFD54F");
        peakPaint.setColor(peakDotColor);
        
        for (int i = 0; i < currentCount; i++) {
            barHeights[i] += (targetBarHeights[i] - barHeights[i]) * 0.5f;
            
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
            
            // 峰值亮点：在圆点上方画一个小亮点标记峰值位置
            if (peakHeights[i] > minH + 4f) {
                float peakCy = viewHeight - peakHeights[i] * weight;
                if (peakCy < cy - radius) {
                    canvas.drawCircle(x, peakCy, 2f, peakPaint);
                }
            }
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
            barHeights[i] += (targetBarHeights[i] - barHeights[i]) * 0.5f;
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
        
        // 波浪线峰值点：在波峰上方画小亮点
        int peakDotColor = isNightMode ? Color.parseColor("#FFCA28") : Color.parseColor("#FFD54F");
        peakPaint.setColor(peakDotColor);
        for (int i = 0; i < currentCount; i++) {
            if (peakHeights[i] > 10f) {
                float peakY = viewHeight - peakHeights[i];
                if (peakY < pointsY[i] - 4f) {
                    canvas.drawCircle(pointsX[i], peakY, 2f, peakPaint);
                }
            }
        }
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
        int b = (int) (b1 + (b2 - b1) * t);
        
        return Color.rgb(r, g, b);
    }

    /**
     * 重建频谱数组（柱数变化时调用）
     */
    private void rebuildArrays() {
        barHeights = new float[currentCount];
        targetBarHeights = new float[currentCount];
        peakHeights = new float[currentCount];
        peakDecayTimers = new float[currentCount];
    }

    /**
     * 获取当前频谱柱数（供 PlayerActivity 的 FFT 合并逻辑使用）
     */
    public int getCurrentCount() {
        return currentCount;
    }

    /**
     * 获取竖条模式实际需要的 FFT 频段数
     */
    public int getBarInputCount() {
        return currentCount;
    }

    /**
     * 对称镜像：将半个数组的频段数据镜像展开为完整柱数
     * 左半 = 原始倒序（高频→低频），右半 = 原始正序（低频→高频）
     * 结果：低频在中间，高频在两端，形成对称山丘
     */
    private float[] mirrorData(float[] half) {
        if (half == null || half.length == 0) return half;
        int fullLen = half.length * 2;
        float[] result = new float[fullLen];
        // 左半：倒序排列
        for (int i = 0; i < half.length; i++) {
            result[i] = half[half.length - 1 - i];
        }
        // 右半：正序排列
        for (int i = 0; i < half.length; i++) {
            result[half.length + i] = half[i];
        }
        return result;
    }

    /**
     * 用 DFT 幅度数据更新频谱
     * 竖条模式：BD 方式 — 非线性倒数归一化 + 高斯平滑
     * 圆点/波浪线：保留原有对数映射
     */
    public void updateDTFMagnitudes(float[] magnitudes, float maxMag) {
        if (magnitudes == null || magnitudes.length == 0) return;
        
        if (currentStyle == STYLE_BAR) {
            // ========= BD visualize1 方式：非线性倒数归一化 =========
            float maxHeight = getHeight() * 0.9f;
            int offset = 3;  // 跳过前3个超低频频段（直流分量干扰）
            
            // 0. 跳过offset频段：左移数据，前offset根柱子用最小高度
            int dataLen = Math.min(currentCount, magnitudes.length) - offset;
            if (dataLen < 1) dataLen = 1;
            float[] offsetData = new float[currentCount];
            for (int i = 0; i < currentCount; i++) {
                offsetData[i] = (i < offset) ? 0f : ((i - offset < magnitudes.length) ? magnitudes[i - offset] : 0f);
            }
            
            // 1. 非线性倒数放大：小幅度放大、大幅度压缩，增强视觉差异
            //    公式：val = (max/val) * h + val （阈值 20% * max）
            float threshold = 0.2f * maxMag;
            for (int i = 0; i < currentCount; i++) {
                if (offsetData[i] != 0 && offsetData[i] >= threshold) {
                    offsetData[i] = (maxMag / offsetData[i]) * maxHeight + offsetData[i];
                }
            }
            
            // 2. 缩放倍率（g=5.0）和上限（f=200.0）
            float scale = 4.0f;
            float cap = maxHeight;
            for (int i = 0; i < currentCount; i++) {
                float v = offsetData[i] * scale;
                if (v > cap) v = cap;
                offsetData[i] = v;
            }
            
            // 3. 高斯平滑（sigma = 1.5 * 3 = 4.5 → kernelSize=9）
            float sigma = 1.5f;
            offsetData = gaussianSmooth(offsetData, sigma);
            
            // 4. 对称镜像：暂不启用
            // magnitudes = mirrorData(magnitudes);
            
            // 5. 赋值给 targetBarHeights
            float minVal = 4f;
            for (int i = 0; i < currentCount; i++) {
                targetBarHeights[i] = Math.max(minVal, offsetData[i]);
            }
        } else {
            // ========= 原有方式：对数映射 + 钟形权重 =========
            float maxHeight = getHeight() * 0.9f;
            float logMax = (float) Math.log1p(maxMag > 0 ? maxMag : 1);
            float centerIndex = (currentCount - 1) / 2f;
            
            for (int i = 0; i < currentCount && i < magnitudes.length; i++) {
                float normalized = (float) Math.log1p(magnitudes[i]) / logMax;
                normalized = normalized * (2f - normalized);
                float distFromCenter = Math.abs(i - centerIndex) / centerIndex;
                float positionWeight = (currentStyle == STYLE_DOT) ? 1.0f : (1.0f - distFromCenter * 0.9f);
                float height = normalized * maxHeight * positionWeight;
                float minVal = 6f;
                targetBarHeights[i] = Math.max(minVal, height);
            }
        }
        
        if (isPlaying && !animRunning) {
            startAnimation();
        }
        postInvalidate();
    }
    
    /**
     * 高斯平滑（参考 BD 的 DataManipulationUtil 实现）
     * @param data 原始数据
     * @param sigma 高斯核标准差
     * @return 平滑后的数据
     */
    private float[] gaussianSmooth(float[] data, float sigma) {
        if (data == null || data.length == 0) return data;
        
        int len = data.length;
        float[] result = new float[len];
        
        // 构建高斯核：size = (int)(6 * sigma)，确保奇数
        int kernelSize = (int) (6 * sigma);
        if (kernelSize % 2 == 0) kernelSize++;
        if (kernelSize < 3) kernelSize = 3;
        int half = kernelSize / 2;
        
        // 计算高斯权重
        double[] kernel = new double[kernelSize];
        double sum = 0;
        for (int i = -half; i <= half; i++) {
            double w = Math.exp(-(i * i) / (2.0 * sigma * sigma));
            kernel[i + half] = w;
            sum += w;
        }
        // 归一化
        for (int i = 0; i < kernelSize; i++) {
            kernel[i] /= sum;
        }
        
        // 卷积
        for (int i = 0; i < len; i++) {
            float v = 0;
            for (int k = -half; k <= half; k++) {
                int idx = i + k;
                if (idx >= 0 && idx < len) {
                    v += data[idx] * (float) kernel[k + half];
                }
            }
            result[i] = v;
        }
        return result;
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
            float minVal;
            switch (currentStyle) {
                case STYLE_BAR: minVal = 8f; break;
                default: minVal = 4f; break;
            }
            for (int i = 0; i < currentCount; i++) {
                targetBarHeights[i] = minVal;
                barHeights[i] = minVal;
                peakHeights[i] = minVal;
                peakDecayTimers[i] = 0;
            }
        }
        postInvalidate();
    }
}
