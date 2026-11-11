package com.tom.magify.launcher

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.tom.magify.launcher.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val OFFSCREEN_FOLDER_CACHE = 4

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: AppRepository
    private lateinit var folderAdapter: FolderAdapter
    private lateinit var aiQuotaWidget: AiQuotaWidget
    private var loadJob: Job? = null
    private var firstLoad = true

    // The real, category-sorted folders as last loaded — the source of
    // truth the search filter is applied on top of, never overwritten by it.
    private var allFolders: List<LauncherFolder> = emptyList()
    private var lastSearchHadResults = true

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            loadApps()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = (application as MagifyApplication).repository
        folderAdapter = FolderAdapter { app -> launchApp(app) }
        binding.folderList.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            setHasFixedSize(true)
            itemAnimator = null
            // Keep a few off-screen folder rows bound instead of tearing
            // them down the instant they scroll out, so a fast fling
            // doesn't keep re-creating/re-binding rows at the edges.
            setItemViewCacheSize(OFFSCREEN_FOLDER_CACHE)
            adapter = folderAdapter
        }
        aiQuotaWidget = AiQuotaWidget(
            this,
            (application as MagifyApplication).aiQuotas,
            mapOf(AiProvider.CLAUDE to binding.claudeQuota, AiProvider.CODEX to binding.codexQuota)
        )
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                (application as MagifyApplication).aiQuotas.states.collect {
                    aiQuotaWidget.render(it)
                }
            }
        }
        loadApps()

        binding.searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilter(s?.toString().orEmpty())
            }
        })

        // A home screen has nothing "before" it: left unhandled, back press
        // falls through to finish()ing this activity, which makes the system
        // tear down and relaunch a brand new instance the moment it needs to
        // show Home again (visible as a full reload). Swallow it instead —
        // except the search box still has text, in which case the first back
        // press (after the system has already dismissed the keyboard) clears
        // it, matching how search fields on most launchers behave.
        onBackPressedDispatcher.addCallback(this) {
            if (binding.searchBox.text?.isNotEmpty() == true) {
                binding.searchBox.text?.clear()
                binding.searchBox.clearFocus()
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        registerReceiver(packageChangeReceiver, filter)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            val focused = currentFocus
            if (focused is EditText) {
                val bounds = Rect()
                focused.getGlobalVisibleRect(bounds)
                if (!bounds.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    focused.clearFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(focused.windowToken, 0)
                    // A search that matched nothing left behind is just dead
                    // weight once the user has tapped away from it — clear it
                    // so they land back on the normal folder view.
                    if (focused === binding.searchBox && !lastSearchHadResults) {
                        binding.searchBox.text?.clear()
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask: the system re-delivers Home presses to this same
        // instance instead of creating a new one; nothing to rebuild here.
        setIntent(intent)
    }

    override fun onDestroy() {
        aiQuotaWidget.stopTicking()
        unregisterReceiver(packageChangeReceiver)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        aiQuotaWidget.startTicking()
        (application as MagifyApplication).aiQuotas.onForeground(
            refreshQuotas = true
        )
    }

    private fun loadApps() {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            // The very first load awaits the enumeration the Application
            // class kicked off at process start, instead of starting a
            // second, redundant one from scratch.
            val app = application as MagifyApplication
            val folders = if (firstLoad) {
                firstLoad = false
                app.preloadedFolders.await()
            } else {
                repository.buildFolders()
            }
            allFolders = folders
            applyFilter(binding.searchBox.text?.toString().orEmpty())
        }
    }

    private fun applyFilter(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            lastSearchHadResults = true
            folderAdapter.updateFolders(allFolders)
            return
        }
        val needle = trimmed.lowercase()
        val matches = allFolders.flatMap { it.apps }
            .filter { it.label.lowercase().contains(needle) || it.packageName.lowercase().contains(needle) }
            .sortedBy { it.label.lowercase() }
        lastSearchHadResults = matches.isNotEmpty()
        val results = if (matches.isEmpty()) {
            emptyList()
        } else {
            listOf(LauncherFolder(getString(R.string.search_results_folder), matches))
        }
        folderAdapter.updateFolders(results)
    }

    private fun launchApp(app: LauncherApp) {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName(app.packageName, app.activityName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        runCatching { startActivity(intent) }
    }
}
