## Android SMS Compliance Filter — App Specification

### Overview

Build a production-ready Android application in Kotlin that monitors incoming SMS messages and sends opt-out responses to stop messages from unknown senders. The app runs in the background reactively using a BroadcastReceiver and WorkManager (without a persistent background service) and cross-references incoming numbers in real-time against Google Contacts and HubSpot CRM (without storing any phone number data locally) before applying opt-out detection logic. If the message is from an unknown sender and contains opt-out language, the app should automatically reply to the sender with the appropriate one-word opt-out keyword ("stop" or "end") to unsubscribe. 

Target SDK: 35 (Android 15), Min SDK: 26 (Android 8.0). All code must be readable, editable, and buildable in Android Studio Quail 1 | 2026.1.1 Patch 2 or later. All files and public functions must use KDoc documentation. All files should have the BSD license and attribute the authorship to Bill Roth, bill.roth@gmail.com. 

The principal files should be saved in a git repo. The purpose of the git repo is to be able to backup the source code to GitHub and allow those who pull it down or close it to be able to recreate the app and build it on their own systems.

**Package:** `com.digiroth.smsfilter` — use this package name everywhere in the generated code.

---

### Architecture

Follow Google's official Guide to App Architecture, structuring the codebase with Unidirectional Data Flow (UDF) across these layers:

- **UI Layer**:
  - Jetpack Compose for all screens and composables.
  - Hilt-injected Jetpack ViewModels (`@HiltViewModel`) to act as state holders, manage UI logic, and expose UI State reactively via `StateFlow`.
