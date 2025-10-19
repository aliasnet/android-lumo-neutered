## Recent Update
- Replaced the Play Store-specific billing-unavailable copy across locales with a generic message shared by Compose dialogs and WebView-triggered toasts.
- Notified users via `UiEvent.ShowToast` when the WebView requests billing while the gateway is unavailable, and added JVM coverage to lock in the new behavior.

## Suggested Next Task
- Backfill instrumentation/UI coverage for the billing unavailable dialog once Android SDK access is restored, or document the manual validation steps if automation remains blocked.
