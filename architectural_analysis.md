# Architectural Analysis: SMS Compliance Filter

This document provides a comprehensive architectural review of the specifications in [Prompt.md](file:///Users/bill/code/smsfilter/Prompt.md), specifically evaluating the recent updates made to handle **multi-part PDU reconstruction** (concatenated SMS) and permission optimization (`READ_SMS` removal).

Below are the identified architectural risks, platform constraints (for Target SDK 35), and recommended mitigations.

---

## 1. ~~Usability Deadlock: Subsequent Startup Flow~~ ✅ RESOLVED
> [!NOTE]
> **Fixed in Prompt.md** — `moveTaskToBack(true)` removed from the post-onboarding startup path.

### Resolution:
* `Prompt.md` now specifies that the **Settings screen is always shown** on a normal launcher launch.
* Notification clicks navigate directly to the appropriate screen (Detection Log or Settings).
* `moveTaskToBack(true)` is explicitly prohibited in the post-onboarding flow.

---

## 2. ~~Android 14/15 (API 34/35) Foreground Service Type Crash~~ ✅ RESOLVED
> [!NOTE]
> **Fixed in Prompt.md & walkthrough.md** — The specification has been updated to explicitly require the `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_DATA_SYNC` permissions, and to explicitly configure WorkManager's `SystemForegroundService` with `android:foregroundServiceType="dataSync"` in the manifest using `tools:node="merge"`.

### Resolution:
* The manifest declarations now mandate the type-specific permission `FOREGROUND_SERVICE_DATA_SYNC`.
* The WorkManager service merging requirements are fully integrated into the worker specifications to prevent runtime `SecurityException` crashes on Android 14+.

---

## 3. "Data SMS" vs. Concatenated Text SMS Clarification
> [!NOTE]
> **Terminology & Scope Clarification**

### The Terminology:
* In Android, a **"Data SMS"** refers to a binary message sent to a specific port, received via `android.intent.action.DATA_SMS_RECEIVED` (requiring `RECEIVE_WAP_PUSH` or specific permissions). These contain binary payloads, not plain text, and are not used for standard business-to-consumer text messaging.
* A **Concatenated SMS (Multi-part SMS)** is a standard text message exceeding 160 characters, split into multiple text segments and received via `android.provider.Telephony.SMS_RECEIVED`.

### Analysis:
* The user's changes correctly target **Multi-Part PDU Reconstruction** (concatenated text SMS), which is the correct approach for monitoring opt-outs.
* To avoid confusion for developers or AI code generators, we should explicitly document that the app monitors standard text SMS broadcasts (`SMS_RECEIVED`) and does *not* listen to binary/port-based data SMS (`DATA_SMS_RECEIVED`).

---

## 4. SMS Loop & Flooding Protection (Auto-Reply Cooldown)
> [!WARNING]
> **Carrier Quota / Cost Risk**

### The Problem:
* If an automated responder on the other side reacts to our auto-reply (e.g., we reply `stop`, their system auto-replies with *"You have unsubscribed. Reply stop2stop to re-subscribe"*), it could trigger an infinite loop of auto-replies.
* This could exhaust the user's SMS allowance, generate carrier spam flags, or incur high costs.

### Recommendation:
* Add an **Auto-Reply Cooldown** mechanism (e.g., maximum of 1 auto-reply per sender phone number per 24 hours).
* This can be stored in a simple Room table `AutoReplyCooldownEntity` containing `phoneNumber` and `timestamp`. The worker checks this table before sending an SMS.

---

## 5. Multi-Part PDU Sender Verification
> [!TIP]
> **Robustness & Security**

### The Problem:
* When receiving concatenated SMS, the spec suggests taking the originating address (sender) from the first segment.
* While standard, it is technically possible for multiple SMS messages to arrive in quick succession and be passed to a single broadcast receiver instance.
* To ensure data integrity, `SmsReceiver` should verify that **all** extracted segments in the array have the same originating address before concatenating their bodies. If there is a mismatch, segments should be grouped and processed by sender.

---

## 6. Real-Time HubSpot CRM Lookup Latency & Rate Limits
> [!IMPORTANT]
> **Performance & Thread Block Safety**

### Analysis:
* **Rate Limits (HTTP 429)**: HubSpot APIs have strict rate limits. If the user is flooded with SMS, multiple parallel worker executions could hit these limits.
* **Network Failures**: In areas with poor connectivity, real-time lookups might time out (enforced at 5 seconds).
* **WorkManager Expedited Execution**: Expedited tasks have strict runtime execution limits (usually around 1-2 minutes). If network calls take too long, the system can kill the worker.

### Recommendation:
* Ensure that if the HubSpot API lookup returns an HTTP 429 (Rate Limit) or times out (5 seconds), the worker immediately falls back to treating the contact as **unknown** and runs the opt-out detection logic. It should not block the thread or loop indefinitely, prioritizing the prompt delivery of the opt-out auto-reply.

---

## 7. Room DB Fallback to Destructive Migration in Production
> [!CAUTION]
> **Accidental User Data Loss**

### The Spec Constraint:
> * "Configure the Room database builder with `fallbackToDestructiveMigration()` so that the database schema is safely recreated if changed..."

### The Problem:
* Using `fallbackToDestructiveMigration()` in release builds means that if an update modifies the Room schema, the user's local settings, Stop List keywords, and detection logs are instantly wiped out.

### Recommendation:
* Limit destructive migration specifically to **Debug builds** using conditional configuration, and enforce the creation of explicit Room migration paths for Release builds to preserve user data.

---

## 8. Persistable Uri Permissions for Selected Sound Files
> [!IMPORTANT]
> **SecurityException / Playback Failures**

### The Problem:
* The Settings screen allows users to select a custom sound file URI using the system ringtone/notification picker.
* When the user selects a file from the system picker, the app gets temporary access to that content URI.
* If `SmsLookupWorker` triggers in the background after the main activity process has been killed and restarted, it will attempt to read/play the URI. Without a persistable permission grant, the system will throw a `SecurityException` (access denied), causing the sound playback to fail.

### Recommendation:
* When the user selects a custom sound URI in the Settings screen, the app must take persistable URI read permissions:
  ```kotlin
  val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
  contentResolver.takePersistableUriPermission(selectedUri, takeFlags)
  ```
* Document this requirement in [Prompt.md](file:///Users/bill/code/smsfilter/Prompt.md) to ensure correct implementation.

---

## 9. Process Death and Hilt-Managed In-Memory Cache
> [!NOTE]
> **Cache Expiration Clarification**

### Analysis:
* The spec calls for an in-memory `LruCache` (15-minute expiration) to avoid redundant HubSpot queries.
* Since the application relies on event-driven execution (no persistent foreground service), the Android OS is free to terminate the application process when idle.
* When the process is terminated, the in-memory cache is wiped. A subsequent incoming SMS will spawn a new process, leading to a cache miss and a HubSpot API request.
* While this is acceptable and preserves privacy (since numbers are never written to disk), the developer should expect cache hit rates to be low during periods of low activity. No architectural changes are needed, but this process-death behavior should be accepted as a design trade-off.

---

## 10. Phased Code Generation & Modular Build Strategy ✅ RESOLVED
> [!NOTE]
> **Integrated into Prompt.md & AGENTS.md** — Development strategy structured into 7 sequential, incremental phases to prevent token truncation, stubbed code, and Hilt circular compilation failures.

### Key Architectural Safeguards:
1. **Co-located Hilt Modules**: Hilt modules (`DatabaseModule`, `RepositoryModule`, `NetworkModule`) are strictly generated in the exact phase where their referenced classes are created. `RepositoryModule` uses a temporary no-op placeholder for `HubSpotRepository` in Phase 4 to allow worker testing before Phase 5 network integration.
2. **Pure, Decoupled Engine**: `OptOutDetector` and `StopListMatcher` are implemented as pure classes taking data models (`List<OptOutPatternEntity>`, `List<StopListEntity>`) as parameters rather than injecting Room DAOs. This allows JVM unit testing without database or Android framework mocks.
3. **Explicit Worker Pre-Flight IO**: `SmsLookupWorker` executes a strict 8-step pipeline, beginning with an IO block on `Dispatchers.IO` to read runtime settings (`useHubSpot`, `beepOnOptOut`, `soundFileUri`) and fetch live pattern/stop lists from Room DAOs before evaluating any incoming message.
4. **Pinned Dependency Catalog**: All library versions are pinned in `libs.versions.toml` to prevent version drift between Kotlin, KSP, Compose BOM, and Hilt.
5. **Code Standards & Licensing**: All generated `.kt` files enforce BSD 3-Clause licensing (authored by Bill Roth <bill.roth@gmail.com>) and mandatory KDoc documentation across all public API surfaces as defined in `.agents/AGENTS.md`.

