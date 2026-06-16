# ===== 项目自身类 =====

# Activity 类 - 布局回调、onClick 等通过反射调用
-keep class com.jingxin.jingxinmusic.MainActivity { *; }
-keep class com.jingxin.jingxinmusic.PlayerActivity { *; }
-keep class com.jingxin.jingxinmusic.ui.WebDavSettingsActivity { *; }

# CoverScene 策略 - 接口和实现类被 PlayerActivity 动态调用
-keep class com.jingxin.jingxinmusic.scene.CoverScene { *; }
-keep class com.jingxin.jingxinmusic.scene.CoverSceneHelper { *; }
-keep class com.jingxin.jingxinmusic.scene.CoverSceneHelper$Callback { *; }
-keep class com.jingxin.jingxinmusic.scene.PortraitClassicScene { *; }
-keep class com.jingxin.jingxinmusic.scene.PortraitImmersiveScene { *; }
-keep class com.jingxin.jingxinmusic.scene.LandscapeClassicScene { *; }
-keep class com.jingxin.jingxinmusic.scene.LandscapeImmersiveScene { *; }

# CoverLoader - 回调接口被 Activity/Service 实现
-keep class com.jingxin.jingxinmusic.util.CoverLoader { *; }
-keep interface com.jingxin.jingxinmusic.util.CoverLoader$CoverCallback { *; }

# BitmapUtil - 公共方法被多处调用
-keep class com.jingxin.jingxinmusic.util.BitmapUtil { *; }

# ThemeColors - 静态方法被多处调用
-keep class com.jingxin.jingxinmusic.util.ThemeColors { *; }

# MiniFloatService - 动态构建视图，公共字段/方法不能混淆
-keep class com.jingxin.jingxinmusic.service.MiniFloatService { *; }

# Song 模型 - 公共字段被多个类直接读写，BrowseItem 同理
-keep class com.jingxin.jingxinmusic.model.Song { *; }
-keep class com.jingxin.jingxinmusic.model.BrowseItem { *; }

# FolderInfo 模型 - 被 SongAdapter 内部使用
-keep class com.jingxin.jingxinmusic.model.FolderInfo { *; }

# KrcParser - 内部类被外部直接访问
-keep public class com.jingxin.jingxinmusic.util.KrcParser { *; }
-keep public class com.jingxin.jingxinmusic.util.KrcParser$* { *; }

# LrcParser
-keep public class com.jingxin.jingxinmusic.util.LrcParser { *; }

# CoverFetcher - 回调接口被外部实现
-keep class com.jingxin.jingxinmusic.util.CoverFetcher { *; }
-keep interface com.jingxin.jingxinmusic.util.CoverFetcher$CoverCallback { *; }

# LyricFetcher - 回调接口被外部实现
-keep class com.jingxin.jingxinmusic.util.LyricFetcher { *; }
-keep interface com.jingxin.jingxinmusic.util.LyricFetcher$LyricCallback { *; }

# LyricView - 枚举被 Activity 直接引用
-keep class com.jingxin.jingxinmusic.view.LyricView { *; }
-keep class com.jingxin.jingxinmusic.view.LyricView$DisplayMode { *; }
-keep class com.jingxin.jingxinmusic.view.LyricView$ThemeMode { *; }
-keep interface com.jingxin.jingxinmusic.view.LyricView$OnModeChangeListener { *; }

# FavoriteManager - JSON 序列化依赖字段名
-keep class com.jingxin.jingxinmusic.util.FavoriteManager { *; }

# HistoryManager - 内部类通过 JSON 序列化
-keep class com.jingxin.jingxinmusic.util.HistoryManager { *; }
-keep class com.jingxin.jingxinmusic.util.HistoryManager$HistoryItem { *; }

# SongAdapter - 接口被 Activity 实现
-keep class com.jingxin.jingxinmusic.adapter.SongAdapter { *; }
-keep interface com.jingxin.jingxinmusic.adapter.SongAdapter$OnSongClickListener { *; }

# BrowseAdapter - 公共字段被外部访问
-keep class com.jingxin.jingxinmusic.adapter.BrowseAdapter { *; }

# MusicPlayerService - Binder 被跨进程使用
-keep class com.jingxin.jingxinmusic.service.MusicPlayerService { *; }
-keep class com.jingxin.jingxinmusic.service.MusicPlayerService$MusicBinder { *; }

# WebDAV 相关
-keep class com.jingxin.jingxinmusic.util.WebDavConfig { *; }
-keep class com.jingxin.jingxinmusic.util.WebDavScanner { *; }
-keep class com.jingxin.jingxinmusic.util.WebDavScanner$DavItem { *; }
-keep class com.jingxin.jingxinmusic.util.WebDavCacheManager { *; }
-keep class com.jingxin.jingxinmusic.util.LocalDirectoryScanner { *; }

# ===== 第三方库 =====

# Media3 - 仅 keep 被反射调用的类，库自带 consumer rules
-keep class androidx.media3.common.MediaItem { *; }
-keep class androidx.media3.common.MediaMetadata { *; }

# MediaSessionCompat - 跨进程使用
-keep class androidx.media.MediaSessionCompat { *; }
-keep class androidx.media.session.MediaSessionCompat { *; }

# sardine-android (WebDAV) - XML 解析冲突
-dontwarn org.xmlpull.v1.**
-dontwarn android.content.res.XmlResourceParser

# OkHttp / Okio - 库自带 consumer rules，仅需 dontwarn
-dontwarn okhttp3.**
-dontwarn okio.**
