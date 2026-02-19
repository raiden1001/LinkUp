package com.simplogics.chat.ui.framework.presentation.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.simplogics.chat.ui.framework.presentation.state.FrameworkUiState

@Composable
fun FrameworkHomeScreen(
    state: FrameworkUiState,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = state.title,
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = state.subtitle,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
