package io.drivegrowth.core.garage

import io.drivegrowth.core.content.ContentLoad
import io.drivegrowth.core.content.GameContentFixtures
import io.drivegrowth.core.content.GameContentLoader
import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.contract.ContentVersion
import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.GachaAuthority
import io.drivegrowth.core.contract.GachaChangeStore
import io.drivegrowth.core.contract.GachaCommand
import io.drivegrowth.core.contract.GachaProgress
import io.drivegrowth.core.contract.GachaState
import io.drivegrowth.core.contract.GachaTicketUse
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.InMemoryGameChangeStore
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.MoneyDirection
import io.drivegrowth.core.contract.OwnedVehicle
import io.drivegrowth.core.contract.OwnedVehicleId
import io.drivegrowth.core.contract.Rarity
import io.drivegrowth.core.contract.RequestId
import io.drivegrowth.core.contract.Revision
import io.drivegrowth.core.contract.VehicleAcquisitionSource
import io.drivegrowth.core.contract.VehicleId
import io.drivegrowth.core.contract.VehicleLifecycle
import org.junit.Assert.*
import org.junit.Test

class GachaPullTest {
    private val content = GameContentFixtures.loaded() // Lv1 4종, Lv2 +g90
    private val gacha = GachaPull(content)
    private val PAID = GachaTicketUse.PURCHASED
    private val FREE = GachaTicketUse.FREE
    private val casper = VehicleId("hyundai_casper")
    private val firstCar = OwnedVehicle(OwnedVehicleId("first"), casper, 1, Revision(0), VehicleLifecycle.OWNED,
        Rarity.COMMON, 0, GameMoney(0), VehicleAcquisitionSource.INITIAL_GRANT)

    /** 저장 측 권위 상태: 이 콘텐츠와 같은 snapshot. 시험 주입값이며 제품 초기값이 아니다. */
    private fun state(shopLevel: Int = 1, capacity: Int = 3, counter: Int = 0, freeTickets: Long = 0,
                      snapshot: GameContentSnapshot = content, contentVersion: ContentVersion = snapshot.catalog.contentVersion) =
        GachaState(
            GachaAuthority(InputSource.SIMULATION, contentVersion, snapshot.ruleVersion, shopLevel, capacity, GameMoney(500),
                snapshot.gacha.pity, snapshot.gacha.guaranteedFrom, snapshot.unlockedAt(shopLevel).map { it.vehicleId }.toSet(),
                Rarity.entries.toSet()),
            GachaProgress(Revision(0), counter, freeTickets),
        )

    private fun store(balance: Long, vararg vehicles: OwnedVehicle = arrayOf(firstCar), state: GachaState = state()) =
        InMemoryGameChangeStore(vehicles.toList(), GameMoney(balance), gachaState = state)

    private fun <T> ok(result: ContractResult<T>): T = (result as ContractResult.Success).value
    private fun fail(error: ContractError) = ContractResult.Failure(error)
    private fun owned(store: InMemoryGameChangeStore) = ok(store.listOwnedVehicles())
    private fun prepare(store: GachaChangeStore, id: String, ticket: GachaTicketUse = PAID) = gacha.prepare(store, RequestId(id), ticket)
    /** 첫 시도: 계획하고 바로 확정. 계획 단계 실패는 그대로. */
    private fun pull(store: InMemoryGameChangeStore, id: String, ticket: GachaTicketUse = PAID) = when (val c = prepare(store, id, ticket)) {
        is ContractResult.Failure -> c
        is ContractResult.Success -> store.commitGacha(c.value)
    }

    @Test fun `유료 뽑기는 500 차감·새 개체·카운터를 한 번에 확정하고 같은 명령 재제출은 처음 결과다`() {
        val store = store(1_000)
        val command = ok(prepare(store, "r1"))
        val first = ok(store.commitGacha(command))
        val vehicle = first.settlement.changedVehicle!!

        assertEquals(GameMoney(500), first.settlement.balance)
        assertEquals(MoneyDirection.DEBIT, first.settlement.transaction.money!!.direction)
        assertEquals(listOf<Any>(GameMoney(500), VehicleAcquisitionSource.GACHA, 1), listOf(vehicle.acquisitionCost, vehicle.acquisitionSource, vehicle.level))
        assertEquals(command.award.vehicle, vehicle)
        assertTrue(vehicle.vehicleId in content.unlockedAt(1).map { it.vehicleId })
        assertEquals(GachaProgress(Revision(1), command.award.nextCounter, 0), first.progress)
        assertEquals(first.progress, ok(store.readGachaState()).progress)

        assertEquals(first, ok(store.commitGacha(command))) // 응답 유실 후 재제출
        assertEquals(2, owned(store).size)
    }

