package io.drivegrowth.app.host

import io.drivegrowth.app.data.FirstCollectionOwnership
import io.drivegrowth.app.data.FirstGachaReceipt
import io.drivegrowth.core.contract.EpochMillis
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.OwnedVehicle
import io.drivegrowth.core.contract.OwnedVehicleId
import io.drivegrowth.core.contract.RequestId
import io.drivegrowth.core.contract.Revision
import io.drivegrowth.core.contract.RuleVersion
import io.drivegrowth.core.contract.Settlement
import io.drivegrowth.core.contract.SettlementId
import io.drivegrowth.core.contract.Transaction
import io.drivegrowth.core.contract.TransactionId
import io.drivegrowth.core.contract.TransactionKind
import io.drivegrowth.core.contract.VehicleAcquisitionSource
import io.drivegrowth.core.contract.VehicleLifecycle
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.ProfileId
import io.drivegrowth.core.contract.Rarity
import io.drivegrowth.core.contract.VehicleId
import io.drivegrowth.core.garage.CollectionKey
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionNewPresentationTest {
    @Test
    fun confirmedKeyIsDisplayedOnceForMatchingProfileAndSource() {
        val state = CollectionNewPresentation()
        val profile = ProfileId("profile")
        val identity = GameIdentity(profile, InputSource.SIMULATION)
        val key = CollectionKey(VehicleId("hyundai_casper"), Rarity.COMMON)
        val event = FirstCollectionOwnership(profile, identity.source, key)
        assertFalse(state.take(identity, key))
        assertTrue(state.offer(event))
        assertFalse(state.offer(event))
        assertFalse(state.take(identity.copy(profileId = ProfileId("other")), key))
        assertFalse(state.take(identity.copy(source = InputSource.VEHICLE), key))
        assertFalse(state.take(identity, key.copy(rarity = Rarity.RARE)))
        assertTrue(state.take(identity, key))
        assertFalse(state.offer(event))
        assertFalse(state.take(identity, key))
        assertFalse(CollectionNewPresentation().take(identity, key))
    }

    @Test
    fun bothSurfacesConsumeTheSameConfirmedReceiptIndependentlyInEitherOrder() {
        val identity = GameIdentity(ProfileId("profile"), InputSource.SIMULATION)
        val key = CollectionKey(VehicleId("car"), Rarity.COMMON)
        val car = OwnedVehicle(OwnedVehicleId("award"), key.vehicleId, 1, Revision(0), VehicleLifecycle.OWNED,
            key.rarity, 0, GameMoney(0), VehicleAcquisitionSource.GACHA)
        val tx = Transaction(TransactionId("tx"), RequestId("draw"), TransactionKind.GARAGE, identity.source,
            RuleVersion("v1"), null)
        val settlement = Settlement(SettlementId("receipt"), tx, GameMoney(0), car, EpochMillis(1))
        val event = FirstCollectionOwnership(identity.profileId, identity.source, key,
            FirstGachaReceipt(tx.requestId, settlement.settlementId, car.ownedVehicleId))
        for (collectionFirst in listOf(true, false)) {
            val state = CollectionNewPresentation()
            assertFalse(state.takeGacha(identity, settlement))
            assertTrue(state.offer(event))
            if (collectionFirst) assertTrue(state.take(identity, key))
            assertFalse(state.takeGacha(identity.copy(profileId = ProfileId("other")), settlement))
            assertFalse(state.takeGacha(identity.copy(source = InputSource.VEHICLE), settlement))
            assertFalse(state.takeGacha(identity, settlement.copy(transaction = tx.copy(requestId = RequestId("other")))))
            assertFalse(state.takeGacha(identity, settlement.copy(settlementId = SettlementId("other"))))
            assertFalse(state.takeGacha(identity, settlement.copy(changedVehicle = car.copy(ownedVehicleId = OwnedVehicleId("other")))))
            assertFalse(state.takeGacha(identity, settlement.copy(changedVehicle = car.copy(rarity = Rarity.RARE))))
            assertTrue(state.takeGacha(identity, settlement))
            assertFalse(state.takeGacha(identity, settlement))
            assertFalse(state.offer(event))
            if (!collectionFirst) assertTrue(state.take(identity, key))
            assertFalse(state.take(identity, key))
        }
        val replay = CollectionNewPresentation()
        assertTrue(replay.offer(event))
        replay.discardGachaRequest(identity, tx.requestId)
        assertFalse(replay.takeGacha(identity, settlement))
        assertTrue(replay.take(identity, key))
    }
}
