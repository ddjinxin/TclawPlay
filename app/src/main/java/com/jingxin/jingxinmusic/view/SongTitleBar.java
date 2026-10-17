package com.jingxin.jingxinmusic.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.jingxin.jingxinmusic.R;
import com.jingxin.jingxinmusic.model.Song;
import com.jingxin.jingxinmusic.player.PlayerStateHub;

/**
 * 播放页歌名+歌手组件
 *
 * 双模式（组件化第2步）：
 * - 订阅模式：attachHub(stateHub)，切歌自动刷新
 * - 直调模式：setSong 保留（过渡期兼容）
 */
public class SongTitleBar extends LinearLayout {

    private TextView tvSongName;
    private TextView tvArtist;
    private PlayerStateHub hub;
    private PlayerStateHub.Observer hubObserver;

    /** 绑定数据中枢（订阅模式：切歌自动刷新） */
    public void attachHub(PlayerStateHub hub) {
        detachHub();
        this.hub = hub;
        hubObserver = state -> setSong(state.song);
        hub.addObserver(hubObserver);
        setSong(hub.getSong()); // 立即应用当前快照
    }

    /** 解绑数据中枢（onDestroyView 调用，防泄漏） */
    public void detachHub() {
        if (hub != null && hubObserver != null) {
            hub.removeObserver(hubObserver);
        }
        hub = null;
        hubObserver = null;
    }

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
