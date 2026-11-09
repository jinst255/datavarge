package app.datavarge.savings

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

@Serializable
data class Plan(
    val id: String,
    val provider: String,
    val name: String,
    val monthly: Double,
    @SerialName("monthly_without_autopay") val monthlyWithoutAutopay: Double? = null,
    @SerialName("upfront_annual") val upfrontAnnual: Double? = null,
    val billing: String = "",
    @SerialName("taxes_included") val taxesIncluded: Boolean = false,
    @SerialName("high_speed_gb") val highSpeedGb: Double? = null,
    @SerialName("after_cap") val afterCap: String? = null,
    @SerialName("hotspot_included") val hotspotIncluded: Boolean = false,
    @SerialName("hotspot_addon_monthly") val hotspotAddonMonthly: Double? = null,
    @SerialName("hotspot_note") val hotspotNote: String? = null,
    val network: String = "",
    @SerialName("source_confidence") val sourceConfidence: String = "low",
    val caveats: List<String> = emptyList(),
)

@Serializable
data class PlanSnapshot(
    @SerialName("snapshot_date") val snapshotDate: String,
    @SerialName("baseline_plan_id") val baselinePlanId: String,
    val plans: List<Plan>,
    @SerialName("general_caveats") val generalCaveats: List<String> = emptyList(),
)

data class SavingsInputs(
    val currentMonthly: Double = 25.00,
    val needsHotspot: Boolean = true,
    val taxRate: Double = 0.14,
)

data class PlanResult(
    val plan: Plan,
    val effectiveMonthly: Double,
    val savingsMonthly: Double,
    val savingsYearly: Double,
    /** For annual-prepay plans: the amount paid today, taxes applied the same way. */
    val upfrontToday: Double?,
)

object Savings {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): PlanSnapshot = json.decodeFromString(PlanSnapshot.serializer(), text)

    fun load(context: Context): PlanSnapshot =
        parse(context.assets.open("plans.json").bufferedReader().use { it.readText() })

    private fun hotspotAddon(plan: Plan, needsHotspot: Boolean): Double =
        if (needsHotspot && !plan.hotspotIncluded) plan.hotspotAddonMonthly ?: 0.0 else 0.0

    private fun taxed(amount: Double, plan: Plan, taxRate: Double) =
        if (plan.taxesIncluded) amount else amount * (1 + taxRate)

    /** effective = (price + hotspot add-on if needed and not included) * (1 + tax) unless taxes are included. */
    fun effectiveMonthly(plan: Plan, inputs: SavingsInputs, priceOverride: Double? = null): Double =
        taxed((priceOverride ?: plan.monthly) + hotspotAddon(plan, inputs.needsHotspot), plan, inputs.taxRate)

    /** The baseline uses the user's own current price, with the baseline plan's hotspot and tax rules. */
    fun baselineEffective(snapshot: PlanSnapshot, inputs: SavingsInputs): Double {
        val base = snapshot.plans.firstOrNull { it.id == snapshot.baselinePlanId }
            ?: return inputs.currentMonthly * (1 + inputs.taxRate)
        return effectiveMonthly(base, inputs, priceOverride = inputs.currentMonthly)
    }

    fun results(snapshot: PlanSnapshot, inputs: SavingsInputs): List<PlanResult> {
        val baseline = baselineEffective(snapshot, inputs)
        return snapshot.plans.filter { it.id != snapshot.baselinePlanId }.map { p ->
            val eff = effectiveMonthly(p, inputs)
            val sm = baseline - eff
            PlanResult(
                plan = p,
                effectiveMonthly = eff,
                savingsMonthly = sm,
                savingsYearly = sm * 12,
                upfrontToday = p.upfrontAnnual?.let { taxed(it + hotspotAddon(p, inputs.needsHotspot) * 12, p, inputs.taxRate) },
            )
        }
    }

    fun money(v: Double): String = "$" + String.format(java.util.Locale.US, "%,.2f", abs(v))
}
