package io.drivegrowth.app.host

import io.drivegrowth.core.contract.*
import io.drivegrowth.core.content.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class GameSessionPolicyTest {
    @Test fun newInstallUsesExplicitVehicleButExistingSimulationIsPreserved() {
        assertEquals(ContractResult.Success(InputSource.VEHICLE),chooseGameSource(emptyMap(),InputSource.VEHICLE))
        assertEquals(ContractResult.Success(InputSource.SIMULATION),chooseGameSource(mapOf(InputSource.SIMULATION to ContractResult.Success(InputSource.SIMULATION)),InputSource.VEHICLE))
    }
    @Test fun bothExistingModesRequireExplicitResolutionAndNeverCreateAnotherDatabase() {
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),chooseGameSource(InputSource.entries.associateWith{ContractResult.Success(it)},InputSource.VEHICLE))
    }
    @Test fun malformedOrWrongSourceExistingFileFailsInsteadOfNewProfile() {
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),chooseGameSource(mapOf(InputSource.SIMULATION to ContractResult.Failure(ContractError.STORAGE_FAILURE)),InputSource.VEHICLE))
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),chooseGameSource(mapOf(InputSource.SIMULATION to ContractResult.Success(InputSource.VEHICLE)),InputSource.VEHICLE))
    }
    @Test fun gachaAuthorityUsesRealShopAndSameContentInsteadOfFixtureSpace() {
        val c=(GameContentLoader.load(File("src/main/assets/content/vehicle-facts.csv").readText(),File("src/main/assets/content/game-content.csv").readText()) as ContentLoad.Loaded).snapshot
        val authority=(gameGachaAuthority(c,InputSource.VEHICLE,2,7) as ContractResult.Success).value
        assertEquals(2,authority.shopLevel);assertEquals(7,authority.garageCapacity)
        assertEquals(c.unlockedAt(2).map{it.vehicleId}.toSet(),authority.unlockedVehicles)
        assertEquals(c.catalog.contentVersion,authority.contentVersion);assertEquals(c.ruleVersion,authority.ruleVersion)
        assertEquals(GameMoney(500),authority.purchasePrice)
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),gameGachaAuthority(c.copy(vehicles=emptyList()),InputSource.VEHICLE,2,7))
    }

    @Test fun onlyKnownLockedCandidatesGiveSpecificGachaFailure() {
        val content = (GameContentLoader.load(File("src/main/assets/content/vehicle-facts.csv").readText(),
            File("src/main/assets/content/game-content.csv").readText()) as ContentLoad.Loaded).snapshot
        val locked = content.copy(vehicles = content.vehicles.map { it.copy(unlockShopLevel = 2) })
        val unavailable = ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        assertEquals(unavailable.copy(detail = ContractFailureDetail.GACHA_NO_UNLOCKED_CANDIDATES),
            gameGachaAuthority(locked, InputSource.VEHICLE, 1, 3))
        assertEquals(unavailable, gameGachaAuthority(locked, InputSource.VEHICLE, 0, 3))
        assertEquals(unavailable, gameGachaAuthority(locked, InputSource.VEHICLE, 1, 0))
        val missingCatalog = locked.copy(catalog = locked.catalog.copy(vehicles = emptyList()))
        assertEquals(unavailable, gameGachaAuthority(missingCatalog, InputSource.VEHICLE, 1, 3))
    }
}
