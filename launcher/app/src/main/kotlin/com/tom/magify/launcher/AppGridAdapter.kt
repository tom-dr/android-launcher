package com.tom.magify.launcher

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tom.magify.launcher.databinding.ItemAppBinding

private const val DEFAULT_ICON_SIZE_DP = 48

class AppGridAdapter(
    private val onClick: (LauncherApp) -> Unit,
    private val onLongClick: (LauncherApp, android.view.View) -> Unit = { _, _ -> }
) : RecyclerView.Adapter<AppGridAdapter.ViewHolder>() {

    private var apps: List<LauncherApp> = emptyList()

    /** Icon size in dp; above the cached 48 dp, icons are loaded at full quality. */
    var iconSizeDp: Int = 48
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    init {
        setHasStableIds(true)
    }

    /** Rebinds this already-inflated adapter to a different folder's apps.
     *  Called on every scroll-triggered recycle, almost always swapping to a
     *  completely different folder's list (nothing to diff), so a plain
     *  notifyDataSetChanged is cheaper here than paying DiffUtil's O(N+M)
     *  comparison for an answer that's "everything changed" most of the time. */
    fun updateApps(newApps: List<LauncherApp>) {
        apps = newApps
        notifyDataSetChanged()
    }

    inner class ViewHolder(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        val context = holder.binding.root.context
        val icon = if (iconSizeDp > DEFAULT_ICON_SIZE_DP) {
            // The cache holds 48 dp bitmaps, blurry once enlarged; the few
            // big icons (car grid) are loaded straight from the app instead.
            app.resolveInfo.loadIcon(context.packageManager)
        } else {
            // Icons were already decoded and resized by AppRepository in the
            // background, so this is a cheap LruCache hit, not a fresh decode.
            IconCache.get(context, app.iconCacheKey) { app.resolveInfo.loadIcon(context.packageManager) }
        }
        holder.binding.appIcon.setImageDrawable(icon)
        val sizePx = (iconSizeDp * context.resources.displayMetrics.density).toInt()
        holder.binding.appIcon.layoutParams = holder.binding.appIcon.layoutParams.apply {
            width = sizePx
            height = sizePx
        }
        holder.binding.appLabel.textSize = if (iconSizeDp > DEFAULT_ICON_SIZE_DP) 14f else 11f
        holder.binding.appLabel.text = app.label
        holder.binding.root.setOnClickListener { onClick(app) }
        holder.binding.root.setOnLongClickListener { view ->
            onLongClick(app, view)
            true
        }
    }

    override fun getItemCount() = apps.size

    override fun getItemId(position: Int) = apps[position].iconCacheKey.hashCode().toLong()
}
