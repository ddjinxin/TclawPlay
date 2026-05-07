package com.jingxin.jingxinmusic.model;

import java.util.List;

/**
 * 目录信息模型
 */
public class FolderInfo {
    public String folderPath;  // 完整路径
    public String folderName;  // 最后一级目录名
    public String coverArt;    // 封面（第一首歌的 albumArt）
    public List<Song> songs;   // 该目录下的歌曲
    public boolean expanded;   // 是否展开
    public boolean coverLoaded; // 封面是否已请求过（防止重复请求）

    public FolderInfo(String folderPath, String folderName, String coverArt, List<Song> songs) {
        this.folderPath = folderPath;
        this.folderName = folderName;
        this.coverArt = coverArt;
        this.songs = songs;
        this.expanded = false;
    }
}
