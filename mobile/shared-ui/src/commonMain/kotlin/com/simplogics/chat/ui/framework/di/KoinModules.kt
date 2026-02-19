package com.simplogics.chat.ui.framework.di

import com.simplogics.chat.ui.framework.data.local.LocalDatabaseDriver
import com.simplogics.chat.ui.framework.data.local.RoomKmpDatabasePlaceholder
import com.simplogics.chat.ui.framework.data.remote.NetworkClientFactory
import com.simplogics.chat.ui.framework.data.repository.FrameworkRepositoryImpl
import com.simplogics.chat.ui.framework.domain.repository.FrameworkRepository
import com.simplogics.chat.ui.framework.domain.usecase.GetFrameworkBootstrapUseCase
import com.simplogics.chat.ui.framework.presentation.viewmodel.FrameworkRootViewModel
import org.koin.core.KoinApplication
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.dsl.module

private val frameworkModule = module {
    single { NetworkClientFactory.create() }
    single<LocalDatabaseDriver> { RoomKmpDatabasePlaceholder() }
    single<FrameworkRepository> { FrameworkRepositoryImpl() }
    factory { GetFrameworkBootstrapUseCase(get()) }
    single { FrameworkRootViewModel(get()) }
}

private var koinApp: KoinApplication? = null

fun initKoin() {
    if (GlobalContext.getOrNull() != null) return
    koinApp = startKoin {
        modules(frameworkModule)
    }
}
