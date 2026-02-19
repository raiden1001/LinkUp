package com.simplogics.chat.ui.framework.presentation.state

import com.simplogics.chat.ui.framework.presentation.navigation.AppRoute
import com.simplogics.chat.ui.framework.presentation.resources.FrameworkStrings

data class FrameworkUiState(
    val route: AppRoute = AppRoute.FrameworkHome,
    val title: String = FrameworkStrings.appName,
    val subtitle: String = FrameworkStrings.bootstrapSubtitle,
)
