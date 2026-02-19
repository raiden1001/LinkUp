# LinkUp UI Framework Checklist

This module now contains a framework-only UI scaffold with strict `Data -> Domain -> Presentation` layering.

## 1) Project Configuration & Tooling

- [ ] Kotlin 2.3+ (current repo version is unchanged to avoid breaking existing modules)
- [x] Compose Multiplatform shared UI module
- [ ] KSP per-android/per-ios target wiring (left for DB processor integration phase)
- [ ] SKIE integration (left for iOS interoperability phase)

## 2) Core Architecture (Shared Module)

- [x] Layering: `framework/data`, `framework/domain`, `framework/presentation`
- [x] Koin DI initialization in `commonMain`
- [x] ViewModel from `androidx.lifecycle.ViewModel` in `commonMain`
- [x] Type-safe serializable route model in `AppRoute`

## 3) Data & Networking

- [x] Ktor client with `ContentNegotiation + kotlinx.serialization`
- [x] UI-safe error wrapper (`UiResult`)
- [ ] Room KMP native driver (placeholder scaffold only)
- [ ] Coil 3 image cache integration (placeholder scaffold only)

## 4) UI & Presentation

- [x] `MutableStateFlow` private, public `StateFlow`
- [x] `collectAsStateWithLifecycle()` usage in root app composable
- [x] Centralized Material3 theme in `LinkUpTheme`
- [ ] Shared resources via MOKO/Compose Resources (currently placeholder constants)

## 5) Testing & Quality

- [ ] Business logic tests in `commonTest`
- [ ] Coroutine testing with `StandardTestDispatcher`
- [ ] Compose UI tests for shared components
