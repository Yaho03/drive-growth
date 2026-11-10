package io.drivegrowth.app.data

import android.content.res.AssetManager
import io.drivegrowth.core.content.ContentLoad
import io.drivegrowth.core.contract.*
import java.io.IOException

/** 단일 버전 asset에서 숫자를 공급한다. 기존 차량 콘텐츠와 ID 전체를 대조하며 빈 기본값을 만들지 않는다. */
class AssetTrialDefinitionProvider internal constructor(
    private val read:()->String, private val knownVehicles:()->ContractResult<Set<VehicleId>>,
) : TrialDefinitionSource {
    constructor(assets:AssetManager):this(
        {assets.open("content/trial-definitions.csv").bufferedReader(Charsets.UTF_8).use{it.readText()}},
        {when(val r=AssetGameContent(assets).load()) {
            is ContentLoad.Loaded->ContractResult.Success(r.snapshot.catalog.vehicles.map{it.vehicleId}.toSet())
            is ContentLoad.Rejected->ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        }})
    override fun load():ContractResult<TrialDefinition> {
        return try {
            val text=read()
            val known=when(val r=knownVehicles()){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
            parseTrialDefinition(text,known)
        } catch (_:IOException) {ContractResult.Failure(ContractError.DATA_UNAVAILABLE)}
          catch (_:SecurityException) {ContractResult.Failure(ContractError.PERMISSION_DENIED)}
    }
}
