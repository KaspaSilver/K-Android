# Play Store submission — K (5.1, updated for 5.4)

Everything the Play Console asks for that is *not* answerable from the listing text, plus the
build-level checks that have to pass before an upload. Written for the operator doing the
submission; the companion doc for FCM is `PUSH_ANDROID_SETUP.md`.

> **Open item — Android App Links (release audit 2026-11-27 M2).**
> `https://kachat.app/.well-known/assetlinks.json` still answers with an empty
> `sha256_cert_fingerprints`, so the `autoVerify` intent filter on `kachat.app` (`/u/`, `/pay/`,
> `/post/`, `/broadcast/`, `/hub/`) does not verify and Android 12+ opens those links in the
> browser. The file needs **two** fingerprints, because the GitHub APK and the Play build are
> signed with different certificates. Publish this, served as `application/json` over https with
> no redirect, with the Play value copied from Play Console > Setup > App integrity > App signing
> key certificate > SHA-256:
>
> ```json
> [
>   {
>     "relation": ["delegate_permission/common.handle_all_urls"],
>     "target": {
>       "namespace": "android_app",
>       "package_name": "com.kachat.app",
>       "sha256_cert_fingerprints": [
>         "AB:DC:99:E2:83:06:75:A4:AC:D4:1A:6D:07:79:78:D8:B8:B0:FD:C0:4D:0B:22:50:34:3A:01:6D:45:D1:9F:4D",
>         "PLAY_APP_SIGNING_SHA256_FROM_PLAY_CONSOLE"
>       ]
>     }
>   }
> ]
> ```
>
> The first entry is `kachat-release.keystore` (the GitHub APK). Replace the placeholder before
> publishing; an invalid entry makes verification fail for both. Debug builds
> (`com.kachat.app.debug`) are deliberately not listed. Re-check with
> `curl -s https://kachat.app/.well-known/assetlinks.json` and, on a phone,
> `adb shell pm verify-app-links --re-verify com.kachat.app` then
> `adb shell pm get-app-links com.kachat.app` (each domain should read `verified`).

## 1. The artifact

```bash
./gradlew bundlePlayRelease
```

`app/build/outputs/bundle/playRelease/app-play-release.aab`

- Build it again from the exact commit being uploaded and check the file's date: an AAB left in
  `build/outputs` from an earlier build is easy to upload by mistake (release audit 2026-11-27 L5).

- Signed with the **upload** key (`uploadStoreFile` in `keystore.properties`, Play expects
  SHA-1 `CF:58:68:0B:…`), not the key the GitHub APK uses. The swap is per-variant, in
  `androidComponents.onVariants` — see `app/build.gradle.kts`.
- `versionCode` must exceed Play's high-water mark for `com.kachat.app`, which is permanent and
  never resets on a `versionName` change. 5.0 shipped as 69; the 5.1 betas have run past 92.
- The play flavour sets `KACHAT_IS_RELEASE = true`, so About and the crash/diagnostics reports
  read plain **"5.1"**. The github flavour keeps `false` and reads "5.1 (N)". Nothing to flip by
  hand at submission time — which is the point, since a forgotten flip ships a store build
  labelled like a beta.

  The trade: the same AAB goes to internal testing and to production, so a **Play beta** now also
  reads plain "5.1" instead of "5.1 (N)". Build identity on that track comes from the versionCode
  instead, which every crash and diagnostics report already prints next to the version. If build
  numbers on Play betas matter more, set the play flavour's `KACHAT_IS_RELEASE` to `"false"` and
  flip it by hand for the production upload, the way iOS's single flag works.
- All four ABIs stay in the bundle (only the github APK is arm64-only) — Play serves each device
  its own, so 32-bit and x86 devices can still install.

## 2. Pre-upload checks

| Check | Command | Requirement |
| --- | --- | --- |
| Unit tests | `./gradlew testGithubReleaseUnitTest` | all green |
| Release lint | `./gradlew lintPlayRelease` | no new Error-severity issues |
| 16 KB page size | see below | every 64-bit `.so` has `p_align >= 16384` |
| R8 mapping saved | `app/build/outputs/mapping/playRelease/mapping.txt` | upload with the bundle |

Last verified on versionCode 92: 358 unit tests green, lint clean against the baseline, bundle
signed with the upload key (SHA-1 `CF:58:68:0B:11:F0:C5:E9:BF:40:5B:07:85:5D:86:EC:3B:A0:31:02`),
all four ABIs present, 39 MB.

