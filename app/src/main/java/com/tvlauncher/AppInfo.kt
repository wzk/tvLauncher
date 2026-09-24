package com.tvlauncher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import java.text.Collator

/**
 * 应用条目数据类
 *
 * @param label             应用显示名称
 * @param packageName       应用包名
 * @param activityClassName 入口 Activity 全类名（用于精确启动）
 * @param category          入口分类（LAUNCHER 或 LEANBACK_LAUNCHER，用于精确启动）
 * @param icon              应用图标
 */
data class AppInfo(
    val label: String,
    val packageName: String,
    val activityClassName: String,
    val category: String,
    val icon: Drawable
)

/**
 * 已安装应用列表加载器。
 * 同时查询手机入口（LAUNCHER）与电视入口（LEANBACK_LAUNCHER）并合并去重，
 * 确保电视盒子上的电视专属应用也能被列出和启动。
 */
object AppListLoader {

    /** 加载所有可启动的应用，按名称排序（本应用自身除外） */
    fun load(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val selfPackage = context.packageName
        val result = ArrayList<AppInfo>()

        fun collect(category: String) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            val resolveInfos = try {
                pm.queryIntentActivities(intent, 0)
            } catch (_: Exception) {
                emptyList()
            }
            for (ri in resolveInfos) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (pkg == selfPackage) continue
                // 同一应用只保留第一个入口
                if (result.any { it.packageName == pkg }) continue
                val label = try {
                    ri.loadLabel(pm).toString().ifBlank { pkg }
                } catch (_: Exception) {
                    pkg
                }
                val icon = try {
                    ri.loadIcon(pm)
                } catch (_: Exception) {
                    pm.defaultActivityIcon
                }
                result.add(
                    AppInfo(
                        label = label,
                        packageName = pkg,
                        activityClassName = ri.activityInfo.name,
                        category = category,
                        icon = icon
                    )
                )
            }
        }

        collect(Intent.CATEGORY_LAUNCHER)
        collect(Intent.CATEGORY_LEANBACK_LAUNCHER)

        // 使用 Collator 排序，保证中文名称按拼音正确排序
        val collator = Collator.getInstance()
        result.sortWith(compareBy(collator) { it.label })
        return result
    }

    /** 构建启动指定应用的 Intent（优先使用缓存列表中的入口组件信息） */
    fun buildLaunchIntent(pm: PackageManager, app: AppInfo): Intent =
        Intent(Intent.ACTION_MAIN)
            .addCategory(app.category)
            .setClassName(app.packageName, app.activityClassName)
}
