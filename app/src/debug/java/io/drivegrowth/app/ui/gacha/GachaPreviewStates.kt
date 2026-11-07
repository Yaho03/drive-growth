package io.drivegrowth.app.ui.gacha

import io.drivegrowth.core.content.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.progression.GachaRules
import java.math.BigDecimal

/** 결정적 표시 입력이다. 실제 추첨·티켓 구매·차감·정산·SDK 실행이 아니다. */
object GachaPreviewStates {
    private val rule = RuleVersion("gacha-ui-fixture")
    private val casper = VehicleId("hyundai_casper")
    private val avante = VehicleId("hyundai_avante")
    fun ready(): GachaFrame {
        val content = GameContentSnapshot(
            VehicleCatalogSnapshot(ContentVersion("gacha-ui-fixture"), listOf(VehicleDefinition(casper,"캐스퍼"), VehicleDefinition(avante,"아반떼"))),
            rule, true, listOf(VehicleGameData(casper,GameMoney(1000),1),VehicleGameData(avante,GameMoney(1200),1)),
            GachaRules(mapOf(Rarity.COMMON to 747,Rarity.ADVANCED to 200,Rarity.RARE to 50,Rarity.LEGEND to 3),30,Rarity.RARE),
        )
        return GachaFrame(1, GachaScreen.Offers(GachaOffers(content, listOf(casper,avante),
            mapOf(Rarity.COMMON to BigDecimal("74.7"),Rarity.ADVANCED to BigDecimal("20"),Rarity.RARE to BigDecimal("5"),Rarity.LEGEND to BigDecimal("0.3")),
            GachaGuarantee(12,false,true,"공급된 남은 횟수 · 고정 입력"), GachaSpace(2,3,GachaAvailability.READY),
            GameMoney(1200),GameMoney(500),listOf(
                GachaOption("paid","유료 뽑기권 사용",GachaOptionKind.PAID,GameMoney(0),1,2,RequestId("fixture-paid"),GachaAvailability.READY,"이미 보유한 유료권 1장 사용 · 구입 기능 미연결"),
                GachaOption("free","무료 뽑기권 사용",GachaOptionKind.FREE,GameMoney(0),1,1,RequestId("fixture-free"),GachaAvailability.READY,"공급된 무료권 1장 사용 · 고정 입력"),
            ),GachaAvailability.READY,GachaOrigin.FIXTURE)))
    }
    fun result(): GachaFrame {
        val vehicle = OwnedVehicle(OwnedVehicleId("fixture-gacha-owned"),avante,1,Revision(0),VehicleLifecycle.OWNED,
            Rarity.RARE,0,GameMoney(500),VehicleAcquisitionSource.GACHA)
        val settlement = Settlement(SettlementId("fixture-gacha-result"),
            Transaction(TransactionId("fixture-gacha-transaction"),RequestId("fixture-paid"),TransactionKind.GARAGE,InputSource.SIMULATION,rule,null),
            GameMoney(1200),vehicle,EpochMillis(1794040000000))
        return GachaFrame(3,GachaScreen.Result(GachaConfirmedResult(settlement,"아반떼","외부 확정 결과 모양의 고정 시험 입력 · 실제 지급 아님",GachaOrigin.FIXTURE)))
    }
    fun blocked(): List<GachaFrame> {
        val value = (ready().screen as GachaScreen.Offers).value
        return listOf(
            GachaFrame(1,GachaScreen.Loading),GachaFrame(1,GachaScreen.Unsupported("공급 미지원")),
            GachaFrame(1,GachaScreen.Missing("프로필 조회 미제공",true)),
            GachaFrame(1,GachaScreen.Failure(ContractError.STORAGE_FAILURE,"시험 조회 실패",true)),
        ) + listOf(value.copy(candidates = emptyList()), value.copy(appliedPercent = null),value.copy(content = null),
            value.copy(guarantee = value.guarantee!!.copy(available = false)),
            value.copy(space = GachaSpace(3,3,GachaAvailability.NO_SPACE)),
            value.copy(appliedPercent = mapOf(Rarity.COMMON to BigDecimal(100))),
        ).map { GachaFrame(1,GachaScreen.Offers(it)) } +
            listOf(GachaAvailability.INSUFFICIENT_MONEY,GachaAvailability.INSUFFICIENT_TICKETS).map { status ->
                GachaFrame(1,GachaScreen.Offers(value.copy(options = value.options.map { it.copy(availability = status) })))
            }
    }
    fun guaranteed(): GachaFrame {
        val value = (ready().screen as GachaScreen.Offers).value
        return GachaFrame(2,GachaScreen.Offers(value.copy(
            appliedPercent = mapOf(Rarity.COMMON to BigDecimal.ZERO,Rarity.ADVANCED to BigDecimal.ZERO,Rarity.RARE to BigDecimal("94.3396"),Rarity.LEGEND to BigDecimal("5.6604")),
            guarantee = GachaGuarantee(1,true,true,"보장 회차 표시 시험 · 적용 확률은 외부에서 반올림해 공급한 시험값"))))
    }
}