    @Test fun `같은 requestId로 카운터 등 입력이 다른 명령은 충돌하고 상태를 바꾸지 않는다`() {
        val store = store(10_000)
        val first = ok(store.commitGacha(ok(prepare(store, "same"))))
        // 리뷰 재현: 성공 뒤 같은 ID로 다시 계획하면 카운터·revision이 달라진 새 명령이 된다
        val again = ok(prepare(store, "same"))
        assertNotEquals(first.command, again)
        val before = Triple(owned(store), ok(store.readGachaState()), first.settlement.balance)
        assertEquals(fail(ContractError.STATE_CONFLICT), store.commitGacha(again))
        assertEquals(fail(ContractError.STATE_CONFLICT), store.commitGacha(first.command.copy(ticket = FREE)))
        assertEquals(before, Triple(owned(store), ok(store.readGachaState()), first.settlement.balance))
        assertEquals(first, ok(store.commitGacha(first.command)))
    }

    @Test fun `마지막 칸에 같은 상태로 만든 두 요청은 한 건만 성공한다`() {
        val store = store(2_000, firstCar, firstCar.copy(ownedVehicleId = OwnedVehicleId("second")))
        val a = ok(prepare(store, "race-a"))
        val b = ok(prepare(store, "race-b"))
        ok(store.commitGacha(a))
        assertTrue(store.commitGacha(b) is ContractResult.Failure)
        assertEquals(3, owned(store).size)
        assertEquals(fail(ContractError.CAPACITY_EXCEEDED), prepare(store, "race-c")) // 이후 새 계획은 공간 부족
    }

    @Test fun `재제출은 그사이 차고가 차거나 보유 조회가 실패해도 처음 결과를 돌려준다`() {
        val store = store(2_000, firstCar, firstCar.copy(ownedVehicleId = OwnedVehicleId("second")))
        val command = ok(prepare(store, "lost"))
        val first = ok(store.commitGacha(command))
        assertEquals(fail(ContractError.CAPACITY_EXCEEDED), prepare(store, "next"))
        assertEquals(first, ok(store.commitGacha(command)))
        val listingFails = object : GachaChangeStore by store {
            override fun listOwnedVehicles(): ContractResult<List<OwnedVehicle>> = ContractResult.Failure(ContractError.STORAGE_FAILURE)
        }
        assertEquals(fail(ContractError.STORAGE_FAILURE), prepare(listingFails, "x"))
        assertEquals(first, ok(listingFails.commitGacha(command)))
    }

    @Test fun `무료권은 차감 없이 취득액 0으로 지급하고 무료권이 없으면 차감 전에 거절한다`() {
        val store = store(0, state = state(freeTickets = 1))
        val result = ok(pull(store, "free", FREE))
        assertNull(result.settlement.transaction.money)
        assertEquals(GameMoney(0), result.settlement.changedVehicle!!.acquisitionCost)
        assertEquals(0L, result.progress.freeTickets)
        assertEquals(fail(ContractError.INSUFFICIENT_FUNDS), pull(store, "free-2", FREE))
        assertEquals(2, owned(store).size)
    }

    @Test fun `같은 차종이 다시 나와도 새 개체를 만들고 기존 개체는 그대로다`() {
        val store = store(100_000, state = state(capacity = 100))
        val got = (1..30).map { ok(pull(store, "dup-$it")).settlement.changedVehicle!! }
        assertTrue(got.groupBy { it.vehicleId }.any { it.value.size > 1 }) // 4종에서 30번이면 반드시 중복
        assertEquals(31, owned(store).map { it.ownedVehicleId }.toSet().size)
        assertEquals(firstCar, owned(store).first { it.ownedVehicleId == firstCar.ownedVehicleId })
        assertEquals(Revision(30), ok(store.readGachaState()).progress.revision)
    }

