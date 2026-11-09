# SignalSurvey: Blueprint v1

Working name. Personal-use Android app that passively logs cellular signal strength by location, then draws heat maps and compares carriers. Also shows what each carrier would save (or cost) per month and per year.

---

## 0. Instructions for Claude Code (read first)

You are building and installing this app end to end. Work autonomously. Stop and ask the human ONLY for things you physically cannot do: plugging in the phone, tapping "Allow USB debugging" on the phone, granting runtime permissions on the phone.

1. **Check the environment.** The dev machine is a Linux laptop (Ubuntu-based); confirm with `uname -a` and `lsb_release -a`. Verify JDK 17+, Android command-line tools (`sdkmanager`), platform-tools (`adb`), and a Gradle wrapper. If anything is missing, install it with the command-line tools. Do NOT require Android Studio. Set up udev rules for adb if `adb devices` shows nothing.
2. **Phone setup (tell the human exactly this):** Settings > About phone > Software information > tap Build number 7 times. Then Settings > Developer options > turn on USB debugging. Plug in via USB and tap Allow on the "Allow USB debugging?" prompt. Confirm `adb devices` lists the phone as `device` (not `unauthorized`).
3. **Build** a debug APK with the Gradle wrapper, **install** with `adb install -r`, **launch** with `adb shell am start`, and watch `adb logcat` for crashes.
4. **Do not say it works until you have verified it** (see section 22). A build that compiles is not a build that works.
5. Verify current Android API behavior against official Android developer docs wherever this document says "verify". Do not rely on memory for permissions, foreground service types, or telephony APIs.
6. Keep it simple. No backend, no accounts, no analytics, no API keys, no Google Maps SDK. One module, Kotlin.
7. When finished, give the human a short checklist of what to do on the phone (permissions, battery setting, SIM label), then stop.

**Target device:** Samsung Galaxy A34 (One UI, Android 14 to 16 possible). Single physical SIM, **no eSIM**, **no barometer**. Use minSdk 31, and set compileSdk and targetSdk to the latest stable.

---

## 1. Project overview

The human is comparing mobile carriers (current: Boost Mobile; candidates: Tello, US Mobile). Carrier coverage maps are generic, so this app measures real signal on his phone along his real routes and places. He will run it for roughly 2 to 3 days per SIM without touching it, then open the app (or an export on his computer) to see heat maps and a side-by-side comparison. It needs to be detailed but not drain the battery. A "Test now" button logs the current spot on demand, with an optional speed test.

Only one SIM can be measured at a time. Comparing carriers means swapping SIMs and repeating similar routes. The data model, the logging cap, and the compare screen are all built around that.

---

## 2. Name and distribution

- Working name: **SignalSurvey**. Package: `app.signalsurvey`. No name-conflict search needed (private, sideloaded only).
- Distribution: debug APK sideloaded over USB. No Play Store, no signing setup beyond the debug key.
- License: none (private project).

---

## 3. Core user flow

1. Install. First launch runs the setup wizard (section 16).
2. Logging starts (a foreground service with a quiet ongoing notification).
3. Human goes about life for 2 to 3 days. App logs automatically (section 6).
4. Anytime: tap **Test now** to log the exact spot (optionally with a speed test).
5. Open **Map** to see heat maps per carrier. Open **Compare** for the side-by-side. Open **Savings** for monthly and yearly cost differences.
6. **Export** a zip (CSV plus a self-contained HTML heat map) to view on the computer.
7. Swap SIM, label it when prompted, repeat. Data from all SIMs stays in one database.

---

## 4. Tech stack

- Kotlin, Jetpack Compose, single Activity, Material 3.
- Room (SQLite) for storage.
- `TelephonyCallback` for signal and cell info (verify; fall back to `PhoneStateListener` only if needed).
- Fused Location Provider (Play Services). Samsung ships Play Services. If unavailable, fall back to `LocationManager`.
- osmdroid for the in-app map (OpenStreetMap tiles, no API key). Set a proper user agent. Enable tile caching so visited areas work offline.
- OkHttp for the speed test.
- WorkManager for a watchdog (section 18).
- kotlinx.serialization for `plans.json` and export.

