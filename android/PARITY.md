# Swift → Kotlin Parity Manifest

Tracks every symbol in `iphone/AetherChat/*.swift` against its Android counterpart.
Status: `done` · `missing` · `partial` · `substitute` (platform API differs, feature ported) · `n/a`

Baseline at start of port: iOS 6,953 Swift lines vs Android 3,447 Kotlin lines.

---

> **Round 2 update (2026-07-26).** A full re-audit found this manifest had overclaimed:
> iOS had shipped ~3,400 more Swift lines than the round-1 baseline (private model
> delivery, Recently Deleted, contributor telemetry, feedback loop, streaming preview,
> UI polish), and several round-1 rows were wrong — see **Round 2 corrections** and
> **Round 2 ports** at the bottom of this file. Build-host note: the app now also
> builds on aarch64 Linux hosts with `-Pcanopy.skipNative` (Google ships no NDK for
> linux-arm64); that APK lacks `libcanopy_llama.so`, so on-device inference reports
> itself unavailable and the app offers the Backend provider. Backend-side
> requirements live in `docs/ANDROID_BACKEND_REQUIREMENTS.md`.

---

## AetherModelCatalog.swift → core/Models.kt

| Swift symbol | Kotlin target | Status |
|---|---|---|
| `InferenceProvider` (onDevice/backend) | `InferenceProvider` | done |
| `aetherV1DisplayName` | `CANOPY_V1_DISPLAY_NAME` | done |
| `legacyAetherV1DisplayName` ("Aether V1") | `LEGACY_DISPLAY_NAME` | done |
| `aetherV1ContextTokens` = 12288 | `CONTEXT_TOKENS` (+ CONTEXT_SIZE in canopy_llama.cpp) | done |
| `aetherV1MaxOutputTokens` = 768 | `MAX_OUTPUT_TOKENS` | done |
| `aetherV1ImageMaxTokens` = 768 | `IMAGE_MAX_TOKENS` | done |
| `aetherV1BatchTokens` | `BATCH_TOKENS` | done |
| download URLs | `ggufDownloadUrl` / `mmprojDownloadUrl` | done |
| `aetherV1RuntimeMessage` | `RUNTIME_MESSAGE` | done |

## Theme.swift → core/Models.kt + ui/Theme.kt

| Swift symbol | Kotlin target | Status |
|---|---|---|
| `AetherColors` | `OakColors` | done |
| `Color(hex:)` | Compose `Color(android.graphics.Color.parseColor)` | done |
| `Workspace` + 4 built-ins + `custom(name:)` | `Workspace` | done |
| `AssistantPersona` + 4 built-ins | `AssistantPersona` | done |

## Models.swift → AppState.kt / core/*

| Swift symbol | Kotlin target | Status |
|---|---|---|
| `AetherNotifications` (local notif on background reply) | `CanopyNotifications` | substitute (NotificationManager + POST_NOTIFICATIONS runtime grant) |
| `AetherBackgroundTask` (bg execution window) | viewModelScope Job + `appIsActive` | substitute (Android coroutines survive backgrounding; no UIApplication task handle needed) |
| `Conversation` / `ChatMessage` / `ChatAttachment` / `MessageRole` | `core/Models.kt` | done |
| `AppState.conversations` | `_conversations` | done |
| `AppState.isDarkTheme` | `isDarkTheme` | done |
| `AppState.apiEndpoint` | `apiEndpoint` | done |
| `AppState.selectedModel` (+ legacy-name migration) | `selectedModel` / `setSelectedModel` | done |
| `AppState.inferenceProvider` | `inferenceProvider` | done |
| `AppState.modelLoadingMessage` | `modelLoadingMessage` | done |
| `AppState.generationStatusMessage` | `generationStatus` | done |
| `AppState.appIsActive` | `appIsActive` (driven by Activity onStart/onStop) | done |
| `AppState.defaultWorkspace` | `defaultWorkspaceId` | done |
| `AppState.messageFontScale` | `messageFontScale` | done |
| `AppState.customSystemPrompt` | `customSystemPrompt` | done |
| `AppState.customPersonas` / `customWorkspaces` | same | done |
| `togglePin` / `delete` / `createConversation` / `renameConversation` | same | done |
| `createCustomPersona` / `updateCustomPersona` / `deleteCustomPersona` | same | done |
| `createWorkspace` / `deleteWorkspace` | `createCustomWorkspace` / `deleteCustomWorkspace` | done |
| `sendMessage` | `sendMessage` | done |
| `editUserMessage` | `editUserMessage` | done |
| `regenerateLastResponse` | `regenerateLastResponse` | done |
| `stopSending` (task cancel) | `stopSending` (cancels `replyJob`) | done |
| `generateAndAppendReply` | inline in `sendMessage` | done |
| `offlineWebContext` + one-shot notice dedupe | `offlineWebContext` + `offlineWebNoticeShownConversationIds` | done |
| `generateReply` | inline | done |
| `responseWithSources` | `responseWithSources` | done |
| `runtimeSystemPrompt` | `PromptBuilder` | done |
| `memoryContext` | `MemoryPlanner.memoryContext` | done |
| `refreshMemorySummary` | inlined into `updateConversation` transforms | done |
| `persistConversation` / `persistAllConversations` | `updateConversation` | done |
| `removeLegacySeedConversations` | `removeLegacySeedConversations` | done |
| `redirectedSystemPrompt` | `LoopDetector.redirectedSystemPrompt` | done |
| `compactRecoverySystemPrompt` | `LoopDetector.compactRecoverySystemPrompt` | done |
| `isLoopingResponse` / `isRepeatedAssistantResponse` / `hasInternalRepetition` / `normalized` / `similarity` | `LoopDetector` | done |
| `fallbackRedirectResponse` | `LoopDetector` | done |
| `appendAssistantMessage` | same | done |
| `inferenceErrorDescription` | `inferenceErrorDescription` | done |
| `localBackgroundInterruptionMessage` | same | done |
| `notifyIfNeeded` | same | done |
| `attachmentPreview` | `attachmentPreview` | done |
| `AetherTitleGenerator` | `TitleGenerator` | done |
| `sampleConversations` | `sampleConversations()` | done |

