package com.jingxin.jingxinmusic.view;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

/**
 * 双色光晕背景 View
 * 严格 1:1 移植 QQ 音乐 Hu.glsl 片元着色器逻辑到 Canvas 绘制。
 *
 * 核心原理：
 * - 两个椭圆锚定在屏幕底边以下，只露出上半部分 → 光晕只在下半部分
 * - 椭圆深度函数 d=x²/A²+y²/B²，depth=0.5·(2·min(d,1-d))^1.5 → 三次方衰减
 * - 128×128 LUT 精确预计算深度，每帧仅 translate/rotate/scale + drawBitmap
 * - 颜色过渡 33 步 × 33.33ms ≈ 1.1 秒线性 RGB 插值
 *
 * 参数（对应 LightEffectShader.N() 的传值）：
 * - duration = 4 秒，time 每帧 +33.33ms（~30fps）
 * - aspectRatio = 1.0 固定
 * - cropMinX/Y=0, cropMaxX/Y=1（无裁剪）
 * - isForSurfaceView=0（光晕透明叠加在底色上）
 */
public class AuroraGradientView extends View {


    // === shader 常量 ===
    private static final float PI = (float) Math.PI;
    private static final float DURATION = 4.0f;          // T(4.0f)
    private static final float FRAME_INTERVAL_MS = 33.333f; // BaseShader companion.b()
    private static final float TIME_STEP = FRAME_INTERVAL_MS / 1000f; // 0.03333s
    private static final int COLOR_TRANSITION_STEPS = 33;  // 1/ (1/33.333) ≈ 33步
    private static final int LUT_SIZE = 128;               // 深度 LUT 分辨率

    // === 光晕颜色 ===
    private int firstColor = Color.parseColor("#26A69A");   // 默认青绿兜底
    private int secondColor = Color.parseColor("#80CBC4");

    // === 底色常量（白天/夜间），供外部同步根布局背景 ===
    public static final int BG_DAY = 0xFFEDF0F2;
    public static final int BG_NIGHT = 0xFF181A22;

    // === 颜色过渡 ===
    private int prevFirstColor = firstColor;
    private int prevSecondColor = secondColor;
    private int targetFirstColor = firstColor;
    private int targetSecondColor = secondColor;
    private float colorProgress = 1.0f;  // 0→1，1=过渡完成
    private boolean colorTransitioning = false;

    // === 动画时间 ===
    private float shaderTime = 0f;
    private boolean animating = false;

    // === 宿主生命周期闸门 ===
    // NXP 8.1 等车机 ROM 退后台不下发窗口隐藏（onStop 后 isShown 仍 true），
    // 视图层止损不触发，帧链后台空转 ~28Hz 拖累整机（诊断日志 2026-10-31：
    // 15:26:56 onStop 后心跳仍持续递增）。宿主 onStop 挂起、onStart 复活；
    // 无宿主接线的场景默认 true 保持原行为
    private boolean hostResumed = true;

    /** 宿主生命周期闸门：true=允许/复活帧链（幂等），false=挂起帧链（后台零负载） */
    public void setHostResumed(boolean resumed) {
        if (hostResumed == resumed) return;
        hostResumed = resumed;
        if (resumed) startAnimation();
        else stopAnimation();
    }

    // === LUT ===
    private Bitmap lutBitmap1; // 椭圆1深度 LUT
    private Bitmap lutBitmap2; // 椭圆2深度 LUT
    private int[] lutPixels1;
    private int[] lutPixels2;
    private boolean lutDirty = true;

    // === 绘制工具（成员变量复用，零分配） ===
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix1 = new Matrix();
    private final Matrix matrix2 = new Matrix();

    // === Handler ===
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable frameRunnable = new Runnable() {
        @Override
        public void run() {
            doFrame();
        }
    };

    // === 夜间模式 ===
    private boolean isNightMode = false;

    // === 透明底模式 ===
    // 沉浸模式下光晕作为氛围叠加在封面上，不画底层底色，只画光晕椭圆
    private boolean transparentBackground = false;

