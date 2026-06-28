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
 * 支持九种样式：竖条、圆点、波浪线、圆环、ColumnarView、KugouColumn、AiVoiceView、WaveColumnformView、DiffusionRingView
 * 圆环模式有三种子模式：柱状、爆炸、波形
 * 扩散圆环模式无子模式
 */
public class SpectrumView extends View {
    
    private static final String TAG = "SpectrumView";
    
    // 频谱样式常量
    public static final int STYLE_BAR = 0;          // 竖条模式
    public static final int STYLE_DOT = 1;          // 圆点模式
    public static final int STYLE_WAVE = 2;         // 波浪线模式
    public static final int STYLE_RING = 3;         // 圆环模式
    public static final int STYLE_COLUMNAR = 4;     // ColumnarView柱状模式（原版）
    public static final int STYLE_KUGOU = 5;       // KugouColumn酷狗风格柱状模式（原版）
    public static final int STYLE_AIVOICE = 6;     // AiVoiceView AI语音模式（原版）
    public static final int STYLE_WAVECOLUMN = 7;  // WaveColumnformView 波形柱模式（原版）
    public static final int STYLE_DIFFUSION_RING = 8; // DiffusionRingView 扩散圆环模式（原版）
    public static final int STYLE_WAVE_RING = 9;     // WaveRingView 波浪圆环模式（原版）
    
    // 频谱名称数组（对外暴露）
    public static final String[] STYLE_NAMES = {
        "竖条", "圆点", "波浪线", "网易圆环",
        "柱状", "酷狗柱状", "AI语音", "波形柱",
        "扩散圆环", "波浪圆环"
    };
    
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
    private static final String PREFS_NAME = "spectrum";
    private static final String KEY_STYLE = "current_style";
    
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
    
    // 扩散圆环模式状态（严格匹配原版DiffusionRingView）
    private static final int DIFFUSION_THRESHOLD = 500;  // 能量触发阈值
    private static final int DIFFUSION_START_RADIUS = 0; // 扩散起始半径偏移
    private java.util.List<DiffusionRingBean> diffusionRings = new java.util.ArrayList<>();
    private float diffusionTotalEnergy = 0f;    // 累计总能量
    private Paint diffusionPaint;               // 扩散圆环画笔
    
    // 波浪圆环模式状态（基于封面大小动态缩放：内环+2px，外径120%封面）
    private static final float WAVE_RING_OUTER_RATIO = 2.0f;   // 外径 = coverRadius * 2.0
    private static final int WAVE_RING_INNER_OFFSET = 10;              // 内环距封面外沿10px
    private static final int WAVE_RING_SCOPE = 1;               // 能量阈值
    private static final int WAVE_RING_SPEED = 2;               // 能量点扩散速度(px/帧)
    private boolean waveRingIsRotate = true;            // 原版isRotate
    private boolean waveRingIsRandom = false;           // 原版isRandom
    private boolean waveRingIsBase = false;             // 原版isBase（不画基线）
    private boolean waveRingIsWave = true;              // 原版isWave（画波浪）
    private boolean waveRingIsPoint = true;             // 原版isPoint（画能量圆点）
    private boolean waveRingIsPowerOffset = true;       // 原版isPowerOffset（均衡能量）
    private boolean waveRingIsSpread = true;            // 原版isSpread（能量点扩散）
    private float waveRingDegrees = 0f;                 // 旋转角度
    private int waveRingRandomAngle = 0;                // 随机角度值
    private int waveRingFrameCounter = 0;               // 帧计数器
    private java.util.List<WaveRingBean> waveRingList = new java.util.ArrayList<>();
    private java.util.List<Float> waveRingLastRadius = new java.util.ArrayList<>();
    private Paint waveRingPaint;                        // 波浪圆环画笔
    private android.graphics.SweepGradient waveRingSweepGradient; // 渐变
    
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
        
