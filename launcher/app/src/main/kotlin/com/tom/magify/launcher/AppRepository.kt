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

    // Réseaux sociaux and Multimédia are used most often day to day, so they
    // lead; Jeux is pushed near the end; Autres always stays last.
    private val folderOrder = listOf(
        "Réseaux sociaux",
        "Navigation",
        "Multimédia",
        "Outils & Productivité",
        "Finance & Banque",
        "Shopping",
        "Santé & Bien-être",
        "Administratif",
        "Jeux",
        "Autres"
    )

    // Checked before everything else (declared category included): browsers
    // are a well-known, enumerable set, so this is worth pulling out ahead
    // of any broader "social"/"communication" declared-category match that
    // would otherwise catch them alongside the actual social apps.
    private val navigationKeywords = listOf(
        "browser", "navigateur", "chrome", "firefox", "torproject",
        "duckduckgo", "brave", "opera", "vivaldi", "samsung internet",
        "ucbrowser", "puffin", "dolphin", "qwant", "ecosia", "startpage"
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
            "impots", "gouv", "ameli", "caf", "admin", "identit", "amende",
            "autoentrepreneur", "entrepreneur", "cpf", "urssaf", "ants"
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
            // loadLabel() does a resource lookup, not a field read — worth
            // computing once and reusing, rather than paying for it twice
            // per app (toLauncherApp + resolveFolder) on every rebuild.
            val label = resolveInfo.loadLabel(pm).toString()
            val app = toLauncherApp(resolveInfo, label)
            // Decode + resize + cache the icon here, off the main thread, so
            // RecyclerView binds later are pure cache hits.
            IconCache.get(context, app.iconCacheKey) { resolveInfo.loadIcon(pm) }
            val folderName = resolveFolder(resolveInfo, label)
            grouped.getOrPut(folderName) { mutableListOf() }.add(app)
        }

        // Most-used-first within each folder; apps with no (or equal, e.g.
        // zero) usage fall back to alphabetical among themselves.
        val usage = UsageScores.get(context)
        val comparator = compareByDescending<LauncherApp> { usage[it.packageName] ?: 0L }
            .thenBy { it.label.lowercase() }

        folderOrder.mapNotNull { name ->
            val apps = grouped[name].orEmpty().sortedWith(comparator)
            if (apps.isEmpty()) null else LauncherFolder(name, apps)
        }
    }

    private fun queryLaunchableApps(): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        // No match-type flags at all — *not* MATCH_ALL, which also pulls in
        // components the user has disabled (MATCH_DISABLED_COMPONENTS is
        // part of it), defeating "Désactiver". And *not* MATCH_DEFAULT_ONLY
        // either: confirmed on-device that it silently drops launcher
        // activities whose manifest declares only CATEGORY_LAUNCHER without
        // also declaring CATEGORY_DEFAULT, which most launcher icons do —
        // more than half the app drawer vanished under that flag in testing.
        // Passing 0 is PackageManager's normal, unmodified query: it already
        // excludes disabled components on its own, with neither drawback.
        return pm.queryIntentActivities(intent, 0)
            .distinctBy { it.activityInfo.packageName }
    }

    private fun toLauncherApp(info: ResolveInfo, label: String): LauncherApp = LauncherApp(
        label = label,
        packageName = info.activityInfo.packageName,
        activityName = info.activityInfo.name,
        resolveInfo = info
    )

    private fun resolveFolder(info: ResolveInfo, label: String): String {
        val pkg = info.activityInfo.packageName.lowercase()
        val lowerLabel = label.lowercase()

        if (navigationKeywords.any { pkg.contains(it) || lowerLabel.contains(it) }) {
            return "Navigation"
        }

        mapDeclaredCategory(info.activityInfo.applicationInfo.category)?.let { return it }

        for (name in folderOrder) {
            val keywords = keywordMap[name] ?: continue
            if (keywords.any { pkg.contains(it) || lowerLabel.contains(it) }) {
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
