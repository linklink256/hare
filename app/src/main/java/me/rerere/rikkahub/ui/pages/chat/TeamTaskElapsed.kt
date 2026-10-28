package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.formatTeamTaskElapsed

@Composable
internal fun TeamTaskElapsed(elapsedMillis: Long?, modifier: Modifier = Modifier) {
    val text = elapsedMillis?.let(::formatTeamTaskElapsed) ?: "—"
    val description = if (elapsedMillis == null) {
        stringResource(R.string.agent_team_elapsed_unknown)
    } else {
        stringResource(R.string.agent_team_elapsed, text)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = modifier.semantics { contentDescription = description },
    )
}
