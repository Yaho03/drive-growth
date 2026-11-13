package io.drivegrowth.app.ui.results

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import io.drivegrowth.app.R
import io.drivegrowth.app.data.RentalIncomeKind
import io.drivegrowth.app.runtime.rental.product.RentalCity
import io.drivegrowth.app.ui.pixel.PixelStyle
import io.drivegrowth.app.ui.pixel.VehicleSprites
import io.drivegrowth.app.ui.presentation.RewardCelebrationKind
import io.drivegrowth.app.ui.presentation.RewardCelebrationView
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.RentalCategory
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 호스트가 준 확정 사실을 표시한다. 차량 SDK·DB·보상 계산을 호출하지 않는다. */
class ResultsView(context: Context, onEvent: (ResultsEvent) -> Unit) : LinearLayout(context) {
    private val style = ResultsStyle(context)
    private val sprites = VehicleSprites(context)
    private val interaction = ResultsInteraction(onEvent)
    internal var onCelebration: ((RewardCelebrationKind) -> Unit)? = null
    private val celebration = RewardCelebrationView(context).apply {
        onStarted = { onCelebration?.invoke(it) }
    }
    private var openedResult: SettlementId? = null
    private var wide = false
    private var listScroll: ScrollView? = null
    private var detailScroll: ScrollView? = null
    private var listY = 0
    private var detailY = 0
    val uiState: ResultsUiState get() = interaction.uiState
    val selectedSettlementId: SettlementId? get() = uiState.selectedId

    init { orientation = VERTICAL; setBackgroundColor(style.base) }

    fun render(state: ResultsScreenState, controlsEnabled: Boolean) {
        interaction.render(state, controlsEnabled); drawScreen()
    }

    fun restoreSelection(id: SettlementId?) {
        interaction.restoreSelection(id); detailY = 0; detailScroll = null; drawScreen()
    }

    /** Activity 밖에서 보관한 선택·처리 상태를 복원한다. 저장 완료를 새로 만들지 않는다. */
    fun restoreUiState(value: ResultsUiState) {
        interaction.restoreUiState(value); detailY = 0; detailScroll = null; drawScreen()
    }

    fun completeMarkViewed(id: SettlementId, result: ContractResult<Unit>) {
        interaction.completeMarkViewed(id, result); drawScreen()
    }

    fun completeRetry() { interaction.completeRetry(); drawScreen() }

    /** 공통 Back 정책은 호스트가 소유한다. 상세를 닫은 경우에만 true다. */
    fun closeDetail(): Boolean {
        if (!interaction.closeDetail()) return false
        detailY = 0; detailScroll = null; drawScreen(); return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val next = w >= style.dp(1000)
        if (next != wide) { wide = next; post { drawScreen() } }
    }

    private fun s(id: Int) = context.getString(id)
    private fun drawScreen(): Unit = with(style) {
        // Loading/Error/Restricted에는 콘텐츠 scroll을 만들지 않아 저장 위치를 0으로 덮지 않는다.
        listScroll?.takeIf { it.isLaidOut }?.let { listY = it.scrollY }
        detailScroll?.takeIf { it.isLaidOut }?.let { detailY = it.scrollY }
        val focusedTag = findFocus()?.tag
        listScroll = null; detailScroll = null
        celebration.rebuildContent { removeAllViews() }; setPadding(dp(PixelStyle.panelPaddingDp), dp(PixelStyle.gapDp), dp(PixelStyle.panelPaddingDp), dp(PixelStyle.gapDp))
        if (!interaction.controlsEnabled) {
            celebration.render(null, RewardCelebrationKind.SETTLEMENT)
            openedResult = null
            addView(scroll(message(s(R.string.results_restricted), s(R.string.results_restricted_body)), 0), LayoutParams(-1, -1))
            return@with
        }
        addView(text(s(R.string.results_title), 26, bold = true))
        when (val current = interaction.state) {
            ResultsScreenState.Loading -> addView(scroll(message(if (uiState.scope == ResultsScope.ALL) "정산 이력을 불러오고 있어요" else s(R.string.results_loading)), 0), LayoutParams(-1, 0, 1f))
            is ResultsScreenState.Error -> addView(scroll(problem(s(R.string.results_error), current.message, true), 0), LayoutParams(-1, 0, 1f))
            is ResultsScreenState.Missing -> addView(scroll(problem(s(R.string.results_missing), current.message, true).apply {
                addView(text(s(R.string.results_missing_body), color = muted))
            }, 0), LayoutParams(-1, 0, 1f))
            is ResultsScreenState.Unsupported -> addView(scroll(problem(s(R.string.results_unsupported), current.message, false), 0), LayoutParams(-1, 0, 1f))
            is ResultsScreenState.Content -> {
                if (current.origin == ResultsOrigin.FIXTURE) addView(text(s(R.string.results_fixture), color = accent))
                val area = if (wide) row() else column()
                addView(area, LayoutParams(-1, 0, 1f))
                // HTML 정산 화면: 고르기 전에는 가운데 카드 하나, 고르면 오른쪽에 상세
                val single = wide && selectedSettlementId == null
                if (wide || selectedSettlementId == null) {
                    listScroll = scroll(resultList(current), listY).apply { tag = "results:list" }
                    area.addView(listScroll, if (single) LayoutParams(0, -1, 1f).apply { marginStart = dp(160); marginEnd = dp(160) }
                        else if (wide) LayoutParams(0, -1, 1f) else LayoutParams(-1, -1))
                }
                if ((wide && !single) || selectedSettlementId != null) {
                    detailScroll = scroll(detail(), detailY).apply { tag = "results:detail" }
                    area.addView(detailScroll, if (wide) LayoutParams(0, -1, 1.2f).apply { marginStart = dp(PixelStyle.gapDp) } else LayoutParams(-1, -1))
                }
            }
        }
        if (focusedTag != null) findViewWithTag<View>(focusedTag)?.takeIf { it.isEnabled }?.requestFocus()
    }

