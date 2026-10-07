package com.jingxin.jingxinmusic.util;

import android.content.Context;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.media.audiofx.Virtualizer;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * 音效管理器：负责音量均衡增益（LoudnessEnhancer）与兜底 MediaPlayer 的预设处理。
 * EQ 预设/低音增强/全景环绕已改由 SoftwareEqProcessor（ExoPlayer 链内软件 DSP）实现，
 * 绕开车机厂商 EQ 效果器（NXP）在多声道输出上的静音缺陷。
 *
 * 线程模型：效果器构造/设置/release 内部会 Binder 到 AudioFlinger，
 * 音频服务异常设备（如 5.1 模拟器 AudioFlinger 卡死）上会阻塞调用线程，
 * 因此全部操作走内部单线程串行执行，主线程只做内存赋值，绝不 ANR；
 * 任何异常静默禁用（effectFailed），绝不影响播放。
 */
public class AudioEffectManager {

    private static final String TAG = "AudioEffectManager";

    /** 效果预设的 SP 存储（与 SettingsFragment.PREFS_NAME 同文件） */
    public static final String EFFECT_PREFS_NAME = "app_settings";
    public static final String KEY_EFFECT_PRESET = "audio_effect_preset";

    /** 预设索引（顺序固定，与 PRESET_NAMES 对应） */
    public static final int PRESET_OFF = 0;
    public static final int PRESET_VOCAL = 1;
    public static final int PRESET_POP = 2;
    public static final int PRESET_ROCK = 3;
    public static final int PRESET_CLASSIC = 4;
    public static final int PRESET_BASS = 5;
    public static final int PRESET_SURROUND = 6;

    public static final String[] PRESET_NAMES =
            {"关闭", "人声", "流行", "摇滚", "古典", "低音增强", "全景环绕"};

    /**
     * EQ 预设曲线（毫贝，1dB=100mb），5 个锚点对应 EQ_ANCHOR_FREQS 频率。
     * 强度按业界经典预设 1.5 倍档（峰值 ±9dB，车机/手机喇叭安全上限内）：
     * 人声：中频抬升突出人声；流行：两端轻抬中频微降；摇滚：V 型力度；古典：两端微暖保持原味；
     * 低音增强/全景环绕：EQ 平直（由 BassBoost/Virtualizer 单独发力）。
     */
    private static final short[][] EQ_CURVES = {
            {0, 0, 0, 0, 0},                // 关闭（EQ 不启用）
            {0, 300, 750, 600, 0},          // 人声（中频 +7.5dB）
            {600, 300, 0, 300, 450},        // 流行（两端抬升）
            {900, 600, -300, 450, 900},     // 摇滚（V 型 ±9dB）
            {300, 0, 0, 0, 300},            // 古典（平直保真）
            {0, 0, 0, 0, 0},                // 低音增强（EQ 平直）
            {0, 0, 0, 0, 0},                // 全景环绕（EQ 平直）
    };
    /** EQ 锚点中心频率（Hz），与 EQ_CURVES 列对应 */
    private static final float[] EQ_ANCHOR_FREQS = {60f, 230f, 1000f, 3500f, 14000f};

    /** 效果器串行操作线程（Binder 调用全部隔离在此线程） */
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private volatile int preset = PRESET_OFF;    // 当前预设（Service onCreate 从 SP 恢复）
    private volatile int sessionId = 0;         // 当前挂载的音频会话（0=未挂载）
    private volatile float loudnessGainDb = 0f; // 音量均衡正增益（dB），<=0 不启用
    private volatile boolean released = false;  // Service 销毁后拒绝新任务
    // 兜底 MediaPlayer 场景才走系统效果器（车机厂商 EQ 多声道静音，ExoPlayer 已改软件 DSP）
    private volatile boolean fallbackMode = false;

    private Equalizer equalizer;
    private BassBoost bassBoost;
    private Virtualizer virtualizer;
    private LoudnessEnhancer loudness;
    private boolean effectFailed = false;       // 构造失败标志（本 session 不再重试）

