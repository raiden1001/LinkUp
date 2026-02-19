package com.simplogics.chat.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplogics.chat.ui.framework.di.initKoin
import com.simplogics.chat.ui.framework.presentation.navigation.AppRoute
import com.simplogics.chat.ui.framework.presentation.screen.FrameworkHomeScreen
import com.simplogics.chat.ui.framework.presentation.theme.LinkUpTheme
import com.simplogics.chat.ui.framework.presentation.viewmodel.FrameworkRootViewModel
import org.koin.compose.koinInject

@Suppress("RememberReturnType")
@Composable
fun App() {
    remember {
        initKoin()
    }

    val viewModel: FrameworkRootViewModel = koinInject()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LinkUpTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            when (state.route) {
                AppRoute.FrameworkHome -> FrameworkHomeScreen(state = state)
            }
        }
    }
}
