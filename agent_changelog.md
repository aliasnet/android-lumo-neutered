## Recent Update
- Replaced the Play Store-specific billing-unavailable copy across locales with a generic message shared by Compose dialogs and WebView-triggered toasts.
- Added toast-based surfacing for WebView purchase requests while billing is disabled and refreshed JVM coverage so Compose and Web surfaces assert the shared copy.

## Outstanding Issues
- Instrumentation/UI tests are still missing for the billing-unavailable surfaces because the container lacks an Android SDK; CI covers JVM, but device-parity remains manual.
- Gradle assemble, targeted unit tests, and lint still fail locally for the same SDK-location reason noted in previous turns, so verification depends on external environments.

## Suggested Next Task (Turn 11)
- Stand up instrumentation or UI tests that exercise the billing-unavailable dialog/Toast once an SDK-enabled environment is accessible, or alternatively capture a detailed manual QA script that downstream contributors can follow until automation is viable.
- Document the dependency on the GitHub Actions workflow (or another SDK-capable runner) for regression coverage so future turns can trigger the tests once they exist.
