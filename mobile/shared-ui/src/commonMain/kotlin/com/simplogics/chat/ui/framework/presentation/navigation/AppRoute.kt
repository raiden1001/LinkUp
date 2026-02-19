package com.simplogics.chat.ui.framework.presentation.navigation

import kotlinx.serialization.Serializable

@Serializable
sealed interface AppRoute {
    @Serializable
    data object FrameworkHome : AppRoute
}