        // 扩散圆环画笔（严格匹配原版DiffusionRingView）
        diffusionPaint = new Paint();
        diffusionPaint.setStrokeWidth(getResources().getDisplayMetrics().density); // dp2px(1)
        diffusionPaint.setAntiAlias(true);
        
        // 波浪圆环画笔（严格匹配原版WaveRingView）
        waveRingPaint = new Paint();
        waveRingPaint.setAntiAlias(true);
        
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
        
        // 恢复上次的频谱样式
        int savedStyle = getContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .getInt(KEY_STYLE, STYLE_BAR);
        if (savedStyle >= 0 && savedStyle <= STYLE_WAVE_RING) {
            currentStyle = savedStyle;
            initStyleParams(savedStyle);
        }
        
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
                        float minVal = getMinVal(currentStyle);
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
     * 切换频谱样式：竖条 → 圆点 → 波浪线 → 圆环 → ... → 竖条
     */
    public void switchStyle() {
        int next = (currentStyle + 1) % (STYLE_WAVE_RING + 1);
        setStyle(next);
    }
    
    /**
     * 获取当前频谱样式
     */
    public int getCurrentStyle() {
        return currentStyle;
    }
    
    /**
     * 直接设置频谱样式（switchStyle也调用此方法）
     */
    public void setStyle(int style) {
        if (style < 0 || style > STYLE_WAVE_RING) return;
        currentStyle = style;
        // 持久化保存
        getContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .edit().putInt(KEY_STYLE, style).apply();
        initStyleParams(style);
        rebuildArrays();
        float totalSpacing = barSpacing * (currentCount - 1);
        barWidth = (getWidth() - totalSpacing) / currentCount;
        if (currentStyle != STYLE_KUGOU) {
            kugouGradient1 = null;
            kugouGradient2 = null;
        }
        if (currentStyle != STYLE_WAVECOLUMN) {
            waveColumnPaint.setShader(null);
        }
        if (currentStyle == STYLE_AIVOICE) {
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        } else {
            setLayerType(LAYER_TYPE_NONE, null);
        }
        requestLayout();
        postInvalidate();
    }
    
