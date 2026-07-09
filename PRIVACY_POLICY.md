# CanopyChat Privacy Policy

**Effective date:** July 6, 2026

CanopyChat is a local-first AI chat app for iPhone. This policy describes what the app does with your information.

## Summary

CanopyChat does not have user accounts, does not collect analytics, does not use advertising or tracking, and does not send your conversations to any server operated by us. The developer does not collect any data from the app.

## Conversations and memory

Your conversations, attachments, assistant settings, and conversation memory are stored only on your device (in the app's private storage, using a local SQLite database). They are never uploaded by the app. Deleting a conversation deletes it from your device. Deleting the app deletes all app data.

## On-device AI model

The AI model runs entirely on your iPhone. On first use, the app downloads the model files (about 1.7 GB) over HTTPS from Hugging Face (huggingface.co) and caches them on your device. This download does not include any of your personal data; it is a plain file download. Hugging Face's own privacy policy applies to that network request (for example, your IP address, as with any web request).

## Web search

If you ask a question that needs current information (or explicitly ask the app to search), CanopyChat sends only the search query text over HTTPS to a public search service (DuckDuckGo, retrieved through the r.jina.ai reader service) to fetch results. The query is not linked to any account or identifier by the app. If you never trigger a search, no query is sent.

## Location

If you ask for nearby places, weather, or other local results, CanopyChat asks for while-in-use location permission. Your location is used on-device only, to convert your coordinates into a city/region name that is added to the search query (for example, "coffee near Austin, TX"). Precise coordinates are not sent to any service other than Apple's geocoding service, and the app never stores your location. If you deny permission, local queries still work without your location.

## Camera, photos, and files

Photos you take, images you attach, and files you import are used only to build the message you send to the on-device model, and are stored only inside your local conversation history.

## Notifications

CanopyChat can send local notifications on your device when a reply finishes while the app is in the background. These are generated on-device; the app does not use remote push notifications.

## Subscriptions

CanopyChat Plus is an auto-renewable subscription processed entirely by Apple through your Apple ID. The app never sees your payment details. Manage or cancel the subscription in Settings → Apple ID → Subscriptions.

## Children

CanopyChat is not directed at children under 13.

## Changes

If this policy changes, the updated version will be posted at this URL with a new effective date.

## Contact

Questions or requests: consulting.nathanael@gmail.com
