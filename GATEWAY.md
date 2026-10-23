# Dude Gateway: design and change report

Dude Gateway is this fork of Fossify Messages turned into an SMS/MMS gateway for one dedicated phone.
It reports every message the phone receives to the TheDude server, sends the replies the server queues
for this phone, and reports what happened to each send. It never decides what to say and never sends
anything that didn't come from the server's outbox. Fossify's own screens keep working so the operator
can see what the phone did.

The fork was identical to upstream `7fd846af` when this work started, so every path in the brief applied
as written.

## Where the code is

All new code is in `org.fossify.messages.gateway`:

| Package / file | What it does |
|---|---|
| `gateway/core/` | Android-free logic, unit-tested on the JVM: wire format (`Protocol.kt`), E.164 handles (`Handles.kt`), event ids, the pre-send recipient check, MMS `addr` parsing, the HTTP client (`GatewayApi.kt`), the SSE doorbell, backoff, the outbound state machine (`Outbound.kt`), the inbound uploader (`Inbound.kt`), the drain lock (`Drain.kt`), the heartbeat and thread-list senders, and the 404 feature gate |
| `gateway/data/` | The gateway's own Room database (`gateway.db`): `inbound_events`, `inbound_attachments`, `outbound_jobs`. Separate from `MessagesDatabase`, so Fossify's migrations are untouched |
| `Gateway.kt` | Process-wide wiring: one database, one HTTP client, one drain lock, shared by the service, the watchdog and the receivers |
| `GatewayHooks.kt` | The only entry points Fossify's receivers call. Each one is a single line in the Fossify file and never throws |
| `GatewaySender.kt` | Runs the pre-send checks and sends through `MessagingUtils`/`SmsSender` (SMS) or a `Transaction` (MMS) with the gateway's own `Settings` |
| `InboundEvents.kt` | Builds events and thread lists from the provider; `CatchUpScanner` |
| `ProviderReader.kt` | Read-only SMS/MMS provider queries, with no toasts |
| `GatewayService.kt` | The foreground service |
| `GatewayWatchdogWorker.kt` | The 15-minute WorkManager watchdog, and `GatewayStartupProvider` (starts the service when the app opens) |
| `GatewayActivity.kt` | The operator screen |
| `GatewaySettings.kt`, `GatewayStatus.kt`, `DeviceStatus.kt` | Configuration, the Keystore-backed `TokenVault`, persisted status, and the heartbeat's phone facts |

## Every change to Fossify's files, and why

| File | Change | Why |
|---|---|---|
| `gradle.properties` | `APP_ID=app.thedude.gateway` | Install as its own app |
| `app/build.gradle.kts` | `namespace` pinned to `org.fossify.messages`; OkHttp, okhttp-sse, libphonenumber and WorkManager added, plus JUnit and MockWebServer for tests; unit tests get OkHttp's JVM build | Upstream derived `namespace` from `APP_ID`, so changing the id would have moved `R` and `BuildConfig` out of the Kotlin package. For the test-only substitution, see "Tests" below |
| `gradle/libs.versions.toml` | Versions for the new dependencies | |
| `app/src/main/AndroidManifest.xml` | `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_REMOTE_MESSAGING`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; the service, operator activity and startup provider; `networkSecurityConfig`; app label; the launcher `activity-alias` names now start `app.thedude.gateway.activities.SplashActivity.*` | Fossify commons builds launcher-alias class names from the application id (`"$appId.activities.SplashActivity.Green"`, see `toggleAppIconColor`). With the new id the old names would never match, and icon-colour changes would only show error toasts |
| `res/values/strings.xml`, `res/values/donottranslate.xml`, `src/debug/res/values/strings.xml` | App name "Dude Gateway" | The launcher label is the new `gateway_app_name`. The 80 translations of `app_launcher_name` were left alone so upstream translation updates still merge, so the recents title in non-English locales still says "Messages" |
| `README.md` | Modified-version notice at the top | GPL-3.0 §5(a). `LICENSE` is unchanged |
| `receivers/SmsReceiver.kt` | `isMessageFilteredOut`, `isNumberBlocked` and `blockUnknownNumbers` are skipped while the gateway is on; after `insertNewSMS` returns, `GatewayHooks.onSmsStored` enqueues the event in the same background block | Every received message must be stored and reported |
| `receivers/MmsReceiver.kt` | `isAddressBlocked`/`isContentBlocked` return `false` while the gateway is on; `onMessageReceived` enqueues the stored MMS; `onError` counts a download failure | Same. The two parameters became `String?` because mmslib passes `null` when it can't parse the PDU; the old non-null signature would crash the receiver thread on that path |
| `receivers/SmsStatusSentReceiver.kt`, `MmsSentReceiver.kt`, `SmsStatusDeliveredReceiver.kt` | One line each, passing the result to the gateway when the intent carries `gateway_outbox_id` (with `EXTRA_ERROR_CODE`, or the MMS result code and `EXTRA_MMS_HTTP_STATUS`) | Results reach the outbox job |
| `receivers/RescheduleAlarmsReceiver.kt` | Starts the service on `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` | Start on boot |
| `messaging/SmsSender.kt` | Optional `gatewayOutboxId` parameter, put on the sent and delivered intents | Matching results to jobs. Other callers' behaviour is unchanged |
| `messaging/MessagingUtils.kt` | `insertSmsMessage` is now `internal` (was `private`); `Transaction` creation and send happen inside `synchronized(Transaction::class.java)` | The gateway files SMS replies into the exact thread. mmslib keeps `Settings` in a **static** field (`Transaction.settings`), so a Fossify UI send and a gateway send could otherwise swap settings mid-send |
| `activities/SettingsActivity.kt`, `res/layout/activity_settings.xml` | A "Dude Gateway" entry at the top. While the gateway is on, group MMS, delivery reports, blocked numbers and blocked keywords are hidden | The gateway overrides these settings |
| `app/detekt-baseline.xml` | One existing `ThrowsCount` entry updated to `SmsSender.sendMessage`'s new signature | Detekt keys baseline entries by signature |

