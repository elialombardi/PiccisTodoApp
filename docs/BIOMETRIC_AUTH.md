# Biometric-bound challenge authentication

## Configuration

Set the Gradle project property `BASE_URL` to the backend's HTTPS origin, for
example `BASE_URL=https://your-backend.example/` in `gradle.properties`, or pass
`-PBASE_URL=https://your-backend.example/` to Gradle. `app/build.gradle.kts` exposes
it as `BuildConfig.BASE_URL`, which is the fallback when no server has been saved.
The placeholder `https://example.invalid/` deliberately cannot authenticate.
The login screen's **Server** field is editable. Enter an HTTPS origin such as
`https://your-backend.example:8443`, then select either sign-in action to save and
use it. No rebuild is needed for runtime changes. The address is restored on the
next launch; only an active login temporarily disables editing.

All API clients use the selected origin. Changing it clears the memory-only
session, and token interceptors restrict bearer credentials to the selected
scheme/host/port, including when an older client is still cached. A different
backend may require registering the device there with username/password first.
Credentials in URLs, cleartext HTTP, API paths, query strings, and fragments are
rejected. Include `https://`; do not append `/api` or a login endpoint.

The existing username/password route is `POST /api/auth/login` with JSON
`username` and `password`, returning `access_token`. Change only the annotation in
`auth/AuthApi.kt` if your backend uses a different password-login route. No new
password endpoint was invented.

The biometric routes match the supplied backend contract:

| Route | Request | Response |
| --- | --- | --- |
| `/api/v1/auth/register-device` | Bearer registration JWT; `device_id`, `public_key_pem`, `device_name` | Any 2xx, with optional/empty body |
| `/api/v1/auth/challenge` | `device_id` | `challenge`, `expires_at` |
| `/api/v1/auth/verify-challenge` | `device_id`, `signature_b64` | `access_token`, positive integer `expires_in` in seconds |

`expires_at` is an RFC 3339 timestamp (or Unix seconds). The client signs the exact
UTF-8 bytes of the returned **challenge string**, without decoding Base64 or
trimming it. If the server instead expects decoded random bytes, change that
protocol decision in `BiometricLoginUseCase.validateChallenge` on both sides.
`signature_b64` is standard, unwrapped Base64 of the ASN.1 DER ECDSA signature.
In Go, verify SHA-256 of those challenge bytes using `ecdsa.VerifyASN1`, not raw
64-byte R/S verification. The PEM is X.509 SubjectPublicKeyInfo (`PUBLIC KEY`),
parseable with `x509.ParsePKIXPublicKey`.

## Files and flow

The seven requested core classes are in
`app/src/main/java/com/elialombardi/piccistodo/auth/`.
`ui/viewmodels/AuthViewModel.kt` and `ui/screens/LoginScreen.kt` provide the UI.

1. **Register device and sign in** exchanges username/password for a temporary
   registration JWT, uses it once to register the Keystore public key, then drops
   the JWT reference. This JWT is never installed as an application session.
2. The app initializes a SHA256withECDSA signing operation with the private key
   and passes that exact Signature to a strong-only BiometricPrompt CryptoObject.
3. Only biometric success permits a challenge request, one signature, and one
   verification request. A mismatch fails closed; retry is explicit. No password
   fallback occurs. Verification success installs the returned token in RAM.
4. Future **Sign in with fingerprint** attempts need no password or saved JWT.
   Missing/invalidation errors require explicit password-based registration again.

### Important ordering corrections

Android per-operation keys require `Signature.initSign` **before** the prompt.
Calling it after biometric success would create a new, unauthorized operation.
`KeyManager.sign` therefore accepts the Signature returned by the authenticated
CryptoObject and never initializes it again.

Fetching a challenge before prompting conflicts with the requirement that failed
or canceled prompts make **no network calls**. This implementation prompts first,
then fetches and signs the challenge using the already-authorized operation. It
uses one prompt, not two. The Boolean non-crypto authenticator overload exists for
UI gates but is never used to authorize login signing.

All auth network calls and Keystore/preference operations run on `Dispatchers.IO`;
BiometricPrompt construction, authentication, and cancellation run on the main
thread. AndroidX owns its cancellation signal; a platform CancellationSignal and
the cancellable continuation bridge to `cancelAuthentication()`.

