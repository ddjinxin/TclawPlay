package com.jingxin.jingxinmusic.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.jingxin.jingxinmusic.R;
import com.jingxin.jingxinmusic.model.Song;

/**
 * 播放页歌名+歌手组件
 *
 * 封装：标题/歌手文本与主题染色。
 * 阶段性设计（组件化第1步）：
 * - 文字大小/margin 手术由 Scene 层继续执行，故暴露 songNameView()/artistView()
 * - setSong 语义化接口逐步替代 Fragment 直接 setText
 */
public class SongTitleBar extends LinearLayout {

    private TextView tvSongName;
    private TextView tvArtist;

    public SongTitleBar(Context context) {
        super(context);
        init(context);
    }

    public SongTitleBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public SongTitleBar(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setOrientation(VERTICAL);
        LayoutInflater.from(context).inflate(R.layout.view_song_title_bar, this, true);
        tvSongName = findViewById(R.id.song_name_text);
        tvArtist = findViewById(R.id.artist_text);
    }

    /** 歌曲信息更新（标题+歌手一次性） */
    public void setSong(Song song) {
        tvSongName.setText(song == null ? "未知歌曲" : song.title);
        tvArtist.setText(song == null ? "未知歌手" : song.artist);
    }

    /** 主题染色：标题+歌手同色 */
    public void setTextColor(int color) {
        tvSongName.setTextColor(color);
        tvArtist.setTextColor(color);
    }

    // ===== 兼容访问（Scene 布局手术 / 歌词定位取坐标） =====

    public TextView songNameView() {
        return tvSongName;
    }

    public TextView artistView() {
        return tvArtist;
    }
}
