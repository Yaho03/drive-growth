package io.drivegrowth.core.content

import io.drivegrowth.core.contract.ContentVersion
import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.RuleVersion
import io.drivegrowth.core.contract.VehicleCatalog
import io.drivegrowth.core.contract.VehicleCatalogSnapshot
import io.drivegrowth.core.contract.VehicleDefinition
import io.drivegrowth.core.contract.VehicleId
import io.drivegrowth.core.progression.GachaRules
import io.drivegrowth.core.contract.Rarity
import java.util.Collections
import java.util.EnumMap

// 차량 사실(vehicles.csv)과 별도로 관리하는 게임용 값. 실제 판매가·실차 능력치가 아니다.
// 희귀도는 차종이 아니라 보유 개체(OwnedVehicle)가 가진다(PRD 2026-11-05).
data class VehicleGameData(
    val vehicleId: VehicleId,
    /** 차종 가격 = price_unit × 가격 비율. 레벨업·수리 비용과 「가장 비싼 차」의 기준. */
    val price: GameMoney,
    val unlockShopLevel: Int,
)

data class GameContentSnapshot(
    val catalog: VehicleCatalogSnapshot,
    val ruleVersion: RuleVersion,
    /** 시험용 수치면 true. 최종 밸런스로 배포하지 않는다. */
    val trial: Boolean,
    val vehicles: List<VehicleGameData>,
    val gacha: GachaRules,
) {
    /** 가게 레벨에서 해금된 차종. 가챠는 희귀도를 먼저 뽑고 이 중 하나를 고른다(#47). */
    fun unlockedAt(shopLevel: Int): List<VehicleGameData> = vehicles.filter { it.unlockShopLevel <= shopLevel }
}

sealed interface ContentLoad {
    data class Loaded(val snapshot: GameContentSnapshot) : ContentLoad
    /** reason은 진단용. 자동 기본값으로 대체 지급하지 않는다. */
    data class Rejected(val reason: String) : ContentLoad

    fun toResult(): ContractResult<GameContentSnapshot> = when (this) {
        is Loaded -> ContractResult.Success(snapshot)
        is Rejected -> ContractResult.Failure(ContractError.INVALID_INPUT)
    }

    fun asVehicleCatalog(): VehicleCatalog = VehicleCatalog {
        when (this) {
            is Loaded -> ContractResult.Success(snapshot.catalog)
            is Rejected -> ContractResult.Failure(ContractError.INVALID_INPUT)
        }
    }
}

/**
 * 콘텐츠 파일 형식:
 * ```
 * # content_version=...
 * # rule_version=...
 * # trial=true
 * # price_unit=8000        (가격 비율 100%의 금액. 수익 단위(#44)가 정해지면 이 값만 바꾼다)
 * # gacha_weights=COMMON:747,ADVANCED:200,RARE:50,LEGEND:3   (천분율, 합 1000)
 * # gacha_pity=30
 * # gacha_guaranteed_from=RARE
 * vehicle_id,price_ratio_percent,unlock_shop_level
 * hyundai_casper,100,1      (가격 비율 1.0 = 100)
 * ```
 */
object GameContentLoader {
    private const val CONTENT_HEADER = "vehicle_id,price_ratio_percent,unlock_shop_level"
    private val META_KEYS = setOf("content_version", "rule_version", "trial", "price_unit", "gacha_weights", "gacha_pity", "gacha_guaranteed_from")

    fun load(factsCsv: String, contentText: String): ContentLoad = try {
        ContentLoad.Loaded(parse(readFacts(factsCsv), contentText))
    } catch (e: IllegalArgumentException) { // require·숫자/enum 변환 실패 모두 여기로 온다.
        ContentLoad.Rejected(e.message ?: e.toString())
    }

    // ponytail: 따옴표 CSV는 거절한다. vehicles.csv에 따옴표 필드가 생기면 CSV 파서로 교체.
    private fun readFacts(csv: String): Map<VehicleId, String> {
        val lines = csv.removePrefix("﻿").lines().filter { it.isNotBlank() }
        require(lines.isNotEmpty()) { "차량 사실 CSV가 비어 있음" }
        require(lines.none { '"' in it }) { "따옴표 필드는 지원하지 않음" }
        val header = lines.first().split(',')
        val idCol = header.indexOf("vehicle_id")
        val nameCol = header.indexOf("model_name")
        require(idCol >= 0 && nameCol >= 0) { "사실 CSV에 vehicle_id·model_name 열이 필요" }
        val facts = linkedMapOf<VehicleId, String>()
        lines.drop(1).forEachIndexed { i, line ->
            val cols = line.split(',')
            require(cols.size == header.size) { "사실 CSV ${i + 2}행 열 개수 불일치" }
            val id = VehicleId(cols[idCol])
            require(facts.put(id, cols[nameCol]) == null) { "사실 CSV 중복 vehicle_id: ${id.value}" }
        }
        return facts
    }

