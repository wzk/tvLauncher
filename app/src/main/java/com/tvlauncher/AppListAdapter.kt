package com.tvlauncher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 应用网格列表适配器（桌面 App Drawer 与设置中的应用选择弹窗共用）。
 *
 * 遥控器（D-Pad）焦点处理：
 * - 条目 focusable + clickable（见 item_app.xml），方向键可逐格移动焦点；
 * - 通过 OnFocusChangeListener 在聚焦时放大 1.08 倍并显示高亮边框
 *   （边框由 bg_focusable_item 选择器绘制），失焦还原。
 *
 * @param apps    当前展示的应用列表
 * @param onClick 条目被遥控器"确认键"点击时的回调
 */
class AppListAdapter(
    private var apps: List<AppInfo> = emptyList(),
    private val onClick: (AppInfo) -> Unit
) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

    /** 全量刷新列表 */
    fun submit(newApps: List<AppInfo>) {
        apps = newApps
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.iv_icon)
        val label: TextView = view.findViewById(R.id.tv_label)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false)
        val holder = ViewHolder(view)

        // 焦点高亮：聚焦时轻微放大，视觉反馈明确且不刺眼
        view.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) 1.08f else 1.0f
            v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
        }
        return holder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        holder.icon.setImageDrawable(app.icon)
        holder.label.text = app.label
        holder.itemView.setOnClickListener { onClick(app) }
    }

    override fun getItemCount(): Int = apps.size
}
