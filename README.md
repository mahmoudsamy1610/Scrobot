# Scrobot

An Android app that lets Claude Code, running in Termux on the same phone, control the screen —
and that runs imported scripts on its own.
It needs no adb, Wi-Fi, root, Tasker or AutoInput. The app uses Android's **Accessibility**
permission and exposes a small **HTTP API on `127.0.0.1:8765`**. Requests need an `X-Token` header.

- Package: `com.mahmoud.scrobot`. minSdk 26, targetSdk 36 (Android 16). Kotlin, no libraries.
- No Google Play services, no analytics. The only network use is the loopback server.

## Layout

| Path | What |
|---|---|
| `app/src/main/java/com/mahmoud/scrobot/` | `HandsService` (accessibility service, owns everything below), `Api` (endpoints), `Nodes` (window/node search), `HttpServer` + `Router` (hand-written HTTP server, token check), `MainActivity`, `KeepAliveService` (foreground notification), `StopReceiver`, `Prefs`, `ApiLog` |
| …`ScriptStore` `ScriptParser` `ActionInterpreter` `ScriptRunner` `JsRunner` `ShellRunner` | version 2: imported scripts — the scripts folder, the action language, its interpreter, and the JavaScript and shell engines |
| …`ApiServer` `Status` `CrashLog` `AccessibilityList` `AccessibilityRepair` `UpdateReceiver` | version 2.2: staying alive and telling the truth about it — see **When it says ON but does nothing** |
| …`Trigger` `TriggerEngine` `TriggerStore` `Triggers` `SystemReceiver` `BootReceiver` | version 3: triggers — the rules, when they fire, where they are kept, and the events that set them off |
| `examples/` | scripts to import and try |
| `app/src/main/res/xml/accessibility_service_config.xml` | gestures, window content, screenshots, `flagReportViewIds|flagRetrieveInteractiveWindows|flagIncludeNotImportantViews`, all event types |
| `client/hands` | Bash client for Termux / proot Ubuntu |
| `keystore/scrobot-release.jks.gpg` | The release signing key, encrypted (AES-256). The password is only in the `KEYSTORE_PASSWORD` GitHub secret. Every future version must be signed with this key to install over the old one. |
| `tools/build-offline.sh` | Builds the signed APK without the Android Gradle Plugin (this is how the release APK was made) |
| `tools/desugar/`, `tools/check-desugar.sh` | Rewrites `invokedynamic` lambdas into plain classes, and proves the rewrite is faithful. See **Lambdas** below. |
| `tools/jvmtest/`, `tools/test-client.sh` | Runs the real `HttpServer`, `Router`, `ScriptParser`, `ActionInterpreter`, `ShellBridge` and `JsBridge` on a JVM: 294 checks of the script language, both script engines, the trigger rules and schedules, the status and crash-log behaviour, server restarts and the `hands` client. `scrobot-jvmtest parse FILE` checks a script without a phone, and `scrobot-jvmtest js FILE` prints the program a `.js` script really runs. |

## Download the latest APK

