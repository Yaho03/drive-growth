package io.drivegrowth.app.host.shop

import android.os.Bundle
import android.os.Parcel
import android.test.InstrumentationTestCase
import io.drivegrowth.app.ui.shop.*
import io.drivegrowth.app.data.HostCheckpointCommand
import io.drivegrowth.app.data.HostCheckpointJson
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.facility.FacilityCommand
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.*

@Suppress("DEPRECATION")
class ShopUiStateBundleTest : InstrumentationTestCase() {
    private val rule = RuleVersion("shop-bundle-test-v1")
    private val appearance = ShopAppearance("승인된 간판", 0xff8fbcff.toInt())
    private fun decoration(id: String, price: Long) = ShopOption("decoration:$id", id, "조건", "효과",
        ShopAvailability.AVAILABLE, ShopCommand.Decorate(id, appearance), GameMoney(price), rule, true)
    private fun selection(option: ShopOption) = ShopSelection(Revision(17), option)
    private fun request(option: ShopOption) = ShopRequest(RequestId("shop/request"), selection(option), "attempt/2")
    private fun parcelCopy(bundle: Bundle): Bundle {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(bundle); parcel.setDataPosition(0)
            checkNotNull(parcel.readBundle(javaClass.classLoader))
        } finally { parcel.recycle() }
    }
    private fun roundTrip(state: ShopUiState) = ShopUiStateBundle.decode(parcelCopy(ShopUiStateBundle.encode(state)))
    private fun invalid(bundle: Bundle) = assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), ShopUiStateBundle.decode(bundle))

    fun testPresentNullActivityBundleIsRejectedWhileAbsentStateRemainsNew() {
        val state = Bundle().apply { putBundle("shopUi", null) }
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), ShopUiStateBundle.decodePresent(state.getBundle("shopUi")))
        state.putString("shopUi", "wrong type")
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), ShopUiStateBundle.decodePresent(state.getBundle("shopUi")))
        assertEquals(ContractResult.Success(ShopUiState()), ShopUiStateBundle.decode(null))
    }

    fun testNullAndNullableDisplayInformationRemainDistinctFromFreePrice() {
        assertEquals(ContractResult.Success(ShopUiState()), ShopUiStateBundle.decode(null))
        assertEquals(ContractResult.Success(ShopUiState()), roundTrip(ShopUiState()))
        val missing = ShopOption("missing", "", "", "", ShopAvailability.UNKNOWN)
        val state = ShopUiState(selection = selection(missing), message = "")
        assertEquals(ContractResult.Success(state), roundTrip(state))
        val free = ShopUiState(selection = selection(decoration("free", 0)))
        assertEquals(ContractResult.Success(free), roundTrip(free))
        assertNull(state.selection!!.option.price)
        assertEquals(GameMoney(0), free.selection!!.option.price)
    }

    fun testFullStateAndEveryCommandRoundTripThroughParcelWithoutCreatingAResult() {
        val expansion = ShopExpansion(rule, true, 2, 5, 8, 7, GameMoney(900))
        val facility = FacilityOffer(Facility.CAR_WASH, rule, true, GameMoney(800))
        val options = listOf(decoration("free", 0), decoration("paid", 100),
            ShopOption("expand", "확장", "조건", "효과", ShopAvailability.AVAILABLE,
                ShopCommand.Expand(expansion), expansion.price, rule, true),
            ShopOption("facility", "시설", "조건", "효과", ShopAvailability.AVAILABLE,
                ShopCommand.BuyFacility(facility), facility.price, rule, true))
        for (option in options) {
            val selected = selection(option)
            val state = ShopUiState(ShopMode.VEHICLES, selected, request(option), selection(options.first()),
                linkedMapOf(selected to RequestId("retry/original"), selection(ShopOption("unknown", "알 수 없음", "", "", ShopAvailability.UNKNOWN)) to RequestId("retry/other")),
                Revision(19), "보관한 요청", true, 211, 422)
            assertEquals(ContractResult.Success(state), roundTrip(state))
        }
    }

    fun testEncodingAndDecodedRequestMapDoNotShareMutableInput() {
        val selected = selection(decoration("free", 0))
        val requests = linkedMapOf(selected to RequestId("original"))
        val bundle = ShopUiStateBundle.encode(ShopUiState(requestIds = requests))
        requests.clear()
        val decoded = ShopUiStateBundle.decode(parcelCopy(bundle)) as ContractResult.Success
        assertEquals(mapOf(selected to RequestId("original")), decoded.value.requestIds)
        try {
            (decoded.value.requestIds as MutableMap<ShopSelection, RequestId>).clear()
            fail("복원한 요청 이력은 외부에서 변경할 수 없어야 한다")
        } catch (_: UnsupportedOperationException) { }
        bundle.putParcelableArrayList("requests", arrayListOf<Bundle>())
        assertEquals(mapOf(selected to RequestId("original")), decoded.value.requestIds)
    }

    fun testMissingOrWrongTypedRequiredFieldsAreRejectedRatherThanDefaulted() {
        for (key in listOf("version", "mode", "requests", "retry", "sceneY", "controlsY")) {
            invalid(ShopUiStateBundle.encode(ShopUiState()).apply { remove(key) })
        }
        invalid(ShopUiStateBundle.encode(ShopUiState()).apply { putString("version", "1") })
        invalid(ShopUiStateBundle.encode(ShopUiState()).apply { putInt("version", 2) })
        invalid(ShopUiStateBundle.encode(ShopUiState()).apply { putString("mode", "UNKNOWN") })
        invalid(ShopUiStateBundle.encode(ShopUiState()).apply { putInt("retry", 1) })
        invalid(ShopUiStateBundle.encode(ShopUiState()).apply { putString("pending", "lost-command") })
        invalid(ShopUiStateBundle.encode(ShopUiState()).apply { putString("message", null) })
        invalid(ShopUiStateBundle.encode(ShopUiState()).apply { putInt("latest", -1) })
        invalid(ShopUiStateBundle.encode(ShopUiState()).apply { putLong("latest", 1L) })
        invalid(ShopUiStateBundle.encode(ShopUiState()).apply { putInt("sceneY", -1) })
        invalid(ShopUiStateBundle.encode(ShopUiState()).apply { putInt("controlsY", -1) })
    }

    fun testMalformedNestedCommandsAmountsAndDuplicateRequestKeysAreRejected() {
        fun selected() = ShopUiStateBundle.encode(ShopUiState(selection = selection(decoration("paid", 100))))
        invalid(selected().apply { getBundle("selected")!!.putInt("revision", -1) })
        invalid(selected().apply { getBundle("selected")!!.getBundle("option")!!.putLong("price", -1) })
        invalid(selected().apply { getBundle("selected")!!.getBundle("option")!!.putInt("price", 100) })
        invalid(selected().apply { getBundle("selected")!!.getBundle("option")!!.putString("rule", "") })
        invalid(selected().apply { getBundle("selected")!!.getBundle("option")!!.remove("availability") })
        invalid(selected().apply { getBundle("selected")!!.getBundle("option")!!.getBundle("command")!!.putString("kind", "NEW_KIND") })
        invalid(selected().apply { getBundle("selected")!!.getBundle("option")!!.getBundle("command")!!.putString("id", "") })
        val pending = ShopUiStateBundle.encode(ShopUiState(pending = request(decoration("free", 0))))
        invalid(pending.apply { getBundle("pending")!!.putString("attempt", "") })
        invalid(ShopUiStateBundle.encode(ShopUiState(pending = request(decoration("free", 0)))).apply {
            getBundle("pending")!!.putString("request", "")
        })
        val duplicate = ShopUiStateBundle.encode(ShopUiState(requestIds = mapOf(selection(decoration("free", 0)) to RequestId("first"))))
        val row = duplicate.getParcelableArrayList<Bundle>("requests")!!.single()
        duplicate.putParcelableArrayList("requests", arrayListOf(row, Bundle(row).apply { putString("request", "other") }))
        invalid(duplicate)
    }

    fun testExpansionAndFacilityPayloadMustMatchDisplayedPriceRuleAndTrial() {
        val offers = listOf<ShopCommand>(ShopCommand.Expand(ShopExpansion(rule, true, 1, 3, 5, 3, GameMoney(500))),
            ShopCommand.BuyFacility(FacilityOffer(Facility.FUEL_STATION, rule, true, GameMoney(500))))
        for (command in offers) {
            val option = ShopOption("offer", "항목", "조건", "효과", ShopAvailability.AVAILABLE, command, GameMoney(500), rule, true)
            fun bundle() = ShopUiStateBundle.encode(ShopUiState(selection = selection(option)))
            invalid(bundle().apply { getBundle("selected")!!.getBundle("option")!!.putLong("price", 499) })
            invalid(bundle().apply { getBundle("selected")!!.getBundle("option")!!.putString("rule", "different") })
            invalid(bundle().apply { getBundle("selected")!!.getBundle("option")!!.putBoolean("trial", false) })
        }
    }

    private fun facilityOriginal() = FacilityCommand.Buy(GameIdentity(ProfileId("bundle-profile"), InputSource.SIMULATION),
        RequestId("facility/original"), ContentVersion("definition-independent-v7"),
        FacilityOffer(Facility.CAR_WASH, RuleVersion("rules-independent-v2"), true, GameMoney(100)))
    private fun facilityUi(command: FacilityCommand.Buy): ShopUiState {
        val offer = command.offer
        val option = ShopOption("facility:${offer.facility.name}", "시설", "실제 조건", "실제 효과", ShopAvailability.AVAILABLE,
            ShopCommand.BuyFacility(offer), offer.price, offer.ruleVersion, offer.trial)
        return ShopUiState(pending = ShopRequest(command.requestId, selection(option), "facility-attempt/2"), latestRevision = Revision(17))
    }
    private fun encodedFacility(command: FacilityCommand.Buy) = HostCheckpointJson.encode(command.identity.profileId,
        HostCheckpointCommand.FacilityPurchase(command.requestId, command.identity.source, command.definitionVersion, command.offer))
    private fun invalidFacility(bundle: Bundle?) = assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), ShopUiStateBundle.decodeFacility(bundle))

    fun testFacilityOriginalPreservesIndependentDefinitionAndRuleVersionsThroughParcel() {
        val command = facilityOriginal()
        assertFalse(command.definitionVersion.value == command.offer.ruleVersion.value)
        val state = facilityUi(command)
        val copy = parcelCopy(ShopUiStateBundle.encode(state, command))
        assertEquals(ContractResult.Success(state), ShopUiStateBundle.decode(copy))
        assertEquals(encodedFacility(command), copy.getString("facilityOriginalCommand"))
        assertEquals(ContractResult.Success(command), ShopUiStateBundle.decodeFacility(copy))
    }

    fun testFacilityOriginalRequiresMatchingPendingRequestAndEntireOffer() {
        val command = facilityOriginal(); val state = facilityUi(command)
        val wrong = listOf(command.copy(requestId = RequestId("different-request")),
            command.copy(offer = command.offer.copy(price = GameMoney(101))),
            command.copy(offer = command.offer.copy(ruleVersion = RuleVersion("different-rules"))),
            command.copy(offer = command.offer.copy(trial = false)),
            command.copy(offer = command.offer.copy(facility = Facility.INSURANCE)))
        for (other in wrong) {
            try { ShopUiStateBundle.encode(state, other); fail("다른 원명령을 UI 요청에 연결하면 안 된다") }
            catch (_: IllegalArgumentException) { }
            invalidFacility(ShopUiStateBundle.encode(state).apply { putString("facilityOriginalCommand", encodedFacility(other)) })
        }
        for (missing in listOf(ShopUiState(), state.copy(pending = null), ShopUiState(pending = request(decoration("free", 0))))) {
            try { ShopUiStateBundle.encode(missing, command); fail("일치하는 시설 pending 없이 원명령을 저장하면 안 된다") }
            catch (_: IllegalArgumentException) { }
            invalidFacility(ShopUiStateBundle.encode(missing).apply { putString("facilityOriginalCommand", encodedFacility(command)) })
        }
    }

    fun testFacilityOriginalRejectsPresentMalformedWrongKindNullAndNoncanonicalPayloads() {
        val command = facilityOriginal(); val state = facilityUi(command); val canonical = encodedFacility(command)
        val wrongKind = HostCheckpointJson.encode(command.identity.profileId,
            HostCheckpointCommand.VehicleResting(command.requestId, command.identity.source,
                io.drivegrowth.core.garage.VehicleRef(OwnedVehicleId("vehicle"), Revision(0)), false, true))
        val invalid = listOf("{broken", canonical + " ", canonical.replace("\"price\":100", "\"price\":100.5"),
            canonical.replace("\"command\":{", "\"command\":null,\"discarded\":{"),
            canonical.replace("\"price\":100", "\"price\":{\"value\":100}"), wrongKind)
        assertTrue(invalid.all { it != canonical })
        for (payload in invalid) invalidFacility(ShopUiStateBundle.encode(state).apply { putString("facilityOriginalCommand", payload) })
        invalidFacility(ShopUiStateBundle.encode(state).apply { putString("facilityOriginalCommand", null) })
        invalidFacility(ShopUiStateBundle.encode(state).apply { putBundle("facilityOriginalCommand", Bundle()) })
    }

    fun testAbsentFacilityMetadataIsUnknownOriginalAndDoesNotHideInvalidUiState() {
        assertEquals(ContractResult.Success<FacilityCommand.Buy?>(null), ShopUiStateBundle.decodeFacility(null))
        val state = facilityUi(facilityOriginal())
        val legacy = parcelCopy(ShopUiStateBundle.encode(state))
        assertFalse(legacy.containsKey("facilityOriginalCommand"))
        assertEquals(ContractResult.Success(state), ShopUiStateBundle.decode(legacy))
        assertEquals(ContractResult.Success<FacilityCommand.Buy?>(null), ShopUiStateBundle.decodeFacility(legacy))
        invalidFacility(legacy.apply { remove("mode") })
    }
}
