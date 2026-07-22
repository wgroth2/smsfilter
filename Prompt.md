## Android SMS Compliance Filter — App Specification

### Overview

Build a production-ready Android application in Kotlin that monitors incoming SMS messages and sends opt-out responses to stop messages from unknown senders. The app runs in the background reactively using a BroadcastReceiver and WorkManager (without a persistent background service) and cross-references incoming numbers in real-time against Google Contacts and HubSpot CRM (without storing any phone number data locally) before applying opt-out detection logic. If the message is from an unknown sender and contains opt-out language, the app should automatically reply to the sender with the appropriate one-word opt-out keyword ("stop" or "end") to unsubscribe. There should be a setting called "Beep On Opt-Out", and if the setting is true, the app should beep when it sends an opt-out response. If the setting is false, it should not make any noise. Settings should also contain a setting for the sound file to be used, defaulting to the system beep.

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
  - Local Data Sources: Room database for settings (storing application configuration like beep on opt-out, sound file URI, and app language), stop list, opt-out patterns, and detection logs (no phone number data is stored locally), and `DataStore<Preferences>` for onboarding flags.
  - Remote Data Sources: Retrofit/OkHttp API services for HubSpot communication.
  - Repositories: Coordinate local and remote data sources, acting as the Single Source of Truth (SSOT) for the rest of the application.

Use Hilt for dependency injection. Use Kotlin Coroutines and asynchronous Flows throughout.

---

### Onboarding & First-Run Flow

On first launch (detected via a `firstRunComplete: Boolean` flag in `DataStore<Preferences>`), the app must walk the user through a mandatory setup wizard before the SMS service starts. The wizard uses a `NavHost` with these sequential steps:

**Step 1 — Welcome**
Brief explanation of what the app does. "Get Started" button advances to Step 2.

**Step 2 — Permissions**
Request all required permissions (see Permissions section). The user cannot advance until `RECEIVE_SMS`, `SEND_SMS`, and `POST_NOTIFICATIONS` (API 33+) are granted. `READ_CONTACTS` shows a warning if denied but does not block advancement.

**Step 3 — HubSpot (optional)**
- The "Use HubSpot" toggle defaults to **on** during onboarding, matching the default in Settings.
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
- **Default Action (Launcher Launch):** Display the **Settings screen** directly. The app's SMS monitoring runs silently in the background at all times via the OS `BroadcastReceiver` + `WorkManager` pipeline — no persistent service or UI presence is required for that to function. Do **not** call `moveTaskToBack(true)` on a standard launcher launch; doing so would prevent the user from ever accessing the Settings, Stop List, or Detection Log unless they happened to have an unread notification.
- **Notification Launch:** If the main Activity is launched via a notification click (detected by checking for a specific intent extra, e.g., `EXTRA_OPEN_SCREEN`), navigate directly to the appropriate screen:
  - An **opt-out detection notification** → open the **Activity & Detection Log** screen.
  - A **connection warning notification** → open the **Settings** screen.
- **No Background Auto-Hide:** Remove any use of `moveTaskToBack(true)` from the post-onboarding startup path entirely.

---

### Core Components

#### 1. SMS Receiver & WorkManager Lookup

- Register a manifest-declared `BroadcastReceiver` (`SmsReceiver`) for `android.provider.Telephony.SMS_RECEIVED` (requires `RECEIVE_SMS` permission).
- On receipt, delegate message details (sender, body, timestamp) immediately to a one-time `WorkManager` worker (`SmsLookupWorker`) for async processing.
- **Multi-Part PDU Reconstruction**: Marketing/opt-out texts frequently exceed the 160-character single-segment limit and arrive as multiple concatenated PDUs in the same broadcast `Intent`. `SmsReceiver` must reconstruct the full message correctly before handing it off:
  - Use `Telephony.Sms.Intents.getMessagesFromIntent(intent)` to extract the array of `SmsMessage` segments — do not manually parse the raw `pdus` extra `Object[]` bundle, since that skips the framework's handling of the `format` extra (`"3gpp"` vs `"3gpp2"`, which varies by carrier/OEM) and can throw or silently misparse on certain devices.
  - Concatenate `getMessageBody()` from each segment **in array order** (the array returned by `getMessagesFromIntent` is already ordered by segment sequence number) to form the complete message body before passing it to `SmsLookupWorker`. Do not process each segment as a separate message — partial segments must never be run through opt-out detection independently, since Tier 2 detection depends on the **last line of the fully reconstructed body**, and a truncated or out-of-order reconstruction will cause false negatives on legitimate opt-out replies.
  - Similarly, take the originating address (sender) from the first segment only; it is identical across all segments of the same message.
  - This reconstruction must complete synchronously inside `onReceive()` before enqueuing `SmsLookupWorker` — pass the single fully-assembled body string as the worker's input `Data`, not the individual PDUs.
