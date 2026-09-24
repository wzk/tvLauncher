package com.tvlauncher

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.webkit.WebView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

/**
 * TV 桌面主界面。
 *
 * 职责：
 * 1. 全屏背景图（优先自定义路径/URI，回退内置；等待联网时若设置了背景图则只显示图片）；
 * 2. 展示全部可启动应用网格，遥控器 D-Pad 聚焦 + 确认启动；
 * 3. 网络检查：未联网时显示等待提示（无背景图时）并实时监听，联网后自动继续；
 * 4. 默认应用 Kiosk 逻辑（无倒计时浮层）：
 *    - 开机联网后延时 2 秒自动启动默认应用；
 *    - 从默认应用退出返回桌面时，立即重新拉起（短暂防抖）；
 *    - 上述等待期间按【返回】键取消启动、停留在桌面；
 *    - 快捷键【菜单】键随时进入桌面配置页。
 */
class MainActivity : Activity() {

    companion object {
        /** 开机联网后自动启动默认应用的延时 */
        private const val BOOT_DELAY_MS = 2000L

        /** 从默认应用退出后立即重进的防抖延迟（毫秒），避免与系统窗口切换竞态 */
        private const val RESTART_DELAY_MS = 600L

        /** 应用网格列数 */
        private const val GRID_COLUMNS = 5

    }

    private lateinit var ivWallpaper: ImageView
    private lateinit var wvWallpaper: WebView
    private lateinit var rvApps: RecyclerView
    private lateinit var btnOpenSettings: TextView
    private lateinit var contentRoot: View
    private lateinit var overlayWaiting: View
    private lateinit var groupWaitingText: View

    private lateinit var appAdapter: AppListAdapter
    private var cachedApps: List<AppInfo> = emptyList()

    /** 网络回调，仅在"等待联网"期间注册 */
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** 自动启动调度器：开机延时 2 秒 / 退出立即重进共用 */
    private val launchHandler = Handler(Looper.getMainLooper())
    private var pendingLaunch: Runnable? = null

    /** 标记"已自动启动过默认应用"，用于 onResume 识别从默认应用返回 */
    private var launchedDefaultApp = false

    /** 是否处于"等待网络连接"状态（联网回调据此判断是否继续启动流程） */
    private var waitingForNetwork = false

    /** 自定义启动图 WebView 已加载的标识（路径+文件修改时间，图片更新后立即生效） */
    private var webviewLoadedKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ivWallpaper = findViewById(R.id.iv_wallpaper)
        wvWallpaper = findViewById(R.id.wv_wallpaper)
        wvWallpaper.settings.javaScriptEnabled = false
        wvWallpaper.settings.allowFileAccess = true
        // 纯背景展示层：禁止聚焦，防止抢占遥控器 D-Pad 焦点
        wvWallpaper.isFocusable = false
        wvWallpaper.isFocusableInTouchMode = false
        rvApps = findViewById(R.id.rv_apps)
        btnOpenSettings = findViewById(R.id.btn_open_settings)
        contentRoot = findViewById(R.id.content_root)
        overlayWaiting = findViewById(R.id.overlay_waiting_network)
        groupWaitingText = findViewById(R.id.group_waiting_text)

        setupAppGrid()