## AetherMemoryStore.swift → core/MemoryStore.kt + core/MemoryPlanner.kt

| Swift symbol | Kotlin target | Status |
|---|---|---|
| `AetherMemoryHit` | `MemoryHit` | done |
| `AetherMemoryPlanner.summary` / `.memoryContext` / `.compact` / `.score` | `MemoryPlanner` | done |
| `loadConversations` / `saveConversation` / `deleteConversation` / `saveAll` | same | done |
| `relevantMessages` (FTS5) | same | done |

## AetherBackendClient.swift → inference/InferenceEngine.kt

| Swift symbol | Kotlin target | Status |
|---|---|---|
| `send` (OpenAI chat completions) | `BackendInferenceEngine.send` | done |
| `chatURL(from:)` normalization | `chatUrl` | done |
| `makeMessages` (system/persona/memory/web) | `requestMessages` | done |
| `requestContent` (multimodal image parts) | `requestContent` | done |
| `AetherBackendError` cases | exceptions | done |

## AetherWebSearchService.swift → core/WebSearch.kt

| Swift symbol | Kotlin target | Status |
|---|---|---|
| `AetherWebSearchResult` / `AetherWebCitation` | `WebSearchResult` / `WebCitation` | done |
| `search` (r.jina.ai) | `search` | done |
| `directDuckDuckGoSearch` (tried first) + `duckDuckGoLiteDocuments` + `cleanHTML` | same | done |
| document ranking / scoring (incl. `score(for:)` query-aware boosts + 11 sports domains) | `rankDocuments` / `SearchDocument.score(query)` | done |
| `AetherWebSearchIntent` | `WebSearchIntent` | done |
| offline context | `offlineContext` | done |

## AetherLocationService.swift → core/AndroidServices.kt

| Swift symbol | Kotlin target | Status |
|---|---|---|
| `localizeSearchQuery` / reverse geocode / permission | `CanopyLocationService` | done (substitute: CLLocationManager → LocationManager) |

## AetherNetworkMonitor.swift → core/AndroidServices.kt

| Swift symbol | Kotlin target | Status |
|---|---|---|
| `AetherNetworkMonitor` | `CanopyNetworkMonitor` | done (substitute: NWPathMonitor → ConnectivityManager) |

## CanopyFeedback.swift → core/AndroidServices.kt

| Swift symbol | Kotlin target | Status |
|---|---|---|
| `CanopyLegal` | `CanopyLegal` | done |
| `CanopyFeedback.modelFeedback` / `.appIssue` | `CanopyFeedback` | done |

## CanopySubscriptionManager.swift → core/Subscription.kt

