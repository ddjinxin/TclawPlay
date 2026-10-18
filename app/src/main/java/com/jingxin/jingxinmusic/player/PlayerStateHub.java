package com.jingxin.jingxinmusic.player;

import android.os.Handler;
import android.os.Looper;

import com.jingxin.jingxinmusic.model.Song;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 播放页状态中枢（单一数据源）
 *
 * 播放页所有状态（当前歌曲/播放态/进度/播放模式/日夜/皮肤/封面双色）的
 * 唯一持有者。UI 组件与页面订阅字段变化，不再各自从 Service 拉取。
 *
 * 设计约束（组件化第2步）：
 * - 值语义：仅携带不可变字段，set 时值未变化不通知（防重复刷新）
 * - 线程：任意线程 set，观察者统一主线程回调
 * - 生命周期：观察者需自行注销（Fragment/组件 destroy 时调用 removeObserver）
 * - 进度类高频字段单独通道（ProgressObserver），避免整包刷新
 */
public class PlayerStateHub {

    // ===== 单例 =====
    private static volatile PlayerStateHub sInstance;

    public static PlayerStateHub get() {
        if (sInstance == null) {
            synchronized (PlayerStateHub.class) {
                if (sInstance == null) sInstance = new PlayerStateHub();
            }
        }
        return sInstance;
    }

    private PlayerStateHub() {}

    // ===== 状态模型（不可变值 + 版本快照） =====

    /** 播放状态快照 */
    public static class State {
        public final Song song;            // 当前歌曲（null=无）
        public final boolean playing;      // 是否正在播放
        public final int playOrder;        // 0=顺序 1=随机 2=单曲循环
        public final boolean nightMode;    // 日夜
        public final int bgSkinMode;       // 背景皮肤 0=默认 1=双色光晕
        public final boolean auroraActive; // 光晕是否当前生效

        public State(Song song, boolean playing, int playOrder,
                     boolean nightMode, int bgSkinMode, boolean auroraActive) {
            this.song = song;
            this.playing = playing;
            this.playOrder = playOrder;
            this.nightMode = nightMode;
            this.bgSkinMode = bgSkinMode;
            this.auroraActive = auroraActive;
        }

        public State copyWith(Song song, Boolean playing, Integer playOrder,
                              Boolean nightMode, Integer bgSkinMode, Boolean auroraActive) {
            return new State(
                    song != null ? song : this.song,
                    playing != null ? playing : this.playing,
                    playOrder != null ? playOrder : this.playOrder,
                    nightMode != null ? nightMode : this.nightMode,
                    bgSkinMode != null ? bgSkinMode : this.bgSkinMode,
                    auroraActive != null ? auroraActive : this.auroraActive);
        }
    }

    /** 核心状态观察者（歌/播放态/模式/日夜/皮肤） */
    public interface Observer {
        void onStateChanged(State state);
    }

    /** 高频进度观察者（进度轮询毫秒值，轻量） */
    public interface ProgressObserver {
        void onProgress(int positionMs, int durationMs);
    }

    /** 封面观察者（封面 bitmap，低频：切歌/加载完成） */
    public interface CoverObserver {
        void onCoverChanged(android.graphics.Bitmap bitmap);
    }

    /** 封面双色观察者（主色 top / 副色 bottom，光晕皮肤消费） */
    public interface CoverColorObserver {
        void onCoverColorsChanged(int colorTop, int colorBottom);
    }

    /** 歌词观察者（歌词数据，低频：切歌/搜索下载完成） */
    public interface LyricObserver {
        void onLyricLoaded(com.jingxin.jingxinmusic.util.KrcParser.LyricData data);
    }

    // ===== 当前值 =====
    private volatile State state = new State(null, false, 0, false, 0, false);
    private volatile int positionMs = 0;
    private volatile int durationMs = 0;
    private volatile android.graphics.Bitmap coverBitmap = null;
    private volatile boolean hasCoverColors = false;
    private volatile int coverColorTop = 0;
    private volatile int coverColorBottom = 0;
    private volatile com.jingxin.jingxinmusic.util.KrcParser.LyricData lyricData = null;

