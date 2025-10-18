# Agent Implementation Guide

## Repository Orientation
- **Primary module**: `app/` contains all Android/Kotlin sources. Entry point is `MainActivity.kt` which binds view model, WebView shell, billing, and speech.
- **State & events**: `MainActivityViewModel` in `app/src/main/java/.../MainActivityViewModel.kt` owns `MainUiState` and a channel of `UiEvent`s that drive UI side effects. Compose UI reads state while the activity consumes events.
- **Web integration**: `WebAppInterface` (same package) bridges JavaScript events from the Lumo web app into strongly typed `WebEvent`s dispatched to the view model.
- **Billing layer**: `BillingManagerWrapper` initializes `BillingManager` (Google Play Billing), handles Play Services availability checks, and exposes observable billing state to the UI.
- **Payment UI**: `payment/` package contains Compose dialogs (`PaymentDialog`, `PaymentProcessingScreen`) and data models for plan presentation.
- **Speech**: `speech/` package wraps `SpeechRecognizer`, permissions, and lifecycle via `SpeechRecognitionManager` controlled by the view model.
- **Utilities**: `utils/isHostReachable.kt` performs TCP reachability checks for the initial offline/online decision. Static assets live in `app/src/main/assets/`.

## Coding Conventions
- Kotlin sources follow Jetpack Compose idioms: prefer immutable state holders (`StateFlow`, `ImmutableList`), and route side effects through the `UiEvent` channel.
- When adding interfaces or providers, define them in `app/src/main/java/.../billing/` adjacent to `BillingManager`. Keep JVM interop classes (`@JavascriptInterface`) in the `web` package.
- Compose UI files group previews at the bottom; remember to annotate with `@Preview` and guard preview-only helpers with `@VisibleForTesting` if reused.
- Prefer sealed interfaces for event/result modeling (`WebEvent`, `UiEvent`). Use exhaustive `when` with `else -> {}` forbidden.
- Follow existing logging style: `Log.d(TAG, "...")` with package-level `TAG` constants; avoid leaking PII.

## Implementation Playbook (aligns with README goals)
1. **Billing facade**
   - Create `BillingGateway` interface exposing the minimal surface needed by UI (`available`, flows for products/purchases, `launchBillingFlow`).
   - Move current Google Play implementation into `PlayBillingGateway` (wrap existing `BillingManager`).
   - Add `NoopBillingGateway` returning safe defaults and never throwing.
   - Introduce `BillingProvider` that attempts to instantiate `PlayBillingGateway` within a timeout; on failure, return `NoopBillingGateway`.
2. **Runtime decoupling from GMS**
   - Remove `GoogleApiAvailability` checks from `BillingManagerWrapper`. Instead, let `BillingProvider` decide by attempting BillingClient connection and falling back automatically.
   - Ensure all callers treat `BillingGateway.available` (or similar) as the single flag for showing purchase UI.
3. **UI gating**
   - Update `PaymentDialog`, `MainActivityViewModel`, and any entry points to observe the new gateway availability flag. Hide or replace upgrade buttons when unavailable, showing a friendly "Billing not available" message where appropriate.
4. **Gradle hygiene**
   - Keep `com.android.billingclient:billing-ktx` dependency in place, but structure code so the app runs gracefully even if billing cannot initialize.
   - When introducing flavors (`play`, `foss`) later, move billing dependencies and Play-specific code into the `play` source set; leave `NoopBillingGateway` in `main` or `foss`.
5. **Testing & validation**
   - Run `./gradlew :app:assembleDebug` to ensure compilation.
   - Execute `./gradlew :app:testDebugUnitTest` and `./gradlew :app:lint` when modified code touches logic/UI or Gradle.
   - For billing changes, smoke-test on devices/emulators with and without Play Services; verify no crashes and UI gating works (manual note).

## Agent Workflow Tips
- Use `rg` for code search (e.g., `rg "BillingManager" app/src`).
- Prefer `./gradlew` over `gradle` to honor wrapper settings.
- When editing multiple related files, stage commits logically: interface extraction, UI gating, Gradle tweaks.
- Reference README’s milestones for planning but implement iteratively; keep commits scoped and reversible.