---

## 5. Permissions (request in the wizard, in this order)

| Permission | Why |
|---|---|
| `ACCESS_FINE_LOCATION` | GPS fixes and cell info |
| `ACCESS_BACKGROUND_LOCATION` | Logging while the app is closed. Must be requested separately after fine location (verify the two-step flow on the current Android version) |
| `READ_PHONE_STATE` | Network type, SIM and subscription info |
| `POST_NOTIFICATIONS` | The ongoing logging notification |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION` | Foreground service of type `location` (verify manifest requirements) |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Survive Samsung battery management |
| `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_NETWORK_STATE` | Speed test and map tiles |

No activity-recognition permission (avoid an extra prompt). Motion detection uses the location stream itself.

---

## 6. Logging engine

A foreground service owns everything. It keeps the latest signal and cell info in memory (updated by telephony callbacks) and takes a snapshot whenever a log trigger fires. Do not poll `getAllCellInfo()` on a timer (it is throttled; verify).

### Triggers

| Trigger | Rule |
|---|---|
| `MOVE` | New fix at least **20 m** from the last logged point |
| `HEARTBEAT` | While stationary, one sample every **10 min** |
| `MANUAL` | Test now button. Always logs. Bypasses the cap and the accuracy filter |

All constants live in one `Config.kt` so they are easy to tune. No settings screen for them.

### Battery strategy (the user wants detail, but not a drained battery)

- **Moving:** `PRIORITY_HIGH_ACCURACY`, interval about 10 s, fastest interval 5 s, minimum displacement 20 m.
- **Still** (no displacement over 20 m for 2 min): downgrade to `PRIORITY_BALANCED_POWER_ACCURACY` and rely on the 10-minute heartbeat.
- Upgrade back to high accuracy as soon as a fix is more than 20 m from the last one.
- Run in a foreground service so the OS keeps it alive. No wake locks beyond what the service holds.

### Density cap (do not pile up points in one spot)

For automatic samples only (`MOVE` and `HEARTBEAT`):
- Skip a sample if there are already **6** automatic samples within **20 m** for the same **carrier label**, OR if the newest automatic sample within 20 m is less than **30 min** old.
- The cap is **per carrier label**, never global. After a SIM swap, the new carrier must be able to log the same places.
- If the phone has a barometer, also key the cap by pressure band (round(hPa / 0.4), about 3 m of height) so garage level 1 and level 5 of the same building count separately. Without a barometer, the cap is 2D only.
- `MANUAL` samples ignore the cap entirely.

### Accuracy filter

- Automatic samples with horizontal accuracy worse than **100 m** are not logged.
- Samples between 50 and 100 m are stored but excluded from the heat map.
- `MANUAL` samples are always stored, with accuracy shown, and a note if accuracy is poor ("Location was approximate").

### No service is data

When the service state is out of service or emergency-only, still log a sample with `signal_dbm = null` and `service_state = OUT_OF_SERVICE`. Dead zones are the most useful result. The map shows them as a distinct color (dark gray or black).

### SIM labels

MVNOs often report the host network's name, so the operator string alone cannot tell Tello from T-Mobile. Therefore:
- Each SIM gets a **user label** ("Boost", "Tello", "US Mobile (Verizon)").
- Detect SIM changes with a subscription-change listener, keyed by subscription ID plus MCC/MNC (verify what is readable without extra permissions).
- On a new SIM, show a notification and an in-app prompt: "New SIM detected. What should we call it?" with suggestions (Boost, Tello, US Mobile, Other).
- Until labeled, samples are stored under `Unlabeled` and are **backfilled** with the label once it is set.

---

## 7. Signal data captured per sample

- `signal_dbm`: RSRP for LTE; SS-RSRP for 5G NR; RSSI for 3G or 2G. Prefer NR values when on 5G.
- `rsrq`, `snr` (RSSNR or SS-SINR) when available.
- `level_0_4`: Android's own bar level.
- `network_type`: LTE, NR_NSA, NR_SA, UMTS, GSM, none.
- `band` / channel number, `pci`, `cell_id` when readable.
- `service_state`, `is_roaming`, `wifi_connected` (as a flag only; signal is cellular regardless).
- `sim_operator_name`, `mcc_mnc`, `carrier_label`.

### Signal quality bins (used for colors everywhere)

| dBm (RSRP) | Label | Color |
|---|---|---|
| >= -85 | Excellent | green |
| -85 to -95 | Good | light green |
| -95 to -105 | Fair | yellow |
| -105 to -115 | Poor | orange |
| < -115 | Very poor | red |
| no service | None | black |

---

## 8. Altitude

- Always store `altitude_m` and `altitude_accuracy_m` from the GPS fix (use the MSL altitude API where available; verify).
- GPS altitude is typically off by 10 to 30 m, so it **cannot reliably tell floors apart**. The Galaxy A34 has **no barometer**, so on the target phone, altitude is informational only.
- If the phone has a pressure sensor (`Sensor.TYPE_PRESSURE`), also store `pressure_hpa` per sample. Barometer-based height is far more reliable over short periods than GPS. It drifts with weather over hours, so only compare within the same session or place.
- UI: show altitude in the sample detail sheet. On phones with a barometer, add a **Level** filter on the map. On phones without one, hide that filter and do not pretend.
- **Manual tests get an optional note field** ("garage level 2"). This is how floor context is captured on the A34.

---

## 9. Test now and the speed test

**Test now** (big button on Home, also an action in the notification):
1. Request a fresh single high-accuracy fix (timeout 15 s; use the best available fix if it times out).
2. Snapshot the signal. Save as a `MANUAL` sample.
3. Run the speed test (toggle on the button area, default on). Show progress.
4. Show a result card: signal, network type, down, up, latency. Offer an optional note field, dismissible.

**Speed test (must work on any carrier):**
- It measures whatever SIM is live, so it is carrier-agnostic by design.
- **Force it over cellular, not Wi-Fi.** Android prefers Wi-Fi by default, which would silently test the wrong network. Use `ConnectivityManager.requestNetwork` with `TRANSPORT_CELLULAR` and `NET_CAPABILITY_INTERNET`, then bind the OkHttp client to that network's socket factory (verify). If no cellular network is available, abort with a clear message.
- Latency: 5 small requests, median. Download: about 8 MB from Cloudflare's public speed test endpoint (`speed.cloudflare.com/__down?bytes=...`). Upload: about 3 MB to its `__up` endpoint. Hard stop after 12 s per phase.
- Show estimated data used (about 11 MB) under the toggle so the user knows the cost against a 10 GB plan.
- Record `ran_on_cellular = true/false` and never mix Wi-Fi results into carrier stats.

---

## 10. Storage schema (Room)

**samples**: `id`, `ts_utc_ms`, `lat`, `lon`, `loc_accuracy_m`, `altitude_m`, `altitude_accuracy_m`, `pressure_hpa?`, `speed_mps`, `carrier_label`, `sim_key`, `sim_operator_name`, `mcc_mnc`, `network_type`, `signal_dbm?`, `rsrq?`, `snr?`, `level_0_4`, `band?`, `pci?`, `cell_id?`, `service_state`, `is_roaming`, `wifi_connected`, `trigger` (MOVE/HEARTBEAT/MANUAL), `note?`

**speed_tests**: `id`, `sample_id`, `ts_utc_ms`, `down_mbps?`, `up_mbps?`, `latency_ms?`, `bytes_used`, `ran_on_cellular`, `error?`

**sim_labels**: `sim_key`, `label`, `first_seen_ms`

Indexes on `(carrier_label, lat, lon)` and `ts_utc_ms`.

---

## 11. Map and heat map (in app)

- osmdroid map, centered on the latest point. Cells are a fixed **20 m grid**, colored by the **median** `signal_dbm` of samples in that cell (median resists outliers). Draw as translucent squares or circles.
- Controls (compact): carrier chips (one carrier at a time, or "Difference" mode when two are selected), metric toggle (Signal / Download speed), time range (All / Today / Last 24 h), legend.
- Tap a cell: bottom sheet with sample count, median and worst dBm, network types seen, last sample time, and any notes.
- Dead zones (no service) always drawn on top.
- "Difference" mode: only cells with at least 3 samples from each of two carriers; color by dBm difference (3 dB or more counts as a real difference, otherwise "tie").

---

## 12. Compare screen

Per carrier label:
- Samples, distinct 20 m cells, hours logged.
- **Place-weighted median signal**: median per cell first, then the median across cells (so a long stop at home doesn't dominate).
- Worst 10% (p10) signal, and **% of cells usable** (at or above -105 dBm).
- **No-service %** of samples.
- Median download, upload, latency from manual speed tests (with test count).

**Head-to-head** (the fair comparison): use only cells that have at least 3 samples from both carriers. Show wins, ties, losses by median dBm (3 dB threshold) and the count of shared cells.

Honesty rules for the UI:
- If shared cells are fewer than 20, show "Not enough overlapping places yet. Repeat your usual routes with each SIM."
- Show a one-line caution that signal strength is not speed, and that time of day and congestion affect speed tests.
- Never output a single "winner" verdict. Show the numbers.

---

## 13. Savings screen

Compares monthly and yearly cost of candidate plans against what the user pays now. The comparison is a **snapshot of published prices** and must say so.

### Inputs (editable, saved)
- Current plan price per month, pre-tax. Default **$25.00** (Boost Unlimited with AutoPay).
- Toggle: **I need hotspot** (default ON). When ON, any plan that does not include hotspot gets its hotspot add-on price added (Boost: +$10/mo).
- **Estimated taxes and fees** for carriers that add them at checkout. Default **14%** (rough; the actual rate varies by state and city).
- Snapshot date shown at the top: "Prices checked Nov 9, 2026. Verify on the carrier's site before switching."

### Math
- `effective_monthly = price + (hotspot_addon if needs_hotspot and not hotspot_included) `, then multiplied by `(1 + tax_rate)` unless the plan says taxes are included.
- `savings_monthly = baseline_effective - plan_effective`
- `savings_yearly = savings_monthly * 12`
- Annual-prepay plans: also show the upfront amount (e.g., $210 today) and the monthly equivalent.
- Negative savings are shown plainly as "costs $X more".

### Data file: `assets/plans.json`
Ship this snapshot. Every plan has `source_confidence` (high/medium/low) so conflicts are visible in the UI.

```json
{
  "snapshot_date": "2026-11-09",
  "baseline_plan_id": "boost_unlimited",
  "plans": [
    {
      "id": "boost_unlimited",
      "provider": "Boost Mobile",
      "name": "Unlimited",
      "monthly": 25.00,
      "monthly_without_autopay": 30.00,
      "billing": "monthly prepaid, no contract",
      "taxes_included": false,
      "high_speed_gb": 30,
      "after_cap": "512 Kbps",
      "hotspot_included": false,
      "hotspot_addon_monthly": 10.00,
      "hotspot_note": "Hotspot draws from the same 30 GB your phone uses",
      "network": "Mainly AT&T, with some T-Mobile access during Boost's network transition",
      "source_confidence": "high",
      "caveats": [
        "$25 requires AutoPay; it is $30 without",
        "Taxes and fees are added at checkout",
        "Boost advertises the $25 price as locked ('Forever')",
        "Data is deprioritized and slows to 512 Kbps after 30 GB"
      ]
    },
    {
      "id": "tello_20gb",
      "provider": "Tello",
      "name": "20 GB + unlimited talk/text",
      "monthly": 20.00,
      "billing": "monthly prepaid, no contract",
      "taxes_included": false,
      "high_speed_gb": 20,
      "hotspot_included": true,
      "hotspot_note": "Hotspot uses the plan's data balance (up to the full 20 GB)",
      "network": "T-Mobile",
      "source_confidence": "medium",
      "caveats": [
        "Taxes and fees are added at checkout",
        "No AutoPay discount and no price-lock guarantee",
        "Tello's plan lineup and prices changed over time and sources disagree (an older source lists a 10 GB plan at about $19); confirm on tello.com",
        "Your A34 has no eSIM, so you would need a mailed physical SIM"
      ]
    },
    {
      "id": "usm_10gb_gig",
      "provider": "US Mobile",
      "name": "10 GB By the Gig",
      "monthly": 20.00,
      "billing": "monthly prepaid, no contract",
      "taxes_included": true,
      "high_speed_gb": 10,
      "hotspot_included": true,
      "hotspot_note": "Reported as included; allowance varies by source, confirm",
      "network": "You choose Verizon, T-Mobile, or AT&T",
      "source_confidence": "medium",
      "caveats": [
        "Taxes and fees are included in the listed price",
        "Data stops or slows sharply after the cap, depending on plan",
        "Plan names and prices vary between sources; confirm on usmobile.com"
      ]
    },
    {
      "id": "usm_flex_annual",
      "provider": "US Mobile",
      "name": "Unlimited Flex (annual)",
      "monthly": 17.50,
      "upfront_annual": 210.00,
      "billing": "paid yearly upfront (prepaid)",
      "taxes_included": true,
      "high_speed_gb": 10,
      "after_cap": "1 Mbps",
      "hotspot_included": true,
      "hotspot_note": "About 5 GB of hotspot",
      "network": "You choose Verizon, T-Mobile, or AT&T",
      "source_confidence": "medium",
      "caveats": [
        "You pay about $210 up front for the year; check the refund policy before paying",
        "Only about 10 GB of fast data, then 1 Mbps",
        "Hotspot allowance is smaller than on the unlimited plans"
      ]
    },
    {
      "id": "usm_starter_tmobile",
      "provider": "US Mobile",
      "name": "Unlimited Starter (T-Mobile network)",
      "monthly": 25.00,
      "billing": "monthly prepaid, no contract",
      "taxes_included": true,
      "high_speed_gb": 70,
      "after_cap": "1 Mbps",
      "hotspot_included": true,
      "hotspot_note": "10 GB hotspot",
      "network": "T-Mobile (Verizon and AT&T versions exist at similar prices)",
      "source_confidence": "medium",
      "caveats": [
        "Taxes and fees are included in the listed price",
        "An annual version costs less per month but is paid upfront",
        "Data is deprioritized versus the carrier's own customers"
      ]
    }
  ],
  "general_caveats": [
    "All of these are prepaid, no-contract plans. 'Prepaid' means you pay before the service period, and annual plans lock your money in for a year.",
    "Prices and plan details change often and sources disagree. This is a snapshot, not a quote.",
    "Switching means keeping or porting your number and installing a new physical SIM. Confirm your phone is unlocked first.",
    "Introductory promotions and price changes at renewal are not modeled."
  ]
}
```

### UI
- One row per plan: provider, plan, **saves $X/mo, $Y/yr** (green) or **costs $X/mo more** (red). Tap to expand: network, data cap, hotspot note, billing type, all caveats, confidence badge.
- A pinned "Before you switch" card showing `general_caveats`.
- Optional cross-link: if signal data exists for a carrier whose label matches the plan's provider, show its place-weighted median signal beside the savings. (Cost and coverage side by side is the point.)

---

## 14. Export (view on the computer)

Export button creates `signalsurvey_export_YYYYMMDD_HHMM.zip` in Downloads and offers the Android share sheet. Contents:
- `samples.csv` and `speed_tests.csv`.
- `heatmap.html`: a self-contained page with the data embedded as JSON, using Leaflet from a CDN (it opens in a normal desktop browser with internet). Carrier selector, same color bins, same 20 m cells, tap for details.
- `README.txt` (field descriptions).

Also document `adb pull` for the same files.

---

## 15. Screens and copy (keep minimal and plain)

Bottom navigation: **Home**, **Map**, **Compare**, **Savings**, **More** (SIM labels, export, permissions help, delete data).

**Home**
- Status line: "Logging • Boost • 4G LTE • -92 dBm (Good)". Paused state: "Paused".
- Counters: "Today: 143 points • Total: 612".
- Primary button: **Test now**. Subtext: "Logs this spot. Speed test uses about 11 MB."
- Secondary: **Pause** / **Resume**.

**Result card after Test now**
- "Saved. Signal -92 dBm (Good), LTE. Download 38.4 Mbps, upload 9.1, latency 41 ms."
- Note field placeholder: "Add a note (for example: garage level 2)". Button: Done.

**Notification (ongoing, silent, low importance)**
- Title: "SignalSurvey is logging"
- Text: "612 points • tap to open"
- Actions: Test now, Pause.

**Empty states**
- Map: "No data yet. Keep the app logging and check back after you've moved around."
- Compare: "Need data from at least one carrier. Swap SIMs to compare more."

---

## 16. First-run wizard

1. **What this does**: "SignalSurvey quietly records your phone's signal strength and location so you can see which carrier works best where you actually go. Your data stays on this phone." Button: Continue.
2. **Location**: "Allow precise location, then choose 'Allow all the time' so it can log while closed." Two-step request with a short explanation before each system dialog.
3. **Other permissions**: phone state, notifications.
4. **Keep it running** (Samsung): request the battery exemption, then show steps: Settings > Apps > SignalSurvey > Battery > **Unrestricted**; Settings > Battery > Background usage limits > make sure SignalSurvey is **not** in Sleeping apps or Deep sleeping apps. Button opens the relevant settings screen where possible; detect and show whether it's currently exempt.
5. **Your SIM and plan**: SIM label chips (Boost, Tello, US Mobile, Other). Current monthly price (default $25.00), "I need hotspot" toggle.
6. **Start**: "You're set. Use your phone normally for a couple of days." Starts the service.

---

## 17. Error handling

| Situation | User-facing behavior | Recovery / logging |
|---|---|---|
| Location services off | Notification: "Location is off, so logging is paused." | Resume automatically when turned on |
| Permission revoked | Banner on Home with a Fix button | Stop service cleanly |
| No GPS fix (indoors) | Auto: skip silently. Manual: save with "approximate" note | Retry on next trigger |
| No cellular service | Log as out of service | Counted in no-service % |
| Airplane mode | Pause logging, note it | Resume when off |
| OS killed the service | Watchdog notification (section 18) | Restart service on app open |
| SIM removed or swapped | Prompt to label the new SIM | Backfill `Unlabeled` samples |
| Speed test on Wi-Fi only or no cellular data | "Couldn't reach the cellular network. Speed test skipped." | Signal part still saved |
| Speed test timeout | "Speed test timed out. Signal was saved." | Store `error` |
| Storage error | "Couldn't save this point." | Log to a local file |
| Phone rebooted | App can't resume itself without being opened | Show a note on first open |

A local debug log file (rotating, 1 MB) is kept in app storage and exportable from More.

---

## 18. Survival on Samsung

- Foreground service of type `location`, started from a visible app launch.
- WorkManager periodic watchdog (about every 30 min): if logging should be on and the newest sample is more than 30 min old while the phone has been moving or a heartbeat was due, post "Logging stopped. Tap to restart."
- Battery exemption flow in the wizard (section 16). The setting the human most often misses is the **Sleeping apps** list.

---

## 19. Privacy

- All data stays on the phone. No accounts, analytics, or cloud sync.
- Network use: map tiles from OpenStreetMap and speed test traffic to Cloudflare, nothing else.
- Set `allowBackup="false"`.
- More > **Delete all data** (confirm first). Uninstalling removes everything, so export first.

---

## 20. File structure (suggested)

```
app/src/main/
  AndroidManifest.xml
  java/app/signalsurvey/
    MainActivity.kt                 single activity, nav host
    Config.kt                       all tunable constants
    service/LoggingService.kt       foreground service, triggers, cap, battery modes
    service/SignalMonitor.kt        TelephonyCallback, latest-signal cache
    service/SimTracker.kt           SIM change detection and labels
    service/Watchdog.kt             WorkManager check
    data/AppDatabase.kt             Room
    data/Sample.kt, SpeedTest.kt, SimLabel.kt
    data/Dao.kt
    speedtest/SpeedTester.kt        cellular-bound OkHttp tests
    analysis/Grid.kt                20 m cells, medians
    analysis/Compare.kt             place-weighted stats, head-to-head
    savings/Plans.kt                loads plans.json, savings math
    export/Exporter.kt              zip, CSV, heatmap.html
    ui/ ...                         Compose screens (Home, Map, Compare, Savings, More, Wizard)
  assets/plans.json
  assets/heatmap_template.html