- **Expedited Work Requirement**: The lookup worker must be executed as an **Expedited Work Request** (`setExpedited(...)` with `OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST`) to guarantee immediate execution even if the device is in Doze mode or battery saver. This avoids running a persistent foreground service while still ensuring real-time notification delivery. To prevent crashes on older Android versions (API levels < 31) where expedited work runs as a foreground service, and to comply with Android 14/15 (API 34/35) strict foreground service constraints:
  - The `SmsLookupWorker` must override `getForegroundInfo()` to display a transient system notification when fallback execution is required.
  - The manifest must declare both `android.permission.FOREGROUND_SERVICE` and the type-specific `android.permission.FOREGROUND_SERVICE_DATA_SYNC` permissions.
  - The WorkManager's `SystemForegroundService` must be explicitly declared in the application's `AndroidManifest.xml` with `tools:node="merge"` to attach the `android:foregroundServiceType="dataSync"` attribute (matching the sync operations for local Contacts and remote HubSpot CRM lookups). This prevents runtime `SecurityException` crashes when WorkManager attempts to launch fallback foreground processes.
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
- If the "Beep On Opt-Out" setting is true, play a beep sound (using the configured sound file URI, or falling back to the system beep) when the opt-out response is sent. If false, do not play any sound.

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

#### Sound & Language Settings
- **"Beep On Opt-Out" toggle**: Enable or disable playing a beep sound when an opt-out response is sent (default: true).
- **Sound File Selector**: Allow selecting a sound file (URI) for the beep using a system ringtone/notification sound picker, defaulting to the system beep sound.
- **Language Selector**: A dropdown or list preference to switch the app's language between English and Spanish, defaulting to US English.

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

The application declares the following permissions in its manifest:

**Runtime Permissions (requested on first launch via the onboarding wizard):**
```
RECEIVE_SMS
SEND_SMS
READ_CONTACTS
POST_NOTIFICATIONS          (API 33+)
```
Use `ActivityResultContracts.RequestMultiplePermissions`. Show rationale dialogs for `RECEIVE_SMS`, `SEND_SMS`, and `READ_CONTACTS` explaining why each is needed. If any critical permission is denied, show a persistent banner in the UI and disable the relevant feature gracefully.

**Install-Time Permissions (declared in `AndroidManifest.xml` but not requested at runtime):**
```
INTERNET
FOREGROUND_SERVICE
FOREGROUND_SERVICE_DATA_SYNC (API 34+)
```

**Note:** `READ_SMS` is intentionally omitted. `SmsReceiver` reads the sender, body, and timestamp directly from the `SMS_RECEIVED` broadcast's PDU extras, so the app never queries the SMS content provider/inbox. Requesting `READ_SMS` would grant access to the full on-device SMS history without a corresponding feature, which is both an unnecessary privacy exposure and a needless Play Protect risk signal.

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
| 11 | Unknown | "Hello\nSTOP" with Beep On Opt-Out enabled | Alert, auto-reply "stop", and configured sound/beep plays |
| 12 | Unknown | "Hello\nSTOP" with Beep On Opt-Out disabled | Alert and auto-reply "stop" without any sound playing |
| 13 | N/A | Switching app language to Spanish in settings | All UI screens display in Spanish |

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

- **Distribution Method**: The app will be distributed as an APK (private distribution for sideloading). There must be two configured build types to compile the APK:
  - **Debug Mode**: Uses the default debug keystore, keeps logging active, bypasses ProGuard/R8 optimization, and does not require release signing environment variables.
  - **Production/Release Mode**: Uses a production-grade release keystore (configured securely via environment variables), enables full R8/ProGuard code shrinking and optimization, and strips out debug log statements to maintain efficiency and security.
