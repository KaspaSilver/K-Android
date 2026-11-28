# K Android release audit, 2026-11-27

**Code:** main d1ccb92b (K 5.4 build 7, versionCode 149).

## Fix status (2026-11-28)

**Fixed:** every finding below except the ones listed here. Not committed yet.
- **Checks:** 1,730 unit tests pass; release lint is clean.
- **Emulator re-test:**
  - E1: Posts opens at the newest post.
  - E2: Explorer tile titles read in full.
  - E4: Arcade's header lines up.
  - E5: Kasshi's X goes back to Arcade.
  - Back works in chats and Settings.
  - The minified release build runs onboarding, Posts and Portfolio with no crash.

**Not fixed, or only partly:**
- **M2 (assetlinks.json):** a server file. Publish this at `https://kachat.app/.well-known/assetlinks.json`, with Play Console > App integrity > App signing key SHA-256 in place of the placeholder (see PLAY_STORE_SUBMISSION.md):
  `[{"relation":["delegate_permission/common.handle_all_urls"],"target":{"namespace":"android_app","package_name":"com.kachat.app","sha256_cert_fingerprints":["AB:DC:99:E2:83:06:75:A4:AC:D4:1A:6D:07:79:78:D8:B8:B0:FD:C0:4D:0B:22:50:34:3A:01:6D:45:D1:9F:4D","PLAY_APP_SIGNING_SHA256_FROM_PLAY_CONSOLE"]}}]`
- **E6:** not a bug. The 40 requests on a new install are the usernames registry building its name map once, as on iPhone.
- **Messaging C3 (partly):**
  - A delivered copy now replaces a Failed row only within 10 minutes, and never a definite failure.
  - Same text sent twice in 10 minutes, the second killed mid-send, can still merge. Closing that needs a DB column.
- **Messaging A5 (partly):** the Review prices the real transaction, but doesn't show the separate payment-notice fee.
- **Messaging B1 (partly):** a kUSDT tip waits for the author check (up to 6 s) with nothing on screen.
- **Battery R3 (partly):** chat history preload is capped at 2,000 rows or 8M characters. Loaded bodies still sit in memory.
- **Battery R4:** the in-app browser pauses fully. Trestly pages stop drawing in the background, but their JavaScript timers keep running.
- **Battery R7 (partly):** a battery exemption you already granted can't be withdrawn by the app.
- **Smoothness S7 (partly):** .kachat Recent Activity draws 16 rows a frame rather than lazily.
- **Release L3:** "KaPost" and "KaChat" wording stays, because iPhone still uses it.
- **Release L7:** tests that need Robolectric aren't written.
- **Translations to check:** 19 Posts strings and most `rel5_*` error strings were translated by the agent, because iPhone has no translation either.

**Changes beyond iPhone (iPhone needs the same fix):**
- Security S2: block, unblock and profile signing refusal.
- E5: Kasshi's X returns to the Hub section.
- E3: a newly joined room loads its newest message.
- Battery R8: indexer timeouts get network-change grace and a slow-link rule.
- Groups D2–D6, plus likely D1.
- Messaging C3 and A7.
- Smoothness R3: history preload cap.
- L2: some untranslated literals.

**New on Android 13–15:** predictive back (`enableOnBackInvokedCallback`). Sheets behave as before: Back does nothing on a sheet's first step and closes it from later steps, same as build 7.

**Original status (2026-11-27):** nothing fixed yet.

**Method:**
1. **Code review.** Five read-only reviewers each took one area:
   - security and privacy;
   - battery, network and memory;
   - smoothness;
   - money and messaging;
   - crashes, data and release readiness.
2. **Checked by hand.** Every High finding was re-checked in the code.
3. **Emulator run** (Pixel 8, Android 15):
   - the debug build installed over the old 5.2.3 data, then a tap-through of every area on the mainnet account;
   - the signed release APK installed fresh, onboarding with a throwaway wallet, then Posts, Explorer and the log. The wallet was uninstalled afterwards.
   - Nothing was sent on chain.

The reviewers' full write-ups are the appendix, each with its own IDs.

## Verdict

**No Critical finding.** Nothing loses funds or keys, and no remote attack works.

| Area | Critical | High | Medium | Low |
|---|---|---|---|---|
| Security | 0 | 0 | 2 | 2 |
| Battery, network, memory | 0 | 0 | 3 | 6 |
| Smoothness | 0 | 0 | 3 | 7 |
| Money and messaging | 0 | 2 | 4 | 15 |
| Crashes, data, release | 0 | 1 | 3 | 7 |
| Seen on the emulator | 0 | 1 | 1 | 3 |

The earlier battery audit (A1–A6 and its Medium list) is fixed, and the fixes hold. In the background on the emulator, K used 0 CPU over 60 s and the system cached the process; Wi-Fi woke 2 times in 2 minutes.

## Fix before release

| ID | What | Who sees it | Where |
|---|---|---|---|
| **H1** | Adding or removing Trestly pool liquidity **crashes K** as soon as the Approve sheet opens, in every language. `tr()` always runs `String.format`, and `kp_trestly_pool_note_kc` has a bare "99%". Nothing is signed by then. | Everyone who uses pools | `util/AppText.kt:51`, `TrestlyApprovalSheet.kt:116` |
| **C1** | A 1:1 chat or group left open when K goes to the background **silences that contact**. No banner appears, and their messages are filed as read. The open-chat hold isn't released on background. | Very common: close the app from inside a chat | `NotificationHelper.kt:369`, `enterContact`/`setActiveGroup` |
| **C2** | When a push starts K, the messages it brings are **filed as read**, so there's no unread badge. `liveBaselineMs` is still `Long.MAX_VALUE` because only the poll paths load it. | Everyone with push, after K was killed | `ChatRepository.kt:1713`, `:2003`, `:3076` |
| **E1** | **Posts opens partway down the feed.** The first open in a session lands a few posts below the newest, with Back to Top showing and no new-posts pill. Seen twice: on the upgraded install, and on a brand-new install. | Every user, every first open | `KaPostsScreens.kt` feed list, `KaPostsViewModel` first load |
| **M1** | **Updating from 5.2.1 or early 5.2.2 to 5.4 deletes Portfolios and Public Chats rooms.** On database v42, migrations 42→43→44 drop those tables and 44→45 recreates them empty. Later 5.2.2 builds (v44) dropped them on purpose, so only users who skipped those lose data. | Users still on versionCode ≤ 120 | `KaChatDatabase.kt:551-600` |
| **M2** | **kachat.app links don't open K on Android 12+.** `assetlinks.json` has no certificate fingerprints. This is a server file, so you'll need to add the Play and GitHub signing SHA-256s. | Everyone tapping a shared K link | `kachat.app/.well-known/assetlinks.json` |
| **S2 (sec)** | **The site-signing Posts-shape refusal misses** `block:` / `unblock:` and the profile (`broadcast`) signing string. A hostile Kasshi page could get you to sign a block or a profile change. iPhone has the same gap. | Narrow: needs a compromised kasshi.io and your approval | `KaChatSiteWallet.kt:685-713` |

## Should fix

**Security**
- **S1:** the in-app browser (Kasshi, Websites, faucet) keeps cookies and site storage across account switches, Log Out and Delete Account. Kasshi can link two accounts. iPhone uses a non-persistent store; Trestly on Android already has its own profile.

**Messaging**
- **A1:** iPhone's C5 fix doesn't work on Android. A "Not confirmed" chat payment from a spending address never sends its notice, because the owed notice is only kept in memory.
- **C3:** a Failed message is deleted silently when the same text was delivered in the last 48 hours.
- **D1/D2:** account-switch races in groups:
  - a catch-up that spans a switch loses the old account's invites and keys;
  - Add or Remove Member signs with the other account.

**Translation**
- **M3:** 76 Posts and alert strings are English in all 18 other languages, including Delete Chat's warning. iPhone has them translated.

**Smoothness**
- **Main thread (S1–S3):** a long 1:1 chat's history merge, QR encoding (the 0-balance funding card in every chat), and an uncached image check on every bubble all run there. Long chats with media will stutter.

**Battery (no push)**
- **R1/R2:** without push (no Play services, or registration failed):
  - the gRPC keepalive pings every 60 s all night;
  - a short node pool runs a full discovery wave every 2 minutes with the screen off.
- **R3:** opening a photo-heavy chat loads its whole history into memory.

**Seen on the emulator**
- **E2:** Explorer's tile titles are cut off at the default text size on a Pixel 8: "Networ…", "KAS Pri…", "Block R…", "Market …".

## Low (selected; the full lists are in the appendix)

**Seen on the emulator**
- **E3:** a public room you just joined says "No messages yet" until you open it.
- **E4:** Arcade's header sits higher than every other Hub section.
- **E5:** Kasshi's X goes to Chats, not back to the Hub section you opened it from (Arcade).
- **E6:** creating a new wallet fires 40 address-history requests, about 2.2 s each. A newly created account has nothing to discover.

**Security**
- **S3:** no tapjacking guard on Swipe up to Send, the site Sign sheet, or key reveal.
- **S4:** the in-app browser follows `http://` on the same host.

**Release**
- **L1:** Portfolio and Send read each other's saved price as 0 in release builds; R8 renames the Gson fields.
- **L3:** "KaPost" and "KaChat" still show in some texts and notifications.
- **L5:** release-doc drift:
  - the submission doc's Data Safety and permissions describe 5.1;
  - the only AAB on disk predates build 7;
  - the lint baseline has 94 entries for deleted files.

**Battery**
- **R4–R9:**
  - WebViews are never paused in the background;
  - a hidden Cold Storage section keeps Address Activity polling;
  - the battery-exemption prompt comes after a single failed push registration.

**Platform**
- The manifest doesn't set `android:enableOnBackInvokedCallback`, so there's no predictive back. Apps targeting SDK 36 get it on Android 16, so check Back on an Android 16 device.

## What worked on the emulator

Build 7 over 5.2.3 data:
- **Upgrade:** installed and launched with no crash; no migration error in the log.

Chats:
- **Chats:** the list, a 1:1 chat with payment bubbles, and one-tap Back while typing.
- **Public Chats:** the switch, Add Room, Join #kaspa, opening at the newest message, and leaving with swipe Delete.

Posts and Profile:
- **Posts:** Feed and Popular, threads with self-replies, and Back to Top.
- **Profile** and the **bell**.

Wallet:
- **Wallet:** the card, Send (name lookup, Choose an Address, keypad), and Review with fee and UTXO; cancelled, nothing sent.

Hub sections:
- **Explorer:** live home, search, and an address page.
- **Others:** Cold Storage, Portfolio and Arcade.
- **Kasshi:** the sign-in sheet (cancelled), the page, and close.

Settings:
- **Settings:** the order, and Connection.

Release APK:
- **Install and onboarding:** fresh install, terms, onboarding without a PIN, the Welcome Guide.
- **Screens:** Chats, Posts and Explorer.
- **Log:** no crash and no R8 errors.

Not tried:
- any on-chain action;
- push notifications;
- an account switch;
- Delete Account;
- the Trestly pool;
- card buy;
- swaps;
- names;
- a real phone or Android 16.

---

# Appendix: reviewers' findings


## K Android release audit, 2026-11-27: security and privacy

