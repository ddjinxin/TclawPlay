package com.jingxin.jingxinmusic.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import com.jingxin.jingxinmusic.util.KrcParser;
import com.jingxin.jingxinmusic.util.ThemeColors;

import java.util.ArrayList;
import java.util.List;

/**
 * 歌词显示视图
 * 支持逐字颜色填充效果（毫秒级精度）
 * 支持自动折行（长歌词不再被截断）
 * 支持多种歌词显示模式
 */
public class LyricView extends View {
    
    private static final String TAG = "LyricView";
    
    // 歌词显示模式
    public enum DisplayMode {
        DOUBLE_LINE,    // 三行模式（上一行 + 当前行 + 下一行）
        MULTI_LINE,     // 五行模式（上二行 + 当前行 + 下二行）
        FULL,           // 多行模式（全部歌词滚动，隐藏封面）
        KARAOKE         // 卡拉OK模式（当前行 + 下一行，两行）
    }
    
    private DisplayMode currentMode = DisplayMode.DOUBLE_LINE;
    private boolean isWideScreen = false;

    // 模式切换回调
    public interface OnModeChangeListener {
        void onModeChanged(DisplayMode newMode);
    }
    private OnModeChangeListener modeChangeListener;
    
    // 主题模式
    public enum ThemeMode {
        NIGHT,  // 夜间模式（黑色背景）
        DAY     // 白天模式（白色背景）
    }
    
    private ThemeMode currentTheme = ThemeMode.NIGHT;
    
    // 夜间模式颜色配置（统一由 ThemeColors 管理）
    private static final int TEXT_COLOR_NORMAL_NIGHT = ThemeColors.NIGHT_LYRIC_NORMAL;
    private static final int TEXT_COLOR_PLAYED_NIGHT = ThemeColors.LYRIC_HIGHLIGHT;
    private static final int TEXT_COLOR_CURRENT_NIGHT = ThemeColors.LYRIC_HIGHLIGHT;

    // 白天模式颜色配置
    private static final int TEXT_COLOR_NORMAL_DAY = ThemeColors.DAY_LYRIC_NORMAL;
    private static final int TEXT_COLOR_PLAYED_DAY = ThemeColors.LYRIC_HIGHLIGHT;
    private static final int TEXT_COLOR_CURRENT_DAY = ThemeColors.LYRIC_HIGHLIGHT;
    // 当前行未播放字颜色：白天模式下用比灰色更深的颜色，避免在深色背景上看不见
    private static final int TEXT_COLOR_UNPLAYED_CURRENT_DAY = Color.parseColor("#555555");
    
    // 当前使用的颜色（根据主题切换）
    private int textColorNormal = TEXT_COLOR_NORMAL_NIGHT;
    private int textColorPlayed = TEXT_COLOR_PLAYED_NIGHT;
    private int textColorCurrent = TEXT_COLOR_CURRENT_NIGHT;
    
    // 字体大小配置（动态计算）
    private float textSizeNormal = 36f;
    private float textSizeCurrent = 48f;
    private float textSizeKaraoke = 60f;

    /** 获取当前行歌词字体大小（像素），供外部动态调整歌名字号 */
    public float getTextSizeCurrent() { return textSizeCurrent; }
    private float lineSpacing = 60f;     // 歌词行之间的间距（不同时间戳的行之间）
    
    // 字体大小计算比例
    private static final float TEXT_SIZE_NORMAL_RATIO = 0.036f;
    private static final float TEXT_SIZE_CURRENT_RATIO = 0.048f;
    private static final float TEXT_SIZE_KARAOKE_RATIO = 0.06f;
    
    // 字体大小限制
    private static final float TEXT_SIZE_MIN = 24f;
    private static final float TEXT_SIZE_MAX = 80f;
    
    // 文本折行相关
    private int textAreaWidth;               // 文本可用宽度
    private static final int TEXT_PADDING_DP = 4; // 文本两侧内边距(dp)
    private int textPadding;                 // 像素内边距
    private static final float WRAP_SPACING_ADD_RATIO = 0.3f; // 折行时行间距 = textSize * 此比例
    
    // 歌词数据
    private KrcParser.LyricData lyricData;
    private List<KrcParser.LyricLine> lines = new ArrayList<>();
    private int currentLineIndex = -1;
    private long currentPosition = 0;
    