- **Installation Documentation**: A comprehensive installation guide (`INSTALL_GUIDE.md`) must be generated for the web/users. This guide must explain step-by-step how to download the APK, enable the "Install unknown apps" permission for browsers/file managers, and bypass/resolve standard Google Play Protect warnings for sideloaded apps.
- **APK Integrity & Safety Signals**: The APK must be digitally signed using a production-grade release Keystore (configured securely via Gradle from environment variables). Signing with a release key rather than a debug key is critical to signal to Android and Google Play Protect that the APK is safe and has not been tampered with.
- **Play Protect Compliance**: Google Play Protect scans installed APKs on-device regardless of install source, so avoid requesting permissions that aren't justified by an actual feature. `FOREGROUND_SERVICE` is acceptable and expected here since it backs the required `getForegroundInfo()` fallback notification for expedited work on API < 31 — it is not a persistent/long-running foreground service and should not be removed. Do not request permissions with no corresponding implementation, such as `READ_SMS` (see Permissions section) unless a concrete feature requires it.
- **R8/ProGuard Rules**: Provide a `proguard-rules.pro` file configured to preserve Hilt modules, Room database entities/DAOs, and Moshi/serialization data classes used for HubSpot API communication to prevent runtime crashes in release builds.
- **String Externalization**: All UI strings must be declared in `res/values/strings.xml` to support potential localization and clean resource management. Spanish strings should be included as well and a setting should be added to switch languages. But the default is to use us english strings.
- **Git Version Control**: All generated source code, project files, assets, documentation (`TEST_CASES.md`, `INSTALL_GUIDE.md`), and build scripts must be fully tracked and committed to git.

---

### Dependency Version Catalog

All dependency versions are pinned below. These versions are known to be mutually compatible as of July 2026. **Do not substitute different version numbers** — use these exact values when generating `gradle/libs.versions.toml`. If a newer stable release is available at generation time, flag it for human review before changing anything.

> **Note:** The KSP version **must** match the Kotlin version exactly (format: `<kotlin-version>-<ksp-release>`). If the Kotlin version is updated, the KSP version must be updated together.

```toml
[versions]
agp                 = "8.10.1"
kotlin              = "2.1.21"
ksp                 = "2.1.21-1.0.31"
hilt                = "2.56.1"
room                = "2.7.1"
compose-bom         = "2025.06.01"
lifecycle           = "2.9.1"
workmanager         = "2.10.2"
retrofit            = "2.11.0"
okhttp              = "4.12.0"
moshi               = "1.15.2"
datastore           = "1.1.7"
navigation-compose  = "2.9.0"
security-crypto     = "1.1.0-alpha06"

[libraries]
# AndroidX Core
androidx-core-ktx              = { module = "androidx.core:core-ktx", version = "1.16.0" }
androidx-appcompat             = { module = "androidx.appcompat:appcompat", version = "1.7.1" }

# Jetpack Compose (versions managed by BOM)
compose-bom                    = { group = "androidx.compose", name = "compose-bom", version.ref = "compose-bom" }
compose-ui                     = { group = "androidx.compose.ui", name = "ui" }
compose-ui-tooling-preview     = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
compose-ui-tooling             = { group = "androidx.compose.ui", name = "ui-tooling" }
compose-material3              = { group = "androidx.compose.material3", name = "material3" }
compose-activity               = { module = "androidx.activity:activity-compose", version = "1.10.1" }

# Lifecycle & ViewModel
lifecycle-viewmodel-ktx        = { module = "androidx.lifecycle:lifecycle-viewmodel-ktx", version.ref = "lifecycle" }
lifecycle-runtime-ktx          = { module = "androidx.lifecycle:lifecycle-runtime-ktx", version.ref = "lifecycle" }
lifecycle-runtime-compose      = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }

# Navigation
navigation-compose             = { module = "androidx.navigation:navigation-compose", version.ref = "navigation-compose" }

# Hilt
hilt-android                   = { module = "com.google.dagger:hilt-android", version.ref = "hilt" }
hilt-compiler                  = { module = "com.google.dagger:hilt-android-compiler", version.ref = "hilt" }
hilt-navigation-compose        = { module = "androidx.hilt:hilt-navigation-compose", version = "1.2.0" }

# Room
room-runtime                   = { module = "androidx.room:room-runtime", version.ref = "room" }
room-ktx                       = { module = "androidx.room:room-ktx", version.ref = "room" }
room-compiler                  = { module = "androidx.room:room-compiler", version.ref = "room" }

# WorkManager
workmanager-ktx                = { module = "androidx.work:work-runtime-ktx", version.ref = "workmanager" }
hilt-work                      = { module = "androidx.hilt:hilt-work", version = "1.2.0" }
hilt-work-compiler             = { module = "androidx.hilt:hilt-compiler", version = "1.2.0" }

# DataStore
datastore-preferences          = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }

# Security (EncryptedSharedPreferences)
security-crypto                = { module = "androidx.security:security-crypto", version.ref = "security-crypto" }

# Retrofit + OkHttp
retrofit-core                  = { module = "com.squareup.retrofit2:retrofit", version.ref = "retrofit" }
retrofit-moshi                 = { module = "com.squareup.retrofit2:converter-moshi", version.ref = "retrofit" }
okhttp-core                    = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
okhttp-logging                 = { module = "com.squareup.okhttp3:logging-interceptor", version.ref = "okhttp" }

# Moshi
moshi-kotlin                   = { module = "com.squareup.moshi:moshi-kotlin", version.ref = "moshi" }
moshi-kotlin-codegen           = { module = "com.squareup.moshi:moshi-kotlin-codegen", version.ref = "moshi" }

# Browser (CustomTabsIntent for HubSpot OAuth)
browser                        = { module = "androidx.browser:browser", version = "1.8.0" }

# Testing
junit                          = { module = "junit:junit", version = "4.13.2" }
androidx-test-ext-junit        = { module = "androidx.test.ext:junit", version = "1.2.1" }
androidx-test-espresso-core    = { module = "androidx.test.espresso:espresso-core", version = "3.6.1" }
room-testing                   = { module = "androidx.room:room-testing", version.ref = "room" }
workmanager-testing            = { module = "androidx.work:work-testing", version.ref = "workmanager" }
hilt-testing                   = { module = "com.google.dagger:hilt-android-testing", version.ref = "hilt" }
mockwebserver                  = { module = "com.squareup.okhttp3:mockwebserver", version.ref = "okhttp" }
kotlinx-coroutines-test        = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version = "1.10.2" }

[plugins]
android-application  = { id = "com.android.application", version.ref = "agp" }
kotlin-android       = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose       = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp                  = { id = "com.google.devtools.ksp", version.ref = "ksp" }
hilt-plugin          = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
```

