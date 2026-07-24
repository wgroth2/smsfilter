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
  - Local Data Sources: Room database for list-shaped data only — stop list, opt-out patterns, detection logs, and auto-reply cooldown entries (no phone number data is stored locally; cooldown rows hold only a one-way SHA-256 hash of the sender address) — and `DataStore<Preferences>` as the single store for all scalar settings and flags: application configuration (auto-reply toggle, beep on opt-out, sound file URI, app language, "Use HubSpot" toggle, opt-out notification toggle), onboarding flags, and connection health state. **There is no Room settings table.**
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

**Step 3 — Connection Test**
Trigger a real-time connection test to verify access to Google Contacts (by checking if the system contacts database can be queried). Show the connection status result: *"Google Contacts: Accessible"*. "Done" button marks `firstRunComplete = true` and navigates to the main Settings screen.

HubSpot setup is intentionally **not** part of the wizard — it is offered exactly once via the post-onboarding dialog below, and afterwards lives only in Settings. This keeps the wizard short and never blocks first-run completion on an external account.

If the app is force-stopped and restarted mid-wizard, resume at the last incomplete step.

### Post-Onboarding HubSpot Prompt (One-Time Dialog)

The first time the Settings screen is shown after the wizard completes (tracked via a `hubSpotPromptShown: Boolean` flag in `DataStore<Preferences>`), display a standard cancelable Material `AlertDialog`:

- **Title:** *"Connect HubSpot CRM?"*
- **Body:** *"SMS Filter can also check unknown senders against your HubSpot contacts. You can connect now, or later from Settings."*
- **Buttons:** **"Connect"** and **"Not now"**.

Behavior:
- Any outcome — "Connect", "Not now", or dismissing the dialog via back/outside tap (which counts as "Not now") — sets `hubSpotPromptShown = true`. The dialog is shown **at most once, ever**.
- **"Connect"** → turns the "Use HubSpot" toggle on and scrolls to the HubSpot Account section of Settings with the access-token field focused (see Settings section).
- **"Not now"** → HubSpot stays off. The app never re-prompts; the only path to connect afterwards is Settings → HubSpot Account.

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
- **Hilt + WorkManager Initialization**: Because `SmsLookupWorker` is a `@HiltWorker` with an `@AssistedInject` constructor, WorkManager's default self-initialization must be disabled and replaced with a Hilt-aware configuration — otherwise the worker is instantiated reflectively without its dependencies and crashes at runtime with no compile-time warning:
  - The `Application` class must implement `Configuration.Provider`, returning a `Configuration` built with the injected `HiltWorkerFactory`.
  - The manifest must remove the automatic initializer by declaring `androidx.startup.InitializationProvider` (authority `${applicationId}.androidx-startup`, `tools:node="merge"`) containing `<meta-data android:name="androidx.work.WorkManagerInitializer" tools:node="remove" />`.
- Processing pipeline inside the worker (in order):
  1. Check stop list words (case-insensitive) → if any match → **ignore** (checked first to avoid redundant API queries).
  2. If not ignored → query Google Contacts (via Android ContactsProvider ContentResolver) and HubSpot CRM (via real-time Contacts API search call) to check if the sender is a known contact.
  3. If found in either → **ignore**.
  4. If not found (unknown sender) → check for opt-out signals (see Opt-Out Detection below).
  5. If opt-out signal found → trigger alert (notification + log entry), then run the auto-reply gate (see Auto-Reply Safety Controls below): reply only if auto-reply is enabled, the sender is repliable (not alphanumeric), and the sender is not inside the 24-hour cooldown window.

#### 2. Real-Time Contact Verification