`Messaging.kt` (`sendMessageCompat`, `getSendMessageSettings`) is untouched: the gateway doesn't go through it.

`MessagingUtils.sendMmsMessage` did **not** get a `gatewayOutboxId` parameter, though the brief listed it. It turns every exception into a toast, and the gateway needs to know when a send failed, so `GatewaySender` builds its own `Transaction` and attaches the id to the `MmsSentReceiver` intent itself.

## How it works

**Inbound.** Receivers enqueue the event as soon as the provider has the row. `CatchUpScanner` re-reads the provider on service start, on a `content://mms-sms/` change (3 s debounce), from the watchdog, and on "Sync now". It reads past the highest uploaded SMS/MMS ids, and also re-reads the last 48 hours, so a row stored just before a crash isn't skipped.

- The event id comes from the provider row (`sms:<_id>:<date>`, `mms:<_id>:<date*1000>`), so both paths produce the same id. Insertion is idempotent on that id.
- Messages dated before the gateway was switched on are never reported, so turning it on doesn't upload the phone's history or restored backups.
- Messages whose sender is one of the phone's own numbers are dropped, and only downloaded inbox MMS (M-Retrieve.conf) count.
- MMS sender and participants come from the `addr` rows: FROM, TO and CC, without own numbers.
- `InboundUploader` goes oldest first: it uploads each event's attachments, then the event, in batches of at most 100 events and 1 MB.
  - An event waiting for a retry holds back later ones, to keep order.
  - On `400` the batch is split and each event is sent once on its own, so only the rejected event is set aside. It shows on the operator screen, and "Sync now" puts it back in the queue.
  - Text is capped at 8,000 characters. The server ignores anything over 1,600 anyway, so semantics don't change, and one event stays far below 1 MB.

**Outbound.** `OutboundProcessor` keeps a row per outbox id in `outbound_jobs`, moving `received` → `checked` → `sending` → `sent`/`failed`, then `delivered`.

- The row is inserted before anything happens. It is marked `sending` before the provider or the radio is touched.
- An id handed out again while `sending` or later is never sent again: its known result is confirmed again, or the gateway keeps waiting.
- A multipart SMS settles when every part has reported, and is `sent` only if all parts are OK.
- Fossify asks for a delivery report on the last part only, so the first final report settles delivery. It is posted after the sent confirmation is accepted.
- Confirmations and delivery reports that fail on the network stay in the table and are retried (5 s to 15 min backoff) every 30 s by the service and by the watchdog.
- A `sending` job whose result never arrives is settled after 10 minutes from what the provider recorded. After 60 minutes with no answer it is confirmed as a permanent failure: this never sends anything again.

**Pre-send check (`RecipientCheck`)**, before every send:

- The thread must exist.
- Its stored addresses, normalised and minus the phone's own numbers, must equal `recipients` exactly.
- `recipients` must not contain the phone's own number or duplicates.
- `sms` needs exactly one recipient and `mms` two or more.

Otherwise the job fails permanently with a reason naming who is missing or extra.

- MMS goes as one group MMS to the thread's **stored** addresses. The provider then files it in that same thread (`PduPersister` builds the thread from the TO list).
- SMS is written with `insertSmsMessage(threadId = chat)` and sent multipart; it is never converted to MMS.

**Handles.** libphonenumber with the selected SIM's country as default region.

