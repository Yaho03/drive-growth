package io.drivegrowth.app.data

import android.content.res.AssetManager
import io.drivegrowth.app.host.shop.*
import io.drivegrowth.core.contract.*
import java.io.IOException

/** 동의된 확장 시험 정의만 읽는다. 파일 실패를 임의 금액/기본 단계로 채우지 않는다. */
class AssetShopExpansionProvider internal constructor(private val read: () -> String) : ShopExpansionDefinitionSource {
    constructor(assets: AssetManager) : this({ assets.open("content/shop-expansions.csv").bufferedReader(Charsets.UTF_8).use { it.readText() } })
    override fun load(): ContractResult<ShopExpansionDefinition> = try { parseShopExpansionDefinition(read()) }
        catch (_: IOException) { ContractResult.Failure(ContractError.DATA_UNAVAILABLE) }
}
