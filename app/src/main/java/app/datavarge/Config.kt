package app.datavarge

/** Every tunable constant lives here. There is deliberately no settings screen for these. */
object Config {
    // ---- Triggers ----
    /** MOVE: a new fix at least this far from the last evaluated point. */
    const val MOVE_DISTANCE_M = 20.0
    /** HEARTBEAT: while stationary, try one sample this often. */
    const val HEARTBEAT_INTERVAL_MS = 10 * 60_000L

    // ---- Battery modes ----
    const val MOVING_INTERVAL_MS = 10_000L
    const val MOVING_FASTEST_INTERVAL_MS = 5_000L
    const val MOVING_MIN_DISPLACEMENT_M = 20f
    /** No displacement over [MOVE_DISTANCE_M] for this long means "still". */
    const val STILL_AFTER_MS = 2 * 60_000L
    const val STILL_INTERVAL_MS = 60_000L
    const val STILL_FASTEST_INTERVAL_MS = 30_000L
    /** Heartbeat fix: how long to wait for a fresh high-accuracy fix. */
    const val HEARTBEAT_FIX_TIMEOUT_MS = 20_000L
    /** A cached fix younger than this is good enough for a heartbeat. */
    const val HEARTBEAT_MAX_FIX_AGE_MS = 60_000L

    // ---- Density cap (automatic samples only, per carrier label) ----
    const val CAP_RADIUS_M = 20.0
    const val CAP_MAX_SAMPLES = 6
    const val CAP_MIN_SPACING_MS = 30 * 60_000L
    /** Barometer pressure band width (~3 m of height). Only used on phones with a barometer. */
    const val PRESSURE_BAND_HPA = 0.4

    // ---- Accuracy filter ----
    /** Automatic samples worse than this are not logged at all. */
    const val MAX_ACCURACY_LOG_M = 100f
    /** Samples worse than this are stored but excluded from heat maps and stats. */
    const val MAX_ACCURACY_MAP_M = 50f

    // ---- Manual test ----
    const val MANUAL_FIX_TIMEOUT_MS = 15_000L

    // ---- Speed test ----
    const val SPEED_DOWN_BYTES = 8_000_000L
    const val SPEED_UP_BYTES = 3_000_000L
    const val SPEED_PHASE_LIMIT_MS = 12_000L
    const val SPEED_LATENCY_PINGS = 5
    const val SPEED_NETWORK_TIMEOUT_MS = 10_000L
    const val SPEED_EST_MB = 11
    const val SPEED_BASE_URL = "https://speed.cloudflare.com"

    // ---- Analysis ----
    const val GRID_CELL_M = 20.0
    const val USABLE_DBM = -105
    const val HEAD_TO_HEAD_MIN_SAMPLES = 3
    const val TIE_THRESHOLD_DB = 3.0
    const val MIN_SHARED_CELLS = 20

    // ---- Watchdog ----
    const val WATCHDOG_PERIOD_MIN = 30L
    const val WATCHDOG_STALE_MS = 30 * 60_000L

    // ---- Debug log ----
    const val DEBUG_LOG_MAX_BYTES = 1_000_000L

    /** Gaps between "logging active" ticks longer than this are not counted as logging time. */
    const val ACTIVE_TICK_MS = 60_000L

    const val USER_AGENT = "Datavarge/1.0 (personal signal survey; Android)"
}
