package com.namankadam.kaggleruns

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private const val SUBMISSIONS_PREVIEW = 5

@Composable
fun CompetitionsScreen(snapshot: Snapshot?) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        snapshot?.error?.let { item { ErrorBanner(it) } }
        when {
            snapshot == null -> item { EmptyState("Loading your competitions…") }
            snapshot.competitions.isEmpty() -> item { EmptyState("You haven't entered any active competitions.") }
            else -> items(snapshot.competitions, key = { it.slug }) { CompetitionCard(it, snapshot) }
        }
    }
}

/** Collapsed: the headline stats. Expanded: history graphs and submissions. */
@Composable
private fun CompetitionCard(comp: Competition, snapshot: Snapshot) {
    val context = LocalContext.current
    var expanded by rememberSaveable(comp.slug) { mutableStateOf(false) }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")

    OutlinedCard(
        onClick = { expanded = !expanded },
        modifier = Modifier
            .fillMaxWidth()
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    comp.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Hide details" else "Show details",
                    modifier = Modifier.rotate(chevron),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val meta = listOfNotNull(
                timeLeft(comp.deadlineMillis),
                comp.leftToday()?.let { "$it of ${comp.maxDaily} submissions left today" },
            )
            if (meta.isNotEmpty()) SecondaryText(meta.joinToString(" · "))

            Summary(comp, snapshot)

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Details(comp, snapshot)
            }

            if (!expanded) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Show graphs and all submissions",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { context.openUrl(comp.url) }) { Text("Open on Kaggle") }
            }
        }
    }
}

@Composable
private fun Summary(comp: Competition, snapshot: Snapshot) {
    val context = LocalContext.current
    val colors = LocalAppColors.current
    val change = remember(comp.slug, snapshot.fetchedAt) { HistoryStore.rankChange(context, comp) }

    // Hero figure: the one number the card leads with.
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            comp.userRank?.let { "#" + formatCount(it) } ?: "Unranked",
            fontSize = 44.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 48.sp,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.padding(bottom = 5.dp)) {
            SecondaryText("of ${formatCount(comp.teamCount)} teams")
            if (change != null && change.delta != 0) {
                val window = if (System.currentTimeMillis() - change.sinceMillis >= 24 * 3_600_000L) "in 24h"
                else "since " + formatClock(context, change.sinceMillis)
                Text(
                    "${formatRankDelta(change.delta)} $window",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (change.delta > 0) colors.goodText else colors.critical,
                )
            }
        }
    }

    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Stat("Best score", comp.bestSubmission?.publicScore ?: "—")
        Stat("Top score", comp.topScore ?: "—")
    }
    comp.latest?.let { sub ->
        Spacer(Modifier.height(6.dp))
        val name = sub.notebookRef?.let { snapshot.notebookTitle(it) } ?: "File upload"
        Text(
            "Latest: $name · ${sub.scoreText} · ${formatDay(context, sub.dateMillis)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Details(comp: Competition, snapshot: Snapshot) {
    val context = LocalContext.current
    val history = remember(comp.slug, snapshot.fetchedAt) { HistoryStore.load(context, comp.slug) }
    var showAll by rememberSaveable(comp.slug) { mutableStateOf(false) }

    Column {
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Two single-series charts rather than one dual-axis chart: rank and score have different scales.
        Spacer(Modifier.height(16.dp))
        ChartSection(
            title = "Rank over time",
            subtitle = "Up is better",
            points = history.mapNotNull { p -> p.rank?.let { p.t to it.toDouble() } },
            invertY = true,
            format = { "#" + formatCount(it.roundToInt()) },
        )
        Spacer(Modifier.height(16.dp))
        val decimals = comp.scoreDecimals
        ChartSection(
            title = "Best public score over time",
            subtitle = if (comp.higherIsBetter) "Up is better" else "Up is better (lower scores rank higher)",
            points = history.mapNotNull { p -> p.best?.let { p.t to it } },
            invertY = !comp.higherIsBetter,
            format = { "%.${decimals}f".format(it) },
        )

        Spacer(Modifier.height(20.dp))
        Text("Submissions (${comp.submissions.size})", style = MaterialTheme.typography.titleMedium)
        if (comp.submissions.isEmpty()) SecondaryText("No submissions yet.", Modifier.padding(top = 4.dp))
        val shown = if (showAll) comp.submissions else comp.submissions.take(SUBMISSIONS_PREVIEW)
        shown.forEachIndexed { i, sub ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SubmissionRow(sub, snapshot)
        }
        if (comp.submissions.size > SUBMISSIONS_PREVIEW) {
            TextButton(onClick = { showAll = !showAll }) {
                Text(if (showAll) "Show fewer" else "Show all ${comp.submissions.size}")
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SubmissionRow(sub: Submission, snapshot: Snapshot) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = sub.notebookRef != null, role = Role.Button) {
                sub.notebookRef?.let { context.openUrl("https://www.kaggle.com/code/$it") }
            }
            .padding(vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                sub.notebookRef?.let { snapshot.notebookTitle(it) } ?: "File upload",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            val score = if (sub.status == SubmissionStatus.COMPLETE && sub.privateScore.isNotBlank()) {
                "${sub.publicScore} / ${sub.privateScore}"
            } else sub.scoreText
            Text(score, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        }
        Text(
            formatClock(context, sub.dateMillis),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (sub.description.isNotBlank()) {
            Text(
                sub.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (sub.status == SubmissionStatus.ERROR && sub.error.isNotBlank()) {
            Text(sub.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
