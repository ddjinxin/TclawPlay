package com.jingxin.jingxinmusic.player;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.Visualizer;
import android.os.Handler;
import android.os.Looper;
import androidx.core.content.ContextCompat;

/**
 * 频谱采集引擎（单一事实源）
 *
 * 收编原 PlayerFragment.tryStartVisualizer 与 MiniFloatService.initVisualizer 的双头镜像实现：
 * - 唯一持有 Visualizer 生命周期（构造序列/禁用防御/延迟 release 照抄原实现）
 * - Visualizer 构造失败时可选降级 AudioRecord 麦克风 + 自写 Cooley-Tukey FFT（原播放页专属能力）
 * - sessionId 未就绪时 300ms×10 内部重试；跨端抢占由 synchronized 方法序列化，
 *   原双端 PLAYER_CLAIM/SERVICE_CLAIM 广播对偶仲裁整个消失
 * - 每消费者独立乒乓双缓冲（采集线程写一槽、主线程读另一槽，防撕裂且避免每帧 new）
 */
public final class SpectrumEngine {

    public static final String OWNER_PLAYER = "player";
    public static final String OWNER_MINI_FLOAT = "mini_float";

    /** 帧来源：Visualizer 播放流 / AudioRecord 麦克风降级 */
    public static final int SOURCE_PLAYBACK = 0;
    public static final int SOURCE_MIC = 1;

    /** sessionId 提供方（播放页/悬浮窗各自经 Binder 取） */
    public interface SessionIdProvider {
        int getAudioSessionId();
    }

    /** 帧消费方：count=本帧频段需求（0=跳过分发），回调在采集线程 */
    public interface FrameSink {
        int getBarCount();
        void onSpectrumFrame(float[] magnitudes, float maxMag, int source);
    }

    public enum Result { RUNNING, RETRYING, NEED_MIC_PERMISSION, FAILED }

    private static final int SPECTRUM_RETRY_INTERVAL = 300; // ms
    private static final int SPECTRUM_MAX_RETRIES = 10;     // 共 3 秒
    private static final long DELAYED_RELEASE_MS = 300;      // native CaptureThread 退出等待
    private static final int SAMPLE_RATE = 8000;

    private static volatile SpectrumEngine sInstance;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ===== 采集器状态（引擎内唯一一份，所有方法 synchronized 序列化） =====
    private Visualizer visualizer;
    private AudioRecord audioRecord;
    private Thread micThread;
    private boolean usingMic = false;
    private boolean wantMicFallback = false;
    private Context appContext;
    private SessionIdProvider sessionIdProvider;
    private FrameSink sink;
    private String owner;
    private int generation = 0;      // 代际令牌：重新 request 后旧重试/旧线程自动失效
    private int retryCount = 0;
    private volatile boolean sinkPlaying = true; // 消费方暂停语义（悬浮窗暂停即停采集）

    private SpectrumEngine() {}

    public static SpectrumEngine getInstance() {
        if (sInstance == null) {
            synchronized (SpectrumEngine.class) {
                if (sInstance == null) sInstance = new SpectrumEngine();
            }
        }
        return sInstance;
    }

    /**
     * 请求频谱采集（幂等）：
     * - 同 owner 且 sessionId 未变 → 仅更新 sink，不重建
     * - owner 变化 → 同步接管（旧实例先断回调禁用，native release 延迟 300ms 异步）
     * - sessionId 未就绪 → RETRYING（内部 300ms×10 重试，超限按需降级麦克风）
     */
    public synchronized Result request(Context context, String newOwner,
                                       SessionIdProvider provider, FrameSink frameSink,
                                       boolean allowMicFallback) {
        if (context != null) appContext = context.getApplicationContext();
        sink = frameSink;
        wantMicFallback = allowMicFallback;
        boolean sameOwner = newOwner != null && newOwner.equals(owner);
        boolean restarted = false;
        if (!sameOwner) {
            // 跨端抢占：同步断回调禁用旧实例，让位在锁内即刻生效（原广播仲裁的时序空隙闭合）
            teardownVisualizerLocked();
            stopMicThreadLocked();
            owner = newOwner;
            sessionIdProvider = provider;
            // 换主重置暂停态：原播放页创建即 setEnabled(true)；悬浮窗随后自行 setPlaying 对齐播放态。
            // 若不重置，悬浮窗暂停期的 sinkPlaying=false 会残留给新主，播放页频谱建出即冻结
            sinkPlaying = true;
            restarted = true;
        } else if (provider != null) {
            // 同 owner：sessionId 可能已变（ExoPlayer 重建/兜底），变更则重建
            int sid = safeSessionId(provider);
            if (sid > 0 && visualizer != null && visualizerEnabledSessionId > 0 && sid != visualizerEnabledSessionId) {
                teardownVisualizerLocked();
                stopMicThreadLocked();
                sessionIdProvider = provider;
                restarted = true;
            } else {
                sessionIdProvider = provider;
            }
        }
        if (!restarted && (visualizer != null || usingMic)) return Result.RUNNING; // 已在跑，幂等
        generation++;
        final int myGen = generation;
        retryCount = 0;
        return startLocked(myGen);
    }

