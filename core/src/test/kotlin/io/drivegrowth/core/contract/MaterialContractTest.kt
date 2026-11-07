package io.drivegrowth.core.contract

import io.drivegrowth.core.content.GameContentFixtures
import io.drivegrowth.core.garage.VehicleRef
import io.drivegrowth.core.garage.VehicleUpgrade
import io.drivegrowth.core.progression.VehicleActivity
import org.junit.Assert.*
import org.junit.Test

/** 순수 명령/검증/소비 예제다. 원장 커밋·원자 경쟁·SQLite를 실행하지 않는다. */
class MaterialContractTest {
    private val content = GameContentFixtures.loaded()
    private val target = RentalContractFixtures.firstVehicle.copy(vehicleId = VehicleId("hyundai_casper"))
    private val material = RentalContractFixtures.secondVehicle.copy(vehicleId = target.vehicleId)
    private val current = MaterialState(InputSource.SIMULATION, content.catalog.contentVersion, content.ruleVersion, GameMoney(1000),
        MaterialVehicleState(target, VehicleActivity.IDLE), MaterialVehicleState(material, VehicleActivity.IDLE))
    private fun command(cost: Long = 0) = MaterialCommand(RequestId("material-1"), current.source, current.contentVersion,
        current.ruleVersion, VehicleWrite.Update(target.ownedVehicleId, target.revision, 3, VehicleLifecycle.OWNED, 50),
        VehicleWrite.Update(material.ownedVehicleId, material.revision, material.level, VehicleLifecycle.CONSUMED,
            material.levelProgressPercent), GameMoney(cost))
    private fun <T> ok(result: ContractResult<T>): T = (result as ContractResult.Success).value

    @Test fun `기존 계획의 두 개체를 한 명령에 표현하고 권위 취득 정보와 역할을 보존한다`() {
        val command = command()
        val result = prepareMaterialChange(command, current)
        assertTrue("두 개체 준비 성공", result is ContractResult.Success)
        val prepared = ok(result)
        assertEquals(command, prepared.command)
        assertEquals(target.copy(level = 3, levelProgressPercent = 50, revision = Revision(4)), prepared.target)
        assertEquals(material.copy(lifecycle = VehicleLifecycle.CONSUMED, revision = Revision(4)), prepared.material)
        assertEquals(current.balance, prepared.balance)
        assertNull(prepared.money)
        assertEquals(1, target.level)
        assertEquals(VehicleLifecycle.OWNED, material.lifecycle)
    }

