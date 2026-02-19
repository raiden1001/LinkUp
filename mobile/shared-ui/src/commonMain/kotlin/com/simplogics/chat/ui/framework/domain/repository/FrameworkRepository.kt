package com.simplogics.chat.ui.framework.domain.repository

import com.simplogics.chat.ui.framework.domain.model.UiResult

interface FrameworkRepository {
    suspend fun fetchBootstrapMessage(): UiResult<String>
}