Google requires 16 KB page-size support for anything targeting Android 15+. Both halves have to
hold — the ELF segment alignment of each prebuilt library, and the zip alignment inside the
bundle. It applies to the **64-bit** ABIs only, which is where it currently stands: `arm64-v8a`
and `x86_64` are fully 16 KB-aligned, while WebRTC's and ML Kit's `armeabi-v7a`/`x86` libraries
are still 4 KB-aligned. That is fine — no 16 KB device is 32-bit — but it means the check has to
be read per ABI, not as one pass/fail. To re-verify after a dependency bump (WebRTC is the one
that matters, at ~12 MB per architecture):

```bash
rm -rf /tmp/soaudit && unzip -o -q \
  app/build/outputs/bundle/playRelease/app-play-release.aab 'base/lib/*' -d /tmp/soaudit
python3 -c "
import glob, struct
for p in sorted(glob.glob('/tmp/soaudit/base/lib/*/*.so')):
    d = open(p, 'rb').read()
    if d[4] != 2:            # 32-bit: exempt, 16 KB devices are all 64-bit
        continue
    off = struct.unpack_from('<Q', d, 0x20)[0]
    size, num = struct.unpack_from('<H', d, 0x36)[0], struct.unpack_from('<H', d, 0x38)[0]
    a = [struct.unpack_from('<Q', d, off + i * size + 0x30)[0]
         for i in range(num) if struct.unpack_from('<I', d, off + i * size)[0] == 1]
    print('OK  ' if all(x >= 16384 for x in a) else 'FAIL', p, sorted(set(a)))
"
```

## 3. Console declarations

These are the ones that block a release if they are missing or wrong.

**App access** — no login is required to use the app; a wallet is created on device. No reviewer
credentials needed.

**No foreground services, exact alarms or `USE_FULL_SCREEN_INTENT`** (5.4). Calls left the app,
and with them `CallForegroundService` and its `FOREGROUND_SERVICE_*` permissions; there is no
foreground-service declaration to make. Do not re-add any of them without a policy review.

**Financial features.** Declare **crypto exchanges and digital wallets** — the app holds Kaspa
keys on device, includes a ChangeNOW swap, the Trestly token trading pages (kUSDT, pool deposits and
withdrawals) and a buy-with-card flow run by a third-party provider in a WebView. Have the region/licensing answers ready; this is
the declaration most likely to hold up a first submission.

**Target audience: 18+.** Not negotiable alongside the crypto declaration — Play's Families
policy excludes crypto apps, so the app must not claim a child audience. Child Mode is a
*parental control inside an adult app* (it hides Swaps, KaPosts and Public Chats behind a
password); describe it that way if asked, never as a kids mode.

**Privacy policy URL.** `https://home.kachat.app/privacy/`, linked in the app. It has to cover the
third-party endpoints in §4.

**Data deletion.** The app stores keys and history on device only; uninstalling removes them.
Account deletion does not apply (there is no account), but the Console still wants that stated.

## 4. Data Safety

Checked against 5.4 (release audit 2026-11-27 L5). No analytics or crash-reporting SDK is linked.
Crash records are written and read locally (`CrashRecorder`); nothing is uploaded. K does not read
or write system contacts (that permission is gone). Language identification for Posts runs on
device (ML Kit language-id, bundled).

| Data type | Collected | Shared | Purpose / endpoint |
| --- | --- | --- | --- |
| Financial info — other (Kaspa address) | Yes | Yes | Push routing (KaChat indexer), K name lookups, explorer and price pages, the swap destination sent to ChangeNOW, and the address a site sees when the user connects K to it (Kasshi, Trestly). App functionality; required. |
| Financial info — purchase history | Yes (ephemeral) | Yes | Buy with card: the amount and destination address go to the card provider's page, which handles payment itself. K never sees card details. |
| Messages — other in-app messages | Yes (ephemeral) | No | Public post text goes to KaChat's translation service **only** when the reader taps Translate. The request carries no identifier. |
| App activity — other | Yes (ephemeral) | No | Which addresses/rooms/groups to watch, sent to the indexer for push. Addresses are hashed in the auth preimage. |
| Device ID | Yes | No | FCM registration token, sent to the KaChat indexer's `/v1/push`, signed with the wallet key. |
| Photos | No | No | Attached from the device and sent in-chat or in a post; never uploaded to a KaChat service except the user's own Nextcloud. |
| Audio | No | No | Voice messages, recorded on device. |
| Contacts | No | No | No contacts permission. |
| Location | No | No | No location permission. |

