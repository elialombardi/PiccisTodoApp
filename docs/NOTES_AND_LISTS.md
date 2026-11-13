# Notes & lists — Android integration

## Setup and scope

- The existing Collections/home section is now **Notes & lists**. Login and the global start screen are unchanged. Existing Notes detail/create routes open the same typed workspace; the legacy untyped editors are no longer routed from the app.
- Uses the existing configured HTTPS server, encrypted bearer-token session, login roles, Material 3 themes and Android string/plural resources. No credentials, bot configuration, new dependencies or data import is required.
- The shared Retrofit provider normalizes the configured URL to its HTTPS origin; full `/api/...` paths are not prefixed twice. Authenticated 401 responses invalidate the rejected session using the existing username/password reauthentication flow. Requests are not automatically replayed and have a 30-second call timeout.
- Deploy the backend folder-type/item-field migrations first. Existing records are read in place. A missing legacy folder type is displayed as Diary without rewriting any item.
- `Writer` and `Admin` can edit; Reader, unknown and missing roles are read-only. Older sessions without stored role metadata require a fresh password login to expose writes.
- **Notes data is shared among authenticated accounts.** The current backend has no per-user Notes ownership filter and does not enforce the Writer/Admin UI policy. Client controls are not server authorization or privacy guarantees.
- Android minimum API 24, compile/target API 37. There is no iOS target in this repository. No Solarized theme exists; the workspace follows the existing light/dark theme.

## Connected behavior

- Folder navigator contains folders only, including nested folders, independent type icons, direct counts, indentation and selected state. It never contains item titles or completion controls.
- The main list uses only `selectedFolder.notes`, in the server's returned order. Child and parent notes are not combined. All returned items remain available in a lazy list.
- Phones use folder → direct items → editor navigation. At 840 dp and wider, folder-only navigation, the direct-item list and editor remain separate panes. The folder pane can be hidden/restored.
- Root and child folder creation default independently to Diary. Folder name/type/position can be configured; mutation responses never replace cached subtrees because their children/notes are empty. Tree refresh selects the created/edited folder.
- Buy lists show title/Purchased; task lists show title, description, optional end date/Done; Diary shows title, multiline content, whole-note style controls and preview. Position and destination folder are common controls.
- Diary style is not HTML or rich-text spans. Controls preserve all five style properties and offer 14/16/18/20/24 font sizes; existing other font sizes are retained unless changed.
- Numeric IDs identify folders/items. Partial PUT payloads contain changed fields only. Immediate list completion writes contain **only** `completed`, including explicit false. Unchanged hidden content, style, description, end date and completion are omitted, not reset.
- End dates remain ISO calendar strings and never pass through UTC. Clearing sends `endDate: ""`, not null. Dates may be in the past; no date-ordering restriction is added.
- Folder type changes do not write notes or children. Moving an item selects its destination and the saved item after refresh. Drafts retain all fields when destination type changes.
- Save/delete operations are serialized. Failed writes retain input and do not optimistically toggle or delete rows. Confirmed saves refetch the tree and reselect the saved item; failed refreshes show **saved, refresh failed** and an independent refresh retry.
- Uncertain writes are not replayed. A successful reconciliation and explicit user review are required before new writes. A malformed body after confirmed HTTP success is treated as saved; the duplicate-create draft is closed and the tree is reconciled.
- Item deletion requires confirmation, closes the editor on success and refetches the selected folder. Cancel sends no mutation. Dirty navigation requires explicit discard; canceling folder creation preserves the previous selected folder.
- Refresh on screen return/resume and pull-to-refresh; old responses are generation-guarded. Session/account/API changes cancel work and clear client selections, drafts and cached tree. No disk Notes cache or offline queue exists.

## Reordering folders and notes (restored 10 November 2026)

- Writer/Admin users can drag the reorder handle on folder cards and on direct
  notes/items in every folder type: Diary, Buy list and Tasks list. Dragging only
  previews the order; position changes are submitted once the handle is released.
- Tap the same handle for **Move up** / **Move down**. Equivalent screen-reader
  custom actions are available, and actions at the first/last boundary are disabled.
  Readers have no reorder controls. A single sibling has no unnecessary handle.
- Root folders reorder among roots. Nested folders reorder only among siblings
  under the same parent. A moved folder keeps all children and notes attached;
  dragging does not reparent folders or mix notes into the folder navigator.
