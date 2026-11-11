package io.drivegrowth.app.host.shop

import android.os.Bundle
import io.drivegrowth.app.data.HostCheckpointCommand
import io.drivegrowth.app.data.HostCheckpointJson
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.facility.FacilityCommand
import org.json.JSONException
import io.drivegrowth.app.ui.shop.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.*
import java.util.Collections

/** 표시·선택·원요청 식별만 보존한다. 저장 성공·현재 소유·차량 허용 상태를 만들어내지 않는다. */
@Suppress("DEPRECATION")
object ShopUiStateBundle {
    fun encode(state: ShopUiState, facility: FacilityCommand.Buy? = null): Bundle = Bundle().apply {
        facility?.let {
            require(matchesFacility(state, it)) { "시설 원명령과 화면 보관 요청 불일치" }
            putString("facilityOriginalCommand", HostCheckpointJson.encode(it.identity.profileId,
                HostCheckpointCommand.FacilityPurchase(it.requestId, it.identity.source, it.definitionVersion, it.offer)))
        }
        putInt("version", 1); putString("mode", state.mode.name)
        state.selection?.let { putBundle("selected", selection(it)) }
        state.pending?.let { putBundle("pending", request(it)) }
        state.acknowledged?.let { putBundle("acknowledged", selection(it)) }
        putParcelableArrayList("requests", ArrayList(state.requestIds.map { (selected, id) ->
            selection(selected).apply { putString("request", id.value) }
        }))
        state.latestRevision?.let { putInt("latest", it.value) }
        state.message?.let { putString("message", it) }
        putBoolean("retry", state.retryPending)
        putInt("sceneY", state.sceneScrollY); putInt("controlsY", state.controlsScrollY)
    }

    /** Activity 상태에 키가 존재하면 null도 손상이다. 키 부재의 새 상태와 구분한다. */
    fun decodePresent(bundle: Bundle?): ContractResult<ShopUiState> =
        if (bundle == null) ContractResult.Failure(ContractError.INVALID_INPUT) else decode(bundle)

    fun decode(bundle: Bundle?): ContractResult<ShopUiState> {
        if (bundle == null) return ContractResult.Success(ShopUiState())
        return try {
            require(integer(bundle, "version") == 1)
            val requests = linkedMapOf<ShopSelection, RequestId>()
            for (row in entries(bundle, "requests")) {
                val selected = readSelection(row)
                require(selected !in requests)
                requests[selected] = RequestId(text(row, "request", nonBlank = true))
            }
            ContractResult.Success(ShopUiState(
                mode = ShopMode.valueOf(text(bundle, "mode")),
                selection = nested(bundle, "selected")?.let(::readSelection),
                pending = nested(bundle, "pending")?.let(::readRequest),
                acknowledged = nested(bundle, "acknowledged")?.let(::readSelection),
                requestIds = Collections.unmodifiableMap(requests),
                latestRevision = optionalInt(bundle, "latest")?.let(::Revision),
                message = optionalText(bundle, "message"),
                retryPending = boolean(bundle, "retry"),
                sceneScrollY = integer(bundle, "sceneY").also { require(it >= 0) },
                controlsScrollY = integer(bundle, "controlsY").also { require(it >= 0) },
            ))
        } catch (_: RuntimeException) { ContractResult.Failure(ContractError.INVALID_INPUT) }
    }

    /** 키 부재는 구형 UI의 원명령 정보 미제공이다. 실행 완료나 현재 정의로의 재구성을 뜻하지 않는다. */
    fun decodeFacility(bundle: Bundle?): ContractResult<FacilityCommand.Buy?> {
        val state = when (val decoded = decode(bundle)) {
            is ContractResult.Failure -> return decoded
            is ContractResult.Success -> decoded.value
        }
        if (bundle == null || !bundle.containsKey("facilityOriginalCommand")) return ContractResult.Success(null)
        return try {
            val (profile, stored) = HostCheckpointJson.decode(text(bundle, "facilityOriginalCommand", nonBlank = true))
            val value = stored as? HostCheckpointCommand.FacilityPurchase ?: error("시설 구매 원명령 종류 오류")
            val command = FacilityCommand.Buy(GameIdentity(profile, value.source), value.requestId, value.definitionVersion, value.offer)
            require(matchesFacility(state, command)) { "시설 원명령과 화면 보관 요청 불일치" }
            ContractResult.Success(command)
        } catch (_: JSONException) { ContractResult.Failure(ContractError.INVALID_INPUT) }
          catch (_: RuntimeException) { ContractResult.Failure(ContractError.INVALID_INPUT) }
    }

    private fun matchesFacility(state: ShopUiState, command: FacilityCommand.Buy): Boolean {
        val pending = state.pending ?: return false
        return pending.requestId == command.requestId &&
            (pending.selection.option.command as? ShopCommand.BuyFacility)?.offer == command.offer
    }