| Swift symbol | Kotlin target | Status |
|---|---|---|
| `refresh` / `purchaseMonthly` / `purchaseYearly` / `restorePurchases` / `canRedeemTestAccessCode` | same | done (substitute: StoreKit 2 → Play Billing) |
| `redeemTestAccessCode` / `resetTestAccess` | same | done |
| `observeTransactionUpdates` | `PurchasesUpdatedListener` | done |

## AetherOnDeviceClient.swift → inference/InferenceEngine.kt + cpp/canopy_llama.cpp

| Swift symbol | Kotlin target | Status |
|---|---|---|
| `preload` | — | n/a — dead code on iOS (no callers) |
| `send` w/ degradation levels | `LlamaCppEngine.send` | done |
| `AetherModelStore` download+cache | `ModelStore` | done |
| `AetherPromptBuilder` | `PromptBuilder` | done |
| `AetherLlamaEngine` (C interop) | `canopy_llama.cpp` JNI | done |
| `AetherOnDeviceError` messages | exceptions | done |
| `stripLeakedSystemContext` / `clean` | `canopy_llama.cpp` | done |

## ChatView.swift → ui/Screens.kt (+ new files)

| Swift symbol | Kotlin target | Status |
|---|---|---|
| `ChatView` | `ChatScreen` | done |
| `send` / `stopSending` / `regenerate` / `editPrompt` | `ChatScreen` + `AppState` | done |
| `ModelLoadingOverlay` | `ModelLoadingOverlay` | done |
| `MessageBubble` (+ action row) | `MessageBubble` / `MessageActionButton` | done |
| `PromptEditDraft` / `PromptEditorSheet` | `editDraft` + `PromptEditorSheet` | done |
| `MarkdownMessageText` | `MarkdownMessageText` (+ hand-rolled inline bold/italic/code/link) | done |
| `MarkdownBlock` / `MarkdownBlockParser` (heading/bullet/numbered/code/table) | `core/Markdown.kt` | done |
| `MarkdownSource` / `MarkdownSourceParser` / `SourceChipsView` | `core/Markdown.kt` + `SourceChips` | done |
| `CodeBlockView` (+ copy) | `CodeBlockView` | done |
| `SharePayload` / `AetherShare` / `ActivityView` | `CanopyShare` | substitute (ACTION_SEND / ACTION_SENDTO) |
| `SpeechPlaybackState` / `AetherSpeechController` | `CanopySpeechController` | substitute (TextToSpeech). **Note: no UI entry point on iOS either — the controller is instantiated but no button starts playback. Parity preserved deliberately.** |
| `ChatAttachmentLoader` | `ChatAttachmentLoader` | done (PDF text extraction unavailable — see note below) |
| `AetherImageNormalizer` | `ImageNormalizer` | done |
| `MessageAttachmentThumbnail` | same | done |
| `AttachmentTray` / `AttachmentTrayItem` | `AttachmentTray` | done |
| `CameraCaptureView` (UIImagePickerController) | `TakePicturePreview` contract | substitute |
| `InputBar` (+ stop button) | inline in `ChatScreen` | done |
| `TypingIndicator` | `TypingIndicator` | done |
| `ChatEmptyState` | `ChatEmptyState` | done |
| `RoundedCornerShape` | Compose `RoundedCornerShape` | done |

## ConversationListView.swift → ui/Screens.kt

| Swift symbol | Kotlin target | Status |
|---|---|---|
| `ConversationListView` + pinned/recent split | `ConversationListScreen` | done |
| `WorkspaceChip` / `SectionHeader` | same | done |
| `ConversationRow` + context menu (pin/rename/delete) | `ConversationRow` + `DropdownMenu` + `RenameConversationDialog` | done |
| `relativeDate` | `relativeDate` | done |
| `EmptyGrove` | `EmptyGrove` | done |

## SettingsView.swift → ui/Screens.kt

| Swift symbol | Kotlin target | Status |
|---|---|---|
| `SettingsView` (6 sections) | `SettingsScreen` | done |
| `SettingsSection` / `SettingsSwitchRow` / `SettingsNavRow` / `SettingsInfoRow` / `SettingsRowLabel` | Compose equivalents | done |
| `FontSizeSettingsRow` | `FontSizeSettingsRow` | done |
| `WorkspacePickerRow` | `WorkspacePickerRow` | done |
| `ApiConfigSheet` | — | n/a — dead code on iOS (nothing references it) |
| `ModelConfigSheet` | — | n/a — dead code on iOS (nothing references it) |
| `SystemPreferencesSheet` | `SystemPreferencesSheet` | done |
| `NewChatSheet` | `NewChatDialog` | done |
| `AssistantEditorSheet` | `AssistantEditorSheet` | done |
| `AssistantPickerRow` | `AssistantPickerRow` | done |

