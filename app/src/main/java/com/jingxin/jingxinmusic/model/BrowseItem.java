package com.jingxin.jingxinmusic.model;

/**
 * 统一浏览项模型
 * 本地目录和WebDAV目录共用，用于卡片网格展示
 * 目录项：isDirectory=true，显示文件夹图标卡片
 * 歌曲项：isDirectory=false，显示封面卡片
 */
public class BrowseItem {

    public String name;           // 显示名称
    public String path;           // 标识路径（本地文件路径 或 WebDAV href路径）
    public String url;            // 完整URL/URI（用于播放或导航）
    public boolean isDirectory;   // 是否目录
    public long size;             // 文件大小
    public long modified;         // 修改时间
    public String contentType;    // MIME类型

    // 歌曲相关（仅非目录项有效）
    public Song song;             // 关联的Song对象（本地歌曲直接引用，WebDAV歌曲由DavItem转换）

    // 来源标记
    public static final int SOURCE_LOCAL = 0;
    public static final int SOURCE_WEBDAV = 1;
    public int source = SOURCE_LOCAL;

    public BrowseItem() {}

    /**
     * 创建目录项
     */
    public static BrowseItem directory(String name, String path, String url, int source) {
        BrowseItem item = new BrowseItem();
        item.name = name;
        item.path = path;
        item.url = url;
        item.isDirectory = true;
        item.source = source;
        return item;
    }

    /**
     * 创建本地歌曲项
     */
    public static BrowseItem localSong(Song song) {
        BrowseItem item = new BrowseItem();
        item.name = song.title;
        item.path = song.filePath;
        item.url = song.contentUri != null ? song.contentUri : song.filePath;
        item.isDirectory = false;
        item.source = SOURCE_LOCAL;
        item.song = song;
        item.size = 0;
        return item;
    }

    /**
     * 创建WebDAV歌曲项
     */
    public static BrowseItem webdavSong(String name, String path, String url,
                                         long size, long modified, String contentType) {
        BrowseItem item = new BrowseItem();
        item.name = name;
        item.path = path;
        item.url = url;
        item.isDirectory = false;
        item.source = SOURCE_WEBDAV;
        item.size = size;
        item.modified = modified;
        item.contentType = contentType;
        return item;
    }
}