    public AuroraGradientView(android.content.Context context) {
        super(context);
        init();
    }

    public AuroraGradientView(android.content.Context context, android.util.AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public AuroraGradientView(android.content.Context context, android.util.AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // 默认硬件加速（与全页普通视图同主干道渲染）。
        // 不设 SOFTWARE 软件层：UNISOC uis7862a 车机乐酷画中画窗口 resize 时（VirtualDisplay
        // surface 拆装），软层位图"进程内绘制→HWUI纹理上传→合成"路径不重协商，画面全黑，
        // 尺寸复原/重建才恢复（2026-11 uis7862a 用户实测：单分屏调宽/高必黑、双分屏不黑）。
        // 硬件加速视图（StarfieldView/blurBg 等）同场景从未复现——drawColor/drawBitmap
        // 全是硬加速标准操作，改道零视觉差异。
        // 注：历史注释曾混淆 LAYER_TYPE_HARDWARE（离屏纹理缓存，确有色偏）与硬件加速，两者不是一回事
        paint.setAntiAlias(true);
        paint.setDither(true);
    }

    /**
     * 设置光晕颜色（对应 QQ 的 magicColor.a()/b()）
     * 自动启动 1.1 秒线性 RGB 过渡。
     */
    public void setColors(int first, int second) {
        if (first == targetFirstColor && second == targetSecondColor) return;
        prevFirstColor = firstColor;
        prevSecondColor = secondColor;
        targetFirstColor = first;
        targetSecondColor = second;
        colorProgress = 0f;
        colorTransitioning = true;
        lutDirty = true;
        if (!animating) startAnimation();
    }

    /**
     * 直接设置颜色（无过渡，首次初始化用）
     */
    public void setColorsDirect(int first, int second) {
        firstColor = first;
        secondColor = second;
        prevFirstColor = first;
        prevSecondColor = second;
        targetFirstColor = first;
        targetSecondColor = second;
        colorProgress = 1.0f;
        colorTransitioning = false;
        lutDirty = true;
        invalidate();
    }

    public void setNightMode(boolean night) {
        if (isNightMode != night) {
            isNightMode = night;
            lutDirty = true; // 日夜切换会改变白天提亮，需重建 LUT
            invalidate();
        }
    }

    /**
     * 设置是否透明底模式。
     * true：不画底层底色，只画光晕椭圆（用于沉浸模式叠加在封面上）
     * false：画完整底色（默认，光晕直接作为页面背景）
     */
    public void setTransparentBackground(boolean transparent) {
        if (transparentBackground != transparent) {
            transparentBackground = transparent;
            invalidate();
        }
    }

    public boolean isTransparentBackground() {
        return transparentBackground;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopAnimation();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) {
            startAnimation();
        } else {
            stopAnimation();
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            startAnimation();
        } else {
            stopAnimation();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // 自愈：尺寸回归（分屏/悬浮窗口 resize）时确保动画链活着。
        // 乐酷 reparent 时 detach/attach 回调可能乱序，stopAnimation 在 startAnimation
        // 之后到达会把自续帧链掐死（View 可见但永不再画→黑屏）；startAnimation 幂等，
        // 正常路径零影响，死链（animating=false）时在此复活
        if (w > 0 && h > 0) startAnimation();
    }

    private void startAnimation() {
        if (animating || !hostResumed) return;
        // View 构造期间（super 触发 onVisibilityChanged）handler 尚未初始化，
        // 此时不启动动画，待 onAttachedToWindow 时 handler 已就绪再正常启动
        if (handler == null) return;
        animating = true;
        handler.post(frameRunnable);
    }

    private void stopAnimation() {
        if (!animating) return;
        animating = false;
        // View 构造期间 handler 尚未初始化，判空保护
        if (handler != null) handler.removeCallbacks(frameRunnable);
    }

    private void doFrame() {
        if (!animating) return;

        long frameStart = System.currentTimeMillis();

        // 链断自检：View 在窗口中且可见但尺寸就绪却停链（reparent 乱序残留），
        // invalidate 已无后续帧驱动——此处主动补偿一帧 keep-alive 不能停，
        // 检测方式即本方法被调用（startAnimation post 过来的）说明链已重接
        if (!isAttachedToWindow() || getWindowVisibility() != VISIBLE || getVisibility() != VISIBLE) {
            // 不可见态不推进动画（省电），链保留在 startAnimation 复活路径
            if (!isAttachedToWindow()) {
                animating = false; // 已脱离窗口，等 re-attach
                return;
            }
        }

        // 空转止损：父 root GONE 时子 View getVisibility() 仍返回 VISIBLE（上述检测的盲区），
        // 尺寸 0x0 的实例画不出任何东西却仍全速跑帧链——4 链并发空转曾把 5.1 弱 GPU 车机
        // 拖到 native 崩溃（诊断日志 2026-10-31：帧率 9fps→3.5fps 衰减后进程无声死亡）。
        // 祖先不可见或零尺寸时挂起帧驱动，双保险等 onSizeChanged/onVisibilityChanged 复活
        if (getWidth() <= 0 || getHeight() <= 0 || !isShown()) {
            animating = false; // 挂起：保留 animating=false 让 startAnimation 复活路径接管
            return;
        }

        // 时间步进（对应 LightEffectShader.D()）
        if (shaderTime >= DURATION) {
            shaderTime = 0f;
        }
        shaderTime += TIME_STEP;

        // 颜色过渡（对应 BaseShader mColorChangeHandler）
        if (colorTransitioning) {
            colorProgress += 1.0f / COLOR_TRANSITION_STEPS;
            if (colorProgress >= 1.0f) {
                colorProgress = 1.0f;
                colorTransitioning = false;
                firstColor = targetFirstColor;
                secondColor = targetSecondColor;
            } else {
                firstColor = lerpColor(prevFirstColor, targetFirstColor, colorProgress);
                secondColor = lerpColor(prevSecondColor, targetSecondColor, colorProgress);
            }
            lutDirty = true;
        }

        invalidate();

        // 下一帧：补偿本帧耗时，确保真实间隔为 33ms（对应 QQ 的 sendEmptyMessageDelayed(0, 33)）
        long elapsed = System.currentTimeMillis() - frameStart;
        long delay = (long) FRAME_INTERVAL_MS - elapsed;
        if (delay < 1) delay = 1;
        handler.postDelayed(frameRunnable, delay);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int viewW = getWidth();
        int viewH = getHeight();
        if (viewW <= 0 || viewH <= 0) return;

        // === 底色（QQ bg1_light/bg1）：整页铺满 ===
        // QQ 播放页整页背景就是此色，上半无光晕区域也是它
        // 透明底模式下不画底色（沉浸模式叠加在封面上，露出封面）
        if (!transparentBackground) {
            int bgColor = isNightMode ? 0xFF181A22 : 0xFFEDF0F2;
            canvas.drawColor(bgColor);
        }

        // 确保 LUT 已生成
        if (lutDirty || lutBitmap1 == null || lutBitmap2 == null) {
            generateLUT();
            lutDirty = false;
        }

        // === 光晕视口：QQ 布局为底部对齐、高度 80%，shader 归一化坐标 [-1,1] 映射到此视口 ===
        int shaderH = (int) (viewH * 0.8f);
        int yOffset = viewH - shaderH;
        float halfW = viewW / 2f;
        float halfH = shaderH / 2f;

        // === 时间参数 ===
        float progressTime = (shaderTime % DURATION) / DURATION;
        float rotateTime = progressTime;
        // 三角波：0→1→0
        progressTime = progressTime < 0.5f ? 2f * progressTime : 1f - 2f * (progressTime - 0.5f);

        // === 椭圆1（firstColor, alpha 0.45）：中心绕锚点(-0.53,-1.09)半径0.3做轨道运动，无形状旋转 ===
        float angle = -2f * PI * rotateTime;
        float cx1 = -0.53f + 0.3f * (float) Math.cos(angle);
        float cy1 = -1.09f + 0.3f * (float) Math.sin(angle);
        float A1 = 1.32f - 0.20f * progressTime;
        float B1 = 1.32f + 0.22f * progressTime;

        // === 椭圆2（secondColor, alpha 0.4）：中心从x=0.74滑向x=0，无旋转 ===
        float cx2 = 0.74f - 0.74f * progressTime;
        float cy2 = -1.073f;
        float A2 = 0.98f + 1.06f * progressTime;
        float B2 = 0.98f + 0.42f * progressTime;

        // 椭圆中心 → 屏幕像素（fragY=+1 是视口顶部）
        float px1 = (cx1 + 1f) * halfW;
        float py1 = yOffset + (1f - cy1) * halfH;
        float px2 = (cx2 + 1f) * halfW;
        float py2 = yOffset + (1f - cy2) * halfH;

        // LUT 覆盖范围 = 椭圆半轴 A/B ×1.2 的两倍
        float drawW1 = 2f * A1 * 1.2f * halfW;
        float drawH1 = 2f * B1 * 1.2f * halfH;
        float drawW2 = 2f * A2 * 1.2f * halfW;
        float drawH2 = 2f * B2 * 1.2f * halfH;

        // 椭圆1：与椭圆2相同处理，只做平移+缩放，不旋转
        matrix1.reset();
        matrix1.postTranslate(-LUT_SIZE / 2f, -LUT_SIZE / 2f);
        matrix1.postScale(drawW1 / LUT_SIZE, drawH1 / LUT_SIZE);
        matrix1.postTranslate(px1, py1);

        // 椭圆2：无旋转
        matrix2.reset();
        matrix2.postTranslate(-LUT_SIZE / 2f, -LUT_SIZE / 2f);
        matrix2.postScale(drawW2 / LUT_SIZE, drawH2 / LUT_SIZE);
        matrix2.postTranslate(px2, py2);

        // 先画椭圆2（底层），再画椭圆1（上层）
        paint.setAlpha(255);
        canvas.drawBitmap(lutBitmap2, matrix2, paint);
        canvas.drawBitmap(lutBitmap1, matrix1, paint);
    }

    /**
     * 生成深度 LUT
     * 精确计算 Hu.glsl 的 ellipse() 深度函数：
     *   d = x²/(A*1.2)² + y²/(B*1.2)²，A=B=1（缩放由 Matrix 处理）
     *   d = clamp(d, 0, 1)
     *   depth = 0.5 * (2 * min(d, 1-d))^1.5
     *   if (d < 0.5) depth = 1 - depth
     *   depth = clamp(depth, 0, 1) ^ 3
     *   alpha = depth * ellipseAlpha
     */
    private void generateLUT() {
        if (lutBitmap1 == null) {
            lutBitmap1 = Bitmap.createBitmap(LUT_SIZE, LUT_SIZE, Bitmap.Config.ARGB_8888);
            lutBitmap2 = Bitmap.createBitmap(LUT_SIZE, LUT_SIZE, Bitmap.Config.ARGB_8888);
            lutPixels1 = new int[LUT_SIZE * LUT_SIZE];
            lutPixels2 = new int[LUT_SIZE * LUT_SIZE];
        }

        // 椭圆1：alpha=0.45, firstColor（白天模式提亮 20%、透明度降 10%）
        generateLUTInternal(lutPixels1, getDisplayColor(firstColor), getDisplayAlpha(0.45f));
        lutBitmap1.setPixels(lutPixels1, 0, LUT_SIZE, 0, 0, LUT_SIZE, LUT_SIZE);

        // 椭圆2：alpha=0.40, secondColor（白天模式提亮 20%、透明度降 10%）
        generateLUTInternal(lutPixels2, getDisplayColor(secondColor), getDisplayAlpha(0.40f));
        lutBitmap2.setPixels(lutPixels2, 0, LUT_SIZE, 0, 0, LUT_SIZE, LUT_SIZE);
    }

    /**
     * 获取用于绘制的光晕颜色。
     * 白天模式：亮度提升 20%（HSV 的 V 值 ×1.2，保留色相/饱和度），使光晕更明亮通透；
     * 夜间模式：亮度降低 15%（HSV 的 V 值 ×0.85），使光晕更暗沉柔和。
     */
    private int getDisplayColor(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        float v = hsv[2] * (isNightMode ? 0.85f : 1.2f);
        if (v > 1f) v = 1f;
        hsv[2] = v;
        return Color.HSVToColor(Color.alpha(color), hsv);
    }

    /**
     * 获取用于绘制的光晕透明度。
     * 白天模式：透明度降低 10%（×0.9），使光晕更柔和淡雅；
     * 夜间模式：使用原始透明度，不调整。
     */
    private float getDisplayAlpha(float alpha) {
        if (isNightMode) return alpha;
        return alpha * 0.9f;
    }

    /**
     * 生成单个椭圆的 LUT 像素
     *
     * QQ 音乐 Hu.glsl 混色方式（isForSurfaceView=0，TextureView 透明合成）：
     * - ellipseColor = vec4(vec3(color), 1.0)  ← alpha 固定 1.0
     * - color = mix(color, ellipseColor, depth)  ← mix 混合所有通道
     * - 输出 color.rgb = C * depth, color.a = depth（depth 已含 ellipseAlpha）
     * - TextureView 透明合成: result = color.rgb * color.a + bg * (1 - color.a)
     *
     * Canvas drawBitmap SRC_OVER 等价公式（非预乘 ARGB_8888）:
     *   result = src.rgb * src.a + dst * (1 - src.a)
     * 与 OpenGL 预乘 alpha 合成数学一致。
     *
     * LUT 归一化坐标 [-1,1] 映射到 fragCoord = nx * (A*1.2), ny * (B*1.2)
     * 代入 shader 公式 d = fragCoord.x²/(A*1.2)² + fragCoord.y²/(B*1.2)² = nx² + ny²
     * 所以这里 A=B=1，d = nx² + ny²（1.2 缩放由 Matrix 的 drawW/drawH 处理）
     */
    private void generateLUTInternal(int[] pixels, int color, float alpha) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);

