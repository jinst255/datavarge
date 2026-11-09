# Datavarge

Android app that passively logs cellular signal strength by location, draws heat maps, compares carriers on the same places, and shows what each candidate plan would save (or cost) per month and per year. Works with any carrier's SIM. Sideloaded, no backend, no accounts; all data stays on the phone.

## What it does

- A foreground service logs a sample every time you move 20 m (`MOVE`), and every 10 min while you're still (`HEARTBEAT`), subject to a per-carrier density cap (max 6 automatic samples within 20 m, at least 30 min apart). **Test now** (`MANUAL`) always logs and can run a speed test forced over cellular.
- Losing service is logged as a sample with no dBm. Dead zones show black on the map.
- **Map**: 20 m grid colored by the median signal per cell; pick two carriers for difference mode.
- **Compare**: place-weighted median, p10, % usable cells, no-service %, speed-test medians, plus a head-to-head on shared cells only. It never declares a "winner".
- **Savings**: plan snapshot (`app/src/main/assets/plans.json`, prices checked 2026-11-09) against what you pay now, with hotspot add-on and estimated tax. Pick your current plan from the list, or choose "Other / not listed" and enter what you pay.
- **Export**: zip with CSVs and a self-contained `heatmap.html` for your computer.

## Build

Requirements: JDK 17+, Android SDK command-line tools with platform 37 (`ANDROID_HOME` set). No Android Studio needed.

```sh
./gradlew assembleDebug          # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # density cap, grid/bins, head-to-head, savings math
```

Stack: AGP 9.5 (built-in Kotlin), Kotlin 2.4, Compose (BOM 2026.09), Room, Play Services location, osmdroid, OkHttp, WorkManager. minSdk 31, target/compile 37. All tunable constants live in `app/src/main/java/app/datavarge/Config.kt`.

## Install

```sh
adb devices                      # phone must show as "device", not "unauthorized"
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n app.datavarge/.MainActivity
adb logcat -s Datavarge          # app log lines
```

## Phone setup

Install the APK from the [latest release](../../releases/latest) (allow "Install unknown apps" for your browser or file manager), or build it yourself and install over USB:

1. **Developer options**: Settings > About phone > Software information > tap **Build number** 7 times (on non-Samsung phones: Settings > About phone > Build number).
2. Settings > Developer options > turn on **USB debugging**. Plug in, tap **Allow** on "Allow USB debugging?".
3. Open Datavarge and follow the setup wizard:
   - Location: **Precise**, then **Allow all the time**.
   - Phone and Notifications: **Allow**.
   - Battery: allow background running.

### Battery settings (important on Samsung and other aggressive OEMs)

- Settings > Apps > Datavarge > Battery > **Unrestricted**.
- Settings > Battery > Background usage limits: Datavarge must **not** be in **Sleeping apps** or **Deep sleeping apps**. This is the setting people most often miss.

If Android still stops the service, a watchdog (every ~30 min) restarts it, or posts "Logging stopped. Tap to restart." if it can't.

## Swapping SIMs

1. Power off, swap the SIM, power on. Open Datavarge once (this also restarts logging if the reboot didn't).
2. You'll be asked: "New SIM detected. What should we call it?" Pick a suggestion (AT&T, Verizon, T-Mobile, Boost, Tello, US Mobile, Mint, Visible, Cricket, Metro, or the name the SIM reports) or type your own, like "US Mobile (Verizon)".
3. Until you label it, points are stored as `Unlabeled`; they're relabeled automatically when you name it. You can rename SIMs any time in More.
4. Repeat your usual routes for 2 to 3 days. The head-to-head needs at least 20 shared 20 m cells with 3+ samples from each carrier.

## Export

More > **Export** saves `datavarge_export_YYYYMMDD_HHMM.zip` to Downloads and offers Share. Or from the computer:

```sh
adb shell ls /sdcard/Download/ | grep datavarge
adb pull /sdcard/Download/datavarge_export_YYYYMMDD_HHMM.zip .
```

The zip has `samples.csv`, `speed_tests.csv`, `heatmap.html` (open in a browser with internet; uses Leaflet + OpenStreetMap tiles) and `README.txt` with field descriptions. More > **Share debug log** exports the rotating local log.

## Where data lives and how to delete it

- Everything is in the app's private storage on the phone (`datavarge.db` Room database, debug log, cached map tiles). No accounts, analytics, or cloud backup (`allowBackup=false`).
- Network use: map tiles from OpenStreetMap, speed-test traffic to Cloudflare (`speed.cloudflare.com`). Nothing else.
- More > **Delete all data** (asks first). Uninstalling also deletes everything, so export first.

## Known limitations

- Only one SIM is measured at a time; comparing needs SIM swaps and similar routes.
- GPS altitude isn't accurate enough to tell floors apart. On phones without a barometer, use the Test now note ("garage level 2") for floors. Phones with a barometer get a Level filter on the map.
- Signal strength is not speed. Speed varies with time of day and congestion.
- Samsung may still stop background apps; the watchdog tells you when that happens.
- After a reboot the app tries to resume by itself; if Android refuses, a notification asks you to tap to resume (or open the app once).
- The carrier name the phone reports can be the host network, not the brand. That's why labels are user-set.
- Plan prices are a snapshot of a few US prepaid plans, and some sources disagree. Confirm on each carrier's site. To compare other plans, edit `app/src/main/assets/plans.json` and rebuild; signal logging and comparison work with any carrier regardless.
- Airplane mode pauses logging rather than recording a dead zone (radio off is not a coverage result).
- When you stay in one place that's already capped (6 samples), no new automatic samples are stored there. That's intended; "hours logged" on Compare is tracked separately so it stays accurate.
