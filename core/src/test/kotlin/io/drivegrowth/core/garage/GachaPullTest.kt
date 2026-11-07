package io.drivegrowth.core.garage

import io.drivegrowth.core.content.ContentLoad
import io.drivegrowth.core.content.GameContentFixtures
import io.drivegrowth.core.content.GameContentLoader
import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.GameChange
import io.drivegrowth.core.contract.GameChangeStore
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.InMemoryGameChangeStore
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.MoneyChange
import io.drivegrowth.core.contract.MoneyDirection
import io.drivegrowth.core.contract.OwnedVehicle
import io.drivegrowth.core.contract.OwnedVehicleId
import io.drivegrowth.core.contract.Rarity
import io.drivegrowth.core.contract.RequestId
import io.drivegrowth.core.contract.TransactionKind
import io.drivegrowth.core.contract.Revision
import io.drivegrowth.core.contract.VehicleAcquisitionSource
import io.drivegrowth.core.contract.VehicleId
import io.drivegrowth.core.contract.VehicleLifecycle
import org.junit.Assert.*
import org.junit.Test

class GachaPullTest {
    private val content = GameContentFixtures.loaded() // Lv1 4종, Lv2 +g90
    private val gacha = GachaPull(content)
    private val casper = VehicleId("hyundai_casper")
    private val firstCar = OwnedVehicle(OwnedVehicleId("first"), casper, 1, Revision(0), VehicleLifecycle.OWNED,
        Rarity.COMMON, 0, GameMoney(0), VehicleAcquisitionSource.INITIAL_GRANT)

    private fun request(id: String, ticket: GachaTicket = GachaTicket.PURCHASED, shopLevel: Int = 1,
                        capacity: Int = 3, counter: Int = 0) =
        GachaRequest(RequestId(id), InputSource.SIMULATION, ticket, shopLevel, capacity, counter)

    private fun store(balance: Long, vararg vehicles: OwnedVehicle = arrayOf(firstCar)) =
        InMemoryGameChangeStore(vehicles.toList(), GameMoney(balance))

    private fun <T> ok(result: ContractResult<T>): T = (result as ContractResult.Success).value
    private fun owned(store: InMemoryGameChangeStore) = ok(store.listOwnedVehicles())
    /** 첫 시도: 현재 보유로 계획을 만들고 저장. 계획 단계 실패는 그대로. */
    private fun pull(store: InMemoryGameChangeStore, r: GachaRequest) = when (val p = gacha.prepare(owned(store), r)) {
        is ContractResult.Failure -> p
        is ContractResult.Success -> gacha.submit(store, p.value)
    }

    @Test fun `유료 뽑기는 500 차감과 새 개체 지급을 한 거래로 저장하고 같은 요청을 다시 보내도 한 번만 반영한다`() {
        val store = store(1_000)
        val first = ok(pull(store, request("r1")))
        val vehicle = first.settlement.changedVehicle!!

        assertEquals(GameMoney(500), first.settlement.balance)
        assertEquals(MoneyDirection.DEBIT, first.settlement.transaction.money!!.direction)
        assertEquals(GameMoney(500), vehicle.acquisitionCost)
        assertEquals(VehicleAcquisitionSource.GACHA, vehicle.acquisitionSource)
        assertEquals(1, vehicle.level)
        assertEquals(first.draw.rarity, vehicle.rarity)
        assertTrue(vehicle.vehicleId in content.unlockedAt(1).map { it.vehicleId })

        // 앱 재실행 후 같은 요청 재시도: 같은 결과, 추가 차감·지급 없음
        val retry = ok(pull(store, request("r1")))
        assertEquals(first, retry)
        assertEquals(2, owned(store).size)
        assertEquals(GameMoney(0), ok(pull(store, request("r2"))).settlement.balance)
    }

    @Test fun `같은 요청과 카운터는 저장 없이도 같은 추첨 결과를 낸다`() {
        val a = ok(gacha.prepare(listOf(firstCar), request("seed-7", counter = 5)))
        val b = ok(gacha.prepare(listOf(firstCar), request("seed-7", counter = 5)))
        assertEquals(a, b)
    }

    @Test fun `무료권은 잔액 0에서도 차감 없이 지급하고 취득 기준액은 0이다`() {
        val store = store(0)
        val result = ok(pull(store, request("free", GachaTicket.FREE)))
        assertNull(result.settlement.transaction.money)
        assertEquals(GameMoney(0), result.settlement.balance)
        assertEquals(GameMoney(0), result.settlement.changedVehicle!!.acquisitionCost)
        assertEquals(VehicleAcquisitionSource.GACHA, result.settlement.changedVehicle!!.acquisitionSource)
    }

    @Test fun `같은 차종이 다시 나와도 기존 개체를 덮어쓰지 않고 새 개체를 만든다`() {
        val store = store(100_000)
        val ids = (1..30).map { ok(pull(store, request("dup-$it", capacity = 100))).settlement.changedVehicle!! }
        assertTrue(ids.groupBy { it.vehicleId }.any { it.value.size > 1 }) // 4종에서 30번이면 반드시 중복
        val all = owned(store)
        assertEquals(31, all.size)
        assertEquals(31, all.map { it.ownedVehicleId }.toSet().size)
        assertEquals(firstCar, all.first { it.ownedVehicleId == firstCar.ownedVehicleId })
    }