    // ===== 观察者 =====
    private final List<Observer> observers = new CopyOnWriteArrayList<>();
    private final List<ProgressObserver> progressObservers = new CopyOnWriteArrayList<>();
    private final List<CoverObserver> coverObservers = new CopyOnWriteArrayList<>();
    private final List<CoverColorObserver> coverColorObservers = new CopyOnWriteArrayList<>();
    private final List<LyricObserver> lyricObservers = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ===== 订阅 =====

    public void addObserver(Observer o) {
        if (o != null && !observers.contains(o)) observers.add(o);
    }

    public void removeObserver(Observer o) {
        observers.remove(o);
    }

    public void addProgressObserver(ProgressObserver o) {
        if (o != null && !progressObservers.contains(o)) progressObservers.add(o);
    }

    public void removeProgressObserver(ProgressObserver o) {
        progressObservers.remove(o);
    }

    public void addCoverObserver(CoverObserver o) {
        if (o != null && !coverObservers.contains(o)) coverObservers.add(o);
    }

    public void removeCoverObserver(CoverObserver o) {
        coverObservers.remove(o);
    }

    public void addCoverColorObserver(CoverColorObserver o) {
        if (o != null && !coverColorObservers.contains(o)) coverColorObservers.add(o);
    }

    public void removeCoverColorObserver(CoverColorObserver o) {
        coverColorObservers.remove(o);
    }

    public void addLyricObserver(LyricObserver o) {
        if (o != null && !lyricObservers.contains(o)) lyricObservers.add(o);
    }

    public void removeLyricObserver(LyricObserver o) {
        lyricObservers.remove(o);
    }

    // ===== 同步读取 =====

    public State getState() {
        return state;
    }

    public Song getSong() {
        return state.song;
    }

    public boolean isPlaying() {
        return state.playing;
    }

    public int getPlayOrder() {
        return state.playOrder;
    }

    public boolean isNightMode() {
        return state.nightMode;
    }

    public int getBgSkinMode() {
        return state.bgSkinMode;
    }

    public int getPositionMs() {
        return positionMs;
    }

    public int getDurationMs() {
        return durationMs;
    }

    /** 当前封面快照（可能为 null：无封面/未加载） */
    public android.graphics.Bitmap getCover() {
        return coverBitmap;
    }

    /** 当前歌词快照（可能为 null） */
    public com.jingxin.jingxinmusic.util.KrcParser.LyricData getLyricData() {
        return lyricData;
    }

    /** 封面双色是否已提取 */
    public boolean hasCoverColors() {
        return hasCoverColors;
    }

    // ===== 写入（值语义，变化才通知） =====

    public void setSong(Song song) {
        State cur = state;
        if (cur.song == song || (cur.song != null && song != null
                && cur.song.title.equals(song.title) && cur.song.artist.equals(song.artist))) {
            return; // 同歌不通知
        }
        updateState(cur.copyWith(song, null, null, null, null, null));
    }

    public void setPlaying(boolean playing) {
        if (state.playing == playing) return;
        updateState(state.copyWith(null, playing, null, null, null, null));
    }

    public void setPlayOrder(int order) {
        if (state.playOrder == order) return;
        updateState(state.copyWith(null, null, order, null, null, null));
    }

    public void setNightMode(boolean night) {
        if (state.nightMode == night) return;
        updateState(state.copyWith(null, null, null, night, null, null));
    }

    public void setBgSkinMode(int mode) {
        if (state.bgSkinMode == mode) return;
        updateState(state.copyWith(null, null, null, null, mode, null));
    }

    public void setAuroraActive(boolean active) {
        if (state.auroraActive == active) return;
        updateState(state.copyWith(null, null, null, null, null, active));
    }

