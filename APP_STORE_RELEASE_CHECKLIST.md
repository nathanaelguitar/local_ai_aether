# CanopyChat App Store Release Checklist

## Payments

- Use StoreKit/In-App Purchase for the in-app $4.99/month digital subscription.
- Current product ID expected by the app: `com.nathanaelguitar.canopychat.monthly`.
- Create an auto-renewable subscription in App Store Connect with this exact product ID.
- Put it in a subscription group such as `CanopyChat Plus`.
- Set price tier to USD $4.99/month.
- Add localized subscription display name, description, privacy policy URL, and terms of use URL.
- Test purchase, restore, cancellation, and expired-subscription states with StoreKit testing or Sandbox.
- Local/TestFlight testing can use the sandbox-only test access code `CANOPY-TEST` from the paywall. This does not unlock production App Store receipts.

Do not add Stripe or Apple Pay buttons inside the iOS app for unlocking digital AI access unless the flow is intentionally designed for Apple’s external-purchase rules. For App Store review, StoreKit is the safest path.

## App Metadata

- App name: `CanopyChat`.
- Subtitle: focus on private on-device AI and eco-friendly intelligence.
- Category: Productivity or Utilities.
- Age rating: complete honestly based on AI-generated content and web access.
- Support URL: required.
- Temporary support email for TestFlight and early review: `consulting.nathanael@gmail.com`.
- Privacy policy URL: `https://nathanaelguitar.github.io/canopy_publicsite/privacy.html`.
- Terms of use URL: `https://nathanaelguitar.github.io/canopy_publicsite/terms.html`.
- Support URL: `https://nathanaelguitar.github.io/canopy_publicsite/support.html`.
- Marketing URL: `https://nathanaelguitar.github.io/canopy_publicsite/`.
- Add review notes explaining that the model downloads on first use and that location is requested only for nearby/weather/local queries.

## Privacy

- Complete App Privacy nutrition labels in App Store Connect.
- Disclose any data collected by web search or backend services if backend mode is shipped.
- If the app remains local-first, state that conversations run on device by default.
- Verify permission copy in `Info.plist` for camera, photos, and location.
- Avoid claiming “no data leaves device” if web search, location lookup, model downloads, or backend inference remain available.

## Build Readiness

- Archive with Release configuration on a real device target.
- Bump `MARKETING_VERSION` and `CURRENT_PROJECT_VERSION` for every App Store upload.
- Verify app icon assets and launch screen.
- Confirm the app name appears as `CanopyChat` on the Home Screen.
- Run through first launch, subscription purchase, restore, model download, chat, attachments, location search, and dark mode.
- Test airplane mode behavior and first-launch model download failure messaging.

## Review Risk Areas

- Payment: digital subscription access must use StoreKit in-app purchase.
- AI output: add disclaimers in metadata if needed; avoid medical/legal/financial guarantees.
- Web search: ensure sources are shown clearly and prompt/system text is never surfaced.
- Location: request permission only when the user asks for nearby/weather/local results.
- Large model download: explain the download and cache behavior before it starts.

## Later Stripe Option

Stripe can be used for a web account, enterprise billing, or other flows that do not violate App Store digital-goods purchase rules. If added, the app needs server-side entitlement syncing so a Stripe subscriber can be recognized on-device without exposing secret keys in the app.
