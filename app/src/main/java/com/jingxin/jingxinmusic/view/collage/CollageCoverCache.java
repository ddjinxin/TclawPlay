package com.jingxin.jingxinmusic.view.collage;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import com.jingxin.jingxinmusic.fragment.SettingsFragment;
import com.jingxin.jingxinmusic.model.Song;
import com.jingxin.jingxinmusic.util.CoverLoader;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * 拼贴墙封面缓存（两档位解码，车机版）
 *
 * - 卡档（小卡显示）/ 展开档（6x6 大卡）
 * - 先显示卡档，展开档解码完成回调升级（只升不降，模糊胜过空白）
 * - 失败负缓存防重试（本地封面缺二进制 404 场景）
 * - in-flight 去重：同 key 并发请求只发一次
 * - LruCache 总量钳制 maxMemory/8
 */
public class CollageCoverCache {

    /** 档位 */
    public static final int STEP_CARD = 0;    // 普通卡
    public static final int STEP_EXPANDED = 1; // 展开卡

    private static final int[] STEP_SIZE = { 192, 640 };

    public interface Listener {
        /** 指定 key 的位图就绪（可能是从缓存直出或异步解码完成） */
        void onCoverReady(Song song, int step, Bitmap bitmap);
    }

    private static class Key {
        final long songId;
        final int step;

        Key(long songId, int step) { this.songId = songId; this.step = step; }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Key)) return false;
            Key k = (Key) o;
            return songId == k.songId && step == k.step;
        }
        @Override public int hashCode() { return (int) (songId * 31 + step); }
    }

    private final LruCache<Key, Bitmap> cache;
    private final Set<Key> failed = new HashSet<>();
    private final Set<Key> inFlight = new HashSet<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile ExecutorService executor; // 页面 install 后注入
    private final Context context;
    private final Listener listener;
    /** 失败负缓存上限（防长队列烂封面撑爆） */
    private static final int MAX_FAILED = 256;

    public CollageCoverCache(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.cache = new LruCache<Key, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 8)) {
            @Override protected int sizeOf(Key key, Bitmap value) {
                return value.getByteCount();
            }
        };
    }

    public static int stepSize(int step) {
        return STEP_SIZE[step];
    }

    public void setExecutor(ExecutorService executor) {
        this.executor = executor;
    }

    /** 取缓存（可能为 null）；null 时内部发起异步解码 */
    public Bitmap get(Song song, int step) {
        if (song == null) return null;
        Key key = new Key(song.id, step);
        Bitmap hit = cache.get(key);
        if (hit != null && !hit.isRecycled()) return hit;
        if (failed.contains(key) || inFlight.contains(key)) return null;
        // 无缓存不挡路：异步拉
        request(song, step, key);
        return null;
    }

    private void request(final Song song, final int step, final Key key) {
        final ExecutorService exec = executor;
        if (exec == null) return; // 未注入 executor：等注入后首次 get 重试（不在 failed/inFlight 里）
        inFlight.add(key);
        boolean preferLocal = SettingsFragment.isLocalCoverPriority(context);
        CoverLoader.load(context, song, STEP_SIZE[step], STEP_SIZE[step], true, preferLocal,
                exec, new CoverLoader.CoverCallback() {
                    @Override public void onCoverLoaded(Bitmap bitmap) {
                        main.post(() -> {
                            inFlight.remove(key);
                            if (bitmap == null || bitmap.isRecycled()) {
                                rememberFailed(key);
                                return;
                            }
                            cache.put(key, bitmap);
                            if (listener != null) listener.onCoverReady(song, step, bitmap);
                        });
                    }
                    @Override public void onCoverFailed() {
                        main.post(() -> {
                            inFlight.remove(key);
                            rememberFailed(key);
                        });
                    }
                });
    }

    private void rememberFailed(Key key) {
        if (failed.size() >= MAX_FAILED) {
            Iterator<Key> it = failed.iterator();
            if (it.hasNext()) it.next();
            it.remove();
        }
        failed.add(key);
    }
}
