package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import com.example.engine.configureAppCheck
import com.example.ui.BoardroomApp
import com.example.ui.theme.MuseBoardroomTheme

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configureAppCheck(this, intent)

        setContent {
            MuseBoardroomTheme {
                val windowSizeClass = calculateWindowSizeClass(this)
                BoardroomApp(widthSizeClass = windowSizeClass.widthSizeClass)
            }
        }
    }
}