    private fun message(title: String, body: String? = null) = style.card().apply {
        addView(style.text(title, 28, bold = true))
        if (body != null) addView(style.text(body, color = style.muted))
    }

    private fun problem(title: String, body: String, retry: Boolean) = message(title, body).apply {
        if (retry) style.add(this, style.button(s(if (uiState.retryPending) R.string.results_retry_pending else R.string.results_retry), !uiState.retryPending) {
            interaction.retry(); drawScreen()
        }.apply { tag = "results:retry" })
    }

    private fun resultList(state: ResultsScreenState.Content) = with(style) {
        column().apply {
            add(this, row().apply {   // 제목 한 줄 + 오른쪽 작은 [미확인][전체 기록]
                gravity = Gravity.CENTER_VERTICAL
                addView(column().apply {
                    addView(text(s(R.string.results_headline), 24, bold = true))
                    addView(text(context.getString(R.string.results_summary,
                        state.results.count { interaction.readState(it.settlement.settlementId) != ResultsReadState.Done }, state.results.size), 16, muted))
                    if (!state.historyAvailable) addView(text(s(R.string.results_history_unsupported), 14, muted))
                }, LayoutParams(0, -2, 1f))
                if (state.historyAvailable) ResultsScope.entries.forEach { scope ->
                    addView(button(if (scope == ResultsScope.ALL) "전체 기록" else "미확인", primary = uiState.scope == scope) {
                        interaction.scope(scope); drawScreen()
                    }.apply { tag = "results:scope:${scope.name}"; textSize = 16f; minHeight = dp(48); minimumHeight = dp(48) },
                        LayoutParams(-2, -2).apply { marginStart = dp(8) })
                }
            }, 4)
            if (interaction.visibleResults.isEmpty()) add(this, message(
                if (state.historyAvailable && uiState.scope == ResultsScope.ALL) "저장된 정산 기록이 없어요" else s(R.string.results_empty),
                if (state.historyAvailable) "현재 필터에서 표시할 정산이 없어요." else s(R.string.results_empty_body)))
            // HTML처럼 한 카드 안에 [종류 칩] 내용 … 금액 줄들, 맨 아래 합계
            if (interaction.visibleResults.isNotEmpty()) add(this, card().apply {
                var total = 0L
                interaction.visibleResults.forEachIndexed { index, value ->
                    val settlement = value.settlement
                    settlement.transaction.money?.let { total += if (it.direction == MoneyDirection.CREDIT) it.amount.units else -it.amount.units }
                    val selected = selectedSettlementId == settlement.settlementId
                    val open = {
                        if (selectedSettlementId != settlement.settlementId) { detailY = 0; detailScroll = null }
                        openedResult = settlement.settlementId
                        interaction.select(settlement.settlementId)
                        drawScreen()
                    }
                    addView(row().apply {
                        gravity = Gravity.CENTER_VERTICAL
                        background = shape(inset, selected)
                        setPadding(dp(12), dp(8), dp(10), dp(8))
                        addView(text(kind(settlement.transaction.kind), 15, ink, bold = true).apply {
                            background = android.graphics.drawable.GradientDrawable().apply { setColor(PixelStyle.panel); cornerRadius = dp(14).toFloat() }
                            setPadding(dp(12), dp(4), dp(12), dp(4))
                        })
                        settlement.changedVehicle?.let { car -> vehicleArt(car.vehicleId)?.let { addView(it, LayoutParams(dp(52), dp(38)).apply { marginStart = dp(10) }) } }
                        addView(text(value.vehicleName ?: time(settlement), 17).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                            LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
                        if (interaction.readState(settlement.settlementId) == ResultsReadState.Done) addView(text(s(R.string.results_mark_done), 14, muted))
                        addView(text(moneyChange(settlement), 22, amountColor(settlement), bold = true), LayoutParams(-2, -2).apply { marginStart = dp(10) })
                        addView(button("보기", primary = selected) { open() }.apply {
                            tag = "results:open:${settlement.settlementId.value}"
                            contentDescription = context.getString(R.string.results_open_accessibility, kind(settlement.transaction.kind), settlement.settlementId.value)
                            textSize = 16f; minHeight = dp(48); minimumHeight = dp(48)
                        }, LayoutParams(-2, -2).apply { marginStart = dp(10) })
                    }, LayoutParams(-1, -2).apply { if (index > 0) topMargin = dp(8) })
                }
                addView(row().apply {   // 합계(금색 테두리)
                    gravity = Gravity.CENTER_VERTICAL
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(PixelStyle.inset); cornerRadius = dp(16).toFloat(); setStroke(dp(2), PixelStyle.gold) }
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    addView(text(s(R.string.results_total), 18, bold = true), LayoutParams(0, -2, 1f))
                    addView(text((if (total >= 0) "+" else "−") + java.text.NumberFormat.getIntegerInstance(Locale.KOREA).format(kotlin.math.abs(total)), 34, PixelStyle.gold, bold = true))
                }, LayoutParams(-1, -2).apply { topMargin = dp(12) })
            })
        }
    }

