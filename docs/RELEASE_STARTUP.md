# Installed release APK startup failure

Investigated: 13 November 2026.

## Confirmed cause

The connected Xiaomi phone (model reported by ADB: M2101K7BNY, Android 13)
recorded this startup failure for the installed production package:

`java.lang.IllegalAccessError: Class kotlin.text.j extended by class sz2 is inaccessible`

The exported release mapping identifies `kotlin.text.j` as
`kotlin.text.StringsKt__IndentKt` and `sz2` as
`kotlin.text.StringsKt__StringBuilderKt`. Release optimization repackaged the
subclass outside the inaccessible parent's package. The failure occurs during
Kotlin reflection initialization on activity startup, before login.

This explains why the non-optimized Android Studio debug build starts while
the previously exported release APK crashes. This exception is not evidence
of a missing permission, an authentication role failure, or a phone hardware fault.

## Fix

`app/build.gradle.kts` explicitly disables release code optimization. Do not
restore the previous `androidx.**` / `kotlin.**` / `kotlinx.**` scoped optimization
until an optimizer update or narrower workaround passes installed-release tests.
The resulting release APK is larger; the build remains non-debuggable.
No signing credentials, authentication policy, or stored user data were changed.

## Build and installation

- Rebuild using Android Studio's **Generate Signed App Bundle / APK** action,
  selecting **APK**, **release**, and the existing release key.
- Install the newly generated signed APK as an update. Do not uninstall the
  existing production app to work around signing errors: uninstalling removes
  app data and device enrollment.
- The previously exported `app/release/app-release.apk` is not automatically
  replaced by a command-line unsigned release build. Do not reuse the old export.
- Command-line `assembleRelease` without a configured signing key produces an
  unsigned APK, not a distributable signed update. Keep key passwords out of
  source control, terminal logs, and chat.

## Local verification records

Release and non-debuggable smoke APK builds passed. All 87 JVM tests passed
with zero failures/errors. Release lint reported zero errors, 50 warnings,
and two hints. The unsigned fixed release is
`app/build/outputs/apk/release/app-release-unsigned.apk` (17,454,041 bytes).

Device startup validation remains **blocked**: MIUI rejected the separate test
APK with `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`. No cold
launches ran and no UI hierarchy was captured. USB installation must be allowed
by the device owner before retrying. The existing production package remained
installed and unchanged.

Build/JVM test/release lint output: `app/build/release-startup-verification.log`.
Original crash: `app/build/release-startup-original-crash.txt`.
Phone smoke-test output: `app/build/release-startup-phone-validation.txt`.

The local validation init script `app/build/notes-release-smoke.init.gradle`
creates a non-debuggable variant inheriting the release configuration with a
separate `.releasesmoke` application ID and the debug signing key. It is for
startup testing only, not distribution. The test removes only that separate
package afterward; it does not replace the installed production app.

Startup smoke testing does not validate live backend operations, biometric
sign-in, the final production signature, or every supported Android version.
The connected model above is not a Redmi Note 8; a Note 8-specific result must
be recorded separately if that is a different target device.