To protect user privacy, the app must not store phone numbers locally in any persistent database. (The auto-reply cooldown table stores only a one-way SHA-256 hash of the sender address — see Auto-Reply Safety Controls — which cannot be reversed into a phone number.) All contact lookups are performed in real-time:
- **Google Contacts:** Query the system's `ContactsContract.PhoneLookup` using a `ContentResolver` to check if the incoming phone number belongs to a saved contact. Since this uses the Android local Contacts database, it requires `READ_CONTACTS` permission but does not require network calls.
- **HubSpot Contacts:** If HubSpot is connected, query the HubSpot Search Contacts API (`/crm/v3/objects/contacts/search`) in real-time. Filter on HubSpot's normalized calculated property **`hs_searchable_calculated_phone_number`** — do **not** rely on exact-match (`EQ`) filters against the raw `phone` or `mobilephone` properties, because HubSpot stores those in whatever format they were entered (e.g., "(650) 555-1234"), so an exact match against E.164 misses them and real CRM contacts get misclassified as unknown senders. Query using the E.164 digits first, and fall back to a second search with the raw incoming digits if the first returns no match.
- **Normalization:** Prior to lookup, normalize the incoming phone number to E.164 using the platform's built-in `PhoneNumberUtils.formatNumberToE164(number, "US")` — do **not** add an external phone-number library; nothing outside the pinned Dependency Version Catalog may be introduced. `PhoneNumberNormalizer` must return `null` when normalization fails, and callers must then use the raw sender address unchanged. Two sender classes can never normalize to E.164 and must be explicitly classified by `PhoneNumberNormalizer`:
  - **Short codes** (5–6 digit senders — the most common source of marketing/opt-out SMS): skip E.164 conversion entirely; look up and reply using the raw digits exactly as received.
  - **Alphanumeric sender IDs** (e.g., "VERIZON"): cannot receive SMS replies at all. Run detection on the message normally, but skip the auto-reply and record the log entry with the reason "cannot reply to alphanumeric sender".
- **In-Memory Caching:** To minimize network latency for frequent senders, implement a small, time-limited in-memory cache (e.g., LruCache with a 15-minute expiration) that stores verified known contact numbers. When an SMS arrives from a cached number, skip the external HubSpot API lookup. Never persist this cache to disk.

#### 3. Opt-Out Detection

Check the full message body (case-insensitive) for these two tiers:

**Tier 1 — Stop List (configurable keywords):**
- User-defined list of keywords stored in Room DB.
- If **any** keyword appears anywhere in the message body (case-insensitive substring match) → ignore the message entirely.
- Default stop list: *(empty — user fills this in)*.

**Tier 2 — Opt-Out Signal Detection:**
Applies only if Tier 1 produces no match. Every opt-out pattern carries a `matchMode` that controls how it is evaluated (all matching is case-insensitive):
- `ANYWHERE` — the pattern matches as a substring anywhere in the message body.
- `LAST_LINE_EXACT` — the pattern matches only if the **last non-empty line** of the message, after trimming whitespace, is exactly the pattern word.

Store the configured opt-out patterns in Room DB as `OptOutPatternEntity` rows (fields: pattern string, `replyType`, `matchMode`) so they are fully editable. Seed on first launch with:

| Pattern | matchMode | replyType |
|---|---|---|
| `stop2stop` | `ANYWHERE` | `stop` |
| `end2end` | `ANYWHERE` | `end` |
| `stop` | `LAST_LINE_EXACT` | `stop` |
| `end` | `LAST_LINE_EXACT` | `end` |

The detector must read `matchMode` from each entity — **never hardcode which pattern strings are last-line-only**. User-added patterns must work correctly with either mode.

When an opt-out signal is detected:
- Show a high-priority notification: "Opt-out request detected". Include a setting for this to be disabled.
- If the auto-reply gate passes (see Auto-Reply Safety Controls below), automatically reply with a one-word message of either "stop" or "end" — taken from the matched pattern's `replyType` — to the **raw originating address** (never the E.164-normalized form; a short code must receive the reply at the exact address it sent from).
- If the "Beep On Opt-Out" setting is true, play a beep sound (using the configured sound file URI, or falling back to the system beep) when the opt-out response is sent. If false, or if no reply was actually sent, do not play any sound.

#### 4. Auto-Reply Safety Controls

Because the app sends SMS autonomously, every auto-reply must pass all three gates below, evaluated in order. When a gate blocks the reply, the detection is still logged — with the skip reason — and the notification still fires.