---

### Phased Code Generation & Build Strategy

To prevent token truncation, stubbed implementation code, and version drift during code generation, development must be executed sequentially across 5 incremental phases. After each phase, verify that the generated components compile cleanly:

1. **Phase 1 — Project Scaffolding & Build Configuration**
   - Generate `gradle/libs.versions.toml` using **exactly** the pinned versions from the Dependency Version Catalog section above.
   - Generate `build.gradle.kts` (root & app), `local.properties.example`, `proguard-rules.pro`, `AndroidManifest.xml`, and the custom `@HiltAndroidApp Application` class.
   - *Verification:* Confirm Gradle syncs cleanly and `./gradlew assembleDebug` compiles the base application skeleton.

2. **Phase 2 — Core Data Layer & Database**
   - Implement Room entities (`StopListEntity`, `OptOutPatternEntity`, `DetectionLogEntity`), DAOs, Room database (configured with `fallbackToDestructiveMigration()`), `DataStore<Preferences>`, and `EncryptedSharedPreferences`.
   - Implement `ContactRepository` (Google Contacts ContentResolver query logic + in-memory LruCache) and `StopListMatcher`.
   - *Verification:* Execute unit tests (`RoomDatabaseTest`, `StopListMatcherTest`, `PhoneNumberNormalizerTest`).

3. **Phase 3 — Background SMS Processing Pipeline**
   - Implement `SmsReceiver` (multi-part PDU reconstruction via `getMessagesFromIntent()`).
   - Implement `SmsLookupWorker` (Expedited Work request setup, `getForegroundInfo()` system notification fallback, contact verification, opt-out detection, sound/beep trigger, and `SmsManager` auto-reply logic).
   - Implement `OptOutDetector`.
   - *Verification:* Execute `OptOutDetectorTest` and worker unit tests.

4. **Phase 4 — HubSpot API & OAuth Layer**
   - Implement Moshi JSON models, Retrofit service interfaces (`HubSpotApiService`), `HubSpotRepository` (with search query filters & 5s timeouts), and token refresh interceptors.
   - Implement `OAuthRedirectActivity` for `smsfilter://oauth` Intent callback and runtime credentials override storage.
   - *Verification:* Execute `HubSpotRepositoryTest` with mock web server tests.

5. **Phase 5 — Compose UI, Onboarding, Settings & Localization**
   - Implement Jetpack Compose UI: Onboarding Wizard steps, Connection Health Summary, Settings Screen, Detection Log Screen.
   - Externalize all strings into `res/values/strings.xml` (US English) and `res/values-es/strings.xml` (Spanish).
   - Generate `TEST_CASES.md` and `INSTALL_GUIDE.md`.
   - *Verification:* Run full test suite (`./gradlew test`) and compile debug and release APKs (`./gradlew assembleDebug assembleRelease`).