    private fun detail() = with(style) {
        column().apply {
            val id = selectedSettlementId
            if (id == null) { add(this, message(s(R.string.results_select_prompt))); return@apply }
            add(this, button(s(R.string.results_close)) { closeDetail() }.apply { tag = "results:close" })
            val value = interaction.selectedResult
            if (value == null) {
                add(this, message(s(R.string.results_removed), s(R.string.results_removed_body)))
                return@apply
            }
            val settlement = value.settlement
            val effectKey = id.value.takeIf {
                val money = settlement.transaction.money
                (interaction.state as? ResultsScreenState.Content)?.origin == ResultsOrigin.STORED &&
                    openedResult == id && interaction.readState(id) == ResultsReadState.Ready &&
                    money?.direction == MoneyDirection.CREDIT && money.amount.units > 0
            }
            celebration.render(effectKey, RewardCelebrationKind.SETTLEMENT)
            add(this, celebration.over(card(selected = effectKey != null).apply {
                addView(text(kind(settlement.transaction.kind), 32, bold = true))
                addView(text(s(R.string.results_confirmed), color = accent))
                add(this, card().apply {
                    background = shape(inset)
                    addView(text(s(R.string.results_money_change), color = muted))
                    addView(text(moneyChange(settlement), 48, amountColor(settlement), bold = true))
                })
                add(this, card().apply {
                    background = shape(inset)
                    addView(text(pair(R.string.results_balance, money(settlement.balance)), 26, bold = true))
                })
            }))
            add(this, card().apply {
                addView(text(pair(R.string.results_settlement_id, id.value), color = muted))
                addView(text(pair(R.string.results_transaction_id, settlement.transaction.transactionId.value), color = muted))
                addView(text(pair(R.string.results_request_id, settlement.transaction.requestId.value), color = muted))
                addView(text(pair(R.string.results_rule, settlement.transaction.ruleVersion.value), color = muted))
                addView(text(pair(R.string.results_source, s(if (settlement.transaction.source == InputSource.VEHICLE)
                    R.string.results_source_vehicle else R.string.results_source_simulation)), color = muted))
                addView(text(pair(R.string.results_time, time(settlement)), color = muted))
            })
            add(this, vehicle(value))
            if (value.rental != null) add(this, rentalDetails(value))
            else add(this, text(if ((interaction.state as? ResultsScreenState.Content)?.historyAvailable == true)
                "추가 상세 정보는 확인할 수 없어요." else s(R.string.results_details_unsupported), color = muted))
            add(this, text(s(R.string.results_mark_note), color = muted))
            val read = interaction.readState(id)
            when (read) {
                ResultsReadState.Pending -> add(this, text(s(R.string.results_mark_pending), bold = true))
                ResultsReadState.Done -> add(this, text(s(R.string.results_mark_done), bold = true))
                is ResultsReadState.Failed -> add(this, text(s(R.string.results_mark_failed), color = accent))
                ResultsReadState.Ready -> Unit
            }
            val anyPending = uiState.readStates.values.any { it == ResultsReadState.Pending }
            if (anyPending && read != ResultsReadState.Pending) add(this, text(s(R.string.results_other_pending)))
            add(this, button(s(R.string.results_mark_viewed), interaction.canMarkViewed(id), primary = true) {
                interaction.requestMarkViewed(id); drawScreen()
            }.apply { tag = "results:mark:${id.value}" })
        }
    }

