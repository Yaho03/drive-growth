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
        assertEquals(VehicleGameData(VehicleId("genesis_g90"), GameMoney(24_000), 2), g90) // 8,000 × 300%
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

    @Test fun `앱에 넣는 20종 콘텐츠는 PRD 해금 배치와 가격 비율대로 읽힌다`() {
        // ponytail: app 모듈의 assets를 경로로 읽는다. 앱 장치 시험(PackagedContentTest)과 같은 파일을 로컬에서도 검증하기 위함.
        val dir = File("../app/src/main/assets/content")
        val snapshot = (GameContentLoader.load(File(dir, "vehicle-facts.csv").readText(), File(dir, "game-content.csv").readText())
            as ContentLoad.Loaded).snapshot
        assertTrue(snapshot.trial)
        assertEquals(listOf(8, 14, 18, 20), (1..4).map { snapshot.unlockedAt(it).size })
        val prices = snapshot.vehicles.associate { it.vehicleId.value to it.price.units }
        assertEquals(1_000L, prices["hyundai_casper"])      // 1.0
        assertEquals(1_700L, prices["hyundai_staria_tourer"]) // 1.7
        assertEquals(3_000L, prices["genesis_g90"])        // 3.0, 최고가 = 최저가의 3배
        assertEquals(prices.values.max(), prices.values.min() * 3)
        // 첫 지급 후보(캐스퍼·아반떼)는 Lv1에 있다
        assertTrue(snapshot.unlockedAt(1).map { it.vehicleId.value }.containsAll(listOf("hyundai_casper", "hyundai_avante")))
        // 앱 사실 CSV 이름은 저장소 원본 vehicles.csv와 같다
        val real = (GameContentLoader.load(File("../docs/data/vehicles.csv").readText(), File(dir, "game-content.csv").readText())
            as ContentLoad.Loaded).snapshot
        assertEquals(real.catalog, snapshot.catalog)
    }

    @Test fun `사실 CSV에 없는 차량과 중복 ID는 거절한다`() {
        assertTrue(rejected(content = contentWith("hyundai_casper,100", "hyundai_unknown,100")).contains("hyundai_unknown"))
        assertTrue(rejected(content = contentWith("hyundai_avante,120", "hyundai_casper,120")).contains("중복"))
        assertTrue(rejected(facts = "$facts\nhyundai_casper,캐스퍼2").contains("중복"))
    }

    @Test fun `음수·0·소수 가격 비율과 가격 단위, 잘못된 레벨은 거절한다`() {
        listOf("-100", "0", "NaN", "Infinity", "100.5", "").forEach { ratio ->
            rejected(content = contentWith("hyundai_casper,100,", "hyundai_casper,$ratio,"))
        }
        listOf("0", "-8000", "8000.5", "").forEach { unit -> rejected(content = contentWith("price_unit=8000", "price_unit=$unit")) }
        rejected(content = contentWith("hyundai_casper,100,1", "hyundai_casper,100,0"))
        rejected(content = contentWith("hyundai_casper,100,1", "hyundai_casper,100,COMMON,1")) // 옛 희귀도 열
    }

    @Test fun `차종 가격은 가격 단위에 비율을 곱한다`() {
        val prices = GameContentFixtures.loaded().vehicles.associate { it.vehicleId.value to it.price.units }
        assertEquals(mapOf("hyundai_casper" to 8_000L, "hyundai_avante" to 9_600L, "hyundai_sonata" to 12_000L,
            "hyundai_grandeur" to 16_000L, "genesis_g90" to 24_000L), prices)
        val doubled = (GameContentLoader.load(facts, contentWith("price_unit=8000", "price_unit=16000")) as ContentLoad.Loaded).snapshot
        assertEquals(GameMoney(16_000), doubled.vehicles.first().price) // 수익 단위만 바꾸면 전체 가격이 따라 바뀐다
    }

    @Test fun `중간 곱이 커도 최종 가격이 표현 가능하면 계산하고 범위를 넘거나 0이면 거절한다`() {
        // 캐스퍼 한 종만 남긴 콘텐츠에서 가격 단위·비율만 바꾼다.
        val casperOnly = content.lines().filter { !it.startsWith("hyundai_") && !it.startsWith("genesis_") || it.startsWith("hyundai_casper,") }
            .joinToString("\n")
        fun casperPrice(unit: String, ratio: Int) =
            (GameContentLoader.load(facts, casperOnly.replace("price_unit=8000", "price_unit=$unit").replace("hyundai_casper,100,", "hyundai_casper,$ratio,"))
                as ContentLoad.Loaded).snapshot.vehicles.single().price
        assertEquals(GameMoney(Long.MAX_VALUE), casperPrice(Long.MAX_VALUE.toString(), 100)) // 중간 곱은 Long 초과
        assertEquals(GameMoney(Long.MAX_VALUE / 100 * 99 + Long.MAX_VALUE % 100 * 99 / 100), casperPrice(Long.MAX_VALUE.toString(), 99))
        assertTrue(rejected(content = contentWith("price_unit=8000", "price_unit=${Long.MAX_VALUE}")).contains("지원 범위")) // G90 300%
        assertTrue(rejected(content = contentWith("price_unit=8000", "price_unit=1").replace("hyundai_casper,100,", "hyundai_casper,1,")).contains("1 이상"))
        assertEquals(GameMoney(1), casperPrice("1", 100))
    }

    @Test fun `가챠 확률 합·누락 희귀도·보장 조건 오류는 거절한다`() {
        rejected(content = contentWith("COMMON:747", "COMMON:748"))                 // 합 1001
        rejected(content = contentWith(",LEGEND:3", "").replace("COMMON:747", "COMMON:750")) // LEGEND 누락
        rejected(content = contentWith("COMMON:747", "COMMON:747,COMMON:0"))         // 중복 희귀도
        rejected(content = contentWith("RARE:50,LEGEND:3", "RARE:0,LEGEND:0").replace("COMMON:747", "COMMON:800")) // 보장 대상 확률 0
        rejected(content = contentWith("gacha_pity=30", "gacha_pity=0"))
        rejected(content = contentWith("gacha_guaranteed_from=RARE", "gacha_guaranteed_from=EPIC"))
    }

    @Test fun `보장 후보는 해금 차종이 있으면 충분하고 보장 기준을 RARE 아래로 내리면 거절한다`() {
        // 모든 차종이 모든 희귀도로 나오므로 첫 해금 레벨이 2여도 그 레벨부터 보장 후보가 있다.
        val shifted = content.replace(",1\n", ",2\n")
        val snapshot = (GameContentLoader.load(facts, shifted) as ContentLoad.Loaded).snapshot
        assertTrue(snapshot.unlockedAt(1).isEmpty())
        assertEquals(5, snapshot.unlockedAt(2).size)
        rejected(content = contentWith("gacha_guaranteed_from=RARE", "gacha_guaranteed_from=ADVANCED"))
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
        rejected(content = contentWith("vehicle_id,price_ratio_percent", "id,price_ratio_percent"))
        rejected(content = contentWith("hyundai_casper,100,1", "hyundai_casper,100,1,extra"))
        rejected(content = content.lines().takeWhile { !it.startsWith("hyundai") }.joinToString("\n")) // 차량 없음
        rejected(facts = "$facts\n\"hyundai_x\",엑스")
        rejected(facts = "")
    }
}
