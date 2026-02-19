package com.simplogics.chat.ui.framework.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val LinkUpColorScheme = darkColorScheme()

@Composable
fun LinkUpTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LinkUpColorScheme,
        content = content,
    )
}
