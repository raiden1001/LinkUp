# LinkUp UI Framework Architecture Guide

This document defines how UI code must be written in the LinkUp KMP project, using strict layering and predictable file placement.

Target platforms: Android and iOS (shared UI-first approach).

---

## 1. Goals

- Keep business logic and UI logic testable and platform-agnostic.
- Avoid feature code being spread randomly across modules.
- Use one directional flow: `Data -> Domain -> Presentation`.
- Make onboarding easy by giving exact "write code here" rules.

---

## 2. Current Module Entry Points

Primary shared UI module:

- `mobile/shared-ui/src/commonMain/kotlin/com/simplogics/chat/ui/App.kt`

Current framework scaffold root:

- `mobile/shared-ui/src/commonMain/kotlin/com/simplogics/chat/ui/framework/`

Android launcher:

- `mobile/androidApp/src/main/kotlin/com/simplogics/chat/android/MainActivity.kt`

---

## 3. Layered Architecture (Strict)

### 3.1 Data Layer (`framework/data`)

Purpose:

- External inputs/outputs (network, local DB, image/cache adapters, DTO mapping).
- No Compose UI rendering.
- No direct dependency on screen composables.

Current locations:

- `framework/data/remote/NetworkClientFactory.kt`
- `framework/data/local/LocalDatabaseDriver.kt`
- `framework/data/local/RoomKmpDatabasePlaceholder.kt`
- `framework/data/image/CoilImageLoaderPlaceholder.kt`
- `framework/data/repository/FrameworkRepositoryImpl.kt`

Rules:

- Repositories in Data implement interfaces from Domain.
- Ktor, Room, Coil, SQL, serialization DTOs stay in Data.
- Convert remote/local response models into domain models before returning.

### 3.2 Domain Layer (`framework/domain`)

Purpose:

- Business rules and use-cases.
- Pure contracts and decision logic.
- No Android/iOS framework calls.

Current locations:

- `framework/domain/repository/FrameworkRepository.kt`
- `framework/domain/usecase/GetFrameworkBootstrapUseCase.kt`
- `framework/domain/model/UiResult.kt`

Rules:

- Domain owns repository interfaces.
- Use-cases should be small and focused (one intent per class).
- Domain models must not depend on Compose, Ktor, Room, or platform classes.

### 3.3 Presentation Layer (`framework/presentation`)

Purpose:

- Screen state, UI state transitions, route models, and composables.

Current locations:

- `framework/presentation/navigation/AppRoute.kt`
- `framework/presentation/state/FrameworkUiState.kt`
- `framework/presentation/viewmodel/FrameworkRootViewModel.kt`
- `framework/presentation/theme/LinkUpTheme.kt`
- `framework/presentation/resources/FrameworkStrings.kt`
- `framework/presentation/screen/FrameworkHomeScreen.kt`

Rules:

- ViewModels are the only layer that orchestrates use-cases for UI.
- Composables read immutable state and emit events.
- Composables do not call repositories directly.
- Keep `MutableStateFlow` private and expose only `StateFlow`.

---

## 4. Dependency Injection Structure

DI entry point:

- `framework/di/KoinModules.kt`

Responsibilities:

- Register Data implementations.
- Register Domain use-cases.
- Register Presentation ViewModels.
- Initialize Koin once (`initKoin()`), currently triggered in `App.kt`.

Rules:

- All cross-layer wiring happens in DI.
- Never instantiate repositories/use-cases directly inside composables.

---

## 5. Navigation Strategy

Navigation model:

- Type-safe routes in `framework/presentation/navigation/AppRoute.kt`.
- Route classes are `@Serializable`.

Rules:

- Add new route types inside `AppRoute`.
- Route parameters must be serializable primitives/value objects.
- Keep navigation decisions in ViewModel/state, not hard-coded inside UI widgets.

---

## 6. State Management Rules

Required pattern:

- `private val _uiState = MutableStateFlow(...)`
- `val uiState: StateFlow<...> = _uiState.asStateFlow()`

UI collection:

- `collectAsStateWithLifecycle()` from Compose lifecycle runtime.

Rules:

- No mutable state directly exposed to Composables.
- UI state classes should be immutable data classes.
- For async operations, update loading/error/success in a single state stream.

---

## 7. Where To Write Specific Code

### 7.1 When adding a new feature screen (example: Profile)

Create under `framework/`:

