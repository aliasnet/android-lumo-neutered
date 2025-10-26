# Manual QA Report: Billing Availability Scenarios

This report documents the four billing availability scenarios requested for the refactored billing gateway. Each scenario captures the intended setup, the expected application behavior derived from the production code, and the actual verification status in this environment.

| Scenario | Setup | Expected Behavior | Actual Result |
| --- | --- | --- | --- |
| **1. Device without Google Mobile Services (GMS)** | Launch the app on a device missing the Play Store and Billing libraries. | `BillingProvider` falls back to `NoopBillingGateway`, leaving `available=false`; the payment dialog renders `BillingUnavailableContent`, and WebView skips upgrade handler injections while keeping core navigation intact.【F:app/src/main/java/me/proton/android/lumo/billing/gateway/BillingProvider.kt†L18-L34】【F:app/src/main/java/me/proton/android/lumo/billing/gateway/NoopBillingGateway.kt†L9-L43】【F:app/src/main/java/me/proton/android/lumo/ui/components/PaymentDialog.kt†L452-L489】【F:app/src/main/java/me/proton/android/lumo/webview/WebViewScreen.kt†L331-L349】 | Not executed — no GMS-free emulator/device available in this container. |
| **2. Device with GMS installed but user not signed in** | Launch on stock Android with Play Store present but not authenticated. | Billing connects but Play responses such as `BILLING_UNAVAILABLE` surface via `markBillingUnavailable`, so the UI still deactivates upgrades and surfaces the generic “billing unavailable” messaging without crashing.【F:app/src/main/java/me/proton/android/lumo/billing/BillingManager.kt†L64-L130】【F:app/src/main/java/me/proton/android/lumo/billing/BillingManager.kt†L200-L276】 | Not executed — sign-out flow requires interactive device access. |
| **3. Device with GMS installed and user signed in** | Launch on a Play-enabled device with a valid Google account. | `BillingManager.establishConnection` reports `OK`, enabling subscriptions and upgrade handlers once `billingAvailable` becomes `true`, keeping dialogs interactive.【F:app/src/main/java/me/proton/android/lumo/billing/BillingManager.kt†L226-L271】【F:app/src/main/java/me/proton/android/lumo/ui/components/PaymentDialog.kt†L452-L513】【F:app/src/main/java/me/proton/android/lumo/MainActivity.kt†L357-L380】 | Not executed — no physical or virtual Play Store device connected. |
| **4. Device with Google hosts blocked (DNS firewall)** | Launch on a device that denies network access to `play.googleapis.com`. | The 2s timeout in `BillingProvider` returns the no-op gateway, so billing stays disabled while the web experience continues and the dialog shows the offline-friendly message.【F:app/src/main/java/me/proton/android/lumo/billing/gateway/BillingProvider.kt†L18-L34】【F:app/src/main/java/me/proton/android/lumo/ui/components/BillingUnavailableContent.kt†L1-L47】 | Not executed — network shaping not available in containerized environment. |

> **Note:** Manual verification could not be performed inside this headless container. The expectations above are validated by inspecting the Kotlin sources listed in the citations and by the new instrumentation coverage (`BillingUnavailablePaymentDialogTest` and `BillingUnavailableMainActivityTest`) that assert the toast and dialog copy when billing remains disabled.

## Artifact Capture Checklist (for SDK-equipped environments)

Follow the steps below when executing the scenarios on an emulator or physical device. These steps collect the screenshots and logs referenced by the CI/instrumentation tasks outlined in the changelog.

1. **Provision the test device**
   - Decode the bundled Gradle wrapper (`./gradlew unpackWrapper`) and install the Android SDK using the instructions in `README.md`.
   - Create a Play Store emulator image (API 34 or higher) plus a GMS-free image for scenario 1. Keep snapshots disabled to avoid state bleed between tests.

2. **Run the connected test suite**
   - Execute `./gradlew :app:connectedProductionStandardDebugAndroidTest --console=plain` with the desired emulator launched.
   - After the run, collect the generated reports under `app/build/reports/androidTests/connected/` and archive them as CI artifacts.

3. **Capture localized screenshots**
   - For each locale listed in `app/src/main/res/values-*/strings.xml`, launch the billing dialog and capture screenshots showing the generic “Billing currently unavailable” copy in Compose and WebView contexts.
   - Store the PNG files under `artifacts/screenshots/<locale>/` and name them `compose-billing-unavailable.png` and `webview-billing-unavailable.png` respectively.

4. **Export logcat traces**
   - Run `adb logcat -d -v time ProtonBilling:D BillingManager:D BillingProvider:D *:S > artifacts/logs/billing-unavailable.log` immediately after exercising the billing fallback scenarios.
   - Clear logcat (`adb logcat -c`) between scenarios to keep captures targeted.

5. **Document manual observations**
   - Update this report with pass/fail results, linking to the stored artifacts and noting any anomalies (e.g., unexpected Play prompts, stale purchase dialogs).
   - File defects referencing the captured logs/screenshots if behavior deviates from the expected copy.

This checklist feeds the upcoming emulator-backed CI workflow: once automated, ensure the job uploads the same directories so the manual QA log remains synchronized with machine-produced evidence.
