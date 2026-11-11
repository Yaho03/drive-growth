package io.drivegrowth.app.data

import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class RentalResultBasisTest {
    private val policy = RentalIncomePolicy(RuleVersion("original"), 1_800_000, 28_800_000, BigDecimal("0.30"))
    private fun period() = RentalIncomePeriod(EpochMillis(0), EpochMillis(3_600_000), RentalIncomeKind.PARKED,
        EpochMillis(0), 1_800_000, 1_800_000, 0, BigDecimal("2340000.00"), policy)
    private fun basis(p: List<RentalIncomePeriod>) = RentalResultBasis(RequestId("r"), SettlementId("s"),
        ProfileId("p"), InputSource.SIMULATION, RuleVersion("rental-original"), EpochMillis(0), EpochMillis(3_600_000), p)

    @Test fun totalsUseOriginalAcceptedDurationsAndExactDelta() {
        val b = basis(listOf(period()))
        assertEquals(1_800_000L, b.normalMillis); assertEquals(1_800_000L, b.reducedMillis)
        assertEquals(0L, b.cappedMillis); assertEquals(0, b.weightedMillis.compareTo(BigDecimal("2340000")))
        assertEquals(RuleVersion("original"), b.periods.single().policy!!.version)
    }
    @Test fun returnedPeriodsAreCopiedAndReadOnly() {
        val original = mutableListOf(period()); val b = basis(original); original.clear()
        assertEquals(1, b.periods.size)
        assertThrows(UnsupportedOperationException::class.java) { (b.periods as MutableList).clear() }
    }
    @Test fun invalidDurationOrClassificationIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { period().copy(normalMillis = -1) }
        assertThrows(IllegalArgumentException::class.java) { period().copy(cappedMillis = 1) }
        assertThrows(IllegalArgumentException::class.java) { period().copy(weightedMillis = BigDecimal("2340001")) }
        assertThrows(IllegalArgumentException::class.java) { period().copy(origin = null) }
        assertThrows(IllegalArgumentException::class.java) { period().copy(kind = RentalIncomeKind.DRIVING) }
    }
    @Test fun aCompletedBasisCannotHaveAHoleOverlapOrMissingTail() {
        assertThrows(IllegalArgumentException::class.java) { basis(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { basis(listOf(period(), period())) }
        assertThrows(IllegalArgumentException::class.java) { basis(listOf(period().copy(from = EpochMillis(1)))) }
    }
    @Test fun malformedStoredPolicyIsRejectedWithoutCurrentDefaults() {
        assertThrows(IllegalArgumentException::class.java) { policy.copy(normalMillis = -1) }
        assertThrows(IllegalArgumentException::class.java) { policy.copy(capMillis = 1) }
        assertThrows(IllegalArgumentException::class.java) { policy.copy(reducedMultiplier = BigDecimal("1.1")) }
    }
    @Test fun anArchivedPolicyIsKeptInsteadOfBeingReplacedByCurrentValues() {
        // 원본 형식의 합성 이전 버전 fixture다. 제품 경제 규칙을 바꾸지 않는다.
        val archived = RentalIncomePolicy(RuleVersion("archived-fixture"), 600_000, 28_800_000, BigDecimal("0.58"))
        val b = basis(listOf(period().copy(normalMillis = 600_000, reducedMillis = 3_000_000, policy = archived)))
        assertEquals(600_000L, b.normalMillis); assertEquals(3_000_000L, b.reducedMillis)
        assertEquals(archived, b.periods.single().policy)
        assertEquals(0, b.weightedMillis.compareTo(BigDecimal("2340000")))
    }
}