    /** 从 SP 恢复上次选择的预设（Service onCreate 调用一次） */
    public void initFromPrefs(Context context) {
        preset = getSavedPreset(context);
    }

    /** 读取 SP 中保存的预设索引（默认关闭） */
    public static int getSavedPreset(Context context) {
        return context.getSharedPreferences(EFFECT_PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_EFFECT_PRESET, PRESET_OFF);
    }

    /**
     * 异步挂载到音频会话（幂等：同 session 且效果器存活时只重设参数；
     * session 变化时释放旧效果器重建）。ExoPlayer onAudioSessionIdChanged 回调、
     * startPlayback 双保险、FallbackInit prepare 完成后三个入口都会调用。
     */
    public void attachAsync(int newSessionId) {
        if (newSessionId <= 0 || released) return;
        final int sid = newSessionId;
        submit(() -> {
            if (sessionId == sid && !effectFailed && (equalizer != null || bassBoost != null
                    || virtualizer != null || loudness != null || preset == PRESET_OFF)) {
                // 同 session：重设参数即可（PRESET_OFF 时本就直通，也无需重建）
                applyLoudnessLocked();
                return;
            }
            releaseAllLocked();
            sessionId = sid;
            effectFailed = false;
            applyPresetLocked();
            applyLoudnessLocked();
        });
    }

    /** 仅音量均衡挂载（ExoPlayer 模式专用：EQ/BB/VT 走软件 DSP，不再碰系统 EQ 效果器） */
    public void attachLoudnessOnlyAsync(int newSessionId) {
        if (newSessionId <= 0 || released) return;
        final int sid = newSessionId;
        submit(() -> {
            releasePresetEffectsLocked(); // 释放可能残留的 EQ/BB/VT（防车机静音）
            sessionId = sid;
            effectFailed = false;
            applyLoudnessLocked();
        });
    }

    /**
     * 切换预设（SP 持久化由设置页完成，此处只更新内存与效果器）。
     * ExoPlayer 模式：由 SoftwareEqProcessor 处理，此处若非兜底模式则不碰系统效果器。
     */
    public void setPresetAsync(int newPreset) {
        preset = newPreset;
        if (!fallbackMode) {
            // ExoPlayer 模式：EQ/BB/VT 由软件 DSP 处理，确保系统 EQ 类效果器全部释放（防残留静音）
            submit(this::releasePresetEffectsLocked);
            return;
        }
        submit(this::applyPresetLocked);
    }

    /** 标记当前是否兜底 MediaPlayer 播放（决定预设走系统效果器还是软件 EQ） */
    public void setFallbackMode(boolean fallback) {
        this.fallbackMode = fallback;
    }

    /**
     * 设置音量均衡正增益（dB）。正增益走 LoudnessEnhancer（ExoPlayer setVolume 上限 1.0 升不了音量），
     * 负增益由 Service 走 player.setVolume 缩小，此处只管 >0 的部分。
     */
    public void setLoudnessGainAsync(float gainDb) {
        loudnessGainDb = gainDb;
        submit(this::applyLoudnessLocked);
    }

    /** Service onDestroy 释放全部效果器 */
    public void releaseAsync() {
        released = true;
        submit(this::releaseAllLocked);
        executor.shutdown();
    }

    // ==================== 内部方法（均在 executor 线程执行） ====================

