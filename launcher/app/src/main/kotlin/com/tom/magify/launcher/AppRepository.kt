package com.tom.magify.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Enumerates launchable apps and sorts them into a fixed set of French-named
 * folders: Android's declared ApplicationInfo.category first, falling back to
 * a package-name / label keyword heuristic, falling back to "Autres".
 *
 * All enumeration and icon decoding runs on a background dispatcher; only the
 * finished folder list crosses back to the main thread.
 */
class AppRepository(private val context: Context) {

    private val pm: PackageManager = context.packageManager

    private val folderOrder = listOf(
        "Jeux",
        "Réseaux sociaux",
        "Finance & Banque",
        "Outils & Productivité",
        "Shopping",
        "Santé & Bien-être",
        "Multimédia",
        "Administratif",
        "Autres"
    )

    private val keywordMap: Map<String, List<String>> = mapOf(
        "Jeux" to listOf(
            "game", "jeu", "play", "casino", "puzzle", "arena", "clash", "match3",
            "com.king.", "com.supercell.", "com.ea.", "com.gameloft."
        ),
        "Réseaux sociaux" to listOf(
            "social", "chat", "messenger", "dating", "whatsapp", "instagram",
            "facebook", "snapchat", "twitter", "musically", "telegram", "discord"
        ),
        "Finance & Banque" to listOf(
            "bank", "banque", "pay", "wallet", "credit", "invest", "bourse",
            "crypto", "paypal", "lydia", "revolut"
        ),
        "Outils & Productivité" to listOf(
            "office", "docs", "note", "calendar", "agenda", "pdf", "scanner",
            "drive", "cloud", "translate", "authenticator"
        ),
        "Shopping" to listOf(
            "shop", "market", "store", "amazon", "ebay", "vinted", "leboncoin",
            "aliexpress", "lidl", "decathlon", "leroymerlin", "kiabi"
        ),
        "Santé & Bien-être" to listOf(
            "health", "sante", "fit", "sport", "medical", "pharma", "sleep",
            "meditation", "doctolib", "yuka", "foodvisor"
        ),
        "Multimédia" to listOf(
            "music", "video", "photo", "camera", "stream", "netflix", "spotify",
            "youtube", "gallery", "tiktok"
        ),
        "Administratif" to listOf(
            "impots", "gouv", "ameli", "caf", "admin", "identit", "amendes"
        )
    )

    suspend fun buildFolders(): List<LauncherFolder> = withContext(Dispatchers.Default) {
        val grouped = linkedMapOf<String, MutableList<LauncherApp>>()
        folderOrder.forEach { grouped[it] = mutableListOf() }

        val resolveInfos = queryLaunchableApps()
        // Size the cache to fit every icon before warming any of them, so
        // none get evicted by the time the user scrolls back to an earlier
        // folder (see IconCache.ensureCapacity).
        IconCache.ensureCapacity(context, resolveInfos.size)

        for (resolveInfo in resolveInfos) {
            val app = toLauncherApp(resolveInfo)
            // Decode + resize + cache the icon here, off the main thread, so
            // RecyclerView binds later are pure cache hits.
            IconCache.get(context, app.iconCacheKey) { resolveInfo.loadIcon(pm) }
            val folderName = resolveFolder(resolveInfo)
            grouped.getOrPut(folderName) { mutableListOf() }.add(app)
        }

        folderOrder.mapNotNull { name ->
            val apps = grouped[name].orEmpty().sortedBy { it.label.lowercase() }
            if (apps.isEmpty()) null else LauncherFolder(name, apps)
        }
    }

    private fun queryLaunchableApps(): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .distinctBy { it.activityInfo.packageName }
    }

    private fun toLauncherApp(info: ResolveInfo): LauncherApp = LauncherApp(
        label = info.loadLabel(pm).toString(),
        packageName = info.activityInfo.packageName,
        activityName = info.activityInfo.name,
        resolveInfo = info
    )

    private fun resolveFolder(info: ResolveInfo): String {
        mapDeclaredCategory(info.activityInfo.applicationInfo.category)?.let { return it }

        val pkg = info.activityInfo.packageName.lowercase()
        val label = info.loadLabel(pm).toString().lowercase()

        for (name in folderOrder) {
            val keywords = keywordMap[name] ?: continue
            if (keywords.any { pkg.contains(it) || label.contains(it) }) {
                return name
            }
        }
        return "Autres"
    }

    private fun mapDeclaredCategory(category: Int): String? = when (category) {
        ApplicationInfo.CATEGORY_GAME -> "Jeux"
        ApplicationInfo.CATEGORY_SOCIAL -> "Réseaux sociaux"
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Outils & Productivité"
        ApplicationInfo.CATEGORY_AUDIO,
        ApplicationInfo.CATEGORY_VIDEO,
        ApplicationInfo.CATEGORY_IMAGE -> "Multimédia"
        ApplicationInfo.CATEGORY_MAPS -> "Outils & Productivité"
        else -> null
    }
}