    private fun rentalDetails(value: ResultPresentation) = with(style) {
        val rental = requireNotNull(value.rental)
        val receipt = rental.receipt
        card().apply {
            tag = "results:rental-detail"
            addView(text("가상 고객의 렌터카 영업 결과", 24, bold = true))
            when (val demand = value.rentalDemand) {
                is Observation.Missing -> addView(text("가상 고객 요청: 기록 없음", color = muted))
                is Observation.Available -> {
                    val details = demand.value
                    val city = when (val recorded = details.city) {
                        is Observation.Missing -> "기록 없음"
                        is Observation.Available -> when (recorded.value) {
                            RentalCity.URBAN -> "도심"
                            RentalCity.SUBURBAN -> "근교"
                            RentalCity.TRANSPORT_HUB -> "교통 거점"
                        }
                    }
                    val purpose = when (details.purpose) {
                        RentalCategory.SMALL -> "근거리 이동"
                        RentalCategory.SEDAN -> "업무 이동"
                        RentalCategory.SUV -> "야외 활동"
                        RentalCategory.VAN -> "단체 이동"
                        RentalCategory.ECO -> "친환경 이동"
                    }
                    addView(text("목적 도시 특성: $city"))
                    addView(text("용도: $purpose"))
                    addView(text("희망 차종: ${details.desiredVehicleName}"))
                }
            }
            addView(text("대여 차량: ${value.rentalVehicleName ?: "차종명 확인 불가"}"))
            addView(text("대여 차량 식별: ${rental.ownedVehicleId.value}", color = muted))
            val amount = receipt.settlement.transaction.money?.takeIf { it.direction == MoneyDirection.CREDIT }?.amount
            addView(text("확정 매출: ${amount?.let(::money) ?: "확인 불가"}"))
            addView(text("매출에 포함된 시설 보너스: ${money(receipt.facilityBonus)}"))
            addView(text("가상 영업 사고: ${if (receipt.accident) "발생" else "없음"}"))
            addView(text("차종 궁합 리뷰 상승: ${if (receipt.matchReview) "있음" else "없음"}"))
            addView(text("세차장 리뷰 상승: ${if (receipt.washReview) "있음" else "없음"}"))
            addView(text("단골 여부: ${when (receipt.regularCustomer) { true -> "예"; false -> "아니요"; null -> "확인 불가" }}"))
            when (val evidence = value.rentalBasis) {
                is Observation.Missing -> addView(text("감액·상한 적용 내역: 확인할 수 없어요.", color = muted))
                is Observation.Available -> {
                    val basis = evidence.value
                    addView(text("저장된 영업 시간 근거", 24, bold = true))
                    addView(text("정상 적용 시간: ${rentalDuration(basis.normalMillis)}"))
                    addView(text("감액 적용 시간: ${rentalDuration(basis.reducedMillis)}"))
                    addView(text("상한 제외 시간: ${rentalDuration(basis.cappedMillis)}"))
                    if (basis.unobservedMillis > 0) {
                        addView(text("미관측 제외 시간: ${rentalDuration(basis.unobservedMillis)}"))
                    }
                    basis.periods.forEachIndexed { index, period ->
                        val kind = when (period.kind) {
                            RentalIncomeKind.DRIVING -> "주행 중 영업"
                            RentalIncomeKind.PARKED -> "주차 중 영업"
                            RentalIncomeKind.INACTIVE -> "자동 영업 반영"
                            RentalIncomeKind.UNOBSERVED -> "미관측 · 수익 제외"
                        }
                        addView(text("구간 ${index + 1} · $kind", bold = true))
                        addView(text("${basisTime(period.from)} ~ ${basisTime(period.through)}", color = muted))
                        if (period.kind != RentalIncomeKind.UNOBSERVED) {
                            addView(text("정상 ${rentalDuration(period.normalMillis)} · 감액 ${rentalDuration(period.reducedMillis)} · 상한 제외 ${rentalDuration(period.cappedMillis)}"))
                        }
                        period.origin?.let { addView(text("수익 계산 기준 시각: ${basisTime(it)}", color = muted)) }
                        period.policy?.let { policy ->
                            val percent = policy.reducedMultiplier.movePointRight(2).stripTrailingZeros().toPlainString()
                            addView(text("적용 기준: 처음 ${rentalDuration(policy.normalMillis)} 정상, 이후 $percent% · 인정 상한 ${rentalDuration(policy.capMillis)}", color = muted))
                        }
                    }
                }
            }
        }
    }