    private fun parse(facts: Map<VehicleId, String>, text: String): GameContentSnapshot {
        val lines = text.removePrefix("﻿").lines().filter { it.isNotBlank() }
        val meta = mutableMapOf<String, String>()
        lines.takeWhile { it.startsWith("#") }.forEach { line ->
            val (key, value) = line.removePrefix("#").trim().split('=', limit = 2)
                .takeIf { it.size == 2 } ?: throw IllegalArgumentException("메타 형식 오류: $line")
            require(key in META_KEYS) { "알 수 없는 메타 키: $key" }
            require(meta.put(key, value.trim()) == null) { "중복 메타 키: $key" }
        }
        require(meta.keys == META_KEYS) { "누락된 메타 키: ${META_KEYS - meta.keys}" }

        val priceUnit = meta.getValue("price_unit").toLong()
        require(priceUnit > 0) { "price_unit은 양수" }
        val body = lines.dropWhile { it.startsWith("#") }
        require(body.firstOrNull() == CONTENT_HEADER) { "콘텐츠 열 머리는 $CONTENT_HEADER" }
        val vehicles = body.drop(1).map { line ->
            val cols = line.split(',')
            require(cols.size == 3) { "콘텐츠 열 개수 불일치: $line" }
            val id = VehicleId(cols[0])
            require(id in facts) { "사실 CSV에 없는 vehicle_id: ${id.value}" }
            val ratio = cols[1].toInt()
            require(ratio > 0) { "가격 비율은 양수: ${id.value}" }
            val level = cols[2].toInt()
            require(level >= 1) { "해금 가게 레벨은 1 이상: ${id.value}" }
            // 내림. 비율이 정수 %라 price_unit이 100의 배수면 정확하다.
            VehicleGameData(id, GameMoney(Math.multiplyExact(priceUnit, ratio.toLong()) / 100), level)
        }
        require(vehicles.isNotEmpty()) { "콘텐츠 차량이 없음" }
        require(vehicles.map { it.vehicleId }.toSet().size == vehicles.size) { "콘텐츠 중복 vehicle_id" }

        val gacha = GachaRules(parseWeights(meta.getValue("gacha_weights")), meta.getValue("gacha_pity").toInt(),
            Rarity.valueOf(meta.getValue("gacha_guaranteed_from")))
        // PRD: 보장은 희귀 이상. 기준을 그 아래로 내리는 콘텐츠는 거절한다.
        // 모든 차종이 모든 희귀도로 나오므로 보장 후보는 "해금 차종 1종 이상"이면 되고, 이는 차량이 하나라도
        // 있으면 첫 해금 레벨부터 성립한다. 보장 희귀도의 가중치 > 0은 GachaRules가 검사한다.
        require(gacha.guaranteedFrom >= Rarity.RARE) { "보장 희귀도는 RARE 이상" }

        return GameContentSnapshot(
            catalog = VehicleCatalogSnapshot(
                ContentVersion(meta.getValue("content_version")),
                vehicles.map { VehicleDefinition(it.vehicleId, facts.getValue(it.vehicleId)) }.frozen(),
            ),
            ruleVersion = RuleVersion(meta.getValue("rule_version")),
            trial = meta.getValue("trial").toBooleanStrict(),
            vehicles = vehicles.frozen(),
            gacha = gacha,
        )
    }

    private fun parseWeights(text: String): Map<Rarity, Int> {
        val weights = text.split(',').associate { pair ->
            val (rarity, weight) = pair.split(':').takeIf { it.size == 2 } ?: throw IllegalArgumentException("가중치 형식 오류: $pair")
            Rarity.valueOf(rarity.trim()) to weight.trim().toInt()
        }
        require(weights.keys == Rarity.entries.toSet() && weights.size == text.split(',').size) { "희귀도마다 가중치 하나씩 필요" }
        require(weights.values.all { it >= 0 } && weights.values.sumOf { it.toLong() } == 1000L) { "가중치는 0 이상, 합 1000(천분율)" }
        return Collections.unmodifiableMap(EnumMap(weights))
    }

    // 검증 후 Java 호출자가 목록을 바꿔 확률·후보가 달라지지 않도록 복사본을 읽기 전용으로 감싼다.
    private fun <T> List<T>.frozen(): List<T> = Collections.unmodifiableList(ArrayList(this))
}
