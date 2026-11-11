package io.drivegrowth.app.simulation

import android.test.InstrumentationTestCase
import io.drivegrowth.app.data.*
import io.drivegrowth.app.host.*
import io.drivegrowth.app.host.driving.*
import io.drivegrowth.app.host.onboarding.StoredOnboardingHostSource
import io.drivegrowth.app.host.rental.SessionRentalProductSource
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.FirstGrant
import java.time.ZoneId
import java.util.UUID

/** 실제 source/SQLite의 같은 첫 P 재시도. 입력·clock은 명시 SIMULATION이며 SDK 성공이 아니다. */
@Suppress("DEPRECATION")
class SimulationDuplicateParkingTest : InstrumentationTestCase() {
    private fun <T> ok(r:ContractResult<T>):T { assertTrue(r.toString(),r is ContractResult.Success);return(r as ContractResult.Success).value }
    fun testDuplicateFirstParkFrameRetainsOriginalDrivingEndAcrossRetry() {
        val context=instrumentation.targetContext;val name="simulation-duplicate-${UUID.randomUUID()}.db"
        var current=SimulationTimeline()
        val defs=ok(AssetTrialDefinitionProvider(context.assets).load())
        val games=GameSessionFactory(context,mapOf(InputSource.SIMULATION to name),InputSource.SIMULATION,
            {defs.questDefinitions(ZoneId.of("UTC"))},{current.clock.epoch.value})
        try {
            val onboarding=StoredOnboardingHostSource(games);val initial=ok(onboarding.read())
            ok(onboarding.choose(initial.identity,FirstGrant.CANDIDATES.first()))
            ok(games.open()).use{ok(it.store.activateObservedDriving(initial.identity.profileId))}
            val policy=RecordingPolicy(60_000,60_000,1000,3000,0.1,0.5)
            val driving=SessionObservedDrivingSource(games,SqliteObservedDrivingJournal(),ObservedDrivingReducer(policy){"duplicate-park-simulation"},{defs.drivingIncomeRules()})
            val rental=SessionRentalProductSource(games,{ContractResult.Success(defs)},policy,{ContractResult.Success(current.clock)})
            fun feed(){val frame=current.frame();val actual=driving.observe("simulation-receiver",frame,RecordingClock(current.elapsedMs,current.clock.epoch));ok(actual);ok(rental.observe(frame,actual))}
            current=checkNotNull(current.begin())
            while(current.stage<62){feed();current=checkNotNull(current.next())}
            val original=current.clock;feed();feed() // 원래 첫 P를 같은 시각·payload로 다시 전달한다.
            while(current.stage<65){current=checkNotNull(current.next());feed()}
            ok(rental.inactive(false,ContractResult.Success(current.clock)))
            val journal=SqliteRentalProductJournal(games,{error("조회에서 현재 정의 호출 금지")},{error("조회에서 현재 clock 호출 금지")},{error("조회에서 현재 원점 호출 금지")})
            val saved=checkNotNull(ok(journal.currentInactive(initial.identity)))
            assertEquals(RentalInactiveBasis.DRIVING_END,saved.basis)
            assertEquals(current.clock,saved.since)
            assertEquals(Observation.Available(original),ok(saved.policyOrigin))
        } finally {context.deleteDatabase(name)}
    }
}
