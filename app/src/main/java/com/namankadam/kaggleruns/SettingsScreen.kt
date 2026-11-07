package com.namankadam.kaggleruns

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

@Composable
fun SettingsScreen(hasToken: Boolean, onConnected: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TokenSection(hasToken, onConnected)
        if (hasToken) {
            RefreshSection()
            NotificationSection()
        }
        Text(
            "Kaggle Runs ${BuildConfig.VERSION_NAME} · Only committed runs (Save Version) are visible; " +
                "interactive editor sessions aren't exposed by the Kaggle API.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun TokenSection(hasToken: Boolean, onConnected: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    SettingsCard("Kaggle API token") {
        SecondaryText("Create one on kaggle.com under Settings → API → Create New Token. It starts with KGAT_.")
        TextButton(onClick = { context.openUrl("https://www.kaggle.com/settings") }) { Text("Open Kaggle settings") }
        if (hasToken) {
            val last4 = remember(hasToken) { TokenStore.load(context)?.takeLast(4) ?: "" }
            Text("A token ending in …$last4 is saved. Paste a new one to replace it.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("API token") },
            placeholder = { Text("KGAT_…") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = !checking,
                onClick = {
                    val entered = token.trim()
                    if (entered.isEmpty()) {
                        result = "Paste your token first."
                        return@Button
                    }
                    checking = true
                    result = "Checking with Kaggle…"
                    scope.launch {
                        val (message, ok) = verifyAndSave(context, entered)
                        result = message
                        checking = false
                        if (ok) {
                            token = ""
                            onConnected()
                        }
                    }
                },
            ) { Text("Save and test") }
            if (hasToken) {
                OutlinedButton(onClick = {
                    removeToken(context)
                    result = "Token removed."
                }) { Text("Remove token") }
            }
        }
        result?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private suspend fun verifyAndSave(context: Context, token: String): Pair<String, Boolean> = try {
    val kernels = KaggleApi(token).listMine()
    TokenStore.save(context, token)
    AppState.hasToken.value = true
    RefreshWorker.schedulePeriodic(context)
    RefreshWorker.refreshNow(context)
    val user = kernels.firstOrNull()?.ref?.substringBefore('/')
    (if (user != null) "✓ Connected as $user." else "✓ Token works, but you have no notebooks yet.") to true
} catch (e: CancellationException) {
    throw e
} catch (e: AuthException) {
    "Kaggle rejected this token. Check that you copied all of it, or create a new one." to false
} catch (e: IOException) {
    "Couldn't reach Kaggle (${e.message}). Check your connection and try again." to false
} catch (e: Exception) {
    "Unexpected response from Kaggle (${e.javaClass.simpleName})." to false
}

private fun removeToken(context: Context) {
    RefreshWorker.cancelAll(context)
    TokenStore.clear(context)
    SnapshotCache.clear(context)
    DurationCache.clear(context)
    HistoryStore.clear(context)
    AppState.hasToken.value = false
    AppState.snapshot.value = null
    WidgetUpdater.updateAll(context)
}

@Composable
private fun RefreshSection() {
    val context = LocalContext.current
    var minutes by remember { mutableIntStateOf(Prefs.refreshMinutes(context)) }
    SettingsCard("Background refresh") {
        SecondaryText("How often widgets and notifications check Kaggle. Android doesn't allow background checks more often than every 15 minutes.")
        Spacer(Modifier.height(4.dp))
        Prefs.REFRESH_CHOICES.forEach { choice ->
            val label = when (choice) {
                Prefs.MANUAL -> "Manual only (pull to refresh, or ⟳ on a widget)"
                in 1..59 -> "Every $choice minutes"
                60 -> "Every hour"
                else -> "Every ${choice / 60} hours"
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = minutes == choice, role = Role.RadioButton) {
                        minutes = choice
                        Prefs.setRefreshMinutes(context, choice)
                        RefreshWorker.schedulePeriodic(context)
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = minutes == choice, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun NotificationSection() {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(Notifier.canPost(context)) }
    var runs by remember { mutableStateOf(Prefs.notifyRuns(context)) }
    var subs by remember { mutableStateOf(Prefs.notifySubmissions(context)) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    fun askIfNeeded() {
        if (!allowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) request.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    SettingsCard("Notifications") {
        if (!allowed) {
            SecondaryText("Notifications are turned off for Kaggle Runs.")
            TextButton(onClick = { askIfNeeded() }) { Text("Allow notifications") }
        }
        SwitchRow("When a run finishes, fails or is cancelled", runs) {
            runs = it
            Prefs.setNotifyRuns(context, it)
            if (it) askIfNeeded()
        }
        SwitchRow("When a submission is scored", subs) {
            subs = it
            Prefs.setNotifySubmissions(context, it)
            if (it) askIfNeeded()
        }
        SecondaryText("Notifications arrive at the next background check, so within your refresh interval.")
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}
