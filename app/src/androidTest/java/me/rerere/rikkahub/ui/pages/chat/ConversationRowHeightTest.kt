package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Real Compose measurement tests; require a test device, not run in the user's active Tablet. */
@OptIn(ExperimentalUuidApi::class)
@RunWith(AndroidJUnit4::class)
class ConversationRowHeightTest {
    @get:Rule val compose = createComposeRule()

    @Test fun ordinaryAndTeamRowsShare36DpBaseline() {
        showPair(fontScale = 1f)
        compose.onNodeWithTag("plain").assertHeightIsEqualTo(36.dp)
        compose.onNodeWithTag("team").assertHeightIsEqualTo(36.dp)
    }

    @Test fun accessibilityTextGrowsBothRowsEqually() {
        showPair(fontScale = 2f)
        val plain = compose.onNodeWithTag("plain").fetchSemanticsNode().size.height
        val team = compose.onNodeWithTag("team").fetchSemanticsNode().size.height
        assertEquals(plain, team)
    }

    @Test fun teamArrowRemainsAnIndependentActionForLongTitles() {
        var teamClicks = 0
        var conversationClicks = 0
        showPair(fontScale = 1f, onTeam = { teamClicks++ }, onConversation = { conversationClicks++ })
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithContentDescription(context.getString(R.string.agent_team_open_team)).performClick()
        compose.runOnIdle {
            assertEquals(1, teamClicks)
            assertEquals(0, conversationClicks)
        }
        compose.onNodeWithTag("plain").performClick()
        compose.runOnIdle { assertEquals(1, conversationClicks) }
    }

    private fun showPair(fontScale: Float, onTeam: () -> Unit = {}, onConversation: () -> Unit = {}) {
        val conversation = Conversation.ofId(Uuid.random()).copy(
            title = "自动压缩功能开发与团队 · long conversation title that must leave room for the arrow",
        )
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                MaterialTheme {
                    Column(Modifier.width(280.dp)) {
                        ConversationItem(
                            conversation = conversation, selected = false, loading = true,
                            modifier = Modifier.testTag("plain"), onClick = { onConversation() },
                        )
                        ConversationItem(
                            conversation = conversation, selected = true, loading = true,
                            modifier = Modifier.testTag("team"), isTeamLead = true,
                            onOpenTeam = { onTeam() }, onClick = { onConversation() },
                        )
                    }
                }
            }
        }
    }
}
