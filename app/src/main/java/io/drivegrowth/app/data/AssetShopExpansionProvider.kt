package io.drivegrowth.app.data

import android.content.res.AssetManager
import io.drivegrowth.app.host.shop.*
import io.drivegrowth.core.contract.*

/** 확장 숫자를 별도 복제하지 않고 단일 trial 정의를 위임 소비한다. */
class AssetShopExpansionProvider(assets:AssetManager) : ShopExpansionDefinitionSource {
    private val definitions=AssetTrialDefinitionProvider(assets)
    override fun load():ContractResult<ShopExpansionDefinition> = when(val r=definitions.load()) {
        is ContractResult.Failure->r
        is ContractResult.Success->ContractResult.Success(r.value.expansions)
    }
}