- A number libphonenumber considers a possible full number becomes E.164.
- A leading `00` is read as the international prefix even from a US SIM.
- Short codes and alphanumeric senders are kept as they are, minus separators. Emails are lower-cased.
- The operator screen shows the normalised form of the numbers the operator enters, so they can be checked against the handles the device was registered with.

**Doorbell and drains.** `Doorbell` uses okhttp-sse.

- The streaming client's 60 s read timeout is the silence detector: OkHttp hides the `: still here` comments, but every byte resets the timeout.
- Reconnects use 1–60 s backoff with jitter. A connection that stayed up for a minute resets it.
- When the network returns, a `ConnectivityManager` default-network callback reconnects at once.
- After three drops in a row without a healthy minute, the service runs `GET /outbox?wait=20` in a loop until a stream opens again.
- Each `ready` asks `DrainCoordinator` for a drain. The service and the watchdog share that coordinator, so only one drain runs, and a request during a drain makes it go round once more.
- A drain stops when the outbox is empty, or when the server hands out only ids already handled in that drain, so it can't spin.

**A judgement call to confirm with the server side.** When a job failed *transiently*, *no part went out*, and the server *accepted* that failure, a re-handout of the same id is the server's own retry ("up to six attempts"), so it is sent again. Without this the server's retries could never work. In every other case a re-handout re-confirms the known result. If the server gives retries a new id instead, this path is simply never taken.

**Foreground service type: `remoteMessaging`.**

- `dataSync` is capped at 6 hours a day on Android 15+, and Android 15 forbids starting it from `BOOT_COMPLETED`.
- `remoteMessaging` ("transfer text messages from one device to another") describes what the gateway does. It has no runtime limit and may start from `BOOT_COMPLETED`.
- `specialUse` would also work, but needs a justification string and gives nothing more for a sideloaded app.

If the app is distributed through Google Play, the declared use must match Play's policy for that type.

**Running continuously.**

