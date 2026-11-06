package com.elialombardi.piccistodo.ui.screens

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.elialombardi.piccistodo.ui.viewmodels.VoiceCreateViewModel // Ensure this matches your package

@Composable
fun VoiceCreateScreen(
    onConfirm: (String) -> Unit,
    viewModel: VoiceCreateViewModel = hiltViewModel()
) {
    val recognizedText by viewModel.recognizedText.collectAsState()
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (text != null) viewModel.onTextRecognized(text)
    }

    LaunchedEffect(Unit) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
        }
        launcher.launch(intent)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Ho sentito:", style = MaterialTheme.typography.titleMedium)
        Text(recognizedText, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        Button(onClick = { onConfirm(recognizedText) }) {
            Text("Conferma e Salva")
        }
    }
}