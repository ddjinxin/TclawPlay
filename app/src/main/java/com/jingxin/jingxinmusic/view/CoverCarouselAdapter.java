package com.jingxin.jingxinmusic.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.widget.ImageView;

import com.jingxin.jingxinmusic.R;
import com.jingxin.jingxinmusic.model.Song;
import com.jingxin.jingxinmusic.util.CoverLoader;

import java.util.List;
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
                iv.setImageResource(R.drawable.ic_music_icon);
                iv.setAlpha(i == 2 ? 1.0f : 0.5f);
            }
        }
    }

    private void loadCoverForCard(ImageView iv, Song song, int cardIndex) {
        // 先设置默认封面
        iv.setImageResource(R.drawable.ic_music_icon);
        // 中间卡片不透明，侧边卡片半透明
        iv.setAlpha(cardIndex == 2 ? 1.0f : (cardIndex == 1 || cardIndex == 3) ? 0.85f : 0.7f);

        if (executor != null) {
            CoverLoader.load(context, song, 300, 300, true, executor,
                    new CoverLoader.CoverCallback() {
                        @Override
                        public void onCoverLoaded(Bitmap bitmap) {
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