1. **Master switch:** An `autoReplyEnabled: Boolean` setting (default: `true`) stored in `DataStore<Preferences>`. When `false`, the app runs in **detection-only (dry run) mode**: it detects, notifies, and logs, but never sends an SMS. This is both the kill switch if a pattern ever misfires and a trust-building mode for new users.
2. **Repliable sender:** Alphanumeric sender IDs are skipped (see Real-Time Contact Verification above).
3. **Cooldown — at most one auto-reply per sender per 24 hours.** This prevents SMS ping-pong loops with automated responders (e.g., their system answers our "stop" with a confirmation text that itself trips a pattern, which would otherwise trigger replies back and forth forever, exhausting the user's SMS allowance and risking carrier spam flags):
   - Implemented via a Room entity `AutoReplyCooldownEntity` with fields `senderHash: String` (primary key) and `lastReplyTimestamp: Long`.
   - `senderHash` is the lowercase-hex **SHA-256 hash of the raw originating address** — never the address itself — preserving the rule that no phone number data is stored locally (the hash is one-way and cannot be reversed into a number).
   - Before sending, the worker looks up the hash: if a row exists with `lastReplyTimestamp` within the last 24 hours, skip the reply and log "Detected — reply skipped (cooldown)". After a successful send, upsert the row with the current timestamp. On each worker run, delete rows older than 24 hours as housekeeping.

---

### Settings Screen

Single-screen Settings UI built in Jetpack Compose. 

#### Connection Health Summary
- Rendered at the very top of the Settings screen, showing real-time connection status indicators (active/disconnected) with colored dots:
  - **Google Contacts**: Green dot ("Connected") or Red dot ("Permissions required / Disconnected").
  - **HubSpot CRM**: Gray dot ("Off") when the "Use HubSpot" toggle is off, Green dot ("Connected") when connected, or Red dot ("Token invalid / Unreachable") only when the toggle is on and the last check failed. A deliberately-disconnected HubSpot must never be styled as an error state.
- **State Persistence**: To avoid redundant API requests on UI recompositions, the connection health status for Google and HubSpot must be persisted in `DataStore<Preferences>` under a shared connection status key (e.g., as string values representing `CONNECTED`, `DISCONNECTED`, `AUTH_ERROR`, etc.). Both the UI's connection tests and the background `SmsLookupWorker` must update this state on success/failure. The Settings screen will observe this state to update the status indicator colors.
- **Privacy & Latency Info Card**: A dismissible card explaining: *"To protect your privacy, this app does not store your contacts locally. Lookups are done in real-time, which may cause a 1-2 second delay for unknown numbers."*

Sections:

#### Google Contacts
- Since the app queries system-synced Google Contacts locally, it does not require Google Sign-In or OAuth.
- Display the status of the local contact permission: *"Permission Granted"* or *"Permission Denied"* (with a button to open App Settings to grant it if denied).
- Provide a **"Test Connection"** button that performs a local query via ContentResolver and displays the status along with the number of local contacts found: *"Google Contacts: Accessible (X contacts found)"*.

#### HubSpot Account

HubSpot authentication uses a **HubSpot Private App access token** — there is no OAuth flow, no browser round-trip, and no client ID or client secret anywhere in the app or build. (HubSpot rejects custom-scheme OAuth redirect URLs, and for a single-user sideloaded app a Private App token is the simplest, most reliable mechanism.) The user creates a Private App in their HubSpot portal — with the `crm.objects.contacts.read` scope — and pastes its access token into the app.

- **"Use HubSpot" toggle switch** at the top of this section (default: **off/false**).
- When the toggle is turned **on** and no token is saved, reveal an inline connect card (never auto-launch anything):
  - A masked **"Private App Access Token"** text field with a show/hide visibility icon.
  - A help link — *"Where do I find my access token?"* — that opens HubSpot's Private Apps documentation (`https://developers.hubspot.com/docs/api/private-apps`) in the browser.
  - A **"Connect & Test"** button, enabled once the field is non-empty. Tapping it validates the token on `Dispatchers.IO` with a lightweight test call (`GET /crm/v3/objects/contacts?limit=1`). On success, store the token in `EncryptedSharedPreferences` under the key `hubspot_access_token`, set the shared connection status to `CONNECTED`, and collapse the card into the connected state. On failure, show an inline error beneath the field (distinguishing invalid token, missing scope, and network error) without leaving the screen.
- **Connected state:** green status indicator, the portal ID if available (via `GET /account-info/v3/details`; if that call fails due to scopes, just show "Connected"), a **"Test Connection"** button (re-runs the lightweight test call), and a **"Disconnect"** button (deletes the token from `EncryptedSharedPreferences`, sets the connection status to `DISCONNECTED`, and turns the toggle off).
- When the toggle is turned **off**, all HubSpot logic — API calls, connection tests — is completely bypassed and the rest of this section is grayed out and non-interactive. A saved token is **retained** so re-enabling the toggle reconnects without re-entry; "Disconnect" is the explicit action that forgets the token.
- All HubSpot API requests authenticate via an `Authorization: Bearer <token>` header read from `EncryptedSharedPreferences`. Private App tokens do not expire, so no token-refresh logic exists. If any API call returns 401 (token revoked or scope removed), set the shared connection status to `AUTH_ERROR` and surface the red "Token invalid" indicator in the Connection Health Summary — do not delete the stored token automatically.
- During SMS processing or connection testing, check the "Use HubSpot" toggle before making the HubSpot API calls. If off, skip HubSpot lookups entirely.

#### Stop List
- Scrollable list of current stop list keywords with delete (swipe or trash icon).
- Text field + "Add" button to add new keywords.
- Keywords stored in Room DB (`StopListEntity`).

#### Opt-Out Patterns
- Same add/delete UI pattern as Stop List. However, when adding a new pattern, the user must select **both** the reply type ("stop" or "end") and the match mode ("Match anywhere" or "Exact match on last line") via dropdowns or radio buttons.
- Pre-seeded with:
  - `stop2stop` (match anywhere, reply type: "stop")
  - `end2end` (match anywhere, reply type: "end")
  - `stop` (exact match on last line, reply type: "stop")
  - `end` (exact match on last line, reply type: "end")
- Each list row displays the pattern together with its match mode and reply type.
- Note in the UI: "All matching is case-insensitive."
- The `OptOutPatternEntity` in Room DB must store the pattern string, its `replyType`, and its `matchMode` (as strings or enums).

#### Auto-Reply
- **"Auto-Reply" master toggle** (default: **on**). When off, the app runs in detection-only (dry run) mode: detections still notify and appear in the log, but **no SMS is ever sent**. The toggle's subtitle text must make this explicit, e.g., *"Off = detect and notify only — never send a reply."*
- Static informational text: *"To prevent reply loops, at most one auto-reply is sent to any sender per 24 hours."* (The cooldown window is fixed, not configurable.)

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
  - **Detections**: timestamp, matched pattern, reply status ("Reply sent: stop" / "Reply skipped: dry run" / "Reply skipped: cooldown" / "Reply skipped: alphanumeric sender"), message preview (no phone number).
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
- `OptOutDetectorTest` — test all pattern combinations: `stop2stop` mid-message, `end2end` in subject, `STOP` alone on last line, `End` alone on last line, `stop` embedded in a word (e.g., "Postop" — should NOT match for last-line check), multi-line messages, empty messages, Unicode whitespace. Also verify `matchMode` is honored generically: a custom `ANYWHERE` pattern matching mid-message, and a custom `LAST_LINE_EXACT` pattern NOT matching mid-message.
- `PhoneNumberNormalizerTest` — E.164 normalization for US numbers, international numbers, numbers with formatting characters; short codes (return `null` / classified as short code); alphanumeric sender IDs (classified as not repliable).
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
| 14 | Unknown short code (e.g., 89887) | "Hello\nSTOP" | Alert — auto-reply "stop" sent to the raw short code address |
| 15 | Same sender as #14, second message within 24 hours | "Hello\nSTOP" | Alert and log entry "Reply skipped: cooldown" — no second reply sent |
| 16 | Unknown | "Hello\nSTOP" with Auto-Reply toggle off | Alert and log entry "Reply skipped: dry run" — no SMS sent |
| 17 | Alphanumeric sender (e.g., "PROMO") | "Hello\nSTOP" | Alert and log entry "Reply skipped: alphanumeric sender" — no SMS sent |

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
```

---

### Additional Requirements

- **No hardcoded secrets.** The app has no build-time secrets: the HubSpot Private App access token is entered by the user at runtime and stored only in `EncryptedSharedPreferences` (key `hubspot_access_token`). No token, client ID, or secret may ever appear in source code, `BuildConfig` fields, `local.properties`, or version control. `local.properties` (auto-generated by Android Studio for `sdk.dir`) must remain listed in `.gitignore`. Enable `buildFeatures { buildConfig = true }` in `build.gradle.kts` only if `BuildConfig.DEBUG` is used to gate debug logging.
- **Modern Annotation Processing**: Use **Kotlin Symbol Processing (KSP)** instead of the legacy `kapt` tool for room database compiler and Hilt compiler dependencies to ensure compatibility with modern Kotlin versions in Android Studio Quail.
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
ksp                 = "2.1.21-2.0.2"
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

To prevent token truncation, stubbed implementation code, and version drift during code generation, development must be executed sequentially across 7 incremental phases. After each phase, verify that the generated components compile cleanly before proceeding.

> **Important:** Each phase prompt should include the full spec from `Prompt.md` plus a list of all files already generated in previous phases, so the AI has complete context without regenerating existing code.

1. **Phase 1 — Project Scaffolding & Build Configuration**
   - **Create the Gradle wrapper first.** The wrapper JAR (`gradle/wrapper/gradle-wrapper.jar`) is a binary file that an AI cannot author — obtain it either by scaffolding the project from an Android Studio "Empty Activity" template or by running `gradle wrapper --gradle-version 8.11.1` with a locally installed Gradle (AGP 8.10 requires Gradle 8.11.1 or newer). Commit `gradlew`, `gradlew.bat`, and `gradle/wrapper/` to git; no phase verification can run without them.
   - Generate `gradle/libs.versions.toml` using **exactly** the pinned versions from the Dependency Version Catalog section above.
   - Generate `build.gradle.kts` (root & app module), `settings.gradle.kts`, `proguard-rules.pro`, `.gitignore`, and `AndroidManifest.xml` (permissions, receiver, and service declarations — these are forward declarations for classes generated in later phases).
   - Generate the `@HiltAndroidApp Application` class with notification channel registration **and the Hilt + WorkManager initialization wiring from the Core Components section**: implement `Configuration.Provider` with the injected `HiltWorkerFactory`, and remove the default WorkManager initializer in the manifest. This must exist before Phase 4's `@HiltWorker` is generated, or the worker will crash at runtime. **Do not generate any Hilt `@Module` files in this phase** — each module is generated in the phase where its dependency classes are first defined.
   - *Verification:* Confirm Gradle syncs cleanly and `./gradlew assembleDebug` compiles the bare application skeleton with no errors.

2. **Phase 2 — Room Database, DataStore & Secure Storage**
   - Implement all Room entities — `StopListEntity`, `OptOutPatternEntity` (pattern, `replyType`, `matchMode`), `DetectionLogEntity`, and `AutoReplyCooldownEntity` (`senderHash` SHA-256 primary key, `lastReplyTimestamp`) — their DAOs, and the Room database class configured with `fallbackToDestructiveMigration()`.
   - Implement `DataStore<Preferences>` as the single store for all scalar settings and flags: app configuration (`autoReplyEnabled`, `useHubSpot`, `beepOnOptOut`, `soundFileUri`, app language, opt-out notification toggle), onboarding flags (`firstRunComplete`, `hubSpotPromptShown`), and connection health state. Expose it through one injectable wrapper class (e.g., `SettingsDataStore`) so later phases inject a single type. **There is no Room settings entity or settings DAO.**
   - Implement `EncryptedSharedPreferences` wrapper for the HubSpot Private App access token (key `hubspot_access_token`).
   - Generate `di/DatabaseModule` — provides `AppDatabase` singleton and all DAO instances. This is the first Hilt module and must be generated here, after the Room classes it references exist.
   - *Verification:* Execute `RoomDatabaseTest` (CRUD for all entities, plus the opt-out pattern seeding callback on a fresh database). The AI must generate this test as part of this phase.

3. **Phase 3 — Detection Engine & Utility Layer**
   - Implement `PhoneNumberNormalizer` using the platform's `PhoneNumberUtils.formatNumberToE164(number, "US")` — no external phone-number library may be added. It must return `null` on normalization failure and classify each sender as a standard number, a short code (5–6 digits — pass through raw), or an alphanumeric sender ID (not repliable).
   - Implement `StopListMatcher` (case-insensitive substring matching against a `List<StopListEntity>` passed in as a parameter — do not inject a DAO into this class).
   - Implement `OptOutDetector` with a `detect(body: String, patterns: List<OptOutPatternEntity>): OptOutResult?` function signature. The patterns list must be passed in by the caller — **do not hardcode the four default patterns as constants and do not inject a DAO into `OptOutDetector` directly**. This keeps the class pure and easily unit-testable without a database. The caller (`SmsLookupWorker`, Phase 4) is responsible for fetching the live pattern list from `OptOutPatternDao` and passing it in. The detector must evaluate each pattern according to its `matchMode` (`ANYWHERE` substring vs. `LAST_LINE_EXACT`) — never by special-casing particular pattern strings.
   - `OptOutResult` must be a data class (or sealed class) that captures the matched pattern string and its `replyType` (`"stop"` or `"end"`), so `SmsLookupWorker` knows which word to auto-reply with.
   - *Verification:* Execute `PhoneNumberNormalizerTest`, `StopListMatcherTest`, and `OptOutDetectorTest`. Tests must pass the pattern list explicitly as constructor/function arguments — no mocking of a DAO is needed or permitted in this phase.

4. **Phase 4 — Background SMS Pipeline**
   - Implement `ContactRepository` (Google Contacts `ContactsContract.PhoneLookup` via `ContentResolver` + 15-minute in-memory `LruCache`).
   - Define `HubSpotRepository` as a **Kotlin interface** only (full implementation comes in Phase 5). The interface must declare all methods needed by `SmsLookupWorker` so the worker compiles without the real implementation.
   - Implement `SmsReceiver` (manifest-declared, multi-part PDU reconstruction via `getMessagesFromIntent()`, synchronous reconstruction in `onReceive()` before enqueuing the worker).
   - Implement `SmsLookupWorker` as an Expedited Work Request with `getForegroundInfo()` fallback notification. The worker must inject the **`SettingsDataStore` wrapper** (generated in Phase 2) and read the following four values on `Dispatchers.IO` **before any processing logic runs**:
     - `autoReplyEnabled: Boolean` — if `false`, run in detection-only (dry run) mode: never send an SMS, but still notify and write the log entry with the skip reason.
     - `useHubSpot: Boolean` — if `false`, skip all `HubSpotRepository` calls entirely; treat sender as unknown if not found in Google Contacts.
     - `beepOnOptOut: Boolean` — if `true`, play audio after sending an opt-out reply; if `false`, produce no sound.
     - `soundFileUri: String?` — the URI of the configured beep sound; fall back to the system notification sound (`RingtoneManager.TYPE_NOTIFICATION`) if null or empty.
   - The full worker processing pipeline must execute in this exact order: (1) read runtime settings from `SettingsDataStore` + **fetch `List<StopListEntity>` from `StopListDao` and `List<OptOutPatternEntity>` from `OptOutPatternDao`** on `Dispatchers.IO` → (2) stop list check (pass list to `StopListMatcher`) → (3) Google Contacts lookup → (4) HubSpot lookup (only if `useHubSpot = true`) → (5) opt-out detection (pass pattern list to `OptOutDetector`) → (6) auto-reply gate: `autoReplyEnabled` is `true`, the sender is repliable (not alphanumeric), and `AutoReplyCooldownDao` has no row for the sender's SHA-256 hash within the last 24 hours → (7) `SmsManager` auto-reply to the **raw originating address** using `replyType` from `OptOutResult`, then upsert the cooldown row → (8) sound playback using `soundFileUri` (only if `beepOnOptOut = true` and a reply was sent) → (9) `DetectionLogEntity` write, recording whether the reply was sent or skipped and the skip reason (dry run / alphanumeric sender / cooldown).
   - Generate `di/RepositoryModule` — binds `ContactRepository` as its concrete type and binds the `HubSpotRepository` interface to a no-op placeholder implementation so Hilt can satisfy the dependency. This placeholder will be replaced in Phase 5. **Do not generate `NetworkModule` here** — Retrofit/OkHttp/Moshi do not exist until Phase 5.
   - *Verification:* Execute worker unit tests with the `HubSpotRepository` interface mocked via the placeholder. The AI must generate these tests as part of this phase.

5. **Phase 5 — HubSpot API Layer**
   - Implement all Moshi JSON request/response models for the HubSpot Contacts Search API.
   - Implement `HubSpotApiService` (Retrofit interface) and `HubSpotRepositoryImpl` — the full, production implementation of the `HubSpotRepository` interface defined in Phase 4 — including the 5-second HTTP timeout, exponential backoff retry (max 3), rate limit handling, and an auth interceptor that attaches the `Authorization: Bearer` header from the `EncryptedSharedPreferences` token. On a 401 response, set the shared connection status to `AUTH_ERROR` — there is no token-refresh logic, because Private App tokens do not expire.
   - Generate `di/NetworkModule` — provides `Moshi`, `OkHttpClient` (with timeout, auth interceptor, and logging interceptor), and `Retrofit` singleton instances.
   - Update `di/RepositoryModule` — replace the Phase 4 no-op placeholder binding for `HubSpotRepository` with the real `HubSpotRepositoryImpl` binding.
   - *Verification:* Execute `HubSpotRepositoryTest` using `MockWebServer`. The AI must generate this test class as part of this phase.

6. **Phase 6 — Onboarding UI & Permissions Screen**
   - Implement `OnboardingViewModel` (`@HiltViewModel`) and `OnboardingScreen.kt` (Jetpack Compose, 3-step `NavHost` wizard: Welcome → Permissions → Connection Test).
   - Implement `PermissionsScreen.kt` with `ActivityResultContracts.RequestMultiplePermissions`, rationale dialogs, and permission denial banners.
   - Implement `MainActivity.kt` with first-run detection, navigation to onboarding vs. settings, and notification click routing via `EXTRA_OPEN_SCREEN`.
   - Include a **placeholder Settings destination** (an empty Compose screen with a title) so `MainActivity`'s navigation graph compiles and the wizard's "Done" button has a landing screen. Phase 7 replaces this stub with the real `SettingsScreen`.
   - *Verification:* Compile with `./gradlew assembleDebug`. Manually verify the onboarding wizard steps render and advance correctly.

7. **Phase 7 — Settings, Detection Log UI & Localization**
   - Implement `SettingsViewModel` (`@HiltViewModel`) and `SettingsScreen.kt` (Connection Health Summary, Google Contacts section, HubSpot Account section with Private App token entry, Stop List, Opt-Out Patterns, Sound & Language settings, Connection Testing, and Activity & Detection Log link).
   - Implement the one-time post-onboarding **"Connect HubSpot CRM?"** dialog on the Settings screen, gated by the `hubSpotPromptShown` flag in `DataStore<Preferences>` (see the Post-Onboarding HubSpot Prompt section).
   - Implement `DetectionLogViewModel` (`@HiltViewModel`) and `DetectionLogScreen.kt` (scrollable log with filter chips and Clear Log button).
   - Externalize **all** UI strings into `res/values/strings.xml` (US English) and `res/values-es/strings.xml` (Spanish). No string literals may remain hardcoded in any Compose file.
   - Generate `TEST_CASES.md` and `INSTALL_GUIDE.md`.
   - *Verification:* Run full test suite (`./gradlew test connectedAndroidTest`). Compile both APK variants (`./gradlew assembleDebug assembleRelease`). Manually verify the Settings screen and Detection Log render correctly in both English and Spanish.