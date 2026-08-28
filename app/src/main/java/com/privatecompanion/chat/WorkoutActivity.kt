package com.privatecompanion.chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.privatecompanion.chat.ui.WorkoutScreen
import com.privatecompanion.chat.ui.theme.MiCompanionTheme

class WorkoutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MiCompanionTheme {
                WorkoutScreen()
            }
        }
    }
}
