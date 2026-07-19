package com.privatecompanion.chat

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import com.privatecompanion.chat.notifications.CompanionNotifications
import com.privatecompanion.chat.ui.CompanionApp
import com.privatecompanion.chat.ui.theme.MiCompanionTheme

class MainActivity : ComponentActivity() {
    private val openChatRequest = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CompanionNotifications.createChannel(this)
        consumeOpenChatIntent(intent)
        setContent {
            MiCompanionTheme {
                CompanionApp(openChatRequest = openChatRequest.intValue)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeOpenChatIntent(intent)
    }

    private fun consumeOpenChatIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(CompanionNotifications.EXTRA_OPEN_CHAT, false) == true) {
            openChatRequest.intValue += 1
            intent.removeExtra(CompanionNotifications.EXTRA_OPEN_CHAT)
        }
    }
}