    @Test fun `30번째는 희귀 이상이고 카운터가 0으로 돌아간다`() {
        repeat(200) {
            val draw = ok(gacha.prepare(listOf(firstCar), request("pity-$it", counter = 29))).draw
            assertTrue(draw.guaranteed)
            assertTrue(draw.rarity >= Rarity.RARE)
            assertEquals(0, draw.drawsSinceGuarantee)
        }
    }

    @Test fun `거절되면 잔액·차량이 그대로이고 새 카운터를 주지 않는다`() {
        val full = store(1_000, firstCar, firstCar.copy(ownedVehicleId = OwnedVehicleId("second")),
            firstCar.copy(ownedVehicleId = OwnedVehicleId("third")))
        assertEquals(ContractResult.Failure(ContractError.CAPACITY_EXCEEDED), pull(full, request("full")))

        val poor = store(499)
        assertEquals(ContractResult.Failure(ContractError.INSUFFICIENT_FUNDS), pull(poor, request("poor")))

        val broken = store(1_000).apply { nextCommitFailure = ContractError.STORAGE_FAILURE }
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), pull(broken, request("broken")))

        val bad = store(1_000)
        for (r in listOf(request("lv0", shopLevel = 0), request("cnt", counter = 30), request("neg", counter = -1))) {
            assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), pull(bad, r))
        }

        for (s in listOf(full, poor, broken, bad)) {
            assertTrue(owned(s).none { it.acquisitionSource == VehicleAcquisitionSource.GACHA })
        }
        // 실패 후 같은 요청을 다시 보내면 정상 처리된다(실패가 기록으로 남지 않음)
        assertEquals(GameMoney(500), ok(pull(broken, request("broken"))).settlement.balance)
    }

    @Test fun `해금된 차종이 없으면 차감 전에 거절한다`() {
        val lockedAtOne = GameContentFixtures.content.replace(",1\n", ",2\n").replace(Regex(",1$"), ",2")
        val content = (GameContentLoader.load(GameContentFixtures.facts, lockedAtOne) as ContentLoad.Loaded).snapshot
        assertTrue(content.unlockedAt(1).isEmpty())
        val store = store(1_000)
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), GachaPull(content).prepare(owned(store), request("locked")))
        assertEquals(listOf(firstCar), owned(store))
    }

    @Test fun `가게 레벨 해금만 후보이고 해금 차종은 고르게 나온다`() {
        val lv1 = content.unlockedAt(1).map { it.vehicleId }.toSet()
        val n = 20_000
        val counts = (0 until n).map { ok(gacha.prepare(emptyList(), request("dist-$it"))).vehicle.vehicleId }
            .groupingBy { it }.eachCount()
        assertEquals(lv1, counts.keys) // Lv2 g90은 나오지 않음
        counts.values.forEach { assertEquals(n / 4.0, it.toDouble(), n * 0.02) }
        assertTrue((0 until 2_000).any { ok(gacha.prepare(emptyList(), request("lv2-$it", shopLevel = 2))).vehicle.vehicleId == VehicleId("genesis_g90") })
    }

    @Test fun `응답을 잃어도 보관한 계획을 다시 내면 공간·후보·조회 상태와 무관하게 처음 결과를 돌려준다`() {
        val store = store(2_000, firstCar, firstCar.copy(ownedVehicleId = OwnedVehicleId("second")))
        val plan = ok(gacha.prepare(owned(store), request("lost")))
        val first = ok(gacha.submit(store, plan)) // 저장 성공, 응답 유실 가정
        assertEquals(3, owned(store).size)

        // 차고가 찼다: 새 계획은 공간 부족이지만, 보관한 계획 재제출은 처음 정산·추첨 결과
        assertEquals(ContractResult.Failure(ContractError.CAPACITY_EXCEEDED), gacha.prepare(owned(store), request("lost")))
        assertEquals(first, ok(gacha.submit(store, plan)))

        // 콘텐츠가 바뀌어 후보가 없어도 같은 계획은 재생된다(계획은 현재 후보를 다시 읽지 않음)
        val locked = GameContentFixtures.content.replace(",1\n", ",2\n")
        val noCandidates = GachaPull((GameContentLoader.load(GameContentFixtures.facts, locked) as ContentLoad.Loaded).snapshot)
        assertEquals(first, ok(noCandidates.submit(store, plan)))

        // 보유 조회가 실패하는 저장소에서도 재제출은 조회 없이 처음 결과
        val listingFails = object : GameChangeStore by store {
            override fun listOwnedVehicles(): ContractResult<List<OwnedVehicle>> = ContractResult.Failure(ContractError.STORAGE_FAILURE)
        }
        assertEquals(first, ok(gacha.submit(listingFails, plan)))
        assertEquals(GameMoney(1_500), spendCheck(store)) // 차감은 처음 한 번(500)뿐
    }

    private fun spendCheck(store: InMemoryGameChangeStore): GameMoney =
        ok(store.commitGameChange(GameChange(RequestId("check"), TransactionKind.GARAGE, InputSource.SIMULATION,
            content.ruleVersion, money = MoneyChange(MoneyDirection.DEBIT, GameMoney(0))))).balance
}