Every push runs `.github/workflows/build-apk.yml`. It runs the client tests, builds and signs the APK,
and publishes a GitHub Release `v1.0.<run>` containing `scrobot.apk` and `hands`. The newest APK always has this link
(sign in to GitHub in the phone's browser first, because the repo is private):

https://github.com/mahmoudsamy1610/Scrobot/releases/latest/download/scrobot.apk

The versionCode goes up with every run, so each APK installs over the previous one.
CI signs with the encrypted key in `keystore/`, unlocked by the repository secret `KEYSTORE_PASSWORD`
(Settings → Secrets and variables → Actions). Without that secret the build stops before signing.

## When it says ON but does nothing

If an accessibility service crashes, Android puts it on a "crashed" list, leaves the Settings
switch ON, shows *Not working*, and will not reconnect it until the user turns it off and on again.
Version 2.2 is about never getting into that state, and being honest when it happens anyway.

- **Nothing crashes the process.** Every script run, every bridge and watcher thread and every
  request is wrapped, and an uncaught-exception handler logs anything that still escapes. Only the
  main thread is allowed to be fatal; a background thread simply ends, and the service lives on.
  A badly written script can fail only itself.
- **The server outlives the service.** The HTTP server belongs to `ApiServer`, not to the
  accessibility service, so when accessibility goes away `/ping` still answers — with
  `"service": false` and a `reason` — instead of leaving Termux with a refused connection. A
  supervisor thread restarts the server if it dies and retries with a growing delay if the port is
  busy; `SO_REUSEADDR` means a restart can take the port straight back.
- **The screen tells the truth.** *Switched on in Settings* and *actually connected* are shown as
  the different things they are. If accessibility is ON but not connected, the main screen shows a
  red warning and a button that opens Accessibility settings, the status notification changes to
  "Scrobot needs attention", and after about a minute a notification says so too.
- **After an update**, `UpdateReceiver` brings the API back and re-arms that warning, because an
  update unbinds the service and Android does not always bind it again.
- **Problem log.** Crashes, server errors and connect/disconnect events go to a file inside the
  app, with **View / share log** on the main screen. No adb needed.
- **Repair accessibility** appears only if the app holds `WRITE_SECURE_SETTINGS`, which only you
  can grant, once:
  `adb shell pm grant com.mahmoud.scrobot android.permission.WRITE_SECURE_SETTINGS`.
  It switches Scrobot's own service off and on. It never runs by itself.
- **Battery**: the foreground service returns `START_STICKY`, and the main screen shows whether
  battery optimisation is excluded, with a button and the reminder to add Scrobot to Samsung's
  *Never auto sleeping apps*.

## Lambdas: why this build has its own desugaring step

Android cannot run `invokedynamic` lambdas (`LambdaMetafactory`), so every Android build rewrites
them into ordinary classes. Google's D8 does that, but it is published only on Google's Maven,
which this build environment cannot reach, and `dx` — the dexer that can be installed here —
silently passes `invokedynamic` through. Version 2.0 shipped that way and crashed the moment a
script ran:

```
java.lang.NoSuchMethodError: No static method metafactory(...) in class LambdaMetafactory
  at kotlin.io.FilesKt__FileReadWriteKt.readLines(FileReadWrite.kt:273)
```

The app's own code was already free of such call sites (the Kotlin compiler runs with
`-Xlambdas=class -Xsam-conversions=class`), but `kotlin-stdlib` ships 77 of them.

`tools/desugar` now rewrites each one into a small generated class, the way D8 does.
`tools/check-desugar.sh` runs the same 55 standard-library calls against the original library and
the rewritten one and compares the results, and the build fails if any jar still has a lambda call
site or if `classes.dex` mentions `LambdaMetafactory`. All three run in CI.

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
| `GET /ping` | – | `{"ok":true,"version":"2.0"}` |
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
| `/find` | the same selectors as `/click` | `{"found":true,"node":{...}}`. Never fails when nothing matches. |
| `/focus` | `{"direction":"next"}` — `next previous up down left right first last`, or a selector | Moves input focus, which is what pressing Tab does. Walks the focusable nodes in reading order; the directional names pick the nearest node that way. |
| `/scroll` | `{"direction":"forward"}` — `forward backward up down left right`, optional selector | Scrolls the biggest scrollable list on screen (or the one you select). Falls back to a swipe if nothing reports itself scrollable. |
| `/sleep` | `{"ms":500}` | Waits inside the app, for scripts. |
| `GET /scripts` | – | The imported scripts and the folder they live in. |
| `/script/run` | `{"name":"login.scro"}` or `{"source":"tap 1 2","kind":"actions"}`, optional `args` (strings), `timeout` (ms), `background`, `format:"text"` | Runs a script. Waits for it unless `background`. Returns the output, how long it took, and `status`. One script runs at a time; a second one gets 409. |
| `/script/status` | optional `format:"text"` | The running (or last) script and its output so far. |
| `/script/stop` | – | Stops the running script. |
| `/script/import` | `{"name":"login.scro","source":"..."}` | Saves a script. |
| `/script/check` | `{"name":...}` or `{"source":...}` | Parses an action or JSON script without running it, and names the first mistake. |
| `/script/read`, `/script/delete` | `{"name":...}` | Read or delete a script. |
| `GET /triggers` | – | Every trigger, what it does, when it last ran and when it runs next. |
| `/trigger/add` | one trigger as JSON | Adds it, or replaces the one with the same name. |
| `/trigger/import` | `{"source":"<triggers.json>"}` | Replaces the whole list. |
| `/trigger/enable` | `{"name":...,"enabled":false}` | Switches one on or off. |
| `/trigger/fire` | `{"name":...}` | Runs its script now, to try it. |
| `/trigger/delete` | `{"name":...}` | Deletes one. |

