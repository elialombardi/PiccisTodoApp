# Android Tasks section

Implemented against the Spending Tracker task/project contract prepared 9 November
2026 and updated 10 November 2026. This repository is a native Android application
(Jetpack Compose, Hilt, Retrofit/Gson), not the web/backend repository referenced
by the handoff. No backend changes or iOS target are included.

## Integration clarifications

- The web homepage/route policy does not change mobile's global start screen:
  mobile continues to open Notes after login. Tasks remains available to every
  authenticated role, matching the API's authentication-only policy.
- Work Tasks uses UUIDs, projects, costs, work dates and `sentOn`. It is separate
  from the numeric-ID **Tasks list** folder type in [Notes & lists](NOTES_AND_LISTS.md),
  which uses `completed` and optional `endDate`. Work-task PUTs replace the full
  writable record; Notes PUTs preserve omitted fields.
- There are no task reminders, Telegram schedules or message-delivery actions.
  Marking as sent only stores a date. Records are shared among authenticated users.
- Required first-release operations only: no Prepare, mark-all, reassignment,
  task/project deletion or project editing was added.

## Setup

1. Use the existing Android Studio/Gradle wrapper setup and installed SDKs
   specified by `app/build.gradle.kts` and `gradle/libs.versions.toml`.
2. Obtain the development/staging/production **HTTPS origin** and a test account
   from the deployment owner through the normal secure channel. No origin or
   credentials are embedded in this feature. Use an origin such as
   `https://your-deployment.example/`, not a device-local `localhost`, and use
   valid certificates. Do not disable certificate checks.
3. Configure that origin in the existing login screen and sign in normally.
   Prefer the origin root (without `/api`) for compatibility with the existing
   Notes/login client. Tasks always addresses `/api/tasks` and `/api/projects`
   at the origin root, so it never produces `/api/api/...`.
4. Open **Tasks** from the bottom navigation. All authenticated roles use the
   same actions; task/project data is **shared**, not user-private.
   **Account** contains the workspace connection, biometric preference, and
   confirmed sign-out. Bottom navigation is hidden in task details/editors so
   leaving unsaved edits goes through the normal Back/discard flow.

Bearer tokens reuse the existing session and are now encrypted at rest using a
non-exportable Android Keystore AES-GCM key. Existing plaintext stored tokens
are migrated on first read. A restored/corrupted encrypted token without its
key is invalidated and requires login. Task data is only held in the
navigation-entry ViewModel, not persisted as an offline cache. A Tasks 401 clears
the session, biometric session flag, Tasks state, and protected navigation stack.
There is no refresh-token endpoint or retry loop.

## Walkthrough

1. Open Tasks: **Not sent** is the initial view. Check pending count, pending
   EUR total, and total task count. Pending records are grouped by task month
   ascending and date ascending. UUID string comparison breaks date ties.
2. Choose **Add project**. Enter a name and optional description, then Save.
   Duplicate names produce an inline error and retain entered values.
3. Choose **Add task**, or create a project from inside its editor. The project
   is automatically selected without losing other entered task data. A task
   needs an existing selected project, trimmed nonempty name, finite decimal
   cost, and real task date. Decimal comma input is accepted; negative costs,
   future dates, and sent dates before work dates are not prohibited.
4. Dates can be typed as `YYYY-MM-DD` or selected using native calendar controls.
   Today is the local calendar date; date-only strings are never converted via
   UTC instants. Optional description is trimmed and blank sent date means
   Not sent. Default create cost is 10.
5. Tap a task for every field and **Edit task**. Edits use the same prefilled
   form. Back prompts before discarding changed task/project inputs. Saves are
   disabled while in flight; only confirmed responses close/reset a form.
6. In details, **Mark as sent** asks for a date (today by default).
   **Mark as not sent** confirms clearing the date. Both explain that no email,
   invoice, notification, or message is transmitted. Each shortcut fetches the
   latest task, then sends a full writable record with only `sentOn` changed.
7. **History** contains **all tasks**, both statuses, by task date descending,
   with descending UUID strings breaking ties. Sent-on-create records appear
   here, not in Not sent. Missing historical projects have a fallback label and
   remain in totals; select a valid project before saving such records.
8. Pull down or choose Refresh to reconcile with the web client. Returning from
   the background also refreshes. Failed loads identify tasks/projects
   separately. Loaded data remains visible with an explicit stale warning.