1. **Domain**
   - `domain/model/Profile.kt`
   - `domain/repository/ProfileRepository.kt`
   - `domain/usecase/GetProfileUseCase.kt`
   - `domain/usecase/UpdateProfileUseCase.kt`

2. **Data**
   - `data/remote/ProfileApi.kt` (Ktor calls)
   - `data/local/ProfileDao.kt` (Room abstraction)
   - `data/repository/ProfileRepositoryImpl.kt`
   - optional mapper files in `data/mapper/`

3. **Presentation**
   - `presentation/state/ProfileUiState.kt`
   - `presentation/viewmodel/ProfileViewModel.kt`
   - `presentation/screen/ProfileScreen.kt`
   - add route to `presentation/navigation/AppRoute.kt`

4. **DI**
   - register repository/use-cases/viewmodel in `framework/di/KoinModules.kt`

### 7.2 When adding a reusable UI component

Place in:

- `framework/presentation/component/`

Examples:

- `PrimaryButton.kt`
- `AvatarImage.kt`
- `LoadingState.kt`

Rules:

- Components receive plain state + callbacks.
- Avoid hidden dependencies (no direct Koin injection in leaf components).

### 7.3 When adding theme tokens

Place in:

- `framework/presentation/theme/`

Suggested files:

- `Color.kt`
- `Typography.kt`
- `Shape.kt`
- `LinkUpTheme.kt`

### 7.4 When adding shared text/images

Current placeholder:

- `framework/presentation/resources/FrameworkStrings.kt`

Planned production move:

- Compose Multiplatform Resources or MOKO resources in shared source sets.

---

## 8. Platform-Specific Placement

Use these source sets for platform-only code:

- `mobile/shared-ui/src/androidMain/...`
- `mobile/shared-ui/src/iosMain/...` (when iOS target wiring is enabled)

Write platform code only when required:

- Android-specific permissions, intents, notifications.
- iOS-specific wrappers/interops.

Do not put platform-specific classes in `commonMain`.

---

## 9. Error Handling Contract

Current shared wrapper:

- `framework/domain/model/UiResult.kt`

Recommended usage:

- Data catches transport/storage exceptions and maps to `UiResult.Error`.
- Domain use-cases return `UiResult`.
- ViewModels translate `UiResult` into user-facing state.

---

## 10. Networking, DB, and Images (Implementation Plan)

### Networking

- Use Ktor client from `data/remote/NetworkClientFactory.kt`.
- Keep endpoint-specific clients in `data/remote/*Api.kt`.

### Database

- Replace `RoomKmpDatabasePlaceholder` with actual Room KMP setup.
- Keep DAOs/entities in Data layer only.

### Image Loading

- Replace `CoilImageLoaderPlaceholder` with Coil 3 config.
- Keep cache/image-loader configuration in Data layer.

---

## 11. Testing Placement

### Unit tests (Domain + ViewModel)

- `mobile/shared-ui/src/commonTest/...`

Suggested packages:

- `framework/domain/usecase/...Test.kt`
- `framework/presentation/viewmodel/...Test.kt`

### UI tests (shared composables)

- `mobile/shared-ui/src/commonTest/...` for logic-like composable tests
- platform UI integration tests under platform test source sets as needed

Guidelines:

- Use `kotlin.test`.
- Use `StandardTestDispatcher` for deterministic coroutine tests.
- Test ViewModel state transitions for loading/success/error paths.

---

## 12. Coding Rules (Must Follow)

- No feature logic directly inside `App.kt`.
- No repository calls from composables.
- No mutable state exposed publicly from ViewModels.
- No platform imports in Domain.
- No DTOs leaking into Presentation state.
- Every new feature must include:
  - route entry
  - UI state model
  - ViewModel
  - at least one use-case
  - DI registration

---

## 13. Suggested Package Template For New Features

Use this structure for each feature namespace:

- `framework/domain/model/<Feature>Model.kt`
- `framework/domain/repository/<Feature>Repository.kt`
- `framework/domain/usecase/<Action>UseCase.kt`
- `framework/data/repository/<Feature>RepositoryImpl.kt`
- `framework/presentation/state/<Feature>UiState.kt`
- `framework/presentation/viewmodel/<Feature>ViewModel.kt`
- `framework/presentation/screen/<Feature>Screen.kt`

---

## 14. Current Status Snapshot

Checklist reference:

- `mobile/shared-ui/UI_FRAMEWORK_CHECKLIST.md`

This architecture guide is the coding contract. The checklist tracks rollout completion.