```

---

## 21. Dependencies

Android Gradle Plugin, Kotlin, Compose BOM, Material 3, Room (+ KSP), `play-services-location`, osmdroid, OkHttp, WorkManager, kotlinx-coroutines, kotlinx-serialization-json. Dev-only: JUnit. Pin recent stable versions; check each one is current when you add it.

---

## 22. Build order and verification

**Build order**
1. Project skeleton, manifest, permissions, Gradle builds and installs a blank screen.
2. Room schema and DAO.
3. `SignalMonitor` (read live signal and show it on Home).
4. `LoggingService` with location, triggers, cap, accuracy filter.
5. Home screen, Test now (signal only), notification actions.
6. Speed test (cellular-bound).
7. SIM tracking and labels with backfill.
8. Grid analysis and Map screen.
9. Compare screen.
10. Savings screen with `plans.json`.
11. Export zip with `heatmap.html`.
12. Wizard, battery-exemption flow, watchdog.
13. Unit tests (below), then on-device verification.

**Unit tests (required)**
- Density cap: per-label, 6-sample limit, 30-min spacing, manual bypass, SIM swap not blocked.
- Grid median and the quality bins.
- Head-to-head logic (shared cells only, 3 dB tie threshold).
- Savings math: hotspot add-on, tax estimate, annual upfront, negative savings.

**On-device verification (report results to the human)**
- App installs and launches with no crash in `adb logcat`.
- Live signal appears on Home within 10 s.
- Walk or drive a short distance: new MOVE samples appear about every 20 m.
- Sit still 12+ min: at least one HEARTBEAT, and the cap prevents flooding.
- Test now logs a MANUAL sample, and the speed test result shows `ran_on_cellular = true` even while connected to Wi-Fi.
- Toggle airplane mode: an out-of-service sample is recorded.
- Screen off for 15 min with the app closed: logging continues (confirms the battery settings).
- Export zip opens on the computer and `heatmap.html` renders.

---

## 23. Known limitations (document in README)

- Only one SIM is measured at a time; comparison needs SIM swaps and similar routes.
- A34 has no barometer; GPS altitude is not floor-accurate. Use notes for garage levels.
- Signal strength is not the same as speed. Speed varies with time of day and congestion.
- Samsung may still stop background apps; the watchdog tells you when it did.
- If the phone reboots, open the app once to resume.
- Carrier names reported by the phone can be the host network, not the brand. That is why labels are user-set.
- Plan prices are a snapshot and some sources disagree.

## 24. README checklist

What it does; how to build (`./gradlew assembleDebug`); how to install (`adb install -r ...`); phone setup steps; battery settings; how to swap SIMs and label them; how to export; known limitations; where data lives; how to delete it.
