```markdown
## Android SMS Compliance Filter — App Specification

### Overview

Build a production-ready Android application in Kotlin that monitors incoming SMS messages and flags potential opt-out requests from unknown senders. The app runs as a persistent background service and cross-references incoming numbers in real-time against Google Contacts and HubSpot CRM (without storing any phone number data locally) before applying opt-out detection logic.

Target SDK: 35 (Android 15), Min SDK: 26 (Android 8.0). All code must be readable and buildable in Android Studio Quail 1 | 2026.1.1 Patch 2 or later. All files and public functions must use KDoc documentation.

**Package:** `com.digiroth.smsfilter` — use this package name everywhere in the generated code.

---

### Architecture

Use a clean architecture pattern with these layers:

- **UI Layer** — Jetpack Compose for all screens
- **Service Layer** — Foreground Service for SMS monitoring
- **Data Layer** — Room database for settings, stop list, opt-out patterns, and detection logs (no phone number data is stored locally)
- **Repository Layer** — abstracts Contact, HubSpot, and Settings data sources

Use Hilt for dependency injection. Use Kotlin Coroutines + Flow throughout.

---

### Onboarding & First-Run Flow

On first launch (detected via a `firstRunComplete: Boolean` flag in `DataStore<Preferences>`), the app must walk the user through a mandatory setup wizard before the SMS service starts. The wizard uses a `NavHost` with these sequential steps:

**Step 1 — Welcome**
Brief explanation of what the app does. "Get Started" button advances to Step 2.

**Step 2 — Permissions**
Request all required permissions (see Permissions section). The user cannot advance until `RECEIVE_SMS` and `POST_NOTIFICATIONS` (API 33+) are granted. Other permissions show a warning if denied but do not block advancement.

**Step 3 — Google Account**
Prompt the user to connect their Google account. Connecting is required to advance; show a "Skip Google (not recommended)" escape hatch that records a `googleSkipped: Boolean` flag and advances.

**Step 4 — HubSpot (conditional)**
Only shown if the "Use HubSpot" toggle is on (which it is by default).
- The toggle is shown in this step and defaults to **on**.
- If `BuildConfig.HUBSPOT_CLIENT_ID` is non-empty, immediately launch OAuth on arrival at this step. The step is not completable until OAuth succeeds or the toggle is turned off.
- If `BuildConfig.HUBSPOT_CLIENT_ID` is empty, show the inline Client ID text field (same behavior as the Settings screen). The user must either enter a valid Client ID and complete OAuth, or turn the toggle off, to advance.
- Turning the toggle off skips OAuth entirely and advances with one tap.
- The OAuth screen offers both HubSpot login and Google login natively — no additional code required beyond launching the standard HubSpot OAuth URL.

**Step 5 — Connection Test**
Trigger a real-time connection test to verify access to Google Contacts and (if HubSpot is toggled on) HubSpot CRM. Show connection status results: *"Google Contacts: Accessible, HubSpot CRM: Connected"* (or just Google if HubSpot is off). "Done" button marks `firstRunComplete = true`, starts the `SmsProcessingService`, and navigates to the main Settings screen.

If the app is force-stopped and restarted mid-wizard, resume at the last incomplete step.

---

### Core Components

#### 1. SMS Receiver & Processing Service

- Register a `BroadcastReceiver` for `android.provider.Telephony.SMS_RECEIVED` (requires `RECEIVE_SMS` permission).
- On receipt, pass the message to a `ForegroundService` (`SmsProcessingService`) for processing. The service must show a persistent notification to satisfy Android 8+ background execution requirements.
- Processing pipeline (in order):
  1. Check stop list words (case-insensitive) → if any match → **ignore** (checked first to avoid redundant API queries).
  2. If not ignored → query Google Contacts (via Android ContactsProvider ContentResolver) and HubSpot CRM (via real-time Contacts API search call) to check if the sender is a known contact.
  3. If found in either → **ignore**.
  4. If not found (unknown sender) → check for opt-out signals (see Opt-Out Detection below).
  5. If opt-out signal found → trigger alert (notification + log entry).

#### 2. Real-Time Contact Verification

To protect user privacy, the app must not store phone numbers locally in any database or cache. All contact lookups are performed in real-time:
- **Google Contacts:** Query the system's `ContactsContract.PhoneLookup` using a `ContentResolver` to check if the incoming phone number belongs to a saved contact. Since this uses the Android local Contacts database, it requires `READ_CONTACTS` permission but does not require network calls.
- **HubSpot Contacts:** If HubSpot is connected, query the HubSpot Search Contacts API (`/crm/v3/objects/contacts/search`) in real-time. Use a search filter to match the `phone` or `mobilephone` properties against the incoming phone number.
- **Normalization:** Prior to lookup, normalize the incoming phone number to E.164 format. When querying HubSpot, search for both the E.164 normalized format and the raw incoming format to ensure a robust match.
- **Caching & Privacy:** Do not persist query results or contact details locally.

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
- Show a high-priority notification: "Opt-out request detected".
- Log the event to a `DetectionLogEntity` (message preview — first 50 chars, timestamp, matched pattern). Note that the phone number must be omitted entirely from the log entity and notification body to prevent storing phone number data locally.

---

### Settings Screen

Single-screen Settings UI built in Jetpack Compose. Sections:

#### Google Account
- "Connect Google Account" button → launches Google Sign-In via `CredentialManager` API (preferred) or legacy `GoogleSignInClient`.
- Once connected, display the account name and a "Disconnect" option.
- Scopes required: `https://www.googleapis.com/auth/contacts.readonly`.

