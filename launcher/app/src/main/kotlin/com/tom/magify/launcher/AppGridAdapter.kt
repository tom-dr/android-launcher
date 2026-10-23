package com.tom.magify.launcher

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tom.magify.launcher.databinding.ItemAppBinding

class AppGridAdapter(
    private val onClick: (LauncherApp) -> Unit
) : RecyclerView.Adapter<AppGridAdapter.ViewHolder>() {

    private var apps: List<LauncherApp> = emptyList()

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
        // Icons were already decoded and resized by AppRepository in the
        // background, so this is a cheap LruCache hit, not a fresh decode.
        val icon = IconCache.get(context, app.iconCacheKey) { app.resolveInfo.loadIcon(context.packageManager) }
        holder.binding.appIcon.setImageDrawable(icon)
        holder.binding.appLabel.text = app.label
        holder.binding.root.setOnClickListener { onClick(app) }
    }

    override fun getItemCount() = apps.size

    override fun getItemId(position: Int) = apps[position].iconCacheKey.hashCode().toLong()
}