Code: main d1ccb92b. Read-only review. Earlier audits (the iPhone release and smoothness audits, and Android's 2026-11-25 battery audit) were read first; what they fixed is not repeated here. iPhone R3 (Posts-shape refusal), R6 (timestamp range) and C6 (permit cap) were checked on Android: they are ported and work, but the R3 refusal list has gaps (S2).

No Critical or High finding. Keys, seed storage, backups, deep links, PendingIntents, push and the release build are sound. Two Medium findings: the in-app browser keeps web data across accounts, and the Posts-shape refusal misses two indexed action shapes.

### S1 The in-app browser (Kasshi, Kaspa Websites, faucet) keeps cookies and site storage forever, across account switches, Log Out and Delete Account
- **Severity:** Medium
- **Where:** `ui/screens/HelpScreens.kt:186-201` (the WebView is created on the default profile, `domStorageEnabled = true`), `ui/screens/HelpScreens.kt:225-235` (`onRelease` only stops and destroys the view). It is used by `ui/KaChatApp.kt:1220` (Kasshi in Kaspa Hub), `KaspaWebsitesScreen.kt:131`, `HelpScreens.kt:553` (faucet) and `KachatNamesLiveScreens.kt:1243`. Nothing anywhere calls `CookieManager.removeAllCookies` or `WebStorage.deleteAllData` for the default profile; the only clean-up is Trestly's own profile (`TrestlyBrowser.kt:478-511`). `WalletViewModel.logout()` (1434) and `wipeDeletedAccountData` (1643) and `CacheManager.clearAll` do not touch WebView data.
- **Trigger:**
  1. Account A opens Kasshi from Kaspa Hub and signs in. Kasshi keeps its session in a cookie or localStorage.
  2. The user switches to account B, or logs out, or deletes A.
  3. Kasshi is opened again.
- **What happens:**
  - The page loads with A's Kasshi session. A's Kasshi account (its address, history and balance on the site) shows while B is signed in to K.
  - Kasshi's server sees one browser holding A's session and then B's sign-in, which links the two K accounts. Keeping accounts unlinked is something K takes care over everywhere else (payment notices, shortcuts, share targets).
  - After Delete Account, the deleted account's site sessions and storage are still on the phone.
  - The same applies to every site opened in the in-app browser.
  - iOS avoids all of this with `.nonPersistent()` (K-iPhone `ContactsView.swift:2494-2511`, its 2026-11-21 audit #2). The Android port missed it.
  - The persistence is confirmed in the code. That Kasshi keeps a session this way is assumed, since that is how its sign-in works; it was not tested on a device.
- **Fix:** do what `makeTrestlyWebView` / `releaseTrestlyWebView` already do. Give the in-app browser its own `WebViewCompat.setProfile` profile (MULTI_PROFILE), and delete it on `onRelease`. Without profile support, call `CookieManager.getInstance().removeAllCookies(null)`, `WebStorage.getInstance().deleteAllData()` and `clearCache(true)` on release. At the least, clear them in `logout()` and `deleteWallet()`. Kasshi's "Sign In to Kasshi" sheet already runs on every open, so the sign-in flow is unchanged (iOS behaves this way today).
- **Confidence:** Confirmed (storage persists; Kasshi's exact session storage is Plausible)

### S2 The site-signing "Posts-shape" refusal misses KaPost's `block`/`unblock` and profile (`broadcast`) actions
- **Severity:** Medium
- **Where:** `services/KaChatSiteWallet.kt:685-713` (`KAPOST_ACTION_PREFIXES`, `looksLikeKaPostAction`). On the indexer side: `K-Digital-Freedom-Indexer/kachat-transaction-processor/src/k_protocol.rs:961-988` (`kchat:1:block`) with its verify at 3760-3780, and `k_protocol.rs:825-850` (`kchat:1:broadcast`) with its verify at 2466-2497. The same list is in iOS `KaChat/Services/KaChatSiteWallet.swift:349`.
- **Trigger:** this is the R3 attack again. A compromised or malicious `*.kasshi.io` page connected to K (which defaults to the Chatting Address, the KaPost identity key) calls `kachat.signMessage(...)` with either:
  - `block:<66-hex pubkey>` or `unblock:<66-hex pubkey>`;
  - `<b64 nickname>:<b64 image or empty>:<b64 bio>`, e.g. `SGFja2Vk::SGk=`.
- **What happens:**
  - Neither shape is refused:
    - `block:` and `unblock:` aren't in the prefix list;
    - the profile shape's text after the first colon starts with `:` or base64, not `[` or `{`, so the post check doesn't match.
  - The Sign sheet shows an opaque string. Once it is approved, anyone can broadcast `kchat:1:block:<your pubkey>:<sig>:block:<pubkey>`, and the KaChat indexer accepts it: it verifies exactly `"<blocking_action>:<blocked_pubkey>"` (`k_protocol.rs:3772`).
  - KaPost then hides that author's posts from you, because `KaPostsService.kt:837` drops posts with `blockedUser == true`.
  - Through `unblock`, someone you blocked comes back into your feed. That is useful to a harasser.
  - A signed profile string becomes your K profile (nickname, avatar, bio) on the indexer, which serves it as `user_nickname`.
  - Every other action shape (post, reply, vote, quote, unquote, follow, edit, delete, poll, pollvote, tip, schedule, cancel-schedule) is covered. Checked against `KaPostsProtocol` and the indexer's `message_to_verify` strings.
- **Fix:**
  - Add `"block:"` and `"unblock:"` to `KAPOST_ACTION_PREFIXES`.
  - Refuse texts of the shape "three colon-separated fields, each empty or strict base64" (the broadcast signing string).
  - Make the same change on iOS.
  - Longer term, as R3 says: default Connect to a spending address, or domain-separate KaPost signatures on the indexer.
- **Confidence:** Confirmed

### S3 No tapjacking guard on the send, sign and key-reveal surfaces
- **Severity:** Low
- **Where:** nothing in the app sets `filterTouchesWhenObscured`, checks `FLAG_WINDOW_IS_OBSCURED` or calls `setHideOverlayWindows` (grep over `app/src/main`). Relevant surfaces:
  - `SwipeUpToSend.kt` (every send, including a `kachat://pay/…` link from another app, which opens Send Kaspa filled in, `MainActivity.kt:628-642`);
  - `SiteWalletSheets.kt` (the site's Sign and Send);
  - the Reveal and Copy buttons on the seed and key screens.
- **Trigger:**
  1. A malicious app the user has given "Display over other apps" fires `kachat://pay/<its address>?amount=…`.
  2. It then draws a decoy over K: fully opaque on Android 8-11 (minSdk 26), or at the 0.8 opacity Android 12+ still lets touches through.
  3. It asks the user to swipe up or tap.
- **What happens:** the swipe reaches K's Swipe up to Send under the overlay, and the payment goes to the attacker. The same works for Sign on a site request. It needs a malicious app with the overlay permission, so the risk is narrow.
- **Fix:**
  - On API 31+, call `window.setHideOverlayWindows(true)` (permission `HIDE_OVERLAY_WINDOWS`) while a send review, a site Sign sheet or a key reveal is on screen.
  - On older devices, ignore touches whose `MotionEvent.flags` has `FLAG_WINDOW_IS_OBSCURED` / `FLAG_WINDOW_IS_PARTIALLY_OBSCURED` on the swipe bar and the Sign/Reveal buttons.
- **Confidence:** Plausible (code path confirmed; not run on a device)

### S4 The in-app browser loads plain-http pages on the same site
- **Severity:** Low
- **Where:** `ui/screens/HelpScreens.kt:296-312`. `isOnSite` checks only the host, so `http://<same host>/…` loads in the main frame. App-wide `usesCleartextTraffic="true"` (manifest) lets the WebView fetch it.
- **Trigger:** a page in the in-app browser (Kaspa Websites, Kasshi, the faucet, a .kachat Explorer link) links or redirects to `http://` on its own host, on a hostile network.
- **What happens:** an on-path attacker can rewrite that page while it sits inside K's browser chrome. The header does say `http://host` (line 117). On iOS, ATS refuses the load outright. On Kasshi's origin the site wallet is safe, because the bridge and script are https-only (`ORIGIN_RULES`, `isAllowedOrigin`).
- **Fix:** in `InAppBrowserClient.shouldOverrideUrlLoading`, rewrite main-frame `http` to `https` for an on-site target, or refuse it (return true).
- **Confidence:** Confirmed

## Checked and fine

- **Seed and keys at rest:**
  - `WalletManager` (accounts, mnemonics, passphrases) and `GroupSecretStore` use EncryptedSharedPreferences with an AES256-GCM Keystore master key.
  - Side stores such as Nextcloud and .kachat fall back to memory, never to plaintext (`StartupSafety`).
  - `allowBackup="false"`, and `data_extraction_rules.xml` excludes every domain for cloud and device transfer.
  - The NFC card holds a PBKDF2-SHA512 (600k) AES-GCM blob behind the Java Card applet's PIN.
- **Biometric gates:**
  - Seed, chatting key, spending keys, backup steps, Delete Account and .kachat actions go through `authenticateWithDeviceCredential`.
  - A phone with no screen lock is refused (no fall-through).
  - The three Security toggles are themselves gated in both directions (`Screens.kt:10156-10196`).
- **FLAG_SECURE:**
  - Applied to the seed/passphrase/private-key screens, Import, Passphrase entry, Backup steps, Backup Hygiene (until passed) and NFC backup/restore.
  - The onboarding steps count references on the flag (AND-030).
  - The key sheet in `KaChatApp.kt:1809` is `SecureOn`.
  - No privacy cover in Recents (owner's call).
- **Clipboard:**
  - Seed, keys and kpub go through `copyPrivateKeyWithAutoWipe`: `EXTRA_IS_SENSITIVE`, and a 30 s wipe via Handler plus WorkManager.
  - Import's Paste clears the clipboard.
- **Logs:**
  - R8 strips `Log.d`/`Log.v`.
  - The kept i/w/e lines truncate addresses (`takeLast(8..10)`) and carry no plaintext, keys, tokens or signatures.
  - OkHttp verbose logging caps at BASIC in release and runs through `shortenAddresses`.
  - The diagnostics export is address-shortened and has no secrets.
  - CrashRecorder writes stack traces only; no key material is in any exception text.
  - No `println`.
- **Manifest:**
  - Only MainActivity is exported, plus library components guarded by permissions (FCM `c2dm.SEND`, WorkManager/ProfileInstaller `DUMP`).
  - FileProvider is not exported, grant-only, and covers its own cache/crash folders only.
  - Every PendingIntent is `FLAG_IMMUTABLE`.
  - Dynamic receivers are `RECEIVER_NOT_EXPORTED`.
  - The notification extras on the exported activity are validated: address checksum, group id pattern, and a per-install token for KaPost.
- **Deep links and share targets:**
  - Every `kachat://`, App Link and retired-scheme link only routes or pre-fills: profile (`chat=1` only for a known partner), pay (Send Kaspa review and swipe), post, room (asks first), hub, explorer, compose text, chess/arcade.
  - Shares only pre-fill a composer.
  - A direct-share target must belong to the signed-in account.
  - `file://` streams are refused, and size is capped.
  - Intent payloads are stripped after use.
- **Site wallet (Kasshi):**
  - https main frame only, on allowed hosts, via origin-bound `addWebMessageListener` and document-start script, re-checked per message.
  - Message, params and concurrency caps; one sheet at a time; a 3 s pause after a decline.
  - Sign and connect re-derive the key and check it makes the connected address, so a stale connection after an account switch can't sign with another account's key.
  - Auto-sign is limited to kasshi.io/www, a 2-minute window, one page load, and Kasshi's exact challenge for the connected address with a range-checked timestamp (iOS R6 ported).
  - `sendKaspa` goes through Send Kaspa review with From locked, and has the 10-minute maybe-sent hold.
- **Trestly / Buy Kaspa / kUSDT core:**
  - Own WebView profile, wiped on close.
  - Origin-pinned bridge and navigation.
  - Camera only for Trestly or Stripe frames, after asking.
  - Mixed content never allowed; third-party cookies off.
  - The permit cap is applied when the amount is known (C6 ported).
  - `KusdtCore`: pinned SHA-256 assets served from memory, no file/content access, and every other request gets a 403.
- **WebViews in general:** no `addJavascriptInterface`, no `onReceivedSslError` override, no `setWebContentsDebuggingEnabled`, no file chooser or permission grants in the in-app browser, and non-http(s) schemes are refused in the main frame (`intent:`, `javascript:`, `data:`).
- **Network:**
  - No custom TrustManager or HostnameVerifier.
  - Cleartext is limited to gRPC/ws nodes by design (decision 3A).
  - User-editable servers (Nextcloud, Trestly site, room indexers) reject `http://`.
- **Link previews and images:**
  - `PublicOnlyDns` blocks loopback, link-local, RFC1918, ULA and multicast, and redirects are gated.
  - Rooms and KaPosts preview only an allow-list of sites, from posters with a .kachat name.
  - 1:1 strangers' links are tap-to-load; groups auto-load only for accepted senders.
  - Every remote image loader (preview, profile, full) is size-capped and uses the same DNS rule.
  - (Not blocked: CGNAT 100.64/10; negligible.)
- **Push:**
  - Data-only DMs are decrypted locally, and a message not for this account is dropped (`NotForThisAccount`).
  - Other-network and signed-out pushes are silenced.
  - Own sends never ring.
  - Payment/handshake pushes are receiver-filtered on the server (`kasia-indexer push.rs`).
  - Registration is re-signed per account; switching accounts goes through Log Out (unregister).
  - No FCM token or auth is logged.
- **Crypto:**
  - 1:1 nonces are 12 random bytes from SecureRandom.
  - Group nonces are HKDF(sender nonce key, deviceId ‖ counter). The counter is committed with `commit()` before use, and the bag is excluded from backup, so no reuse after a restore.
  - The BIP-340 nonce is deterministic (sha256(d ‖ m)), and different messages get different nonces.
  - `Schnorr.verify` checks even-Y R and s < n.
  - Backup envelope: AES-GCM with a fresh nonce, and a key from the identity key.
  - Domain separation between site signing (keyed BLAKE2b personal message), push auth (SHA-256 digest) and transaction sighash.
  - No security-relevant compare of secrets outside the AEADs.
- **Account isolation:**
  - Log Out and switching cancel all notifications, discard drafts and scheduled KaPost reminders, and unregister push.
  - Share shortcuts are rebuilt per signed-in account.
  - Delete Account wipes every per-wallet store listed in `AccountFeatureCleanup` and `wipeDeletedAccountData`: Room, groups, Nextcloud (with the app password revoked), site picks, KaPost, rooms, Cold Storage, Portfolio, Swap, card buy, .kachat, Address Book, the bell, tokens, caches and exports. An interrupted wipe resumes at next launch.
  - Background senders (contact notes, KaPost actions) check the owner before sending.
  - The only gap found is S1.
- **Release build:**
  - `isMinifyEnabled` is on and not debuggable.
  - Debug-only paths (send-failure simulation, Explorer demo data) are behind `BuildConfig.DEBUG`.
  - The keep rules don't strip security code. `-assumenosideeffects` covers only `Log.d`/`Log.v`, and no stripped call has side effects.
  - The release is never signed with the debug key.
  - Keystores, `keystore.properties` and `google-services.json` are gitignored and untracked.
  - No hard-coded secrets in source. The ChangeNOW key comes from local.properties or env and is empty in this checkout; it is readable from the APK by design, as on iOS.


## K Android release audit 2026-11-27: battery, network and memory

Code: main d1ccb92b (K 5.4 build 7). Read-only review. Paths are relative to `app/src/main/java/com/kachat/app/`.

## Status of the 2026-11-25 battery audit (A1-A6 and the Medium list)

| Item | Status now | Where checked |
|---|---|---|
| A1: 1:1 poll | **Fixed, and the fix holds.** Only the open chat gets the 2 s tick (5 s metered). The contact sweep reads 12 contacts (the 4 most recent plus a rotating slice) on the 30/60 s ForegroundBeat. With push active the sweep is doubled to 60/120 s. The next sweep is timed from the end of the last one, so sweeps can't run back to back. A 40-contact catch-up runs at start, and on returning after 5 min or more. | `repository/ChatRepository.kt:130-245`, `repository/ChatPollSchedule.kt` |
| A2: room block stream in the background | **Fixed.** `reevaluate()` gives an empty stream set when the app isn't in front or nobody is signed in. Without push, a bell-on room gets one indexer request every 5 min in the background, and that request also clears a "down" mark. The group stream is foreground-only. The KaPost fallback stream is foreground-only, Wi-Fi only and only while the feed shows. The inbox scan is foreground-only. | `services/BroadcastScanningService.kt:336-372,514-536`, `services/GroupScanningService.kt:116-139`, `services/kaposts/KaPostsChainFallback.kt:865`, `repository/ChatRepository.kt:4426-4436` |
| A3: no-push background | **Fixed for the 1:1 loop.** It now does one light incoming-only pass of 12 contacts every 5 min. It is paused when signed out, when notifications are off in K or in Android, and while push is active. The node pool's own background behaviour without push is still costly: see R1 and R2 below. | `ChatRepository.kt:160-189`, `ChatPollSchedule.background` |
| A4: SyncWorker | **Fixed.** The job is enqueued only when `!backgroundQuiet && hasGroups`, so only without push, signed in and with notifications on. It is cancelled otherwise. It now requires network and a battery that isn't low. Push re-registration on a cold start is skipped by a persisted fingerprint (N3). | `KaChatApplication.kt:318-357`, `services/SyncWorker.kt`, `services/PushRegistrationManager.kt:595-605` |
| A5: CameraX never unbound | **Fixed.** `onDispose` clears the analyzer and unbinds the preview and analysis use cases. A provider answer that arrives after dispose is dropped. | `ui/screens/QrScannerScreen.kt:97-109,129-130` |
| A6: funding gate polls | **Fixed.** The loop runs only while `active && running` (`rememberScreenRunning`: RESUMED and the Hub section not hidden). It runs on the beat, and on own-address UTXO activity. | `ui/screens/ZeroBalanceFundingUi.kt:86-121`, `KachatNamesLiveScreens.kt:4825` |
| Battery exemption | **Fixed in principle.** It is asked only when `PushState.pushUnavailable`, notifications are granted, and it hasn't been asked before. One weak spot remains: see R7. | `MainActivity.kt:130-165,228-246`, `services/PushState.kt:218-223` |
| N8: Automatic Scan stuck unhealthy | **Improved.** An unhealthy pool is probed every 10 s, stretching to 60 s while the Active count doesn't rise. A dead pool backs off to 2 min. In the background without push it is still bad: see R2. | `NodePoolManager.kt:660-695`, `NodeProbeCadence` |
| Unaligned timers | **Mostly fixed.** The 1:1 sweep, Address Activity, kUSDT, rooms, KaPost, the group pass, the pinned-node keepalive and the unnamed sweep all run on ForegroundBeat. Still on their own schedule: the Nextcloud watcher and the unhealthy-pool probe (R6). | |
| B7: Address Activity | **Fixed.** Balances are fetched in one batch request per 100 addresses. A pass is skipped when the feature is off and no balance screen is listening. One exception remains: see R5. | `services/AddressActivityNotifier.kt:139-147,311-323` |
| N10/S6: group push | **Fixed.** A readable group push syncs only its own group's lanes. An unreadable one runs the coalesced catch-up. Both are bounded by an 8 s budget. | `services/KaChatFirebaseMessagingService.kt:637-660` |
| N3, N4, N5, N6, S7 | **Fixed.** N3: fingerprint persisted. N4: `probingPaused()` on a network change or going back online. N5: `withdrawFailedSubscribe` in KaspadConnection. N6: `ensureIdleClose` closes connections idle for 30 s. S7: PushState restores the last known answer. | |
| S3, S4, S5 (1:1 tick repeats) | **Fixed.** S3: `NoticeChainGate`. S4: alias discovery every 2 min for the open chat and 5 min for the others, with shallow reads. S5: the repair runs once per account per process. | |
| R4 spinners and fee shimmer, R7 GIF, R9 Explorer dot, R3 KaPost new-posts check | **Fixed.** Each is gated on a hidden Hub section, on the paused frame clock, or on `rememberScreenRunning`. | `ui/theme/IosComponents.kt:196`, `ComposerFeePill.kt:78`, `AnimatedGifImage.kt:66`, `ExplorerPieces.kt:430`, `KaPostsScreens.kt:1508` |

---

## Findings

### R1 Without push, the background keeps a gRPC node connection alive with a keepalive ping every 60 s, so the radio wakes about once a minute all night
- **Severity:** Medium
- **Where:** `services/NodePoolManager.kt:686-691` (the background branch of the probe loop), `services/NodePoolManager.kt:990-993` (background is treated as metered: the primary is kept), `services/NodePoolManager.kt:1709-1724` (`enterBackground` closes connections only when `isBackgroundQuiet`), `services/grpc/KaspadConnection.kt:199,204,232-234` (`keepAliveTime` 60 s, or 75 s metered, with `keepAliveWithoutCalls(true)`).
- **Trigger:** The background is not quiet: signed in, notifications on, and no push. That covers every testnet user, phones without Play services, and any device whose push registration fails. These are also exactly the users K asks to exempt from battery optimisation, so Doze doesn't stop any of this. K is in the background with the screen off and the pool connected over gRPC.
- **What happens:** In push mode `enterBackground` closes every idle connection and `ensureIdleClose` sweeps up later ones. Without push neither runs, and `probeCycle` keeps the primary connection on purpose ("the pool stays up for what still needs it"). Each KaspadConnection holds a `MessageStream` call open, and HTTP/2 keepalive is set to 60 s (75 s metered). The probe runs only every `max(wait, 120 s)`, so between probes gRPC sends a PING after each 60 s without reads. The result is a radio wake about every 60 s, all night. On cellular each wake costs the radio tail (about 5-10 s at high power), so 60 wakes an hour keeps the modem in a high-power state for roughly 10-15 % of the night.
  - Nothing in the no-push background needs the node. The light 1:1 pass and the room poll use the HTTP indexer and REST. A notification Reply or a send dials through `connectionFor` on demand.
- **User's view:** "K drains my battery overnight" on de-Googled phones and on testnet. Battery usage shows K holding mobile radio while the phone sleeps.
- **Fix:** Close idle connections in the background whether or not push is active. In `enterBackground`, drop the `isBackgroundQuiet` gate before `closeIdleConnections` and `ensureIdleClose`. Also make `probingPaused()` true in the background without push, or have the background probe skip the primary when no request needs it. A Reply still dials on demand.
- **Confidence:** Confirmed. The code path and the keepalive configuration were read. Wake timing is inferred from gRPC-java's keepalive semantics; it was not measured.

### R2 Without push, a background node pool short of 8 Active nodes runs a full discovery wave every 2 minutes with the screen off, and every background cold start does the same
- **Severity:** Medium
- **Where:** `services/NodePoolManager.kt:977-999` (`firstWave`: only a *healthy* pool shrinks to the primary in the background, otherwise it is `fullWave`), `:974-976` (the DNS and wss seed refresh starts whenever `activeCount < targetActiveNodes`), `:1043-1085` (peer gossip), `:174` (`targetActiveNodes = 8`), `:686-691` (the background wait is `max(wait, 120 s)`).
- **Trigger:** K is in the background without push, as in R1, and the pool has fewer than 8 Active nodes. That happens on testnet (few public nodes), on networks that block gRPC on most nodes, and on every cold start of the process in the background. A cold start begins with every persisted node unprobed, so the count is 0. Without push, background cold starts are frequent: the 15-min SyncWorker (for accounts with groups) and FCM data messages both start the process, and on phones that kill cached processes each SyncWorker run is a cold start.
- **What happens:** Every 2 minutes the probe loop dials the full list of endpoints, roughly 40-50. That list is seeds, manual, discovered, DNS-resolved, wss seeds and stand-ins. It also re-resolves the DNS and wss seeds (subject to their cooldown) and asks a node for peers. Each dial is a TCP+TLS handshake plus GetInfo. Every reachable, synced node stays connected afterwards: the sweep only closes addresses that weren't probed. Each of those connections then carries its own 60 s keepalive, which compounds R1.
  - On a cold start, the 1:1 loop's background branch also runs a light pass at once (`lastPassEndAt == 0`, `ChatRepository.kt:176-183`). So each SyncWorker wake costs a full node discovery plus a 1:1 pass, on top of the group sync the job exists for.
- **User's view:** Same as R1, worse. Hundreds of TLS handshakes an hour with the screen off.
- **Fix:** In the background, never run discovery. Probe only `registry.activeBestFirst().firstOrNull()`, or nothing at all when R1's fix is taken, whatever the pool's health. Leave the DNS, wss and gossip refreshes for the foreground. A background process started for SyncWorker or FCM doesn't need the node pool.
- **Confidence:** Confirmed in code (the `healthy` guard on the background shrink). How often a pool is short of 8 nodes in the wild is Plausible.

### R3 Opening a 1:1 chat loads its whole stored history into memory, photo and voice parts included
- **Severity:** Medium
- **Where:** `viewmodels/ChatViewModel.kt:3997-4007` (`preloadThreadHistory` loads 500 rows at a time until the history is exhausted), `:3955-3987` (each page is prepended to `_olderThreadPages`), `services/database/MessageDao.kt:60-75,480-487` (the page query selects `plaintextBody` and `encryptedPayload`, each capped only at 900,000 characters), `util/MediaParts.kt:187` (a 1:1 photo part is up to 31,000 bytes).
- **Trigger:** The user opens a long 1:1 chat that has many photos or long voice notes. Each photo is 1-12 parts.
- **What happens:** Every row carries the decrypted part (base64) and the raw encrypted payload (hex). That is roughly 100-150 KB of Java strings per photo or voice part. A chat with a few hundred photos pulls tens of MB, and a heavy one 100 MB or more, into the heap. Many Android phones have a 256 MB app heap; the manifest has no `largeHeap`. Coil's cache (25 % of the heap) and the 24 MB photo LRU sit on top of that.
  - Every 150 ms page also re-emits the whole older-pages map, so the thread list is recombined over the full history about 100 times while the preload runs.
  - Each 500-row page of large rows overflows SQLite's 2 MB CursorWindow many times. Every refill re-runs the query.
- **User's view:** Opening a big photo chat stutters and gets hot. On low-memory phones K can be killed for OOM, or the hidden Hub sections are dropped.
- **Fix:** Cap the preload, for example to the newest 2,000 rows or about 10 MB, and load the rest on scroll as before. Also stop selecting `encryptedPayload` in `getMessagesForContactBefore` when `plaintextBody` is present: use `CASE WHEN plaintextBody IS NOT NULL THEN '' ELSE ... END`. The thread doesn't render the payload.
- **Confidence:** Plausible. The code path and the stored columns are confirmed. Heap numbers are estimated; not measured on a device.

### R4 Web pages keep running in the background: K never pauses its WebViews
- **Severity:** Low
- **Where:** `ui/screens/HelpScreens.kt:185-236` (`InAppBrowserScreen`: Kaspa Websites, Kasshi in Kaspa Hub, the browser inside .kachat), `ui/screens/tokens/TrestlyBrowser.kt:233-245` (Trestly, Buy Kaspa). There is no `WebView.onPause()`, `pauseTimers()` or `onResume()` call anywhere: `grep` finds only `MainActivity.onResume`.
- **Trigger:**
  - A Kasshi, Kaspa Websites or Trestly page is open when the user leaves K or the screen turns off.
  - Or a page is opened from the .kachat section, which is kept alive, and the user switches to another Hub section. The page stays composed but hidden.
- **What happens:** The page's JavaScript keeps running: timers (Chromium only throttles hidden pages), WebSockets, and the site's own polling of its backend or a node. The process stays alive, and without push it is exempt from Doze, so this can go on for hours with the screen off. iOS suspends the whole app.
- **User's view:** Background data and CPU from a page the user isn't looking at.
- **Fix:** Pause the page while it isn't in view. Observe the lifecycle around each AndroidView WebView: on ON_STOP call `onPause()` (and `pauseTimers()` once no other K WebView is showing); on ON_START call `onResume()` and `resumeTimers()`. Do the same on `LocalHubSectionHidden`.
- **Confidence:** Plausible (no WebView lifecycle calls, confirmed; how much a given site does in the background is not measured).

### R5 A hidden Cold Storage section keeps Address Activity polling every beat even with its notifications off
- **Severity:** Low
- **Where:** `ui/screens/coldstorage/ColdStorageScreens.kt:534-537` (collects `ownAddressActivity` while hidden, and only drops the events), `services/AddressActivityNotifier.kt:139-147` (`balanceScreenListening = subscriptionCount > 0`).
- **Trigger:** Address Activity notifications are off. The user has opened Cold Storage from Kaspa Hub (it is kept alive) and then moved to another section or tab while the Hub route lives.
- **What happens:** The collector stays subscribed while the section is hidden. `subscriptionCount > 0`, so the notifier makes a batch balance request every 30 s (60 s metered) as long as K is open, for a screen nobody is looking at.
- **Fix:** Key the collector on `coldHubHidden` and return when hidden: `LaunchedEffect(accountId, coldHubHidden) { if (coldHubHidden) return@LaunchedEffect; ... }`. That way a hidden section doesn't count as a listener.
- **Confidence:** Confirmed

### R6 Remaining foreground timers off the shared beat
- **Severity:** Low
- **Where:**
  - `services/NextcloudSyncService.kt:451-458`: the remote-change PROPFIND uses `delay(30 s)`, or 60 s metered, measured from the last run.
  - `services/NodePoolManager.kt:679-681,692-694`: an unhealthy pool is probed with `delay(10-60 s)`, off the beat.
- **Trigger:** K is open on cellular, with Nextcloud Automatic Sync on, or with a pool short of 8 nodes.
- **What happens:** These requests land between beats, so the cellular radio wakes once more per window. That partly undoes the ForegroundBeat alignment (old B3/N9). For an unhealthy pool on cellular it is also a full ~45-endpoint wave every 60 s (the unhealthy branch ignores `isMetered`).
- **Fix:** Use `ForegroundBeat.sleepUntilNextBeat(offset)` for the Nextcloud watcher. For the unhealthy probe, round up to the next beat once the stall backoff reaches the beat interval. On a metered path, cap the unhealthy wave to the known-good nodes.
- **Confidence:** Confirmed

### R7 One failed push registration is enough to ask for the battery exemption, and the exemption then stays after push works
- **Severity:** Low
- **Where:** `services/PushState.kt:218-223` (`pushUnavailable` is true after any single `register` attempt that failed), `MainActivity.kt:130-165,228-246`.
- **Trigger:** The device has Play services and push would work. One registration attempt fails once (offline at that moment, a timeout, or a 5xx from the push service) while notifications are granted.
- **What happens:** The battery rationale sheet shows straight away and, on Allow, Android's exemption dialog. The answer is stored as "asked" for good. Once push recovers, K stays exempt from battery optimisation, although with push working nothing needs it. Gated background work is quiet with push, but anything not gated (R4's WebViews, a stuck block client's 30 s idle-close sweep) then runs through Doze.
- **Fix:** Treat push as unavailable only when there is no push service, or after the registration has failed repeatedly (for example 3 attempts over at least an hour). Re-check before showing the sheet.
- **Confidence:** Confirmed

### R8 With a flaky mobile connection, K can stream full blocks over cellular while it is open
- **Severity:** Low
- **Where:**
  - `services/IndexerHealth.kt:70-86`: three failures at least 5 s apart within 2 min mark the indexer down. Timeouts count whenever the phone is "online" (`IndexerApiResilience.kt:46-53`).
  - `repository/ChatRepository.kt:4426-4436`: the inbox block scan runs in front on any network.
  - `services/GroupScanningService.kt:134-138`: the group stream is allowed on a metered network while the indexer is down.
- **Trigger:** K is open on poor cellular. A few indexer reads time out, so `IndexerHealth.isDown` turns true.
- **What happens:** The node's block stream (about 10 full blocks per second, every one decoded, tens of MB per hour) starts for the inbox scan, and for groups if the account has any. It runs over the same bad cellular link until an indexer read succeeds again. The recovery check is about once a minute. This matches the iOS design for real outages, but a weak signal is not an outage.
- **Fix:** Don't count timeouts within a network-change or weak-signal grace window, as `KaPostsIndexerHealth.isHandoffError` already does for KaPost. Or require the outage to last a minute or two before starting a block stream on a metered path.
- **Confidence:** Plausible

### R9 A few caches grow for the life of the process
- **Severity:** Low
- **Where:** `services/KaPostPreviewLoader.kt:42-43` (`cache`, `misses`: plain HashMaps), `services/kaposts/KaPostRepostOrigins.kt` (`found`, `failedAt`: plain HashMaps).
- **Trigger:** Long KaPost sessions with many quote and repost cards.
- **What happens:** One entry per quoted or reposted post ever shown, never evicted, until the process dies. These are small objects, so it is only a slow creep. Every other map checked is bounded or keyed by contact or account.
- **Fix:** Use a `LruCache` (for example 500 entries) for each.
- **Confidence:** Confirmed

---

## Loops and timers: interval, start and stop

Columns: **Fg** = app in front. **Bg** = background or screen off (they behave the same; `appForegroundFlow` follows ProcessLifecycle STARTED). **Signed out**. **Push** = push active. **Hidden Hub** = in a kept, hidden Kaspa Hub section.

| Loop | Interval and work | Fg | Bg | Signed out | Push active | Hidden Hub |
|---|---|---|---|---|---|---|
| 1:1 poll (`ChatRepository` init) | Open chat: 2 s (5 s metered). Sweep of 12 contacts: 30/60 s beat. Catch-up of 40. | runs | No push: light pass every 5 min. Push: paused. | waits | sweep ×2; Bg paused | n/a (chats not kept) |
| Room sweep (`BroadcastScanningService`) | Beat. One indexer request per bell-on room. | runs | No push: every 5 min. Push: off. | off | covered rooms every 5 min | n/a |
| Room block stream | Live, about 10 blocks/s | Uncovered rooms, or the open room on Wi-Fi | off | off | same | n/a |
| Open room poll (`BroadcastViewModel`) | 8 s | runs | sleeps | skipped | same | n/a |
| Group live poll (`GroupScanningService`) | Full pass on the beat (5 min with push). Open group 5 s (15 s metered). | runs | off | off | 5 min | n/a |
| Group block stream | Live | Wi-Fi until the indexer serves `since` reads, or indexer down | off | off | same | n/a |
| SyncWorker | 15 min, groups only | job | no push + groups | cancelled | cancelled | n/a |
| Node probe | Healthy: beat, 4 nodes (Wi-Fi) or 1 (metered). Unhealthy: 10-60 s. | runs | No push: 2 min (R1, R2). Push: paused, connections closed. | paused | Bg paused | n/a |
| Public fallback watch | 20 s | runs | stopped | n/a | n/a | n/a |
| Idle close sweep | 30 s | n/a | only while paused | runs | runs | n/a |
| Address Activity | Beat; one batch request | runs (skipped if off and nobody listens) | stopped | returns early | same | R5 |
| kUSDT (`TokenWallet`) | Beat (5 min with Address Activity off) | runs | stopped | loop ends | same | n/a |
| KaPost notifications | Beat without push, 5 min with push | runs | stopped | stopped | 5 min | n/a |
| KaPost new-posts check | Beat (`dueOnBeat`) | runs while the feed shows | off | n/a | n/a | off |
| KaPost indexer probe and chain stream | 60 s probe; stream on Wi-Fi with the feed on screen | outage only | asleep | n/a | n/a | off (`onScreen`) |
| .kachat unnamed sweep | 60 s on the beat grid, one batch | runs | asleep | off | n/a | n/a |
| .kachat registration driver | 2-5 s while registering | runs | asleep | stops | n/a | n/a |
| Profile rescanner | Hourly | runs | stopped | breaks | n/a | n/a |
| Chess arena (`ChessTournamentService`) | 2 s fast poll, 8 s backfill, 1 s tick, arena live-viewed | while a chess screen is acquired | asleep | n/a | n/a | released (`OnScreenPresence`) |
| Chess UI clocks and dots | 100 ms-1 s | `rememberScreenRunning` | off | n/a | n/a | off |
| Explorer live (`ExplorerLive`) | Full-block stream; 1 s flush; 10 s REST | `onScreen && resumed` | off | n/a | n/a | off |
| Funding gate | Beat plus UTXO events | `rememberScreenRunning` | off | n/a | n/a | off |
| Card Buy (`CardBuy`) | 8 s with Buy Kaspa open, else beat; up to a week per order | runs | waits for front | stops | n/a | n/a |
| Swap deposit | 10 s until it ends | `rememberScreenRunning` | off | n/a | n/a | off |
| Payment notice retry | 20 s for up to 15 min | runs | skipped | n/a | n/a | n/a |
| Nextcloud watcher | 30 s (60 s metered), off the beat (R6) | runs | cancelled | no-op | n/a | n/a |
| Inbox block scan | Live | indexer down only (R8) | off | off | n/a | n/a |
| Spinner, fee shimmer, BlockDAG, GIF | Frame-based | runs | frame clock paused | n/a | n/a | gated |
| WebViews | The page's own JS | runs | **keeps running (R4)** | n/a | n/a | **keeps running (R4)** |

**FCM handlers:**
- Every handler runs under `runBlocking` with budgets: an 8 s sync budget inside a handler window of about 20 s, and an off-chain read deadline.
- A group message push syncs only its own group's lanes. A control push runs one coalesced catch-up.
- A DM push queues one ingest of that sender's conversation.
- With push active, a process started by a push leaves every loop paused: the 1:1 loop in PAUSE, the node pool paused, no room poll, the group stream needing the front.
- No heavy unbounded work was found.

**Not present:** no foreground services, wake locks, AlarmManager or exact alarms. The manifest has no WAKE_LOCK, FOREGROUND_SERVICE or BOOT permission.

## Steady-state cost: push working versus not

- **Push working, K open on Wi-Fi.** About one 12-contact sweep a minute, plus the open chat's 2 s tick. On each 30 s beat: the probe of 4 nodes, Address Activity and kUSDT. Every 5 min: rooms covered by push, groups, KaPost. The unnamed sweep is one request a minute. That is about 30-40 requests a minute, plus about 30-60 for an open chat. On metered the beat is 60 s and one node is probed.
- **Push working, background or screen off.** Nothing runs. Connections close after at most 30 s idle, SyncWorker is cancelled, and K wakes only for FCM pushes, each bounded. This is close to iOS.
- **No push, K open.** The same as above with the sweep every beat and rooms and groups every beat: about 50-70 requests a minute.
- **No push, background or screen off.**
  - Every 5 min: a 1:1 light pass (12 contacts) and one request per bell-on room.
  - Every 2 min: a node probe.
  - Every 15 min: SyncWorker, if there are groups.
  - On average that is about 6-10 requests a minute, down from about 65-75 before the fixes.
  - On top of that, R1 adds a keepalive radio wake about every minute. R2 adds a full discovery wave every 2 min whenever the pool is short, or after a background cold start.
  - The exemption is asked for exactly in this case, so all of it runs through Doze. R1 and R2 are what's left of the overnight drain for these users.

## Checked and fine
- ForegroundBeat alignment and `dueOnBeat`. `rememberScreenRunning` is RESUMED and not hidden. `OnScreenPresence` pairs appear and disappear correctly.
- Hub kept sections (KaPost, Portfolio, KaPlay, .kachat, Explorer, Cold Storage):
  - The hidden flag reaches the spinners, the shimmer, the countdown clock, the Explorer, chess acquire and the KaPost feed check.
  - Memory pressure (`onTrimMemory` at RUNNING_LOW or above) drops the hidden sections.
- The block-stream lifecycle:
  - NOTIFY_STOP goes to the exact subscribed connection.
  - A failed subscribe is withdrawn.
  - Retries back off and wait for the network.
  - The `liveBlocks` client pauses in the background.
- PushState `backgroundQuiet` takes in signed-in state, K's notification switch and Android's (with the block-state broadcast). `NotificationHelper.appForeground` defaults to false, so a background cold start never behaves as if it were in front.
- Signed out:
  - Stopped: the 1:1 loop, the node pool (connections closed), the room stream and poll, the group stream and poll, kUSDT, the KaPost poller, the unnamed sweep and the rescanner.
  - Not stopped, but harmless: Address Activity and the Nextcloud watcher still wake but do no network work.
- CameraX unbind on dispose (both scanners use `CameraQrScanner`).
- Memory:
  - Coil uses the default bounded memory and disk caches. The three loaders made with `newBuilder()` share one memory cache, and a pixel-cap decoder is installed.
  - The incoming-photo LRU is 24 MB by byteCount; GIF bytes 8 MB.
  - Group and backup avatar LRUs hold 32 and 64 entries of bitmaps capped at 512 px. The sources are about 10 KB, so this is small in practice.
  - `SafeBitmapDecode` caps dimensions. `BroadcastSenderVerifier` maps are bounded. The `LinkPreviewService` cache is capped. `KaPostsLiveChainFeed.seenPayloads` is capped.
  - Explorer pending blocks are capped. The KusdtCore headless WebView is torn down when idle.
- No Activity, View or NavController is held in a singleton:
  - `AppText` and `KaPlayText` hold the application context.
  - `KaPostsDock.dockContent` is cleared on dispose.
  - The WebView reloader ref is per screen.
- FCM handlers are bounded (see above). No foreground service, wake lock or alarm exists.
- WorkManager: SyncWorker has network and battery-not-low constraints and the UPDATE policy. The Nextcloud fallback runs every 6 h with constraints. The clipboard wipe is a one-time job.


## K Android release audit, 2026-11-27: smoothness and UI performance (Compose)

Code: main d1ccb92b. Read-only review. Line numbers are as of that commit. Paths are relative to
`app/src/main/java/com/kachat/app/`.

Most of the iPhone smoothness fixes (C1 chess sniff, C3, L1, L2 batching, L6 Swap QR, L9, T1) and the
battery audit's rendering fixes (R4 spinners, R7, R9, B10 timers) are on Android and work as
intended. The findings below are what is left, and most are specific to Android.

### S1 The 1:1 chat merges, sorts and scans its whole preloaded history on the main thread
- **Severity:** Medium (High for long chats with a lot of media)
- **Where:**
  - `viewmodels/ChatViewModel.kt:3931-3941` (`threadMessages`: `combine` of window + older pages, then `filter` + `sortedWith`, with no `flowOn`);
  - collected by `produceState` on the main dispatcher (`ui/screens/ScreenRunning.kt:58-66`, used at `ui/screens/Screens.kt:248`);
  - the history preload is at `viewmodels/ChatViewModel.kt:3997-4007` (500 rows every 150 ms until the whole conversation is in memory);
  - the per-change scans are at `ui/screens/Screens.kt:256-263` (`filterNot` + `VoicePartGrouping.group` over everything), `:758-770` (`activeChessGame`) and `:1739-1749` (`chessSourceMessages`). Each scan runs `mayHoldChess` (`:2394`, `body.contains("\"chess_")`) over every message body.
- **Trigger:** you open a 1:1 chat with a long history (thousands of rows, some of them photos or voice notes whose body is a tens-of-KB base64 data URL).
- **What happens:**
  - About 0.3 s after opening, `preloadThreadHistory` pages the whole history in, 500 rows every 150 ms. Every page triggers this on the main thread:
    - re-concatenating, de-duplicating and sorting the full list;
    - a structural `equals` against the previous list;
    - two full `contains` scans of every body, media payloads included (a chat with 300 photos or voice notes of about 30 KB means about 18 MB of chars scanned per change);
    - the part grouping;
    - rebuilding the LazyColumn's key map.
  - After the preload, any write to the `messages` table re-runs the merge and sort on the main thread. That includes another contact's sweep, a read flag or a delivery-status flip. A real change in this chat also re-runs both scans.
  - User's view: stutter when you start scrolling up right after opening a long chat, and a hitch each time a message lands or its status flips.
  - The group thread is better: decryption is `flowOn`'d (`repository/GroupRepository.kt:455`). But `ChatViewModel.kt:2095-2102` still filters the whole group history on the main thread on every change and every 60 s (`systemLineClock`).
- **Fix:**
  - Add `.flowOn(Dispatchers.Default)` to `threadMessages`, and to `getGroupMessages`'s combines.
  - Move the hidden-row filter, the part grouping and the chess source list into the view model on `Default`. Emit one ready `ThreadModel`, and `distinctUntilChanged` it.
  - Have `mayHoldChess` skip media envelopes first (`InlineMediaSniff.mimeType(body) != null`, a 2 KB head check). Compute the chess source once and use it for both the top bar and the list.
- **Confidence:** Confirmed (code path). The frame cost is estimated, not measured.

### S2 QR codes are encoded on the main thread at 1,500-2,000 px, including in every chat composer for 0-balance users
- **Severity:** Medium
- **Where:**
  - `ui/screens/Screens.kt:11544-11580` (`rememberQrBitmapPainter(content: String)`, default `size = 512` dp, so about 1,536 px on a 3x phone and 2,048 px on a 4x phone): ZXing encode, then a 2.4-4.2 M-call `matrix.get` loop, a 9-16 MB `IntArray` and a bitmap of the same size, all inside `remember {}` during composition. The `ByteArray` overload at `:11590` is the same.
  - Callers:
    - `ui/screens/ZeroBalanceFundingUi.kt:193`: the funding card in the composer of every 1:1, group and room, and in the KaPost gate dialog, drawn at 140 dp;
    - `ui/screens/AddressReceiveView.kt:198`, `ui/screens/WalletCards.kt:196`, `ui/screens/WelcomeGuideScreen.kt:543`, `ui/screens/coldstorage/ColdStorageAccountCard.kt:243`, `ui/screens/explorer/ExplorerPages.kt:696` and `ui/screens/Screens.kt:11883`, `:13471`: all 220-240 dp;
    - `ui/screens/PaymentRequestViews.kt:751`: 260 dp.
- **Trigger:**
  - Every new or read-only user (confirmed 0 KAS) opening any chat, group or room. `remember` doesn't survive leaving the screen, so it happens on every open.
  - Anyone opening Receive, the Wallet's address card, an Explorer address QR, a Cold Storage account card or a payment-request QR.
- **What happens:**
  - Tens of ms of main-thread work plus about 19-32 MB of short-lived allocations (likely a GC) in the first frames, during the screen's fade or the sheet's slide.
  - Each open screen then keeps a 9-16 MB bitmap for a 140-240 dp image.
  - User's view: opening a chat lags for new users (their first impression of the app), and Receive or address QR sheets open with a hitch.
  - Swap's QR (`tokens/SwapScreen.kt:1642-1680`) and the animated Cold Storage QR (`coldstorage/ColdQrViews.kt:169-195`) were already moved off the main thread and encoded at module size; this shared helper was not.
- **Fix:** encode at one pixel per module (`encode(..., 0, 0, ...)`, as `encodeQrModules` does) and draw it scaled with `FilterQuality.None`. Do it in `produceState` on `Dispatchers.Default` with a small `LruCache` keyed by content, as `SwapQrCode` does.
- **Confidence:** Confirmed (code path and sizes). The time is estimated.

### S3 Every 1:1 voice note, photo and JSON bubble runs a full, uncached Gson parse as an image on the main thread
- **Severity:** Medium
- **Where:**
  - `util/ImageMessage.kt:27-50`: `parseOrNull` has no `InlineMediaSniff` check and no cache, unlike `VoiceMessage.parseOrNull` at `util/VoiceMessage.kt:60-67`.
  - It is called in `MessageBubble` at `ui/screens/Screens.kt:2762-2764`, before the voice check at `:2770`. It is also called by `GroupChatScreens.kt:1606-1609` (only when not voice) and by `MediaPartBubbles.kt:73`.
- **Trigger:** you scroll a 1:1 chat that has voice notes, photos, chess moves or other JSON messages.
- **What happens:**
  - Every bubble whose body starts with `{` is fully parsed by Gson as an image during composition, on the main thread.
  - A voice note's whole payload (tens of KB) is deserialized and then thrown away, because the mime isn't `image/`. `VoiceMessage` parses it again, from cache.
  - A photo is re-parsed every time its bubble scrolls back into view, because `remember` doesn't outlive the item. The bitmap is cached, but this parse isn't.
  - A chess move or any other JSON body parses into a `VoiceMessageContent` whose non-null `mimeType` is null. That throws an NPE, which is caught, and writes a `Log.w` line for each such bubble.
  - User's view: dropped frames when flinging through a voice- or photo-heavy 1:1 history.
- **Fix:**
  - At the top of `ImageMessage.parseOrNull`, return null unless `InlineMediaSniff.mimeType(text)` starts with `image/`.
  - Add the same 64-entry LRU that `VoiceMessage` has.
  - In `MessageBubble`, parse the voice note first and skip the image parse when it is one (as the group bubble already does).
- **Confidence:** Confirmed.

### S4 No lazy list sets `contentType`, and the chat, room and feed timelines mix very different rows
- **Severity:** Low
- **Where:** `contentType` appears nowhere under `ui/`. The mixed lists:
  - the 1:1 timeline, `ui/screens/Screens.kt:1821`;
  - the group timeline, `ui/screens/GroupChatScreens.kt:1188`;
  - the room timeline, `ui/screens/BroadcastScreens.kt:1244`;
  - the Posts feed, `ui/screens/kaposts/KaPostsScreens.kt:1615`;
  - the Chats list, `ui/screens/ChatsScreen.kt:777`, which mixes chats, groups and rooms.
- **Trigger:** you scroll any of these lists.
- **What happens:**
  - Every item shares one reuse pool, so a slot that held a photo, voice note, chess or payment bubble is reused for a text bubble (or the reverse).
  - Most of its subtree is then disposed and rebuilt, instead of being reused by a row of the same shape.
  - User's view: more composition work per row that scrolls in, so scrolling is less smooth on mid- and low-end phones.
- **Fix:** `contentType = { _, msg -> bubbleKind(msg) }` (text, image, voice, chess, payment, system) on the timelines; `contentType = { it::class }` on the Chats list entries.
- **Confidence:** Confirmed (absence). The gain is estimated.

### S5 Public room rows are built inline in the item lambda, so room-wide state changes recompose every visible row
- **Severity:** Low
- **Where:**
  - `ui/screens/BroadcastScreens.kt:1244-1420` and onward: the whole row (reply parse lookup, link checks, translation key, `strippingRoomBidiControls`, avatar, quick bar, bubble) is written inline in `itemsIndexed {}`. The 1:1 and group rows, by contrast, are their own skippable composables.
  - The row reads screen-level maps directly: `translatableKeys` (`:364`, read at `:1813`, `:1924`), `translationStates`, `reactionsByTxId` (`:369`), `failedEdits` and `editsByTxId`.
  - `viewmodels/BroadcastViewModel.kt:804-816`: `considerTranslation` adds one key per offerable message to `_translatable`, one emission each.
- **Trigger:**
  - You open a room where many messages are in another language. Each row composing asks for detection, and each "translatable" answer is a separate emission.
  - Or any reaction, edit or failed edit lands in a busy room.
- **What happens:**
  - Each emission re-runs the body of every visible row, not only the row that changed.
  - Opening such a room gives N emissions × all visible rows of recomposition in the first second or two, while the reader starts scrolling.
- **Fix:**
  - Extract the row into a `@Composable RoomMessageRow(...)` that takes per-row values (`canTranslate: Boolean`, `reactions: List`, `failedEditText: String?`, `translation: TranslationState?`).
  - Or read the maps through per-key `derivedStateOf`.
  - Optionally batch `_translatable` updates, for example one emission per 250 ms.
- **Confidence:** Confirmed (structure). The cost is estimated.

### S6 Explorer pages draw long lists all at once inside a `verticalScroll`
- **Severity:** Low
- **Where:**
  - `ui/screens/explorer/ExplorerPages.kt:254-262` (`ExplorerPageScaffold`: a `Column` + `verticalScroll`);
  - the lists in it: a transaction's inputs and outputs (`:407`), a block's transactions (`:477`), its parents or children (`:494`), and an address's history (`:628`), which grows by a page on every Load More;
  - the address page also collects `live.stats` at its root (`:522`), so each stats change recomposes the whole page.
- **Trigger:** you open a block with a few hundred transactions, a transaction with many inputs (a compound or KRC transaction), or tap Load More several times on a busy address.
- **What happens:** every row is composed and laid out in one frame, and again for all rows on each Load More. User's view: a lag opening those pages, and a pause on each Load More. iPhone L8 fixed this with lazy stacks; it isn't ported.
- **Fix:** make `ExplorerPageScaffold` a `LazyColumn` (or give these sections `items(..., key = { it.id })`), and read `stats` only in the balance card.
- **Confidence:** Confirmed.

### S7 Arcade's leaderboard and .kachat Recent Activity are 100-row non-lazy columns
- **Severity:** Low
- **Where:**
  - `ui/screens/arcade/ArcadeScreens.kt:611-650` (`ArcadeBoard`: `rows.take(100)` in a `Column`, each row with a `ContactAvatar`, inside the `verticalScroll` at `:476`);
  - `ui/screens/KachatNamesLiveScreens.kt:1858-1863` (`activity.take(100)`).
- **Trigger:** you open a game's home or switch its period; you open the Usernames activity page.
- **What happens:** 100 rows, and 100 avatars with their identity lookups and image requests, are composed in the opening frame. User's view: a lag opening these screens. This matches iPhone L12.
- **Fix:** put these sections in a `LazyColumn` with keys (the game home is a single scrolling page, so it can become one).
- **Confidence:** Confirmed.

### S8 The chat threads read the keyboard inset in composition, so each keyboard animation frame recomposes the composer
- **Severity:** Low
- **Where:**
  - `ui/KaChatApp.kt:1763-1767` (`threadDockInset`: `WindowInsets.ime.getBottom(density)` read during composition);
  - used for `chat/{contactId}` and `group_chat/{groupId}` (`:1641` and in the `chat/` route) through `LocalDockInset`;
  - read by the 1:1 bottomBar at `ui/screens/Screens.kt:937` and by the group composer.
- **Trigger:** the keyboard opens or closes in a 1:1 or group chat.
- **What happens:**
  - Every frame of the IME animation (about 15-20 frames) recomposes the nav entry and every `LocalDockInset` reader: the whole composer (fee pill, voice row, text field, attachments).
  - It also changes the bottom bar's height each frame, so the Scaffold body is subcomposed again too.
  - User's view: possible stutter of the keyboard slide in chats on mid- and low-end phones.
- **Fix:** apply "dock minus keyboard" in the layout phase instead: a `Modifier.layout {}` or `windowInsetsPadding(WindowInsets(bottom = dock).exclude(WindowInsets.ime))` on the composer's column. Then no composable reads the IME inset per frame.
- **Confidence:** Plausible. The read in composition is confirmed; the visible stutter was not measured.

### S9 `iosShadow` allocates a Paint on every draw, and draws no shadow at all on Android 8
- **Severity:** Low
- **Where:**
  - `ui/theme/IosGlass.kt:22-29`: `drawBehind { Paint(...).apply { setShadowLayer(...) } }`;
  - used through `iosGlass` about 44 times (Swap 14, `Screens.kt` 11, Portfolio 6, the room cards and the chat composer field).
- **Trigger:** any redraw of a screen with glass cards: scrolling Swap or Portfolio, typing in the chat composer's glass field, list scrolls that re-record the parent's display list.
- **What happens:**
  - A new `android.graphics.Paint` is allocated per glass element per draw, which adds GC churn while scrolling.
  - On API 26-27 (minSdk 26), `setShadowLayer` is ignored for non-text draws on a hardware canvas, so the card's shadow is missing there.
- **Fix:** `drawWithCache { val paint = Paint().apply { setShadowLayer(...) }; onDrawBehind { ... } }`. On API < 28, fall back to `Modifier.shadow`.
- **Confidence:** Confirmed (allocation). The API 26-27 behaviour comes from the platform docs, not a device check.

### S10 Small first-frame and pull costs
- **Severity:** Low
- **Where and what:**
  - **The 1:1 chat:** `ui/screens/Screens.kt:1601-1638`. When the window arrives, the list composes at index 0 for a frame, as the code's own comment says, then `scrollToItem(last)` in a `LaunchedEffect`. During the open fade, the oldest rows of the window are composed and thrown away: their image and voice parses, plus decode jobs that start and are then cancelled.
    - Fix: `reverseLayout = true`, or create the list only once the first window is in, with `rememberLazyListState(initialFirstVisibleItemIndex = last)`.
  - **The Posts feed:** `ui/screens/kaposts/KaPostsScreens.kt:1701` reads `pullRefreshState.verticalOffset` in composition. Every frame of a pull-to-refresh drag recomposes the feed's Box scope, LazyColumn call included.
    - Fix: always compose the container and let it hide itself, or read the offset in a `graphicsLayer {}` or `derivedStateOf { offset > 0f }`.
- **Confidence:** Confirmed.

## Checked and fine
- **Typing:**
  - the 1:1 composer keeps the text as `State` and reads it only through `derivedStateOf`;
  - the group draft is read only in its field and a `snapshotFlow`;
  - the Posts reply box and composer pass state down, and their fee and link work is debounced.
- **Lists:** every `items`/`itemsIndexed` on the main screens has a stable key. These are all lazy:
  - Chats, 1:1, groups and rooms;
  - Posts feed, thread, profile and bookmarks;
  - Manage Addresses, Contacts, the bell (`GlobalNotificationSheet`, `AllNotifications`) and Cold Storage.
- **Chats list:** the conversation flow is coalesced and de-duplicated, previews are memoized per message, and rows skip (Kotlin 2.0.21 strong skipping; stable entities).
- **Posts cells:** per-row `collectSelectedAsState` slices, delayed thread probes, a once-per-row translation check, and the feed's "new posts" poll only while it is on screen.
- **Images:**
  - chat photos, group photos and backup avatars decode off the main thread and are LRU-cached;
  - avatars use a plain `AsyncImage` sized by its box (no `SubcomposeAsyncImage` in lists);
  - photo tiles have fixed placeholders.
- **Animations:** spinners, fee shimmer, the Explorer live dot, the BlockDAG and chess clocks are paced per frame or by delay, and stop in the background and in hidden Hub sections (`LocalHubSectionHidden`, `rememberScreenRunning`).
- **Kaspa Hub:**
  - hidden sections are composed but not measured or drawn, and their polls pause;
  - the Explorer's live feed stops when it is hidden;
  - a section pick waits for the menu's fade (iPhone T1 is ported).
- **Explorer:** stats no longer carry the DAA score, and the DAA ticks in its own composable.
- **Wallet flows:**
  - the Send flow's own-address list batches its used answers and waits for the step slide;
  - Cold Storage refreshes only balances on a quick return;
  - Swap's QR and the animated Cold Storage QR are made off the main thread.
- **Startup:**
  - services are `dagger.Lazy`, and the optional ones are built on Default or IO;
  - there is a broad baseline profile (`baseline-prof.txt`), with profileinstaller pulled in by Compose, and R8 on;
  - the remaining main-thread Keystore work (S13, including `SecureStores.accountStoresOpen` in `MainActivity.onCreate`) is the earlier audit's, left by choice.
- **Main-thread blocking:** `runBlocking` appears only in `KaChatFirebaseMessagingService`, on FCM's worker thread. SharedPreferences reads in composables are small plain prefs.


## K Android release audit, 2026-11-27: money and messaging correctness

Code: main d1ccb92b (K 5.4 build 7). Read-only. Four reviewers covered the areas below; the lead re-checked the two High findings (C1, C2) and the Medium A1 in the code.

IDs by area: A = KAS sends; B = tokens, Swap, card buy, Trestly, names, tips, Kasshi, Arcade; C = 1:1 messaging, push, accounts; D = groups, rooms, Posts.

**Counts:** Critical 0, High 2, Medium 4, Low 15.

**Verdict:** no loss-of-funds bug found. Fix before release: C1 and C2 (missed notifications and unread badges, most users see them). Should fix: A1, C3, D1, D2.

A shared cause behind A2, A3, A4, C4, C5, D1, D2, D3 and D4: code captures the account at the start but later re-reads `walletManager.getAddress()` / `getPrivateKeyBytes()` (or a DAO call that uses the active account) after a suspension point. One sweep that threads the captured owner (address and key) through `sendKasiaMessage`, `updateMessageStatus`/`getMessage`, the group handlers and the scheduled-post store closes all of them.

## Findings

### C1 A chat left open on screen silences that contact's notifications and files their messages as read while K is in the background
- **Severity:** High
- **Where:** `services/NotificationHelper.kt:369` (`if (activeContactId.value == contactId) return`), `:320` `isViewingContact`. The hold is set and released only by `ui/screens/Screens.kt:709-713` (`DisposableEffect` → `enterContact`/`leaveContact`). `KaChatApplication.kt:380-384` (`onStop`) clears `appForeground` but never the open chat. Groups have the same problem: `NotificationHelper.kt:548` (`activeGroupId`), `GroupRepository.kt:1690` (`markGroupRead` while `isViewingGroup`), `GroupChatScreens.kt:478`.
- **Trigger:** open a 1:1 chat with Alice, then press Home or lock the phone (the activity is only stopped, so the composition stays and `activeContactId` stays "Alice"). Alice then writes, pays or reacts.
- **What happens:**
  - The FCM DM, payment and handshake pushes are data-only, so the app draws them. `handleDirectMessage` (and the `"payment"`/`"handshake"` branch) call `notificationHelper.show(...)`, which returns at line 369. No banner at all.
  - The same message is ingested in the background (`notePushedMessage` → `processContextualMessage`, `ChatRepository.kt:3076`) with `isRead = isViewingContact(...) = true`, so no unread badge either.
  - The no-push background pass is silenced the same way.
  - This is the most common case: the person you were just talking to answers after you put the phone away, and you are never told. The same holds for an open group thread.
  - iOS doesn't have this: its extension draws the push whatever the app's state.
- **Fix:** treat a chat as "viewed" only while the app is in the foreground. Either clear `activeContactId`/`activeGroupId` in `onStop` and restore them in `onStart`, or make `show`/`showGroup`/`isViewingContact`/`isViewingGroup` also require `appForeground.value`. Re-mark the open chat as read in `onStart`.
- **Confidence:** Confirmed


### C2 A 1:1 message stored from a push in a process K didn't open is filed as read (no unread badge)
- **Severity:** High
- **Where:** `repository/ChatRepository.kt:1713` (`liveBaselineMs = Long.MAX_VALUE`), `:2178` `isBackfill`, `:3076` (`isRead = backfill || …`).
  - The baseline is loaded only in `runSync` (`:1878`), `syncConversationNow`, `rescanContactHistory` and the resync.
  - `ingestPushedMessages` (`:2003`, started by `notePushedMessage` from the FCM handler) runs `syncContextualMessages` without loading it.
- **Trigger:** K's process isn't running (swiped away, or reclaimed in the background, which is normal now that the battery exemption is only asked for without push). A contact sends a message.
  - FCM starts the process in the background.
  - The poll loop sees push active and pauses, so `runSync` never runs.
  - The push handler queues the message and ingests it.
- **What happens:**
  - `isBackfill(blockTime)` is `blockTime < Long.MAX_VALUE`, which is always true, so the row is inserted with `isRead = true`.
  - Every later push in that process is ingested the same way, plus any entry queued before the indexer API was ready.
  - The banner itself shows, because the push draws it. But when the user opens K from the launcher (not from the banner), the chat row and the Chats dock badge show no unread count for those messages. On the next sync they are already stored, so the badge never appears.
  - Payment notices and handshakes ingested on this path are filed read too.
  - Groups don't have this: `GroupRepository.kt:2358` reads the stored baseline each time.
- **Fix:** in `ingestPushedMessages` (or at the top of `syncContextualMessages`), set `liveBaselineMs = settingsRepository.liveNotificationBaseline(myAddress)` when it is still `Long.MAX_VALUE`. Better, make `isBackfill` read the per-account stored value, as groups do.
- **Confidence:** Confirmed. The very first push of a cold process may instead wait in the queue when `indexerApi` isn't set yet; the next push then ingests it as read.


### A1 The iOS C5 fix doesn't work on Android: a "Not confirmed" chat payment From a spending address never settles, and its notice is never sent
- **Severity:** Medium
- **Where:** services/ChatPaymentSender.kt:343-356 (stores `owedSpendingNotices`), :60-82 (waits for `chatRepository.settledSends`); repository/ChatRepository.kt:1318 `settleUnconfirmedSend`, whose only callers are :2149 (handshakes), :2810 (`fileOwnSend`, indexer messages), :3996 and :4316 (`syncPaymentPages`, which reads only `getTransactions(myAddress)`, the chatting address, :3874).
- **Trigger:** In a 1:1 chat, use Send KAS with From = a spending address (or pay a `chat=1` request From a spending address), and the submit's answer is lost (`SendOutcomeUnknownException`, for example a timeout on cellular).
- **What happens:** The bubble is stored under the txid as "warning" (Not confirmed), and the notice waits in memory for `settledSends`. On Android, that flow fires only when a transaction is read back from the chatting address's own history, or as an indexer message. This payment touches neither:
  - its inputs are on the spending address;
  - its change goes to a fresh quantum slot;
  - its only other output is the contact's address;
  - it carries no payload.

  iOS settles it from UTXO-change events on contact addresses (ChatService+UtxoProcessing.swift:204). Android has no UTXO subscription. Result:
  - the payee gets the KAS but never the chat bubble or memo (the iOS C5 symptom);
  - the payer's bubble reads "Not confirmed" for good, even though the payment landed.

  `owedSpendingNotices` is also lost on every process death, which is common on Android.
- **Fix:** After an unknown outcome from a spending source, ChatPaymentSender starts a bounded check of the txid, for example every 30 s for 15 min using `nodePoolManager.isTransactionKnown` or REST `getTransaction`. When the network has the transaction, it calls `chatRepository.settleUnconfirmedSend(txId, owner)`. Persist `owedSpendingNotices` the way `PaymentNoticeOutbox` persists notices, so they survive a restart.
- **Confidence:** Confirmed (no code path reads the spending-address transaction into a settle)


### C3 A Failed message is deleted silently when the same text was delivered in the last 48 hours
- **Severity:** Medium
- **Where:**
  - `repository/ChatRepository.kt:1357-1380` `repairStuckProvisionalMessages`;
  - `:1389-1410` `failPendingSendsFromBeforeThisRun`;
  - `MessageDao.kt:227-247` `hasDeliveredDuplicate`;
  - `PROVISIONAL_MATCH_WINDOW_MS = 48 h` (`:5012`).
  - `updateMessageStatus(…, "failed")` (`:1415`) schedules the repair to run 2 minutes after a failure.
- **Trigger:** the user sends "ok" (or "yes", "thanks", 👍…) and it is delivered. Within 48 h they send the same text to the same chat and that send fails (insufficient funds, network, node rejection), so it shows Failed with Retry.
- **What happens:**
  - About 2 minutes later the next sync's repair finds a delivered row with the same trimmed text within ±48 h. That row is the earlier, unrelated message. The repair deletes the Failed row as its "stuck twin".
  - The message the user meant to resend disappears with no trace, and the recipient never gets it.
  - The same happens at the next launch to a send left pending by a killed process (`failPendingSendsFromBeforeThisRun` deletes it instead of marking it Failed).
  - `matchProvisionalCandidates` also lets an own send from another device with that text take over a Failed row (`sameText.firstOrNull()` falls back to failed rows).
- **Fix:**
  - Accept a sibling only if it could be this send's own copy: its `blockTimestamp >= row.blockTimestamp - PROVISIONAL_CLOCK_SKEW_MS`, within a few minutes, not hours;
  - and it isn't already older than the placeholder;
  - and no other placeholder or sent row has claimed it.
  - Never collapse a row whose send failed with a definite (non-unknown) error.
- **Confidence:** Confirmed


### D1 A group catch-up that runs into Log Out, an account switch or Delete Account loses the old account's invites, epoch keys and messages
- **Severity:** Medium
- **Where:** `repository/GroupRepository.kt:2718-2810` (`syncAllGroups` captures `walletAddress` once), `:3243-3405` (`syncGroupMessages` / `syncGroupControlBySender` / `syncGroupControlByRecipient` advance that wallet's cursor after each page), `:1485` (`handleIncomingGroupMessage` reads `walletManager.getAddress()` per row), `:1954-1959` (`handleIncomingControlMessage` reads the current address and `getPrivateKeyBytes()` per row), `:3408` (`ingestRow` swallows the throw).
- **Trigger:** A catch-up is running (it starts on every foreground, on opening a group, on a pull, on a group push, and on block-stream reconnects; with several groups it is many indexer pages, so it takes seconds). During it the user taps Log Out, switches account (Log Out then sign in to B), or deletes the account. Nothing cancels the catch-up: it runs in `ProcessLifecycleOwner.lifecycleScope`, the FCM scope or a view model, and has no account check after its start.
- **What happens:**
  - While signed out, `getPrivateKeyBytes()` throws "Signed out", so every `gctl` row on the page (invites, roster changes, epoch roots) is logged as "failed to apply" and skipped. Then `advanceGroupSyncCursor(A)` moves A's cursor past them, and the deep-backfill mark is set.
  - After B signs in, A's `gcomm` rows are handled as B's. B has no matching group, so they are dropped ("no local group matched"), and A's cursor still advances. A's `gctl` rows fail to decrypt with B's key and are dropped the same way.
  - Cursors only move forward. When A comes back, a missed epoch root means every later message in that group is rejected as "no root for epoch". A missed invite (`gctl-recipient`) is never seen again. Only a manual per-group Refresh gets them back, and there is none for an unknown group.
  - Delete Account: the still-running walk writes A's cursors and deep-backfill marks back after `clearAllLocalData` removed them. A later re-import of the same seed then starts past A's group invites and never rediscovers those groups.
  - Narrower: a switch between the decrypt and `completeJoin` (each reads `getAddress()` itself) files A's group root under B, so A's group shows in B's Chats.
- **Fix:** Pass the captured `walletAddress` into `handleIncomingGroupMessage` / `handleIncomingControlMessage` / `completeJoin`. In each page loop, stop without advancing the cursor once `walletManager.isLoggedOut` or the active address is no longer `walletAddress`. Also check this before `advanceGroupSyncCursor` and `deepBackfilledGroupKeys.mark`.
- **Confidence:** Confirmed (code path); the timing is plausible in normal use.


### D2 Add or Remove Member during an account switch signs and pays the group's new keys with the other account
- **Severity:** Medium
- **Where:** `repository/GroupRepository.kt:819-901` (`rotateEpoch`), `:1415` (`sendSelfRootControlMessage` re-reads `getAddress()`), `:1454-1460` (`sendControlPayload` only compares the key with the one passed in).
- **Trigger:** Admin of a group in account A taps Add Member or Remove Member, then switches to account B while the first step (`syncGroupControlByRecipient`, a paged indexer read) is still running. On a slow network that can take several seconds.
- **What happens:**
  - `walletAddress` is A's (read at the start), but `privateKey = walletManager.getPrivateKeyBytes()` is read after the network fetch, so it is B's.
  - A's bag is rotated to a new epoch and A's roster saved. Then:
    - the self root goes to B's own pubkey and carries A's group seed;
    - every member gets a `gctl` epoch and root control signed by B, paid from B's chatting address;
    - the recipient x-only pubkey is in clear in `kchat:1:gctl:<recipient>:`.
  - `sendControlPayload`'s check passes because it compares B's key with B's key.
  - Members refuse the root (wrong admin), so nobody gets A's new epoch. A's later messages in that group can't be read by anyone until "Resend invites".
  - On chain, B's address now pays transactions to every member of A's group, which links the two accounts publicly.
- **Fix:** Read `privateKey` together with `walletAddress` at the top, before the sync, and call `refuseIfAccountChanged(walletAddress)` after the sync. Pass `walletAddress` into `sendSelfRootControlMessage` instead of re-reading it.
- **Confidence:** Confirmed


### A2 A Wallet send from the chatting address files its "Sent" row under whichever account is active when the send returns
- **Severity:** Low
- **Where:** viewmodels/WalletViewModel.kt:2107-2131 (`onSendClicked`) → repository/ChatRepository.kt:743-765 (`recordPlainSendFromChattingAddress` reads `walletManager.getAddress()` after the send).
- **Trigger:**
  1. Send Kaspa from the chatting address of account A.
  2. Close the screen mid-send (Cancel works mid-send).
  3. Switch to account B before the submit returns. The submit can take seconds: it waits for `sendMutex` behind a multi-part voice note or photo, or runs the lost-answer checks, up to about 20 s.
- **What happens:** When the send returns:
  - `watchPaymentDestination(dest)` adds A's payee as a contact in B's account;
  - the next check then finds that contact, and a "Sent X KAS" payment row is inserted in B's chat with them.

  B then shows a payment it never made, and A has no record in that chat. This is the KAS twin of iOS R5.
- **Fix:** Capture the owner (`getAddress()`) when `onSendClicked` starts, and pass it to `recordPlainSendFromChattingAddress`. Return early if it is no longer the active account.
- **Confidence:** Plausible (the path is confirmed; it needs a switch inside the submit window)


### A3 A deferred chat payment's bubble stays "Sending" for the rest of the process after an account switch
- **Severity:** Low
- **Where:** services/ChatPaymentSender.kt:417-430 (`scheduleRetry`: `chatRepository.getMessage(pendingId)` reads the *active* account, so after a switch it is null and returns), and :238-245 (the owner-changed refusal returns without marking the row). repository/ChatRepository.kt:1371-1386 fails leftovers only for rows from before this process started.
- **Trigger:**
  1. In account A, send a chat payment while the coins are unconfirmed, so it is deferred (Outcome.Deferred, retried for up to about 8 min).
  2. Switch to B before it goes out.
  3. Switch back to A.
- **What happens:** The retry timer drops the payment silently. A's bubble stays "pending" (spinner, no Failed, no explanation) until the next cold start. Android processes live long (push, battery exemption), so this can last days. The user believes the payment is still on its way. No money moves, and nothing is sent twice.
- **Fix:** In `scheduleRetry` and in the refusal branch, read the row with `owner` (`messageDao().getById(pendingId, owner)`) and set it to "failed" under `owner`, rather than returning.
- **Confidence:** Confirmed


### A4 Kept payment notices and notice sends are not bound to their account inside the send
- **Severity:** Low
- **Where:** services/PaymentNoticeOutbox.kt:162-172 (checks `currentKey() != key`, then awaits `getContact`, then calls `sendKasiaMessage`); services/ChatPaymentSender.kt:469-478 (same check, then the send); services/WalletService.kt:700-724 (`sendKasiaMessage` reads `getAddress()` and `getPrivateKeyBytes()` again, across suspension points).
- **Trigger:** An account switch lands between the account check and the point where `walletEngine.sendKaspa` binds its default from-address and key. That window covers a DB read and the encryption on Dispatchers.Default.
- **What happens:** A's `payment_notice` can be encrypted with one account's key and sent from B's chatting address. That links the two accounts on chain, which iOS R4 and `keep(owner=)` exist to prevent. The window is milliseconds, so this is rare.
- **Fix:** Give `sendKasiaMessage` an `owner` parameter. Have it throw "switched accounts" unless `getAddress() == owner` right before `walletEngine.sendKaspa`, and pass the owner's address and key explicitly.
- **Confidence:** Plausible


### A5 Paying a `chat=1` request From a spending address: the Review prices a different transaction than the one sent
- **Severity:** Low
- **Where:** ui/screens/Screens.kt:5965-5975 (`chatPaymentPayload` is priced whatever the From), with WalletViewModel.kt:884-893 (`spendableForEstimate` keeps unconfirmed and mature coinbase coins for a spending source). The send is ChatPaymentSender → `sendSpendingPayment` (KaspaWalletEngine.kt:651-670): no payload, `confirmedOnly`, `excludeCoinbase`, change always to a fresh P2SH slot.
- **Trigger:** Open a payment request link that has `chat=1`, choose From = a spending address, and swipe.
- **What happens:**
  - The Review's fee and Total include a payload the transaction never carries, so it shows more than is paid.
  - The Review omits the separate `payment_notice` fee that the chat sheet shows (`fromNote`).
  - If the address's largest coin is still unconfirmed, the estimate prices it, but the send uses confirmed coins only. More inputs push the fee past the 1,000-sompi margin, so the send is refused with "fee changed", and the re-estimate shows the same fee again until the coin confirms.
  - Max on such a request is priced without the payload, so a chat payment from the chatting address at Max is then refused by the fee estimate.
- **Fix:** In SpendingAddressSendFlow, price a chat-payment request the way ChatViewModel's payment quote does:
  - payload only when `sourceIndex == null`;
  - confirmed, non-coinbase coins only;
  - P2SH change for a spending source.

  Pass `payloadSize` into the Max too.
- **Confidence:** Confirmed (code); the fee-changed loop is Plausible and lasts only until the coin confirms


### A6 Cold Storage's "lead with the Not confirmed coin" never sees Cold Storage's own unknown outcomes
- **Severity:** Low
- **Where:** services/ColdStorageSendEngine.kt:660-663 calls `nodePoolManager.submitConfirmingKnown(signedTx, rest)`. That is the RawTransaction overload (NodePoolManager.kt:1306-1309), which passes `rememberUnknownOutcome = false`, so `UnknownOutcomeOutpoints` is never marked. `conflictingOutpoint` (:339-346) reads only those marks, which only kUSDT sends make. ColdStorageViewModel.kt:1315 also drops `pendingUnsignedTx` on an unknown outcome, so the same signed transaction can't be broadcast again.
- **Trigger:** A Cold Storage broadcast ends "Not confirmed". A coin larger than the spent one arrives at the address, or the person uses coin control. Then they build and sign a new send.
- **What happens:** The rebuild doesn't have to spend any coin the first transaction spent, so both can land and the person pays twice. With no new coin, largest-first already makes them conflict, which is why this is Low. The comments say this protection exists (iOS 3d126db), but on Android it is not wired for Cold Storage.
- **Fix:** Call the outpoint overload with `rememberUnknownOutcome = true` (pass `spent = signedTx.inputs.map { it.previousOutpoint }`). Optionally keep `pendingUnsignedTx` after an unknown outcome so the same signed transaction can be broadcast again, which is always safe.
- **Confidence:** Confirmed (code)


### A7 After a "Not confirmed" send, the automatic "already spent" retry can make a manual resend pay twice
- **Severity:** Low
- **Where:** services/KaspaWalletEngine.kt:503-529. On an "orphan" or "already spent" rejection, it retries once with freshly fetched, confirmed-only coins.
- **Trigger:**
  1. A payment P1 from the chatting address ends Not confirmed, but it actually sits in the mempool. Its inputs are released (by design, so a resend conflicts) and its pending change is not recorded.
  2. P1 had spent a pending-change coin C (blockDaaScore 0), as happens with back-to-back sends.
  3. The person resends the same payment despite the warning.
- **What happens:** The resend picks C and is rejected with "already spent" (P1 holds C). The fallback then drops every unconfirmed coin, builds from other confirmed coins, and the resend lands. P1 lands too, so the payee is paid twice. The "at most one of the two can land" guarantee holds only when P1's inputs were all confirmed coins.
- **Fix:** Do not run the confirmed-only fallback for a send that moves money (`amountSompi > 0`). Alternatively, skip it when the rejected input is one an unknown-outcome send of this app spent (track those outpoints as kUSDT does).
- **Confidence:** Plausible


### B1 A kUSDT tip on a post goes to whoever the indexer names as its author; a KAS tip checks the chain first
- **Severity:** Low
- **Where:** `ui/screens/kaposts/KaPostsScreens.kt:5412` (`KaPostTipFlow`, the KUSDT branch, then `KusdtTipSend`), compared with `services/kaposts/KaPostTipSender.kt:58` and `:106` (`authorMatches`, KAS only).
- **Trigger:** Tip In is set to kUSDT (or Ask Each Time, then kUSDT is picked). The person tips a post whose author the indexer misreports: a hostile or compromised KaPost indexer (the URL can be changed in Settings) gives a popular post its own `userPublicKey`.
- **What happens:**
  - `mapRemotePost` derives `posterAddress` from the indexer's pubkey (`KaPostsViewModel.kt:1997`).
  - A KAS tip refuses an address whose post transaction on chain has another signed author (`authorMatches`, which exists to stop exactly this relabelling).
  - The kUSDT branch prefills `TokenSendContent` with that address and never checks it, so the dollars go to the impostor.
  - The person sees the impostor's address on Review, under the display name the indexer gave it.
- **Fix:** before opening `KusdtTipSend` for a post tip, run the same `authorMatches(address, tip)` check (make it internal, or add a `KaPostTipSender.verifyAuthor`). On a mismatch, show the KAS path's failure text.
- **Confidence:** Confirmed (code path). It needs a lying indexer.


### C4 A send refused or failing after an account switch never gets its Failed mark
- **Severity:** Low
- **Where:**
  - `ChatRepository.updateMessageStatus` (`:1415`) and `getMessage` (`:1203`) use the account active at the time of the call;
  - their callers in `ChatViewModel.kt` include `sendMessageAwait`'s catch `:2841`, `sendMediaPartsAwait` `:3633-3636`, `retryFailedPartsInOrder` `:3712`, `retrySendMessage` `:3756` and `finalizeUnconfirmedSend` `:3778`;
  - `refuseIfAccountChanged` (`:3764`) throws after the switch.
- **Trigger:** start a long voice note, a multi-part photo, or a send that is still building, then switch accounts in Profile. ChatViewModel is activity-scoped and outlives the switch.
- **What happens:**
  - The send is correctly refused ("Not sent: you switched accounts"). But `updateMessageStatus(pendingId, "failed")` runs against account B, so it is a no-op, and A's rows stay "Sending…" with no Retry until the next cold start.
  - At that start `failPendingSendsFromBeforeThisRun` marks them Failed.
  - An unknown-outcome send is left as pending instead of "Not confirmed", so after the restart it gets a Retry that could post it twice.
- **Fix:** pass the owner captured at the start (`owner`/`myAddress`) into `updateMessageStatus`, `getMessage` and `finalizeProvisionalMessage` on these paths. The DAO already takes `walletAddress`.
- **Confidence:** Confirmed


### C5 A 1:1 send that spans the switch can be sealed by one account and paid for by the other
- **Severity:** Low
- **Where:**
  - `services/WalletService.kt:700-726` `sendKasiaMessage`: it reads `getContact`, the alias and `getPrivateKeyBytes()` at `:716`, then calls `sendKaspa`.
  - `sendKaspa` re-reads `walletManager.getAddress()`/`getPrivateKeyBytes()` as defaults (`KaspaWalletEngine.sendKaspa`, `fromAddress`/`signingPrivateKey`). Between the two reads sits `firstContactInboxTag`, which can make an indexer probe.
  - The same applies to `sendReaction` and `sendEdit`, which have no `refuseIfAccountChanged`, and to `PaymentNoticeOutbox.sendUnsentPaymentNotices`.
- **Trigger:** a send is past `refuseIfAccountChanged` and encrypted under A when Profile's switch runs logout → login(B) (one frame apart). `sendKaspa` then builds under B.
- **What happens:**
  - The payload (A's alias, sealed with A's key) is submitted from B's chatting address with B's coins.
  - The recipient's app fetches it under A's address, so it never arrives.
  - A's chat shows it as Sent.
  - On chain, B's address is linked to the recipient and to A's alias.
- **Fix:** capture address and key once in `sendKasiaMessage` and pass them to `sendKaspa(fromAddress=…, signingPrivateKey=…)`. Or check that the account is unchanged right before signing.
- **Confidence:** Plausible. The window is narrow: while signed out, `getPrivateKeyBytes` throws.


### C6 A payment or handshake push for the previous account rings as the current one
- **Severity:** Low
- **Where:** `services/KaChatFirebaseMessagingService.kt:351-381` (the `"payment"`/`"handshake"` branch) and `:957` `applyMessageRequestRule`. Neither checks that the push is for the active account. Only the 1:1 `"contextual"` path does (`NotForThisAccount`, `:873`).
- **Trigger:** switch from A to B. A's unregister (`unregisterAsync`) and B's register are network calls, so a push for A already in flight, or sent before the service rebinds the token, arrives while B is signed in.
- **What happens:**
  - A payment to A shows "<sender> - Sent you X KAS" under B. The title comes from B's contacts, and tapping it opens that sender's chat in B.
  - A handshake to A records "request notified" in B's store and shows "New message request" for B.
- **Fix:** have the push carry its recipient or watched address (most types already carry `address`/`sender`) and drop it when that isn't the active account's. Failing that, drop payment and handshake pushes received within a few seconds of an account switch.
- **Confidence:** Plausible. It depends on push-service timing.


### C7 "Mark as Unread" can flip another local account's row
- **Severity:** Low
- **Where:** `services/database/MessageDao.kt:314-322` `markLatestAsUnread`. The outer `UPDATE messages … WHERE id = (subquery)` matches on `id` only, but the primary key is `(id, walletAddress)` (`Models.kt:24`).
- **Trigger:** two accounts on this phone both hold a row with the same txid: a message between your own two accounts, or a payment transaction that paid both. Mark that chat Unread in one account.
- **What happens:** the other account's row with that txid is set unread as well. When it is a received row (a multi-output payment), the other account gains an unread badge nobody created.
- **Fix:** add `AND walletAddress = :walletAddress` to the outer `UPDATE`.
- **Confidence:** Confirmed (rare trigger)


### D3 An invite's "joined" answer can go out from the account switched to
- **Severity:** Low
- **Where:** `repository/GroupRepository.kt:2266-2283` (`publishGroupAnswers` status outbox), `:2317-2330` (`sendMemberStatus` reads `getAddress()` and `getPrivateKeyBytes()` itself).
- **Trigger:** Accept a group invite in A (or have owed answers sent by a catch-up) and switch to B while `publishGroupAnswers` is sending. Each accept send can take up to 4 tries with 1.8 s gaps.
- **What happens:**
  - The accept-marker sends are guarded: the key comparison throws, and the loop moves on.
  - `sendMemberStatus` is not guarded. It seals A's group's JOINED status under B's sender id and pubkey and sends it from B. `sendSelfStash(B)` passes.
  - A's group then drops the status from A's outbox. A's members never see A joined, and they reject the message, since B isn't in the roster.
  - B pays a fee, and group members can tie B's address to the group (the blinded id is derivable by members).
- **Fix:** Pass the `walletAddress` and `privateKey` that `publishGroupAnswers` captured into `sendMemberStatus`, and send through `sendSelfStash(walletAddress, …)`.
- **Confidence:** Confirmed


### D4 Cancelling a scheduled post while switching accounts can leave it scheduled, and the phone then posts it
- **Severity:** Low
- **Where:** `services/kaposts/KaPostsScheduledStore.kt:228-264` (`cancel`), `:153` (`persist` writes to `loadedWallet`), `:266-275` (`rememberCancel` uses the loaded wallet's key).
- **Trigger:** Tap Cancel on a scheduled post in account A, and switch to B while `cancelScheduledOnServer` is in flight. `activeAddressFlow` reloads the store to B.
- **What happens:**
  - `persist(_entries.value.filterNot { … })` filters B's list and writes it to B, so A's entry stays `scheduled` in A's prefs.
  - If the indexer call failed, `rememberCancel` files the id under B. B's flush signs the cancel with B's key, which the indexer refuses, so the id is retried on every run.
  - Back in A, `submitDue` finds the entry still scheduled. For a phone-only one, or an on-server one that turns local after `pullServerOutcomes`, it submits the post the user cancelled.
- **Fix:** Capture `loadedWallet` at the start of `cancel`. Remove the entry from that wallet's stored list (as `update(entry, wallet)` does), and file the pending cancel under that wallet's key.
- **Confidence:** Confirmed (code path); the window is narrow.


### D5 Group control sends retry after an unknown outcome
- **Severity:** Low
- **Where:** `repository/GroupRepository.kt:1462-1475` (`sendControlPayload` loop).
- **Trigger:** An invite, root, epoch, photo or accept control whose broadcast throws `SendOutcomeUnknownException`, for example a node timeout after submit.
- **What happens:** All 4 attempts treat it as a plain failure and send the same control again as a new transaction. Each copy costs a fee, and a group-photo control can be large. Receivers dedupe, so there is no visible duplicate. Every other send path in K stops on an unknown outcome.
- **Fix:** Rethrow `SendOutcomeUnknownException` like `GroupSendAccountChangedException`.
- **Confidence:** Confirmed


### D6 A group message killed mid-send comes back as Failed with Retry, though it may have landed
- **Severity:** Low
- **Where:** `repository/GroupRepository.kt:3086-3098` (`settlePendingSendsFromBeforeThisRun`).
- **Trigger:** The process is killed (or crashes) after `sendSelfStash` broadcast the message but before the row was swapped. On the next launch the catch-up hasn't seen the copy yet (indexer lag).
- **What happens:** The row is marked "failed" and shows Retry. A Retry re-seals it under a new msg_id, so the group gets the message twice and the user pays twice. Rooms settle the same case as "unconfirmed" with no Retry (`BroadcastRepository.settlePendingSendsFromBeforeThisRun`), and so does 1:1.
- **Fix:** Mark it "warning" (Not confirmed), as `sealAndSendGroupMessage` does for an unknown outcome. `deletePlaceholders` still replaces it when the copy comes in.
- **Confidence:** Plausible (it depends on indexer lag at launch)


## Checked and fine

### KAS sends (Wallet, swipe, chat payments, Cold Storage, requests)
- **Amount parsing:** `KaspaUnit.sompiFromUserText` uses exact BigDecimal, accepts "," as the decimal point and Arabic or Persian digits, and is capped at the supply. The keypad (`KaspaKeypad.applying`) caps decimals and digits. In fiat mode the conversion goes through `Locale.US` formatting. Max uses `setMaxSompi`/`setExactKas`, which is exact.
- **What's signed equals the Review:**
  - `expectedFeeSompi` is checked on the signed transaction (inputs − outputs, 1,000-sompi margin) for Wallet sends, withdrawals, chat payments, deferred retries and request payments.
  - `finalAmount` always equals the typed amount; only a handshake may go reduced.
  - Change that can't stand is refused rather than burned as fee.
- **Max:**
  - the chatting address uses confirmed, non-coinbase coins, as its send does;
  - it is capped at 80 inputs (largest first, the selector's order);
  - the extra is derived from Max's own base;
  - it is recomputed when the speed, custom fee or From changes.
- **Swipe-to-send:** `startSending` switches phase before `action()`, so one swipe means one send. A Not confirmed send shows Done only, with no Try Again. Payment bubbles never offer Retry (`shouldShowRetryOption` excludes TYPE_PAY). Retrying a "warning" message is refused.
- **UTXO double-spend:**
  - `sendMutex` covers fetch → select → sign → submit → record;
  - pending spent coins and chained change are reconciled;
  - `OutpointReservations` holds inputs during a submit;
  - unknown outcomes release coins so a resend conflicts;
  - covenant coins are excluded.
- **Unknown outcomes:**
  - `submitConfirmingKnown` looks up the locally computed txid after any error;
  - a lost answer gets 6 more checks, then counts as unknown;
  - a cancellation mid-submit counts as unknown, and a quantum source is marked spent;
  - an unknown-outcome notice is never resent (P6);
  - the REST fallback is used only for payload-less transport failures.
- **Accounts:**
  - ChatPaymentSender captures `owner` and refuses after a switch;
  - `withdrawFromSpendingAddress` writes the quantum pick, spent mark and primary move to `owner`;
  - `PaymentNoticeOutbox` keeps notices under their owner;
  - its R4 port (fixed key per run, `finishRun` re-reads the list, contact not loaded means the notice is kept) is correct apart from A4's window;
  - the owed-notice owner check is correct apart from A1.
- **Quantum rules:**
  - change never goes back onto a revealed key (a hot quantum source fails without a fresh slot, Cold Storage refuses change to the source);
  - fresh slots are checked on chain and held while in flight;
  - a slot is revealed when its send may have gone and given back when the send failed;
  - Cold Storage's quantum source spends `quantumSourceCoins` in its build, Max and Compound alike (T1 fixed);
  - a KCC-3 redeem must hash to the input's script;
  - only SIGHASH_ALL is broadcast;
  - Receive hands out regular addresses.
- **Cold Storage `broadcastSigned`:** input count, outpoints, output values and scripts are all checked against the unsigned transaction, and covenant records are refused. The fee rate is shared between preview and build.
- **Payment requests and deep links:**
  - an address must be on the active network;
  - the amount takes only digits and a dot and is parsed exactly;
  - the memo is cleaned;
  - a request signature that doesn't verify refuses the link;
  - a request only pre-fills Send, which still goes through the Review;
  - your own request opens the Own sheet;
  - with no account, the request is dismissed;
  - QR codes strip `?amount=`, so nothing sends silently.
- **Network checks:** sending to a kaspatest: address on mainnet (or the other way round) is refused in the engine and in chat payments.
- **Debug-only:** `SendFailureSimulation` runs only when `BuildConfig.DEBUG` is set and a system property is on.

### kUSDT, Swap, card buy, Trestly, .kachat names, tips, Kasshi, Arcade
- **kUSDT / Send Dollars** (`KusdtTransfer`, `TokenViewModel.send`, `TokenSendView`, `TokenChatPayments`):
  - **Amount:** `TokenAmount.unitsFromTyped` takes keypad text only (always "."; `KaspaKeypad.applying`) and Max (`TokenAmount.typed`, no grouping). It does checked math and refuses more decimals than the token has. Swap's path sanitizes first and refuses more than 6 decimals.
  - **What's signed:** the built outputs are compared with the requested states and values. The transaction's own outputs, the covenant id, the exact input set and the fee worked out from inputs minus outputs are all checked. The fee cap applies to the real fee. The key must match the holding's Schnorr owner.
  - **Review vs send:** a review older than 2 minutes is rebuilt and refused if it got dearer or changed (`changedSinceReview`). `matches` stops a build for an older From, To or amount.
  - **Unknown outcome:** Try Again resends the same signed transaction. Going Back clears the unknown flag, and a fresh build spends the "Not confirmed" coins first (`UnknownOutcomeOutpoints`, marked by `submitConfirmingKnown`, `rememberUnknownOutcome` = true), so at most one of the two lands.
  - **R5 (owner after a switch):** the owner is captured before submit and passed as `expectedOwner`. `recordTokenPayment` drops a late report after a switch, files the bubble under the owner, and keeps a failed notice under the owner. Once per txid (`insertOwnTokenBubble`'s `fresh`), so the detached watcher and Try Again can't duplicate it.
  - **Leaving mid-send:** the review's Back is blocked while sending. An account switch rebuilds the tabs through Log Out, so a send screen can't outlive its account.
  - **Quantum addresses:** a quantum From, To or Trestly target is refused (`schnorrOwner` is null; `secretHex` returns null for a quantum index).
- **Payment notice outbox (R4):** the account is fixed per run, the list is read again at the end and only settled txids are removed, a contact that isn't loaded keeps its notice, and an unknown outcome is dropped rather than resent.
- **Swap (ChangeNOW):**
  - the answer is checked (`answerMatches`);
  - the account is re-checked after the payout address and the create call;
  - history is filed under the account that started it;
  - "Send KAS from K" shows only while the status is new or waiting and nothing was sent (`offersDepositSend`), with both the sent and unknown outcomes recorded (`recordDepositSent`);
  - the deposit screen reads the live history entry, so the button disappears at once.
- **Swap (kUSDT through Trestly):**
  - the minimum is 1% under the quote;
  - a requote is asked again;
  - L3 is in: `Done.account`, so history goes to the starting account.
- **Trestly** (`TrestlyTrade`, `TrestlyProvider`):
  - the origin is pinned (main frame, https, host and port);
  - the key goes into one request and is wiped in `finally`;
  - a possible send holds the address for 10 minutes, stored in prefs so it survives a relaunch;
  - the hold lifts early only while this run still reserves the coins (the iPhone's Low is fixed);
  - spent inputs are reserved;
  - `secretHex` checks that the key matches the target (account switch).
- **Card buy:**
  - R6: `knownDollars` takes only finite amounts above 0 and up to $1,000,000, and the cap uses whole units;
  - C6 follows the owner's decision (not refused when the amount is unknown);
  - the permit's shape, domain, chain, spender, value and deadline are validated;
  - after approval, the key must still make the page's address.
- **.kachat names:**
  - the price and network fee are capped at the Review's plan (`maxPrice = plan.priceFee`, `maxNetworkFee`);
  - the swipe works only with a plan;
  - registration has a `maxPrice` in the record, and the driver checks it before register;
  - a second claim for the same name is refused;
  - the account is re-checked before the commit;
  - the claim is stored under the starting wallet;
  - the driver stops when `myAddress != pendingWallet` or on logout, and sleeps in the background.
- **Posts tips (KAS):**
  - the chain check on the author;
  - the expected fee is passed through;
  - instant tips are capped and gated (fresh price only, checked conversion, the Tips From address unreadable means nothing is sent);
  - `KaPostsTipHolds` stops a double tap and holds for 2 minutes after an unknown outcome.
- **Kasshi** (`KaChatSiteWallet`):
  - R3: `looksLikeKaPostAction` covers every signing shape in `KaPostsProtocol` (edit, delete, poll, pollvote, tip, schedule, cancel-schedule, follow/unfollow, base64 with mentions, 64-hex reply/vote/quote, bare unquote);
  - payment-request signatures use a different digest (SHA-256 with a domain tag), so they aren't at risk;
  - the R6 timestamp is range-checked with no `abs`;
  - the sign-in auto-sign takes only Kasshi's exact challenge, once, on the first page;
  - the send hold is keyed by account and site and lives outside the browser;
  - a lost answer reports 4002 with the txid;
  - `sendKaspa` takes only an address on the running network and whole sompi of at most 19 digits;
  - the key must match the connected address at sign time;
  - an account switch rebuilds the tabs, so a kept Hub browser can't send from the new account under the old connection.
- **Arcade stamps:**
  - the account is checked before and on every retry, and `expectedSender` is passed;
  - an unknown outcome gives no Retry (Unconfirmed);
  - Retry runs only from Failed.

### 1:1 messaging, push, multiple accounts
- **Account switch** (Profile chip = Log Out, then sign-in):
  - Signed out, `getPrivateKeyBytes` throws, so no send, decrypt or banner happens.
  - Banners are cleared at Log Out and Delete Account (`clearNotificationsForSignedOutAccount`).
  - ChatViewModel clears composer state, reply, edit and fee override on switch.
  - All chat-list flows go through `scopedToActiveAccount`.
  - Sync uses the captured account key (`accountKey` checks the key belongs to the address), checks the account per message, and leaves cursors untouched after a switch.
- **Delete Account (earlier P1):**
  - `isLoggedOut` is set before the record is removed, so the fallback account stays signed out: push registration is skipped, the poll loop waits, and no banners show.
  - Push is unregistered before the keys go.
  - `wipeAllLocalDataForAddress` covers messages, reactions, edits, cursors, contacts, tombstones, groups, requests, aliases, the pushed queue, notice bubbles, photo unlocks, the deleted log, notes, drafts and baselines.
  - The wipe resumes after a crash (`pendingAccountWipes`).
- **Retry:**
  - `claimFailedForRetry` is atomic, so a double tap can't post twice.
  - Not confirmed ("warning") rows have no Retry.
  - Media-part Retry is serialized (`retryingPartIds`).
  - Edits, reactions and sends check the owner before writing results.
- **Dedupe:**
  - Incoming rows use `insertIfAbsent`, so a racing fetch can't reset read state.
  - Own sends are matched to in-flight placeholders (Exact match for a live pending first).
  - `finalizeProvisionalMessage` merges into an imported row.
  - Banners are claimed per txid across push and poll; voice and photo parts ring once.
- **Edits:** the editor must be the target message's sender, and the target must be in the same chat. Newest by block time wins; an echoed own edit replaces the pending one.
- **Handshakes:**
  - An unresolved sender holds the cursor.
  - The tombstone check runs before the reopen.
  - Blocked senders are ignored.
  - Accept and Send Handshake refuse a double tap (in-flight sets, `Main.immediate`).
- **Push:**
  - Other-network pushes and pushes while signed out are silenced.
  - Own-device sends are filed quietly.
  - A DM not for this account isn't queued or rung.
  - Requests ring once; blocked senders ring nothing.
  - Registration follows the active-contacts watch list and re-registers on changes (debounced).
  - The token-to-wallet binding conflict is recovered.
- **Unread:**
  - The Chats badge excludes Message Requests and blocked chats.
  - Hidden placeholders and call messages are never unread.
  - Later media parts are not counted.
- **iOS R4 port** (`PaymentNoticeOutbox.sendUnsentPaymentNotices`): fixed owner key, re-reads the list at the end, keeps notices whose contact isn't loaded.
- **Room:** v46 migration matches the entity's indices. No destructive fallback past v14.

### Groups, public rooms, Posts
- Group send, edit, reaction and parts: the counter is reserved before sealing; `sendSelfStash` refuses after an account switch; Retry claims only "failed" rows, re-keys the placeholder in place and ignores a second tap; an unknown outcome becomes "warning" with no Retry.
- Incoming group messages:
  - sender proved (roster, or a former member at that epoch; senderId; signature);
  - replays dropped by `claimMessageId`;
  - txId dedupe, so a live scan, a catch-up and a push never notify twice;
  - backfill is silent and seeds the read marker only while it is null;
  - viewing the group keeps it read;
  - unread counts exclude outgoing and hidden members, and count voice/photo parts once.
- Catch-up concurrency (earlier S10): one mutex with one queued run; `forceRefreshGroup` only reads; cursors are written after ingest; one bad row no longer blocks a page.
- Delete Account (no catch-up running): bags, rows, cursors, deep marks, seen ids, invites, chain cursors, Posts held and scheduled stores.
- Rooms:
  - sends: unknown outcome becomes "unconfirmed" and is settled by the delivered copy; the no-coin deferred retry stops after an account switch or Log Out; Retry checks the sender and claims atomically;
  - the settle touches only this account's rows;
  - deletes for everyone count only from the message's own verified sender and never in the machinery rooms; the delete send is guarded against an account switch;
  - indexer pages are dropped after a switch (`stillWanted`), and Leave keeps shared history while another account is still joined.
- Posts:
  - every action carries `expectedOwner`, which is checked again at submit and in thread retries;
  - unknown outcomes are never retried; a double Retry tap is guarded (FAILED check); follow, vote and delete holds stop rapid taps;
  - countdowns, unsent cards and lists are dropped on an account switch, and held posts stay under their author's key;
  - scheduled posts: one mutex; outcomes go to the entry's own wallet; a pull during a switch is dropped; reminders are cancelled on Log Out; coins are reserved per loaded wallet.


## K Android release audit, 2026-11-27: crashes, data, release readiness (reviewer 5)

Code: main d1ccb92b (K 5.4 build 7, versionCode 149). Read-only; nothing built or run. Checks were done by reading the code, and with small scripts over `res/values*` (placeholders, `%` signs, missing keys), the R8 mapping of today's GitHub release build (`app/build/outputs/mapping/githubRelease/mapping.txt`, 20:23, the same minute as d1ccb92b), the ELF headers of the release native libraries and the zip layout of the release APK.

### H1 Trestly pool deposit and withdrawal crash the app as soon as the Approve sheet opens
- **Severity:** High
- **Where:** `ui/screens/tokens/TrestlyApprovalSheet.kt:116` (`else -> tr(R.string.kp_trestly_pool_note_kc)`), `util/AppText.kt:51`, `res/values*/strings_kapay.xml` `kp_trestly_pool_note_kc`
- **Trigger:** In the Trestly browser, a deposit to the pool or a withdrawal from it (`kusdt_addLiquidity` / `kusdt_removeLiquidity`, both in `TrestlyProvider.TRADE_METHODS`). Trestly's code prepares the trade, then `TrestlyTrade.run` calls `ask(TrestlyApproval(method, ...))`, so the sheet is composed with a method other than `kusdt_swap` and takes the `else` branch.
- **What happens:** `tr(id)` calls `Context.getString(id, *args)`, the vararg overload. It always runs `String.format`, even with no arguments. The string has a bare percent sign:
  - English "…never takes below 99% of this quote." parses `% o` as a format specifier and throws MissingFormatArgumentException.
  - German and Spanish ("99% dieses", "99% de esta") parse `% d` and throw the same.
  - Turkish ("%99'unun") throws UnknownFormatConversionException.
  - Arabic, Japanese and the others throw on the non-letter after `%`.

  This is 19 of 19 languages. The exception is thrown during composition, so K closes. The user sees K crash every time they try to add or remove liquidity. Nothing has been signed yet at that point, so no funds are at risk. `swap_native_note` also contains "1%", but it is read with `stringResource(id)`, which does not format, so it is safe. A script over every `%` in every locale found no other string with a bare `%` that reaches a formatting call. The existing `StringPlaceholderTest` only looks for iOS-style placeholders, so it doesn't catch this.
- **Fix:** In `tr`, skip formatting when there are no arguments: `if (args.isEmpty()) ctx.getString(id) else ctx.getString(id, *args)`. Do the same in `KaPlayText.string`, if it ever formats. Then extend `StringPlaceholderTest` to fail on any `%` that isn't a valid Java specifier or `%%`, in every locale.
- **Confidence:** Confirmed (call chain and Java Formatter behaviour; not run on a device)

### M1 Updating from 5.2.x straight to 5.4 deletes the user's Portfolios and Public Chats rooms
- **Severity:** Medium
- **Where:** `services/database/KaChatDatabase.kt:551-610` (MIGRATION_42_43, 43_44, 44_45), `di/AppModule.kt:70`
- **Trigger:** A phone still on 5.2.x (DB v42: tag 5.2.3 = version 42) installs 5.4 (v46). 5.3 was v44 and had both features removed; 5.4 brings them back.
- **What happens:** Room's only path from 42 is 42→43→44→45→46.
  - 42→43 drops `broadcast_channels`, `broadcast_messages` and `broadcast_hidden_senders`.
  - 43→44 drops `portfolios`, `portfolio_transactions` and `swap_transactions`.
  - 44→45 recreates the first five tables, empty.

  The user opens 5.4, which has Portfolio and Public Chats again, and finds their portfolios gone: manual ledger rows, notes and names. Joined rooms, hidden senders and cached room messages are gone too. A script comparing exported schemas 42 and 45 shows those five tables are identical (only `swap_transactions` is extra in 42), so nothing had to be dropped. Only Nextcloud Automatic Sync users can get their portfolios back. Users who already went through 5.3 lost this data then; this is about everyone who skipped 5.3 (slow Play rollouts, and GitHub APK users who update by hand).
- **Fix:** Register a `MIGRATION_42_45` that only runs `DROP TABLE IF EXISTS swap_transactions`. Room takes the longest registered jump from the current version, so 42→45→46 keeps the data. Optionally add a 43→45 that keeps `portfolios` and `portfolio_transactions`. Add a migration test: there is none today, no androidTest and no Robolectric (see L7).
- **Confidence:** Confirmed (schemas 42/45 compared; migration path read)

### M2 kachat.app links don't open K on Android: assetlinks.json has no fingerprints
- **Severity:** Medium
- **Where:** `AndroidManifest.xml` (the `autoVerify` intent filter for `https://kachat.app/u/ /pay/ /post/ /broadcast/ /hub/`); server `https://kachat.app/.well-known/assetlinks.json`
- **Trigger:** Tapping a kachat.app link that K itself shares (profile share, Request Payment, post share, room invite, Hub card link) on Android 12 or later.
- **What happens:** On 2026-11-27, `curl` of assetlinks.json returns `"sha256_cert_fingerprints": []`. Verification fails, so Android 12+ opens every such link in the browser, not K. The user lands on the web fallback page; payment requests and room invites don't open filled in. `PLAY_STORE_SUBMISSION.md` already lists this as an open item from 5.1. It is still open, and it needs two fingerprints, because the GitHub APK and the Play build are signed with different certificates (see L5).
- **Fix:** Publish both SHA-256 fingerprints: the Play app-signing certificate (Play Console > App integrity) and `kachat-release.keystore` (the GitHub APK). Then check the link with `adb shell pm verify-app-links --re-verify com.kachat.app`.
- **Confidence:** Confirmed (server answer; behaviour per Android App Links rules)

### M3 Posts screens and some common alerts are English in all 18 other languages
- **Severity:** Medium
- **Where:** `res/values/strings_kapost_ui.xml` (70 `kpui_*` keys missing from every locale), `strings_kaposts.xml` (`kaposts_couldnt_schedule_toast`, `kaposts_scheduled_for_on_phone_toast`, `kaposts_tip_didnt_send`, `pr_card_pay`), `strings.xml` `gif`; in every locale English is copied as the "translation" of `delete_chat_alert_message` and `pc_load_earlier_messages`.
- **Trigger:** Any non-English language, in Posts, the Chats list's Delete Chat alert, or a Public Chats room.
- **What happens:** These show in English:
  - the Posts feed's empty states ("Nothing trending yet", "Be the first - tap the pencil…");
  - the Follow and Repost buttons, "Show %d new posts", search, polls and the schedule sheet;
  - the composer's Markdown menu;
  - the notification lines ("liked your post", "followed you", …; `KaPostsScreens.kt:7145-7153`);
  - the permanent-delete warning for a chat ("This permanently deletes every message…", `ChatsScreen.kt:1134/1331`).

  The iPhone has all of these translated (for example, de "liked your post" = "gefällt dein Beitrag"). In total 201 keys are missing from each locale: these 76 plus 125 Explorer `ex_*` keys. Per the iPhone audit, the Explorer staying English is the owner's choice. Placeholders otherwise match across all locales (false positives were checked by hand).
- **Fix:** Port the iPhone translations for the `kpui_*`, `kaposts_*`, `pr_card_pay` and `gif` keys, and translate the two copied-English keys. Decide on the Explorer explicitly.
- **Confidence:** Confirmed

### L1 Gson-saved classes R8 renames: Portfolio and the Send screens read each other's price as 0
- **Severity:** Low
- **Where:** `repository/PortfolioRepository.kt:482-483, 572` (`StoredPricePoint`, `StoredPriceHistory`, `StoredCurrentPrice`), `repository/KasPriceRepository.kt:24`, `viewmodels/ColdStorageViewModel.kt:117` (`AddressRow`, saved as the address-list snapshot at `:465`, read at `:654`), `app/proguard-rules.pro`
- **Trigger:** Release builds. `proguard-rules.pro` keeps field names only for `KasPriceRepository$StoredCurrentPrice`, not for Portfolio's twin class that writes the same key (`kachat_current_price_<cur>` in `kachat_price_history_cache`). The release mapping shows `PortfolioRepository$StoredCurrentPrice` fields renamed to `a`/`b`/`c`, and `ColdStorageViewModel$AddressRow` renamed to `a`..`j`.
- **What happens:**
  - Portfolio writes `{"a":…,"b":…}`, and the Send screens' `KasPriceRepository.readPersisted` reads it as fetchedAt 0, price 0.0. The reverse also happens: Portfolio reading the Send screens' record gets 0. Each side first paints price 0: Portfolio's value shows 0 and the Send screens' fiat line disappears. It stays that way until a fetch succeeds, and CoinGecko's keyless tier often throttles. No send amount is affected: every conversion checks `price > 0`.
  - The Cold Storage snapshot and the price-history files only work while R8 keeps giving the fields the same short names. Today that holds because new fields were only appended, but nothing guarantees it. A shift would swap the meaning of saved fields, for example `hidden` and `hasHistory`.
- **Fix:** Add `-keepclassmembers` rules for `PortfolioRepository$StoredCurrentPrice`, `$StoredPriceHistory`, `$StoredPricePoint` and `ColdStorageViewModel$AddressRow`. Or keep whole packages: `com.kachat.app.repository.**` and `com.kachat.app.viewmodels.**` data classes.
- **Confidence:** Confirmed (mapping.txt)

### L2 Hard-coded English text
- **Severity:** Low
- **Where:**
  - `ui/screens/portfolio/PortfolioScreen.kt:728` ("No app available to share the CSV");
  - `viewmodels/BroadcastViewModel.kt:1430` ("Nextcloud upload failed — sending on-chain instead");
  - `ui/screens/Screens.kt:3270` ("Saves the photo to your gallery.");
  - `Screens.kt:3549` ("Chess - …");
  - `viewmodels/ChatViewModel.kt:248, 336, 2000-2010, 3554, 3827` (import / backup results, Create Group errors, "Audio file is empty", "Enter a valid amount.");
  - the `UserFacingError.message(e, "Failed to send")` fallbacks across the view models, and `util/UserFacingError.kt:49` ("The server returned an error (%d)…");
  - `CoinControl.kt:340` (iOS-identical by choice).
- **Trigger:** Any non-English language.
- **What happens:** English toasts and errors, the send-failure fallback included.
- **Fix:** Move them to string resources; translate the fallbacks `UserFacingError` returns.
- **Confidence:** Confirmed

### L3 Old names still visible to users
- **Severity:** Low
- **Where:**
  - "KaPost": `strings_arcade.xml:20-24` ("Posted / Posting / Shared to KaPost"), `kp_1f3254d6ce59` ("Share to KaPost"), `kpui_search_kapost` ("Search KaPost"), `strings.xml:1411-1413` ("A post on KaPost", "Tap to open it in KaPost.", "Shared a KaPost"), and the Posts notification title ("KaPost", `services/kaposts/KaPostsNotifier.kt:71`).
  - "KaChat": `strings.xml:527` ("Edit KaChat Profile"), `:1065` ("Shared a KaChat profile", a Chats list preview), `:1264` ("KaChat Names Indexer URL"), `:1410` ("…KaChat archive"), `:1442` ("KaChat Link"), `strings_linkcard.xml` ("KaChat Profile"), `strings_kachat_names.xml:52` ("…any KaChat chat"), and Explorer `ex_kind_*_about` ("KaChat message/chat", "KaPost").
  - Text that leaves the app: the room invite share text ("Join #room on KaChat.", `services/publicchats/PublicChatLink.kt:24`) and the gallery album saved photos go into (`Pictures/KaChat`, `util/ImagePrep.kt:298`).
  - The app name itself is correct: "K", and "K APK" for the GitHub flavour. No ".kachat endings" setting or text is left.
- **Trigger:** Normal use.
- **What happens:** Mixed branding.
- **Fix:** Rename to Posts / K, and decide on the album name.
- **Confidence:** Confirmed

### L4 Keyed lists from server data with no de-duplication can crash on a duplicate id
- **Severity:** Low
- **Where:** `ui/screens/Screens.kt:7820` (`itemsIndexed(txHistory, key = { tx.txId })`, fed by `AddressHistoryService.getTransactionHistoryResult`), `ui/screens/coldstorage/ColdStorageScreens.kt:2726` (same, from `ColdStorageAddressDiscovery`)
- **Trigger:** The Kaspa REST server, which the user can set in Settings, returns the same `transaction_id` twice for an address.
- **What happens:** Compose throws "Key … was already used" when the address history opens. iOS only logs a warning for the same case. api.kaspa.org is not known to do this; other lists (Posts engagement, notifications) already de-duplicate.
- **Fix:** `.distinctBy { it.txId }` in both `mapHistory` functions.
- **Confidence:** Plausible

### L5 Release setup drift
- **Severity:** Low
- **Where:** `app/build.gradle.kts:168-171`, `PLAY_STORE_SUBMISSION.md`, `app/build/outputs/bundle/playRelease/`, `app/lint-baseline.xml`
- **Trigger:** Release prep.
- **What happens:**
  1. The flavour comment says "Same package, same signing key… installing one over the other still updates in place". Not so: Play builds are signed with the upload key and then Play's app-signing key, the GitHub APK with `kachat-release.keystore`. Someone moving between the GitHub APK and Play must uninstall first, which loses local data. Users should be told.
  2. `PLAY_STORE_SUBMISSION.md` §4-§6 describes 5.1:
     - it lists permissions the manifest no longer has (contacts, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE_*`, `MODIFY_AUDIO_SETTINGS`) and leaves out NFC;
     - it describes the battery dialog as unexplained (it is now gated to no-push devices, with a rationale sheet);
     - its Data Safety hosts miss Trestly (api/testnet.trestly.io), kasshi.io, kaspa.stream, Kaspalytics, the chess and arcade servers, FxTwitter / unavatar (profile avatars) and the card-buy flow.

     The Play Console's Data Safety and permission answers should be checked against 5.4.
  3. The only AAB in `build/outputs` is from 2026-11-23, older than build 7. Run `bundlePlayRelease` again before uploading.
  4. `lint-baseline.xml`: 94 of its 962 entries point at files that no longer exist (for example `IncomingCallNotifier.kt`, the old `ui/screens/KaPostsScreens.kt` path). It hides nothing fatal: there is no `NewApi`, and `MissingTranslation` is a warning anyway. But it should be regenerated, so a new issue in a renamed file can't match an old entry.
- **Fix:** As listed.
- **Confidence:** Confirmed

### L6 Dead code in the APK
- **Severity:** Low
- **Where:**
  - about 30 functions nothing calls in main, for example `AddressActionsFloatingButton` (Screens.kt:4938), `rememberKachatHeldCount`, `ImagePrep.prepareForUpload`, `MessageProtocol.parseHandshakePayload`, `KachatNamesCodec.decode{Gap,Name,Offer}State`, `AppSettingsRepository.setKapostsFollowing`, `WalletService.generateAlias`, `BroadcastScanningService.myAddressOrNull`; some may be used only by tests;
  - 31 string keys nothing references (for example `shell_tab_kachat` ".kachat", `leave`, `broadcast_popular_retention_note`).
- **Trigger:** None. This is size and noise.
- **What happens:** Minor APK size; old text kept around to be translated.
- **Fix:** Delete them. `isShrinkResources` is off, which is right for now: `KaPlayText` looks strings up with `getIdentifier`, so turning it on needs a `tools:keep` for `kp_*`.
- **Confidence:** Confirmed

### L7 Important logic with no unit test
- **Severity:** Low
- **Where:** `app/src/test` (214 files)
- **Trigger:** None. These are gaps.
- **What happens:** Nothing tests:
  - Room migrations. There are no androidTest and no Robolectric, so M1 could not have been caught.
  - What R8 does to release builds: Gson field names and TypeTokens. Several past release-only crashes are recorded in `proguard-rules.pro`, and L1 is the same kind of bug.
  - `tr()` and formatting with `%` (H1).
  - `PaymentRequest.fromLink` / `KaChatLink.parse` (payment-request links, including their signature check).
  - `TrestlyProvider`'s origin and method gate.
  - `AccountFeatureCleanup` (Delete Account), `SecureStores` and `BackupRestoreCoordinator`.
  - `PortfolioRepository` persistence.
  - `GroupRepository` itself (the group crypto does have tests).
- **Fix:** In priority order:
  1. a Room `MigrationTestHelper` test over every version from 15 to 46;
  2. a release-variant test that round-trips each Gson-saved class (or a check against mapping.txt);
  3. the `%` resource test from H1;
  4. JVM tests for `PaymentRequest.fromLink`, after moving its `Uri.decode` and ICU calls behind seams.
- **Confidence:** Confirmed

## Checked and fine
- **Native libraries:** all four 64-bit release `.so` files (datastore, CameraX, ML Kit language id) have every LOAD segment 16 KB aligned. In the GitHub APK they are stored uncompressed at 16 KB-aligned offsets. The GitHub APK is arm64-only; the Play bundle has all four ABIs.
- **SDK levels and versions:** compileSdk and targetSdk 36, minSdk 26. versionCode 149 is above build 6's 148. `KACHAT_IS_RELEASE` is set per flavour. The release build refuses to fall back to the debug key, and no keystore, `keystore.properties` or `google-services.json` is tracked in git.
- **Manifest and Play policy:**
  - No foreground services, exact alarms, `QUERY_ALL_PACKAGES` or `USE_FULL_SCREEN_INTENT`.
  - Every permission is used (POST_NOTIFICATIONS is asked once; RECORD_AUDIO, CAMERA, NFC, VIBRATE, USE_BIOMETRIC). Hardware features are marked `required=false`.
  - The battery exemption is now asked only when push can't work, after a rationale sheet.
  - `allowBackup=false` plus data-extraction rules that exclude everything.
  - Every PendingIntent is IMMUTABLE; both runtime receivers are NOT_EXPORTED.
  - Predictive back is handled for sheets on API 33+.
- **Release build:** the Explorer's demo numbers and the send-failure simulation are DEBUG-only. Verbose HTTP logging is BASIC in release and gated by a setting. No test endpoints: the duckdns hosts are only old values being migrated away.
- **Process death and restore:**
  - Every `rememberSaveable` holds a primitive, enum, Uri or String, or has a Saver.
  - `getBackStackEntry` is wrapped in `runCatching`.
  - A link, notification or share isn't routed twice after a rebuild (intent payload stripped, `LAUNCHED_FROM_HISTORY` checked).
- **Startup:** each startup step is guarded on its own; a Keystore failure shows `SecureStoreProblemScreen`.
- **Data store safety:** DataStore has a corruption handler. Room has destructive fallback only from versions 1-14, and every step from 15 to 46 is registered; migration 45→46 matches schema 46's index.
- **Crash traps:**
  - Every `!!` was read: all sit behind a check just above.
  - `first()`/`last()` on collections: the risky ones are guarded.
  - `toInt`/`toLong` on untrusted text uses the `OrNull` forms or `runCatching`; Gson `asLong`/`asInt` on server data are inside `runCatching`.
  - Locale-sensitive `format` calls are display-only, or hex (not localized).
  - Link opening goes through the shell `UriHandler` with catches. Settings, camera and WebView intents are guarded; every `WebView(context)` is wrapped.
  - `ArrayDeque.removeFirst` is Kotlin's own, not the API 35 Java method, and `NewApi` is fatal in lint.
  - The uncaught-exception recorder passes the crash on to the default handler. Most singleton scopes use `logOnlyHandler`; the ones without it wrap their bodies in try/catch.
- **Strings:**
  - Placeholders match between English and every locale, the plural forms included (the mismatches the script reported were `%` signs in prose).
  - Every chess and arcade sentence looked up by its English text (`KaPlayText`, 260 literals) has a resource in all 19 languages.
  - No iOS placeholders, and no ".kachat endings" setting or text.
- **Privacy policy:** linked in the app (`home.kachat.app/privacy/` answers 200).
