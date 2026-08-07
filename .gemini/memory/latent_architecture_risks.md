# Latent Architecture Risks (SMS Filter)
*Generated: 2026-08-06*

This document captures latent structural flaws in the SMS Filter app that currently "work" but pose future risks. Keep these in context for future code generation or refactoring.

## 1. The `SmsReceiver` null-segment vulnerability (🔴 High)
**File:** `SmsReceiver.kt`
**Issue:** `messages.joinToString` replaces null segments with empty strings (`segment.messageBody ?: ""`).
**Risk:** If a multi-part PDU is malformed and drops a segment, the concatenated body is quietly incomplete. Because opt-out detection relies on the final line (`LAST_LINE_EXACT`, `LAST_LINE_CONTAINS`), dropping the final segment makes the "last line" actually the middle of the message. Legitimate opt-outs will be missed silently.

## 2. Unsafe Destructive Migration in Release (🔴 High)
**File:** `DatabaseModule.kt`
**Issue:** `fallbackToDestructiveMigration(dropAllTables = true)` is unconditional.
**Risk:** `architectural_analysis.md` explicitly warns against this. If `AppDatabase.version` is ever bumped in a release build, Room will instantly wipe the user's stop-list, custom patterns, and detection log.
**Required Fix:** `if (BuildConfig.DEBUG) fallbackToDestructiveMigration(...)`

## 3. UI Test Abandonment (🟠 Medium)
**File:** `build.gradle.kts`
**Issue:** Compose UI test dependencies (`ui-test-junit4`) are intentionally excluded.
**Risk:** The spec required the architecture to support UI testing later. Because the dependency catalog (`libs.versions.toml`) and BOM are tightly pinned, adding UI tests in the future will require complex version realignment. The app has 176 JVM tests but zero UI verification.

## 4. `READ_CONTACTS` Revocation Race (🟠 Medium)
**File:** `ContactRepository.kt`
**Issue:** Missing `READ_CONTACTS` permission safely defaults to treating the sender as unknown.
**Risk:** If a user revokes the permission in OS Settings, a message from a known contact containing "STOP" will be treated as a stranger, and the app *will auto-reply to the contact*. The app handles the technical exception safely, but the UX consequence is dangerous.

## 5. HubSpot Token Memory Leakage (🟡 Low)
**File:** `SettingsViewModel.kt`
**Issue:** The HubSpot CRM token is handled as an immutable `String`.
**Risk:** Strings are interned in the JVM and linger in memory until garbage collection. For high-privilege credentials, this violates strict security practices (prefer `CharArray` cleared after use).

## 6. RCS Traffic is Structurally Invisible (❌ Accepted Limitation)
**File:** `architectural_analysis.md`
**Issue:** Messages delivered via RCS (Google Messages "Chat features") do not fire `SMS_RECEIVED` broadcasts.
**Risk:** The app cannot see them. This is an accepted limitation because RCS is currently used for person-to-person chat, not marketing. If RCS Business Messaging (RBM) becomes common, this app will become obsolete unless it adopts `NotificationListenerService`.