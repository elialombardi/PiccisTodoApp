package com.elialombardi.piccistodo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun WorkspaceHero(title: String, subtitle: String, icon: ImageVector, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = colors.primaryContainer, contentColor = colors.onPrimaryContainer) {
        Column(Modifier.background(Brush.linearGradient(listOf(colors.primaryContainer, colors.secondaryContainer))).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(16.dp), color = colors.surface.copy(alpha = 0.65f)) {
                Icon(icon, contentDescription = null, modifier = Modifier.padding(12.dp).size(28.dp), tint = colors.primary)
            }
            Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            Text(subtitle, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
fun WorkspaceEmptyState(title: String, description: String, icon: ImageVector, action: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action()
    }
}
