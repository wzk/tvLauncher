package com.tvlauncher

import android.content.Context

/**
 * SharedPreferences 配置存取封装。
 * 持久化保存：默认启动应用包名、自定义背景图路径/URI。
 */
object Prefs {

    private const val FILE_NAME = "tv_launcher_prefs"
    private const val KEY_DEFAULT_APP = "default_app_package"
    private const val KEY_CUSTOM_BG = "custom_background"
    private const val KEY_IMG_FORMAT = "preferred_image_format"

    private fun sp(context: Context) =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** 获取默认开机启动应用的包名，未设置返回 null */
    fun getDefaultAppPackage(context: Context): String? =
        sp(context).getString(KEY_DEFAULT_APP, null)

    /** 设置/清除默认开机启动应用（传 null 清除） */
    fun setDefaultAppPackage(context: Context, pkg: String?) =
        sp(context).edit().putString(KEY_DEFAULT_APP, pkg).apply()

    /** 获取自定义背景图的路径或 content:// URI，未设置返回 null */
    fun getCustomBackground(context: Context): String? =
        sp(context).getString(KEY_CUSTOM_BG, null)

    /** 设置/清除自定义背景图（传 null 清除） */
    fun setCustomBackground(context: Context, value: String?) =
        sp(context).edit().putString(KEY_CUSTOM_BG, value).apply()

    /** 获取设备偏好的图片格式（JPEG/PNG），由诊断自动设置 */
    fun getPreferredImageFormat(context: Context): String =
        sp(context).getString(KEY_IMG_FORMAT, "JPEG") ?: "JPEG"

    /** 设置设备偏好的图片格式（诊断后自动调用） */
    fun setPreferredImageFormat(context: Context, format: String) =
        sp(context).edit().putString(KEY_IMG_FORMAT, format).apply()
}