    /** 释放（幂等）：断回调 → 延迟 native release（Android 8.1 立即 release 会 UAF） */
    public synchronized void release(String who) {
        if (who == null || !who.equals(owner)) return;
        FrameSink oldSink = sink;
        teardownVisualizerLocked();
        stopMicThreadLocked();
        owner = null;
        sessionIdProvider = null;
        sink = null;
        generation++;
        // 顺带清掉双缓冲表里该 sink 的槽位（防多次重建 Fragment 后的微小累积）
        if (oldSink != null) {
            synchronized (sinkBufs) {
                sinkBufs.remove(oldSink);
                sinkBufState.remove(oldSink);
            }
        }
    }

    /** 该 owner 是否持有采集（含重试中） */
    public synchronized boolean isRunning(String who) {
        return who != null && who.equals(owner) && (visualizer != null || usingMic || retryInProgress);
    }

    /** 暂停即停采集（悬浮窗行为；播放页不调用即恒开） */
    public synchronized void setPlaying(String who, boolean playing) {
        if (who == null || !who.equals(owner)) return;
        sinkPlaying = playing;
        if (visualizer != null && !usingMic) {
            try { visualizer.setEnabled(playing); } catch (Exception ignored) {}
        }
    }

    // ==================== 内部：Visualizer 主链 ====================

    private int visualizerEnabledSessionId = 0; // 当前实例对应的 session
    private boolean retryInProgress = false;