Third-party hosts contacted and what each sees:
- prices: `api.coingecko.com`, `api.gateio.ws`, `query1.finance.yahoo.com` (no user data);
- the Kaspa REST and explorer APIs: `api.kaspa.org`, `api.kaspa.stream` (and their testnet hosts),
  `explorer.kaspa.org`, `www.kaspalytics.com` (the address or transaction looked up);
- the KaChat and Kasia indexers (addresses, push token);
- `api.changenow.io` (swap amount and destination address);
- Trestly, `api.trestly.io` / `testnet.trestly.io` (the connected address and the trades the
  user approves), and `kasshi.io` (the connected address after "Sign In to Kasshi");
- the card-buy provider's page (amount, destination address, whatever the user types there);
- the chess and arcade servers (game moves and the player's address);
- profile pictures from linked social accounts: `api.fxtwitter.com`, `unavatar.io`,
  `pbs.twimg.com`, `cdn.discordapp.com` (the handle being looked up);
- the user's own Nextcloud server (backups, Automatic Sync);
- on link previews, whatever host a received link points at, which is how that host learns the
  reader's IP. Inherent to link previews, and matches iOS.

## 5. Permissions

As in the 5.4 manifest:

| Permission | Why |
| --- | --- |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Kaspa node gRPC, indexer, REST |
| `POST_NOTIFICATIONS` | Message and activity notifications (asked once) |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Lets data-only encrypted push wake a closed app. Asked only on phones where push can't work otherwise, after an in-app rationale sheet; see §6. |
| `RECORD_AUDIO` | Voice messages |
| `CAMERA` | Photos and QR scanning |
| `NFC` | Reading a Kaspa card (Cold Storage) |
| `WRITE_EXTERNAL_STORAGE` (maxSdk 28) | Saving a received photo on API ≤28 only |
| `VIBRATE`, `USE_BIOMETRIC` | Notification feedback; the seed-phrase / account-unlock prompt |

Gone since 5.1, so don't declare them: `READ_CONTACTS`, `WRITE_CONTACTS`,
`RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE` and its `_MICROPHONE` / `_CAMERA` types,
`MODIFY_AUDIO_SETTINGS`.

The manifest also declares `android.hardware.camera`, `camera.any`, `camera.autofocus`,
`android.hardware.microphone`, `android.hardware.nfc` and `screen.portrait` as `required="false"`.
Play otherwise infers them as *required* from the permissions and hides the app from every
device without that hardware — most Chromebooks, camera-less tablets, TV and auto form factors —
even though K's camera, microphone and NFC are optional. Do not drop these.

`usesCleartextTraffic="true"` is deliberate and documented in the manifest: Kaspa node gRPC
(protowire, ports 16110/16210) is plaintext by protocol and a `networkSecurityConfig` can only
scope a cleartext allowance by domain, which is useless for a pool of bare peer IPs. Every HTTP
surface in the app is https-only at the application layer, and the one user-editable server
field (Nextcloud) rejects `http://` in the UI and again in `NextcloudService.normalizeServer`.
Expect to explain this if a reviewer asks.

**Moving between the GitHub APK and the Play build.** They share the package name but not the
signing key (Play re-signs with Play App Signing's key), so one can't update the other. Someone
switching must uninstall first, which deletes K's local data. Say so in the release notes and
the GitHub release page, and tell people to back up (or turn on Nextcloud Automatic Sync) first.

## 6. Risk notes

- **Battery-optimization dialog.** `MainActivity` asks for the exemption only on phones where
  push can't wake K otherwise, and only after K's own rationale sheet explains why. It is asked
  once. Keep it that way: an unexplained system dialog is what reviewers flag.
- **One iOS string nuance is not matched.** On a shared public-chat link, iOS says "Tap to *open*
  this KaChat public chat room." for a room you already have and "Tap to *join*…" for one you do
  not; Android always says "join". Closing it means threading the joined/curated channel list into
  `KaChatInternalLinkCard`, which is deliberately presentational with no ViewModel — a refactor not
  worth its regression risk during launch prep. Everything else in that card matches.
- **The translations need a pass, on two counts.** English now reads "Public Chats" everywhere,
  matching iOS, but the 18 localized `strings.xml` files still carry a translation of the old
  "Broadcasts" name. Separately, lint finds 97 strings missing their diacritics — 55 in
  `values-pt`, 28 in `values-es`, 14 in `values-it` ("Nao" for "Não", "camara" for "cámara",
  "dias" for "días", and so on). Neither blocks a launch, and English is unaffected, but a
  Portuguese or Spanish speaker sees an app that reads as machine-translated. Worth fixing before
  promoting the listing in those locales.
- **No app-specific baseline profile.** The bundle carries the AndroidX libraries' profiles only,
  so first-run and first-scroll of KaChat's own Compose code are interpreted. A
  `baselineProfile` module would measurably improve cold start; nothing blocks the launch.
- **Startup does two blocking reads before the first frame.** `KaChatApplication.onCreate` calls
  `CrashRecorder.noteProcessExits` (system exit history, and a file write when the last exit was
  abnormal) and `kaPostsScheduledStore.reloadIfNeeded()` (SharedPreferences + JSON) synchronously.
  Both are small and neither is a network call, but they are the only work between process start
  and Compose; worth a Macrobenchmark measurement rather than a guess if cold start ever looks slow.
- **30 `AutoboxingStateCreation` warnings** — `mutableStateOf(0)` where `mutableIntStateOf(0)` would
  avoid boxing on every write. Pure allocation churn in list-heavy screens; not a launch blocker.
- **Dependency updates are available** (24 `GradleDependency` + 19 `NewerVersionAvailable`
  warnings). Deliberately not taken here: shipping the store build on the exact versions the 5.1
  betas were tested against is worth more than the bumps. Revisit right after launch.
- **Room migrations.** Destructive fallback is only for the 1.0.x databases (versions 1-14). Every
  step from 15 to the current version is registered (`KaChatDatabase.ALL_MIGRATIONS`), plus the
  direct 42→45 and 43→45 jumps that keep Portfolio and Public Chats for phones that skipped 5.3.
  `MigrationPathTest` checks the path Room takes; every new release must keep it green.

## 7. Security review, 2026-09-25

Checked and clean:

- **Secrets.** Nothing sensitive is tracked: `keystore.properties`, `*.jks/*.keystore`,
  `local.properties`, `google-services.json` and the whole `gift-server/` tree are gitignored, and
  a scan of `app/src/main` finds no hardcoded key, token or password literal. The one ChangeNOW key
  is read from `local.properties` at build time.
- **Key storage.** The seed and per-account material live in `EncryptedSharedPreferences`
  (`AES256_SIV` keys / `AES256_GCM` values) under a Keystore-backed `MasterKey`, with no plaintext
  fallback path. `android:allowBackup="false"`, so nothing goes to Android Auto Backup.
- **Screen capture.** `FLAG_SECURE` is set on the seed-phrase and private-key screens (onboarding
  and settings), with tap-to-reveal and a 7-second auto-hide.
- **Clipboard.** `ClipboardUtils` marks copied secrets with `ClipDescription.EXTRA_IS_SENSITIVE`
  so the OS does not preview them, and the private-key copy auto-wipes.
- **Logging.** No `Log.*` call carries a seed, mnemonic, private key or passphrase, and no
  `printStackTrace()` anywhere. R8 strips `Log.v`/`Log.d` from release builds outright
  (`-assumenosideeffects`), leaving only the info/warn/error lines that were written not to carry
  secrets.
- **Transport.** Every HTTP surface is https at the application layer; the two raw
  `HttpURLConnection` callers added for the portfolio charts (Gate.io, Yahoo) are https, on
  `Dispatchers.IO`, with connect/read timeouts, and fail closed. See §5 for why
  `usesCleartextTraffic` stays true.
- **Exported components.** Three are exported, each deliberately: `MainActivity` (launcher and
  deep links), `CallFromContactsActivity` (accepts only KaChat's own contact MIME types), and the
  two contacts account/sync services the platform requires to be exported to own contact rows.
  Nothing else is reachable from another app.
- **No third-party telemetry.** No analytics or crash SDK is linked; crash records stay on device.

Accepted, not fixed:

- **Link previews reveal the reader's IP.** A received link is fetched automatically when the
  bubble renders, so the host it points at learns the reader's IP and that they saw the message.
  The scheme is restricted to http/https but private/loopback addresses are not blocked. There is
  no channel back to the sender for anything but a public host they control, and iOS behaves the
  same way, so this matches rather than diverges. Inherent to link previews as a feature.