    private fun vehicle(value: ResultPresentation) = with(style) {
        card().apply {
            val v = value.settlement.changedVehicle
            addView(text(s(R.string.results_vehicle), 24, bold = true))
            if (v == null) { addView(text(s(R.string.results_no_vehicle))); return@apply }
            vehicleArt(v.vehicleId)?.let { addView(it, LayoutParams(-1, dp(108))) }
            addView(text(value.vehicleName ?: s(R.string.results_vehicle_name_missing), 28, bold = true))
            addView(text(pair(R.string.results_owned_id, v.ownedVehicleId.value)))
            addView(text(pair(R.string.results_vehicle_id, v.vehicleId.value), color = muted))
            addView(text(s(when (v.rarity) {
                Rarity.COMMON -> R.string.results_rarity_common
                Rarity.ADVANCED -> R.string.results_rarity_advanced
                Rarity.RARE -> R.string.results_rarity_rare
                Rarity.LEGEND -> R.string.results_rarity_legend
            }), color = accent))
            addView(text(context.getString(R.string.results_level, v.level, v.levelProgressPercent)))
            addView(text(pair(R.string.results_cost, money(v.acquisitionCost))))
            addView(text(pair(R.string.results_acquisition, s(when (v.acquisitionSource) {
                VehicleAcquisitionSource.INITIAL_GRANT -> R.string.results_acquisition_initial
                VehicleAcquisitionSource.GACHA -> R.string.results_acquisition_gacha
                VehicleAcquisitionSource.PURCHASE_DELIVERY -> R.string.results_acquisition_delivery
            }))))
            addView(text(pair(R.string.results_lifecycle, s(when (v.lifecycle) {
                VehicleLifecycle.OWNED -> R.string.results_lifecycle_owned
                VehicleLifecycle.SOLD -> R.string.results_lifecycle_sold
                VehicleLifecycle.SCRAPPED -> R.string.results_lifecycle_scrapped
                VehicleLifecycle.CONSUMED -> R.string.results_lifecycle_consumed
            }))))
            addView(text(pair(R.string.results_revision, v.revision.value.toString()), color = muted))
        }
    }

    private fun vehicleArt(id: VehicleId): ImageView? = sprites.drawable(id)?.let { art ->
        ImageView(context).apply {
            tag = "results:vehicle-art:${id.value}"
            setImageDrawable(art)
            background = style.shape(PixelStyle.inset)
            clipToOutline = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            val inset = style.dp(6)
            setPadding(inset, inset, inset, inset)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    private fun amountColor(settlement: Settlement) =
        if (settlement.transaction.money?.direction == MoneyDirection.CREDIT) PixelStyle.gold else PixelStyle.text

    private fun basisTime(value: EpochMillis) = SimpleDateFormat("yyyy.MM.dd HH:mm:ss.SSS 'UTC'", Locale.KOREA).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }.format(Date(value.value))
    private fun pair(label: Int, value: String) = context.getString(R.string.results_pair, s(label), value)
    private fun money(value: GameMoney) = context.getString(R.string.results_money, NumberFormat.getIntegerInstance(Locale.KOREA).format(value.units))
    private fun moneyChange(value: Settlement): String {
        val change = value.transaction.money ?: return s(R.string.results_no_money_change)
        return (if (change.direction == MoneyDirection.CREDIT) "+" else "−") + money(change.amount)
    }
    private fun time(value: Settlement) = SimpleDateFormat("yyyy.MM.dd HH:mm:ss z", Locale.KOREA).format(Date(value.confirmedAt.value))
    private fun kind(value: TransactionKind) = s(when (value) {
        TransactionKind.DRIVING -> R.string.results_kind_driving
        TransactionKind.DELIVERY -> R.string.results_kind_delivery
        TransactionKind.RENTAL -> R.string.results_kind_rental
        TransactionKind.FACILITY -> R.string.results_kind_facility
        TransactionKind.GARAGE -> R.string.results_kind_garage
    })
}
