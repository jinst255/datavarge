package app.datavarge

import app.datavarge.savings.Plan
import app.datavarge.savings.PlanSnapshot
import app.datavarge.savings.Savings
import app.datavarge.savings.SavingsInputs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SavingsTest {
    private val snapshot = Savings.parse(File("src/main/assets/plans.json").readText())
    private val defaults = SavingsInputs(25.0, needsHotspot = true, taxRate = 0.14)
    private fun result(id: String, inputs: SavingsInputs = defaults) = Savings.results(snapshot, inputs).single { it.plan.id == id }

    @Test fun parsesShippedSnapshot() {
        assertEquals("2026-11-09", snapshot.snapshotDate)
        assertEquals(5, snapshot.plans.size)
        assertTrue(snapshot.plans.all { it.sourceConfidence in setOf("high", "medium", "low") })
    }

    @Test fun baselineAddsHotspotAndTax() {
        // Boost $25 + $10 hotspot, plus 14%.
        assertEquals(39.90, Savings.baselineEffective(snapshot, defaults), 1e-9)
        assertEquals(28.50, Savings.baselineEffective(snapshot, defaults.copy(needsHotspot = false)), 1e-9)
    }

    @Test fun taxAddedOnlyWhenNotIncluded() {
        val tello = result("tello_20gb")       // $20, taxes extra, hotspot included
        assertEquals(22.80, tello.effectiveMonthly, 1e-9)
        assertEquals(17.10, tello.savingsMonthly, 1e-9)
        assertEquals(205.20, tello.savingsYearly, 1e-9)
        val usm = result("usm_10gb_gig")        // $20, taxes included
        assertEquals(20.00, usm.effectiveMonthly, 1e-9)
        assertEquals(19.90, usm.savingsMonthly, 1e-9)
    }

    @Test fun annualUpfront() {
        val flex = result("usm_flex_annual")
        assertEquals(17.50, flex.effectiveMonthly, 1e-9)
        assertEquals(210.00, flex.upfrontToday!!, 1e-9)
        assertEquals(22.40 * 12, flex.savingsYearly, 1e-9)
    }

    @Test fun negativeSavingsWhenCheaperNow() {
        val cheap = SavingsInputs(10.0, needsHotspot = false, taxRate = 0.0)
        val r = result("usm_starter_tmobile", cheap)
        assertEquals(-15.0, r.savingsMonthly, 1e-9)
        assertEquals(-180.0, r.savingsYearly, 1e-9)
    }

    @Test fun hotspotAddonForCandidatePlanWithoutHotspot() {
        val snap = PlanSnapshot(
            "2026-11-09", "base",
            listOf(
                Plan("base", "Boost Mobile", "Base", 25.0, hotspotIncluded = true),
                Plan("x", "X", "No hotspot", 15.0, hotspotIncluded = false, hotspotAddonMonthly = 5.0, taxesIncluded = true),
            ),
        )
        val r = Savings.results(snap, SavingsInputs(25.0, true, 0.0)).single()
        assertEquals(20.0, r.effectiveMonthly, 1e-9)
        assertEquals(5.0, r.savingsMonthly, 1e-9)
        val r2 = Savings.results(snap, SavingsInputs(25.0, false, 0.0)).single()
        assertEquals(15.0, r2.effectiveMonthly, 1e-9)
    }

    @Test fun otherCarrierBaseline() {
        // Not on any listed plan: typed price is taxed unless the user says taxes are included.
        val other = SavingsInputs(40.0, needsHotspot = true, taxRate = 0.10, baselinePlanId = Savings.OTHER)
        assertEquals(44.0, Savings.baselineEffective(snapshot, other), 1e-9)
        assertEquals(40.0, Savings.baselineEffective(snapshot, other.copy(otherTaxesIncluded = true)), 1e-9)
        // Every listed plan is a candidate, including Boost.
        assertEquals(5, Savings.results(snapshot, other).size)
    }

    @Test fun choosingAListedPlanAsBaselineExcludesIt() {
        val onUsm = SavingsInputs(25.0, needsHotspot = true, taxRate = 0.14, baselinePlanId = "usm_starter_tmobile")
        assertEquals(25.0, Savings.baselineEffective(snapshot, onUsm), 1e-9) // taxes included, hotspot included
        val ids = Savings.results(snapshot, onUsm).map { it.plan.id }
        assertTrue("usm_starter_tmobile" !in ids)
        assertTrue("boost_unlimited" in ids)
    }

    @Test fun planForLabel() {
        assertEquals("boost_unlimited", Savings.planForLabel(snapshot, "Boost")?.id)
        assertEquals("tello_20gb", Savings.planForLabel(snapshot, "Tello")?.id)
        assertEquals(null, Savings.planForLabel(snapshot, "US Mobile")) // several plans: ambiguous
        assertEquals(null, Savings.planForLabel(snapshot, "Verizon"))
    }
}
