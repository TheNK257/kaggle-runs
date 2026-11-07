package com.namankadam.kaggleruns

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun RunsScreen(snapshot: Snapshot?) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        snapshot?.error?.let { item { ErrorBanner(it) } }
        when {
            snapshot == null -> item { EmptyState("Loading your notebooks…") }
            snapshot.runs.isEmpty() -> item { EmptyState("No notebook runs yet. Committed runs (Save Version) show up here.") }
            else -> items(snapshot.runs, key = { it.ref }) { RunCard(it, snapshot.latestSubmissionFor(it.ref)) }
        }
        item {
            Text(
                "Only committed runs (Save Version / Save & Run All) are visible. Interactive editor sessions aren't exposed by the Kaggle API.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun RunCard(run: Run, submission: Submission?) {
    val context = LocalContext.current
    OutlinedCard(
        onClick = { context.openUrl("https://www.kaggle.com/code/${run.ref}") },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    run.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                StatusChip(run.status)
            }
            Spacer(Modifier.height(4.dp))
            if (run.status.active && run.startMillis > 0) {
                LiveElapsed(run.startMillis) { elapsed ->
                    "Running for $elapsed · started ${formatClock(context, run.startMillis)}"
                }
            } else {
                val parts = listOfNotNull(
                    run.startMillis.takeIf { it > 0 }?.let { "Started " + formatClock(context, it) },
                    run.durationSec?.let { "took " + formatDuration(it) },
                )
                if (parts.isNotEmpty()) SecondaryText(parts.joinToString(" · "))
            }
            if (run.status == RunStatus.ERROR && run.failure.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(run.failure, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (submission != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "📤 Submitted ${formatClock(context, submission.dateMillis)} · ${submission.scoreText}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** Re-renders once a second while visible, matching the widget's ticking Chronometer. */
@Composable
private fun LiveElapsed(startMillis: Long, text: (String) -> String) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startMillis) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    SecondaryText(text(formatElapsed(now - startMillis)))
}

@Composable
fun StatusChip(status: RunStatus) {
    val colors = LocalAppColors.current
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            // Colour marks identity; the label carries it in text too.
            Box(Modifier.size(8.dp).background(colors.forStatus(status, MaterialTheme.colorScheme.primary), CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(status.label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun SecondaryText(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
fun EmptyState(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
    )
}

@Composable
fun ErrorBanner(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
        Text(
            "⚠️ $text. Showing the last data received.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        )
    }
}
