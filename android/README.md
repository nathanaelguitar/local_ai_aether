# CanopyChat — Android

Native Kotlin/Jetpack Compose port of the iOS app in `../iphone`. The domain logic
(models, SQLite memory store with full-text recall, ChatML prompt builder, web-search
intent detection and ranking, title generation, loop detection) is a direct port of the
Swift code, file for file:

| Android | Ported from (iphone/AetherChat) |
| --- | --- |
| `core/Models.kt` | `Models.swift`, `Theme.swift`, `AetherModelCatalog.swift` |
| `core/MemoryStore.kt`, `core/MemoryPlanner.kt` | `AetherMemoryStore.swift` |
| `core/PromptBuilder.kt` | `AetherOnDeviceClient.swift` (AetherPromptBuilder) |
| `core/WebSearch.kt` | `AetherWebSearchService.swift` |
| `core/TitleGenerator.kt` | `Models.swift` (AetherTitleGenerator) |
| `inference/InferenceEngine.kt` | `AetherBackendClient.swift` + `AetherOnDeviceClient.swift` |
| `AppState.kt` | `Models.swift` (AppState + loop detection) |
| `ui/*` | `WelcomeView.swift`, `ConversationListView.swift`, `ChatView.swift`, `SettingsView.swift`, `OakBackground.swift` |

## Build

The Gradle wrapper (8.10.2) is checked in. With the Android SDK installed (Homebrew:
`brew install --cask android-commandlinetools`, then
`sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"`) and a
`local.properties` pointing at it (`sdk.dir=/opt/homebrew/share/android-commandlinetools`):

```sh
cd android
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Install on a phone with USB debugging enabled: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

## Testing chat on a physical device

The Backend provider needs an OpenAI-compatible server reachable from the phone:

1. On the Mac: `gradle -p ../backend run` (Ktor proxy on port 8787), with the phone on
   the same Wi-Fi network.
2. In the app: Settings → Backend endpoint → `http://<your-Mac-LAN-IP>:8787` → Save.
   (Find the IP with `ipconfig getifaddr en0`.)

Cleartext HTTP is enabled in the manifest for exactly this development flow; scope it
with a networkSecurityConfig before any Play Store release.

## Inference status

- **Backend provider (works today):** talks to any OpenAI-compatible endpoint, including
  the Ktor proxy in `../backend`. From the Android emulator, the host machine's
  `127.0.0.1` is reachable as `http://10.0.2.2:8787`.
- **On-device llama.cpp (integration point ready):** `inference/InferenceEngine.kt`
  defines `LlamaCppEngine` with the exact steps to wire in the official
  `llama.cpp/examples/llama.android` JNI binding (build `libllama.so` with the NDK,
  then implement `generate()` against the ChatML prompt from `PromptBuilder`).
  `ModelStore` already handles the Hugging Face GGUF download/cache, mirroring iOS.

## Not yet ported

- Attachments (camera/photos/files) — model fields exist and round-trip through SQLite;
  the Compose input bar doesn't offer pickers yet.
- Location-aware search-query localization (`AetherLocationService.swift`) — the
  COARSE_LOCATION permission is declared; port uses Android `Geocoder` when added.
- Subscription gating — the iOS app uses StoreKit; the Android equivalent is Google Play
  Billing (`com.android.billingclient:billing-ktx`). The app is intentionally ungated
  until the Play Console products exist.
- Local "reply ready" notifications (POST_NOTIFICATIONS is declared for it).
