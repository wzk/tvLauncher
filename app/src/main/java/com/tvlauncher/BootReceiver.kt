package com.tvlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启动广播接收器。
 *
 * 收到开机完成广播后拉起主界面。
 * 注意：Android 10+ 系统限制了后台启动 Activity，但由于本应用声明了
 * category.HOME（即系统桌面），开机时系统本身就会拉起默认桌面；
 * 此接收器用于"尚未被设为默认桌面"或支持快速开机的盒子设备兜底。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == ACTION_QUICKBOOT_POWERON) {
            val launch = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(launch)
            } catch (_: Exception) {
                // 极少数 ROM 限制后台启动时静默失败（系统会自行拉起桌面）
            }
        }
    }

    companion object {
        private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
