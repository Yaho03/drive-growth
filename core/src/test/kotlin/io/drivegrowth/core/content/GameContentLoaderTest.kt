package io.drivegrowth.core.content

import io.drivegrowth.core.content.GameContentFixtures.content
import io.drivegrowth.core.content.GameContentFixtures.facts
import io.drivegrowth.core.contract.ContentVersion
import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.RuleVersion
import io.drivegrowth.core.contract.VehicleId
import io.drivegrowth.core.progression.GachaRules
import io.drivegrowth.core.contract.Rarity
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GameContentLoaderTest {
    private fun rejected(facts: String = GameContentFixtures.facts, content: String = GameContentFixtures.content): String {
        val load = GameContentLoader.load(facts, content)
        assertTrue("거절돼야 함: $load", load is ContentLoad.Rejected)
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), load.toResult())
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), load.asVehicleCatalog().load())
        return (load as ContentLoad.Rejected).reason
    }

    private fun contentWith(old: String, new: String) = content.replace(old, new).also { assertNotEquals(content, it) }

    @Test fun `유효한 fixture는 버전·사실 이름·게임 값·가챠 규칙을 함께 읽는다`() {
        val snapshot = GameContentFixtures.loaded()
        assertEquals(ContentVersion("fixture-content-v0"), snapshot.catalog.contentVersion)
        assertEquals(RuleVersion("fixture-rule-v0"), snapshot.ruleVersion)
        assertTrue(snapshot.trial)
        assertEquals(listOf("캐스퍼", "아반떼", "쏘나타", "그랜저", "G90"), snapshot.catalog.vehicles.map { it.displayName })
        val g90 = snapshot.vehicles.single { it.vehicleId == VehicleId("genesis_g90") }
        assertEquals(VehicleGameData(VehicleId("genesis_g90"), GameMoney(60000), Rarity.LEGEND, 2), g90)
        assertEquals(GachaRules(), snapshot.gacha)
        assertEquals(4, snapshot.unlockedAt(1).size)
        assertEquals(5, snapshot.unlockedAt(2).size)
    }

    @Test fun `VehicleCatalog 계약으로 같은 스냅샷을 조회한다`() {
        val load = GameContentLoader.load(facts, content)
        val catalog = (load.asVehicleCatalog().load() as ContractResult.Success).value
        assertEquals(GameContentFixtures.loaded().catalog, catalog)
    }

    @Test fun `저장소의 실제 vehicles csv를 사실 원본으로 읽는다`() {
        val real = File("../docs/data/vehicles.csv").readText()
        val snapshot = (GameContentLoader.load(real, content) as ContentLoad.Loaded).snapshot
        // 사실 CSV의 54개 전체가 아니라 게임 콘텐츠에 있는 차량만 카탈로그에 들어간다. 이미지·로고·3D 열은 읽지 않는다.
        assertEquals(listOf("캐스퍼", "아반떼", "쏘나타", "그랜저", "G90"), snapshot.catalog.vehicles.map { it.displayName })
    }

    @Test fun `사실 CSV에 없는 차량과 중복 ID는 거절한다`() {
        assertTrue(rejected(content = contentWith("hyundai_casper,8000", "hyundai_unknown,8000")).contains("hyundai_unknown"))
        assertTrue(rejected(content = contentWith("hyundai_avante,10000", "hyundai_casper,10000")).contains("중복"))
        assertTrue(rejected(facts = "$facts\nhyundai_casper,캐스퍼2").contains("중복"))
    }

    @Test fun `음수·0·비유한·소수 금액과 잘못된 레벨·희귀도는 거절한다`() {
        listOf("-8000", "0", "NaN", "Infinity", "8000.5", "").forEach { price ->
            rejected(content = contentWith("hyundai_casper,8000,", "hyundai_casper,$price,"))
        }
        rejected(content = contentWith("COMMON,1\nhyundai_avante", "COMMON,0\nhyundai_avante"))
        rejected(content = contentWith("hyundai_casper,8000,COMMON", "hyundai_casper,8000,SUPER"))
    }

    @Test fun `가챠 확률 합·누락 희귀도·보장 조건 오류는 거절한다`() {
        rejected(content = contentWith("COMMON:747", "COMMON:748"))                 // 합 1001
        rejected(content = contentWith(",LEGEND:3", "").replace("COMMON:747", "COMMON:750")) // LEGEND 누락
        rejected(content = contentWith("COMMON:747", "COMMON:747,COMMON:0"))         // 중복 희귀도
        rejected(content = contentWith("RARE:50,LEGEND:3", "RARE:0,LEGEND:0").replace("COMMON:747", "COMMON:800")) // 보장 대상 확률 0
        rejected(content = contentWith("gacha_pity=30", "gacha_pity=0"))
        rejected(content = contentWith("gacha_guaranteed_from=RARE", "gacha_guaranteed_from=EPIC"))
    }

    @Test fun `가챠가 열리는 첫 레벨에 희귀 이상 차량이 없으면 거절한다`() {
        val reason = rejected(content = contentWith("hyundai_grandeur,25000,RARE,1", "hyundai_grandeur,25000,RARE,2"))
        assertTrue(reason.contains("가게 레벨 1"))
        // 첫 해금 레벨이 2로 올라가도 그 레벨에 희귀 이상이 있으면 통과한다.
        val shifted = content.replace(",1\n", ",2\n").replace(Regex(",1$"), ",2")
        val snapshot = (GameContentLoader.load(facts, shifted) as ContentLoad.Loaded).snapshot
        assertTrue(snapshot.unlockedAt(1).isEmpty())
        assertEquals(5, snapshot.unlockedAt(2).size)
    }

    @Test fun `보장 기준을 RARE 아래로 내리면 거절한다`() {
        rejected(content = contentWith("gacha_guaranteed_from=RARE", "gacha_guaranteed_from=ADVANCED"))
        // 리뷰 재현: 보장 기준과 그랜저를 모두 ADVANCED로 내려 1레벨에 희귀 이상이 없는 경우
        rejected(content = contentWith("gacha_guaranteed_from=RARE", "gacha_guaranteed_from=ADVANCED")
            .replace("25000,RARE", "25000,ADVANCED"))
    }

    @Test fun `첫 레벨의 보장 후보가 가중치 0이면 거절한다`() {
        // 리뷰 재현: 1레벨 RARE는 가중치 0, 가중치가 있는 LEGEND는 2레벨에서만 해금
        val reason = rejected(content = contentWith("RARE:50,LEGEND:3", "RARE:0,LEGEND:53"))
        assertTrue(reason.contains("가중치"))
    }

    @Test fun `검증된 스냅샷의 목록과 확률은 외부에서 바꿀 수 없다`() {
        val load = GameContentLoader.load(facts, content)
        val snapshot = (load as ContentLoad.Loaded).snapshot
        val before = snapshot.copy()
        assertThrows(UnsupportedOperationException::class.java) { (snapshot.vehicles as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (snapshot.catalog.vehicles as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (snapshot.gacha.weights as MutableMap<*, *>).clear() }
        assertEquals(before, snapshot)
        assertEquals(5, snapshot.vehicles.size)
        assertEquals(1000, snapshot.gacha.weights.values.sum())
        assertEquals(5, (load.asVehicleCatalog().load() as ContractResult.Success).value.vehicles.size)
    }

    @Test fun `메타·머리·형식 오류는 기본값 없이 거절한다`() {
        rejected(content = contentWith("# trial=true\n", ""))                         // 메타 누락
        rejected(content = contentWith("# trial=true", "# trial=yes"))
        rejected(content = contentWith("# trial=true", "# trial=true\n# trial=false"))
        rejected(content = contentWith("# trial=true", "# trial=true\n# bonus=2"))
        rejected(content = contentWith("vehicle_id,game_price", "id,game_price"))
        rejected(content = contentWith("hyundai_casper,8000,COMMON,1", "hyundai_casper,8000,COMMON,1,extra"))
        rejected(content = content.lines().takeWhile { !it.startsWith("hyundai") }.joinToString("\n")) // 차량 없음
        rejected(facts = "$facts\n\"hyundai_x\",엑스")
        rejected(facts = "")
    }
}