    /** 进度高频更新（值变化才通知，主线程回调） */
    public void setProgress(int positionMs, int durationMs) {
        if (this.positionMs == positionMs && this.durationMs == durationMs) return;
        this.positionMs = positionMs;
        this.durationMs = durationMs;
        notifyProgress();
    }

    // ===== 封面/歌词通道（低频，值语义：引用变化才通知） =====

    /**
     * 封面广播（生产端：Fragment.setCoverBitmap）。同一引用/未变化不重复通知。
     * 生命周期规约：消费者只引用不 recycle，Bitmap 由生产端生命周期管理。
     */
    public void setCover(android.graphics.Bitmap bitmap) {
        if (bitmap == null) return; // null 不广播（禁止清空语义误用）
        if (this.coverBitmap == bitmap) return;
        this.coverBitmap = bitmap;
        notifyOnMain(() -> {
            android.graphics.Bitmap b = coverBitmap;
            for (CoverObserver o : coverObservers) {
                try { o.onCoverChanged(b); } catch (Exception ignored) {}
            }
        });
    }

    /** 封面双色广播（生产端：封面取色完成后）。双 0 表示清除（下次广播重新生效） */
    public void setCoverColors(int colorTop, int colorBottom) {
        if (this.hasCoverColors && this.coverColorTop == colorTop
                && this.coverColorBottom == colorBottom) return;
        this.hasCoverColors = (colorTop != 0 || colorBottom != 0);
        this.coverColorTop = colorTop;
        this.coverColorBottom = colorBottom;
        notifyOnMain(() -> {
            int t = coverColorTop, b = coverColorBottom;
            for (CoverColorObserver o : coverColorObservers) {
                try { o.onCoverColorsChanged(t, b); } catch (Exception ignored) {}
            }
        });
    }

    /** 切歌时清除封面/双色/歌词缓存（新歌数据到来前，快照读旧值语义明确） */
    public void invalidateCoverAndLyric() {
        coverBitmap = null;
        hasCoverColors = false;
        coverColorTop = 0;
        coverColorBottom = 0;
        lyricData = null;
    }

    /** 歌词广播（生产端：歌词加载/下载完成）。空 lines 不广播 */
    public void setLyricData(com.jingxin.jingxinmusic.util.KrcParser.LyricData data) {
        if (data == null || data.lines == null || data.lines.isEmpty()) return;
        if (this.lyricData == data) return;
        this.lyricData = data;
        notifyOnMain(() -> {
            com.jingxin.jingxinmusic.util.KrcParser.LyricData d = lyricData;
            for (LyricObserver o : lyricObservers) {
                try { o.onLyricLoaded(d); } catch (Exception ignored) {}
            }
        });
    }

    /** 主线程统一投递（任意线程调用安全） */
    private void notifyOnMain(Runnable r) {
        if (mainHandler.getLooper() == Looper.myLooper()) {
            r.run();
        } else {
            mainHandler.post(r);
        }
    }

    private void updateState(State next) {
        state = next;
        notifyState();
    }

    private void notifyState() {
        if (mainHandler.getLooper() == Looper.myLooper()) {
            State snapshot = state;
            for (Observer o : observers) {
                try { o.onStateChanged(snapshot); } catch (Exception ignored) {}
            }
        } else {
            mainHandler.post(() -> {
                State s = state; // 重读最新值，合并投递期间的多次 set
                for (Observer o : observers) {
                    try { o.onStateChanged(s); } catch (Exception ignored) {}
                }
            });
        }
    }

    private void notifyProgress() {
        if (mainHandler.getLooper() == Looper.myLooper()) {
            int pos = positionMs, dur = durationMs;
            for (ProgressObserver o : progressObservers) {
                try { o.onProgress(pos, dur); } catch (Exception ignored) {}
            }
        } else {
            mainHandler.post(() -> {
                int p = positionMs, d = durationMs;
                for (ProgressObserver o : progressObservers) {
                    try { o.onProgress(p, d); } catch (Exception ignored) {}
                }
            });
        }
    }
}
