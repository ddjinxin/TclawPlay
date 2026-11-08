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
 * - 卡档（小卡显示）/ 展开档（6x6 大卡），键为稳定身份键（见 stableKeyOf）
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
    /**
     * 展开档独立缓存（条目数上限）：大图 1.6MB/张，若长驻主缓存，切歌浏览多首后累积
     * 几十 MB 挤占小卡空间，驱逐波烧回可见集（从绘制序最前的顶部卡开始闪）。
     * 固定 3 张封顶约 5MB（当前曲 + 刚切过的两首），永不满溢，永不挤小卡。
     */
    private static final int EXPANDED_KEEP = 3;
    private final LruCache<Key, Bitmap> expandedCache = new LruCache<Key, Bitmap>(EXPANDED_KEEP) {
        @Override protected int sizeOf(Key key, Bitmap value) { return 1; }
    };
    private final Set<Key> failed = new HashSet<>();
    private final Set<Key> inFlight = new HashSet<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    /** 封面加载专用共享池：与播放页主单线程池解耦——4路并行，防网络下载串行长队（填墙慢的主因） */
    private static final ExecutorService LOAD_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(4);
    private final Context context;
    private final Listener listener;
    /** 失败负缓存上限（防长队列烂封面撑爆） */
    private static final int MAX_FAILED = 256;

    public CollageCoverCache(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.cache = new LruCache<Key, Bitmap>(cacheBudgetBytes()) {
            @Override protected int sizeOf(Key key, Bitmap value) {
                return value.getByteCount();
            }
        };
    }

    /**
     * 缓存预算：堆/4，钳 [16MB, 96MB]。
     * 拼贴墙可见卡需求 ≈ 192 张 × 147KB（卡档）+ 展开档 1.6MB/张 ≈ 28MB+；
     * 旧的堆/8（24-32MB）在大列表时装不满可见集，缓存填满后进入"驱逐-重取"循环：
     * 被挤掉的可见卡下帧 miss 重解码，put 又挤掉别的卡，占位色/封面交替 = 封面闪烁。
     * 堆/4 让常见设备（192-512MB 堆）预算 48MB+ 稳超需求；16MB 下限兜小堆车机
     * （viewport 小、可见卡少，需求同步小），96MB 上限防大堆设备无意义占用（按需填充不预分配）。
     */
    private static int cacheBudgetBytes() {
        long quarter = Runtime.getRuntime().maxMemory() / 4;
        return (int) Math.max(16L << 20, Math.min(quarter, 96L << 20));
    }

    public static int stepSize(int step) {
        return STEP_SIZE[step];
    }

    /** 取缓存（可能为 null）；null 时内部发起异步解码 */
    public Bitmap get(Song song, int step) {
        if (song == null) return null;
        Key key = new Key(stableKeyOf(song), step);
        LruCache<Key, Bitmap> store = storeFor(step);
        Bitmap hit = store.get(key);
        if (hit != null && !hit.isRecycled()) return hit;
        if (failed.contains(key) || inFlight.contains(key)) return null;
        // 无缓存不挡路：异步拉
        request(song, step, key);
        return null;
    }

    /**
     * 稳定身份键：与歌名/队列无关、跨会话恒定。
     * song.id 不可用：B站歌源头 id=0，进队列靠"每次浏览从 1000000 重计的序号"就地覆写，
     * 跨会话/跨列表两批歌会撞同一 id → 封面墙命中"另外一首"的缓存图（杀进程重启后封面错乱的根因）。
     * 键控策略：本地=MediaStore id（稳定唯一）；B站=bvid+cid；WebDAV=路径+URL；电台=流地址（直播流无过期概念，跨会话恒定）。
     */
    static long stableKeyOf(Song song) {
        switch (song.sourceType) {
            case Song.SOURCE_BILI:
                return (song.bvid != null ? song.bvid.hashCode() : 0) * 1000003L ^ song.cid;
            case Song.SOURCE_WEBDAV:
                return (song.filePath != null ? song.filePath.hashCode() : 0) * 1000003L
                        ^ (song.contentUri != null ? song.contentUri.hashCode() : 0);
            case Song.SOURCE_RADIO:
                return (song.audioUrl != null ? song.audioUrl.hashCode() : 0) * 1000003L
                        ^ (song.filePath != null ? song.filePath.hashCode() : 0);
            default: // 本地：MediaStore id 跨会话稳定
                return song.id;
        }
    }

    /** 档位分流：展开档进条目数小缓存，其余进内存预算主缓存 */
    private LruCache<Key, Bitmap> storeFor(int step) {
        return step == STEP_EXPANDED ? expandedCache : cache;
    }

    private void request(final Song song, final int step, final Key key) {
        inFlight.add(key);
        boolean preferLocal = SettingsFragment.isLocalCoverPriority(context);
        CoverLoader.load(context, song, STEP_SIZE[step], STEP_SIZE[step], true, preferLocal,
                LOAD_POOL, new CoverLoader.CoverCallback() {
                    @Override public void onCoverLoaded(Bitmap bitmap) {
                        main.post(() -> {
                            if (bitmap == null || bitmap.isRecycled()) {
                                inFlight.remove(key);
                                rememberFailed(key);
                                return;
                            }
                            deliverClamped(song, step, key, bitmap);
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

    /**
     * 交付钳制：decodeSampled 是 2 的幂"至少覆盖"降采样，req=192 时 500px 原封面解出
     * 250px（250KB）、2000px 原封面解出 500px（1MB），条目体积 2-7 倍于账面 147KB，
     * 可见集实际需求 60MB+ 恒超预算 → LruCache 恒满 → 驱逐-重取轮转波（可见卡反复闪）。
     * 超目标 1/3 的条目后台精确缩放到贴目标，钉死条目体积，需求回到预算内、轮转断根。
     * inFlight 在缩放完成入缓存后才移除（期间 get 仍去重，不重复发起）。
     */
    private void deliverClamped(Song song, int step, Key key, Bitmap bitmap) {
        int target = STEP_SIZE[step];
        int maxSide = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (maxSide <= target * 4 / 3) {
            finishDelivery(song, step, key, bitmap);
            return;
        }
        LOAD_POOL.execute(() -> {
            float s = (float) target / maxSide;
            int w = Math.max(1, Math.round(bitmap.getWidth() * s));
            int h = Math.max(1, Math.round(bitmap.getHeight() * s));
            Bitmap scaled = null;
            try {
                scaled = Bitmap.createScaledBitmap(bitmap, w, h, step == STEP_EXPANDED);
            } catch (Throwable ignored) {}
            final Bitmap out = scaled != null ? scaled : bitmap;
            if (scaled != null && scaled != bitmap) bitmap.recycle(); // 原图私有交付，缩完回收
            main.post(() -> finishDelivery(song, step, key, out));
        });
    }

    private void finishDelivery(Song song, int step, Key key, Bitmap bitmap) {
        inFlight.remove(key);
        if (bitmap == null || bitmap.isRecycled()) {
            rememberFailed(key);
            return;
        }
        storeFor(step).put(key, bitmap);
        if (listener != null) listener.onCoverReady(song, step, bitmap);
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