    @Test fun `같은 개체 중복과 뒤바뀐 역할은 거절한다`() {
        val command = command()
        val cases = listOf(command.copy(material = command.material.copy(ownedVehicleId = target.ownedVehicleId)),
            command.copy(target = command.material, material = command.target),
            command.copy(target = command.target.copy(lifecycle = VehicleLifecycle.SOLD)),
            command.copy(material = command.material.copy(lifecycle = VehicleLifecycle.SCRAPPED)))
        cases.forEach { assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), prepareMaterialChange(it, current)) }
    }

    @Test fun `한쪽 revision이 다르거나 증가할 수 없으면 전부 거절한다`() {
        val command = command()
        for (bad in listOf(command.copy(target = command.target.copy(expectedRevision = Revision(2))),
            command.copy(material = command.material.copy(expectedRevision = Revision(2))))) {
            assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), prepareMaterialChange(bad, current))
        }
        for (targetMax in listOf(true, false)) {
            val state = if (targetMax) current.copy(target = current.target.copy(vehicle = target.copy(revision = Revision(Int.MAX_VALUE))))
                else current.copy(material = current.material.copy(vehicle = material.copy(revision = Revision(Int.MAX_VALUE))))
            val request = if (targetMax) command.copy(target = command.target.copy(expectedRevision = Revision(Int.MAX_VALUE)))
                else command.copy(material = command.material.copy(expectedRevision = Revision(Int.MAX_VALUE)))
            assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), prepareMaterialChange(request, state))
        }
        assertEquals(Revision(3), current.target.vehicle.revision)
        assertEquals(Revision(3), current.material.vehicle.revision)
    }

    @Test fun `대상 재료 각각의 사용 중과 수리 필요는 허용하지 않는다`() {
        for (activity in listOf(VehicleActivity.RENTED, VehicleActivity.RETURNING, VehicleActivity.NEEDS_REPAIR)) {
            for (state in listOf(current.copy(target = current.target.copy(activity = activity)),
                current.copy(material = current.material.copy(activity = activity)))) {
                assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), prepareMaterialChange(command(), state))
            }
        }
    }

    @Test fun `다른 차종 처분 개체와 역할에 맞지 않는 조회는 구분한다`() {
        val another = current.copy(material = current.material.copy(vehicle = material.copy(vehicleId = VehicleId("other"))))
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), prepareMaterialChange(command(), another))
        for (state in listOf(current.copy(target = current.target.copy(vehicle = target.copy(lifecycle = VehicleLifecycle.SOLD))),
            current.copy(material = current.material.copy(vehicle = material.copy(lifecycle = VehicleLifecycle.CONSUMED))),
            current.copy(target = current.material, material = current.target))) {
            assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), prepareMaterialChange(command(), state))
        }
    }

    @Test fun `대상은 성장해야 하고 재료의 원래 성장값은 바꿀 수 없다`() {
        val command = command()
        for (bad in listOf(command.copy(target = command.target.copy(level = 1, levelProgressPercent = 0)),
            command.copy(material = command.material.copy(level = 5)),
            command.copy(material = command.material.copy(levelProgressPercent = 49)))) {
            assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), prepareMaterialChange(bad, current))
        }
        val max = current.copy(target = current.target.copy(vehicle = target.copy(level = 20)))
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), prepareMaterialChange(
            command.copy(target = command.target.copy(level = 20, levelProgressPercent = 0)), max))
    }

    @Test fun `비용 부족은 변경 없는 실패이고 명시적0은 금액 변경 없음이다`() {
        assertEquals(ContractResult.Failure(ContractError.INSUFFICIENT_FUNDS), prepareMaterialChange(command(1001), current))
        val paid = ok(prepareMaterialChange(command(1000), current))
        assertEquals(GameMoney(0), paid.balance)
        assertEquals(MoneyChange(MoneyDirection.DEBIT, GameMoney(1000)), paid.money)
        assertNull(ok(prepareMaterialChange(command(0), current)).money)
        assertEquals(GameMoney(1000), current.balance)
        assertEquals(VehicleLifecycle.OWNED, current.material.vehicle.lifecycle)
    }

    @Test fun `출처와 규칙 콘텐츠의 불일치를 정상 상태로 보정하지 않는다`() {
        val command = command()
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), prepareMaterialChange(command.copy(source = InputSource.VEHICLE), current))
        for (bad in listOf(command.copy(contentVersion = ContentVersion("old")), command.copy(ruleVersion = RuleVersion("old"))))
            assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), prepareMaterialChange(bad, current))
    }

    @Test fun `실제 VehicleUpgrade 계획을 새 명령으로 연결하지만 저장 성공으로 표시하지 않는다`() {
        val plan = ok(VehicleUpgrade(content).planMaterial(listOf(target, material),
            VehicleRef(target.ownedVehicleId, target.revision), VehicleRef(material.ownedVehicleId, material.revision)) {
                ContractResult.Success(VehicleActivity.IDLE)
            })
        val command = MaterialCommand(RequestId("consumer"), current.source, content.catalog.contentVersion, content.ruleVersion,
            plan.target, plan.material, GameMoney(0))
        val prepared = ok(prepareMaterialChange(command, current))
        assertEquals(3, prepared.target.level)
        assertEquals(50, prepared.target.levelProgressPercent)
        assertEquals(VehicleLifecycle.CONSUMED, prepared.material.lifecycle)
        assertEquals(material.acquisitionCost, prepared.material.acquisitionCost)
        assertEquals(target.acquisitionSource, prepared.target.acquisitionSource)
    }

    @Test fun `전체 입력의 동일성은 역할 revision 성장 비용과 버전을 포함한다`() {
        val command = command()
        val changes = listOf(command.copy(source = InputSource.VEHICLE), command.copy(contentVersion = ContentVersion("other")),
            command.copy(ruleVersion = RuleVersion("other")), command.copy(cost = GameMoney(1)),
            command.copy(target = command.target.copy(expectedRevision = Revision(1))),
            command.copy(material = command.material.copy(expectedRevision = Revision(1))),
            command.copy(target = command.target.copy(levelProgressPercent = 51)),
            command.copy(material = command.material.copy(ownedVehicleId = OwnedVehicleId("other"))))
        assertEquals(command, command.copy())
        changes.forEach { assertNotEquals(command, it) }
        // 저장소의 실제 ID 충돌/재생/원자성 실행 시험은 아직 없다.
    }

    @Test fun `최대 레벨 재료도 쓸 수 있고 기존 계산의 최대 레벨 결과는0진행량이다`() {
        val highTarget = target.copy(level = 19, levelProgressPercent = 99)
        val highMaterial = material.copy(level = 20, levelProgressPercent = 0)
        val plan = ok(VehicleUpgrade(content).planMaterial(listOf(highTarget, highMaterial),
            VehicleRef(highTarget.ownedVehicleId, highTarget.revision), VehicleRef(highMaterial.ownedVehicleId, highMaterial.revision)) {
                ContractResult.Success(VehicleActivity.IDLE)
            })
        val state = current.copy(target = current.target.copy(vehicle = highTarget), material = current.material.copy(vehicle = highMaterial))
        val command = command().copy(target = plan.target, material = plan.material)
        val prepared = ok(prepareMaterialChange(command, state))
        assertEquals(20, prepared.target.level)
        assertEquals(0, prepared.target.levelProgressPercent)
        assertEquals(20, prepared.material.level)
        assertEquals(0, prepared.material.levelProgressPercent)
    }

    @Test fun `Long 최대 금액의 예정 차감도 오버플로 없이 계산하고 원본은 불변이다`() {
        val state = current.copy(balance = GameMoney(Long.MAX_VALUE))
        val command = command(Long.MAX_VALUE)
        val prepared = ok(prepareMaterialChange(command, state))
        assertEquals(GameMoney(0), prepared.balance)
        assertEquals(MoneyChange(MoneyDirection.DEBIT, GameMoney(Long.MAX_VALUE)), prepared.money)
        assertEquals(GameMoney(Long.MAX_VALUE), state.balance)
    }

    @Test fun `결과 형태는 하나의 정산에 원래 명령과 대상 재료를 모두 필수로 보존한다`() {
        val command = command()
        val prepared = ok(prepareMaterialChange(command, current))
        // 저장 성공을 실행한 것이 아니라, 후속 저장소가 보존할 결과 DTO의 소비 예시다.
        val result = MaterialSettlement(command, SettlementId("fixture-material"),
            Transaction(TransactionId("fixture-material"), command.requestId, TransactionKind.GARAGE,
                command.source, command.ruleVersion, prepared.money), prepared.balance, prepared.target, prepared.material,
            RentalContractFixtures.time)
        assertEquals(command, result.command)
        assertEquals(prepared.target, result.target)
        assertEquals(prepared.material, result.material)
        assertEquals(command.requestId, result.transaction.requestId)
        assertNull(result.transaction.money)
    }

}
