# Kaggle Runs

An Android app with two home-screen widgets for Kaggle: which notebooks are **running right now** and how
long they took, which run you submitted and what it scored, and where you stand on the leaderboard.

```
┌─ Kaggle runs ──────────── Updated 5:59 pm ⟳ ┐   ┌─ My Competition ──── Updated 5:59 pm ⟳ ┐
│ ● my-training-run                   Running  │   │ #1,234  of 4,567   ▲ 85 · 24h           │
│   2:46:28 · since 10:56 am                   │   │ Best 0.812 · Top 0.905                  │
│ ● submit-blend-v3                  Complete  │   │ Last: submit-blend-v3 · 0.812           │
│   4 Nov, 7:49 pm · took 5m 22s     📤 0.812  │   │ 3 of 5 left today · 69 days left        │
└──────────────────────────────────────────────┘   └─────────────────────────────────────────┘
```

**Runs widget:** status (Queued / Running / Complete / Error / Cancelled), a live-ticking timer while running,
how long finished runs took, and a 📤 badge with the score when that notebook was submitted.

**Competition widget:** your public-leaderboard rank and its change over 24 h, best and top score, your latest
submission, submissions left today and time to the deadline. Long-press it to choose which competition it follows.

**The app** (for those who'd rather not have a widget):
- **Runs:** every recent run with its start time, duration, failure message and submission.
- **Competitions:** rank, scores, rank and best-score history graphs (tap for values, or switch to a table), and every
  submission with the notebook it came from.
- **Settings:** API token, refresh frequency (15 min, 30 min, 1 h, 3 h or manual), notifications.

**Notifications** when a run finishes, fails or is cancelled ("✅ my-training-run finished · Took 6h 01m"), and
when a submission is scored ("📤 submit-blend-v3 scored 0.821 · rank #1,234 → #1,150").

No server or third-party service: the phone talks only to `https://www.kaggle.com/api`.

> **Limitations**
> - Only committed runs (*Save Version* / *Save & Run All*) are visible. The Kaggle API doesn't expose
>   interactive editor sessions.
> - Background checks can't run more often than every 15 minutes (an Android rule), so notifications arrive
>   within your refresh interval. The live timer and pull-to-refresh don't depend on it.
> - Kaggle only reports your *current* rank, so the history graphs build up from the day you install the app.

## Install

1. Download `KaggleRuns.apk` from [Releases](../../releases) (or build it yourself, below) and open it on
   your phone. Allow "Install unknown apps" for your browser or file manager when asked.
2. Create a Kaggle API token: kaggle.com → **Settings → API → Create New Token** (starts with `KGAT_`).
3. Open **Kaggle Runs**, paste the token, tap **Save and test**.
4. Long-press the home screen → **Widgets** → **Kaggle Runs**, then add **Kaggle runs**, **Kaggle competition**
   or both.

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

All requests use Bearer auth against `https://www.kaggle.com/api/v1/`:

| Endpoint | Used for |
|---|---|
| `kernels/list?group=profile&sortBy=dateRun` | Your notebooks and when each last ran |
| `kernels/status` | Run status for the 8 most recent |
| `kernels/output` | How long a finished run took: the run log stamps every line with seconds since the start. Fetched once per run, then cached. |
| `competitions/list?group=entered` | Active competitions you've entered, with your rank, team count, deadline and daily limit |
| `competitions/submissions/list/{competition}` | Your submissions. Each one's `url` names the notebook and version it came from. |
| `competitions/{competition}/leaderboard/view` | Top score, and whether higher or lower scores are better |

| File | Role |
|---|---|
| `KaggleApi.kt` | The REST client above. |
| `Refresher.kt` | One refresh shared by the background worker, widget ⟳ and pull-to-refresh: fetch, record history, notify, cache, redraw. |
| `RefreshWorker.kt` | WorkManager scheduling at the interval chosen in Settings. |
| `Notifier.kt` | Compares the new snapshot with the previous one to detect finished runs and newly scored submissions. |
| `Stores.kt` | Snapshot cache, run-duration cache, per-competition history files, settings. |
| `KaggleWidgetProvider.kt`, `CompetitionWidget.kt` | The two widgets (`RemoteViews`; a `Chronometer` for the live timer). |
| `MainActivity.kt`, `*Screen.kt`, `Chart.kt` | The Jetpack Compose app. |
| `TokenStore.kt` | Encrypts the token with an Android Keystore AES-GCM key. |
| `OpenNotebookActivity.kt` | Invisible step that opens a tapped notebook (Android 14+ forbids mutable implicit PendingIntents). |
