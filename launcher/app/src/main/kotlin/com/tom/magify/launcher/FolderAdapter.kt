package com.tom.magify.launcher

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tom.magify.launcher.databinding.ItemFolderBinding
import kotlin.math.ceil

private const val SPAN_COUNT = 4
private const val ROW_HEIGHT_DP = 88
private const val POOL_CAPACITY = 40

/** The "all apps" view: every category stacked and scrollable at once, each
 *  with its own inline app grid — the original layout, also reused to show
 *  search results as a single pseudo-folder. */
class FolderAdapter(
    private val onAppClick: (LauncherApp) -> Unit,
    private val onAppLongClick: (LauncherApp, android.view.View) -> Unit = { _, _ -> }
) : RecyclerView.Adapter<FolderAdapter.ViewHolder>() {

    private var folders: List<LauncherFolder> = emptyList()

    // Shared across every folder's inner grid so scrolling the outer list
    // recycles item views between folders instead of re-inflating each time.
    private val sharedAppViewPool = RecyclerView.RecycledViewPool().apply {
        setMaxRecycledViews(0, POOL_CAPACITY)
    }

    init {
        setHasStableIds(true)
    }

    fun updateFolders(newFolders: List<LauncherFolder>) {
        val diff = DiffUtil.calculateDiff(FolderDiffCallback(folders, newFolders))
        folders = newFolders
        diff.dispatchUpdatesTo(this)
    }

    private class FolderDiffCallback(
        private val old: List<LauncherFolder>,
        private val new: List<LauncherFolder>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size
        override fun areItemsTheSame(oldPos: Int, newPos: Int) =
            old[oldPos].name == new[newPos].name
        override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
            val oldApps = old[oldPos].apps
            val newApps = new[newPos].apps
            if (oldApps.size != newApps.size) return false
            return oldApps.map { it.iconCacheKey } == newApps.map { it.iconCacheKey }
        }
    }

    inner class ViewHolder(val binding: ItemFolderBinding) : RecyclerView.ViewHolder(binding.root) {
        val gridAdapter = AppGridAdapter(onAppClick, onAppLongClick)
        val rowHeightPx = (ROW_HEIGHT_DP * binding.root.resources.displayMetrics.density).toInt()

        init {
            binding.appGrid.apply {
                layoutManager = GridLayoutManager(binding.root.context, SPAN_COUNT)
                setRecycledViewPool(sharedAppViewPool)
                itemAnimator = null
                adapter = gridAdapter
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val folder = folders[position]
        holder.binding.folderTitle.text = folder.name

        // Fixed height computed from row count instead of wrap_content: lets
        // the nested RecyclerView skip its own full-measure pass every time
        // this row is recycled for a different folder while scrolling.
        // setLayoutParams() unconditionally calls requestLayout(), even when
        // the height it's given is identical to the current one, which was
        // forcing a full traversal of the outer list on every single bind
        // during a fling. Only touch it when the height actually differs.
        val rows = ceil(folder.apps.size / SPAN_COUNT.toDouble()).toInt().coerceAtLeast(1)
        val targetHeight = rows * holder.rowHeightPx
        val lp = holder.binding.appGrid.layoutParams
        if (lp.height != targetHeight) {
            lp.height = targetHeight
            holder.binding.appGrid.layoutParams = lp
        }

        holder.gridAdapter.updateApps(folder.apps)
    }

    override fun getItemCount() = folders.size

    override fun getItemId(position: Int) = folders[position].name.hashCode().toLong()
}
