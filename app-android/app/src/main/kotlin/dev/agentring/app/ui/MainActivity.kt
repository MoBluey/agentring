package dev.agentring.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentring.app.Graph

/** Which screen is on top, so a push for a conversation the user is reading doesn't notify. */
object AppVisibility {
    @Volatile var visibleChatId: String? = null
    val foreground = mutableStateOf(false)
    var isForeground: Boolean
        get() = foreground.value
        set(value) { foreground.value = value }
    val requestedChat = mutableStateOf<String?>(null)
}

class MainActivity : ComponentActivity() {
    private val openMessages = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            AgentringTheme {
                AppRoot(openMessages)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppVisibility.isForeground = true
    }

    override fun onPause() {
        AppVisibility.isForeground = false
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getStringExtra(EXTRA_OPEN) == OPEN_MESSAGES) {
            AppVisibility.requestedChat.value = intent.getStringExtra("chat_id")
            openMessages.value = true
        }
    }

    companion object {
        const val EXTRA_OPEN = "open"
        const val OPEN_MESSAGES = "messages"
    }
}

@Composable
fun AppRoot(openMessages: MutableState<Boolean>) {
    val pairing by Graph.pairing.pairing.collectAsStateWithLifecycle()
    val setupComplete by Graph.pairing.setupComplete.collectAsStateWithLifecycle()
    val p = pairing
    if (p == null || !setupComplete) {
        OnboardingFlow(paired = p, onFinished = { Graph.pairing.setSetupComplete(true) })
    } else {
        MainScaffold(p, openMessages)
    }
}