## WelcomeView.swift / ContentView.swift / OakBackground.swift / PaywallView.swift

| Swift symbol | Kotlin target | Status |
|---|---|---|
| `WelcomeView` / `FeatureRow` | `WelcomeScreen` / `FeatureRow` | done |
| `ContentView` (root routing) | `CanopyNavHost` | done |
| `OakBackground` / `OakPalette` / `OakInteriorCanvas` (incl. knots + growth rings) | `OakBackground` | done |
| `PaywallView` / `PaywallFeature` (incl. yearly/monthly, nested Testing Options, detail text) | `PaywallScreen` / `PaywallFeature` | done |

---

## Known limitations and intentional divergences

1. **PDF text extraction.** iOS uses PDFKit (`PDFDocument.page(at:).string`) to pull text out
   of attached PDFs and feed it to the prompt. The Android platform SDK has no equivalent —
   `PdfRenderer` rasterizes pages and does not expose text. Attached PDFs therefore carry a
   placeholder explaining the limitation instead of silently contributing nothing. Closing this
   needs a third-party dependency (PdfBox-Android or iText).

2. **Speech playback has no UI entry point — on either platform.** `AetherSpeechController`
   is instantiated in iOS's `MessageBubble` and stopped in `onDisappear`, but no button ever
   calls `speak`. `CanopySpeechController` mirrors this exactly. Wiring a play button is a
   product decision, not a port gap.

3. **`TextToSpeech` has no true pause/resume.** `PAUSED` stops playback and remembers the
   utterance; `resume()` restarts the current utterance from its beginning rather than
   mid-word. `AVSpeechSynthesizer` resumes mid-utterance.

4. **Share URL.** iOS reads `AETHER_APP_STORE_URL` from Info.plist. `CanopyShare.PLAY_STORE_URL`
   is empty until the Play listing exists; shares omit the trailing link, matching iOS's
   behavior when the key is absent.

5. **Dead code not ported.** `ApiConfigSheet`, `ModelConfigSheet` and `AetherOnDeviceClient.preload`
   exist in the Swift sources but nothing references them. They were deliberately not ported —
   porting them would add unreachable Android UI, not parity.

6. **Swipe actions → explicit buttons.** iOS uses `swipeActions` for deleting workspaces and
   editing/deleting assistants, and `contextMenu` for conversation pin/delete. Compose gets
   explicit Delete/Edit buttons and a long-press `DropdownMenu` respectively — same actions,
   idiomatic affordance.

7. **`appIsActive` semantics.** iOS pairs this with `UIApplication.beginBackgroundTask` to buy
   execution time. Android coroutines in `viewModelScope` are not suspended on backgrounding, so
   the flag only gates the reply notification.

---

## Round 2 corrections (rows that were wrong above)