```bash
T=$(cat ~/.config/scrobot/token)
curl -s -H "X-Token: $T" 127.0.0.1:8765/ping
curl -s -H "X-Token: $T" -d '{"text":"Send"}' 127.0.0.1:8765/click
```

## Scripts

Version 2 imports and runs scripts. Nothing is written by hand on the phone: import a file from the
app (**Import script…**) or with `hands import file`, then run it from the app's **Run** button, with
`hands run`, or through `/script/run`. Scripts live in
`/sdcard/Android/data/com.mahmoud.scrobot/files/scripts` and the kind is decided by the extension.

Scripts run with the app's own permissions, one at a time, with a 2-minute limit by default
(`timeout`). **Stop** in the app, `hands stop` and the notification's Stop button all end a run.

### `.scro` — action scripts

One command per line, `#` comments, `"quoted arguments"` with `\n` and `\"` escapes, and `$1`…`$9`
for the arguments passed to the run. Anything the API can do, a line can do.

```
open com.whatsapp
wait "Chats" 10000
click "Ask Meta AI or Search"
type "$1"
sleep 800

if-missing "$1"
  echo "no chat called $1"
  stop
end

click "$1"
type "$2" "Type a message"
click-id com.whatsapp:id/send
```

| Commands | |
|---|---|
| screen | `tap X Y` · `longpress X Y [ms]` · `swipe X1 Y1 X2 Y2 [ms]` · `click "TEXT" [index]` · `click-id ID` · `click-desc "DESC"` · `type "TEXT" ["FIELD"]` · `append "TEXT" ["FIELD"]` |
| moving about | `key back` · `open com.whatsapp` · `open https://…` · `focus next` · `scroll forward` · `wait "TEXT" [ms]` · `wait-gone "TEXT" [ms]` · `sleep MS` |
| output | `screen [all]` · `shot name.png` · `clip "TEXT"` · `echo "..."` |
| control | `repeat N … end` · `if-found "TEXT" … else … end` · `if-missing "TEXT" … end` · `set name "value"` · `stop` |

Any command also takes `key=value` extras that go straight into the request
(`click text="Send" exact=true index=1`), and `/endpoint key=value` calls an endpoint by name, so
nothing in the API is out of reach. `hands check NAME` parses a script and names the first mistake
without running it.

### `.json` — the same steps as JSON

```json
{ "steps": [
  { "action": "open", "package": "com.whatsapp" },
  { "action": "wait", "text": "Chats", "timeout": 8000 },
  { "action": "tap", "x": 540, "y": 1200 }
] }
```

### `.js` — JavaScript

Runs in an off-screen WebView with a `scrobot` object; no network access is involved. `async`/`await`
works, `console.log` goes to the run's output, and throwing fails the run.

```js
const nodes = scrobot.screen();                       // every node, as objects
scrobot.log('nodes: ' + nodes.length);
if (scrobot.find('Send').found) scrobot.click('Send');
scrobot.type('Hello 👋', 'Message');
scrobot.sleep(500);
```