#### HubSpot Account
- **"Use HubSpot" toggle switch** at the top of this section (default: **on/true**).
- When the toggle is **on**, immediately check whether `BuildConfig.HUBSPOT_CLIENT_ID` is non-empty:
  - **If the client ID is missing:** Show an inline error directly beneath the toggle: *"No HubSpot Client ID is configured. Please enter your Client ID below before connecting."* Render an editable text field for the user to enter the Client ID inline. Do not launch OAuth until the field is non-empty and the user taps "Save Client ID." Once saved, write the value to `EncryptedSharedPreferences` under the key `hubspot_client_id_override` and use it in place of `BuildConfig.HUBSPOT_CLIENT_ID` for all subsequent OAuth and API calls.
  - **If the client ID is present** (either from `BuildConfig` or from the saved override): Immediately launch the HubSpot OAuth 2.0 flow via `CustomTabsIntent`. Offer both **HubSpot login** and **Google login** as identity options within the OAuth flow — HubSpot's standard OAuth screen surfaces both natively, so no extra code is needed beyond launching the standard auth URL. The user cannot dismiss this flow without either completing it or turning the toggle back off — enforce this by showing a non-cancelable dialog if they navigate away without completing auth.
- When the toggle is turned **off**, all HubSpot logic — OAuth, index sync, API calls — is completely bypassed. The rest of this section is grayed out and non-interactive.
- Store access token and refresh token securely using `EncryptedSharedPreferences`.
- Once authenticated, display the connected portal name and a "Disconnect" option. Disconnecting clears stored tokens and the client ID override (if one was entered manually), and turns the toggle off.
- Required HubSpot scope: `crm.objects.contacts.read`.
- During SMS processing or connection testing, check the "Use HubSpot" toggle before making the HubSpot API calls. If off, skip HubSpot lookups entirely.

#### Stop List
- Scrollable list of current stop list keywords with delete (swipe or trash icon).
- Text field + "Add" button to add new keywords.
- Keywords stored in Room DB (`StopListEntity`).

#### Opt-Out Patterns
- Same add/delete UI pattern as Stop List.
- Pre-seeded with: `stop2stop`, `end2end`, `stop`, `end`.
- Note in the UI: "All matching is case-insensitive. `stop` and `end` are matched only on the last line; others match anywhere."

#### Connection Testing
- "Test Connection" button that runs a diagnostic check:
  1. Verifies Google Contacts read access.
  2. Verifies HubSpot API accessibility (makes a test request to HubSpot API).
- Display the status (Success/Failure) of the latest diagnostic check.

#### Detection Log
- Link/button to open a scrollable log screen showing recent detections (last 100 entries).
- Each entry: timestamp, matched pattern, message preview (no phone number).
- "Clear Log" button.

---

### Permissions

Request all required permissions on first launch via the onboarding wizard. Required permissions:

```
RECEIVE_SMS
READ_SMS
READ_CONTACTS
INTERNET
FOREGROUND_SERVICE
POST_NOTIFICATIONS          (API 33+)
RECEIVE_BOOT_COMPLETED      (to restart service after reboot)
```

Use `ActivityResultContracts.RequestMultiplePermissions`. Show rationale dialogs for `RECEIVE_SMS` and `READ_CONTACTS` explaining why each is needed. If any critical permission is denied, show a persistent banner in the UI and disable the relevant feature gracefully.

Restart the foreground service on device boot via a `BootReceiver`.

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
        service/              # SmsProcessingService, BootReceiver
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
  local.properties.example
```

---

### Additional Requirements

- **No hardcoded secrets.** HubSpot client ID and client secret must be defined in `local.properties` and injected at build time as `BuildConfig` fields via `build.gradle.kts`:

```kotlin
// local.properties (never commit this file — it is in .gitignore)
hubspot.clientId=YOUR_CLIENT_ID_HERE
hubspot.clientSecret=YOUR_CLIENT_SECRET_HERE

// build.gradle.kts
val localProps = Properties().apply {
    load(rootProject.file("local.properties").inputStream())
}
buildConfigField("String", "HUBSPOT_CLIENT_ID", "\"${localProps["hubspot.clientId"]}\"")
buildConfigField("String", "HUBSPOT_CLIENT_SECRET", "\"${localProps["hubspot.clientSecret"]}\"")
```

- Include a `local.properties.example` file in the repo with placeholder values and a comment explaining how to populate it. `local.properties` must be listed in `.gitignore`.
- If `BuildConfig.HUBSPOT_CLIENT_ID` is empty at runtime and no override has been saved in `EncryptedSharedPreferences`, the "Use HubSpot" toggle must show the inline Client ID entry field as described in the Settings section above.
- **Retry logic** for HubSpot API calls: exponential backoff, max 3 retries.
- **Rate limit awareness**: HubSpot Contacts API search endpoint is rate-limited; handle failures gracefully by falling back to treating the sender as unknown or retrying.
- **Error states**: if Google or HubSpot lookup fails due to network/API issues during real-time processing, default to processing the message for opt-outs (err on the side of safety) and surface connection warnings in Settings.
- Minimum viable happy path must work without HubSpot connected (Google Contacts only mode).
```