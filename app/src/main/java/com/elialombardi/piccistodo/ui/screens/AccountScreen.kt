package com.elialombardi.piccistodo.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.elialombardi.piccistodo.R
import com.elialombardi.piccistodo.data.local.BiometricHelper
import com.elialombardi.piccistodo.ui.components.WorkspaceHero
import com.elialombardi.piccistodo.ui.viewmodels.AccountViewModel

@Composable
fun AccountScreen(onSignedOut: () -> Unit, viewModel: AccountViewModel = hiltViewModel()) {
    val server by viewModel.server.collectAsState()
    val biometric by viewModel.biometric.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val failed by viewModel.failed.collectAsState()
    val activity = LocalContext.current.findActivity()
    val available = remember(activity) { activity?.let { BiometricHelper(it).isBiometricAvailable() } == true }
    AccountContent(server, biometric, busy, failed, available,
        onBiometric = { enabled ->
            if (!enabled) viewModel.biometrics(false)
            else activity?.let { BiometricHelper(it).authenticate(
                onSuccess = { viewModel.biometrics(true) }, onError = {}, onFailed = {}
            ) }
        }, onSignOut = { viewModel.signOut(onSignedOut) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountContent(server: String?, biometric: Boolean, busy: Boolean, failed: Boolean, available: Boolean,
                            onBiometric: (Boolean) -> Unit, onSignOut: () -> Unit) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_account)) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(stringResource(R.string.ui_account_workspace), style = MaterialTheme.typography.titleMedium)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.Dns, null, tint = MaterialTheme.colorScheme.primary)
                    Text(server ?: stringResource(R.string.ui_connection_missing), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.ui_account_connection_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(stringResource(R.string.ui_account_security), style = MaterialTheme.typography.titleMedium)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.Fingerprint, null, tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.ui_biometric_setting), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(if (available) R.string.ui_biometric_setting_description else R.string.ui_biometric_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = biometric, onCheckedChange = onBiometric, enabled = !busy && (available || biometric),
                        modifier = Modifier.semantics { role = Role.Switch },
                        thumbContent = { Icon(if (biometric) Icons.Default.Check else Icons.Default.Close, stringResource(R.string.ui_biometric_setting), Modifier.size(16.dp)) })
                }
            }
            Text(stringResource(R.string.ui_account_appearance), style = MaterialTheme.typography.titleMedium)
            ListItem(headlineContent = { Text(stringResource(R.string.ui_system_theme)) }, leadingContent = { Icon(Icons.Default.Contrast, null) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow))
            if (failed) Text(stringResource(R.string.ui_setting_error), color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { confirm = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Icon(Icons.AutoMirrored.Filled.Logout, null)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.ui_sign_out))
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        icon = { Icon(Icons.AutoMirrored.Filled.Logout, null) },
        title = { Text(stringResource(R.string.ui_sign_out_title)) },
        text = { Text(stringResource(R.string.ui_sign_out_description)) },
        confirmButton = { TextButton(onClick = { confirm = false; onSignOut() }) { Text(stringResource(R.string.ui_sign_out)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.tasks_cancel)) } })
}
