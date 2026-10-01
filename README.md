# Scrobot

An Android app that lets Claude Code, running in Termux on the same phone, control the screen.
It needs no adb, Wi-Fi, root, Tasker or AutoInput. The app uses Android's **Accessibility**
permission and exposes a small **HTTP API on `127.0.0.1:8765`**. Requests need an `X-Token` header.

- Package: `com.mahmoud.scrobot`. minSdk 26, targetSdk 36 (Android 16). Kotlin, no libraries.
- No Google Play services, no analytics. The only network use is the loopback server.

## Layout

| Path | What |
|---|---|
| `app/src/main/java/com/mahmoud/scrobot/` | `HandsService` (accessibility service, owns the server), `Api` (endpoints), `Nodes` (window/node search), `HttpServer` + `Router` (hand-written HTTP server, token check), `MainActivity`, `KeepAliveService` (foreground notification), `StopReceiver`, `Prefs`, `ApiLog` |
| `app/src/main/res/xml/accessibility_service_config.xml` | gestures, window content, screenshots, `flagReportViewIds|flagRetrieveInteractiveWindows|flagIncludeNotImportantViews`, all event types |
| `client/hands` | Bash client for Termux / proot Ubuntu |
| `keystore/scrobot-release.jks.gpg` | The release signing key, encrypted (AES-256). The password is only in the `KEYSTORE_PASSWORD` GitHub secret. Every future version must be signed with this key to install over the old one. |
| `tools/build-offline.sh` | Builds the signed APK without the Android Gradle Plugin (this is how the release APK was made) |
| `tools/jvmtest/`, `tools/test-client.sh` | Runs the real `HttpServer` + `Router` on a JVM with stub endpoints and tests `hands` against it |

## Download the latest APK

Every push runs `.github/workflows/build-apk.yml`. It runs the client tests, builds and signs the APK,
and publishes a GitHub Release `v1.0.<run>` containing `scrobot.apk` and `hands`. The newest APK always has this link
(sign in to GitHub in the phone's browser first, because the repo is private):

https://github.com/mahmoudsamy1610/Scrobot/releases/latest/download/scrobot.apk

The versionCode goes up with every run, so each APK installs over the previous one.
CI signs with the encrypted key in `keystore/`, unlocked by the repository secret `KEYSTORE_PASSWORD`
(Settings → Secrets and variables → Actions). Without that secret the build stops before signing.

## Building

- **Signing key:** decrypt it once with `gpg -d keystore/scrobot-release.jks.gpg > keystore/scrobot-release.jks`
  (the password is the `KEYSTORE_PASSWORD` secret). Then write `keystore/keystore.properties` with `storeFile=scrobot-release.jks`,
  `storePassword=…`, `keyAlias=claudehands`, `keyPassword=…` (same password). Both files are git-ignored. Alternatively,
  `KEYSTORE_PASSWORD=… tools/build-offline.sh` decrypts the key on the fly.
- **Android Studio / SDK:** `./gradlew assembleRelease`. The output is `app/build/outputs/apk/release/app-release.apk`.
- **No Android SDK:** `sudo apt-get install aapt apksigner zipalign dalvik-exchange` (Ubuntu 24.04), then
  `tools/build-offline.sh`. The output is `app/build/outputs/apk/release/scrobot-1.0.apk`.
- Client tests: `(cd tools/jvmtest && gradle installDist) && tools/test-client.sh`

When you release a new version, raise `versionCode`/`versionName` in `app/build.gradle.kts` and in `tools/build-offline.sh`.

## API

Every request needs the header `X-Token: <token>`. Without it, or with a wrong token, the app answers **401**.
Send a JSON body with POST. Query parameters also work for simple values (`GET /screen?all=1`).
Each reply is `{"ok":true,...}`, or `{"ok":false,"error":"..."}` with a non-200 status:
400 bad input, 404 nothing matched, 408 `/wait` timed out, 500 the action failed.

| Endpoint | Body | Result |
|---|---|---|
| `GET /ping` | – | `{"ok":true,"version":"1.0"}` |
| `GET /screen` | `?all=1`, `?format=text` | `windows` (id, title, package, type, layer, active, bounds) and `nodes`: `text desc hint id class package bounds center clickable editable focused checked enabled window window_id`. By default only visible nodes that have text, desc, hint or id. Reads **every window** (both halves of split screen, dialogs, keyboard). |
| `/tap` | `{"x":540,"y":1200}` | 60 ms tap. Waits for the gesture to finish. |
| `/longpress` | `{"x":..,"y":..,"ms":800}` | long press |
| `/swipe` | `{"x1":..,"y1":..,"x2":..,"y2":..,"ms":300}` | swipe |
| `/click` | `{"text":"Send"}` / `{"id":"com.whatsapp:id/send"}` / `{"desc":"Search"}` / `{"hint":..}`, optional `index`, `exact`, `package` | Finds a visible node in any window. Text matching is case-insensitive; it is a "contains" match unless `exact` is set. `text` matches text, desc or hint. `id` may be just `send`. The app sends `ACTION_CLICK` to the node or its nearest clickable ancestor. If there is none, it taps the node's center. Returns `method`, `matched`, `clicked`. |
| `/type` | `{"text":"Hello 👋 مرحبا"}`, optional `append`, `enter`, and field selectors `field` (text/desc/hint), `id`, `desc`, `hint`, `package`, `index`, `exact` | Picks the field: by selector, else the focused field, else the first editable field. If the selector matches something that isn't editable (such as WhatsApp's search bar), the app clicks it and uses the field that gets focus. Focuses the field, then `ACTION_SET_TEXT`. If the text doesn't stick, it falls back to clipboard + select all + `ACTION_PASTE`. Returns `method` (`set_text`/`paste`) and `verified`. |
| `/key` | `{"key":"back"}` | `back home recents notifications quick_settings lock_screen screenshot split_screen power enter` (`enter` = IME action on the focused field) |
| `/open` | `{"package":"com.whatsapp"}` or `{"uri":"https://..."}` (+ optional `package`, `adjacent:true` to open in the other split half) | launches with `FLAG_ACTIVITY_NEW_TASK` |
| `/wait` | `{"text":"Chats","timeout":8000}` (or `id`/`desc`, `"gone":true`) | Polls until the node appears or disappears. Returns the node, or a 408 timeout. |
| `/screenshot` | optional `format` (`png`/`jpg`), `quality`, `scale` (0–1), `raw=1` | `{"png":"<base64>","width":..,"height":..}`. With `raw=1` the reply is the image bytes. Needs Android 11+. |
| `/clipboard` | `{"text":".."}` sets it; `{}` reads it | Android lets only the focused app or keyboard read the clipboard, so reading may return `null`. |