| Round 1 claim | Reality / current state |
|---|---|
| `removeLegacySeedConversations` → done | **Data-loss bug**: Android deleted seed-titled chats at launch while iOS had disabled that routine. Now mirrors iOS: `migrateStockConversationsIfNeeded()` ports the non-destructive migration; the deletion routine is kept but never called (AppState.kt). |
| `aetherV1MaxOutputTokens` = 768 | iOS is now **1024** (`AetherModelCatalog.swift:13`); Android `MAX_OUTPUT_TOKENS` updated to match. |
| Model file `Qwen3.5-2b-Kimi-and-Opus-Distillation.Q4_K_M.gguf` | iOS now ships `canopy-1.1.2.Q4_K_M.gguf` from the private repo `nathanaelguitar/canopy-1.1.2`, version **1.1.2**; `ModelCatalog` resynced. |
| `TypingIndicator` → done | Was 3 bouncing gray dots. Now the real port: breathing amber radial dot, shimmer text sweep, 5 rotating composing phrases (ChatComponents.kt). |
| `ModelLoadingOverlay` → done | Was a generic spinner ring. Now ports `WoodlandWalkScene` (walking sprout, swaying tree, celebration leaf). |
| `ChatEmptyState` / `EmptyGrove` → done | Were emoji + plain text. Now leaf-badge circle + serif titles + subtitle/filled capsule per iOS. |
| PDF limitation (Known limitations #1) | Stale: `AttachmentLoader` ships PdfBox-Android extraction. |
| "nested Testing Options" on paywall | Invented; iOS has a single "Have a test code?" toggle. Paywall rewritten (below). |
| `MemoryStore` FTS4 + recency | Now FTS5 + `bm25()` ordering with recency fallback; DB v2 migration rebuilds the index from `messages` (MemoryStore.kt). |
| Dark theme | Material components ignored the oak palette/dark mode. `CanopyTheme` (ui/Theme.kt) now supplies light+dark oak schemes to all Material3 components. |
| `AetherModelDelivery.swift` not in manifest | Fully ported (see below) — it is the only supported production download path on iOS. |

## Round 2 ports (new sections)

### AetherModelDelivery.swift → core/ModelDelivery.kt + ModelStore rework

| Swift symbol | Kotlin target | Status |
|---|---|---|
| `AetherModelDeliveryError` | `ModelDeliveryError` | done |
| `CanopyModelManifest` (flat+nested tolerant decode, validation, malformed-host reject) | `CanopyModelManifest.parse/validated` | done |
| Info.plist endpoint config (`AETHER_MODEL_*`) | `BuildConfig.MODEL_*_ENDPOINT` (gradle `CANOPY_*` overrides) | done |
| `AetherModelDeliveryKeychain` (ThisDeviceOnly) | `DeliveryCredentialStore` (EncryptedSharedPreferences, Keystore-backed, plain-prefs fallback) | done |
| `AetherBuildChannel` | `CanopyBuildChannel` (gradle `CANOPY_BUILD_CHANNEL`) | done |
| `AetherActiveModelVersion` | `ActiveModelVersion` (contributor-gated; Settings shows "Canopy V1 · vX") | done |
| `AetherCachedPrivateModel` + 12h refresh + URL-free record | `CachedPrivateModel` + `PrivateModelDelivery` (`active-private-model.json`) | done |
| `AetherPrivateModelDelivery` (register, manifest, 401/403 re-auth retry, telemetry token) | `PrivateModelDelivery` | done |
| `AetherRangeFileDownloader` (resume, 3 attempts, URL refresh, 4xx abort) | `ModelStore.downloadResumable` + retry loop | done |
| SHA-256 + size verify, `receipt.json`, atomic promote, verified-cache reads | `ModelStore.verifyDownloadedFile` / `isVerifiedCachedFile` | done |
| Offline fallback "Using downloaded Canopy X" | `ModelStore.localModelFiles` | done |
| Versioned layout `Models/<id>/<version>/` + `safePathComponent` | same | done |
| `excludeFromBackup` for model files | `res/xml/backup_rules.xml` + manifest `fullBackupContent` | done |
| Legacy public-HF fallback when delivery unconfigured | preserved | done |
| Unit tests (AetherModelDeliveryTests, 5) | `ModelDeliveryTest` (8 tests) | done |

### Recently Deleted (Models.swift + SettingsView.swift + ConversationListView.swift)

| Swift symbol | Kotlin target | Status |
|---|---|---|
| `DeletedConversation`, `recentlyDeleted`, `deletedRetentionDays = 30` | `DeletedConversation`, `recentlyDeleted`, `DELETED_RETENTION_DAYS` | done |
| `restoreDeleted` / `permanentlyDeleteConversation` / `emptyRecentlyDeleted` / `purgeExpiredDeletedConversations` | same | done |
| `RecentlyDeletedConversations.json` persistence | same (filesDir, org.json) | done |
| Soft-delete in `delete(_:)` | `deleteConversation` | done |
| `RecentlyDeletedView` + `RecentlyDeletedRow` + Empty-all confirmation | `RecentlyDeletedScreen` + `RecentlyDeletedRow` | done |
| Settings "Chats > Recently Deleted" row | SettingsScreen "Chats" section | done |
| Undo-delete toast (4.5 s) | ConversationListScreen `softDelete` + AnimatedVisibility toast | done |
| Sample-conversation resync (5 iOS samples, staggered timestamps) | `sampleConversations()` | done |

### Feedback loop + contributor telemetry (CanopyFeedback.swift + Contributor/)

| Swift symbol | Kotlin target | Status |
|---|---|---|
| Thumbs rating with fill state | MessageBubble ThumbUp/ThumbDown (filled/outlined swap) | done |
| Negative-rating delayed alert (180 ms) with 3 actions | AlertDialog + `CorrectionEditorSheet` | done |
| `ContributorCorrectionSheet` | `CorrectionEditorSheet` | done |
| User-message resend action | MessageBubble onResend → `editUserMessage` | done |
| `AetherBetaTelemetry` (queue, 2% SHA-256 control sample, failure/harness selection, 24h batch deadline, retry w/ backoff, receipt-gated deletion, consent-withdrawal wipe, 48h prune, 2000 cap) | `core/BetaTelemetry.kt` | done |
| `AetherContributorBatch` wire format (schema_version, batch_id, installation_id, sent_at, consent flag) | `TelemetryEvent.toWireJson` + batch builder | done |
| `AetherFeedbackRating`, `AetherTelemetryEventType` | `TelemetryEventType` | done |
| `CanopyContributorProgram` (disclosure ack, join, stopContributing, disclosure text) | `ContributorProgram` | done |
| `ContributorConsentOverlay` + first-launch gating | MainActivity overlay + Welcome→Enter gate | done |
| Settings "Beta Program" section | SettingsScreen (contributor only) | done |
| Telemetry call sites (generated/rated/regenerated/resent/correction/webSearchPerformed/inferenceFailed, flush on active) | AppState record hooks + call sites | done |
| `CanopyFeedback.modelFeedback/appIssue` exact templates (USER PROMPT, sections, support@canopychat.app) | AndroidServices.kt | done |
| `AETHER_BETA_TELEMETRY_ENDPOINT` | `BuildConfig.BETA_TELEMETRY_ENDPOINT` (gradle override) | done |

### UI/UX polish

| iOS source | Android target | Status |
|---|---|---|
| Streaming preview (`streamingPreview` + `StreamingBubble`, amber ● cursor) | AppState.streamingPreview + JNI token callback (`canopy_llama.cpp`, UTF-8-safe pieces) + StreamingBubble | done (needs NDK rebuild of the .so) |
| `AetherResponseNormalizer` (LaTeX→text, code-fence aware) | `core/ResponseNormalizer.kt`, applied post-generation | done |
| WelcomeView staggered entrance, gradient badge, tinted tiles, gradient CTA, production copy | WelcomeScreen + `visibleAlpha` | done |
| InputBar capsule (gradient stroke, gradient send w/ scale spring, keyboard chevron, gradient backdrop, attach popover + Web Search toggle) | ChatScreen input bar rewrite | done |
| `webSearchEnabled` binding toggle | AppState.webSearchEnabled + generation gate | done |
| Chat header: share-conversation + new-chat frosted capsule, tappable title → rename w/ Clear | ChatScreen header + RenameConversationDialog Clear | done |
| Scroll: anchor trailing assistant reply to top; scroll-dismiss keyboard | ChatScreen LaunchedEffect ports | done |
| ContentView paywall hard gate after Welcome | CanopyNavHost (hasPremium branches) | done |
| PaywallView plan picker (cards, radio, BEST VALUE badge, gradient CTA), header branding, error card + Try Again | PaywallScreen rewrite | done |
| Welcome→List + paywall transitions (0.4 s) | Crossfade in CanopyNavHost | done |
| Settings "Accuracy & Safety" (production) | SettingsScreen | done |
| Reply notification app icon | ic_canopy_tree small icon | done |
| Article-evidence web grounding (`enrichDocuments`/`fetchArticleEvidence`/`relevantPassages`, sports rules) | core/WebSearch.kt | done |
| Location triggers (full list incl. Spanish, fresh fix via requestSingleUpdate) | AndroidServices.kt | done |

### Intentional round-2 divergences

- `Device:`/`Android:` labels in feedback emails replace `Device:`/`iOS:` (platform equivalents).
- Consent overlay is a dialog rather than a full-screen ZStack layer (same actions and copy).
- iOS `webSearchSuggestion` is dead code upstream (`suggestedWebQuery = nil`) — not ported, matching iOS behavior.
- Build/channel/endpoint configuration comes from gradle properties (`CANOPY_*`) instead of Info.plist keys, with identical production defaults.

## Remaining backend-dependent items

See `docs/ANDROID_BACKEND_REQUIREMENTS.md`: the delivery Worker endpoints Android now
consumes, the contributor batches endpoint, and the NDK rebuild needed to ship
`libcanopy_llama.so` (streaming callback included) from an x86_64 build host.

---

## Verification

Both platforms build clean. The Android app was installed on the `canopy_test` emulator
(Pixel 7, API 35) and driven through: welcome → conversation list → chat → settings →
new-chat dialog → long-press context menu. No crashes; `CanopyLlama: Native llama.cpp
runtime loaded` confirms the JNI layer initializes on-device.

Not exercised on the emulator: an actual on-device generation (needs the ~1.7 GB model
download) and a real Play Billing purchase.
