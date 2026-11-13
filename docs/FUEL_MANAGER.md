# Fuel Manager — Android delivery and QA

## Scope and setup

- Native Kotlin/Compose section in the existing workspace navigation, alongside Collections, Tasks and Account.
- Uses the existing configured HTTPS API server, encrypted bearer-token storage, login and expired-session navigation. No new credentials, origin, backend model or data migration is required.
- Configure the normal server address and sign in normally. Paths are rooted at `/api/fuel`, regardless of whether the saved server URL ends with `/api`.
- Login now retains the response's `role`. Only the exact `Admin` role enables destructive controls. Reader and Writer accounts can create per the cycle.
- Sessions saved by an older app version have no stored role; sign out and sign in again to enable Admin controls. Unknown/missing roles do not grant deletion privileges. Server authorization remains authoritative.
- Reusing the same server and account reads existing fuel history automatically. No copying, importing, recreation or reset is performed on rollout.
- Android minimum API 24; project compile/target API 37. This repository has no iOS target.

## Implementation

- `data/model/Fuel.kt`: strict direct-object/array decoding, numeric unsigned user ID (`BigInteger`), UUID identities, lowercase fuel codes, offset-aware fractional timestamps and localized device-zone date/time.
- `data/repository/FuelRepository.kt`: Retrofit/OkHttp following the Tasks repository pattern; authenticated HTTPS reads/create/delete/reset, exact 200/201/204 success handling, safe plain-text/JSON error mapping. Connection replay and redirects are disabled; calls time out after 30 seconds. A confirmed create's response body is not needed to advance UI state: server stats/history are fetched again.
- `ui/viewmodels/FuelViewModel.kt`: testable controller with concurrent stats/history loading, per-resource errors, stale flags, write serialization, generation guards, exact role gating and explicit destructive confirmations. Session changes clear state and cancel obsolete work. No fuel disk cache or offline write queue.
- `ui/screens/FuelScreen.kt`: pinned next-fill guidance, statistics, cycle-gated single-tap actions, pull-to-refresh, refresh on resume/return, virtualized complete history, text badges, Admin overflow reset and record-specific confirmations.
- `res/values/fuel.xml`: all feature copy and plurals use the project's Android resource convention. Default language is English, as with the existing screens; alternate-language translations have not been added.

The server's `/stats` result is authoritative. The app does not optimistically insert records or increment the cycle locally. It validates statistics and checks successful stats/history loads for detectable inconsistency; ambiguity from equal timestamps is not resolved using UUIDs. Invalid/unavailable/stale statistics disable creation. A history-only failure is separate and does not invalidate independently fresh, valid stats.

### Uncertain writes

A timeout, network failure or server-side write failure can leave an uncertain outcome. No POST or destructive request is automatically replayed. The app refreshes, retains an uncertainty explanation, and requires the user to review successfully refreshed history before enabling new operations. Reset/delete then require a fresh destructive confirmation. If reconciliation fails, Retry refresh remains available; writes stay disabled.

After HTTP 201, the saved confirmation remains visible even if refresh fails, explicitly directing the user to refresh rather than submit again. Successful deletion/reset also refetch both resources. HTTP 401 clears the session through existing authentication navigation, without a retry loop; 403 removes destructive privileges, and 404 deletion explains that the record is no longer available and reconciles history.

## Verification performed (2026-11-10)

Final command: `gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:connectedDebugAndroidTest --console=plain`.

| Check | Result |
| --- | --- |
| Kotlin/Java compilation and debug APK assembly | Passed |
| JVM tests | 14 passed, zero failures |
| Android lint | Passed, zero errors; warnings/hints remain in the project report |
| Connected Compose tests, Pixel 7 API 35 / Android 15, standard display, Asia/Tokyo | 3 passed |
| Compose tests repeated at 720×1280 px / 320 dpi (360×640 dp), America/Los_Angeles | 3 passed |
| Large text / dark mode | Covered by the UI test at 200% font scale, including a 60-record scrollable history |
| Timestamp offsets, fractional seconds and day changes across UTC | JVM assertions for Los Angeles and Tokyo; UI suite also executed in both device zones |

All UI tests use an isolated in-memory data source. HTTP contract tests use a local MockWebServer. **These results are not live-backend acceptance or production-account testing.** No real user history was created, deleted or reset.

An initial small-screen rerun encountered an Activity teardown timeout after assertions. Repeating with the emulator awake and unlocked passed; the final standard-size and small-screen runs passed. Keep the emulator awake/unlocked when reproducing UI tests. Display and time-zone overrides were restored after the final run.

### Automated coverage