    private void submit(Runnable task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException ignored) {
        }
    }

    /** 按当前预设应用效果器（仅兜底 MediaPlayer 场景调用；ExoPlayer 走软件 EQ）。 */
    private void applyPresetLocked() {
        if (sessionId <= 0 || effectFailed || released) return;
        if (preset == PRESET_OFF) {
            releasePresetEffectsLocked();
            return;
        }
        try {
            // EQ 类预设：按曲线设置各频段增益
            if (preset >= PRESET_VOCAL && preset <= PRESET_CLASSIC) {
                if (equalizer == null) {
                    equalizer = new Equalizer(0, sessionId);
                }
                equalizer.setEnabled(true);
                applyEqualizerCurve(equalizer, EQ_CURVES[preset]);
            } else if (equalizer != null) {
                equalizer.setEnabled(false);
            }
            // 低音增强：BassBoost 80% 强度
            if (preset == PRESET_BASS) {
                if (bassBoost == null) {
                    bassBoost = new BassBoost(0, sessionId);
                }
                bassBoost.setStrength((short) 800);
                bassBoost.setEnabled(true);
            } else if (bassBoost != null) {
                bassBoost.setEnabled(false);
            }
            // 全景环绕：Virtualizer 80% 强度
            if (preset == PRESET_SURROUND) {
                if (virtualizer == null) {
                    virtualizer = new Virtualizer(0, sessionId);
                }
                virtualizer.setStrength((short) 800);
                virtualizer.setEnabled(true);
            } else if (virtualizer != null) {
                virtualizer.setEnabled(false);
            }
        } catch (Throwable t) {
            // 设备不支持效果器或 session 已失效：静默禁用，不影响播放
            effectFailed = true;
            releaseAllLocked();
        }
    }

    /** 应用音量均衡正增益（LoudnessEnhancer 与 EQ 预设独立共存） */
    private void applyLoudnessLocked() {
        if (sessionId <= 0 || effectFailed || released) return;
        try {
            if (loudnessGainDb > 0f) {
                if (loudness == null) {
                    loudness = new LoudnessEnhancer(sessionId);
                }
                loudness.setTargetGain(Math.round(loudnessGainDb * 100f)); // dB → 毫贝
                loudness.setEnabled(true);
            } else if (loudness != null) {
                loudness.setEnabled(false);
            }
        } catch (Throwable t) {
            if (loudness != null) {
                try { loudness.release(); } catch (Throwable ignored) {}
                loudness = null;
            }
        }
    }

    /**
     * 按锚点曲线设置 EQ 各频段增益。
     * 设备 band 数不定（常见 5），用对数频率轴插值保证任意 band 数都得到平滑曲线，
     * 并 clamp 到设备支持的 band level 范围。
     */
    private void applyEqualizerCurve(Equalizer eq, short[] curve) {
        int bands = eq.getNumberOfBands();
        short[] range = eq.getBandLevelRange();
        for (short i = 0; i < bands; i++) {
            float freq = eq.getCenterFreq(i) / 1000f; // milliHz → Hz
            float gain = interpolateCurve(curve, freq);
            short mb = (short) Math.max(range[0], Math.min(range[1], Math.round(gain)));
            eq.setBandLevel(i, mb);
        }
    }

    /** 对数频率轴上 5 锚点线性插值求目标增益（毫贝） */
    private static float interpolateCurve(short[] curve, float freq) {
        if (freq <= EQ_ANCHOR_FREQS[0]) return curve[0];
        if (freq >= EQ_ANCHOR_FREQS[4]) return curve[4];
        for (int i = 0; i < 4; i++) {
            if (freq >= EQ_ANCHOR_FREQS[i] && freq <= EQ_ANCHOR_FREQS[i + 1]) {
                float la = (float) Math.log10(EQ_ANCHOR_FREQS[i]);
                float lb = (float) Math.log10(EQ_ANCHOR_FREQS[i + 1]);
                float lf = (float) Math.log10(freq);
                float t = (lf - la) / (lb - la);
                return curve[i] + t * (curve[i + 1] - curve[i]);
            }
        }
        return 0f;
    }

    /** 释放 EQ 预设三类效果器（保留 LoudnessEnhancer，音量均衡独立于预设） */
    private void releasePresetEffectsLocked() {
        if (equalizer != null) {
            try { equalizer.release(); } catch (Throwable ignored) {}
            equalizer = null;
        }
        if (bassBoost != null) {
            try { bassBoost.release(); } catch (Throwable ignored) {}
            bassBoost = null;
        }
        if (virtualizer != null) {
            try { virtualizer.release(); } catch (Throwable ignored) {}
            virtualizer = null;
        }
    }

    /** 释放全部效果器（session 切换/Service 销毁） */
    private void releaseAllLocked() {
        releasePresetEffectsLocked();
        if (loudness != null) {
            try { loudness.release(); } catch (Throwable ignored) {}
            loudness = null;
        }
        effectFailed = false;
    }

}
