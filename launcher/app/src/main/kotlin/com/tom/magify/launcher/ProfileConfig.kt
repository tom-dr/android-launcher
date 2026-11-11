package com.tom.magify.launcher

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class ProfileDefinition(
    val id: String,
    val label: String,
    val appsTitle: String,
    val keywords: List<String>,
    val excludeKeywords: List<String>,
    val pinnedKeywords: List<String>,
    val blockedPackages: Set<String>,
    val categories: Set<String>,
    val hiddenKeywords: List<String>,
    // Home-screen widgets shown under the profile's apps, e.g. "ai_quotas".
    val widgets: Set<String>,
    // Other names the search recognizes for this profile ("pro" for work).
    val aliases: List<String>,
    // Badge for profiles tied to a moment or activity ("night", "church"…);
    // null keeps the account avatar.
    val icon: String?,
    // Bluetooth device names that switch to this profile while connected
    // (the car's "Media Display"), see BluetoothProfiles.
    val bluetoothDevices: List<String>,
    // Columns of the home app grid; fewer means bigger icons (car: 3).
    val columns: Int
)

const val PROFILE_PREFS_NAME = "profile_preferences"

private data class ProfileRule(
    val profileId: String,
    val daysOfWeek: Set<Int>,
    val startMinutes: Int,
    val endMinutes: Int,
    val dates: Set<String>
) {
    fun matches(now: Calendar): Boolean {
        val date = SimpleDateFormat("MM-dd", Locale.ROOT).format(now.time)
        if (dates.isNotEmpty() && date !in dates) return false
        if (daysOfWeek.isNotEmpty() && now.get(Calendar.DAY_OF_WEEK) !in daysOfWeek) return false

        val current = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return if (startMinutes <= endMinutes) {
            current >= startMinutes && current < endMinutes
        } else {
            // Also allows a configurable interval crossing midnight.
            current >= startMinutes || current < endMinutes
        }
    }
}

class ProfileConfig private constructor(
    private val defaultProfileId: String,
    private val profiles: List<ProfileDefinition>,
    private val rules: List<ProfileRule>
) {
    fun allProfiles(): List<ProfileDefinition> = profiles

    fun profileById(id: String): ProfileDefinition? = profiles.firstOrNull { it.id == id }

    fun profileForBluetoothDevice(deviceName: String): ProfileDefinition? =
        profiles.firstOrNull { profile -> profile.bluetoothDevices.any { it.equals(deviceName, ignoreCase = true) } }

    /**
     * The profile in effect: a connected Bluetooth device's (the car) first,
     * then the one picked by hand, then the time rules of automatic mode.
     */
    fun currentProfile(context: Context, now: Calendar): ProfileDefinition {
        val prefs = context.getSharedPreferences(PROFILE_PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(BLUETOOTH_PROFILE_KEY, null)?.let(::profileById)
            ?: prefs.getString("manual_profile_id", null)?.let(::profileById)
            ?: activeProfile(now)
    }

    fun activeProfile(now: Calendar): ProfileDefinition {
        val profileId = rules.firstOrNull { it.matches(now) }?.profileId ?: defaultProfileId
        return profiles.firstOrNull { it.id == profileId } ?: profiles.first()
    }

    companion object {
        fun load(context: Context): ProfileConfig {
            val json = context.assets.open("profiles.json").bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            val profiles = root.getJSONArray("profiles").let { array ->
                (0 until array.length()).map { index ->
                    val item = array.getJSONObject(index)
                    ProfileDefinition(
                        id = item.getString("id"),
                        label = item.getString("label"),
                        appsTitle = item.getString("appsTitle"),
                        keywords = item.optJSONArray("keywords")?.let { keywords ->
                            (0 until keywords.length()).map { keywords.getString(it).lowercase(Locale.ROOT) }
                        }.orEmpty(),
                        excludeKeywords = item.optJSONArray("excludeKeywords")?.let { excluded ->
                            (0 until excluded.length()).map { excluded.getString(it).lowercase(Locale.ROOT) }
                        }.orEmpty(),
                        pinnedKeywords = item.optJSONArray("pinnedKeywords")?.let { pinned ->
                            (0 until pinned.length()).map { pinned.getString(it).lowercase(Locale.ROOT) }
                        }.orEmpty(),
                        blockedPackages = item.optJSONArray("blockedPackages")?.let { blocked ->
                            (0 until blocked.length()).map { blocked.getString(it).lowercase(Locale.ROOT) }.toSet()
                        }.orEmpty(),
                        categories = item.optJSONArray("categories")?.let { categories ->
                            (0 until categories.length()).map { categories.getString(it) }.toSet()
                        }.orEmpty(),
                        hiddenKeywords = item.optJSONArray("hiddenKeywords")?.let { hidden ->
                            (0 until hidden.length()).map { hidden.getString(it).lowercase(Locale.ROOT) }
                        }.orEmpty(),
                        widgets = item.optJSONArray("widgets")?.let { widgets ->
                            (0 until widgets.length()).map { widgets.getString(it) }.toSet()
                        }.orEmpty(),
                        aliases = item.optJSONArray("aliases")?.let { aliases ->
                            (0 until aliases.length()).map { aliases.getString(it) }
                        }.orEmpty(),
                        icon = item.optString("icon").ifEmpty { null },
                        bluetoothDevices = item.optJSONArray("bluetoothDevices")?.let { devices ->
                            (0 until devices.length()).map { devices.getString(it) }
                        }.orEmpty(),
                        columns = item.optInt("columns", 4).coerceIn(2, 5)
                    )
                }
            }
            require(profiles.isNotEmpty()) { "profiles.json must define at least one profile" }

            val rules = root.optJSONArray("rules")?.let { array ->
                (0 until array.length()).map { index ->
                    val item = array.getJSONObject(index)
                    ProfileRule(
                        profileId = item.getString("profileId"),
                        daysOfWeek = item.optJSONArray("daysOfWeek")?.let { days ->
                            (0 until days.length()).mapNotNull { dayNameToCalendarValue(days.getString(it)) }.toSet()
                        }.orEmpty(),
                        startMinutes = parseTime(item.optString("start", "00:00")),
                        endMinutes = parseTime(item.optString("end", "24:00")),
                        dates = item.optJSONArray("dates")?.let { dates ->
                            (0 until dates.length()).map { dates.getString(it) }.toSet()
                        }.orEmpty()
                    )
                }
            }.orEmpty()

            return ProfileConfig(root.getString("defaultProfile"), profiles, rules)
        }

        private fun parseTime(value: String): Int {
            if (value == "24:00") return 24 * 60
            val parts = value.split(":")
            require(parts.size == 2) { "Invalid profile time: $value" }
            return parts[0].toInt() * 60 + parts[1].toInt()
        }

        private fun dayNameToCalendarValue(value: String): Int? = when (value.uppercase(Locale.ROOT)) {
            "SUNDAY" -> Calendar.SUNDAY
            "MONDAY" -> Calendar.MONDAY
            "TUESDAY" -> Calendar.TUESDAY
            "WEDNESDAY" -> Calendar.WEDNESDAY
            "THURSDAY" -> Calendar.THURSDAY
            "FRIDAY" -> Calendar.FRIDAY
            "SATURDAY" -> Calendar.SATURDAY
            else -> null
        }
    }
}
