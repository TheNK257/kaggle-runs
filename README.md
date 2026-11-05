# Kaggle Runs

An Android home-screen widget that shows your Kaggle notebook runs: which ones are **running right now**,
how long they've been running (a live-ticking timer), and the status of recent runs.

```
┌──────────────────────────────────────────────┐
│ Kaggle runs              Updated 1:42 pm   ⟳ │
│ ● my-training-run          2:46:28   Running │
│ ● feature-engineering-v2  12:35 am  Complete │
│ ● baseline-model      4 Nov, 8:59 pm   Error │
└──────────────────────────────────────────────┘
```

- Status for each run: Queued, Running, Complete, Error or Cancelled
- The running time ticks live, with no polling needed
- Refreshes every 15 minutes (Android's minimum for background work). Tap ⟳ to refresh now.
- Tap a notebook to open it on kaggle.com. Tap the title to open settings.
- Scrollable list that fits any widget size
- No server or third-party service: the phone talks only to `https://www.kaggle.com/api`

> **Limitation:** only committed runs (*Save Version* / *Save & Run All*) are visible. The Kaggle API
> doesn't expose interactive editor sessions.

## Install

1. Download `KaggleRuns.apk` from [Releases](../../releases) (or build it yourself, below) and open it on
   your phone. Allow "Install unknown apps" for your browser or file manager when asked.
2. Create a Kaggle API token: kaggle.com → **Settings → API → Create New Token** (starts with `KGAT_`).
3. Open **Kaggle Runs**, paste the token, tap **Save and test**.
4. Long-press the home screen → **Widgets** → **Kaggle Runs**.

## Security

Your API token is never part of the app or this repository. You enter it on the phone at runtime.

| Threat | Protection |
|---|---|
| Decompiling the APK | Nothing to find: the token isn't compiled in. Release builds are also obfuscated with R8. |
| Reading the app's storage (root, backup, file copy) | The token is encrypted with AES-256-GCM using a key in the **Android Keystore**. The key is generated inside the phone's security chip (StrongBox where available, otherwise the TEE) and can never be exported. Stolen ciphertext can't be decrypted on any other device. |
| Cloud backup / phone-to-phone transfer | Disabled (`allowBackup=false` plus data-extraction rules). |
| Debugger / `adb run-as` on a USB-connected phone | Release builds are **not debuggable**, so no one can attach to the app to use its Keystore key. |
| Network sniffing | HTTPS only. Cleartext traffic is blocked by the network security config. |

What it can't protect against: a fully compromised (rooted, malware-infected) phone that runs code
*as the app itself*. If you think your token is exposed, revoke it on kaggle.com → Settings → API.

## Build

Requirements: JDK 17+ and the Android SDK (platform 36). Android Studio has both.

```bash
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk (debuggable, for development)
./gradlew assembleRelease    # app/build/outputs/apk/release/app-release.apk
```

To sign release builds, copy `keystore.properties.example` to `keystore.properties` and point it at your
own keystore. Both are git-ignored. Without it, `assembleRelease` produces an unsigned APK.

Install over USB: `adb install -r app/build/outputs/apk/release/app-release.apk`

> Debug and release builds are signed with different keys, so switching between them requires
> uninstalling first, which also clears the saved token.

## How it works

| File | Role |
|---|---|
| `KaggleApi.kt` | `GET /api/v1/kernels/list?group=profile&sortBy=dateRun` for your notebooks and run start times, then `GET /api/v1/kernels/status` for the 8 most recent, in parallel. Bearer auth. |
| `RefreshWorker.kt` | WorkManager job: fetches, caches the snapshot, redraws the widget. Periodic (15 min) plus on demand. |
| `KaggleWidgetProvider.kt` | Widget rendering with `RemoteViews`. A `Chronometer` handles live run time; the list uses `RemoteCollectionItems`. |
| `TokenStore.kt` | Encrypts the token with an Android Keystore AES-GCM key. |
| `SettingsActivity.kt` | Token entry and verification. |
| `OpenNotebookActivity.kt` | Invisible step that opens a tapped notebook (Android 14+ forbids mutable implicit PendingIntents). |
