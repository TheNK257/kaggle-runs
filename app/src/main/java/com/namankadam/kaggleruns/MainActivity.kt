package com.namankadam.kaggleruns

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppState.init(this)
        val startTab = intent.getStringExtra(EXTRA_TAB)
        setContent { KaggleRunsTheme { KaggleRunsApp(startTab) } }
    }

    override fun onResume() {
        super.onResume()
        WidgetUpdater.updateAll(this) // opening the app also repairs a blank widget
        val snapshot = AppState.snapshot.value
        val stale = snapshot == null || System.currentTimeMillis() - snapshot.fetchedAt > 2 * 60_000L
        if (AppState.hasToken.value && stale) lifecycleScope.launch { Refresher.refresh(applicationContext) }
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_RUNS = "runs"
        const val TAB_COMPETITIONS = "competitions"
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    RUNS("Runs", Icons.AutoMirrored.Filled.List),
    COMPETITIONS("Competitions", Icons.Filled.Star),
    SETTINGS("Settings", Icons.Filled.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KaggleRunsApp(startTab: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot by AppState.snapshot.collectAsState()
    val refreshing by AppState.refreshing.collectAsState()
    val hasToken by AppState.hasToken.collectAsState()
    var tab by rememberSaveable {
        mutableStateOf(
            when {
                !hasToken -> Tab.SETTINGS
                startTab == MainActivity.TAB_COMPETITIONS -> Tab.COMPETITIONS
                else -> Tab.RUNS
            },
        )
    }
    val refresh: () -> Unit = { scope.launch { Refresher.refresh(context) } }

    // Ask once for notification permission (Android 13+) after setup; it can be changed in Settings later.
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(hasToken) {
        if (hasToken && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !Notifier.canPost(context) && !Prefs.askedForNotifications(context)
        ) {
            Prefs.setAskedForNotifications(context)
            askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (tab == Tab.SETTINGS) "Settings" else "Kaggle Runs")
                        val fetched = snapshot?.fetchedAt ?: 0L
                        if (tab != Tab.SETTINGS && fetched > 0) {
                            Text(
                                if (refreshing) "Refreshing…" else "Updated " + formatClock(context, fetched),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    if (hasToken && tab != Tab.SETTINGS) {
                        IconButton(onClick = refresh, enabled = !refreshing) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        enabled = hasToken || t == Tab.SETTINGS,
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.RUNS -> PullToRefreshBox(isRefreshing = refreshing, onRefresh = refresh) {
                    RunsScreen(snapshot)
                }
                Tab.COMPETITIONS -> PullToRefreshBox(isRefreshing = refreshing, onRefresh = refresh) {
                    CompetitionsScreen(snapshot)
                }
                Tab.SETTINGS -> SettingsScreen(hasToken, onConnected = { tab = Tab.RUNS })
            }
        }
    }
}