    // 画笔（仅用于"暂无歌词"等提示文字）
    private Paint paintHint;
    
    private int viewWidth;
    private int viewHeight;
    
    // ===== 构造函数 =====
    
    public LyricView(Context context) {
        super(context);
        init();
    }
    
    public LyricView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    public LyricView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    
    private void init() {
        paintHint = new Paint();
        paintHint.setColor(textColorNormal);
        paintHint.setTextSize(textSizeCurrent);
        paintHint.setAntiAlias(true);
        paintHint.setTextAlign(Paint.Align.CENTER);
    }
    
    // ===== 公共方法 =====
    
    public void setLyricData(KrcParser.LyricData data) {
        this.lyricData = data;
        if (data != null && data.lines != null) {
            this.lines = data.lines;
        } else {
            this.lines.clear();
        }
        currentLineIndex = -1;
        invalidate();
    }
    
    public void clearLyric() {
        lyricData = null;
        lines.clear();
        currentLineIndex = -1;
        currentPosition = 0;
        invalidate();
    }
    
    public void setOnModeChangeListener(OnModeChangeListener listener) {
        this.modeChangeListener = listener;
    }

    public void setWideScreen(boolean wideScreen) {
        this.isWideScreen = wideScreen;
        Log.d(TAG, "设置宽屏模式: " + wideScreen);
    }
    
    /**
     * 获取当前歌词显示模式
     */
    public DisplayMode getDisplayMode() {
        return currentMode;
    }

    /**
     * 直接设置歌词显示模式（用于沉浸模式下跳过全屏歌词）
     */
    public void setDisplayMode(DisplayMode mode) {
        this.currentMode = mode;
        invalidate();
        if (modeChangeListener != null) {
            modeChangeListener.onModeChanged(mode);
        }
        Log.d(TAG, "设置歌词模式: " + mode);
    }

    /**
     * 切换歌词显示模式
     * 三行 -> 五行 -> 多行 -> 三行
     */
    public DisplayMode toggleMode() {
        switch (currentMode) {
            case DOUBLE_LINE: currentMode = DisplayMode.MULTI_LINE; break;
            case MULTI_LINE: currentMode = DisplayMode.FULL; break;
            case FULL: currentMode = DisplayMode.DOUBLE_LINE; break;
            case KARAOKE: currentMode = DisplayMode.DOUBLE_LINE; break;
            default: currentMode = DisplayMode.DOUBLE_LINE;
        }
        invalidate();
        if (modeChangeListener != null) {
            modeChangeListener.onModeChanged(currentMode);
        }
        Log.d(TAG, "切换歌词模式: " + currentMode);
        return currentMode;
    }
    
    public void setThemeMode(ThemeMode theme) {
        this.currentTheme = theme;
        Log.d(TAG, "切换主题模式: " + theme);
        
        if (theme == ThemeMode.NIGHT) {
            textColorNormal = TEXT_COLOR_NORMAL_NIGHT;
            textColorPlayed = TEXT_COLOR_PLAYED_NIGHT;
            textColorCurrent = TEXT_COLOR_CURRENT_NIGHT;
        } else {
            textColorNormal = TEXT_COLOR_NORMAL_DAY;
            textColorPlayed = TEXT_COLOR_PLAYED_DAY;
            textColorCurrent = TEXT_COLOR_CURRENT_DAY;
        }
        
        paintHint.setColor(textColorNormal);
        invalidate();
    }
    
    public void updatePosition(long positionMs) {
        this.currentPosition = positionMs;
        findCurrentLine();
        invalidate();
    }
    
    // ===== 颜色工具方法 =====
    
    private int getFadedTextColor() {
        if (currentTheme == ThemeMode.NIGHT) {
            return ThemeColors.NIGHT_LYRIC_FADED;
        } else {
            return ThemeColors.DAY_LYRIC_FADED;
        }
    }
    
    private int applyAlpha(int color, float alpha) {
        int a = Math.round(Color.alpha(color) * alpha);
        if (a < 0) a = 0;
        if (a > 255) a = 255;
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }
    
    private int blendColor(int c1, int c2, float progress) {
        int r = (int) (Color.red(c1) * progress + Color.red(c2) * (1 - progress));
        int g = (int) (Color.green(c1) * progress + Color.green(c2) * (1 - progress));
        int b = (int) (Color.blue(c1) * progress + Color.blue(c2) * (1 - progress));
        return Color.rgb(r, g, b);
    }
    