    @Test fun `30번째는 희귀 이상이고 다음 카운터는 0이다`() {
        val store = store(1_000, state = state(counter = 29))
        repeat(200) {
            val award = ok(prepare(store, "pity-$it")).award
            assertTrue(award.guaranteed)
            assertTrue(award.vehicle.rarity >= Rarity.RARE)
            assertEquals(0, award.nextCounter)
        }
        assertTrue(ok(pull(store, "pity-commit")).command.award.guaranteed)
    }

    @Test fun `거절되면 잔액·차량·가챠 상태가 그대로다`() {
        fun unchanged(s: InMemoryGameChangeStore, before: List<OwnedVehicle>) {
            assertEquals(before, owned(s))
            assertEquals(Revision(0), ok(s.readGachaState()).progress.revision)
        }
        val full = store(1_000, firstCar, firstCar.copy(ownedVehicleId = OwnedVehicleId("b")), firstCar.copy(ownedVehicleId = OwnedVehicleId("c")))
        assertEquals(fail(ContractError.CAPACITY_EXCEEDED), pull(full, "full")); unchanged(full, owned(full))

        val poor = store(499)
        assertEquals(fail(ContractError.INSUFFICIENT_FUNDS), pull(poor, "poor")); unchanged(poor, listOf(firstCar))

        val broken = store(1_000).apply { nextCommitFailure = ContractError.STORAGE_FAILURE }
        val command = ok(prepare(broken, "broken"))
        assertEquals(fail(ContractError.STORAGE_FAILURE), broken.commitGacha(command)); unchanged(broken, listOf(firstCar))
        assertEquals(GameMoney(500), ok(broken.commitGacha(command)).settlement.balance) // 실패는 기록되지 않아 재시도 가능

        val noState = InMemoryGameChangeStore(listOf(firstCar), GameMoney(1_000))
        assertEquals(fail(ContractError.DATA_UNAVAILABLE), prepare(noState, "no-state"))
    }

    @Test fun `저장 측 규칙·해금이 이 콘텐츠와 다르면 계획하지 않는다`() {
        val otherVersion = store(1_000, state = state(contentVersion = ContentVersion("other-content")))
        assertEquals(fail(ContractError.STATE_CONFLICT), prepare(otherVersion, "v"))
        val wrongUnlock = store(1_000, state = state(shopLevel = 2).let { s ->
            s.copy(authority = GachaAuthority(s.authority.source, s.authority.contentVersion, s.authority.ruleVersion, 2, 3,
                GameMoney(500), s.authority.pity, s.authority.guaranteedFrom, setOf(casper), s.authority.eligibleRarities))
        })
        assertEquals(fail(ContractError.STATE_CONFLICT), prepare(wrongUnlock, "u"))
    }

    @Test fun `해금 차종이 없으면 차감 전에 거절한다`() {
        val locked = GameContentFixtures.content.replace(",1\n", ",2\n")
        val lockedContent = (GameContentLoader.load(GameContentFixtures.facts, locked) as ContentLoad.Loaded).snapshot
        val store = store(1_000, state = state(snapshot = lockedContent))
        assertEquals(fail(ContractError.DATA_UNAVAILABLE), GachaPull(lockedContent).prepare(store, RequestId("locked"), PAID))
        assertEquals(listOf(firstCar), owned(store))
    }

    @Test fun `가게 레벨 해금만 후보이고 해금 차종은 고르게 나온다`() {
        val store = store(1_000)
        val n = 20_000
        val counts = (0 until n).map { ok(prepare(store, "dist-$it")).award.vehicle.vehicleId }.groupingBy { it }.eachCount()
        assertEquals(content.unlockedAt(1).map { it.vehicleId }.toSet(), counts.keys) // Lv2 g90은 나오지 않음
        counts.values.forEach { assertEquals(n / 4.0, it.toDouble(), n * 0.02) }
        val lv2 = store(1_000, state = state(shopLevel = 2))
        assertTrue((0 until 2_000).any { ok(prepare(lv2, "lv2-$it")).award.vehicle.vehicleId == VehicleId("genesis_g90") })
    }

    @Test fun `같은 요청과 상태는 저장 없이도 같은 명령을 만든다`() {
        val store = store(1_000, state = state(counter = 5))
        assertEquals(ok(prepare(store, "seed-7")), ok(prepare(store, "seed-7")))
    }
}
