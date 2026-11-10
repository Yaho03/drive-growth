package io.drivegrowth.app.host.shop

import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.ShopExpansion
import java.util.Collections

/** #24 동의의 버전 있는 시험 정의. 시설/꾸미기 가격을 포함하지 않는다. */
class ShopExpansionDefinition internal constructor(
    val version: ContentVersion, val rules: RuleVersion, val trial: Boolean, offers: List<ShopExpansion>,
) {
    val offers: List<ShopExpansion> = Collections.unmodifiableList(ArrayList(offers))
}
fun interface ShopExpansionDefinitionSource { fun load(): ContractResult<ShopExpansionDefinition> }

internal fun parseShopExpansionDefinition(text: String): ContractResult<ShopExpansionDefinition> = try {
    val rows = text.lineSequence().filter { it.isNotBlank() }.map { line -> line.split(',').map(String::trim) }.toList()
    require(rows.size >= 5)
    fun metadata(index: Int, key: String): String {
        require(rows[index].size == 2 && rows[index][0] == key)
        return rows[index][1]
    }
    val version = ContentVersion(metadata(0,"definition_version"))
    val rules = RuleVersion(metadata(1,"rule_version"))
    val trial = when(metadata(2,"trial")) { "true" -> true; "false" -> false; else -> error("trial 형식 오류") }
    require(rows[3] == listOf("from_level","current_capacity","next_capacity","completed_rentals","price"))
    fun integer(value: String): Long { require(value.matches(Regex("0|[1-9][0-9]*"))); return value.toLong() }
    fun int(value: String): Int { val n=integer(value);require(n<=Int.MAX_VALUE);return n.toInt() }
    val offers = rows.drop(4).map { row ->
        require(row.size==5)
        ShopExpansion(rules,trial,int(row[0]),int(row[1]),int(row[2]),int(row[3]),GameMoney(integer(row[4])))
    }
    require(offers.first().fromLevel==1)
    require(offers.zipWithNext().all { (a,b) -> b.fromLevel==a.fromLevel+1 && b.currentCapacity==a.nextCapacity && b.requiredCustomers>=a.requiredCustomers })
    ContractResult.Success(ShopExpansionDefinition(version,rules,trial,offers))
} catch (_: IllegalArgumentException) { ContractResult.Failure(ContractError.INVALID_INPUT) }
  catch (_: IllegalStateException) { ContractResult.Failure(ContractError.INVALID_INPUT) }
