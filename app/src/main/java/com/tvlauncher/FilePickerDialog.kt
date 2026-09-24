package com.tvlauncher

import android.app.Activity
import android.app.AlertDialog
import android.os.Environment
import android.widget.ArrayAdapter
import android.widget.ListView
import java.io.File

/**
 * 内置图片文件选择器（TV 盒子普遍没有系统 DocumentsUI，因此自行实现）。
 *
 * - 从 /sdcard（/storage/emulated/0）开始浏览；
 * - 仅显示目录与常见图片文件，目录在前、文件在后，按名称排序；
 * - 点击目录进入，点击"返回上级目录"逐级回退；
 * - 点击图片文件后回调 onPicked，由调用方负责复制与启用。
 */
class FilePickerDialog(
    private val activity: Activity,
    private val onPicked: (File) -> Unit
) {

    private var currentDir: File = Environment.getExternalStorageDirectory()
    private var dialog: AlertDialog? = null

    /** 列表条目：显示文本 + 关联文件（null 表示"返回上级/占位"等特殊条目） */
    private var entries: List<Pair<String, File?>> = emptyList()

    fun show() {
        val listView = ListView(activity)
        listView.adapter = ArrayAdapter(
            activity, android.R.layout.simple_list_item_1, mutableListOf<String>()
        )
        listView.setOnItemClickListener { _, _, position, _ ->
            entries.getOrNull(position)?.second?.let { clicked ->
                if (clicked.isDirectory) {
                    currentDir = clicked
                    refresh(listView)
                } else if (clicked.isFile && clicked.isImageFile()) {
                    dialog?.dismiss()
                    onPicked(clicked)
                }
                // 特殊条目（空目录占位等）点击无操作
            }
        }

        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.file_picker_title)
            .setView(listView)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        refresh(listView)
        dialog?.show()
    }

    /** 按当前目录刷新列表 */
    private fun refresh(listView: ListView) {
        val items = mutableListOf<Pair<String, File?>>()

        // 上级目录入口（根目录 /storage/emulated/0 之上不再提供）
        if (currentDir.absolutePath != Environment.getExternalStorageDirectory().absolutePath) {
            items.add(getString(R.string.file_picker_parent) to currentDir.parentFile)
        }

        val children = try {
            currentDir.listFiles()?.toList()
        } catch (_: Exception) {
            null
        }
        if (children == null) {
            items.add(getString(R.string.file_picker_no_access) to null)
        } else {
            val dirs = children.filter { it.isDirectory }
            val images = children.filter { it.isFile && it.isImageFile() }
            val sorted = (dirs + images).sortedWith(
                compareBy({ !it.isDirectory }, { it.name.lowercase() })
            )
            if (sorted.isEmpty()) {
                items.add(getString(R.string.file_picker_empty) to null)
            } else {
                for (file in sorted) {
                    val name = if (file.isDirectory) "${file.name}/" else file.name
                    items.add(name to file)
                }
            }
        }
        entries = items

        @Suppress("UNCHECKED_CAST")
        val adapter = listView.adapter as ArrayAdapter<String>
        adapter.clear()
        for ((text, _) in items) adapter.add(text)
        adapter.notifyDataSetChanged()

        dialog?.setTitle(
            getString(R.string.file_picker_title) + "：" + currentDir.absolutePath
        )
    }

    private fun getString(resId: Int): String = activity.getString(resId)

    private fun File.isImageFile(): Boolean {
        val ext = extension.lowercase()
        return ext in IMAGE_EXTENSIONS
    }

    companion object {
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
    }
}
