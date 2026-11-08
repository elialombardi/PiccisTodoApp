package com.elialombardi.piccistodo.ui.screens

import android.util.Log
import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.elialombardi.piccistodo.data.local.BiometricHelper
import com.elialombardi.piccistodo.ui.viewmodels.AuthViewModel


import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.LocalActivity
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel()
) {
    // 1. Collect state from the ViewModel
    val savedUrl by viewModel.savedUrl.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val biometricAvailable by viewModel.biometricAvailable.collectAsState()
    val biometricEnabled by viewModel.biometricEnabled.collectAsState()
    val hasStoredSession by viewModel.hasStoredSession.collectAsState()
    val shouldOfferEnrollment by viewModel.shouldOfferBiometricEnrollment.collectAsState()

    // 2. Local form state
    var serverUrl by remember(savedUrl) {
        mutableStateOf(if (savedUrl.isNotBlank()) savedUrl else "https://")
    }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val activity = context.findActivity()

    // 3. Register biometric availability once we have the activity
    LaunchedEffect(activity) {
        if (activity != null) {
            viewModel.checkBiometricAvailability(
                BiometricHelper(activity).isBiometricAvailable()
            )
        }
    }

    // 4. Auto-prompt on cold start when a session + biometrics are already set up
    var autoPromptAttempted by remember { mutableStateOf(false) }

    LaunchedEffect(activity) {
        Log.d("BiometricTest", "LoginScreen composed. Activity is: $activity")

        if (activity != null) {
            val result = BiometricManager.from(activity)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)

            Log.d("BiometricTest", "Hardware Check Result Code: $result")

            viewModel.checkBiometricAvailability(result == BiometricManager.BIOMETRIC_SUCCESS)
        }
    }
    LaunchedEffect(activity, biometricAvailable, biometricEnabled, hasStoredSession) {
        if (!autoPromptAttempted &&
            activity != null &&
            biometricAvailable &&
            biometricEnabled &&
            hasStoredSession
        ) {
            autoPromptAttempted = true
            BiometricHelper(activity).authenticate(
                onSuccess = { viewModel.onBiometricSuccess(onLoginSuccess) },
                onError = { msg -> viewModel.onBiometricError(msg) },
                onFailed = { /* user can retry from the same prompt */ }
            )
        }
    }

    // 5. Helper to trigger the biometric prompt on demand
    val performBiometricLogin = {
        if (activity != null && biometricAvailable && hasStoredSession) {
            BiometricHelper(activity).authenticate(
                onSuccess = { viewModel.onBiometricSuccess(onLoginSuccess) },
                onError = { msg -> viewModel.onBiometricError(msg) },
                onFailed = { /* no-op; prompt stays open */ }
            )
        }
    }

    // 6. Helper to trigger password login
    val performLogin = {
        keyboardController?.hide()
        if (serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank() && !isLoading) {
            viewModel.saveUrlAndLogin(serverUrl, username, password, onLoginSuccess)
        }
    }

    // 7. Enrollment dialog: shown once after the first successful password login
    if (shouldOfferEnrollment) {
        AlertDialog(
            onDismissRequest = { viewModel.handleBiometricEnrollmentChoice(false) },
            title = { Text("Enable fingerprint login?") },
            text = {
                Text("Use your fingerprint to sign in faster next time. " +
                        "You can change this later in settings.")
            },
            confirmButton = {
                TextButton(onClick = { viewModel.handleBiometricEnrollmentChoice(true) }) {
                    Text("Enable")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.handleBiometricEnrollmentChoice(false) }) {
                    Text("Not now")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Welcome to Picci's",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(32.dp))

        // 1. Server URL Field
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("Server URL") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedLabelColor = MaterialTheme.colorScheme.primary,
                unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                cursorColor = MaterialTheme.colorScheme.primary
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 2. Username Field
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedLabelColor = MaterialTheme.colorScheme.primary,
                unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                cursorColor = MaterialTheme.colorScheme.primary
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 3. Password Field
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { performLogin() }),
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedLabelColor = MaterialTheme.colorScheme.primary,
                unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                cursorColor = MaterialTheme.colorScheme.primary
            )
        )

        if (error != null) {
            Text(
                text = error!!,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // 4. Login Button
        Button(
            onClick = { performLogin() },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            enabled = serverUrl.isNotBlank() && username.isNotBlank() &&
                    password.isNotBlank() && !isLoading,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text("Login", style = MaterialTheme.typography.titleMedium)
            }
        }
// ADD THIS TEMPORARY DEBUG TEXT
        Text(
            text = "Debug - Avail: $biometricAvailable | Enabled: $biometricEnabled | Session: $hasStoredSession",
            color = Color.Red,
            style = MaterialTheme.typography.bodySmall
        )
        // 5. Fingerprint button — visible only when everything is ready
        if (biometricAvailable && biometricEnabled && hasStoredSession) {
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = { performBiometricLogin() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                enabled = !isLoading
            ) {
                Icon(
                    imageVector = Icons.Default.Fingerprint,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Login with fingerprint", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}


fun Context.findActivity(): FragmentActivity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is FragmentActivity) return context
        context = context.baseContext
    }
    return null
}