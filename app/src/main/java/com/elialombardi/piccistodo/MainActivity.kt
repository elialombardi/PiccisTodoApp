package com.elialombardi.piccistodo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.elialombardi.piccistodo.ui.theme.PiccisTodoTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint // CRITICAL: Required for Hilt ViewModels to work
class MainActivity : AppCompatActivity() {
override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val feature = intent?.getStringExtra("feature_name")

        setContent {
            PiccisTodoTheme {
                MainNavigation(startDestination = if (feature != null) "create" else "login")
            }
        }
    }
}