`scrobot.call(endpoint, args)` reaches any endpoint; `scrobot.args` holds the run's arguments. The
shorthands are `screen find tap longpress swipe click type append key open wait waitGone focus
scroll shot clipboard sleep log stopped`.

### `.sh` — shell

Android's shell is **mksh**, not bash, and it has no `curl` or `wget`. So scripts get a `scrobot`
shell function that talks to the app directly (through small files, no network tool needed):

```sh
scrobot shot "screen.png"
scrobot screen > "$SCROBOT_SHOTS/screen.txt"       # one node per line
scrobot click "Send" || echo "no Send button"      # exit status follows the API
scrobot type "Hello" field="Message"
scrobot --json /tap '{"x":540,"y":1200}'
scrobot log "a line for the app's log"
```

`$SCROBOT_SHOTS`, `$SCROBOT_SCRIPTS`, `$SCROBOT_TOKEN` and `$SCROBOT_URL` are set, `$1`… are the
run's arguments, stdout and stderr become the run's output, and a non-zero exit fails the run.
Android does not let an app execute files it wrote, so the script is read by `sh` rather than
executed, and `scrobot` is a shell function: it is not inherited by a `sh -c` sub-shell.

## Triggers

A trigger runs a script by itself. They are kept in `triggers.json` next to the scripts, and are
added the way everything else is: imported from the app, or sent through the API.

| Kind | Fires | Needs |
|---|---|---|
| `app` | an app comes to the front | `match`: the package, e.g. `com.whatsapp`; optional `window` to narrow it to a screen title |
| `text` | something appears on screen | `match`: the text (case-insensitive, anywhere); optional `package` |
| `schedule` | on the clock | `every`: minutes, or `at`: `"08:30"` |
| `screen` | the screen or lock | `match`: `on`, `off` or `unlocked` |
| `power` | the charger | `match`: `charging` or `unplugged` |
| `boot` | after the phone starts | – |

```json
{ "triggers": [
  { "name": "whatsapp-opened", "kind": "app", "match": "com.whatsapp",
    "script": "greet.scro", "cooldown": 60 },
  { "name": "morning", "kind": "schedule", "at": "08:30", "script": "brief.js" },
  { "name": "battery-low", "kind": "text", "match": "Battery low",
    "script": "save.sh", "args": ["now"], "cooldown": 600 }
] }
```

Every trigger also takes `args` (passed to the script as `$1`…), `enabled`, and `cooldown` in
seconds. `examples/triggers.json` has one of each kind.

```
hands triggers                       hands trigger-add '<json>'|FILE
hands trigger-import triggers.json   hands trigger-rm NAME
hands trigger-on NAME                hands trigger-off NAME
hands trigger-fire NAME              # run its script now, to try it
```

The app's screen lists them with an **On/Off** button each, and ⋯ to try or delete one.

**Rules that keep triggers from running away:**

- Nothing fires while a script is running, so a script cannot set itself off again.
- Each trigger waits out its `cooldown` before it may run a second time.
- `app` and `text` fire on the way *in* — when the app comes to the front, or the text first
  appears — not over and over while it stays there.
- A daily time only counts while the app is running: if the phone was asleep at 08:30, that day's
  run is skipped rather than going off at lunchtime.
- Adding a trigger whose script is not imported is refused, instead of failing silently later.
- Times are checked by the foreground service's heartbeat, about every 20 seconds, so a schedule
  can be a few seconds late — and later if Android has stopped the app.
- `text` triggers read the whole screen, so they are throttled to about once every 1.5 seconds,
  and only while such a trigger exists and is switched on.

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
6. **Try a script.** Copy a file from `examples/` to the phone, tap **Import script…** in the app,
   then **Run**. `examples/tab-through.scro` is safe to try anywhere. `examples/triggers.json`
   goes in through **Import triggers…**, once the scripts it names are imported.
7. **Install `hands` in Termux.** Copy `hands` to Downloads, then in Termux:
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
