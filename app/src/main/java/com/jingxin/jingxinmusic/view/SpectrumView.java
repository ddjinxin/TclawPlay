package com.jingxin.jingxinmusic.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import java.util.Random;

/**
 * 频谱视图
 * 支持四种样式：竖条模式、圆点模式、波浪线模式、圆环模式
 * 圆环模式有三种子模式：柱状、爆炸、波形
 */
public class SpectrumView extends View {
    
    private static final String TAG = "SpectrumView";
    
    // 频谱样式常量
    private static final int STYLE_BAR = 0;          // 竖条模式
    private static final int STYLE_DOT = 1;          // 圆点模式
    private static final int STYLE_WAVE = 2;         // 波浪线模式
    private static final int STYLE_RING = 3;         // 圆环模式
    private static final int STYLE_COLUMNAR = 4;     // ColumnarView柱状模式（原版）
    private static final int STYLE_KUGOU = 5;       // KugouColumn酷狗风格柱状模式（原版）
    private static final int STYLE_AIVOICE = 6;     // AiVoiceView AI语音模式（原版）
    private static final int STYLE_WAVECOLUMN = 7;  // WaveColumnformView 波形柱模式（原版）
    
    // 圆环子模式常量
    private static final int RING_COLUMNAR = 0;      // 柱状（放射白线）
    private static final int RING_BOMB = 1;           // 爆炸（半透明白线+末端圆点）
    private static final int RING_WAVE = 2;           // 波形（端点连线）
    
    // 竖条模式：每根柱目标占用像素（含间距），据此动态计算柱数
    private static final float BAR_PIXELS_PER_BAR = 15f;
    private static final int BAR_COUNT_MIN = 32;
    private static final int BAR_COUNT_MAX = 256;
    
    // 圆点/波浪线模式的分量数量（固定不变）
    private static final int DOT_COUNT = 64;
    
    // 圆环模式FFT请求数量（匹配原版SAMPLE_SIZE=256）
    private static final int RING_INPUT_COUNT = 256;
    
    // ColumnarView模式柱数（横屏128，竖屏64）
    private static final int COLUMNAR_COUNT_LANDSCAPE = 128;
    private static final int COLUMNAR_COUNT_PORTRAIT = 64;
    private int columnarCount = COLUMNAR_COUNT_PORTRAIT;
    
    // KugouColumn模式柱数
    private static final int KUGOU_COUNT = 128;
    
    // AiVoiceView模式FFT数据量
    private static final int AIVOICE_INPUT_COUNT = 256;
    
    // WaveColumnformView模式FFT数据量（横屏128，竖屏64）
    private static final int WAVECOLUMN_COUNT_LANDSCAPE = 128;
    private static final int WAVECOLUMN_COUNT_PORTRAIT = 64;
    private int waveColumnCount = WAVECOLUMN_COUNT_PORTRAIT;
    
    // KugouColumn模式状态（严格匹配原版KugouColumn）
    private Paint kugouPaint;
    private float[] kugouBlockTop;              // 能量块top位置
    private float kugouSpacing;                 // 柱子间距（1dp）
    private float kugouBlockSpeed;              // 能量块下落速度（3dp/帧）
    private float kugouDistance;                 // 能量块与柱顶间距（1dp）
    private float kugouLagerOffsetRate = 35f;   // 放大倍率
    private LinearGradient kugouGradient1;       // 偶数组渐变（右→左，半透明暖色）
    private LinearGradient kugouGradient2;       // 奇数组渐变（左→右，渐显暖色）
    
    // AiVoiceView模式状态（严格匹配原版AiVoiceView）
    private Paint aiVoicePaint;
    private Path[] aiVoicePaths;
    private int[] aiVoiceColors;    // 3条曲线颜色
    private float[] aiVoiceStart;   // 3条曲线起始位置比例
    private float[] aiVoiceEnd;     // 3条曲线结束位置比例
    
    // WaveColumnformView模式状态（严格匹配原版WaveColumnformView）
    private Paint waveColumnPaint;
    private int waveColumnMainColor;        // 主色调 #F53F3F
    private int waveColumnSpacingOffset;    // 间距偏移（默认0）
    private int waveColumnCenterHolder;     // 中心保持距离（默认20）
    private static final int WAVECOLUMN_LAGER_OFFSET = 10; // 放大量（匹配原版LAGER_OFFSET）
    private static final int WAVECOLUMN_RECT_WIDTH = 10;   // 圆角矩形宽度（用户指定10px）
    private static final float WAVECOLUMN_ROUND_RX = 10f;  // 圆角x半径
    private static final float WAVECOLUMN_ROUND_RY = 10f;  // 圆角y半径
    
    // ColumnarView模式状态（严格匹配原版ColumnarView）
    private Paint columnarPaint;                     // 渐变画笔
    private float[] columnarBlockTop;                // 能量块top位置
    private float columnarSpacing;                   // 柱子间距（1dp）
    private float columnarBlockSpeed;                // 能量块下落速度（1dp/帧）
    private float columnarDistance;                   // 能量块与柱顶间距（1dp）
    
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
    
    // 圆环模式状态
    private int ringSubMode = RING_COLUMNAR;   // 圆环子模式
    private int ringCount = 64;                 // 圆环当前显示频段数（= RING_INPUT_COUNT - ringScope）
    private float ringRotation = 0f;            // 圆环旋转角度
    private Paint ringPaint;                    // 圆环画笔
    // 圆环封面信息（由外部通过setCoverCenter设置）
    private float coverCenterX;                 // 封面圆心X（相对SpectrumView）
    private float coverCenterY;                 // 封面圆心Y（相对SpectrumView）
    private float coverRadius;                  // 封面半径
    // 圆环参数（严格匹配原版AttachmentRingView）
    private int ringBetween = 1;                // 相邻频段间隔角度（度）
    private int ringScope = 50;                 // 跳过末尾高频分量数
    private float ringStart = 10f;              // 射线最短长度偏移（像素，原版直接+start当px用）
    private boolean ringRandomAngle = true;     // 是否随机跳跃角度
    private int ringRandomAngleValue = 0;       // 当前随机角度值
    private int ringFrameCounter = 0;           // 帧计数器（用于随机角度切换）
    private Random random = new Random();       // 随机数生成器（类字段复用）
    
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
        