- Notes reorder within the selected folder's direct-item list, not across folders.
  Existing destination-folder editing remains the way to move an item elsewhere.
- Close the editor before reordering, including on tablets. Handles are disabled
  while an editor is open, during loading/saving, when data is stale, or while an
  uncertain write still requires review. Unsaved drafts are never discarded by a
  reorder operation.
- Ordered siblings receive consecutive zero-based positions. Only records whose
  position must change are written. Each PUT body is exactly `{"position": n}`:
  no name, type, parent/folder, completion, date, content, description or style is
  sent. Folder mutation responses never replace cached subtrees.
- The existing API has no atomic bulk reorder operation. Updates run serially and
  stop at the first failure. The app always refreshes afterward; it reports an
  incomplete reorder rather than claiming success if some writes failed. A timeout
  requires reconciliation/review and is never automatically replayed. A failed
  refresh retains explicitly stale server data, not a successful-looking preview.
- Reordering uses the already-installed reorderable library and the existing Notes
  endpoints; it does not change the separate UUID/project/cost work-Tasks section.

Regression coverage: `NotesReorderingTest` checks subtree preservation, scope/role
guards, position-only bodies, all item types, serialization, duplicate submissions,
partial failures, uncertain outcomes and account changes. `NotesReorderingScreenTest`
adds real folder/note drag gestures, nested move menus and read-only/editor guards.

### Reordering validation results

- Debug build, instrumentation APK build and lint passed using
  `gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain`.
  Lint remains at **0 errors, 38 warnings and 2 hints**; no new warning remains.
- **77 JVM tests passed**, including **11 new reordering tests**. Existing Notes,
  work-Tasks, Fuel and JWT regression suites also passed.
- **9/9 Notes screen tests passed on each API 35 emulator profile:** small phone
  (720×1280 px at 320 dpi, 360×640 dp) and wide tablet-size override (1920×1200 px
  at 240 dpi, 1280×800 dp). Both runs include the five existing Notes cases and
  four reordering cases. The wide-only pane checks actually ran on the tablet.
- The first instrumentation attempt terminated before test results; emulator crash
  logs also contained Bluetooth-service failures. After the final build, idle
  Gradle daemons were stopped and emulator Bluetooth was temporarily disabled;
  both complete runs passed. Display overrides and the previous Bluetooth setting
  were restored. The attached physical device was not used.
- Evidence: `app/build/notes-reordering-final-build.txt`,
  `app/build/notes-reordering-small-phone.txt`,
  `app/build/notes-reordering-tablet.txt`, and the usual JVM/lint reports.
  These are synthetic local fixtures, not staging persistence or physical-device
  accessibility sign-off. Verify server persistence/reopen and concurrent ordering
  against disposable shared staging records before release.

## Folder deletion policy

Only **empty leaf folders** are eligible. Nonempty folders or folders with children display an explanation and send no DELETE. Confirmation is explicit; a fresh tree is checked again immediately before an empty-folder DELETE. There is no recursive client deletion sequence and no folder-to-root move control.

The fresh check is not an atomic backend guarantee: another client could add a note/child between that check and DELETE. Safe nonempty/recursive deletion, ownership and stronger authorization/concurrency require separate backend/product agreement. The app does not promise those guarantees.

## Implementation map

- `data/model/NotesWorkspace.kt`: typed tree/item/style models, numeric IDs, offset-aware timestamps, date-only drafts, validation and partial-write builders.
- `data/repository/NotesRepository.kt`: exact endpoint/status handling, full item decoding, tree read source, JSON `error` display with bounded plain-text rendering and non-JSON fallbacks. A Notes 400/404 is not assumed to be a field error/already-deleted success.
- `data/remote/RetrofitProvider.kt`: existing shared authentication/network integration, HTTPS-origin normalization and no automatic write replay.
- `ui/viewmodels/NotesViewModel.kt`: session-scoped, testable controller with preservation-safe drafts, role gating, mutation serialization, stale/error/uncertain state and confirmation targets.
- `ui/screens/NotesScreen.kt`: adaptive folder-only workspace, typed native editors and previews, accessible text status, discard/delete confirmations and refresh.
- `res/values/notes.xml`: default-English feature strings/plurals, matching the app's current localization convention. Additional language translations are not included.

Notes task lists are unrelated to the work/billing Tasks section: they do not use UUID/project/cost/sentOn updates. Diary items use the Notes API, not `/api/diary`. No Telegram, reminder, notification, background polling or push-registration code is added.