    private fun selection(value: ShopSelection) = Bundle().apply {
        putInt("revision", value.revision.value); putBundle("option", option(value.option))
    }
    private fun request(value: ShopRequest) = Bundle().apply {
        putString("request", value.requestId.value); putString("attempt", value.attemptId)
        putBundle("selection", selection(value.selection))
    }
    private fun option(value: ShopOption) = Bundle().apply {
        putString("id", value.id); putString("title", value.title)
        putString("condition", value.condition); putString("effect", value.effect)
        putString("availability", value.availability.name); putBoolean("trial", value.trial)
        value.price?.let { putLong("price", it.units) }
        value.ruleVersion?.let { putString("rule", it.value) }
        value.command?.let { putBundle("command", command(it)) }
    }
    private fun command(value: ShopCommand) = Bundle().apply {
        when (value) {
            is ShopCommand.Decorate -> {
                putString("kind", "decorate"); putString("id", value.decorationId)
                putString("sign", value.appearance.sign); putInt("accent", value.appearance.accentArgb)
            }
            is ShopCommand.Expand -> {
                val offer = value.offer
                putString("kind", "expand"); putString("rule", offer.ruleVersion.value)
                putBoolean("trial", offer.trial); putLong("price", offer.price.units)
                putInt("fromLevel", offer.fromLevel); putInt("currentCapacity", offer.currentCapacity)
                putInt("nextCapacity", offer.nextCapacity); putInt("requiredCustomers", offer.requiredCustomers)
            }
            is ShopCommand.BuyFacility -> {
                val offer = value.offer
                putString("kind", "facility"); putString("rule", offer.ruleVersion.value)
                putBoolean("trial", offer.trial); putLong("price", offer.price.units)
                putString("facility", offer.facility.name)
            }
        }
    }
    private fun readSelection(value: Bundle) = ShopSelection(Revision(integer(value, "revision")), readOption(requiredNested(value, "option")))
    private fun readRequest(value: Bundle) = ShopRequest(RequestId(text(value, "request", nonBlank = true)),
        readSelection(requiredNested(value, "selection")), text(value, "attempt", nonBlank = true))
    private fun readOption(value: Bundle) = ShopOption(
        text(value, "id", nonBlank = true), text(value, "title"), text(value, "condition"), text(value, "effect"),
        ShopAvailability.valueOf(text(value, "availability")), nested(value, "command")?.let(::readCommand),
        if (value.containsKey("price")) money(value, "price") else null,
        optionalText(value, "rule")?.let(::RuleVersion), boolean(value, "trial"),
    )
    private fun readCommand(value: Bundle): ShopCommand = when (text(value, "kind")) {
        "decorate" -> ShopCommand.Decorate(text(value, "id", nonBlank = true), ShopAppearance(text(value, "sign"), integer(value, "accent")))
        "expand" -> ShopCommand.Expand(ShopExpansion(RuleVersion(text(value, "rule", nonBlank = true)), boolean(value, "trial"),
            integer(value, "fromLevel"), integer(value, "currentCapacity"), integer(value, "nextCapacity"),
            integer(value, "requiredCustomers"), money(value, "price")))
        "facility" -> ShopCommand.BuyFacility(FacilityOffer(Facility.valueOf(text(value, "facility")),
            RuleVersion(text(value, "rule", nonBlank = true)), boolean(value, "trial"), money(value, "price")))
        else -> error("가게 명령 종류 오류")
    }
    private fun integer(value: Bundle, key: String): Int = value.get(key) as? Int ?: error("가게 정수 누락/형식 오류")
    private fun optionalInt(value: Bundle, key: String): Int? = if (value.containsKey(key)) integer(value, key) else null
    private fun money(value: Bundle, key: String): GameMoney = GameMoney(value.get(key) as? Long ?: error("가게 금액 누락/형식 오류"))
    private fun boolean(value: Bundle, key: String): Boolean = value.get(key) as? Boolean ?: error("가게 상태 누락/형식 오류")
    private fun text(value: Bundle, key: String, nonBlank: Boolean = false): String {
        val result = value.get(key) as? String ?: error("가게 문자열 누락/형식 오류")
        require(!nonBlank || result.isNotBlank()); return result
    }
    private fun optionalText(value: Bundle, key: String): String? = if (value.containsKey(key)) text(value, key) else null
    private fun nested(value: Bundle, key: String): Bundle? = if (!value.containsKey(key)) null
        else value.get(key) as? Bundle ?: error("가게 중첩 상태 형식 오류")
    private fun requiredNested(value: Bundle, key: String) = nested(value, key) ?: error("가게 중첩 상태 누락")
    private fun entries(value: Bundle, key: String): List<Bundle> {
        val rows = value.get(key) as? ArrayList<*> ?: error("가게 요청 이력 누락/형식 오류")
        require(rows.all { it is Bundle }); return rows.filterIsInstance<Bundle>()
    }
}