## Security and lifecycle

- P-256 private keys stay in Android Keystore. API 30+ explicitly uses
  `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)`; API 24–29 uses the
  equivalent auth-per-operation validity value of -1. Biometric enrollment
  changes invalidate keys. Device credentials and weak biometrics are not allowed.
- `TokenHolder` has a volatile backing token and monotonic expiry deadline, a
  proactive expiry job, and an expiry-checking getter. Logout, expiry, process
  death, or a matching rejected-token response removes the in-memory token.
  A late 401 cannot erase a newer token.
- No JWT is stored in SharedPreferences, DataStore, saved instance state, files,
  or a disk HTTP cache. SettingsManager deletes legacy stored JWT/role keys without
  decrypting them. Old encrypted backups cannot be remotely erased, but restored
  legacy tokens are never used. Backup rules exclude the legacy preference file.
- EncryptedSharedPreferences stores only the device identity: an install UUID and
  a SHA-256 identifier derived from it and ANDROID_ID. This is a public identifier,
  not a bearer credential. Its file is excluded from backup/device transfer so a
  reinstall creates a fresh identity. AndroidX security-crypto is deprecated but
  is used here specifically to satisfy the requested encrypted device-ID storage.
- Kotlin/JVM Strings cannot be reliably zeroized. Registration JWT references are
  scoped to registration, explicitly dropped by the caller, and left for GC;
  this is not a guarantee against a rooted process reading live RAM.
- Stopping/rotating the login screen cancels its job and prompt. No pending
  challenge or password is restored. Retry always creates a new signing operation.
- Debug auth logs contain only numeric HTTP/availability status codes. Models do
  not generate credential-bearing `toString()` methods, and UI errors never
  display server bodies or exception details. No crash/analytics integration is
  installed for auth; do not later enable body/header logging or memory capture.
- Logout does not revoke the server device registration or delete its key; that
  enables later biometric login. The account preference can disable that login.
  Server-side device revocation needs a separate backend endpoint/policy.

The backend **must** generate unpredictable, device-bound, single-use challenges,
expire them after 60 seconds, and consume them atomically during verification.
It must also authenticate registration, enforce account/device ownership, rate
limit all auth routes, and reject replay and invalid signatures. Client checks
cannot enforce these backend properties.

## Errors and dependencies

Typed errors include missing hardware/enrollment, biometric unavailability,
lockout, cancellation/mismatch, key invalidation, device not found, challenge
expiry, invalid signature, rate limiting, credential rejection, and network errors.
For ambiguous HTTP 400 responses the server should return a machine-readable
`code` (top-level or under `error`): `device_not_found`, `challenge_expired`,
`signature_invalid`, or `rate_limited`. Recognized HTTP statuses are also mapped.

Existing: androidx.biometric 1.2.0-alpha05, Retrofit 3.0.0, OkHttp 5.5.1, Hilt,
Compose, and lifecycle. Added in `app/build.gradle.kts`:

- `androidx.security:security-crypto:1.1.0`
- `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2`
- `com.squareup.retrofit2:converter-moshi:3.0.0`
- `com.squareup.moshi:moshi-kotlin:1.15.2`

## Validation

`AuthRepositoryTest` covers typed errors, registration bearer use, IO dispatch,
response validation, cancellation, JSON names, and credential-safe string output.
`ServerUrlTest` covers HTTPS origin validation and normalization.
`ServerUrlScreenTest` verifies editing and the active-login field lock.
`AuthSessionTest` covers memory-only storage, expiry, logout, stale 401s, stable
device identity, server persistence, and origin-scoped tokens.
Build with `:app:assembleDebug :app:assembleDebugAndroidTest
:app:testDebugUnitTest`. Run device tests with `:app:connectedDebugAndroidTest`.

On a strong-biometric device and configured backend, also test: initial enrollment,
successful signing, cancellation/mismatch with zero auth HTTP requests, lockout,
new biometric enrollment/key invalidation, expired/replayed challenges, server
rate limiting, logout/relogin, and process kill/rotation during login. These need
real biometric hardware/emulation and backend behavior; JVM tests do not prove them.

