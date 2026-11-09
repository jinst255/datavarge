package app.datavarge

import app.datavarge.service.SimTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarrierLabelTest {
    @Test fun knownBrands() {
        assertEquals("Boost", SimTracker.suggest("Boost Mobile"))
        assertEquals("T-Mobile", SimTracker.suggest("T-Mobile"))
        assertEquals("Verizon", SimTracker.suggest("Verizon Wireless"))
        assertEquals("AT&T", SimTracker.suggest("AT&T"))
        assertEquals("Mint", SimTracker.suggest("Mint Mobile"))
        assertEquals("Google Fi", SimTracker.suggest("Google Fi"))
    }

    @Test fun unknownCarrierFallsBackToReportedName() {
        assertEquals("Telcel", SimTracker.suggest("Telcel"))
        assertEquals("Vodafone UK", SimTracker.suggest("  Vodafone UK "))
    }

    @Test fun blankOrMissing() {
        assertNull(SimTracker.suggest(null))
        assertNull(SimTracker.suggest("   "))
    }
}
