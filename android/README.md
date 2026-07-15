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
sdkmanager "ndk;26.3.11579264" "cmake;3.22.1"
git submodule update --init --recursive
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
- **On-device llama.cpp:** `inference/InferenceEngine.kt` uses the official llama.cpp
  submodule and Android CMake target to build `libcanopy_llama.so` for arm64-v8a and
  x86_64. The JNI wrapper loads GGUF models, generates text, and uses llama.cpp mtmd for
  image attachments. `ModelStore` downloads and caches both the language model and vision
  projector.

## Product setup still required

- Play Console subscription products must exist before Google Play prices or purchases
  appear in the Plus surface. The app remains ungated until those products are configured.
- Local "reply ready" notifications are not yet enabled.
