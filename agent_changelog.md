## Recent Update
- Added `.github/workflows/android-validation.yml` to automate Android SDK provisioning and run the assemble, unit test, and lint Gradle targets in CI.
- Documented the new workflow in the README validation section so contributors can cross-reference the automation.

## Suggested Next Task
- Remove the explicit Play Store package check in `app/src/main/java/me/proton/android/lumo/billing/BillingManager.kt` so the billing fallback path handles missing Play Services gracefully.
