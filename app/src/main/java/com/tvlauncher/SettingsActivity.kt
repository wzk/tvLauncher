package com.tvlauncher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ContentUris
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 设置界面。
 *
 * 功能：
 * 1. 设置/清除【默认开机启动应用】（弹窗列出全部可启动应用）；
 * 2. 设置/清除【等待网络背景图】：
 *    - 选择图片（系统文件选择器）或手动输入图片路径/URI；
 *    - 设置成功后图片会被【复制到应用私有目录】，桌面启动时直接从私有目录
 *      加载，彻底规避 /sdcard 直接路径在部分系统版本上无法访问的问题；
 *    - 提供【预览效果】全屏查看当前背景图。
 * 3. 打开 Android 系统原生设置的入口保留在系统侧（本页不再提供）。
 */
class SettingsActivity : Activity() {

    companion object {
        private const val REQUEST_READ_STORAGE = 1002
    }

    private lateinit var tvDefaultAppValue: TextView
    private lateinit var btnClearDefault: TextView
    private lateinit var tvBgValue: TextView
    private lateinit var btnClearBg: TextView

    /** 待复制的图片路径：等待存储权限授予后继续复制 */
    private var pendingCopyPath: String? = null

    /** 标记：权限授予后打开内置文件选择器 */
    private var pendingOpenPicker = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        tvDefaultAppValue = findViewById(R.id.tv_default_app_value)
        btnClearDefault = findViewById(R.id.btn_clear_default)
        tvBgValue = findViewById(R.id.tv_bg_value)
        btnClearBg = findViewById(R.id.btn_clear_bg)

        // 底部提示 + 版本号（便于确认设备上实际运行的版本）
        val versionText = try {
            val info = packageManager.getPackageInfo(packageName, 0)
            "v${info.versionName} (${info.versionCode})"
        } catch (_: Exception) {
            "unknown"
        }
        findViewById<TextView>(R.id.tv_footer_hint).text =
            getString(R.string.settings_footer_hint) + "\n当前版本：" + versionText