```bash
T=$(cat ~/.config/scrobot/token)
curl -s -H "X-Token: $T" 127.0.0.1:8765/ping
curl -s -H "X-Token: $T" -d '{"text":"Send"}' 127.0.0.1:8765/click
```

## The `hands` client

```
hands ping                  hands screen [all]          hands screen-json [all]
hands tap X Y               hands longpress X Y [ms]    hands swipe X1 Y1 X2 Y2 [ms]
hands click "Send" [i]      hands click-exact "Chats"   hands click-id ID   hands click-desc "Search"
hands type "text" [field]   hands append "text" [field] hands key back
hands open PACKAGE|URL      hands wait "text" [ms]      hands wait-gone "text" [ms]
hands shot out.png          hands clip ["text"]         hands raw /endpoint '{"json":1}'
hands set-token TOKEN
```

`hands screen` prints a header for each window, then one line per node:

```
## window 0: WhatsApp (com.whatsapp)  application  bounds 0,1186,1080,2340  active
540,2201  com.whatsapp:id/send  desc:Send  [WhatsApp]  {click}
```

In split screen, set `HANDS_PKG=com.whatsapp` to limit `click`/`type`/`wait` to one app.
The exit status is 0 when the app replied `"ok":true`.

The client looks for the token in this order: `$HANDS_TOKEN`, then `~/.config/scrobot/token`
(written by `hands set-token`), then `/sdcard/Android/data/com.mahmoud.scrobot/files/token.txt`.
The app writes that last file, but **Android 11 and later block other apps (Termux included)
from reading `Android/data`**, so plan on `hands set-token`.

## Install on the phone (Galaxy A56, One UI 8.5)

1. **Install the APK.** Open `scrobot-1.0.apk` from My Files or Chrome. If asked, allow
   *Install unknown apps* for that app: Settings → Apps → (My Files/Chrome) → Install unknown apps.
   If Samsung **Auto Blocker** refuses the install, turn it off for the moment:
   Settings → Security and privacy → Auto Blocker.
2. **Allow restricted settings.** Android blocks the Accessibility switch for sideloaded apps.
   Open Settings → Accessibility → Installed apps → Scrobot and try to turn it on. You will see
   "Restricted setting". Then open Settings → Apps → Scrobot (or the app's **Open App info**
   button) → ⋮ (top right) → **Allow restricted settings**, and confirm with your PIN or fingerprint.
3. **Turn on the service.** Settings → Accessibility → Installed apps → **Scrobot** → On → Allow.
   The app should now show *Service: ON*, *API: listening on http://127.0.0.1:8765*.
4. **Allow notifications** when the app asks. This shows the "Scrobot is running" notification.
5. **Battery.** In the app tap **Ignore battery optimisations** → Allow. On Samsung, also open
   Settings → Battery → Background usage limits and make sure Scrobot is not in *Sleeping* or
   *Deep sleeping* apps. You can add it to *Never auto sleeping apps*.
6. **Install `hands` in Termux.** Copy `hands` to Downloads, then in Termux:
   ```bash
   termux-setup-storage                       # once, to reach /sdcard
   cp /sdcard/Download/hands $PREFIX/bin/hands && chmod +x $PREFIX/bin/hands
   hands set-token PASTE_TOKEN_FROM_APP       # Copy button in the app
   hands ping
   ```
   Inside proot Ubuntu, use `cp /sdcard/Download/hands /usr/local/bin/hands && chmod +x /usr/local/bin/hands`
   and run `hands set-token ...` there too, because proot has its own `$HOME`.
   It needs only `bash` and `curl` (`pkg install curl` / `apt install curl`).

The notification's **Stop** button shuts the API down until you open the app again.
