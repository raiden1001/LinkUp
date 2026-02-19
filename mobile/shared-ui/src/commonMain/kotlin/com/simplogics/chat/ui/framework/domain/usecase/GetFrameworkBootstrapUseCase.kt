package com.simplogics.chat.ui.framework.domain.usecase

import com.simplogics.chat.ui.framework.domain.model.UiResult
import com.simplogics.chat.ui.framework.domain.repository.FrameworkRepository

class GetFrameworkBootstrapUseCase(
    private val repository: FrameworkRepository,
) {
    suspend operator fun invoke(): UiResult<String> = repository.fetchBootstrapMessage()
}