        findViewById<View>(R.id.row_default_app).setOnClickListener { showAppPickerDialog() }
        btnClearDefault.setOnClickListener {
            Prefs.setDefaultAppPackage(this, null)
            refreshUi()
        }
        findViewById<View>(R.id.btn_pick_image).setOnClickListener { pickImage() }
        findViewById<View>(R.id.btn_input_path).setOnClickListener { showPathInputDialog() }
        findViewById<View>(R.id.btn_preview).setOnClickListener { showPreview() }
        btnClearBg.setOnClickListener { clearCustomBackground() }
    }

    override fun onResume() {
        super.onResume()
        refreshUi() // 从文件选择器返回时刷新
    }

    // ------------------------------------------------------------------
    // 界面刷新
    // ------------------------------------------------------------------

    private fun refreshUi() {
        // 默认启动应用
        val pkg = Prefs.getDefaultAppPackage(this)
        tvDefaultAppValue.text = pkg?.let { appLabel(it) }
            ?: getString(R.string.settings_default_app_none)
        btnClearDefault.visibility = if (pkg == null) View.GONE else View.VISIBLE

        // 自定义背景图
        val bg = Prefs.getCustomBackground(this)
        tvBgValue.text = bg?.takeIf { it.isNotBlank() }
            ?: getString(R.string.settings_bg_none)
        btnClearBg.visibility = if (bg.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    /** 通过 PackageManager 查询应用显示名称 */
    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
    } catch (_: Exception) {
        pkg
    }

    // ------------------------------------------------------------------
    // 默认启动应用选择
    // ------------------------------------------------------------------

    /** 弹出应用选择对话框，点击即设为默认启动应用 */
    private fun showAppPickerDialog() {
        val apps = AppListLoader.load(this)
        if (apps.isEmpty()) {
            Toast.makeText(this, R.string.picker_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val view = layoutInflater.inflate(R.layout.view_app_picker, null)
        val recyclerView = view.findViewById<RecyclerView>(R.id.rv_picker)
        recyclerView.layoutManager = GridLayoutManager(this, 4)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.picker_title)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        recyclerView.adapter = AppListAdapter(apps) { info ->
            Prefs.setDefaultAppPackage(this, info.packageName)
            dialog.dismiss()
            refreshUi()
            Toast.makeText(this, getString(R.string.toast_default_app_set, info.label), Toast.LENGTH_SHORT).show()
        }
        dialog.show()
    }

    // ------------------------------------------------------------------
    // 背景图：选择 / 输入路径（统一复制到私有目录）
    // ------------------------------------------------------------------

    /**
     * 选择图片：使用内置文件选择器浏览 /sdcard（TV 盒子普遍没有系统 DocumentsUI）。
     * 无存储权限时先申请，授权后再打开。
     */
    private fun pickImage() {
        if (hasReadPermission("/sdcard/")) {
            openFilePicker()
        } else {
            pendingOpenPicker = true
            requestReadPermission("/sdcard/")
        }
    }

    /** 打开内置文件选择器，选中图片后复制到私有目录并启用 */
    private fun openFilePicker() {
        try {
            FilePickerDialog(this) { file -> copyFromPath(file.absolutePath) }.show()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.toast_bg_copy_failed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_READ_STORAGE) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            val copyPath = pendingCopyPath
            pendingCopyPath = null
            when {
                copyPath != null ->
                    if (granted) copyFromPath(copyPath)
                    else Toast.makeText(this, R.string.toast_bg_copy_failed, Toast.LENGTH_LONG).show()

                pendingOpenPicker ->
                    if (granted) {
                        pendingOpenPicker = false
                        openFilePicker()
                    } else {
                        pendingOpenPicker = false
                        Toast.makeText(this, R.string.toast_permission_denied, Toast.LENGTH_LONG).show()
                    }
            }
        }
    }

    /** 手动输入图片路径或 content:// URI，确认后复制到私有目录 */
    private fun showPathInputDialog() {
        val density = resources.displayMetrics.density
        val editText = EditText(this).apply {
            hint = getString(R.string.bg_path_hint)
            setText(Prefs.getCustomBackground(this@SettingsActivity) ?: "")
        }
        val container = FrameLayout(this).apply {
            val hPad = (24 * density).toInt()
            val vPad = (12 * density).toInt()
            setPadding(hPad, vPad, hPad, 0)
            addView(
                editText,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_bg_input)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                handleInputPath(editText.text.toString().trim())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 处理用户输入：空=清除；http(s) URL=下载；本地路径/URI=复制到私有目录后启用 */
    private fun handleInputPath(path: String) {
        if (path.isEmpty()) {
            clearCustomBackground()
            return
        }
        if (path.startsWith("http://") || path.startsWith("https://")) {
            // 通过网络下载到私有目录，完全绕过设备存储层（FUSE）
            Toast.makeText(this, R.string.toast_bg_downloading, Toast.LENGTH_SHORT).show()
            Thread {
                val ok = downloadToPrivate(path)
                runOnUiThread { reportCopyResult(ok) }
            }.start()
            return
        }
        if (!path.startsWith("/") && !path.startsWith("content:")) {
            Toast.makeText(this, R.string.toast_bg_copy_failed, Toast.LENGTH_LONG).show()
            return
        }
        if (hasReadPermission(path)) {
            copyFromPath(path)
        } else {
            // 无权限：先申请，授权后继续复制
            pendingCopyPath = path
            requestReadPermission(path)
        }
    }

    /**
     * 从 HTTP(S) 地址下载图片到私有目录并验证启用。
     * 网络传输不经过设备存储层，可绕过 FUSE 截断问题；须在子线程调用。
     */
    private fun downloadToPrivate(urlStr: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code !in 200..299) {
                false
            } else {
                val bytes = conn.inputStream.use { it.readBytes() }
                copyFromBytes(bytes)
            }
        } catch (_: Throwable) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 复制本地图片到私有目录并启用。按三级策略读取源文件：
     * 1. 直接文件读取（有存储权限且 FUSE 放行时成功）；
     * 2. 查询 MediaStore 已索引记录，经 content URI 读取（绕过直接路径限制）；
     * 3. 触发系统媒体扫描后经扫描返回的 URI 读取（adb push 的文件常未被索引）。
     */
    private fun copyFromPath(path: String) {
        // 1) 直接文件读取（每次重新打开源文件）
        val srcFile = File(path)
        val direct = try {
            copyFromOpener({ FileInputStream(srcFile) }, srcFile.length())
        } catch (_: Throwable) {
            false
        }
        if (direct) {
            reportCopyResult(true)
            return
        }

        // 2) MediaStore 已索引记录
        val storeUri = queryMediaStoreUri(path)
        if (storeUri != null) {
            val viaStore = try {
                copyFromOpener({ contentResolver.openInputStream(storeUri) })
            } catch (_: Throwable) {
                false
            }
            if (viaStore) {
                reportCopyResult(true)
                return
            }
        }

        // 3) 媒体扫描后重试
        MediaScannerConnection.scanFile(this, arrayOf(path), arrayOf("image/*")) { _, uri ->
            val scanned = if (uri != null) {
                try {
                    copyFromOpener({ contentResolver.openInputStream(uri) })
                } catch (_: Throwable) {
                    false
                }
            } else {
                false
            }
            runOnUiThread { reportCopyResult(scanned) }
        }
    }

    /** 按 DATA 路径查询 MediaStore 中的图片记录，返回其 content URI */
    private fun queryMediaStoreUri(path: String): Uri? {
        return try {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.DATA}=?",
                arrayOf(path),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)
                    )
                } else {
                    null
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 从内存字节数组原样保存（HTTP 下载场景）。
     */
    private fun copyFromBytes(bytes: ByteArray): Boolean = saveBytes(bytes, -1)

    /**
     * 使用流工厂原样读取并保存。
     * 注意：不做任何图片解码——本设备 BitmapFactory 解码器损坏（能编码不能解码），
     * 显示环节由 WebView（Chromium 自带解码引擎）渲染，保存环节只负责搬运字节。
     *
     * @param expectedSize 源文件大小（已知时校验读取是否被截断），-1 表示未知
     */
    private fun copyFromOpener(opener: () -> InputStream?, expectedSize: Long = -1): Boolean {
        return try {
            val bytes = opener()?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty()) return false
            if (expectedSize >= 0 && bytes.size != expectedSize.toInt()) return false
            saveBytes(bytes, expectedSize)
        } catch (_: Throwable) {
            false
        }
    }

    /** 把字节原样写入私有目录并启用配置（仅校验文件非空，不解码） */
    private fun saveBytes(bytes: ByteArray, expectedSize: Long): Boolean {
        return try {
            openFileOutput(WallpaperLoader.PRIVATE_WALLPAPER_FILE, MODE_PRIVATE).use { output ->
                output.write(bytes)
            }
            val target = File(filesDir, WallpaperLoader.PRIVATE_WALLPAPER_FILE)
            if (expectedSize >= 0 && target.length() != expectedSize) {
                target.delete()
                return false
            }
            Prefs.setCustomBackground(this, target.absolutePath)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun reportCopyResult(ok: Boolean) {
        Toast.makeText(
            this,
            getString(if (ok) R.string.toast_bg_copied else R.string.toast_bg_copy_failed),
            Toast.LENGTH_SHORT
        ).show()
        refreshUi()
    }

    /** 清除自定义背景：同时删除私有目录中的副本 */
    private fun clearCustomBackground() {
        Prefs.setCustomBackground(this, null)
        File(filesDir, WallpaperLoader.PRIVATE_WALLPAPER_FILE).delete()
        refreshUi()
    }

    // ------------------------------------------------------------------
    // 背景图预览
    // ------------------------------------------------------------------

    /** 全屏预览当前背景图的实际显示效果，点击任意位置关闭。
     *  使用 WebView 渲染（与桌面一致的方案），不依赖本机 BitmapFactory 解码器。 */
    private fun showPreview() {
        val spec = Prefs.getCustomBackground(this)
        if (spec.isNullOrBlank()) {
            Toast.makeText(this, R.string.toast_bg_preview_none, Toast.LENGTH_SHORT).show()
            return
        }
        val custom = File(filesDir, WallpaperLoader.PRIVATE_WALLPAPER_FILE)
        if (!custom.isFile || custom.length() == 0L) {
            Toast.makeText(this, R.string.toast_bg_preview_none, Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = Dialog(this, android.R.style.Theme_Material_NoActionBar_Fullscreen)
        val webView = WebView(this).apply {
            settings.javaScriptEnabled = false
            settings.allowFileAccess = true
            val html = "<html><head><meta charset='utf-8'>" +
                "<style>html,body{margin:0;padding:0;height:100%;background:#000;}" +
                ".bg{position:fixed;left:0;top:0;width:100%;height:100%;" +
                "background:url('file://${custom.absolutePath}') no-repeat center center;" +
                "background-size:cover;}</style></head>" +
                "<body><div class='bg'></div></body></html>"
            loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            setOnClickListener { dialog.dismiss() }
        }
        dialog.setContentView(webView)
        dialog.setOnDismissListener { webView.destroy() }
        dialog.show()
    }

    // ------------------------------------------------------------------
    // 存储权限
    // ------------------------------------------------------------------

    /** 本地路径是否有对应的图片读取权限 */
    private fun hasReadPermission(spec: String): Boolean {
        if (!spec.startsWith("/")) return true // content:// 无需存储权限
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) ==
                    PackageManager.PERMISSION_GRANTED
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
            else -> true // Android 5.x 安装时即授权
        }
    }

    /** 按系统版本申请读取权限（API 23+） */
    private fun requestReadPermission(path: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            copyFromPath(path) // 5.x 无需动态权限，直接尝试
            return
        }
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        requestPermissions(arrayOf(permission), REQUEST_READ_STORAGE)
    }
}
