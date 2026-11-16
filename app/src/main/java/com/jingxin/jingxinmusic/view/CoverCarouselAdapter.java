package com.jingxin.jingxinmusic.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.widget.ImageView;

import com.jingxin.jingxinmusic.R;
import com.jingxin.jingxinmusic.model.Song;
import com.jingxin.jingxinmusic.util.CoverLoader;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * 轮播封面数据管理器
 *
 * 管理当前播放列表中5首歌曲的封面加载：
 * - 索引0=上上一曲, 1=上一曲, 2=当前曲, 3=下一曲, 4=下下一曲
 * - 数据来源：播放列表（playlist），不是全部本地歌曲
 * - 切歌时调用 update() 重新加载5张封面
 */
public class CoverCarouselAdapter {

    private final Context context;
    private final ExecutorService executor;
    private List<Song> playlist;
    private int currentIndex;  // 当前播放歌曲在 playlist 中的索引

    // 5个歌曲引用，方便封面加载时比对
    private final Song[] visibleSongs = new Song[5];

    // 内存封面缓存（key=歌曲名+艺人），切歌时命中即直接显示，避免默认封面闪烁
    private static final int MAX_CACHE = 24;
    private final Map<String, Bitmap> coverCache =
            new LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                    return size() > MAX_CACHE;
                }
            };

    public CoverCarouselAdapter(Context context, ExecutorService executor) {
        this.context = context;
        this.executor = executor;
    }

    /**
     * 设置播放列表和当前歌曲索引，加载5张封面到对应的ImageView
     *
     * @param playlist     当前播放列表
     * @param currentIndex 当前歌曲在列表中的索引
     * @param cards        CoverCarouselView 的5个 ImageView（0=左二, 1=左一, 2=中间, 3=右一, 4=右二）
     */
    public void update(List<Song> playlist, int currentIndex, ImageView[] cards) {
        this.playlist = playlist;
        this.currentIndex = currentIndex;
        loadCovers(cards);
    }

    /**
     * 只更新当前索引并重新加载（列表不变时用）
     */
    public void updatePosition(int currentIndex, ImageView[] cards) {
        this.currentIndex = currentIndex;
        loadCovers(cards);
    }

    private void loadCovers(ImageView[] cards) {
        if (cards == null || cards.length < 5) return;

        // 计算5张卡片对应的歌曲索引
        // cards[0]=左二=currentIndex-2, cards[1]=左一=currentIndex-1,
        // cards[2]=中间=currentIndex, cards[3]=右一=currentIndex+1,
        // cards[4]=右二=currentIndex+2
        int[] offsets = {-2, -1, 0, 1, 2};

        for (int i = 0; i < 5; i++) {
            int songIndex = currentIndex + offsets[i];
            ImageView iv = cards[i];

            if (playlist != null && songIndex >= 0 && songIndex < playlist.size()) {
                Song song = playlist.get(songIndex);
                visibleSongs[i] = song;
                loadCoverForCard(iv, song, i);
            } else {
                // 超出列表范围，显示空封面
                visibleSongs[i] = null;
                iv.setImageResource(R.mipmap.ic_launcher);
                iv.setAlpha(i == 2 ? 1.0f : 0.5f);
            }
        }
    }

    private void loadCoverForCard(ImageView iv, Song song, int cardIndex) {
        // 封面缓存 key 与工程既有缓存命名同源（歌曲名+艺人）
        String key = Song.toFileName(song.title, song.artist);
        Bitmap cached = coverCache.get(key);
        // 命中内存缓存：直接显示正确封面，不经过默认图，消除切歌闪烁
        if (cached != null) {
            iv.setImageBitmap(cached);
            iv.setAlpha(cardIndex == 2 ? 1.0f : (cardIndex == 1 || cardIndex == 3) ? 0.85f : 0.7f);
            return;
        }
        // 未命中：先设默认封面（品牌图标），异步加载完成后替换
        iv.setImageResource(R.mipmap.ic_launcher);
        // 中间卡片不透明，侧边卡片半透明
        iv.setAlpha(cardIndex == 2 ? 1.0f : (cardIndex == 1 || cardIndex == 3) ? 0.85f : 0.7f);

        if (executor != null) {
            boolean preferLocal = com.jingxin.jingxinmusic.fragment.SettingsFragment.isLocalCoverPriority(context);
            CoverLoader.load(context, song, 300, 300, true, preferLocal, executor,
                    new CoverLoader.CoverCallback() {
                        @Override
                        public void onCoverLoaded(Bitmap bitmap) {
                            // 加载结果入缓存，供后续切歌直接命中
                            coverCache.put(key, bitmap);
                            // 确保ImageView还是对应同一首歌
                            if (visibleSongs[cardIndex] == song) {
                                iv.setImageBitmap(bitmap);
                            }
                        }

                        @Override
                        public void onCoverFailed() {
                            // 保持默认封面
                        }
                    });
        }
    }
}
