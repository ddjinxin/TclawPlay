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

    // ===== 当前值 =====
    private volatile State state = new State(null, false, 0, false, 0, false);
    private volatile int positionMs = 0;
    private volatile int durationMs = 0;

    // ===== 观察者 =====
    private final List<Observer> observers = new CopyOnWriteArrayList<>();
    private final List<ProgressObserver> progressObservers = new CopyOnWriteArrayList<>();
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