9. After an uncertain write, the form stays open and Save is blocked. Review
   refreshed server records before explicitly allowing another save. If the
   record already exists/changed as intended, leave the form instead. No POST
   or stale PUT is automatically replayed. A deleted task is never recreated.
   Closing/reopening editors does not bypass this guard. Review is only accepted
   once both datasets have loaded successfully and no refresh is in flight.
   Response-parsing failures during writes are treated as uncertain, not as safe
   local configuration errors: the server may already have stored the change.

## Implementation map

| File | Responsibility |
| --- | --- |
| `data/model/Task.kt` | Response/request DTOs, exact decimal totals, validation, stable ordering, date-only helpers |
| `data/remote/TasksApi.kt` | Plain-array task/project endpoints and full PUT contracts |
| `data/repository/TasksRepository.kt` | Configured HTTPS origin, bearer header, timeouts, disabled automatic retries/redirects, sanitized text/JSON failures |
| `ui/viewmodels/TasksViewModel.kt` | Concurrent list fetches, obsolete-response guards, forms, confirmed mutations and reconciliation |
| `ui/screens/TasksScreen.kt` | Overview, details, task/project forms, clearable calendars, confirmation and error/review UI |
| `res/values/tasks.xml` | English resource strings ready for translation; follows the existing English UI |
| `MainNavigation.kt`, `ui/screens/AccountScreen.kt` | Section navigation, account settings, sign-out and reauthentication routing |
| `data/local/TokenCipher.kt`, `SettingsManager.kt` | Secure token storage and legacy migration |
| `app/src/test/.../TasksContractTest.kt` | HTTP fixtures, numeric payloads, optional clears, dates, validation, status, totals and ordering |
| `app/src/test/.../TasksControllerTest.kt` | Concurrent/stale loads, obsolete responses, forms, status preservation, duplicate saves, authentication and uncertain outcomes |
| `app/src/androidTest/.../TasksScreenTest.kt` | Compose workflows, large-text/dark mode and fixture screenshots |

Paths above are relative to the application's package under `app/src/main`,
except resources and test paths beginning with `app/`. Writable payloads contain `projectId`, `name`, numeric `cost`,
`date`, `sentOn`, and `description`; never `id`, `projectName`, or `status`.
Optional clears are explicit empty strings. HTTP errors expose only recognized
validation messages through local resource strings, never raw server internals.

## Build and automated checks

