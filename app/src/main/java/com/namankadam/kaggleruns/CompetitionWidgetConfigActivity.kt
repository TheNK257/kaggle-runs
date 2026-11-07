package com.namankadam.kaggleruns

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Lets each competition widget follow a specific competition, or "automatic". */
class CompetitionWidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        // Backing out without choosing must not add the widget on launchers where configuration is required.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))

        val competitions = SnapshotCache.load(this)?.competitions.orEmpty()
        val current = Prefs.widgetCompetition(this, widgetId)
        enableEdgeToEdge()
        setContent {
            KaggleRunsTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
                        Text("Choose a competition", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(4.dp))
                        SecondaryText("This widget shows your rank, scores and submissions for it.")
                        Spacer(Modifier.height(16.dp))
                        Option("Automatic", "The competition you submitted to most recently", current == null) { choose(widgetId, null) }
                        competitions.forEach { comp ->
                            Option(comp.title, timeLeft(comp.deadlineMillis) ?: comp.slug, current == comp.slug) {
                                choose(widgetId, comp.slug)
                            }
                        }
                        if (competitions.isEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            SecondaryText("Your competitions appear here once the app has synced. Choose Automatic for now.")
                        }
                    }
                }
            }
        }
    }

    private fun choose(widgetId: Int, slug: String?) {
        Prefs.setWidgetCompetition(this, widgetId, slug)
        CompetitionWidget.update(this, widgetId)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}

@Composable
private fun Option(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            SecondaryText(subtitle)
        }
    }
}
