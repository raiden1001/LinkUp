package com.simplogics.chat.ui.framework.data.repository

import com.simplogics.chat.ui.framework.domain.model.UiResult
import com.simplogics.chat.ui.framework.domain.repository.FrameworkRepository

class FrameworkRepositoryImpl : FrameworkRepository {
    override suspend fun fetchBootstrapMessage(): UiResult<String> {
        return UiResult.Success("Data -> Domain -> Presentation wiring complete.")
    }
}
