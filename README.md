# CanopyChat

Private, local-first AI chat with an eco-friendly oak identity.

## Modules

- **`iphone/`** — the shipping SwiftUI iOS app (XcodeGen project; run `xcodegen generate`
  inside `iphone/`, then build the `AetherChat` scheme). App Store readiness notes live in
  `APP_STORE_RELEASE_CHECKLIST.md`.
- **`android/`** — native Kotlin/Jetpack Compose port of the same app. See
  `android/README.md` for build steps and the on-device-inference integration status.
- **`backend/`** — optional Kotlin/Ktor OpenAI-compatible proxy used by the "Backend"
  inference provider on both platforms.

The public website (privacy policy, terms, support) lives in the separate
[`canopy_publicsite`](https://github.com/nathanaelguitar/canopy_publicsite) repo and is
served at https://nathanaelguitar.github.io/canopy_publicsite/.
