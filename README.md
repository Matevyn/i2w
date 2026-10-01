# Watch Bridge

Two apps that talk to each other over Bluetooth LE: one puts a photo on your
watch, the other mirrors your watch's health data into Apple Health.

```
watch  ──photo──▶  phone          phone  ──health──▶  Apple Health
(Wear OS)         (iOS)                (iOS)          (HealthKit)
```

HealthKit has no Wear OS SDK, so health data cannot go straight from the watch
into Apple Health. It has to make the BLE hop and be written on the iPhone.
That is the whole reason this project exists.

## Two apps, two jobs

They are separate apps because they are separate jobs with opposite data
directions, different permissions, and different failure modes.

|  | `iphone/` | `watch/` |
|---|---|---|
| **Job** | Send photos out. Receive health in. Write it to Apple Health. | Receive and show photos. Read health. Send it out. |
| **BLE role** | Central — scans, connects, subscribes | Peripheral — advertises, serves |
| **Permissions** | Bluetooth, Photos, HealthKit | Bluetooth, Activity recognition, Samsung Health |
| **Fails when** | Watch is out of range | Samsung Health permission denied |

Splitting them means a failure in one does not take the other down, and neither
app asks for a permission for a feature it does not implement.

## Layout

| Path | What it is |
|---|---|
| `iphone/BridgeStatus.swift` | Connection state and transfer-time estimate. |
| `iphone/ContentView.swift` | BLE central, photo sending, the UI. |
| `iphone/HealthKitBridge.swift` | Receives health payloads, writes `HKHealthStore`. |
| `watch/.../presentation/MainActivity.kt` | BLE GATT server, photo receive, the UI. |
| `watch/.../health/` | Step sensor + Samsung Health reading, and the notify bridge. |
| `watch/.../tile/`, `.../complication/` | Watch face tile and complication. |

## Protocol

One service, three characteristics.

| UUID | Direction | Purpose |
|---|---|---|
| `12345678-1234-1234-1234-123456789abc` | — | service |
| `87654321-4321-4321-4321-cba987654321` | phone → watch | photo, write |
| `0f0e0d0c-0b0a-0908-0706-050405030201` | watch → phone | health, notify |

**Photos** — the phone writes `START:<byteCount>`, then JPEG chunks, then `END`.
Chunk size is clamped to the negotiated ATT MTU; writing more raises an
uncaught exception on some watches.

**Health** — the watch notifies JSON, split across notifications because one
payload cannot exceed the MTU. Each chunk carries `seq` and `last`; the phone
buffers until `last` arrives, then decodes once. Times are milliseconds since
the epoch.

## Working copies

`~/i2w` is the source of truth, but the work happens in the two project
folders you actually open. After any change, run:

```bash
~/i2w/sync-to-working-copies.sh
```

It copies both apps into the working copies, builds each one (including the
unit tests), and fails if any personal identifier reappears. A change that
lives only in `~/i2w` is incomplete.

| Path | Used by |
|---|---|
| `~/i2w` | source of truth, published to GitHub |
| `~/Documents/Iphone to watch` | Xcode |
| `~/AndroidStudioProjects/watchtoios` | Android Studio |

## Building

### Watch

The **Samsung Health Data API AAR is not in this repository.** It is Samsung's
proprietary SDK and its terms do not allow redistribution.

1. Download it from your Samsung developer account.
2. Place it at `watch/app/libs/samsung-health-data-api-1.1.0.aar`
3. `cd watch && ./gradlew assembleDebug`

Without it the build fails naming the missing file. If you only want steps,
delete the `implementation(files("libs/…"))` line from `app/build.gradle.kts`
and remove `SamsungHealthReader.kt` — steps come from the raw step sensor and
need no SDK.

### iPhone

```bash
cd iphone
xcodebuild -project "Iphone to watch.xcodeproj" -scheme "Iphone to watch" \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build
```

Signing is automatic with no development team configured, so set your own in
Xcode before running on a device.

## Data sources

| Metric | Source | Why |
|---|---|---|
| Steps | `SensorManager` step counter | Public API; only needs activity-recognition permission. |
| Heart rate | Samsung Health Data API | Samsung blocks **direct sensor** access on Galaxy watches, but the stored values are readable through the SDK. |
| Sleep | Samsung Health Data API | Same. Staged intervals from `SleepSession.getStages()`. |

No Samsung Partner account is needed: the AAR ships no partner key and access
is a runtime grant through Samsung Health's own consent sheet.

## Known gaps

- **Sync is manual.** BLE needs both apps running, so there is no background
  push. Automating it needs a foreground service and/or WorkManager.
- **Health is a snapshot, not a sync.** The watch sends whatever it has read
  since the last flush, with no dedupe, so a re-flush can duplicate samples in
  Apple Health.
- **A photo interrupted mid-transfer leaves the watch waiting** for chunks that
  never arrive; there is no receive timeout.