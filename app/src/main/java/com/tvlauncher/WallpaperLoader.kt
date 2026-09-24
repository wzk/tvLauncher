package com.tvlauncher

/**
 * 应用私有目录中的壁纸（启动等待画面图片）文件名。
 * 设置页选图/HTTP 下载/adb 手动放置统一使用该文件，
 * 主界面等待画面直接以私有目录中该文件的存在与否为准。
 */
object WallpaperLoader {
    const val PRIVATE_WALLPAPER_FILE = "wallpaper_custom"
}
