package com.elialombardi.piccistodo.ui.screens

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.SpaceDashboard
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.elialombardi.piccistodo.R
import com.elialombardi.piccistodo.data.local.BiometricHelper
import com.elialombardi.piccistodo.ui.components.WorkspaceHero
import com.elialombardi.piccistodo.ui.viewmodels.AuthViewModel


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
    var serverUrl by rememberSaveable { mutableStateOf("https://") }
    LaunchedEffect(savedUrl) { if (serverUrl == "https://" && savedUrl.isNotBlank()) serverUrl = savedUrl }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var enrollmentPending by rememberSaveable { mutableStateOf(false) }

    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val activity = context.findActivity()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshSession() }

    // 3. Register biometric availability once we have the activity
    LaunchedEffect(activity) {
        if (activity != null) {
            viewModel.checkBiometricAvailability(
                BiometricHelper(activity).isBiometricAvailable()
            )
        }
    }

    // 4. Auto-prompt on cold start when a session + biometrics are already set up
    var autoPromptAttempted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(activity, biometricAvailable, biometricEnabled, hasStoredSession) {
        if (!autoPromptAttempted &&
            activity != null &&
            biometricAvailable &&
            biometricEnabled &&
            hasStoredSession
        ) {
            autoPromptAttempted = true
            viewModel.requestBiometricLogin {
                BiometricHelper(activity).authenticate(
                    onSuccess = { viewModel.onBiometricSuccess(onLoginSuccess) },
                    onError = { msg -> viewModel.onBiometricError(msg) },
                    onFailed = { /* user can retry from the same prompt */ }
                )
            }
        }
    }

    // 5. Helper to trigger the biometric prompt on demand
    val performBiometricLogin = {
        if (activity != null && biometricAvailable && biometricEnabled && hasStoredSession) {
            viewModel.requestBiometricLogin {
                BiometricHelper(activity).authenticate(
                    onSuccess = { viewModel.onBiometricSuccess(onLoginSuccess) },
                    onError = { msg -> viewModel.onBiometricError(msg) },
                    onFailed = { /* no-op; prompt stays open */ }
                )
            }
        }
    }

    // 6. Helper to trigger password login
    val performLogin = {
        keyboardController?.hide()
        if (serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank() && !isLoading) {
            viewModel.saveUrlAndLogin(serverUrl, username, password) {
                if (viewModel.shouldOfferBiometricEnrollment.value) enrollmentPending = true
                else onLoginSuccess()
            }
        }
    }

    // 7. Enrollment dialog: shown once after the first successful password login
    if (shouldOfferEnrollment && enrollmentPending) {
        AlertDialog(
            onDismissRequest = { viewModel.handleBiometricEnrollmentChoice(false, onLoginSuccess) },
            title = { Text(stringResource(R.string.ui_biometric_title)) },
            text = {
                Text(stringResource(R.string.ui_biometric_enroll))
            },
            confirmButton = {
                TextButton(onClick = { viewModel.handleBiometricEnrollmentChoice(true, onLoginSuccess) }) {
                    Text(stringResource(R.string.ui_enable))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.handleBiometricEnrollmentChoice(false, onLoginSuccess) }) {
                    Text(stringResource(R.string.ui_not_now))
                }
            }
        )
    }

    LoginContent(serverUrl, username, password, isLoading, error,
        biometricAvailable && biometricEnabled && hasStoredSession,
        { serverUrl = it }, { username = it }, { password = it }, performLogin, performBiometricLogin)
}

@Composable
internal fun LoginContent(server: String, username: String, password: String, loading: Boolean, error: String?,
                          biometric: Boolean, onServer: (String) -> Unit, onUsername: (String) -> Unit,
                          onPassword: (String) -> Unit, onLogin: () -> Unit, onBiometric: () -> Unit) {
    var showPassword by rememberSaveable { mutableStateOf(false) }
    Scaffold { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Text(stringResource(R.string.ui_brand), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.ui_sign_in), style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.ui_sign_in_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(server, onServer, Modifier.fillMaxWidth(), enabled = !loading, singleLine = true,
                            label = { Text(stringResource(R.string.ui_server)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next))
                        OutlinedTextField(username, onUsername, Modifier.fillMaxWidth(), enabled = !loading, singleLine = true,
                            label = { Text(stringResource(R.string.ui_username)) }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
                        OutlinedTextField(password, onPassword, Modifier.fillMaxWidth(), enabled = !loading, singleLine = true,
                            label = { Text(stringResource(R.string.ui_password)) },
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = { IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, stringResource(if (showPassword) R.string.ui_hide_password else R.string.ui_show_password))
                            } },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { onLogin() }))
                        if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
                        Button(onClick = onLogin, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = !loading && server.isNotBlank() && username.isNotBlank() && password.isNotBlank()) {
                            if (loading) CircularProgressIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                            else Text(stringResource(R.string.ui_sign_in))
                        }
                        if (biometric) OutlinedButton(onClick = onBiometric, enabled = !loading, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                            Icon(Icons.Default.Fingerprint, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.ui_biometric_login))
                        }
                        Text(stringResource(R.string.ui_secure_connection), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
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