# LinkUp UI Framework and UI Flow (End-to-End)

This document is the implementation guide for the shared UI stack in LinkUp.
It explains:

- the architecture boundaries
- the runtime flow of UI data and events
- where each type of code must be written
- how to add new screens/features without breaking architecture rules

This is intentionally practical for daily development and code reviews.

---

## 1) Scope and module map

Primary shared UI module:

- `mobile/shared-ui`

App entry points:

- Android entry activity: `mobile/androidApp/src/main/kotlin/com/simplogics/chat/android/MainActivity.kt`
- Shared root composable: `mobile/shared-ui/src/commonMain/kotlin/com/simplogics/chat/ui/App.kt`

Framework root package:

- `mobile/shared-ui/src/commonMain/kotlin/com/simplogics/chat/ui/framework`

Related docs:

- Architecture contract: `docs/ui-framework-architecture.md`
- Rollout checklist: `mobile/shared-ui/UI_FRAMEWORK_CHECKLIST.md`

---

## 2) Core architecture (strict layers)

The shared UI architecture is always:

`Data -> Domain -> Presentation`

### Data layer responsibilities

Location:

- `framework/data/**`

Contains:

- Network clients and API adapters
- Local persistence adapters
- Image/cache integration points
- Repository implementations

Current files:

- `data/remote/NetworkClientFactory.kt`
- `data/local/LocalDatabaseDriver.kt`
- `data/local/RoomKmpDatabasePlaceholder.kt`
- `data/image/CoilImageLoaderPlaceholder.kt`
- `data/repository/FrameworkRepositoryImpl.kt`

Rules:

- Data can depend on Ktor/Room/Coil.
- Data cannot depend on Composable screens.
- Data must return domain-safe models/results.

### Domain layer responsibilities

Location:

- `framework/domain/**`

Contains:

- Business contracts (repository interfaces)
- Use-cases
- Shared result/error abstractions

Current files:

- `domain/repository/FrameworkRepository.kt`
- `domain/usecase/GetFrameworkBootstrapUseCase.kt`
- `domain/model/UiResult.kt`

Rules:

- Domain owns interfaces; Data implements them.
- Domain has no Android/iOS UI imports.
- One use-case should represent one business action.

### Presentation layer responsibilities

Location:

- `framework/presentation/**`

Contains:

- Routes/navigation models
- ViewModels and UI state
- Composable screens/components
- Theme/resources glue

Current files:

- `presentation/navigation/AppRoute.kt`
- `presentation/state/FrameworkUiState.kt`
- `presentation/viewmodel/FrameworkRootViewModel.kt`
- `presentation/screen/FrameworkHomeScreen.kt`
- `presentation/theme/LinkUpTheme.kt`
- `presentation/resources/FrameworkStrings.kt`

Rules:

- Composables render state + raise events.
- ViewModels call use-cases and update state.
- Repositories are never called directly from Composables.

---

## 3) Current runtime UI flow

This is the active execution flow at runtime today.

1. `MainActivity` calls shared `App()`.
2. `App()` runs `initKoin()` once.
3. `App()` resolves `FrameworkRootViewModel` via Koin.
4. `App()` observes `uiState` with `collectAsStateWithLifecycle()`.
5. Route switch runs (`AppRoute`).
6. Matching screen composable renders with immutable state.
7. ViewModel init loads bootstrap data through use-case.
8. Use-case calls repository interface.
9. Data repository returns `UiResult`.
10. ViewModel maps result into `FrameworkUiState`.
11. StateFlow emits -> Compose recomposes screen.

---

## 4) UI event flow pattern (for all new features)

Every feature follows the same event loop:

1. User action in UI (tap/type/scroll intent)
2. Composable invokes ViewModel event function
3. ViewModel validates/transforms input
4. ViewModel triggers one use-case
5. Use-case requests repository contract
6. Data implementation calls network/db/cache
7. Data returns domain-safe result (`UiResult`)
8. ViewModel updates state (`_uiState.update { ... }`)
9. UI observes new state and re-renders

Hard rule: data should never flow backward directly into Composable without ViewModel/state mediation.

---

## 5) Navigation flow model

Navigation is route-driven and type-safe.

Current route model:

- `presentation/navigation/AppRoute.kt`

Guidelines:

- Add each screen as a dedicated route type.
- Keep route payloads serializable (`@Serializable`).
- Route transitions should come from ViewModel state updates.
- `App()` acts as route renderer/switchboard only.

Example route expansion:

- `AppRoute.Login`
- `AppRoute.ConversationList`
- `AppRoute.ConversationDetail(conversationId: String)`

---

## 6) State architecture rules

Required ViewModel state pattern:

- private mutable: `MutableStateFlow`
- public immutable: `StateFlow`
- emit state snapshots using `.update`

UI collection standard:

- use `collectAsStateWithLifecycle()` from lifecycle compose runtime

State shape recommendations:

- Keep state immutable data class
- Include loading, data, and user-visible error
- Include one-time effect channel only when needed (snackbar/nav effect patterns)

---

## 7) Dependency injection flow (Koin)

