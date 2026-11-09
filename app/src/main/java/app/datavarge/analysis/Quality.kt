package app.datavarge.analysis

enum class Quality(val label: String, val argb: Long) {
    EXCELLENT("Excellent", 0xFF1B9E3E),
    GOOD("Good", 0xFF8BC34A),
    FAIR("Fair", 0xFFFFD600),
    POOR("Poor", 0xFFFF8F00),
    VERY_POOR("Very poor", 0xFFE53935),
    NONE("No service", 0xFF212121);

    companion object {
        /** Bins from the blueprint (RSRP dBm). Boundaries belong to the better bin (>= -85 is Excellent). */
        fun of(dbm: Int?): Quality = when {
            dbm == null -> NONE
            dbm >= -85 -> EXCELLENT
            dbm >= -95 -> GOOD
            dbm >= -105 -> FAIR
            dbm >= -115 -> POOR
            else -> VERY_POOR
        }

        /** Download speed bins (Mbps) for the speed metric on the map. */
        fun ofMbps(mbps: Double?): Quality = when {
            mbps == null -> NONE
            mbps >= 50 -> EXCELLENT
            mbps >= 25 -> GOOD
            mbps >= 10 -> FAIR
            mbps >= 3 -> POOR
            else -> VERY_POOR
        }
    }
}
