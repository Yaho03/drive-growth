package io.drivegrowth.app.host.progression

import android.os.Bundle
import android.test.InstrumentationTestCase
import io.drivegrowth.app.ui.progress.*
import io.drivegrowth.core.contract.*

@Suppress("DEPRECATION")
class ProgressionUiStateBundleTest : InstrumentationTestCase() {
    fun testAllSelectionPendingConflictAndRevisionStateRoundTripsWithoutGrantingAnything() {
        val key = ProgressKey("daily-result-viewed", "DAILY|UTC|2026-11-10")
        val first = ClaimReference(key, 5, "quest/claim/first")
        val second = first.copy(requestKey = "quest/claim/other")
        val state = ProgressUiState(ProgressGroup.WEEKLY, key, setOf(first.requestKey, second.requestKey), first,
            true, mapOf(key to 8L), setOf(first, second))
        assertEquals(ContractResult.Success(state), ProgressionUiStateBundle.decode(ProgressionUiStateBundle.encode(state)))
    }
    fun testMalformedStateFailsRatherThanSilentlyDroppingPendingOrClampingRevision() {
        val b = ProgressionUiStateBundle.encode(ProgressUiState())
        b.putString("pending", "invalid")
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), ProgressionUiStateBundle.decode(b))
        val bad = ProgressionUiStateBundle.encode(ProgressUiState(acceptedRevisions = mapOf(ProgressKey("id", "period") to Long.MAX_VALUE)))
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), ProgressionUiStateBundle.decode(bad))
        assertEquals(ContractResult.Success(ProgressUiState()), ProgressionUiStateBundle.decode(null))
    }
}