- `FuelControllerTest` (8 tests): two complete cycles and remaining values 4/3/2/1/0; early/disallowed actions; duplicate taps; saved-but-refresh-failed; uncertain POST reconciliation without replay; partial failures; inconsistent snapshots; Reader/exact-Admin checks; destructive cancellation; selected-ID deletion; deleting latest Benzina; reset; failed deletion; uncertain reset requiring fresh confirmation; obsolete responses and session-state separation.
- `FuelContractTest` (6 tests): create body contains only lowercase `fillType`; strict missing/malformed record/stat rejection; numeric user IDs; UUIDs; offsets/fractional seconds; localized timestamps on both sides of UTC; newest-first ordering; actual Retrofit paths and 201/204 handling; safe plain-text and JSON `error`/`message` handling; 400/401/403/404/500 mappings; cycle rejection without phantom records; session expiry without retry loops.
- `FuelScreenTest` (3 tests): full Reader cycle and hidden destructive controls; Admin deletion/reset confirm/cancel interactions; Writer large-text dark-mode history and guidance. These exercise real Compose screens with fake data, not a bypass of production authentication.

Reports are generated under:

- `app/build/reports/tests/testDebugUnitTest/index.html`
- `app/build/reports/lint-results-debug.html`
- `app/build/reports/androidTests/connected/debug/index.html`
- `app/build/fuel-small-screen-validation.txt` (direct instrumented rerun)

The APK is at `app/build/outputs/apk/debug/app-debug.apk`. If the IDE reports unresolved newly added test libraries despite a passing Gradle build, synchronize its Gradle project model.

## Screenshot walkthrough

Screenshots are captured from the API 35 emulator at the small-screen size with **fake test records**. They demonstrate UI behavior, not persistence in the real service.

1. [Empty Reader overview](fuel-screenshots/01-empty-reader.png): four GPL remaining, GPL available and Benzina unavailable; no Admin controls.
2. Record GPL four times. [Benzina due](fuel-screenshots/02-benzina-due.png): GPL unavailable, Benzina available, text explains the requirement.
3. Record Benzina. [Cycle complete](fuel-screenshots/03-cycle-complete.png): GPL available again, four remaining; lifetime counts are retained.
4. [Admin record deletion](fuel-screenshots/04-delete-confirmation.png): selected record's fuel/date-time, irreversible warning and Cancel. Tests cancel once without a write, then confirm and refresh.
5. [Own-history reset](fuel-screenshots/05-reset-confirmation.png): destructive scope and four-GPL restart are explicit. Tests cancel without a write, then confirm and verify empty statistics.
6. [200% text and dark history](fuel-screenshots/06-large-text-dark-history.png): guidance remains pinned while the history scrolls.

## Remaining release validation

Dedicated staging regular-user/Admin accounts and an approved HTTPS origin were not supplied. Obtain them securely through normal project channels; never commit them. Complete the following against staging, with dedicated disposable data only:

- AC01–AC06: persisted full/repeated cycles; reopen/navigation refresh; verify existing web history appears without migration.
- AC07–AC10, AC19–AC23: real cycle rejection after a web/mobile race; delayed/lost connection around POST/delete/reset; confirmed-save refresh failure; plain-text service errors; expired token, revoked Admin and already-deleted record. Unit/contract tests cover client behavior, but real radio/server failures remain untested.
- AC13–AC17: real account switching (Reader/Writer/Admin), server-side user scope and numeric IDs, actual 204 delete/reset and recalculation after latest Benzina deletion. Local controller/UI tests do not establish backend enforcement.
- Supported physical Android devices, especially API 24 and target API 37; TalkBack traversal/announcements, touch targets, landscape, system font scaling and translated/long labels. API 35 emulator checks are not a complete supported-OS/device matrix.
- Additional language translations and linguistic review as required by the app's release locales. Current resources follow the app's default-English convention; translated-label device validation is pending.

## Limitations and intentional omissions

- The mobile client cannot fix the backend's non-atomic cycle precheck/insert or provide global duplicate prevention across web/mobile. Statistics/history requests are not an atomic snapshot. Refresh/reconciliation is used; no global concurrency guarantee is claimed.
- No idempotency key exists. Refreshed history cannot always prove which client created an uncertain record; the UI does not claim certainty.
- No automatic background polling, offline writes, arbitrary-ID or cross-user administration, ordinary-user deletion, editable dates, vehicles, litres, expenses, odometer, station or notes.
- Optional last-record card/endpoint and date filtering are omitted. History is unpaginated and fully available via a lazy list. There is no range cache to invalidate.
- The optional server range endpoint has inclusive UTC-midnight bounds, not inclusive device-local days. Any future local-day filter must explicitly define/test boundaries or use a corrected backend contract; this implementation does not append days or imply corrected semantics.