        btnOpenSettings.setOnClickListener { openLauncherSettings() }
    }

    override fun onResume() {
        super.onResume()
        // 画面模式由网络/调度流程决定：等待画面（全屏启动图）或桌面画面

        val returnedFromDefault = launchedDefaultApp
        launchedDefaultApp = false

        if (isNetworkConnected()) {
            onNetworkReady(returnedFromDefault)
        } else {
            showWaitingForNetwork()
        }
    }

    override fun onPause() {
        super.onPause()
        // 离开桌面（去设置或去其它应用）时取消未执行的自动启动，避免误启动
        cancelScheduledLaunch()
        unregisterNetworkCallback()
    }

    // ------------------------------------------------------------------
    // 网络检查与监听
    // ------------------------------------------------------------------

    /** 当前是否有可用网络（Wi-Fi / 以太网 / 蜂窝均可，具备 INTERNET 能力即可） */
    private fun isNetworkConnected(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** 显示"等待网络连接"遮罩，并注册网络状态监听，联网成功自动继续 */
    private fun showWaitingForNetwork() {
        cancelScheduledLaunch()
        waitingForNetwork = true
        enterWaitingMode(waitingForNetwork = true)

        if (networkCallback != null) return // 已在监听中

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // 系统回调线程，切回主线程处理
                runOnUiThread {
                    if (waitingForNetwork) {
                        onNetworkReady(returnedFromDefault = false)
                    }
                }
            }
        }
        networkCallback = callback
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.registerNetworkCallback(request, callback)
        } catch (_: Exception) {
            networkCallback = null
        }
    }

    private fun hideWaitingForNetwork() {
        waitingForNetwork = false
        overlayWaiting.visibility = View.GONE
        unregisterNetworkCallback()
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { callback ->
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
            }
        }
        networkCallback = null
    }

    /**
     * 网络就绪后的统一入口（开机联网 / 等待中联网都会走到这里）。
     * 未配置默认应用则停在桌面；从默认应用退出返回 → 立即重进；开机联网 / 从配置页返回 → 延时 2 秒启动。
     * 等待期间全屏只显示启动图，【返回】键取消进入桌面、【菜单】键进设置。
     */
    private fun onNetworkReady(returnedFromDefault: Boolean) {
        hideWaitingForNetwork()

        val pkg = Prefs.getDefaultAppPackage(this)
        // 未配置默认应用或应用已卸载：停在桌面
        if (pkg == null ||
            (findAppInfo(pkg) == null && packageManager.getLaunchIntentForPackage(pkg) == null)
        ) {
            if (pkg != null) {
                Toast.makeText(this, R.string.toast_default_app_missing, Toast.LENGTH_SHORT).show()
            }
            exitWaitingMode()
            return
        }
        scheduleLaunch(if (returnedFromDefault) RESTART_DELAY_MS else BOOT_DELAY_MS)
    }

    // ------------------------------------------------------------------
    // 自动启动调度（Kiosk 保活）
    // ------------------------------------------------------------------

    /** 延迟调度一次默认应用启动；等待期间隐藏桌面内容，全屏只显示启动图 */
    private fun scheduleLaunch(delayMs: Long) {
        cancelScheduledLaunch()
        enterWaitingMode(waitingForNetwork = false)
        val runnable = Runnable {
            pendingLaunch = null
            launchDefaultApp()
        }
        pendingLaunch = runnable
        launchHandler.postDelayed(runnable, delayMs)
    }

    /** 取消待执行的自动启动（仅取消调度，画面切换由调用方负责） */
    private fun cancelScheduledLaunch() {
        pendingLaunch?.let { launchHandler.removeCallbacks(it) }
        pendingLaunch = null
    }

    /**
     * 启动默认应用，并打上"已自动启动"标记：
     * 下次该应用退出返回桌面时，据此识别并立即重新拉起。
     */
    private fun launchDefaultApp() {
        val pkg = Prefs.getDefaultAppPackage(this) ?: return
        val intent = findLaunchIntent(pkg)
        if (intent == null) {
            Toast.makeText(this, R.string.toast_default_app_missing, Toast.LENGTH_SHORT).show()
            return
        }
        launchedDefaultApp = true
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )
        try {
            startActivity(intent)
        } catch (_: Exception) {
            launchedDefaultApp = false
            Toast.makeText(this, R.string.toast_app_launch_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------
    // 应用网格
    // ------------------------------------------------------------------

    private fun setupAppGrid() {
        rvApps.layoutManager = GridLayoutManager(this, GRID_COLUMNS)
        appAdapter = AppListAdapter { info -> launchApp(info) }
        rvApps.adapter = appAdapter

        cachedApps = AppListLoader.load(this)
        appAdapter.submit(cachedApps)

        // 默认焦点落到第一个应用上，遥控器直接可用（等待画面期间不抢焦点）
        rvApps.post {
            if (contentRoot.visibility == View.VISIBLE) {
                rvApps.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
            }
        }
    }

    /** 用户点击网格中的某个应用（不触发 Kiosk 保活标记） */
    private fun launchApp(info: AppInfo) {
        cancelScheduledLaunch()
        val intent = AppListLoader.buildLaunchIntent(packageManager, info)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.toast_app_launch_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------
    // 启动等待画面与桌面画面
    // 需求：自定义图片仅用于【启动等待画面】（等待联网 + 等待自动启动期间全屏显示），
    // 整个启动过程不显示任何桌面图标；用户按【取消】键才显示桌面，桌面不使用该图片。
    // ------------------------------------------------------------------

    /** 自定义启动图是否就绪（应用私有目录文件存在且非空） */
    private fun hasCustomWallpaper(): Boolean {
        val file = File(filesDir, WallpaperLoader.PRIVATE_WALLPAPER_FILE)
        return file.isFile && file.length() > 0
    }

    /**
     * 进入等待画面：隐藏桌面内容，全屏显示启动图。
     * @param waitingForNetwork true=等待网络连接（未设置图片时显示"等待网络连接中"提示）
     */
    private fun enterWaitingMode(waitingForNetwork: Boolean) {
        contentRoot.visibility = View.GONE
        val file = File(filesDir, WallpaperLoader.PRIVATE_WALLPAPER_FILE)

        if (hasCustomWallpaper()) {
            // 有启动图：全屏 WebView 渲染（Chromium 解码引擎，与系统损坏的 BitmapFactory 无关），
            // 不显示任何等待文字（key 含文件 lastModified，图片更新后立即生效）
            overlayWaiting.visibility = View.GONE
            ivWallpaper.visibility = View.GONE
            wvWallpaper.visibility = View.VISIBLE
            val key = "${file.absolutePath}:${file.lastModified()}"
            if (webviewLoadedKey != key) {
                webviewLoadedKey = key
                val html = "<html><head><meta charset='utf-8'>" +
                    "<style>html,body{margin:0;padding:0;height:100%;background:#000;}" +
                    ".bg{position:fixed;left:0;top:0;width:100%;height:100%;" +
                    "background:url('file://${file.absolutePath}') no-repeat center center;" +
                    "background-size:cover;}</style></head>" +
                    "<body><div class='bg'></div></body></html>"
                wvWallpaper.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        } else {
            // 无启动图：内置默认背景 + 等待联网提示
            wvWallpaper.visibility = View.GONE
            wvWallpaper.loadUrl("about:blank")
            webviewLoadedKey = null
            ivWallpaper.visibility = View.VISIBLE
            ivWallpaper.setImageResource(R.drawable.bg_wallpaper_default)
            overlayWaiting.visibility = if (waitingForNetwork) View.VISIBLE else View.GONE
        }
    }

    /** 退出等待画面：显示桌面（内置默认背景），焦点还给应用网格 */
    private fun exitWaitingMode() {
        overlayWaiting.visibility = View.GONE
        hideWaitingForNetwork()
        webviewLoadedKey = null
        wvWallpaper.visibility = View.GONE
        wvWallpaper.loadUrl("about:blank")
        ivWallpaper.visibility = View.VISIBLE
        ivWallpaper.setImageResource(R.drawable.bg_wallpaper_default)
        if (contentRoot.visibility != View.VISIBLE) {
            contentRoot.visibility = View.VISIBLE
            // 焦点在等待期间已丢失（隐藏的视图不持有焦点），恢复桌面后把焦点还给应用网格
            rvApps.post {
                rvApps.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
            }
        }
    }

    override fun onDestroy() {
        pendingLaunch?.let { launchHandler.removeCallbacks(it) }
        pendingLaunch = null
        unregisterNetworkCallback()
        wvWallpaper.destroy()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    private fun findAppInfo(pkg: String): AppInfo? = cachedApps.firstOrNull { it.packageName == pkg }

    /** 查找启动指定应用的 Intent：优先缓存列表（兼容电视专属应用），回退系统方法 */
    private fun findLaunchIntent(pkg: String): Intent? {
        findAppInfo(pkg)?.let { return AppListLoader.buildLaunchIntent(packageManager, it) }
        return packageManager.getLaunchIntentForPackage(pkg)
    }

    /** 打开本桌面系统的配置页（默认启动应用、等待网络背景图） */
    private fun openLauncherSettings() {
        cancelScheduledLaunch()
        try {
            startActivity(Intent(this, SettingsActivity::class.java))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.toast_app_launch_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------
    // 遥控器按键处理
    // ------------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                // 【取消】键：取消待执行的自动启动，退出等待画面，显示桌面
                cancelScheduledLaunch()
                exitWaitingMode()
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                // 快捷键：【菜单】键随时进入桌面配置页（同时取消自动启动并退出等待画面）
                cancelScheduledLaunch()
                exitWaitingMode()
                openLauncherSettings()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
