# KrcParser - 公共方法和内部类被外部直接访问
-keep public class com.jingxin.jingxinmusic.util.KrcParser { *; }
-keep public class com.jingxin.jingxinmusic.util.KrcParser$* { *; }

# LrcParser - 公共方法被直接调用
-keep public class com.jingxin.jingxinmusic.util.LrcParser { *; }

# Song 模型 - 公共字段被多个类直接读写
-keep class com.jingxin.jingxinmusic.model.Song { *; }

# FolderInfo 模型 - 公共字段被 Adapter 直接访问
-keep class com.jingxin.jingxinmusic.model.FolderInfo { *; }

# CoverFetcher - 回调接口和公共方法被外部调用
-keep class com.jingxin.jingxinmusic.util.CoverFetcher { *; }
-keep interface com.jingxin.jingxinmusic.util.CoverFetcher$CoverCallback { *; }

# LyricFetcher - 回调接口和公共方法被外部调用
-keep class com.jingxin.jingxinmusic.util.LyricFetcher { *; }
-keep interface com.jingxin.jingxinmusic.util.LyricFetcher$LyricCallback { *; }

# LyricView - 枚举和公共方法被 Activity 直接调用
-keep class com.jingxin.jingxinmusic.view.LyricView { *; }
-keep class com.jingxin.jingxinmusic.view.LyricView$DisplayMode { *; }
-keep class com.jingxin.jingxinmusic.view.LyricView$ThemeMode { *; }
-keep interface com.jingxin.jingxinmusic.view.LyricView$OnModeChangeListener { *; }

# FavoriteManager - 公共方法被直接调用
-keep class com.jingxin.jingxinmusic.util.FavoriteManager { *; }

# HistoryManager - 公共方法被直接调用，内部类通过 JSON 序列化
-keep class com.jingxin.jingxinmusic.util.HistoryManager { *; }
-keep class com.jingxin.jingxinmusic.util.HistoryManager$HistoryItem { *; }

# SongAdapter - 接口和内部类被 Activity 引用
-keep class com.jingxin.jingxinmusic.adapter.SongAdapter { *; }
-keep interface com.jingxin.jingxinmusic.adapter.SongAdapter$OnSongClickListener { *; }
-keep interface com.jingxin.jingxinmusic.adapter.SongAdapter$OnFolderClickListener { *; }

# MusicPlayerService - 公共常量和 Binder 被 Activity 引用
-keep class com.jingxin.jingxinmusic.service.MusicPlayerService { *; }
-keep class com.jingxin.jingxinmusic.service.MusicPlayerService$MusicBinder { *; }

# ExoPlayer / Media3
-keep class com.google.android.exoplayer2.** { *; }
-keep class androidx.media3.** { *; }

# MediaSessionCompat
-keep class androidx.media.MediaSessionCompat { *; }
-keep class androidx.media.session.MediaSessionCompat { *; }

# AndroidX (appcompat, recyclerview)
-keep class androidx.appcompat.** { *; }
-keep class androidx.recyclerview.** { *; }

# R 资源文件
-keep class **.R$* { *; }

# sardine-android (WebDAV) - XML解析库冲突
-dontwarn org.xmlpull.v1.**
-keep class org.xmlpull.v1.** { *; }
-dontwarn android.content.res.XmlResourceParser

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# WebDAV新增模型
-keep class com.jingxin.jingxinmusic.model.BrowseItem { *; }
-keep class com.jingxin.jingxinmusic.adapter.BrowseAdapter { *; }
-keep class com.jingxin.jingxinmusic.util.WebDavConfig { *; }
-keep class com.jingxin.jingxinmusic.util.WebDavScanner { *; }
-keep class com.jingxin.jingxinmusic.util.WebDavScanner$DavItem { *; }
-keep class com.jingxin.jingxinmusic.util.WebDavCacheManager { *; }
-keep class com.jingxin.jingxinmusic.util.LocalDirectoryScanner { *; }