    private Result startLocked(int myGen) {
        if (sessionIdProvider == null || sink == null) return Result.FAILED;
        int sessionId = safeSessionId(sessionIdProvider);
        if (sessionId == -1 || sessionId == 0) {
            // sessionId 尚未就绪（ExoPlayer 异步 prepare），延迟重试
            if (retryCount < SPECTRUM_MAX_RETRIES) {
                retryCount++;
                retryInProgress = true;
                final SessionIdProvider p = sessionIdProvider;
                mainHandler.postDelayed(() -> {
                    synchronized (SpectrumEngine.this) {
                        if (generation != myGen) return; // 已被新请求顶替
                        retryInProgress = false;
                        startLocked(myGen);
                    }
                }, SPECTRUM_RETRY_INTERVAL);
                return Result.RETRYING;
            }
            // 超过重试次数，按需降级麦克风
        } else {
            try {
                visualizer = new Visualizer(sessionId);
                visualizer.setEnabled(false);
                int[] range = visualizer.getCaptureSizeRange();
                if (range == null || range.length < 2) {
                    throw new RuntimeException("getCaptureSizeRange returned invalid range");
                }
                visualizer.setCaptureSize(range[1]);
                visualizer.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);
                visualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                    @Override
                    public void onWaveFormDataCapture(Visualizer v, byte[] waveform, int samplingRate) {}

                    @Override
                    public void onFftDataCapture(Visualizer v, byte[] fft, int samplingRate) {
                        dispatchFft(fft);
                    }
                }, Visualizer.getMaxCaptureRate(), false, true);
                visualizer.setEnabled(sinkPlaying);
                visualizerEnabledSessionId = sessionId;
                usingMic = false;
                return Result.RUNNING;
            } catch (Exception e) {
                if (visualizer != null) {
                    try { visualizer.release(); } catch (Exception ignored) {}
                    visualizer = null;
                }
                // 构造失败（常见：被其他应用占用）→ 按需降级麦克风
            }
        }
        // 降级：AudioRecord + DFT（和酷狗替身参数一致）
        if (!wantMicFallback || appContext == null) {
            retryCount = 0;
            return Result.FAILED;
        }
        if (ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            return Result.NEED_MIC_PERMISSION;
        }
        startMicThreadLocked(myGen);
        if (usingMic) return Result.RUNNING;
        return Result.FAILED;
    }

    /** FFT → 各消费者双缓冲提取 → 分发（Visualizer 捕捉线程调用） */
    private void dispatchFft(byte[] fft) {
        FrameSink s;
        synchronized (this) { s = sink; }
        if (s == null) return;
        int count = s.getBarCount();
        if (count <= 0) return;
        float[] magnitudes = acquireBuf(s, count);
        float maxMag = 0;
        for (int i = 0; i < count; i++) {
            int idx = (i + 1) * 2; // 跳过 DC 分量
            if (idx + 1 < fft.length) {
                byte real = fft[idx];
                byte imaginary = fft[idx + 1];
                float mag = (float) Math.sqrt(real * real + imaginary * imaginary);
                magnitudes[i] = mag;
                if (mag > maxMag) maxMag = mag;
            }
        }
        s.onSpectrumFrame(magnitudes, maxMag, SOURCE_PLAYBACK);
    }

    /**
     * 消费者级乒乓双缓冲：采集线程写一槽、消费线程读另一槽，
     * count 变化时扩容重建。与原双端 acquireSpectrumBuf 逻辑一致。
     */
    private final java.util.IdentityHashMap<FrameSink, float[][]> sinkBufs = new java.util.IdentityHashMap<>();
    private final java.util.IdentityHashMap<FrameSink, int[]> sinkBufState = new java.util.IdentityHashMap<>();

    private float[] acquireBuf(FrameSink s, int count) {
        synchronized (sinkBufs) {
            float[][] ab = sinkBufs.get(s);
            int[] st = sinkBufState.get(s);
            if (ab == null || (st != null && st[1] < count)) {
                sinkBufs.put(s, new float[][]{new float[count], new float[count]});
                sinkBufState.put(s, new int[]{0, count}); // [flip, len]
                ab = sinkBufs.get(s);
            }
            st = sinkBufState.get(s);
            st[0] = st[0] ^ 1;
            float[] buf = ab[st[0]];
            java.util.Arrays.fill(buf, 0, count, 0f);
            sinkBufState.put(s, st);
            return buf;
        }
    }

    // ==================== 内部：AudioRecord 降级链 ====================

    @SuppressWarnings("deprecation")
    private void startMicThreadLocked(int myGen) {
        final FrameSink s = sink;
        if (s == null || usingMic || micThread != null) return;
        final int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) return;
        final int myRetryGen = myGen;
        micThread = new Thread(() -> {
            AudioRecord ar = null;
            try {
                // 麦克风多音源尝试（局部变量持有防并发覆盖泄漏，与原实现一致）
                int[] sources = {
                        MediaRecorder.AudioSource.MIC,
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        MediaRecorder.AudioSource.UNPROCESSED
                };
                for (int source : sources) {
                    try {
                        ar = new AudioRecord(source, SAMPLE_RATE,
                                AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT, bufferSize);
                        if (ar.getState() == AudioRecord.STATE_INITIALIZED) break;
                        ar.release(); ar = null;
                    } catch (Exception e) {
                        if (ar != null) { try { ar.release(); } catch (Exception ignored) {} ar = null; }
                    }
                }
                if (ar == null) return;
                synchronized (SpectrumEngine.this) { audioRecord = ar; usingMic = true; }
                ar.startRecording();
                Thread.sleep(500);

                // FFT 补零到 1024（2^10），800 采样点零填充
                final int FFT_SIZE = 1024;
                short[] readBuffer = new short[800];
                float[] fftReal = new float[FFT_SIZE];
                float[] fftImag = new float[FFT_SIZE];
                int[] bitRevTable = new int[FFT_SIZE];
                int bits = 10;
                for (int i = 0; i < FFT_SIZE; i++) {
                    int rev = 0, val = i;
                    for (int b = 0; b < bits; b++) { rev = (rev << 1) | (val & 1); val >>= 1; }
                    bitRevTable[i] = rev;
                }

                while (isGenerationActive(myRetryGen)) {
                    int totalRead = ar.read(readBuffer, 0, 800);
                    if (totalRead <= 0) continue;

                    // 零填充 + Hann 窗（减少频谱泄漏）
                    for (int i = 0; i < FFT_SIZE; i++) {
                        if (i < totalRead) {
                            double window = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (totalRead - 1)));
                            fftReal[i] = readBuffer[i] * (float) window;
                        } else {
                            fftReal[i] = 0;
                        }
                        fftImag[i] = 0;
                    }
                    // Cooley-Tukey 原地 FFT（bit-reverse + 蝶形）
                    for (int i = 0; i < FFT_SIZE; i++) {
                        int j = bitRevTable[i];
                        if (j > i) {
                            float tmp = fftReal[i]; fftReal[i] = fftReal[j]; fftReal[j] = tmp;
                            tmp = fftImag[i]; fftImag[i] = fftImag[j]; fftImag[j] = tmp;
                        }
                    }
                    for (int len = 2; len <= FFT_SIZE; len <<= 1) {
                        int halfLen = len >> 1;
                        double angleStep = -2.0 * Math.PI / len;
                        for (int i = 0; i < FFT_SIZE; i += len) {
                            for (int j = 0; j < halfLen; j++) {
                                double angle = angleStep * j;
                                float wr = (float) Math.cos(angle);
                                float wi = (float) Math.sin(angle);
                                int idx1 = i + j, idx2 = i + j + halfLen;
                                float tr = wr * fftReal[idx2] - wi * fftImag[idx2];
                                float ti = wr * fftImag[idx2] + wi * fftReal[idx2];
                                fftReal[idx2] = fftReal[idx1] - tr;
                                fftImag[idx2] = fftImag[idx1] - ti;
                                fftReal[idx1] += tr;
                                fftImag[idx1] += ti;
                            }
                        }
                    }

                    int count = Math.max(s.getBarCount(), 1);
                    float[] magnitudes = acquireBuf(s, count);
                    float maxMag = 0;
                    for (int bar = 0; bar < count; bar++) {
                        int k = bar + 1; // 跳过 DC
                        if (k >= FFT_SIZE / 2) k = FFT_SIZE / 2 - 1;
                        float re = fftReal[k], im = fftImag[k];
                        float mag = (float) Math.sqrt(re * re + im * im) / FFT_SIZE;
                        magnitudes[bar] = mag;
                        if (mag > maxMag) maxMag = mag;
                    }
                    s.onSpectrumFrame(magnitudes, maxMag, SOURCE_MIC);
                }
            } catch (Exception ignored) {
            } finally {
                // 采集线程退出前自我清场：stop/release 在本线程执行
                //（5.1 模拟器 AudioFlinger Binder 卡死，主线程调用会 ANR；原实现的并发防护保留）
                synchronized (SpectrumEngine.this) {
                    if (audioRecord == ar) audioRecord = null;
                    usingMic = false;
                    if (micThread == Thread.currentThread()) micThread = null;
                }
                if (ar != null) {
                    try { ar.stop(); } catch (Exception ignored) {}
                    try { ar.release(); } catch (Exception ignored) {}
                }
            }
        }, "SpectrumThread");
        micThread.start();
    }

    private boolean isGenerationActive(int myGen) {
        synchronized (this) { return generation == myGen; }
    }

    // ==================== 内部：释放 ====================

    /** 断回调 → 禁用 → 延迟 native release（8.1 车机 UAF 防御，照抄原实现） */
    private void teardownVisualizerLocked() {
        if (visualizer != null) {
            final Visualizer v = visualizer;
            visualizer = null;
            visualizerEnabledSessionId = 0;
            retryInProgress = false;
            try {
                v.setEnabled(false);
                v.setDataCaptureListener(null, 0, false, false);
            } catch (Exception ignored) {}
            // 延迟释放：给 native CaptureThread 退出时间
            try {
                mainHandler.postDelayed(() -> {
                    try { v.release(); } catch (Exception ignored) {}
                }, DELAYED_RELEASE_MS);
            } catch (Exception ignored) {}
        }
        retryInProgress = false;
    }

    /** AudioRecord 线程的代际失效即自然退出；此处同步置失效并尽力 final 释放 */
    private void stopMicThreadLocked() {
        generation++; // 令 micThread 的 isGenerationActive 立即为 false
        final AudioRecord ar = audioRecord;
        audioRecord = null;
        final Thread t = micThread;
        micThread = null;
        usingMic = false;
        // 释放交给线程自身 finally（防主线程 Binder 卡死）；
        // 线程已死则兜底在后台执行
        if (t != null && t.isAlive()) {
            final Thread ft = t;
            new Thread(() -> {
                try { ft.join(1000); } catch (InterruptedException ignored) {}
                if (ar != null && ft.isAlive()) return; // 仍在自清场
                if (ar != null) { try { ar.release(); } catch (Exception ignored) {} }
            }, "SpectrumMicReaper").start();
        } else if (ar != null) {
            try { ar.release(); } catch (Exception ignored) {}
        }
    }

    private int safeSessionId(SessionIdProvider p) {
        try { return p.getAudioSessionId(); } catch (Exception e) { return -1; }
    }
}