    // ===== 查找当前行 =====
    
    private void findCurrentLine() {
        if (lines.isEmpty()) return;
        
        for (int i = 0; i < lines.size(); i++) {
            KrcParser.LyricLine line = lines.get(i);
            KrcParser.LyricLine nextLine = (i + 1 < lines.size()) ? lines.get(i + 1) : null;
            
            if (currentPosition >= line.startTime && (nextLine == null || currentPosition < nextLine.startTime)) {
                if (currentLineIndex != i) {
                    currentLineIndex = i;
                }
                break;
            }
        }
    }
    
    // ===== 尺寸变化 =====
    
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        viewWidth = w;
        viewHeight = h;
        textPadding = (int) (TEXT_PADDING_DP * getResources().getDisplayMetrics().density);
        textAreaWidth = viewWidth - 2 * textPadding;
        if (textAreaWidth < 100) textAreaWidth = viewWidth; // 安全下限
        updateTextSize();
    }
    
    private void updateTextSize() {
        if (viewWidth <= 0) return;
        
        textSizeNormal = viewWidth * TEXT_SIZE_NORMAL_RATIO;
        textSizeCurrent = viewWidth * TEXT_SIZE_CURRENT_RATIO;
        textSizeKaraoke = viewWidth * TEXT_SIZE_KARAOKE_RATIO;
        
        textSizeNormal = Math.max(TEXT_SIZE_MIN, Math.min(TEXT_SIZE_MAX, textSizeNormal));
        textSizeCurrent = Math.max(TEXT_SIZE_MIN * 1.33f, Math.min(60f, textSizeCurrent));
        textSizeKaraoke = Math.max(TEXT_SIZE_MIN * 1.5f, Math.min(TEXT_SIZE_MAX * 1.5f, textSizeKaraoke));
        
        // 歌词行间距：不同时间戳行之间的间隔
        lineSpacing = textSizeNormal * 1.0f;
        
        paintHint.setTextSize(textSizeCurrent);
    }
    
    // ===== StaticLayout 构建 =====
    
    /**
     * 获取歌词文本折行后的实际高度
     */
    private float getWrappedHeight(String text, float textSize) {
        if (text == null || text.isEmpty() || textAreaWidth <= 0) {
            return textSize * 1.3f;
        }
        TextPaint p = new TextPaint();
        p.setTextSize(textSize);
        p.setAntiAlias(true);
        float spacingAdd = textSize * WRAP_SPACING_ADD_RATIO;
        StaticLayout layout = new StaticLayout(text, p, textAreaWidth,
                Layout.Alignment.ALIGN_CENTER, 1.0f, spacingAdd, false);
        return layout.getHeight();
    }
    
    /**
     * 构建当前行的 StaticLayout（带逐字颜色渐变）
     * @param unplayedColor 未播放文字的颜色
     * 注意：白天模式下当前行未播放字应该比普通行更深，避免在深色背景上不可见
     */
    private StaticLayout buildCurrentLineLayout(KrcParser.LyricLine line, float textSize, int unplayedColor) {
        TextPaint paint = new TextPaint();
        paint.setTextSize(textSize);
        paint.setAntiAlias(true);
        paint.setFakeBoldText(true);
        
        String text = (line.text != null) ? line.text : "";
        if (text.isEmpty()) text = " ";
        
        float spacingAdd = textSize * WRAP_SPACING_ADD_RATIO;
        
        // 白天模式下，当前行未播放字用更深的颜色，确保在任何背景上可见
        int effectiveUnplayedColor = unplayedColor;
        if (currentTheme == ThemeMode.DAY && unplayedColor == TEXT_COLOR_NORMAL_DAY) {
            effectiveUnplayedColor = TEXT_COLOR_UNPLAYED_CURRENT_DAY;
        }
        
        if (line.words != null && !line.words.isEmpty()) {
            SpannableStringBuilder ssb = new SpannableStringBuilder(text);
            int start = 0;
            for (KrcParser.LyricWord word : line.words) {
                int end = start + word.text.length();
                if (end > text.length()) end = text.length();
                if (start >= text.length()) break;
                
                int color;
                boolean wordPlayed = (currentPosition >= word.startTime + word.duration);
                boolean wordPlaying = (currentPosition >= word.startTime && currentPosition < word.startTime + word.duration);
                if (wordPlayed) {
                    color = textColorPlayed;
                } else if (wordPlaying) {
                    float progress = (currentPosition - word.startTime) / (float) word.duration;
                    color = blendColor(textColorPlayed, effectiveUnplayedColor, progress);
                } else {
                    color = effectiveUnplayedColor;
                }
                ssb.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                start = end;
            }
            return new StaticLayout(ssb, paint, textAreaWidth,
                    Layout.Alignment.ALIGN_CENTER, 1.0f, spacingAdd, false);
        }
        
        // 无逐字数据，整行使用当前行颜色
        paint.setColor(textColorCurrent);
        return new StaticLayout(text, paint, textAreaWidth,
                Layout.Alignment.ALIGN_CENTER, 1.0f, spacingAdd, false);
    }
    
    /**
     * 构建非当前行的 StaticLayout（单色）
     */
    private StaticLayout buildSimpleLayout(KrcParser.LyricLine line, float textSize, int color) {
        TextPaint paint = new TextPaint();
        paint.setTextSize(textSize);
        paint.setColor(color);
        paint.setAntiAlias(true);
        paint.setFakeBoldText(true);
        
        String text = (line.text != null) ? line.text : "";
        if (text.isEmpty()) text = " ";
        
        float spacingAdd = textSize * WRAP_SPACING_ADD_RATIO;
        return new StaticLayout(text, paint, textAreaWidth,
                Layout.Alignment.ALIGN_CENTER, 1.0f, spacingAdd, false);
    }
    
    /**
     * 在指定 Y 位置绘制 StaticLayout
     */
    private void drawLayoutAt(Canvas canvas, StaticLayout layout, float y) {
        canvas.save();
        canvas.translate(textPadding, y);
        layout.draw(canvas);
        canvas.restore();
    }
    
    // ===== 绘制入口 =====
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        if (lines.isEmpty()) {
            paintHint.setTextSize(textSizeCurrent);
            canvas.drawText("暂无歌词", viewWidth / 2f, viewHeight / 2f, paintHint);
            return;
        }
        
        switch (currentMode) {
            case KARAOKE:
                drawKaraokeMode(canvas);
                break;
            case MULTI_LINE:
                drawMultiLineMode(canvas);
                break;
            case FULL:
                drawFullMode(canvas);
                break;
            case DOUBLE_LINE:
                drawDoubleLineMode(canvas);
                break;
        }
    }
    
    // ===== 三行模式（上一行 + 当前行 + 下一行）=====
    
    private void drawDoubleLineMode(Canvas canvas) {
        float centerY = viewHeight * 0.45f;
        
        if (currentLineIndex < 0 || currentLineIndex >= lines.size()) {
            // 还没有当前行，显示前两行
            if (!lines.isEmpty()) {
                float firstH = getWrappedHeight(lines.get(0).text, textSizeCurrent);
                StaticLayout firstLayout = buildCurrentLineLayout(lines.get(0), textSizeCurrent, textColorNormal);
                drawLayoutAt(canvas, firstLayout, centerY - firstH / 2);
                
                if (lines.size() > 1) {
                    float nextY = centerY + firstH / 2 + lineSpacing;
                    StaticLayout secondLayout = buildSimpleLayout(
                            lines.get(1), textSizeNormal, applyAlpha(getFadedTextColor(), 0.6f));
                    drawLayoutAt(canvas, secondLayout, nextY);
                }
            }
            return;
        }
        
        // 计算当前行高度
        float currentH = getWrappedHeight(lines.get(currentLineIndex).text, textSizeCurrent);
        float currentTop = centerY - currentH / 2;
        float currentBottom = centerY + currentH / 2;
        
        // 上一行
        if (currentLineIndex > 0) {
            KrcParser.LyricLine prevLine = lines.get(currentLineIndex - 1);
            float prevH = getWrappedHeight(prevLine.text, textSizeNormal);
            float prevTop = currentTop - lineSpacing - prevH;
            StaticLayout prevLayout = buildSimpleLayout(prevLine, textSizeNormal, applyAlpha(getFadedTextColor(), 0.6f));
            drawLayoutAt(canvas, prevLayout, prevTop);
        }
        
        // 当前行（逐字颜色）
        StaticLayout currentLayout = buildCurrentLineLayout(lines.get(currentLineIndex), textSizeCurrent, textColorNormal);
        drawLayoutAt(canvas, currentLayout, currentTop);
        
        // 下一行
        if (currentLineIndex + 1 < lines.size()) {
            KrcParser.LyricLine nextLine = lines.get(currentLineIndex + 1);
            float nextTop = currentBottom + lineSpacing;
            StaticLayout nextLayout = buildSimpleLayout(nextLine, textSizeNormal, applyAlpha(getFadedTextColor(), 0.6f));
            drawLayoutAt(canvas, nextLayout, nextTop);
        }
    }
    
    // ===== 五行模式（上2行 + 当前行 + 下2行）=====
    
    private void drawMultiLineMode(Canvas canvas) {
        if (lines.isEmpty()) return;
        
        // 当前行未确定时，降级为显示前几行歌词，第一行作为当前行高亮
        if (currentLineIndex < 0 || currentLineIndex >= lines.size()) {
            float centerY = viewHeight / 2f;
            // 第一行用当前行样式（黄色高亮）
            float firstH = getWrappedHeight(lines.get(0).text, textSizeCurrent);
            StaticLayout firstLayout = buildCurrentLineLayout(lines.get(0), textSizeCurrent, textColorNormal);
            float y = centerY - firstH / 2;
            drawLayoutAt(canvas, firstLayout, y);
            y += firstH + lineSpacing;
            // 后续行用普通样式
            for (int i = 1; i < Math.min(5, lines.size()); i++) {
                float h = getWrappedHeight(lines.get(i).text, textSizeNormal);
                float alpha = 1.0f - i * 0.2f;
                StaticLayout layout = buildSimpleLayout(lines.get(i), textSizeNormal,
                        applyAlpha(getFadedTextColor(), alpha));
                drawLayoutAt(canvas, layout, y);
                y += h + lineSpacing;
            }
            return;
        }
        
        float currentH = getWrappedHeight(lines.get(currentLineIndex).text, textSizeCurrent);
        float centerY = viewHeight / 2f;
        float currentTop = centerY - currentH / 2;
        float currentBottom = centerY + currentH / 2;
        
        // 上2行（从紧贴当前行上方往上排列）
        float y = currentTop - lineSpacing;
        for (int offset = -1; offset >= -2; offset--) {
            int idx = currentLineIndex + offset;
            if (idx < 0) break;
            KrcParser.LyricLine line = lines.get(idx);
            float h = getWrappedHeight(line.text, textSizeNormal);
            y -= h; // y 指向该行顶部
            float alpha = 1.0f - Math.abs(offset) * 0.2f; // 0.8, 0.6
            StaticLayout layout = buildSimpleLayout(line, textSizeNormal, applyAlpha(getFadedTextColor(), alpha));
            drawLayoutAt(canvas, layout, y);
            y -= lineSpacing;
        }
        
        // 当前行（逐字颜色）
        StaticLayout currentLayout = buildCurrentLineLayout(lines.get(currentLineIndex), textSizeCurrent, textColorNormal);
        drawLayoutAt(canvas, currentLayout, currentTop);
        
        // 下2行
        y = currentBottom + lineSpacing;
        for (int offset = 1; offset <= 2; offset++) {
            int idx = currentLineIndex + offset;
            if (idx >= lines.size()) break;
            KrcParser.LyricLine line = lines.get(idx);
            float alpha = 1.0f - Math.abs(offset) * 0.2f; // 0.8, 0.6
            StaticLayout layout = buildSimpleLayout(line, textSizeNormal, applyAlpha(getFadedTextColor(), alpha));
            drawLayoutAt(canvas, layout, y);
            y += getWrappedHeight(line.text, textSizeNormal) + lineSpacing;
        }
    }
    
    // ===== 多行全屏模式（全部歌词滚动）=====
    
    private void drawFullMode(Canvas canvas) {
        if (lines.isEmpty()) return;
        
        // 当前行未确定时，降级为从顶部开始显示歌词，第一行作为当前行高亮
        if (currentLineIndex < 0) {
            float y = viewHeight * 0.3f;
            for (int i = 0; i < lines.size(); i++) {
                float h = getWrappedHeight(lines.get(i).text, i == 0 ? textSizeCurrent : textSizeCurrent);
                if (y + h > -50 && y < viewHeight + 50) {
                    if (i == 0) {
                        // 第一行用当前行样式（黄色高亮）
                        StaticLayout layout = buildCurrentLineLayout(lines.get(i), textSizeCurrent, textColorNormal);
                        drawLayoutAt(canvas, layout, y);
                    } else {
                        float alpha = Math.max(0.15f, 1.0f - i * 0.1f);
                        StaticLayout layout = buildSimpleLayout(lines.get(i), textSizeCurrent,
                                applyAlpha(getFadedTextColor(), alpha));
                        drawLayoutAt(canvas, layout, y);
                    }
                }
                y += h + lineSpacing;
                if (y > viewHeight + 200) break;
            }
            return;
        }
        
        float textSize = textSizeCurrent;
        
        // 计算当前行之前所有行的累积高度
        float contentBeforeCurrent = 0;
        for (int i = 0; i < currentLineIndex; i++) {
            contentBeforeCurrent += getWrappedHeight(lines.get(i).text, textSize) + lineSpacing;
        }
        
        float currentH = getWrappedHeight(lines.get(currentLineIndex).text, textSize);
        float currentCenter = contentBeforeCurrent + currentH / 2;
        
        // 偏移量：让当前行居中于视图
        float offsetY = viewHeight / 2f - currentCenter;
        
        // 绘制可见行
        float y = offsetY;
        for (int i = 0; i < lines.size(); i++) {
            float h = getWrappedHeight(lines.get(i).text, textSize);
            
            // 只绘制在视图范围内（含少量余量）的行
            if (y + h > -50 && y < viewHeight + 50) {
                boolean isCurrent = (i == currentLineIndex);
                if (isCurrent) {
                    StaticLayout layout = buildCurrentLineLayout(lines.get(i), textSize, textColorNormal);
                    drawLayoutAt(canvas, layout, y);
                } else {
                    int dist = Math.abs(i - currentLineIndex);
                    float alpha = Math.max(0.15f, 1.0f - dist * 0.18f);
                    int color = applyAlpha(getFadedTextColor(), alpha);
                    StaticLayout layout = buildSimpleLayout(lines.get(i), textSize, color);
                    drawLayoutAt(canvas, layout, y);
                }
            }
            
            y += h + lineSpacing;
            
            // 已经完全超出视图底部，提前退出
            if (y > viewHeight + 200) break;
        }
    }
    
    // ===== 卡拉OK模式（当前行 + 下一行）=====
    
    private void drawKaraokeMode(Canvas canvas) {
        float centerY = viewHeight * 0.45f;
        
        if (currentLineIndex < 0 || currentLineIndex >= lines.size()) {
            if (!lines.isEmpty()) {
                float firstH = getWrappedHeight(lines.get(0).text, textSizeKaraoke);
                StaticLayout firstLayout = buildCurrentLineLayout(lines.get(0), textSizeKaraoke, applyAlpha(getFadedTextColor(), 0.6f));
                drawLayoutAt(canvas, firstLayout, centerY - firstH / 2);
            }
            return;
        }
        
        // 当前行（大字，逐字颜色，未播放字为淡色）
        KrcParser.LyricLine currentLine = lines.get(currentLineIndex);
        float currentH = getWrappedHeight(currentLine.text, textSizeKaraoke);
        float currentTop = centerY - currentH / 2;
        float currentBottom = centerY + currentH / 2;
        
        StaticLayout currentLayout = buildCurrentLineLayout(currentLine, textSizeKaraoke, applyAlpha(getFadedTextColor(), 0.6f));
        drawLayoutAt(canvas, currentLayout, currentTop);
        
        // 下一行（半透明预览）
        if (currentLineIndex + 1 < lines.size()) {
            KrcParser.LyricLine nextLine = lines.get(currentLineIndex + 1);
            float nextTop = currentBottom + lineSpacing;
            StaticLayout nextLayout = buildSimpleLayout(nextLine, textSizeKaraoke, applyAlpha(getFadedTextColor(), 0.6f));
            drawLayoutAt(canvas, nextLayout, nextTop);
        }
    }
}