- **When the service starts.** On boot and on package update, from `RescheduleAlarmsReceiver`. When the app opens, through a small `ContentProvider` (`GatewayStartupProvider`) that waits for the first activity to start, so `MainActivity` isn't touched. When the gateway is switched on.
- **Watchdog.** A periodic WorkManager job every 15 minutes, with a network constraint. It restarts the service, scans, uploads, drains once and retries confirmations.
- **Battery exemption.** Requested when the gateway is switched on. It is also what lets the watchdog restart a foreground service from the background on Android 12+. Without it the watchdog still does its own scan, upload and drain, and records why the service couldn't start.
- **Wake lock.** A partial wake lock is held only while a send is handed to the platform.
- **Default SMS role.** Losing it pauses sending (the outbox isn't even fetched). Reporting continues from the provider. The notification and operator screen show it, and getting the role back triggers a drain.

**401.** All traffic stops (`GatewayApi` refuses every call, and the doorbell stops). This holds across restarts until a new token is saved.

**404 on new routes** (heartbeat, thread list, attachments). The feature turns off and is retried an hour later. This is shown on the operator screen.

**Security.**

- Release builds accept only `https://` base URLs, and the main network security config forbids cleartext.
- The debug build's config allows plain HTTP to `localhost`/`127.0.0.1` only, for `adb reverse tcp:8070 tcp:8070`.
- The token is AES-GCM encrypted with a non-exportable Android Keystore key. It is never put in a URL, and `ServerConfig.toString()` redacts it.
- No `Origin` header is sent (the client also removes one explicitly).
- `GatewayLog` writes nothing in release builds. In debug builds it logs exception types only, never message text, numbers or the token. Fossify has no crash reporter.

## Tests

### What ran here

This container can't build the app: the network policy blocks `dl.google.com` (the Android SDK and Google Maven, which `maven.google.com` redirects to) and `jitpack.io` (`fossify.commons`, `mmslib`). So:

1. **Core unit and HTTP tests: 61 tests, 0 failures.** They run in a scratch JVM Gradle project that compiles `gateway/core` and `src/test` straight from this tree against the same library versions.
   - Pure tests: event-id stability; E.164 (national format, `00` from DE/FR and from a US SIM, short codes, alphanumeric senders, email); MMS sender and participants from `addr` rows; the recipient check (equal, extra person, missing person, own number in the thread, own number in recipients, wrong type for the count, missing thread, duplicates).
   - State machine: id handed out again while sending, `404` on confirm, `401` stopping everything and resuming after a new token, multipart, the server-retry rule, stale jobs.
   - MockWebServer tests: event batching (100 per batch and 1 MB), `500` retrying the same batch, `400` isolation, attachments PUT before their event (with `:` URL-encoded), doorbell `ready`, reconnect, fallback to long polling and back, `401`, reconnect on network return, drain until empty, drain stopping on repeated ids, `wait=20`, exact confirm bodies with no extra fields, explicit nulls in the heartbeat, and the 2.9 fallbacks for attachments, heartbeat and thread list.
2. **Typecheck of the Android layer.** The whole `gateway` package, plus the real modified `SmsSender.kt`, `MessagingUtils.kt` and all six modified receivers, compiles with `kotlinc` against the real Android 16 framework jar (Robolectric's `android-all`). androidx, Room, WorkManager, commons and mmslib were stubbed from their sources. `R` and the view binding were generated from this branch's XML, so resource ids and strings are checked too. Not covered: Room's KSP query validation, `SettingsActivity.kt`, and the real AGP/aapt2 build.
3. **Detekt with the project's `detekt.yml`** (including the Compose rules plugin): clean on the gateway package and tests, and on every modified Fossify file against `app/detekt-baseline.xml`.

The brief asked for the MockWebServer tests to be **instrumented**. They are local JVM tests (`app/src/test`, `./gradlew testFossDebugUnitTest`) because the code they exercise has no Android dependency. That runs the same code without a device, including in CI. OkHttp's Android build fails off-device, so `app/build.gradle.kts` gives the unit-test runtime classpath `okhttp-jvm` instead. I checked that the substitution resolves (okhttp, okhttp-sse and mockwebserver3 all land on `okhttp-jvm`) and that the 61 tests pass with it in the scratch project. It has **not** been run inside AGP, where the configuration name could differ; the block matches both `…UnitTestRuntimeClasspath` and `…HostTestRuntimeClasspath`.

### Still to do on a real build

- `./gradlew assembleFossDebug testFossDebugUnitTest detekt lint`. This is the first real AGP/KSP build of this code.
  - `androidx.work` is pinned to `2.10.1` without being able to check Google Maven from here.
  - Room will write `app/schemas/org.fossify.messages.gateway.data.GatewayDatabase/1.json`; commit it.
- An instrumented test for `RoomOutboundStore.update` under concurrent result callbacks. The JVM tests cover the state machine with an in-memory store, not Room's transactions.

### Real device: not run

No phone was available to this session, so none of these have been tried. Each needs a real run, recording what actually happened:

| Case | Result |
|---|---|
| 1:1 SMS in and out | not run |
| Group MMS in and out with 3+ people, including an iPhone user, landing as **one** message in the same thread | not run |
| A photo sent to the group (attachment upload) | not run |
| A reply over 160 characters (multipart SMS; MMS for groups) | not run |
| Emoji | not run |
| Reboot | not run |
| Airplane mode during a send (expect a `permanent:false` failure, then the server's retry) | not run |
| App force-stopped, then a message arrives. Force-stop also cancels the app's WorkManager jobs until it is next opened. Record whether the message reaches the phone's provider at all, and whether the catch-up scan reports it once the app is opened | not run |
| `adb shell am kill` while a reply is queued (watchdog brings it back and sends within 15 min) | not run |
| Someone added to the group: note the new `thread_id` | not run |
| Default-SMS role taken away | not run |

The operator screen's "Last error" shows a message when a group MMS reply is filed in a different thread than the one it was addressed to. Watch for it during the group tests.

### End to end with the server: not run

1. In the TheDude repo: `make up`, then `adb reverse tcp:8070 tcp:8070`.
2. `make device ARGS="add 'Test Android' android +1XXXXXXXXXX"` with the phone's own number. Copy the `hd_` token it prints.
3. Install the **debug** build (only debug allows `http://localhost:8070`). Make it the default SMS app. In Settings → Dude Gateway, enter `http://localhost:8070`, paste the token, enter the phone's own number exactly as registered (check the "Handles" line), pick the SIM, Save, and switch the gateway on. Allow the battery exemption.
4. Sign in at http://localhost:8081, make a group and get its code.
5. In a group MMS with the Dude phone, send `Hey Dude code: XXXX-XXXX`. Dude's introduction should arrive in that same thread.
6. Send "Hey Dude, add milk to the list". Expect a reply in the thread and the item on the board.

## Known limitations

- `Transaction.settings` in mmslib is static. Sends are serialised around it, but anything else in the process that reads it during a send (mmslib's own download path) still sees the gateway's settings.
- An MMS's provider `date` is rewritten right after the row is inserted. A catch-up scan landing exactly between the two writes would build a second id for that message. The content-observer path waits 3 s, so only a scan by coincidence could hit this window.
- While the gateway is on, Fossify still hides conversations with blocked numbers from its list (`getConversations`). Those messages are still stored and reported. Unblock numbers before relying on the operator view.
- The operator screen's status lines are English only.