- **Background Layer**:
  - Manifest-declared `BroadcastReceiver` and `WorkManager` for reactive background SMS monitoring and real-time lookups (no persistent background service or foreground service, except for the expedited worker's foreground fallback notification).
- **Data Layer**:
  - Local Data Sources: Room database for settings, stop list, opt-out patterns, and detection logs (no phone number data is stored locally), and `DataStore<Preferences>` for onboarding flags.
  - Remote Data Sources: Retrofit/OkHttp API services for HubSpot communication.
  - Repositories: Coordinate local and remote data sources, acting as the Single Source of Truth (SSOT) for the rest of the application.

Use Hilt for dependency injection. Use Kotlin Coroutines and asynchronous Flows throughout.

---

### Onboarding & First-Run Flow

On first launch (detected via a `firstRunComplete: Boolean` flag in `DataStore<Preferences>`), the app must walk the user through a mandatory setup wizard before the SMS service starts. The wizard uses a `NavHost` with these sequential steps:

**Step 1 — Welcome**
Brief explanation of what the app does. "Get Started" button advances to Step 2.

**Step 2 — Permissions**
Request all required permissions (see Permissions section). The user cannot advance until `RECEIVE_SMS`, `SEND_SMS`, and `POST_NOTIFICATIONS` (API 33+) are granted. Other permissions (such as `READ_CONTACTS` and `READ_SMS`) show a warning if denied but do not block advancement.

**Step 3 — HubSpot (optional)**
- The "Use HubSpot" toggle defaults to **off** during onboarding to minimize setup friction for non-HubSpot users.
- If the user turns the toggle **on**:
  - Show a descriptive card with a prominent **"Connect HubSpot Account"** button to start the connection. Do not launch the OAuth browser flow automatically on step arrival to avoid a jarring user transition.
  - If `BuildConfig.HUBSPOT_CLIENT_ID` is non-empty, tapping the connect button launches the OAuth flow via `CustomTabsIntent` using the redirect URI `smsfilter://oauth`.
  - If `BuildConfig.HUBSPOT_CLIENT_ID` is empty, show inline text fields for both the Client ID and Client Secret, along with a help link (*"Where do I find my credentials?"*). The user must enter and save both fields before the "Connect HubSpot Account" button becomes active.
  - The step is not completable until OAuth succeeds, or the user turns the toggle back off, or taps *"Skip HubSpot setup for now"*.
- The OAuth screen offers both HubSpot login and Google login natively — no additional code required beyond launching the standard HubSpot OAuth URL.

**Step 4 — Connection Test**
Trigger a real-time connection test to verify access to Google Contacts (by checking if the system contacts database can be queried) and (if HubSpot is toggled on) HubSpot CRM. Show connection status results: *"Google Contacts: Accessible, HubSpot CRM: Connected"* (or just Google if HubSpot is off). "Done" button marks `firstRunComplete = true` and navigates to the main Settings screen.

If the app is force-stopped and restarted mid-wizard, resume at the last incomplete step.

### Subsequent Startup Flow

On any subsequent app startup (where `firstRunComplete` is `true` in `DataStore<Preferences>`):
- **Default Action:** The app should run in the background. When the main Activity is launched (e.g., from the launcher icon), it must immediately move itself to the background by calling `moveTaskToBack(true)` to run silently, without showing any UI to the user.
- **Exception for Notifications:** If the main Activity is launched with a specific intent flag or extra indicating it was opened via a notification click (such as clicking an opt-out detection notification), bypass the backgrounding action and display the Settings screen or the Activity & Detection Log screen as appropriate.

---

### Core Components

#### 1. SMS Receiver & WorkManager Lookup

- Register a manifest-declared `BroadcastReceiver` (`SmsReceiver`) for `android.provider.Telephony.SMS_RECEIVED` (requires `RECEIVE_SMS` permission).
- On receipt, delegate message details (sender, body, timestamp) immediately to a one-time `WorkManager` worker (`SmsLookupWorker`) for async processing.
- **Expedited Work Requirement**: The lookup worker must be executed as an **Expedited Work Request** (`setExpedited(...)` with `OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST`) to guarantee immediate execution even if the device is in Doze mode or battery saver. This avoids running a persistent foreground service while still ensuring real-time notification delivery. To prevent crashes on older Android versions (API levels < 31) where expedited work runs as a foreground service, the `SmsLookupWorker` must override `getForegroundInfo()` to display a transient system notification when fallback execution is required.
- Processing pipeline inside the worker (in order):
  1. Check stop list words (case-insensitive) → if any match → **ignore** (checked first to avoid redundant API queries).
  2. If not ignored → query Google Contacts (via Android ContactsProvider ContentResolver) and HubSpot CRM (via real-time Contacts API search call) to check if the sender is a known contact.
  3. If found in either → **ignore**.
  4. If not found (unknown sender) → check for opt-out signals (see Opt-Out Detection below).
  5. If opt-out signal found → trigger alert (notification + log entry).

#### 2. Real-Time Contact Verification

To protect user privacy, the app must not store phone numbers locally in any persistent database. All contact lookups are performed in real-time:
- **Google Contacts:** Query the system's `ContactsContract.PhoneLookup` using a `ContentResolver` to check if the incoming phone number belongs to a saved contact. Since this uses the Android local Contacts database, it requires `READ_CONTACTS` permission but does not require network calls.
- **HubSpot Contacts:** If HubSpot is connected, query the HubSpot Search Contacts API (`/crm/v3/objects/contacts/search`) in real-time. Use a search filter to match the `phone` or `mobilephone` properties against the incoming phone number.
- **Normalization:** Prior to lookup, normalize the incoming phone number to E.164 format. When querying HubSpot, search for both the E.164 normalized format and the raw incoming format to ensure a robust match.
- **In-Memory Caching:** To minimize network latency for frequent senders, implement a small, time-limited in-memory cache (e.g., LruCache with a 15-minute expiration) that stores verified known contact numbers. When an SMS arrives from a cached number, skip the external HubSpot API lookup. Never persist this cache to disk.

#### 3. Opt-Out Detection

Check the full message body (case-insensitive) for these two tiers:

**Tier 1 — Stop List (configurable keywords):**
- User-defined list of keywords stored in Room DB.
- If **any** keyword appears anywhere in the message body (case-insensitive substring match) → ignore the message entirely.
- Default stop list: *(empty — user fills this in)*.

**Tier 2 — Opt-Out Signal Detection:**
Applies only if Tier 1 produces no match. Check for:
- The string `stop2stop` anywhere in the message (case-insensitive).
- The string `end2end` anywhere in the message (case-insensitive).
- The word `stop` or `end` appearing **alone on the last non-empty line** of the message (case-insensitive, strip whitespace).

Store the configured opt-out strings in Room DB as a `OptOutPatternEntity` so they are editable. Seed with the four defaults above on first launch.

When an opt-out signal is detected:
- Show a high-priority notification: "Opt-out request detected". Include a setting for this to be disabled.
- Automatically reply with a one word message of either "stop" or "end" to the incoming number:
  - If `stop2stop` or `stop` (last line) is matched $\rightarrow$ reply "stop".
  - If `end2end` or `end` (last line) is matched $\rightarrow$ reply "end".

---

### Settings Screen

Single-screen Settings UI built in Jetpack Compose. 

#### Connection Health Summary
- Rendered at the very top of the Settings screen, showing real-time connection status indicators (active/disconnected) with colored dots:
  - **Google Contacts**: Green dot ("Connected") or Red dot ("Permissions required / Disconnected").
  - **HubSpot CRM**: Green dot ("Connected") or Red dot ("Token expired / Disconnected").
- **State Persistence**: To avoid redundant API requests on UI recompositions, the connection health status for Google and HubSpot must be persisted in `DataStore<Preferences>` under a shared connection status key (e.g., as string values representing `CONNECTED`, `DISCONNECTED`, `AUTH_ERROR`, etc.). Both the UI's connection tests and the background `SmsLookupWorker` must update this state on success/failure. The Settings screen will observe this state to update the status indicator colors.
- **Privacy & Latency Info Card**: A dismissible card explaining: *"To protect your privacy, this app does not store your contacts locally. Lookups are done in real-time, which may cause a 1-2 second delay for unknown numbers."*

Sections:

#### Google Contacts
- Since the app queries system-synced Google Contacts locally, it does not require Google Sign-In or OAuth.
- Display the status of the local contact permission: *"Permission Granted"* or *"Permission Denied"* (with a button to open App Settings to grant it if denied).
- Provide a **"Test Connection"** button that performs a local query via ContentResolver and displays the status along with the number of local contacts found: *"Google Contacts: Accessible (X contacts found)"*.

#### HubSpot Account
- **"Use HubSpot" toggle switch** at the top of this section (default: **on/true**).
- When the toggle is **on**, immediately check whether `BuildConfig.HUBSPOT_CLIENT_ID` is non-empty:
  - **If the client ID is missing:** Show an inline error directly beneath the toggle: *"No HubSpot Client ID and Secret are configured. Please enter them below before connecting."* Render editable text fields for both the Client ID and Client Secret inline, along with a *"Where do I find my credentials?"* help link. Do not launch OAuth until both fields are non-empty and the user taps "Save Credentials." Once saved, write the values to `EncryptedSharedPreferences` under the keys `hubspot_client_id_override` and `hubspot_client_secret_override`, and use them in place of `BuildConfig.HUBSPOT_CLIENT_ID` and `BuildConfig.HUBSPOT_CLIENT_SECRET` for all subsequent OAuth and API calls.
  - **If the credentials are present** (either from `BuildConfig` or from the saved overrides): Immediately launch the HubSpot OAuth 2.0 flow via `CustomTabsIntent` targeting the redirect URI `smsfilter://oauth`. Offer both **HubSpot login** and **Google login** as identity options within the OAuth flow — HubSpot's standard OAuth screen surfaces both natively, so no extra code is needed beyond launching the standard auth URL. The user cannot dismiss this flow without either completing it or turning the toggle back off — enforce this by showing a non-cancelable dialog if they navigate away without completing auth.
- **OAuth Redirect Handling:** Define a custom scheme (`smsfilter://oauth`) in the app's `AndroidManifest.xml` via an `<intent-filter>` on an OAuth redirect Activity. This Activity intercepts the browser redirect, extracts the authorization code (`code`), initiates the token exchange request on `Dispatchers.IO`, stores the resulting access and refresh tokens securely in `EncryptedSharedPreferences`, and finishes the Activity to return the user to the app settings or onboarding flow.
- When the toggle is turned **off**, all HubSpot logic — OAuth, index sync, API calls — is completely bypassed. The rest of this section is grayed out and non-interactive.
- Store access token and refresh token securely using `EncryptedSharedPreferences`.
- Once authenticated, display the connected portal name, a "Disconnect" option, and a **"Test Connection"** button (verifies token validity by making a quick test call to HubSpot's endpoints).
- Required HubSpot scope: `crm.objects.contacts.read`.
- During SMS processing or connection testing, check the "Use HubSpot" toggle before making the HubSpot API calls. If off, skip HubSpot lookups entirely.

#### Stop List
- Scrollable list of current stop list keywords with delete (swipe or trash icon).
- Text field + "Add" button to add new keywords.
- Keywords stored in Room DB (`StopListEntity`).

#### Opt-Out Patterns
- Same add/delete UI pattern as Stop List. However, when adding a new pattern, the user must select the reply type (either "stop" or "end") via a dropdown or radio buttons.
- Pre-seeded with:
  - `stop2stop` (reply type: "stop")
  - `end2end` (reply type: "end")
  - `stop` (reply type: "stop")
  - `end` (reply type: "end")
- Note in the UI: "All matching is case-insensitive. `stop` and `end` are matched only on the last line; others match anywhere."
- The `OptOutPatternEntity` in Room DB must store the pattern string and its associated `replyType` (as a string or enum).

#### Connection Testing
- **"Test All Connections"** button that runs a complete diagnostic check (both Google and HubSpot).
- Display the detailed status (Success/Failure with error reason) of the diagnostic checks.

#### Activity & Detection Log
- Link/button to open a scrollable log screen showing recent activities and detections (last 100 entries).
- **UI Filter Chips**: "All", "Detections Only", "Ignored Only" at the top of the log screen.
- Log entries:
  - **Detections**: timestamp, matched pattern, message preview (no phone number).
  - **Ignored Events**: timestamp, ignore reason (e.g. "Ignored: Known Google Contact", "Ignored: Known HubSpot Contact", "Ignored: Matched Stop List word 'promo'"), message preview (no phone number).
- "Clear Log" button.

---

### Permissions

Request all required permissions on first launch via the onboarding wizard. Required permissions:

```
RECEIVE_SMS
SEND_SMS
READ_SMS
READ_CONTACTS
INTERNET
POST_NOTIFICATIONS          (API 33+)
```

Use `ActivityResultContracts.RequestMultiplePermissions`. Show rationale dialogs for `RECEIVE_SMS`, `SEND_SMS`, and `READ_CONTACTS` explaining why each is needed. If any critical permission is denied, show a persistent banner in the UI and disable the relevant feature gracefully.

---

### Testing

Generate a comprehensive test suite:

**Unit tests** (`/test`):
- `OptOutDetectorTest` — test all pattern combinations: `stop2stop` mid-message, `end2end` in subject, `STOP` alone on last line, `End` alone on last line, `stop` embedded in a word (e.g., "Postop" — should NOT match for last-line check), multi-line messages, empty messages, Unicode whitespace.
- `PhoneNumberNormalizerTest` — E.164 normalization for US numbers, international numbers, numbers with formatting characters.
- `StopListMatcherTest` — case-insensitive match, partial word match behavior (define: keywords match as substrings), empty list behavior.

**Instrumented tests** (`/androidTest`):
- `RoomDatabaseTest` — CRUD for all entities (excluding known number tables, which are removed).
- `RealTimeVerificationTest` — verify that Contact and HubSpot repositories are queried correctly and respect the toggles and API limits.

**Manual test cases** (documented in `TEST_CASES.md` at project root):

| # | Sender | Message | Expected |
|---|--------|---------|----------|
| 1 | Known Google Contact | Any message | Ignored |
| 2 | Known HubSpot contact | Any message | Ignored |
| 3 | Unknown | "Hello, reply STOP to unsubscribe" — "STOP" not alone on last line | No alert |
| 4 | Unknown | "Hello\nSTOP" | Alert — last line match |
| 5 | Unknown | "Thanks\nstop" | Alert — case-insensitive last line |
| 6 | Unknown | Contains stop list word "promo" | Ignored |
| 7 | Unknown | "stop2stop this deal" | Alert — stop2stop match |
| 8 | Unknown | "end2end encryption rocks" | Alert — end2end match |
| 9 | Unknown | "Postop care instructions\nCall us" | No alert — "stop" embedded in word on non-last line |
| 10 | Unknown | Empty message | No alert |

---

### Project Structure

```
app/
  src/
    main/
      java/com/digiroth/smsfilter/
        di/                   # Hilt modules
        data/
          db/                 # Room DB, DAOs, Entities
          repository/         # ContactRepository, HubSpotRepository
        worker/               # SmsLookupWorker
        receiver/             # SmsReceiver
        detection/            # OptOutDetector, StopListMatcher
        ui/
          onboarding/         # OnboardingScreen.kt (Compose, multi-step wizard)
          settings/           # SettingsScreen.kt (Compose)
          log/                # DetectionLogScreen.kt (Compose)
          permissions/        # PermissionsScreen.kt (Compose)
        util/                 # PhoneNumberNormalizer, Extensions
      res/
    test/                     # Unit tests
    androidTest/              # Instrumented tests
  TEST_CASES.md
  INSTALL_GUIDE.md
  local.properties.example
```

---

### Additional Requirements

- **No hardcoded secrets.** HubSpot client ID and client secret must be defined in `local.properties` and injected at build time as `BuildConfig` fields via `build.gradle.kts`. The Gradle script must safely handle the absence of `local.properties` to prevent sync crashes on fresh checkouts, and must explicitly enable the `BuildConfig` feature:

```kotlin
// local.properties (never commit this file — it is in .gitignore)
hubspot.clientId=YOUR_CLIENT_ID_HERE
hubspot.clientSecret=YOUR_CLIENT_SECRET_HERE

// build.gradle.kts
android {
    ...
    buildFeatures {
        buildConfig = true // Required in AGP 8.0+ / Android Studio Quail to generate BuildConfig fields
    }
}

val localProps = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}
val hubspotClientId = localProps.getProperty("hubspot.clientId", "")
val hubspotClientSecret = localProps.getProperty("hubspot.clientSecret", "")

defaultConfig {
    buildConfigField("String", "HUBSPOT_CLIENT_ID", "\"$hubspotClientId\"")
    buildConfigField("String", "HUBSPOT_CLIENT_SECRET", "\"$hubspotClientSecret\"")
}
```

- **Modern Annotation Processing**: Use **Kotlin Symbol Processing (KSP)** instead of the legacy `kapt` tool for room database compiler and Hilt compiler dependencies to ensure compatibility with modern Kotlin versions in Android Studio Quail.
- Include a `local.properties.example` file in the repo with placeholder values and a comment explaining how to populate it. `local.properties` must be listed in `.gitignore`.
- If `BuildConfig.HUBSPOT_CLIENT_ID` or `BuildConfig.HUBSPOT_CLIENT_SECRET` is empty at runtime and no overrides have been saved in `EncryptedSharedPreferences`, the "Use HubSpot" toggle must show the inline credentials entry fields as described in the Settings section above.
- **Retry logic** for HubSpot API calls: exponential backoff, max 3 retries.
- **Rate limit awareness**: HubSpot Contacts API search endpoint is rate-limited; handle failures gracefully by falling back to treating the sender as unknown or retrying.
- **Error states**: if Google or HubSpot lookup fails due to network/API issues during real-time processing, default to processing the message for opt-outs (err on the side of safety) and surface connection warnings in Settings.
- **HTTP Client Timeout**: For all HubSpot API network calls, configure a strict HTTP timeout (e.g., 5 seconds) to prevent `SmsLookupWorker` from hanging and exceeding its expedited execution quota.
- **Notification Channels**: Register the required notification channels (e.g., "Opt-out Alerts" for detections, and a status channel for background execution if needed) inside a custom `Application` class at app startup.
- **SmsManager Retrieval**: To send the automatic replies, retrieve the `SmsManager` instance using `context.getSystemService(SmsManager::class.java)` on API 31+, falling back to `SmsManager.getDefault()` on older versions to avoid deprecation warnings.
- **Database Migration Strategy**: To prevent crashes during development and updates, configure the Room database builder with `fallbackToDestructiveMigration()` so that the database schema is safely recreated if changed, without requiring manual migration scripts.
- **JSON Serialization**: Standardize on **Moshi** (using Kotlin Code Gen via KSP) as the unified JSON serialization library for all HubSpot API request and response parsing.
- Minimum viable happy path must work without HubSpot connected (Google Contacts only mode).
- **State Collection in Compose**: Use `collectAsStateWithLifecycle()` from `androidx.lifecycle:lifecycle-runtime-compose` instead of the standard `collectAsState()` to observe flows reactively in a lifecycle-aware manner.
- **Asynchronous Threading**: Ensure all network calls (HubSpot API), database queries (Room), and system content resolver queries (Google Contacts) are explicitly dispatched on `Dispatchers.IO` in their repositories/workers to avoid blocking the Main thread.
- **Dependency Management**: Manage all dependency coordinates and version numbers globally in a unified Gradle Version Catalog (`gradle/libs.versions.toml`).

---

### Distribution & Production Readiness

- **Distribution Method**: The primary distribution method is private APK distribution for sideloading.
- **Installation Documentation**: A comprehensive installation guide (`INSTALL_GUIDE.md`) must be generated for the web/users. This guide must explain step-by-step how to download the APK, enable the "Install unknown apps" permission for browsers/file managers, and bypass/resolve standard Google Play Protect warnings for sideloaded apps.
- **APK Integrity & Safety Signals**: The APK must be digitally signed using a production-grade release Keystore (configured securely via Gradle from environment variables). Signing with a release key rather than a debug key is critical to signal to Android and Google Play Protect that the APK is safe and has not been tampered with.
- **Play Protect Compliance**: Since we are side-loading the application, the APK must not request any unnecessary permissions like `FOREGROUND_SERVICE` or launch persistent services from the background, which might trigger warnings in Google Play Protect.
- **R8/ProGuard Rules**: Provide a `proguard-rules.pro` file configured to preserve Hilt modules, Room database entities/DAOs, and Moshi/serialization data classes used for HubSpot API communication to prevent runtime crashes in release builds.
- **String Externalization**: All UI strings must be declared in `res/values/strings.xml` to support potential localization and clean resource management. Spanish strings should be included as well and a setting should be added to switch languages. But the default is to use us english strings.
- **Git Version Control**: All generated source code, project files, assets, documentation (`TEST_CASES.md`, `INSTALL_GUIDE.md`), and build scripts must be fully tracked and committed to git.