        for (int j = 0; j < LUT_SIZE; j++) {
            // ny: [-1, 1]，对应 fragCoord.y
            float ny = (j + 0.5f) / LUT_SIZE * 2f - 1f;
            float nySq = ny * ny;
            for (int i = 0; i < LUT_SIZE; i++) {
                // nx: [-1, 1]，对应 fragCoord.x
                float nx = (i + 0.5f) / LUT_SIZE * 2f - 1f;
                float nxSq = nx * nx;

                // d = nx² + ny²（A=B=1，1.2 缩放由绘制 Matrix 处理）
                float d = nxSq + nySq;
                d = Math.min(Math.max(d, 0f), 1f);

                // depth = 0.5 * (2 * min(d, 1-d))^1.5
                float minD = Math.min(d, 1f - d);
                float depth = 0.5f * (float) Math.pow(2f * minD, 1.5f);

                // if (d < 0.5) depth = 1 - depth
                if (d < 0.5f) depth = 1f - depth;

                // clamp + pow(3)
                depth = Math.min(Math.max(depth, 0f), 1f);
                depth = (float) Math.pow(depth, 3f);

                // alpha = depth * ellipseAlpha（Canvas 自动处理预乘）
                int finalAlpha = (int) (depth * alpha * 255);
                if (finalAlpha < 0) finalAlpha = 0;
                if (finalAlpha > 255) finalAlpha = 255;

                pixels[j * LUT_SIZE + i] = Color.argb(finalAlpha, r, g, b);
            }
        }
    }

    /**
     * 线性 RGB 颜色插值（对应 BaseShader.u()）
     */
    private static int lerpColor(int c1, int c2, float t) {
        int a = (int) (Color.alpha(c1) + (Color.alpha(c2) - Color.alpha(c1)) * t);
        int r = (int) (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * t);
        int g = (int) (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * t);
        int b = (int) (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * t);
        return Color.argb(a, r, g, b);
    }
}