    /**
     * 根据样式初始化currentCount和相关状态
     */
    private void initStyleParams(int style) {
        switch (style) {
            case STYLE_BAR:
                currentCount = barCount;
                break;
            case STYLE_DOT:
            case STYLE_WAVE:
                currentCount = DOT_COUNT;
                break;
            case STYLE_RING:
                ringCount = RING_INPUT_COUNT - ringScope;
                currentCount = ringCount;
                break;
            case STYLE_COLUMNAR:
                columnarCount = (getWidth() > getHeight()) ? COLUMNAR_COUNT_LANDSCAPE : COLUMNAR_COUNT_PORTRAIT;
                currentCount = columnarCount;
                columnarBlockTop = null;
                break;
            case STYLE_KUGOU:
                currentCount = KUGOU_COUNT;
                kugouBlockTop = null;
                break;
            case STYLE_AIVOICE:
                currentCount = AIVOICE_INPUT_COUNT;
                break;
            case STYLE_WAVECOLUMN:
                waveColumnCount = (getWidth() > getHeight()) ? WAVECOLUMN_COUNT_LANDSCAPE : WAVECOLUMN_COUNT_PORTRAIT;
                currentCount = waveColumnCount;
                break;
            case STYLE_DIFFUSION_RING:
                diffusionRings.clear();
                currentCount = 1;
                break;
            case STYLE_WAVE_RING:
                waveRingList.clear();
                waveRingLastRadius.clear();
                currentCount = 1;
                break;
        }
        rebuildArrays();
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
     * 当前是否为需要铺满rootLayout覆盖封面的模式（圆环/扩散圆环）
     */
    public boolean isCoverOverlayMode() {
        return isOverlayStyle(currentStyle);
    }

    /**
     * 判断指定频谱样式是否为覆盖封面的圆形模式（圆环/扩散圆环/波浪圆环）
     */
    public static boolean isOverlayStyle(int style) {
        return style == STYLE_RING || style == STYLE_DIFFUSION_RING || style == STYLE_WAVE_RING;
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
     * 圆环模式下的触摸事件：单击封面区域切换子模式
     * 点击封面外区域不拦截（让触摸穿透到其他控件）
     */
    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        if (currentStyle != STYLE_RING) {
            return super.onTouchEvent(event);
        }
        if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
            float dx = event.getX() - coverCenterX;
            float dy = event.getY() - coverCenterY;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            // 封面区域内拦截触摸（覆盖封面本身的点击），封面外穿透
            return dist <= coverRadius;
        }
        if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
            float dx = event.getX() - coverCenterX;
            float dy = event.getY() - coverCenterY;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            
            if (dist <= coverRadius) {
                // 圆环模式：点击封面区域切换子模式
                switchRingSubMode();
            }
            return dist <= coverRadius;
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
        
        // WaveRingView渐变：在drawWaveRing中根据coverCenter动态创建
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
            case STYLE_DIFFUSION_RING:
                drawDiffusionRing(canvas);
                break;
            case STYLE_WAVE_RING:
                drawWaveRing(canvas);
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
            case STYLE_DIFFUSION_RING: {
                // DiffusionRingView静态：白色细圆环基线
                float dCx = coverCenterX;
                float dCy = coverCenterY;
                float dRadius = coverRadius + 4 * getResources().getDisplayMetrics().density;
                diffusionPaint.setStyle(Paint.Style.STROKE);
                diffusionPaint.setColor(Color.argb(60, 255, 255, 255));
                canvas.drawCircle(dCx, dCy, dRadius, diffusionPaint);
                break;
            }
            case STYLE_WAVE_RING: {
                // WaveRingView静态：白色细圆环基线，外径120%封面
                float wCx = coverCenterX;
                float wCy = coverCenterY;
                float wRadius = coverRadius * WAVE_RING_OUTER_RATIO;
                waveRingPaint.setShader(null);
                waveRingPaint.setStyle(Paint.Style.STROKE);
                waveRingPaint.setColor(Color.argb(60, 255, 255, 255));
                waveRingPaint.setStrokeWidth(1f);
                canvas.drawCircle(wCx, wCy, wRadius, waveRingPaint);
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
        // 重置Paint alpha，避免drawStatic()残留的alpha污染Shader渲染
        columnarPaint.setAlpha(255);
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
        // 重置Paint alpha，避免drawStatic()残留的alpha污染Shader渲染
        kugouPaint.setAlpha(255);
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
                // 柱身：从barTop到viewHeight
                canvas.drawRect(left, barTops[i], right, viewHeight, kugouPaint);
                // 能量块：顶部跳动的点
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
                // 柱身：从barTop到viewHeight
                canvas.drawRect(left, barTops[srcIdx], right, viewHeight, kugouPaint);
                // 能量块：顶部跳动的点
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
        // 重置Paint alpha，避免drawStatic()残留的alpha污染Shader渲染
        waveColumnPaint.setAlpha(255);
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
        
        // 旋转+随机跳跃叠加：匀速旋转每帧+1°，随机角度额外偏移
        float currentRotation = ringRotation + ringRandomAngleValue;
        
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
        
        // 更新旋转角度（每帧+1°，匀速旋转始终进行）
        ringRotation += 0.3f;
        if (ringRotation >= 360f) ringRotation -= 360f;
        
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
        if (currentStyle == STYLE_DIFFUSION_RING) {
            return RING_INPUT_COUNT; // 与圆环一样需要足够FFT数据计算总能量
        }
        if (currentStyle == STYLE_WAVE_RING) {
            return RING_INPUT_COUNT; // 原版SAMPLE_SIZE=256
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
            float minVal = getMinVal(currentStyle);
            for (int i = 0; i < currentCount; i++) {
                targetBarHeights[i] = Math.max(minVal, offsetData[i]);
            }
        } else {
            // ========= 圆环模式：原版直传（无log映射，无positionWeight）=========
            // ========= 圆点/波浪线模式：对数映射 =========
            if (currentStyle == STYLE_RING) {
                int total = Math.min(magnitudes.length, RING_INPUT_COUNT) - ringScope;
                if (total < 1) total = 1;
                if (total != currentCount) {
                    currentCount = total;
                    ringCount = total;
                    rebuildArrays();
                }
                float minVal = getMinVal(currentStyle);
                for (int i = 0; i < currentCount && i < magnitudes.length; i++) {
                    targetBarHeights[i] = Math.max(minVal, magnitudes[i]);
                }
            } else if (currentStyle == STYLE_COLUMNAR || currentStyle == STYLE_KUGOU
                    || currentStyle == STYLE_AIVOICE || currentStyle == STYLE_WAVECOLUMN) {
                // 直传模式：直接用原始幅度值（各draw方法自行放大）
                int count = Math.min(currentCount, magnitudes.length);
                float minVal = getMinVal(currentStyle);
                for (int i = 0; i < count; i++) {
                    targetBarHeights[i] = Math.max(minVal, magnitudes[i]);
                }
            } else if (currentStyle == STYLE_DIFFUSION_RING) {
                // DiffusionRingView: 基于总能量决定是否生成扩散环
                // 原版逻辑: positionPercent = totalEnergy / 4000, 触发条件 totalEnergy > 2500
                // 但原版 totalEnergy 来自 VisualizerHelper，数值范围与 DFT magnitudes 不同
                // 使用 maxMag 动态计算阈值，确保节奏强时频繁触发
                float totalEnergy = 0f;
                for (float m : magnitudes) {
                    totalEnergy += m;
                }
                float positionPercent = totalEnergy / (maxMag * magnitudes.length * 0.5f + 1f);
                // 阈值：maxMag 的 2 倍（节奏强时 maxMag 大，阈值提高但更容易超过）
                if (totalEnergy > maxMag * 2.5f) {
                    DiffusionRingBean bean = new DiffusionRingBean();
                    bean.alpha = 255;    // AppConstant.ALPHA = 255
                    float density = getResources().getDisplayMetrics().density;
                    bean.radius = coverRadius + 4 * density + DIFFUSION_START_RADIUS;
                    bean.angle = positionPercent * 360f;
                    diffusionRings.add(bean);
                }
            } else if (currentStyle == STYLE_WAVE_RING) {
                // WaveRingView: 内环+2px，外径120%封面，参数按封面大小自适应
                float cx = coverCenterX;
                float cy = coverCenterY;
                float baseRadius = coverRadius + WAVE_RING_INNER_OFFSET; // 内环距封面外沿2px
                float outerLimit = coverRadius * WAVE_RING_OUTER_RATIO;  // 外径上限
                float amplitude = outerLimit - baseRadius;                // 波浪可用振幅空间
                // 采样数：根据振幅空间自适应，保证视觉密度合理
                int total = Math.min(magnitudes.length, RING_INPUT_COUNT);
                if (total > amplitude * 0.5f) total = (int)(amplitude * 0.5f);
                if (total < 8) total = 8;
                // basePoint = 能量圆点的默认半径位置（约在振幅中段）
                float basePoint = baseRadius + amplitude * 0.3f;
                
                // lastRadius spread逻辑
                if (waveRingList.size() > 0) {
                    if (waveRingLastRadius.size() == 0 || waveRingList.size() != waveRingLastRadius.size()) {
                        waveRingLastRadius.clear();
                        for (int i = 0; i < total; i++) {
                            waveRingLastRadius.add(basePoint);
                        }
                    }
                    for (int i = 0; i < waveRingLastRadius.size(); i++) {
                        if (i < waveRingList.size()) {
                            if (waveRingList.get(i).radius < waveRingLastRadius.get(i)) {
                                waveRingLastRadius.set(i, waveRingList.get(i).radius);
                            } else {
                                if (waveRingIsSpread) {
                                    waveRingLastRadius.set(i, waveRingLastRadius.get(i) + WAVE_RING_SPEED);
                                } else {
                                    waveRingLastRadius.set(i, waveRingList.get(i).radius);
                                }
                            }
                        }
                    }
                }
                
                waveRingList.clear();
                
                for (int i = 0; i < total && i < magnitudes.length; i++) {
                    float positionAngle = i * 1.0f / total * 360f;
                    float currentRotation = waveRingDegrees + waveRingRandomAngle;
                    float angle = positionAngle + currentRotation;
                    float powerPercent = waveRingIsPowerOffset ? 0f : magnitudes[i] / 256f;
                    float dataVal = magnitudes[i];
                    // dataVal归一化到0~1，再映射到amplitude
                    float normVal = dataVal / 256f;
                    
                    WaveRingBean bean = new WaveRingBean();
                    bean.angle = angle;
                    bean.powerPercent = normVal;
                    
                    double rad = Math.toRadians(angle);
                    
                    if (dataVal > WAVE_RING_SCOPE) {
                        // outter: 内环向内收缩（最多收缩到baseRadius的80%）
                        float outterContraction = normVal * amplitude * 0.4f + powerPercent * normVal * amplitude * 0.1f;
                        float outterR = Math.max(baseRadius * 0.8f, baseRadius - outterContraction);
                        bean.outterX = (float) (cx + outterR * Math.cos(rad));
                        bean.outterY = (float) (cy + outterR * Math.sin(rad));
                        // inner: 内环向外扩展，不超过外径上限
                        float innerExpansion = normVal * amplitude * 0.7f;
                        float innerR = Math.min(outerLimit, baseRadius + innerExpansion);
                        bean.innerX = (float) (cx + innerR * Math.cos(rad));
                        bean.innerY = (float) (cy + innerR * Math.sin(rad));
                        // center: 基线位置
                        bean.centerX = (float) (cx + baseRadius * Math.cos(rad));
                        bean.centerY = (float) (cy + baseRadius * Math.sin(rad));
                        // radius: 能量圆点位置
                        bean.radius = basePoint + normVal * amplitude * 0.3f;
                    } else {
                        bean.radius = basePoint;
                        bean.innerX = (float) (cx + baseRadius * Math.cos(rad));
                        bean.innerY = (float) (cy + baseRadius * Math.sin(rad));
                        bean.outterX = bean.innerX;
                        bean.outterY = bean.innerY;
                        bean.centerX = bean.innerX;
                        bean.centerY = bean.innerY;
                    }
                    waveRingList.add(bean);
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
    /**
     * 获取播放中各模式的最小值
     */
    private static float getMinVal(int style) {
        switch (style) {
            case STYLE_BAR: return 4f;
            case STYLE_RING:
            case STYLE_AIVOICE:
            case STYLE_WAVECOLUMN:
            case STYLE_DIFFUSION_RING:
            case STYLE_WAVE_RING: return 2f;
            case STYLE_COLUMNAR:
            case STYLE_KUGOU: return 1f;
            default: return 6f;
        }
    }
    
    /**
     * 获取停止时各模式的最小值（较高，静止状态更明显）
     */
    private static float getStopMinVal(int style) {
        if (style == STYLE_BAR) return 8f;
        if (style == STYLE_COLUMNAR || style == STYLE_KUGOU) return 1f;
        return 2f;
    }
    
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
            float minVal = getStopMinVal(currentStyle);
            for (int i = 0; i < currentCount; i++) {
                targetBarHeights[i] = minVal;
                barHeights[i] = minVal;
                peakHeights[i] = minVal;
                peakDecayTimers[i] = 0;
            }
        }
        postInvalidate();
    }
    
    /**
     * 扩散圆环模式绘制（严格匹配原版DiffusionRingView）
     * 效果：能量超过阈值时从封面边缘生成扩散圆环，逐渐扩大淡出消失
     */
    private void drawDiffusionRing(Canvas canvas) {
        float cx = coverCenterX;
        float cy = coverCenterY;
        float density = getResources().getDisplayMetrics().density;
        float baseRadius = coverRadius + 4 * density; // 与圆环模式一致，封面边缘外4dp
        
        // 原版: 清理 disable 的 ring（每帧只移除一个，匹配原版 for+break）
        for (int i = 0; i < diffusionRings.size(); i++) {
            if (!diffusionRings.get(i).enable) {
                diffusionRings.remove(i);
                break;
            }
        }
        
        // 原版: 画基线圆（白色描边）
        diffusionPaint.setStyle(Paint.Style.STROKE);
        diffusionPaint.setColor(Color.WHITE);
        canvas.drawCircle(cx, cy, baseRadius + DIFFUSION_START_RADIUS, diffusionPaint);
        
        // 原版: 遍历扩散圆环列表，每个ring: alpha-=5, radius+=5
        for (int i = 0; i < diffusionRings.size(); i++) {
            DiffusionRingBean ring = diffusionRings.get(i);
            ring.alpha -= 5;
            ring.radius += 5;
            if (ring.alpha <= 0) {
                ring.enable = false;
            }
            if (ring.enable) {
                // 画扩散圆弧（原版: drawArc，STROKE，color=argb(alpha, RED, GREEN, BLUE)）
                // 原版 AppConstant: ALPHA=255, RED=255, GREEN=255, BLUE=255
                diffusionPaint.setStyle(Paint.Style.STROKE);
                diffusionPaint.setColor(Color.argb(ring.alpha, 255, 255, 255));
                // 原版用 drawArc 画完整圆: left=top=cx-radius, right=bottom=cx+radius, 0~360
                canvas.drawArc(cx - ring.radius, cy - ring.radius,
                        cx + ring.radius, cy + ring.radius,
                        0, 360, false, diffusionPaint);
                
                // 画能量高亮点（原版: calcPoint计算圆弧上的点，drawArc画小圆点）
                // 原版: Utils.calcPoint(centerX, centerY, radius, angle, point)
                float rad = (float) Math.toRadians(ring.angle);
                float pointX = (float) (cx + ring.radius * Math.cos(rad));
                float pointY = (float) (cy + ring.radius * Math.sin(rad));
                diffusionPaint.setStyle(Paint.Style.FILL);
                diffusionPaint.setColor(Color.argb(ring.alpha, 255, 255, 255));
                // 原版: drawArc(point.x-10, point.y-10, point.x+10, point.y+10, 0, 360, true)
                canvas.drawArc(pointX - 10, pointY - 10,
                        pointX + 10, pointY + 10,
                        0, 360, true, diffusionPaint);
            }
        }
    }
    
    /**
     * 扩散圆环数据Bean（严格匹配原版DiffusionRingViewBean）
     */
    private static class DiffusionRingBean {
        float radius;       // 当前扩散半径
        int alpha;          // 当前透明度
        boolean enable = true;  // 是否有效
        float angle;        // 能量高亮点角度
    }
    
    /**
     * 波浪圆环模式绘制（内环+2px，外径120%封面，不显示文字）
     */
    private void drawWaveRing(Canvas canvas) {
        float cx = coverCenterX;
        float cy = coverCenterY;
        float baseRadius = coverRadius + WAVE_RING_INNER_OFFSET; // 内环距封面外沿2px
        
        int total = waveRingList.size();
        if (total == 0) return;
        
        float amplitude = coverRadius * (WAVE_RING_OUTER_RATIO - 1f);
        
        // sweepGradient以圆心为中心，每帧更新中心点
        waveRingSweepGradient = new android.graphics.SweepGradient(cx, cy,
                new int[]{Color.GREEN, Color.RED, Color.BLUE, Color.GREEN}, null);
        
        waveRingPaint.setShader(waveRingSweepGradient);
        waveRingPaint.setColor(Color.WHITE);
        // 线宽按振幅比例缩放，避免粗线淹没小空间
        float strokeWidth = Math.max(1f, amplitude * 0.015f);
        waveRingPaint.setStrokeWidth(strokeWidth);
        
        for (int i = 0; i < total; i++) {
            WaveRingBean bean = waveRingList.get(i);
            
            // isBase: 画基线（从inner到outter的线段）
            if (waveRingIsBase) {
                waveRingPaint.setStyle(Paint.Style.STROKE);
                waveRingPaint.setStrokeWidth(Math.max(0.5f, amplitude * 0.008f));
                canvas.drawLine(bean.innerX, bean.innerY, bean.outterX, bean.outterY, waveRingPaint);
            }
            
            // isPoint: 画能量圆点（使用lastRadius位置，有扩散效果）
            if (waveRingIsPoint && i < waveRingLastRadius.size()) {
                waveRingPaint.setStyle(Paint.Style.FILL);
                float lastR = waveRingLastRadius.get(i);
                double rad = Math.toRadians(bean.angle);
                float px = (float) (cx + lastR * Math.cos(rad));
                float py = (float) (cy + lastR * Math.sin(rad));
                float dotR = Math.max(1f, amplitude * 0.02f);
                canvas.drawArc(px - dotR, py - dotR, px + dotR, py + dotR, 0, 360, true, waveRingPaint);
            }
            
            // isWave: 画波浪（锯齿形闭合曲线）
            if (waveRingIsWave) {
                waveRingPaint.setStyle(Paint.Style.STROKE);
                waveRingPaint.setStrokeWidth(Math.max(1f, amplitude * 0.012f));
                
                WaveRingBean prevBean = waveRingList.get(i == 0 ? total - 1 : i - 1);
                WaveRingBean nextBean = waveRingList.get(i == total - 1 ? 0 : i + 1);
                
                // prevCenter→outter, outter→nextCenter, nextCenter→inner, inner→prevCenter
                canvas.drawLine(prevBean.centerX, prevBean.centerY, bean.outterX, bean.outterY, waveRingPaint);
                canvas.drawLine(bean.outterX, bean.outterY, nextBean.centerX, nextBean.centerY, waveRingPaint);
                canvas.drawLine(nextBean.centerX, nextBean.centerY, bean.innerX, bean.innerY, waveRingPaint);
                canvas.drawLine(bean.innerX, bean.innerY, prevBean.centerX, prevBean.centerY, waveRingPaint);
            }
        }
        
        // 旋转更新（匀速旋转始终进行）
        waveRingDegrees += 0.3f;
        if (waveRingDegrees >= 360f) waveRingDegrees -= 360f;
        
        // 随机角度：每300帧切换
        waveRingFrameCounter++;
        if (waveRingFrameCounter > 300) {
            int r = random.nextInt(4) + 1;
            switch (r) {
                case 1: waveRingRandomAngle = 90; break;
                case 2: waveRingRandomAngle = 180; break;
                case 3: waveRingRandomAngle = 270; break;
                case 4: waveRingRandomAngle = 360; break;
            }
            waveRingFrameCounter = 0;
        }
    }
    
    /**
     * 波浪圆环数据Bean（严格匹配原版WaveRingViewBean）
     */
    private static class WaveRingBean {
        float innerX, innerY;   // 内端点
        float centerX, centerY; // 中心点（基线上）
        float outterX, outterY; // 外端点
        float angle;            // 角度位置
        float radius;           // 能量圆点半径位置
        float powerPercent;     // 能量百分比
    }
}