DI entry:

- `framework/di/KoinModules.kt`

Wiring order:

1. Register Data implementations
2. Register Use-cases
3. Register ViewModels
4. Initialize once from `App()`

Rules:

- Do not construct repository/use-case in Composable.
- Keep all constructor dependencies explicit.
- Add platform modules later for Android/iOS specific services.

---

## 8) Exact "where to write what"

Use this map during implementation and reviews.

### New API call

Write in:

- `data/remote/<Feature>Api.kt`
- update `data/repository/<Feature>RepositoryImpl.kt`
- no API code in ViewModel/Composable

### New persistence operation

Write in:

- `data/local/<Feature>Dao.kt` or local datasource
- repository impl merges local + remote logic

### New business rule

Write in:

- `domain/usecase/<Action>UseCase.kt`

### New screen

Write in:

- `presentation/state/<Screen>UiState.kt`
- `presentation/viewmodel/<Screen>ViewModel.kt`
- `presentation/screen/<Screen>Screen.kt`
- add route in `presentation/navigation/AppRoute.kt`
- register dependencies in `di/KoinModules.kt`

### Shared component

Write in:

- `presentation/component/<Component>.kt`

### Theme tokens

Write in:

- `presentation/theme/Color.kt`
- `presentation/theme/Typography.kt`
- `presentation/theme/Shape.kt`
- `presentation/theme/LinkUpTheme.kt`

### Shared strings/images

Current temporary location:

- `presentation/resources/FrameworkStrings.kt`

Target production location:

- Compose Multiplatform Resources or MOKO resource module

---

## 9) Feature development blueprint

When creating a feature (example: Profile), add files in this order:

1. Domain contract:
   - `domain/repository/ProfileRepository.kt`
2. Domain use-cases:
   - `domain/usecase/GetProfileUseCase.kt`
   - `domain/usecase/UpdateProfileUseCase.kt`
3. Data implementation:
   - `data/repository/ProfileRepositoryImpl.kt`
   - `data/remote/ProfileApi.kt`
   - `data/local/ProfileDao.kt` (if offline needed)
4. Presentation:
   - `presentation/state/ProfileUiState.kt`
   - `presentation/viewmodel/ProfileViewModel.kt`
   - `presentation/screen/ProfileScreen.kt`
5. Navigation:
   - add `AppRoute.Profile(...)`
6. DI:
   - register repo/use-cases/viewmodel
7. Tests:
   - use-case and ViewModel tests first

---

## 10) Error and loading flow standard

Use shared result wrapper:

- `domain/model/UiResult.kt`

Recommended mapping:

- Data exceptions -> `UiResult.Error(message, cause)`
- Success payload -> `UiResult.Success(data)`
- ViewModel maps to:
  - `isLoading = true/false`
  - `errorMessage = ...`
  - `content = ...`

Never show raw exception stack traces in UI state.

---

## 11) Platform split strategy

Write platform-agnostic logic in:

- `commonMain`

Write Android-specific code in:

- `androidMain`

Write iOS-specific code in:

- `iosMain` (when target wiring is enabled)

Examples of platform-only responsibilities:

- permission handlers
- notifications
- OS-level storage/keychain adapters

Domain layer must remain platform-free.

---

## 12) Quality gates for PRs (UI framework)

A feature PR should be considered complete when:

- It follows layer boundaries (`Data -> Domain -> Presentation`)
- ViewModel exposes immutable state only
- Navigation route is type-safe
- DI registration is complete
- `commonTest` covers use-case and ViewModel behavior
- UI renders from state and emits events only

---

## 13) Anti-patterns to reject

- Repository calls inside Composable functions
- Ktor/Room imports in `presentation/*`
- Android context usage in `domain/*`
- Mutable state objects exposed directly to UI
- DTOs leaking directly into UI state models
- Business logic placed in `App.kt` route switch

---

## 14) Migration status and next steps

Current status:

- Framework scaffold and flow are in place.
- Legacy feature UI implementation has been removed.
- Placeholder adapters exist for Room KMP and Coil integration.

Recommended next execution steps:

1. Add real feature route set (`Login`, `ConversationList`, `ConversationDetail`)
2. Replace placeholders with concrete Room KMP + Coil implementations
3. Introduce shared resources module (Compose resources/MOKO)
4. Add ViewModel/use-case test suite in `commonTest`
5. Enable iOS target + SKIE + KSP wiring when toolchain upgrade is scheduled

---

## 15) Quick reference checklist

- Root renderer: `ui/App.kt`
- DI setup: `framework/di/KoinModules.kt`
- Route model: `framework/presentation/navigation/AppRoute.kt`
- Root state: `framework/presentation/state/FrameworkUiState.kt`
- Root VM: `framework/presentation/viewmodel/FrameworkRootViewModel.kt`
- Root screen: `framework/presentation/screen/FrameworkHomeScreen.kt`
- Theme: `framework/presentation/theme/LinkUpTheme.kt`
- Result model: `framework/domain/model/UiResult.kt`

Use this file as the source of truth for UI flow and code placement decisions.
