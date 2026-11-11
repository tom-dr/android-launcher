package com.tom.magify.launcher

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.tom.magify.launcher.databinding.ItemAiQuotaBinding

private val QUOTA_OK_COLOR = Color.parseColor("#4ADE80")
private val QUOTA_WARNING_COLOR = Color.parseColor("#FBBF24")
private val QUOTA_CRITICAL_COLOR = Color.parseColor("#F87171")
private val DIM_TEXT_COLOR = Color.parseColor("#99FFFFFF")
private const val WIDGET_PREFS_NAME = "ai_quota_widget"
// Global setting from before it became per provider; still the fallback.
private const val LEGACY_LAYOUT_KEY = "layout"

/** How each provider lays out its session / weekly windows. */
enum class QuotaLayout { COLUMNS, ROWS }

/** Binds the work profile's Claude Code / Codex quota card to [AiQuotaManager]. */
class AiQuotaWidget(
    private val context: Context,
    private val manager: AiQuotaManager,
    private val rows: Map<AiProvider, ItemAiQuotaBinding>
) {

    private val prefs = context.getSharedPreferences(WIDGET_PREFS_NAME, Context.MODE_PRIVATE)

    // Re-renders on its own so the "il y a N s / min" ages and the reset
    // countdowns stay live while the home screen is showing.
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = Runnable { render() }
    private var ticking = false

    init {
        rows.forEach { (provider, row) ->
            row.root.setOnClickListener {
                if (manager.states.value.getValue(provider).connected) {
                    manager.refresh(provider)
                } else {
                    login(provider)
                }
            }
            row.root.setOnLongClickListener { view ->
                showMenu(provider, view)
                true
            }
            row.quotaRefresh.setOnClickListener { manager.refresh(provider) }
        }
        rows.keys.forEach(::applyLayout)
    }

    // Both settings are per provider: changing Codex's never touches Claude's.
    private fun prefKey(name: String, provider: AiProvider) = "${name}_${provider.name.lowercase()}"

    private fun layoutOf(provider: AiProvider): QuotaLayout {
        val saved = prefs.getString(prefKey("layout", provider), null) ?: prefs.getString(LEGACY_LAYOUT_KEY, null)
        return QuotaLayout.entries.firstOrNull { it.name == saved } ?: QuotaLayout.COLUMNS
    }

    private fun showsPlan(provider: AiProvider) = prefs.getBoolean(prefKey("show_plan", provider), true)

    private fun showsPercent(provider: AiProvider) = prefs.getBoolean(prefKey("show_percent", provider), true)

    private fun setLayout(provider: AiProvider, layout: QuotaLayout) {
        prefs.edit().putString(prefKey("layout", provider), layout.name).apply()
        applyLayout(provider)
        render()
    }

    private fun setShowsPlan(provider: AiProvider, show: Boolean) {
        prefs.edit().putBoolean(prefKey("show_plan", provider), show).apply()
        render()
    }

    private fun setShowsPercent(provider: AiProvider, show: Boolean) {
        prefs.edit().putBoolean(prefKey("show_percent", provider), show).apply()
        render()
    }

    private fun applyLayout(provider: AiProvider) {
        val row = rows.getValue(provider)
        val columns = layoutOf(provider) == QuotaLayout.COLUMNS
        val density = context.resources.displayMetrics.density
        row.quotaWindows.orientation = if (columns) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        listOf(row.quotaPrimary, row.quotaSecondary).forEach { window ->
            window.layoutParams = (window.layoutParams as LinearLayout.LayoutParams).apply {
                width = if (columns) 0 else ViewGroup.LayoutParams.MATCH_PARENT
                weight = if (columns) 1f else 0f
            }
        }
        row.quotaSecondary.layoutParams = (row.quotaSecondary.layoutParams as LinearLayout.LayoutParams).apply {
            marginStart = if (columns) (16 * density).toInt() else 0
            topMargin = if (columns) 0 else (10 * density).toInt()
        }
    }

    fun startTicking() {
        ticking = true
        render()
    }

    fun stopTicking() {
        ticking = false
        ticker.removeCallbacks(tick)
    }

    fun render(states: Map<AiProvider, AiQuotaState> = manager.states.value) {
        val now = System.currentTimeMillis()
        rows.forEach { (provider, row) -> renderRow(provider, row, states.getValue(provider), now) }
        scheduleTick(states, now)
    }

    private fun scheduleTick(states: Map<AiProvider, AiQuotaState>, now: Long) {
        ticker.removeCallbacks(tick)
        if (!ticking) return
        // Every second while an age is shown in seconds, then right when the
        // next minute of age (or of a reset countdown) ticks over.
        val delay = states.values
            .filter { it.updatedAtMillis > 0 }
            .map { now - it.updatedAtMillis }
            .minOfOrNull { age -> if (age < 60_000) 1000 - age % 1000 else 60_000 - age % 60_000 }
            ?: 60_000L
        ticker.postDelayed(tick, delay.coerceAtLeast(250))
    }

    private fun renderRow(provider: AiProvider, row: ItemAiQuotaBinding, state: AiQuotaState, now: Long) {
        // The plan describes the account, not the refresh: it goes with the
        // name ("Codex (Plus)"), dimmed, leaving the right side to freshness.
        row.quotaProviderName.text = if (state.connected && state.plan != null && showsPlan(provider)) {
            SpannableStringBuilder(provider.label).append(" ").append(
                "(${state.plan})",
                ForegroundColorSpan(DIM_TEXT_COLOR),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        } else {
            provider.label
        }
        row.quotaStatus.text = when {
            state.connecting -> context.getString(R.string.ai_quota_connecting)
            state.loading -> context.getString(R.string.ai_quota_loading)
            state.error != null -> state.error
            state.updatedAtMillis > 0 -> formatAge(now - state.updatedAtMillis)
            else -> ""
        }
        row.quotaRefresh.visibility = if (state.connected) View.VISIBLE else View.GONE
        row.quotaRefresh.isEnabled = !state.loading
        row.quotaRefresh.alpha = if (state.loading) 0.3f else 1f
        row.quotaConnectHint.visibility =
            if (!state.connected && !state.connecting) View.VISIBLE else View.GONE
        row.quotaWindows.visibility =
            if (state.connected && state.windows.isNotEmpty()) View.VISIBLE else View.GONE
        val layout = layoutOf(provider)
        val showPercent = showsPercent(provider)
        bindWindow(
            row.quotaPrimary, row.quotaPrimaryLabel, row.quotaPrimaryPercent, row.quotaPrimaryBar,
            row.quotaPrimaryReset, state.windows.getOrNull(0), layout, showPercent, now
        )
        bindWindow(
            row.quotaSecondary, row.quotaSecondaryLabel, row.quotaSecondaryPercent, row.quotaSecondaryBar,
            row.quotaSecondaryReset, state.windows.getOrNull(1), layout, showPercent, now
        )
    }

    private fun bindWindow(
        column: View,
        label: TextView,
        percent: TextView,
        bar: ProgressBar,
        reset: TextView,
        window: QuotaWindow?,
        layout: QuotaLayout,
        showPercent: Boolean,
        now: Long
    ) {
        column.visibility = if (window == null) View.GONE else View.VISIBLE
        if (window == null) return

        label.text = window.label
        val resetText = window.resetsAtMillis?.let { formatReset(it, now) }
        if (layout == QuotaLayout.ROWS) {
            // Full width: the reset fits next to the percentage, two lines per window.
            percent.text = SpannableStringBuilder().apply {
                if (showPercent) append(boldPercent(window))
                if (resetText != null) {
                    append(
                        if (showPercent) " · $resetText" else resetText,
                        ForegroundColorSpan(DIM_TEXT_COLOR),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
            percent.visibility = if (percent.text.isEmpty()) View.GONE else View.VISIBLE
            reset.visibility = View.GONE
        } else {
            percent.text = boldPercent(window)
            percent.visibility = if (showPercent) View.VISIBLE else View.GONE
            reset.text = resetText.orEmpty()
            reset.visibility = if (resetText == null) View.GONE else View.VISIBLE
        }
        bar.progress = window.usedPercent.coerceIn(0, 100)
        bar.progressTintList = ColorStateList.valueOf(
            when {
                window.usedPercent >= 90 -> QUOTA_CRITICAL_COLOR
                window.usedPercent >= 70 -> QUOTA_WARNING_COLOR
                else -> QUOTA_OK_COLOR
            }
        )
    }

    private fun boldPercent(window: QuotaWindow) = SpannableStringBuilder().append(
        "${window.usedPercent} %",
        StyleSpan(Typeface.BOLD),
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    )

    private fun showMenu(provider: AiProvider, anchor: View) {
        val connected = manager.states.value.getValue(provider).connected
        PopupMenu(context, anchor).apply {
            if (connected) {
                menu.add(context.getString(R.string.ai_quota_refresh)).setOnMenuItemClickListener {
                    manager.refresh(provider)
                    true
                }
                menu.add(context.getString(R.string.ai_quota_logout)).setOnMenuItemClickListener {
                    manager.logout(provider)
                    true
                }
                val showPercent = showsPercent(provider)
                menu.add(context.getString(R.string.ai_quota_show_percent)).apply {
                    isCheckable = true
                    isChecked = showPercent
                    setOnMenuItemClickListener {
                        setShowsPercent(provider, !showPercent)
                        true
                    }
                }
                val showPlan = showsPlan(provider)
                menu.add(context.getString(R.string.ai_quota_show_plan)).apply {
                    isCheckable = true
                    isChecked = showPlan
                    setOnMenuItemClickListener {
                        setShowsPlan(provider, !showPlan)
                        true
                    }
                }
            } else {
                menu.add(context.getString(R.string.ai_quota_login)).setOnMenuItemClickListener {
                    login(provider)
                    true
                }
            }
            val otherLayout = if (layoutOf(provider) == QuotaLayout.COLUMNS) QuotaLayout.ROWS else QuotaLayout.COLUMNS
            val layoutLabel = if (otherLayout == QuotaLayout.ROWS) {
                R.string.ai_quota_layout_rows
            } else {
                R.string.ai_quota_layout_columns
            }
            menu.add(context.getString(layoutLabel)).setOnMenuItemClickListener {
                setLayout(provider, otherLayout)
                true
            }
            show()
        }
    }

    private fun login(provider: AiProvider) {
        manager.login(provider) { uri ->
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(context, R.string.ai_quota_no_browser, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun formatAge(ageMillis: Long): String {
        val seconds = ageMillis / 1000
        val minutes = seconds / 60
        return when {
            seconds < 30 -> "à l'instant"
            minutes < 1 -> "il y a $seconds s"
            minutes < 60 -> "il y a $minutes min"
            else -> "il y a ${minutes / 60} h"
        }
    }

    private fun formatReset(resetsAtMillis: Long, now: Long): String {
        val remainingMinutes = (resetsAtMillis - now) / 60_000
        return when {
            remainingMinutes <= 0 -> "réinitialisé"
            remainingMinutes < 60 -> "réinit. dans $remainingMinutes min"
            remainingMinutes < 24 * 60 ->
                "réinit. dans ${remainingMinutes / 60} h %02d".format(remainingMinutes % 60)
            else -> {
                val hours = remainingMinutes / 60
                "réinit. dans ${hours / 24} j %d h".format(hours % 24)
            }
        }
    }
}