        // 圆环画笔
        ringPaint = new Paint();
        ringPaint.setAntiAlias(true);
        ringPaint.setStyle(Paint.Style.FILL);
        
        // ColumnarView画笔
        columnarPaint = new Paint();
        columnarPaint.setAntiAlias(true);
        columnarPaint.setStyle(Paint.Style.FILL);
        
        // ColumnarView参数（严格匹配原版）
        columnarSpacing = 1f * getResources().getDisplayMetrics().density; // 1dp
        columnarBlockSpeed = 1f * getResources().getDisplayMetrics().density; // 1dp/帧
        columnarDistance = 1f * getResources().getDisplayMetrics().density; // 1dp
        
        // KugouColumn画笔
        kugouPaint = new Paint();
        kugouPaint.setAntiAlias(true);
        
        // KugouColumn参数（严格匹配原版）
        kugouSpacing = 1f * getResources().getDisplayMetrics().density; // 1dp
        kugouBlockSpeed = 3f * getResources().getDisplayMetrics().density; // 3dp/帧（原版dp2px(3)）
        kugouDistance = 1f * getResources().getDisplayMetrics().density; // 1dp
        
        // AiVoiceView画笔和路径（严格匹配原版：LAYER_TYPE_SOFTWARE + LIGHTEN混合）
        aiVoicePaint = new Paint();
        aiVoicePaint.setAntiAlias(true);
        aiVoicePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.LIGHTEN));
        aiVoicePaths = new Path[3];
        // AiVoice 3条曲线颜色（原版AppConstant: ALPHA=255, RED=255, GREEN=255, BLUE=255）
        aiVoiceColors = new int[]{
            Color.argb(255, 255, 0, 0),    // red
            Color.argb(255, 0, 255, 0),    // green
            Color.argb(255, 0, 0, 255)     // blue
        };
        aiVoiceStart = new float[]{0f, 0.1f, 0.2f};
        aiVoiceEnd = new float[]{0.8f, 0.9f, 1.0f};
        for (int i = 0; i < 3; i++) {
            aiVoicePaths[i] = new Path();
        }
        
        // WaveColumnformView画笔和参数（严格匹配原版）
        waveColumnPaint = new Paint();
        waveColumnPaint.setAntiAlias(true);
        waveColumnPaint.setStyle(Paint.Style.FILL);
        waveColumnPaint.setStrokeWidth(3f); // 原版setWaveData中设置
        waveColumnMainColor = Color.parseColor("#F53F3F"); // 原版默认主色调
        waveColumnSpacingOffset = 0;
        waveColumnCenterHolder = 20;
        
        barSpacing = 4f;
        
        animRunnable = new Runnable() {
            @Override
            public void run() {
                if (isPlaying && animRunning) {
                    for (int i = 0; i < currentCount; i++) {
                        // AiVoice不需要重力衰减，数据由updateDTFMagnitudes直接更新
                        if (currentStyle != STYLE_AIVOICE) {
                            // 重力衰减：高度越大下落越快，模拟物理重力效果
                            float fallSpeed = targetBarHeights[i] * 0.12f;
                            targetBarHeights[i] -= fallSpeed;
                        }
                        float minVal;
                        switch (currentStyle) {
                            case STYLE_BAR: minVal = 4f; break;
                            case STYLE_RING: minVal = 2f; break;
                            case STYLE_COLUMNAR: minVal = 1f; break;
                            case STYLE_KUGOU: minVal = 1f; break;
                            case STYLE_AIVOICE: minVal = 2f; break;
                             case STYLE_WAVECOLUMN: minVal = 2f; break;
                             default: minVal = 6f; break;
                        }
                        if (targetBarHeights[i] < minVal) {
                            targetBarHeights[i] = minVal;
                        }
                        
                        // AiVoice不需要峰值指示器
                        if (currentStyle != STYLE_AIVOICE) {
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
                    }
                    invalidate();
                    animHandler.postDelayed(this, ANIM_INTERVAL_MS);
                }
            }
        };
    }
    
    /**
     * 切换频谱样式：竖条 → 圆点 → 波浪线 → 圆环 → ColumnarView → KugouColumn → AiVoiceView → WaveColumnformView → 竖条
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
                currentStyle = STYLE_RING;
                ringCount = RING_INPUT_COUNT - ringScope;
                currentCount = ringCount;
                Log.d(TAG, "切换到圆环模式，ringCount=" + ringCount);
                break;
            case STYLE_RING:
                currentStyle = STYLE_COLUMNAR;
                columnarCount = (getWidth() > getHeight()) ? COLUMNAR_COUNT_LANDSCAPE : COLUMNAR_COUNT_PORTRAIT;
                currentCount = columnarCount;
                columnarBlockTop = null;
                Log.d(TAG, "切换到ColumnarView模式，columnarCount=" + columnarCount);
                break;
            case STYLE_COLUMNAR:
                currentStyle = STYLE_KUGOU;
                currentCount = KUGOU_COUNT;
                kugouBlockTop = null;
                Log.d(TAG, "切换到KugouColumn模式");
                break;
            case STYLE_KUGOU:
                currentStyle = STYLE_AIVOICE;
                currentCount = AIVOICE_INPUT_COUNT;
                Log.d(TAG, "切换到AiVoice模式");
                break;
            case STYLE_AIVOICE:
                currentStyle = STYLE_WAVECOLUMN;
                waveColumnCount = (getWidth() > getHeight()) ? WAVECOLUMN_COUNT_LANDSCAPE : WAVECOLUMN_COUNT_PORTRAIT;
                currentCount = waveColumnCount;
                Log.d(TAG, "切换到WaveColumnformView模式，waveColumnCount=" + waveColumnCount);
                break;
            case STYLE_WAVECOLUMN:
            default:
                currentStyle = STYLE_BAR;
                currentCount = barCount;
                Log.d(TAG, "切换到竖条模式，barCount=" + barCount);
                break;
        }
        rebuildArrays();
        // 重新计算竖条宽度（切换模式时onSizeChanged不一定触发）
        float totalSpacing = barSpacing * (currentCount - 1);
        barWidth = (getWidth() - totalSpacing) / currentCount;
        // 切换到圆环模式时重置Kugou渐变
        if (currentStyle != STYLE_KUGOU) {
            kugouGradient1 = null;
            kugouGradient2 = null;
        }
        // 切换时重置WaveColumn渐变（下次进入时重建）
        if (currentStyle != STYLE_WAVECOLUMN) {
            waveColumnPaint.setShader(null);
        }
        // AiVoiceView需要SOFTWARE图层才能让LIGHTEN混合模式生效
        if (currentStyle == STYLE_AIVOICE) {
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        } else {
            setLayerType(LAYER_TYPE_NONE, null);
        }
        requestLayout();
        postInvalidate();
    }
    
    /**
     * 切换圆环子模式：柱状 → 爆炸 → 波形 → 柱状
     */
    public void switchRingSubMode() {
        switch (ringSubMode) {
            case RING_COLUMNAR:
                ringSubMode = RING_BOMB;
                Log.d(TAG, "圆环切换到爆炸模式");
                break;
            case RING_BOMB:
                ringSubMode = RING_WAVE;
                Log.d(TAG, "圆环切换到波形模式");
                break;
            case RING_WAVE:
            default:
                ringSubMode = RING_COLUMNAR;
                Log.d(TAG, "圆环切换到柱状模式");
                break;
        }
        postInvalidate();
    }
    
    /**
     * 当前是否为圆环模式
     */
    public boolean isRingMode() {
        return currentStyle == STYLE_RING;
    }
    
    /**
     * 设置封面在 SpectrumView 中的位置和半径（圆环模式用）
     */
    public void setCoverCenter(float cx, float cy, float radius) {
        this.coverCenterX = cx;
        this.coverCenterY = cy;
        this.coverRadius = radius;
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
     * 圆环模式下的触摸事件：单击圆环外围切换子模式
     * 点击封面区域不拦截（让触摸穿透到封面）
     */
    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        if (currentStyle != STYLE_RING) {
            return super.onTouchEvent(event);
        }
        if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
            float dx = event.getX() - coverCenterX;
            float dy = event.getY() - coverCenterY;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            float baseRadius = coverRadius + 4 * getResources().getDisplayMetrics().density;
            
            if (dist > coverRadius) {
                // 点击在圆环外围区域，切换子模式
                switchRingSubMode();
            }
            // 点击封面内部，不消费，让事件穿透
            return dist > coverRadius + 4 * getResources().getDisplayMetrics().density;
        }
        // 圆环模式下：封面外区域拦截，封面内穿透
        if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
            float dx = event.getX() - coverCenterX;
            float dy = event.getY() - coverCenterY;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            return dist > coverRadius + 4 * getResources().getDisplayMetrics().density;
        }
        return false;
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
            // WaveColumn模式：横竖屏切换时调整柱数
            int newWaveColumnCount = (w > h) ? WAVECOLUMN_COUNT_LANDSCAPE : WAVECOLUMN_COUNT_PORTRAIT;
            if (newWaveColumnCount != waveColumnCount) {
                waveColumnCount = newWaveColumnCount;
                if (currentStyle == STYLE_WAVECOLUMN) {
                    currentCount = waveColumnCount;
                    rebuildArrays();
                }
            }
            // ColumnarView模式：横竖屏切换时调整柱数
            int newColumnarCount = (w > h) ? COLUMNAR_COUNT_LANDSCAPE : COLUMNAR_COUNT_PORTRAIT;
            if (newColumnarCount != columnarCount) {
                columnarCount = newColumnarCount;
                if (currentStyle == STYLE_COLUMNAR) {
                    currentCount = columnarCount;
                    columnarBlockTop = null;
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
        
        // ColumnarView渐变：红→绿→蓝 对角线（匹配原版）
        columnarPaint.setShader(new LinearGradient(0f, 0f, w, h,
                new int[]{0xFFFF0000, 0xFF00FF00, 0xFF0000FF},
                new float[]{0, 0.5f, 1f}, Shader.TileMode.CLAMP));
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
            case STYLE_RING:
                drawRing(canvas);
                break;
            case STYLE_COLUMNAR:
                drawColumnar(canvas);
                break;
            case STYLE_KUGOU:
                drawKugou(canvas);
                break;
            case STYLE_AIVOICE:
                drawAiVoice(canvas);
                break;
            case STYLE_WAVECOLUMN:
                drawWaveColumn(canvas);
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
            case STYLE_RING: {
                // 圆环静态：画一个白色细圆环基线
                float cx = coverCenterX;
                float cy = coverCenterY;
                float radius = coverRadius + 4 * getResources().getDisplayMetrics().density;
                ringPaint.setColor(Color.argb(60, 255, 255, 255));
                ringPaint.setStyle(Paint.Style.STROKE);
                ringPaint.setStrokeWidth(1f);
                canvas.drawCircle(cx, cy, radius, ringPaint);
                ringPaint.setStyle(Paint.Style.FILL);
                break;
            }
            case STYLE_COLUMNAR: {
                // ColumnarView静态：底部小矩形条（实时计算柱宽）
                columnarPaint.setColor(Color.argb(80, 255, 0, 0));
                float colTotalSpacing = (currentCount - 1) * columnarSpacing;
                float colW = (getWidth() - colTotalSpacing) / currentCount;
                if (colW < 1f) colW = 1f;
                float colBlockH = colW / 2f;
                float colTotalW = currentCount * colW + (currentCount - 1) * columnarSpacing;
                float colCompensate = (getWidth() - colTotalW) / 2f;
                for (int i = 0; i < currentCount; i++) {
                    float x = colCompensate + i * (colW + columnarSpacing);
                    canvas.drawRect(x, getHeight() - colBlockH, x + colW, getHeight(), columnarPaint);
                }
                break;
            }
            case STYLE_KUGOU: {
                // KugouColumn静态：底部两排暖色小块
                kugouPaint.setColor(Color.argb(80, 253, 178, 230));
                float kTotalSpacing = (KUGOU_COUNT - 1) * kugouSpacing;
                float kW = (getWidth() - kTotalSpacing) / KUGOU_COUNT;
                if (kW < 1f) kW = 1f;
                float kBlockH = kW / 2f;
                float kTotalW = KUGOU_COUNT * kW + (KUGOU_COUNT - 1) * kugouSpacing;
                float kCompensate = (getWidth() - kTotalW) / 2f;
                float aaa = getWidth() / 2f / 2f;
                int halfCount = KUGOU_COUNT / 2;
                for (int i = 0; i < halfCount; i++) {
                    float x = kCompensate + i * (kW + kugouSpacing) + aaa;
                    canvas.drawRect(x, getHeight() - kBlockH, x + kW, getHeight(), kugouPaint);
                }
                for (int i = 0; i < halfCount; i++) {
                    float x = kCompensate + (i + halfCount - 1) * (kW + kugouSpacing) - aaa;
                    canvas.drawRect(x, getHeight() - kBlockH, x + kW, getHeight(), kugouPaint);
                }
                break;
            }
            case STYLE_AIVOICE: {
                // AiVoiceView静态：3条平直线
                aiVoicePaint.setColor(Color.argb(60, 255, 255, 255));
                aiVoicePaint.setStyle(Paint.Style.STROKE);
                aiVoicePaint.setStrokeWidth(2f);
                aiVoicePaint.setXfermode(null);
                float midY = getHeight() / 2f;
                for (int i = 0; i < 3; i++) {
                    float startX = getWidth() * aiVoiceStart[i];
                    float endX = getWidth() * aiVoiceEnd[i];
                    canvas.drawLine(startX, midY, endX, midY, aiVoicePaint);
                }
                aiVoicePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.LIGHTEN));
                aiVoicePaint.setStyle(Paint.Style.FILL);
                break;
            }
            case STYLE_WAVECOLUMN: {
                // WaveColumnformView静态：底部一排小圆角矩形
                float midY = getHeight() / 2f;
                waveColumnPaint.setColor(Color.argb(80, 245, 63, 63));
                int spacing = getWidth() / waveColumnCount + waveColumnSpacingOffset;
                float left = 0f;
                for (int i = 0; i < waveColumnCount && left < getWidth(); i++) {
                    float top = midY - waveColumnCenterHolder;
                    float bottom = midY + waveColumnCenterHolder;
                    canvas.drawRoundRect(left, top, left + WAVECOLUMN_RECT_WIDTH, bottom,
                            WAVECOLUMN_ROUND_RX, WAVECOLUMN_ROUND_RY, waveColumnPaint);
                    left += WAVECOLUMN_RECT_WIDTH + spacing;
                }
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
     * 绘制ColumnarView频谱（严格匹配原版ColumnarView）
     * 特征：256根细柱 + 下落式峰值能量块 + 红绿蓝对角线渐变
     */
    private void drawColumnar(Canvas canvas) {
        float viewWidth = getWidth();
        float viewHeight = getHeight();
        float minBarPx = getResources().getDisplayMetrics().density; // 1dp最小柱高
        
        // 实时计算柱宽和居中补偿（防止切换模式时onSizeChanged未触发）
        float totalSpacing = (currentCount - 1) * columnarSpacing;
        float colWidth = (viewWidth - totalSpacing) / currentCount;
        if (colWidth < 1f) colWidth = 1f;
        float blockHeight = colWidth / 2f;
        float totalWidth = currentCount * colWidth + (currentCount - 1) * columnarSpacing;
        float compensate = (viewWidth - totalWidth) / 2f;
        
        // 初始化能量块数组
        if (columnarBlockTop == null || columnarBlockTop.length != currentCount) {
            columnarBlockTop = new float[currentCount];
            for (int i = 0; i < currentCount; i++) {
                columnarBlockTop[i] = viewHeight - blockHeight;
            }
        }
        
        // 更新柱高平滑
        for (int i = 0; i < currentCount; i++) {
            barHeights[i] += (targetBarHeights[i] - barHeights[i]) * 0.5f;
        }
        
        // 找出当前帧最大高度，用于按比例缩放
        float lagerOffsetRate = 3.0f;
        float maxBarHeight = 0f;
        for (int i = 0; i < currentCount; i++) {
            float h = barHeights[i] * (1.0f + lagerOffsetRate);
            if (h > maxBarHeight) maxBarHeight = h;
        }
        
        // 缩放因子：如果最大柱高超过频谱区域，按比例压缩
        float scale = 1.0f;
        if (maxBarHeight > viewHeight) {
            scale = (viewHeight - minBarPx) / maxBarHeight;
        }
        
        for (int i = 0; i < currentCount; i++) {
            float x = compensate + i * (colWidth + columnarSpacing);
            
            float barTop = viewHeight - barHeights[i] * (1.0f + lagerOffsetRate) * scale;
            if (barTop < minBarPx) barTop = minBarPx;
            barTop = Math.min(barTop, viewHeight - minBarPx);
            
            canvas.drawRect(x, barTop, x + colWidth, viewHeight, columnarPaint);
            
            // 更新能量块（峰值帽）
            if (barTop > 0 && barTop < columnarBlockTop[i]) {
                columnarBlockTop[i] = barTop - blockHeight - columnarDistance;
            } else {
                columnarBlockTop[i] = columnarBlockTop[i] + columnarBlockSpeed;
            }
            float blockTop = columnarBlockTop[i];
            float blockBottom = blockTop + blockHeight;
            if (blockBottom > viewHeight) {
                blockBottom = viewHeight;
                blockTop = viewHeight - blockHeight;
            }
            
            canvas.drawRect(x, blockTop, x + colWidth, blockBottom, columnarPaint);
        }
    }
    
    /**
     * 绘制KugouColumn频谱（严格匹配原版KugouColumn）
     * 特征：128根柱子只绘制能量块峰值帽 + 两组渐变叠加（中间交汇）
     * lagerOffsetRate=35，blockSpeed=3dp/帧
     */
    private void drawKugou(Canvas canvas) {
        float viewWidth = getWidth();
        float viewHeight = getHeight();
        float minBarPx = getResources().getDisplayMetrics().density;
        
        // 实时计算柱宽和居中补偿
        float totalSpacing = (currentCount - 1) * kugouSpacing;
        float colWidth = (viewWidth - totalSpacing) / currentCount + 1.5f * minBarPx; // 原版+dp2px(1.5)
        if (colWidth < 1f) colWidth = 1f;
        float blockHeight = colWidth / 2f;
        float totalWidth = currentCount * colWidth + (currentCount - 1) * kugouSpacing;
        float compensate = (viewWidth - totalWidth) / 2f;
        
        // aaa: 原版 getWidth() / 2 / drawListSize(2)
        float aaa = viewWidth / 2f / 2f;
        int halfCount = currentCount / 2;
        
        // 初始化能量块数组
        if (kugouBlockTop == null || kugouBlockTop.length != currentCount) {
            kugouBlockTop = new float[currentCount];
            for (int i = 0; i < currentCount; i++) {
                kugouBlockTop[i] = viewHeight - blockHeight;
            }
        }
        
        // 更新柱高平滑
        for (int i = 0; i < currentCount; i++) {
            barHeights[i] += (targetBarHeights[i] - barHeights[i]) * 0.5f;
        }
        
        // 钟形权重：中间高两边低，sigma=0.35
        float centerIndex = (currentCount - 1) / 2f;
        float sigma = 0.35f;
        
        // 找出当前帧最大高度，按比例缩放（含钟形权重）
        float maxBarHeight = 0f;
        for (int i = 0; i < currentCount; i++) {
            float dist = (i - centerIndex) / centerIndex;
            float bellWeight = (float) Math.exp(-(dist * dist) / (2 * sigma * sigma));
            float h = barHeights[i] * (1.0f + kugouLagerOffsetRate) * bellWeight;
            if (h > maxBarHeight) maxBarHeight = h;
        }
        float scale = 1.0f;
        if (maxBarHeight > viewHeight) {
            scale = (viewHeight - minBarPx) / maxBarHeight;
        }
        
        // 计算每根柱子的barTop和能量块位置
        float[] barTops = new float[currentCount];
        for (int i = 0; i < currentCount; i++) {
            float dist = (i - centerIndex) / centerIndex;
            float bellWeight = (float) Math.exp(-(dist * dist) / (2 * sigma * sigma));
            float barTop = viewHeight - barHeights[i] * (1.0f + kugouLagerOffsetRate) * bellWeight * scale;
            if (barTop < minBarPx) barTop = minBarPx;
            barTop = Math.min(barTop, viewHeight - minBarPx);
            barTops[i] = barTop;
            
            // 更新能量块（原版逻辑）
            if (barTops[i] > 0 && barTops[i] < kugouBlockTop[i]) {
                kugouBlockTop[i] = barTops[i] - blockHeight - kugouDistance;
            } else {
                kugouBlockTop[i] = kugouBlockTop[i] + kugouBlockSpeed;
            }
        }
        
        // 计算每根柱子的x坐标
        float[] colLeft = new float[currentCount];
        float[] colRight = new float[currentCount];
        for (int i = 0; i < currentCount; i++) {
            if (i == 0) {
                colLeft[i] = compensate;
            } else {
                colLeft[i] = colRight[i - 1] + kugouSpacing;
            }
            colRight[i] = colLeft[i] + colWidth;
        }
        
        // 初始化渐变（严格匹配原版onSizeChanged中的两段渐变）
        if (kugouGradient1 == null && viewWidth > 0 && viewHeight > 0) {
            // 偶数组：右→左，半透明暖色
            kugouGradient1 = new LinearGradient(viewWidth, 0f, 0f, viewHeight,
                    new int[]{0xCCFDF4BE, 0xCCFEDBB6, 0xCCFEC9C7, 0xCCFEC2DF, 0xCCFDB2E6},
                    new float[]{0, 0.25f, 0.5f, 0.75f, 1f},
                    Shader.TileMode.CLAMP);
            // 奇数组：左→右，渐显暖色
            kugouGradient2 = new LinearGradient(0f, 0f, viewWidth, viewHeight,
                    new int[]{0x4DFDF4BE, 0x7DFEDBB6, 0xBAFEC9C7, 0xE5FEC2DF, 0xFFFDB2E6},
                    new float[]{0, 0.25f, 0.5f, 0.75f, 1f},
                    Shader.TileMode.CLAMP);
        }
        
        // 绘制第一组（前半段，右移aaa）— 偶数组渐变
        if (kugouGradient1 != null) {
            kugouPaint.setShader(kugouGradient1);
            for (int i = 0; i < halfCount; i++) {
                float left = colLeft[i] + aaa;
                float right = colRight[i] + aaa;
                float blockTop = kugouBlockTop[i];
                float blockBottom = blockTop + blockHeight;
                if (blockBottom > viewHeight) {
                    blockBottom = viewHeight;
                    blockTop = viewHeight - blockHeight;
                }
                canvas.drawRect(left, blockTop, right, blockBottom, kugouPaint);
            }
        }
        
        // 绘制第二组（后半段，左移aaa）— 奇数组渐变
        if (kugouGradient2 != null) {
            kugouPaint.setShader(kugouGradient2);
            int offset = halfCount - 1; // 原版: reallyData.get(0).size() - 1
            for (int i = 0; i < halfCount; i++) {
                int srcIdx = i + offset;
                float left = colLeft[srcIdx] - aaa;
                float right = colRight[srcIdx] - aaa;
                float blockTop = kugouBlockTop[srcIdx];
                float blockBottom = blockTop + blockHeight;
                if (blockBottom > viewHeight) {
                    blockBottom = viewHeight;
                    blockTop = viewHeight - blockHeight;
                }
                canvas.drawRect(left, blockTop, right, blockBottom, kugouPaint);
            }
        }
    }
    
    /**
     * 绘制AiVoiceView频谱（严格匹配原版AiVoiceView）
     * 特征：3条cubic贝塞尔闭合曲线（红/绿/蓝），LIGHTEN混合模式叠加
     * 每条曲线用两个cubicTo形成上下对称的叶形
     * FFT数据通过getEnergyByPoint公式计算能量值
     * 高度超频谱区域时按比例缩放
     */
    private void drawAiVoice(Canvas canvas) {
        float viewWidth = getWidth();
        float viewHeight = getHeight();
        float defultY = viewHeight / 2f;
        
        // 平滑过渡（替代原版直接用setWaveData赋值）
        for (int i = 0; i < currentCount && i < barHeights.length; i++) {
            barHeights[i] += (targetBarHeights[i] - barHeights[i]) * 0.5f;
        }
        
        // 原版 getEnergyByPoint: 计算每条曲线的能量值
        // index = data.length * (end + start) / 2
        // if index < data.length: (abs(abs(data[index])) - 2) * 1.5
        // else: (abs(abs(data[data.length - 1])) + 2) * 1.5
        float[] energies = new float[3];
        float maxEnergy = 0f;
        for (int i = 0; i < 3; i++) {
            int index = (int) (barHeights.length * (aiVoiceEnd[i] + aiVoiceStart[i]) / 2f);
            if (index < barHeights.length) {
                energies[i] = (float) ((Math.abs(Math.abs(barHeights[index])) - 2) * 1.5);
            } else {
                energies[i] = (float) ((Math.abs(Math.abs(barHeights[barHeights.length - 1])) + 2) * 1.5);
            }
            if (energies[i] < 0) energies[i] = 0;
            if (energies[i] > maxEnergy) maxEnergy = energies[i];
        }
        
        // 缩放：如果最大能量超过频谱区域上半（defultY），按比例压缩
        float scale = 1.0f;
        if (maxEnergy > defultY) {
            scale = defultY / maxEnergy;
        }
        
        // 严格匹配原版 setWaveData 逻辑
        for (int i = 0; i < 3; i++) {
            float energy = energies[i] * scale;
            
            aiVoicePaths[i].reset();
            aiVoicePaths[i].moveTo(viewWidth * aiVoiceStart[i], defultY);
            // 上半弧线（原版第一个cubicTo）
            aiVoicePaths[i].cubicTo(
                viewWidth * aiVoiceStart[i], defultY,
                viewWidth * (aiVoiceEnd[i] + aiVoiceStart[i]) / 2, defultY - energy,
                viewWidth * aiVoiceEnd[i], defultY
            );
            // 下半弧线（原版第二个cubicTo）
            aiVoicePaths[i].cubicTo(
                viewWidth * aiVoiceEnd[i], defultY,
                viewWidth * (aiVoiceStart[i] + aiVoiceEnd[i]) / 2, defultY + energy,
                viewWidth * aiVoiceStart[i], defultY
            );
            aiVoicePaths[i].close();
            
            aiVoicePaint.setColor(aiVoiceColors[i]);
            canvas.drawPath(aiVoicePaths[i], aiVoicePaint);
        }
    }
    
    /**
     * 绘制WaveColumnformView频谱（严格匹配原版WaveColumnformView）
     * 特征：256个圆角矩形柱，从中心上下对称展开，高度随音频数据变化
     * 水平渐变（两端30%alpha→中间100%alpha），主色调#F53F3F
     * 高度超频谱区域时按比例压缩
     */
    private void drawWaveColumn(Canvas canvas) {
        float viewWidth = getWidth();
        float viewHeight = getHeight();
        float defultY = viewHeight / 2f;
        
        // 原版 spacing = getWidth() / SAMPLE_SIZE + spacingOffset
        int spacing = (int) (viewWidth / waveColumnCount) + waveColumnSpacingOffset;
        
        // 更新渐变shader（匹配原版onLayout中的水平渐变）
        if (waveColumnPaint.getShader() == null && viewWidth > 0 && viewHeight > 0) {
            waveColumnPaint.setShader(new LinearGradient(0f, viewHeight / 2f, viewWidth, viewHeight / 2f,
                    new int[]{
                            getColorWithAlpha(0.3f, waveColumnMainColor),
                            getColorWithAlpha(1f, waveColumnMainColor),
                            getColorWithAlpha(1f, waveColumnMainColor),
                            getColorWithAlpha(0.3f, waveColumnMainColor)
                    },
                    new float[]{0, 0.1f, 0.9f, 1f},
                    Shader.TileMode.CLAMP));
        }
        
        // 平滑过渡
        for (int i = 0; i < currentCount && i < barHeights.length; i++) {
            barHeights[i] += (targetBarHeights[i] - barHeights[i]) * 0.5f;
        }
        
        // 找出最大偏移量，用于按比例缩放
        // 原版 getOffsetY: top = data*LAGER_OFFSET + centerHolder, bottom = -data*LAGER_OFFSET - centerHolder
        // 单侧最大偏移 = maxData * LAGER_OFFSET + centerHolder
        float maxOffset = 0f;
        for (int i = 0; i < currentCount && i < barHeights.length; i++) {
            float offset = barHeights[i] * WAVECOLUMN_LAGER_OFFSET + waveColumnCenterHolder;
            if (offset > maxOffset) maxOffset = offset;
        }
        
        // 缩放：如果单侧最大偏移超过频谱区域上半（defultY），按比例压缩
        float scale = 1.0f;
        if (maxOffset > defultY) {
            scale = defultY / maxOffset;
        }
        
        // 原版 setWaveData 逻辑：逐个构造RectF并绘制
        float left = 0f;
        for (int i = 0; i < currentCount && i < barHeights.length && left < viewWidth; i++) {
            float data = barHeights[i];
            // 原版 getOffsetY
            float topOffset = data * WAVECOLUMN_LAGER_OFFSET + waveColumnCenterHolder;
            float bottomOffset = data * WAVECOLUMN_LAGER_OFFSET + waveColumnCenterHolder;
            
            // 应用缩放
            topOffset *= scale;
            bottomOffset *= scale;
            
            float top = defultY - bottomOffset;   // 上边界（原版 rect.bottom = defultY + (-data*10 - 20)）
            float bottom = defultY + topOffset;     // 下边界（原版 rect.top = defultY + (data*10 + 20)）
            float right = left + WAVECOLUMN_RECT_WIDTH;
            
            canvas.drawRoundRect(left, top, right, bottom,
                    WAVECOLUMN_ROUND_RX, WAVECOLUMN_ROUND_RY, waveColumnPaint);
            
            left = right + spacing;
        }
    }
    
    /**
     * 带alpha的颜色生成（严格匹配原版WaveColumnformView.getColorWithAlpha）
     */
    private int getColorWithAlpha(float alpha, int baseColor) {
        int a = Math.min(255, Math.max(0, (int) (alpha * 255))) << 24;
        int rgb = 0x00ffffff & baseColor;
        return a + rgb;
    }
    
    /**
     * 绘制圆环频谱（围绕封面的放射状频谱）
     * 三种子模式：柱状/爆炸/波形
     * 使用外部传入的封面位置信息（coverCenterX/Y, coverRadius）
     */
    private void drawRing(Canvas canvas) {
        float cx = coverCenterX;
        float cy = coverCenterY;
        float density = getResources().getDisplayMetrics().density;
        float baseRadius = coverRadius + 4 * density; // 基线在封面边缘外4dp
        int total = currentCount;
        
        // 原版线宽计算（严格匹配AttachmentRingView setWaveData）:
        // totalLength = 2 * π * radius
        // eachWidthByDataLength = totalLength / total
        // betweenWidth = between * totalLength / 360
        // width = eachWidthByDataLength - betweenWidth
        float totalLength = (float) (2 * Math.PI * baseRadius);
        float eachWidthByDataLength = totalLength / total;
        float eachWidthByAngle = totalLength / 360f;
        float betweenWidth = ringBetween * eachWidthByAngle;
        float lineWidth = eachWidthByDataLength - betweenWidth;
        if (lineWidth < 1f) lineWidth = 1f;
        
        // start: 原版直接+start当像素用，不做dp转换
        // float startPx = ringStart; // 10px
        
        // 更新平滑高度
        for (int i = 0; i < currentCount; i++) {
            barHeights[i] += (targetBarHeights[i] - barHeights[i]) * 0.5f;
        }
        
        // 旋转：原版 isRotate 每帧+1°，isRandom 用随机跳跃角度
        float currentRotation;
        if (ringRandomAngle) {
            currentRotation = ringRandomAngleValue;
        } else {
            currentRotation = ringRotation;
        }
        
        canvas.save();
        canvas.rotate(currentRotation, cx, cy);
        
        // 计算所有频段的角度和端点
        // 原版: positionAngle = i * 1.0f / total * 360
        float[][] innerPoints = new float[total][2];
        float[][] outerPoints = new float[total][2];
        
        for (int i = 0; i < total; i++) {
            float positionAngle = i * 1.0f / total * 360f;
            double rad = Math.toRadians(positionAngle + currentRotation);
            // 不对，原版rotation已经加在positionAngle中了
            // 但我们用canvas.rotate处理了旋转，所以这里只用positionAngle
            double radNoRot = Math.toRadians(positionAngle);
            
            // 基线上的点: calcPoint(cx, cy, radius, angle, start)
            innerPoints[i][0] = (float) (cx + baseRadius * Math.cos(radNoRot));
            innerPoints[i][1] = (float) (cy + baseRadius * Math.sin(radNoRot));
            
            // 外端点: calcPoint(cx, cy, radius + data[i]*1.5 + start, angle, end)
            // 原版: (int)(radius + data[i] * 1.5) + start
            float outerRadius = baseRadius + barHeights[i] * 1.5f + ringStart;
            outerPoints[i][0] = (float) (cx + outerRadius * Math.cos(radNoRot));
            outerPoints[i][1] = (float) (cy + outerRadius * Math.sin(radNoRot));
        }
        
        // 根据子模式绘制
        switch (ringSubMode) {
            case RING_COLUMNAR:
                drawRingColumnar(canvas, total, lineWidth, innerPoints, outerPoints);
                break;
            case RING_BOMB:
                drawRingBomb(canvas, total, lineWidth, innerPoints, outerPoints);
                break;
            case RING_WAVE:
                drawRingWaveForm(canvas, total, innerPoints, outerPoints);
                break;
        }
        
        canvas.restore();
        
        // 更新旋转角度（isRotate模式：每帧+1°，匹配原版 degress++）
        if (!ringRandomAngle) {
            ringRotation += 1f;
            if (ringRotation >= 360f) ringRotation -= 360f;
        }
        
        // 随机角度跳跃：每300帧随机切换（匹配原版 x > 300）
        ringFrameCounter++;
        if (ringFrameCounter > 300) {
            int r = random.nextInt(4) + 1;
            switch (r) {
                case 1: ringRandomAngleValue = 90; break;
                case 2: ringRandomAngleValue = 180; break;
                case 3: ringRandomAngleValue = 270; break;
                case 4: ringRandomAngleValue = 360; break;
            }
            ringFrameCounter = 0;
        }
    }
    
    /**
     * 圆环柱状模式：白色粗线从基线向外放射
     */
    private void drawRingColumnar(Canvas canvas, int total, float lineWidth,
                                   float[][] innerPoints, float[][] outerPoints) {
        ringPaint.setColor(Color.WHITE);
        ringPaint.setStyle(Paint.Style.FILL);
        ringPaint.setStrokeWidth(lineWidth);
        
        for (int i = 0; i < total; i++) {
            canvas.drawLine(
                innerPoints[i][0], innerPoints[i][1],
                outerPoints[i][0], outerPoints[i][1],
                ringPaint);
        }
    }
    
    /**
     * 圆环爆炸模式：半透明白线 + 末端白色圆点
     */
    private void drawRingBomb(Canvas canvas, int total, float lineWidth,
                               float[][] innerPoints, float[][] outerPoints) {
        // 半透明线（原版: Color.argb((int)(ALPHA * 0.3f), RED, GREEN, BLUE) = argb(76, 255, 255, 255)）
        ringPaint.setColor(Color.argb(76, 255, 255, 255));
        ringPaint.setStyle(Paint.Style.FILL);
        ringPaint.setStrokeWidth(lineWidth);
        
        for (int i = 0; i < total; i++) {
            canvas.drawLine(
                innerPoints[i][0], innerPoints[i][1],
                outerPoints[i][0], outerPoints[i][1],
                ringPaint);
        }
        
        // 末端白色点（原版: drawPoint，白色，strokeWidth=lineWidth）
        ringPaint.setColor(Color.WHITE);
        ringPaint.setStrokeWidth(lineWidth);
        
        for (int i = 0; i < total; i++) {
            canvas.drawPoint(outerPoints[i][0], outerPoints[i][1], ringPaint);
        }
    }
    
    /**
     * 圆环波形模式：相邻端点连线形成环形波浪
     */
    private void drawRingWaveForm(Canvas canvas, int total,
                                   float[][] innerPoints, float[][] outerPoints) {
        // 原版波形：仅画相邻外端点连线，线宽=dp2px(1)=1个density像素，颜色半透明白
        float density = getResources().getDisplayMetrics().density;
        ringPaint.setColor(Color.argb(76, 255, 255, 255));
        ringPaint.setStyle(Paint.Style.FILL);
        ringPaint.setStrokeWidth(density); // 匹配原版 Utils.dp2px(1)
        
        for (int i = 0; i < total; i++) {
            if (i == 0) {
                canvas.drawLine(
                    outerPoints[total - 1][0], outerPoints[total - 1][1],
                    outerPoints[0][0], outerPoints[0][1],
                    ringPaint);
            } else {
                canvas.drawLine(
                    outerPoints[i - 1][0], outerPoints[i - 1][1],
                    outerPoints[i][0], outerPoints[i][1],
                    ringPaint);
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
     * 圆环模式返回 RING_INPUT_COUNT（匹配原版SAMPLE_SIZE=256），以便 scope 生效
     */
    public int getBarInputCount() {
        if (currentStyle == STYLE_RING) {
            return RING_INPUT_COUNT;
        }
        if (currentStyle == STYLE_COLUMNAR) {
            return columnarCount;
        }
        if (currentStyle == STYLE_KUGOU) {
            return KUGOU_COUNT;
        }
        if (currentStyle == STYLE_AIVOICE) {
            return AIVOICE_INPUT_COUNT;
        }
        if (currentStyle == STYLE_WAVECOLUMN) {
            return waveColumnCount;
        }
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
            // ========= 圆环模式：原版直传（无log映射，无positionWeight）=========
            // ========= 圆点/波浪线模式：对数映射 =========
            if (currentStyle == STYLE_RING) {
                // 原版 AttachmentRingView: int total = data.length - scope
                // 直接用原始幅度值，barHeights[i] * 1.5 在drawRing中作为射线像素长度
                int total = Math.min(magnitudes.length, RING_INPUT_COUNT) - ringScope;
                if (total < 1) total = 1;
                if (total != currentCount) {
                    currentCount = total;
                    ringCount = total;
                    rebuildArrays();
                }
                float minVal = 2f;
                for (int i = 0; i < currentCount && i < magnitudes.length; i++) {
                    targetBarHeights[i] = Math.max(minVal, magnitudes[i]);
                }
            } else if (currentStyle == STYLE_COLUMNAR) {
                // 原版 ColumnarView: 直传，data[i] * (1 + 3.0) 作为柱高
                int count = Math.min(currentCount, magnitudes.length);
                float minVal = 1f;
                for (int i = 0; i < count; i++) {
                    targetBarHeights[i] = Math.max(minVal, magnitudes[i]);
                }
            } else if (currentStyle == STYLE_KUGOU) {
                // 原版 KugouColumn: 直传，data[i] * (1 + 35) 作为柱高
                int count = Math.min(currentCount, magnitudes.length);
                float minVal = 1f;
                for (int i = 0; i < count; i++) {
                    targetBarHeights[i] = Math.max(minVal, magnitudes[i]);
                }
            } else if (currentStyle == STYLE_AIVOICE) {
                // AiVoiceView: 直传原始幅度值（drawAiVoice用getEnergyByPoint公式处理）
                int count = Math.min(currentCount, magnitudes.length);
                float minVal = 2f;
                for (int i = 0; i < count; i++) {
                    targetBarHeights[i] = Math.max(minVal, magnitudes[i]);
                }
            } else if (currentStyle == STYLE_WAVECOLUMN) {
                // WaveColumnformView: 直传原始幅度值（drawWaveColumn用getOffsetY公式处理）
                int count = Math.min(currentCount, magnitudes.length);
                float minVal = 2f;
                for (int i = 0; i < count; i++) {
                    targetBarHeights[i] = Math.max(minVal, magnitudes[i]);
                }
            } else {
                // 圆点/波浪线模式：对数映射
                float maxHeight = getHeight() * 0.9f;
                float minVal = 6f;
                float logMax = (float) Math.log1p(maxMag > 0 ? maxMag : 1);
                float centerIndex = (currentCount - 1) / 2f;
                
                for (int i = 0; i < currentCount && i < magnitudes.length; i++) {
                    float normalized = (float) Math.log1p(magnitudes[i]) / logMax;
                    normalized = normalized * (2f - normalized);
                    float positionWeight;
                    if (currentStyle == STYLE_DOT) {
                        positionWeight = 1.0f;
                    } else {
                        float distFromCenter = Math.abs(i - centerIndex) / centerIndex;
                        positionWeight = 1.0f - distFromCenter * 0.9f;
                    }
                    float height = normalized * maxHeight * positionWeight;
                    targetBarHeights[i] = Math.max(minVal, height);
                }
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
                case STYLE_RING: minVal = 2f; break;
                case STYLE_COLUMNAR: minVal = 1f; break;
                case STYLE_KUGOU: minVal = 1f; break;
                case STYLE_AIVOICE: minVal = 2f; break;
                case STYLE_WAVECOLUMN: minVal = 2f; break;
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