Run from the repository root in PowerShell:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
```

Run the Tasks screen suite on a chosen emulator (set `ANDROID_SERIAL` so an
attached physical device is not selected inadvertently):

```powershell
$env:ANDROID_SERIAL = 'emulator-5554'
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.elialombardi.piccistodo.TasksScreenTest' --console=plain
```

Some connected-test runners reset display overrides and uninstall the test APK
afterward. For a specific size, build both APKs with `:app:assembleDebug` and
`:app:assembleDebugAndroidTest`, then install/reinstall them before a direct run:

```powershell
adb -s emulator-5554 install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell wm size 720x1280
adb -s emulator-5554 shell wm density 320
adb -s emulator-5554 shell am instrument -w -r -e class com.elialombardi.piccistodo.TasksScreenTest com.elialombardi.piccistodo.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 shell wm size reset
adb -s emulator-5554 shell wm density reset
```

Record `wm size`/`wm density` in the run log and restore both even if a run fails.
The test captures synthetic-data screenshots under the app's external-files
`tasks-qa` directory. No staging access or embedded credentials are used.

Core library desugaring supports `java.time` on minimum SDK 24.

### Local validation, 10 November 2026

- Debug build and lint passed. Lint: **0 errors, 38 warnings, 2 hints**; these
  warnings/hints remain and are not represented as a clean warning-free release.
- **66 JVM tests passed**, including **25 Tasks tests** (9 contract, 16 controller).
- **All 6 Tasks screen tests passed on each of three API 35 emulator profiles:**

  | Profile | Verified display | Result | Log under `app/build/` |
  | --- | --- | --- | --- |
  | Default phone | 1080×2400 px, 420 dpi | 6 passed, 0 failures | `tasks-phone-validation.txt` |
  | Small phone | 720×1280 px, 320 dpi (360×640 dp) | 6 passed, 0 failures | `tasks-small-phone-validation.txt` |
  | Tablet-size override | 1600×2560 px, 240 dpi (about 1067×1707 dp) | 6 passed, 0 failures | `tasks-tablet-validation.txt` |

  Small-phone/tablet runs invoked instrumentation directly after verifying the
  display overrides; the display was restored afterward. These are size profiles
  on the same emulator, not separate hardware-device or OS-version certifications.
  The screen suite exercises mixed-status History, status confirmation/clearing,
  project creation while retaining task input, decimal-comma input with the
  keyboard open, disabled Save without projects, discard confirmation, stale
  loads, retained uncertain edits, and 1.8× text in dark mode.
- HTTP fixtures exercise 200/201 responses, empty arrays, 400/401/403/404/409/500
  errors, plain-text and JSON error parsing, and full numeric PUT/POST payloads.
  Controller fixtures simulate connectivity failures, lost create responses,
  stale refreshes, concurrent edits/deletions and expired authentication.
- Date-only JVM checks cover Kiritimati (UTC+14), Los Angeles (west of UTC), and
  Tokyo; decimal-comma input, negative costs and unconstrained date order are covered.
- These are **local fixture checks**, not proof of staging/backend integration,
  mobile/web persistence, production HTTPS configuration or device accessibility.

### Acceptance coverage map

| Handoff scenarios | Local evidence | Still needs deployment/device QA |
| --- | --- | --- |
| AC01–AC03, AC11–AC12, AC14 | Summary/status/order/legacy contract tests; loading/empty/controller tests | Representative shared records and role accounts |
| AC04–AC08, AC13 | Project-selection preservation, numeric payloads, full edits, validation/date tests | Reload in both mobile and web; real duplicate-name behavior |
| AC09–AC10 | Fresh-fetch/full-record status tests; date clearing and preservation | Concurrent real-client edits and server persistence |
| AC15–AC19 | Stale retention, obsolete-response guards, 401/403/404, input retention, duplicate-save and uncertain-outcome tests | Real slow/offline networks, expired sessions, process/device behavior |
| AC20–AC21 | Not applicable | Optional operations intentionally omitted |

### Screenshots

Synthetic local fixtures only; no live account or backend data. Full galleries:
[small phone](tasks-screenshots/small-phone) and [tablet-size](tasks-screenshots/tablet).

| Screen / workflow | Small phone | Tablet-size |
| --- | --- | --- |
| Overview | [Screenshot](tasks-screenshots/small-phone/01-overview.png) | [Screenshot](tasks-screenshots/tablet/01-overview.png) |
| Mark-as-sent confirmation | [Screenshot](tasks-screenshots/small-phone/02-status-confirmation.png) | [Screenshot](tasks-screenshots/tablet/02-status-confirmation.png) |
| Task creation/editor | [Screenshot](tasks-screenshots/small-phone/03-task-editor.png) | [Screenshot](tasks-screenshots/tablet/03-task-editor.png) |
| Stale-data error | [Screenshot](tasks-screenshots/small-phone/04-stale-error.png) | [Screenshot](tasks-screenshots/tablet/04-stale-error.png) |
| Large-text dark details | [Screenshot](tasks-screenshots/small-phone/05-large-text-dark-details.png) | [Screenshot](tasks-screenshots/tablet/05-large-text-dark-details.png) |

Reports and APKs:

- `app/build/reports/lint-results-debug.html`
- `app/build/reports/tests/testDebugUnitTest/index.html`
- `app/build/reports/androidTests/connected/debug/index.html`
- `app/build/tasks-verification.log`
- `app/build/outputs/apk/debug/app-debug.apk`

## Remaining release QA and limitations

- Obtain reachable environment origins and test accounts through the normal
  secure channel, then complete AC01–AC19 against the deployment and web client
  with shared records. No live backend validation is claimed by this delivery.
  Optional AC20/AC21 do not apply: Prepare, mark-all, reassignment, deletion,
  and project editing are intentionally omitted.
- Validate supported OS versions (especially SDK 24), small screens, keyboard
  open, large fonts/TalkBack, long names/descriptions, decimal-comma input,
  calendar rotation, time zones on both sides of UTC, and returning from
  background while requests are pending. Capture screenshots or recordings of
  the walkthrough using authorized non-sensitive test data. Perform this
   validation manually in addition to the automated fixture checks. API 24 and
   the full supported OS matrix, physical-device TalkBack/focus/touch targets,
   native date-picker/time-zone behavior, and translated-label review remain
   release checks. Automated screenshots do not constitute visual or accessibility
   sign-off.
- Exercise slow/failed connections, expired sessions, duplicate project names,
  deletion by another client, and a timed-out create that actually succeeded.
  Confirm retained input, stale labels, explicit reconciliation, and no duplicates.
- The backend has no ETags/version checks. Fetch-before-status-update narrows
  but **does not eliminate** the full-record overwrite race. Ordinary edits can
  overwrite another client's changes. This is not conflict-safe editing.
- Drafts survive configuration changes in the ViewModel, not process death.
  There is no offline write queue, background polling, or disk task cache.
- No production signing/release artifact was generated or altered. Existing
  signing keys, credentials, and unrelated working-tree artifacts are untouched.
