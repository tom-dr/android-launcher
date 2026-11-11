package com.tom.magify.launcher

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tom.magify.launcher.databinding.ItemFolderCardBinding

// One accent per category so the cards read as distinct groups at a glance,
// even though they all share the same generic folder glyph.
private val FOLDER_COLORS = mapOf(
    "Réseaux sociaux" to "#4FC3F7",
    "Navigation" to "#7986CB",
    "Multimédia" to "#BA68C8",
    "Outils & Productivité" to "#81C784",
    "Finance & Banque" to "#FFD54F",
    "Shopping" to "#F06292",
    "Santé & Bien-être" to "#4DB6AC",
    "Administratif" to "#A1887F",
    "Jeux" to "#FF8A65",
    "Autres" to "#90A4AE"
)
private const val DEFAULT_FOLDER_COLOR = "#90A4AE"

/** The top-level drawer grid: one card per category, not per app — tapping a
 *  card opens that folder's own app grid full-screen (see MainActivity). */
class FolderCardAdapter(
    private val onFolderClick: (LauncherFolder) -> Unit
) : RecyclerView.Adapter<FolderCardAdapter.ViewHolder>() {

    private var folders: List<LauncherFolder> = emptyList()

    init {
        setHasStableIds(true)
    }

    fun updateFolders(newFolders: List<LauncherFolder>) {
        folders = newFolders
        notifyDataSetChanged()
    }

    inner class ViewHolder(val binding: ItemFolderCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFolderCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val folder = folders[position]
        holder.binding.folderCardLabel.text = folder.name
        holder.binding.folderCardIcon.setColorFilter(
            Color.parseColor(FOLDER_COLORS[folder.name] ?: DEFAULT_FOLDER_COLOR)
        )
        holder.binding.root.setOnClickListener { onFolderClick(folder) }
    }

    override fun getItemCount() = folders.size

    override fun getItemId(position: Int) = folders[position].name.hashCode().toLong()
}