## Initial Notes rollout verification (historical)

Commands executed:

- `gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain`
- `gradlew.bat :app:testDebugUnitTest :app:assembleDebugAndroidTest --console=plain` after extending the regression cases.
- Final verification used the first command with `--no-daemon`, after closing the dedicated emulator; exit code 0, `BUILD SUCCESSFUL`. See `app/build/notes-final-build.log`.

Build and lint passed (zero lint errors; 38 warnings and 2 hints remain). **41 JVM tests passed**, including 24 new Notes tests:

- `NotesContractTest` (10): exact lowercase types, numeric IDs, migration/style defaults, direct tree groups/order, hidden-field omission, explicit false/zero/empty-date, whole-style preservation, root/child defaults, validation, time zones, actual Retrofit paths/statuses, JSON/plain-text failures and malformed confirmed responses.
- `NotesControllerTest` (14): nested selection, independent types, root/child create and refresh/reselection, discard protection, field-preserving type changes/moves, completion-only writes, clear-date writes, Reader gating, duplicate prevention, saved-but-refresh-failed, uncertain/rejected draft retention, deletion confirmation/cancel and nonempty preflight, stale response/session separation, validation and 401 without loops.
- Existing Fuel/JWT regression tests also pass.

UI suite: `NotesScreenTest` contains five cases for folder-only nesting/read-only controls, Buy completion and discard, task date clearing, large-text dark Diary preview, and wide-pane hide/restore/selection. It uses fake in-memory data, not a real account. The tablet-specific branch must be run at a wide size; a phone-only run is not tablet validation.

Completed on the dedicated Android 15/API 35 emulator:

| Configuration | Result |
| --- | --- |
| Small phone, 720×1280 px at 320 dpi (360×640 dp), America/Los_Angeles | **13/13 passed**: Auth (5), Fuel (3), Notes (5). The tablet-only case returns early on a phone. |
| Wide/tablet, 1920×1200 px at 240 dpi (1280×800 dp) | **5/5 Notes passed**, including actual folder-pane hide/restore and folder-switch/editor assertions. |
| Small-phone task date clearing, Asia/Tokyo | **1/1 passed**, again asserting the empty-string payload and preserved description. |
| Large text / dark appearance | Diary preview uses 1.8× text and dark theme in both Notes suite sizes; all assertions passed. |

The initial snapshot-restored emulator run stalled/reported a process crash; logs also showed background ANR/memory-pressure activity. After a cold boot with 3072 MiB and stopping idle Gradle work, all runs above passed. No production-device tests or data mutations were performed in these Notes validation runs. Display size/density and time-zone settings were restored before closing the emulator.

Reports/artifacts:

- `app/build/reports/tests/testDebugUnitTest/index.html`
- `app/build/reports/lint-results-debug.html`
- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
- `app/build/notes-*-validation.txt` for direct emulator runs.
- `docs/notes-screenshots/phone/01-folders-only.png` through `04-diary-preview-large-dark.png`.
- `docs/notes-screenshots/tablet/01-folders-only.png` through `05-tablet-folders-items.png`, including the wide split layout.

Use an explicit emulator serial when another physical device is connected. Instrumentation uses fake data, and Auth regression tests clear app-session preferences; never run it against a real user's installed app/data. If the IDE reports unresolved newly generated string resources despite a successful Gradle build, synchronize its Gradle project model.

## Release validation still required

- Dedicated staging HTTPS access and Reader/Writer/Admin accounts were not provided. Validate actual migrated legacy data, server persistence/reopen, real account switching, create/edit/move/delete responses and role policy using disposable shared data only. No real records have been imported, recreated or deleted in this implementation work.
- Exercise actual server/network failures, expired sessions, uncertain writes, saved-write/failed-refresh and simultaneous same-field edits from web/mobile. Mocked errors do not establish radio/server reliability.
- Complete supported physical-device/API testing (especially API 24/37), TalkBack traversal/announcements, native touch targets, keyboard-open editors, contrast with real themes, translated/long labels and reduced-motion behavior.
- Emulator small-phone, wide/tablet, large-text, dark-mode and both-sides-of-UTC results are recorded above. Repeat against staging and supported physical devices before release; fake-data emulator/JVM results do not prove migrated production data or backend policy.
- Folder-to-root moves, nonempty/recursive folder deletion, offline queued writes, per-user isolation, new backend authorization and notification scheduling remain out of scope.



