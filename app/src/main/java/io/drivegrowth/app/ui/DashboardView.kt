package io.drivegrowth.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 화면은 계산이나 저장을 수행하지 않고 전달받은 상태만 표현한다. */
data class UiModel(
    val state: String = "READY",
    val sourceLabel: String = "차량 입력",
    val xp: Int = 0,
    val level: Int = 1,
    val score: String = "평가 준비 중",
    val distance: String = "0.0 km",
    val duration: String = "0분",
    val statusMessage: String = "",
    val badgeUnlocked: Boolean = false,
    val controlsEnabled: Boolean = false,
    val selectedTab: String = "home",
    val records: List<String> = emptyList(),
    val tutorialCompleted: Set<Int> = emptySet(),
    val ranking: List<String> = emptyList(),
    val rewardXp: Int = 0,
)

class DashboardView(context: Context, private val onAction: (String) -> Unit) : LinearLayout(context) {
    private val ink = Color.rgb(232, 240, 244)
    private val muted = Color.rgb(157, 177, 192)
    private val mint = Color.rgb(117, 232, 190)
    private val base = Color.rgb(12, 23, 34)
    private val panel = Color.rgb(23, 38, 52)
    private var model = UiModel()

    init { orientation = VERTICAL; setBackgroundColor(base); setPadding(dp(26), dp(20), dp(26), dp(12)) }

    fun render(value: UiModel) {
        model = value
        removeAllViews()
        addView(header())
        val restricted = !value.controlsEnabled
        val scroll = ScrollView(context).apply { isFillViewport = true }
        val body = column().apply { setPadding(0, dp(16), 0, dp(12)) }
        scroll.addView(body)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        if (restricted) driving(body) else when (value.selectedTab) {
            "garage" -> garage(body)
            "records" -> records(body)
            "tutorial" -> tutorial(body)
            "ranking" -> ranking(body)
            else -> home(body)
        }
        if (!restricted) addView(navigation())
    }

    private fun header() = row().apply {
        addView(column().apply {
            addView(label("르르르 렌터카", 26, ink, true))
            addView(label("평소의 이동이, 내 차의 이야기로", 14, muted))
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(label(model.sourceLabel, 14, mint, true).apply {
            setPadding(dp(16), dp(12), dp(16), dp(12)); background = shape(panel)
            contentDescription = "입력 출처: ${model.sourceLabel}"
        })
    }

    private fun home(body: LinearLayout) {
        val settled = model.state == "SETTLED"
        body.addView(label(if (settled) "이동을 마쳤어요" else "오늘도 내 차와 함께", 32, ink, true))
        body.addView(label(if (settled) "주행 결과가 저장되었습니다. 차고에서 성장을 확인하세요." else "주행은 자동으로 기록하고, 성장은 주차 후 확인해요.", 16, muted))
        val area = LinearLayout(context).apply {
            orientation = if (resources.configuration.screenWidthDp >= 760) HORIZONTAL else VERTICAL
            setPadding(0, dp(18), 0, dp(14))
        }
        val wide = area.orientation == HORIZONTAL
        area.addView(vehicleCard(), if (wide) LayoutParams(0, dp(290), 1.4f) else LayoutParams(LayoutParams.MATCH_PARENT, dp(270)))
        val stats = column().apply {
            setPadding(dp(if (wide) 20 else 0), dp(if (wide) 0 else 16), 0, 0)
            addView(metric(if (settled) "이번 이동의 성장" else "차량 레벨", if (settled) "+${model.rewardXp} XP" else "LV. ${model.level}", "게임 속 성장 · 실제 차량 성능과 무관"))
            addView(metric("운전 패턴", model.score, "속도 변화 기반 참고 정보 · 안전성 판정 아님"))
            addView(label("${model.distance}   ·   ${model.duration}", 18, ink))
        }
        area.addView(stats, if (wide) LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f) else LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        body.addView(area)
        body.addView(label(model.statusMessage, 16, muted))
        val actions = row()
        actions.addView(action(if (settled) "차고에서 변화 확인" else "내 차고", "tab:garage"), LayoutParams(0, dp(58), 1f))
        if (model.sourceLabel.contains("모의") || model.sourceLabel.contains("시뮬")) {
            actions.addView(action("모의 주행 시작", "demo:start"), LayoutParams(0, dp(58), 1f).apply { marginStart = dp(12) })
        }
        body.addView(actions)
        body.addView(sourceControls())
    }

    private fun driving(body: LinearLayout) {
        body.gravity = Gravity.CENTER
        body.addView(label(if (model.state == "DATA_UNAVAILABLE") "차량 정보를 기다리고 있어요" else "주행 기록 중", 36, ink, true))
        body.addView(label("운전에 집중하세요", 25, mint, true).apply { setPadding(0, dp(20), 0, dp(12)) })
        body.addView(label("성장과 보상은 주차 후 확인할 수 있어요.", 20, muted))
        body.addView(label(model.statusMessage, 16, muted).apply { setPadding(0, dp(24), 0, 0) })
        if (model.state == "DATA_UNAVAILABLE") body.addView(label("정보가 확인될 때까지 게임 조작을 잠급니다.", 16, muted))
    }

    private fun garage(body: LinearLayout) {
        body.addView(label("내 차고", 32, ink, true))
        body.addView(label("차량의 성장은 꾸미기와 기록으로 남아요", 16, muted))
        body.addView(vehicleCard(), LayoutParams(LayoutParams.MATCH_PARENT, dp(280)).apply { topMargin = dp(16) })
        body.addView(metric("누적 성장", "LV. ${model.level}  ·  ${model.xp} XP", "차종 수집과 한 차량의 레벨 성장은 별개의 시스템입니다."))
        body.addView(label(if (model.badgeUnlocked) "첫 성장 배지 해금" else "첫 성장 배지", 24, if (model.badgeUnlocked) mint else ink, true))
        body.addView(label(if (model.badgeUnlocked) "첫 번째 성장을 기념하는 꾸미기 보상입니다." else "첫 레벨 상승 후 꾸미기 배지를 확인할 수 있어요.", 16, muted))
        if (model.badgeUnlocked) body.addView(action("배지 확인", "garage:badge"))
        body.addView(label("차종 자료와 정식 차량 이미지는 준비 중입니다. 현재 도형은 실제 모델을 나타내지 않습니다.", 14, muted).apply { setPadding(0, dp(18), 0, 0) })
    }

    private fun records(body: LinearLayout) {
        body.addView(label("주행 기록", 32, ink, true))
        body.addView(label("${model.sourceLabel}의 기록만 표시합니다", 16, muted))
        if (model.records.isEmpty()) body.addView(metric("아직 기록이 없어요", "첫 이동을 기다리는 중", "주행 후 주차가 확인되면 이곳에 남습니다."))
        else model.records.forEach { body.addView(textCard(it)) }
    }

    private fun tutorial(body: LinearLayout) {
        body.addView(label("시작 안내", 32, ink, true))
        body.addView(label("주차 중에 읽고 확인하세요. 실제 주행 조작을 요구하지 않아요.", 16, muted))
        listOf("주행 중에는 화면을 조작하지 않아요", "게임 속 차량 레벨은 실제 성능과 무관해요", "운전 패턴 점수는 안전성 판정이 아니에요").forEachIndexed { index, title ->
            body.addView(action("${if (index in model.tutorialCompleted) "✓" else "○"}  $title", "tutorial:$index"))
        }
        body.addView(label("${model.tutorialCompleted.size} / 3 확인", 18, mint, true))
    }

    private fun ranking(body: LinearLayout) {
        body.addView(label("운전 패턴 랭킹", 32, ink, true))
        body.addView(label("이 기기에 저장된 내 주행 기록 비교 · 차량 레벨은 점수에 영향을 주지 않습니다", 16, muted))
        if (model.ranking.isEmpty()) body.addView(metric("비교할 기록이 없어요", "평가 준비 중", "확인된 기록이 쌓이면 표시합니다. 임의의 경쟁자는 만들지 않습니다."))
        else model.ranking.forEach { body.addView(textCard(it)) }
        body.addView(label("전체 사용자 온라인 랭킹은 연결되지 않았습니다.", 14, muted))
    }

    private fun vehicleCard() = column().apply {
        background = shape(panel)
        setPadding(dp(20), dp(16), dp(20), dp(14))
        val top = row()
        top.addView(label("나의 차량", 20, ink, true), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        top.addView(label("LV. ${model.level}", 18, mint, true))
        addView(top)
        addView(CarDrawing(context, model.badgeUnlocked), LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(label("임시 차량 도형 · 차종 자료 연동 예정", 12, muted))
    }

    private fun sourceControls() = row().apply {
        setPadding(0, dp(12), 0, 0)
        addView(action("차량 입력", "source:vehicle", false), LayoutParams(0, dp(52), 1f))
        addView(action("모의 입력", "source:simulation", false), LayoutParams(0, dp(52), 1f).apply { marginStart = dp(10) })
    }

    private fun navigation() = row().apply {
        setPadding(0, dp(8), 0, 0)
        listOf("home" to "내 차", "garage" to "차고", "records" to "기록", "tutorial" to "튜토리얼", "ranking" to "랭킹").forEach { (id, title) ->
            addView(action(title, "tab:$id", id == model.selectedTab), LayoutParams(0, dp(56), 1f).apply { marginEnd = dp(5) })
        }
    }

    private fun metric(title: String, value: String, detail: String) = column().apply {
        setPadding(0, dp(12), 0, dp(16))
        addView(label(title, 14, muted))
        addView(label(value, 28, ink, true))
        addView(label(detail, 12, muted))
    }

    private fun textCard(value: String) = label(value, 18, ink).apply {
        background = shape(panel); setPadding(dp(18), dp(18), dp(18), dp(18))
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12); bottomMargin = dp(8) }
    }

    private fun action(title: String, action: String, primary: Boolean = true) = Button(context).apply {
        text = title; textSize = 16f; isAllCaps = false; minHeight = dp(56)
        setTextColor(if (primary) base else ink)
        background = shape(if (primary) mint else panel)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10); bottomMargin = dp(6) }
        contentDescription = title
        setOnClickListener { if (model.controlsEnabled) onAction(action) }
    }

    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun column() = LinearLayout(context).apply { orientation = VERTICAL }
    private fun row() = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun shape(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(18).toFloat() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

/** 외부 이미지나 상표 없이 작성한 임시 차량 도형. */
private class CarDrawing(context: Context, private val badge: Boolean) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width / 520f, height / 185f)
        canvas.save(); canvas.translate((width - 520 * scale) / 2f, (height - 185 * scale) / 2f); canvas.scale(scale, scale)
        p.color = Color.rgb(13, 27, 39); canvas.drawOval(RectF(35f, 143f, 490f, 174f), p)
        p.color = if (badge) Color.rgb(117, 232, 190) else Color.rgb(182, 203, 214)
        val body = Path().apply {
            moveTo(28f, 113f); lineTo(48f, 92f); lineTo(130f, 79f); lineTo(192f, 35f)
            quadTo(211f, 24f, 244f, 27f); lineTo(334f, 30f); quadTo(362f, 34f, 405f, 83f)
            lineTo(478f, 97f); quadTo(502f, 104f, 499f, 136f); lineTo(27f, 136f); close()
        }
        canvas.drawPath(body, p)
        p.color = Color.rgb(38, 62, 77)
        val window = Path().apply { moveTo(154f, 80f); lineTo(208f, 42f); lineTo(260f, 42f); lineTo(260f, 80f); close() }
        canvas.drawPath(window, p)
        canvas.drawPath(Path().apply { moveTo(274f, 42f); lineTo(331f, 44f); lineTo(377f, 81f); lineTo(274f, 81f); close() }, p)
        p.color = Color.rgb(229, 244, 240); canvas.drawRoundRect(RectF(447f, 99f, 486f, 107f), 4f, 4f, p)
        p.color = Color.rgb(232, 110, 109); canvas.drawRoundRect(RectF(37f, 101f, 64f, 109f), 3f, 3f, p)
        listOf(119f, 407f).forEach { x ->
            p.color = Color.rgb(10, 19, 29); canvas.drawCircle(x, 135f, 32f, p)
            p.color = Color.rgb(104, 130, 145); canvas.drawCircle(x, 135f, 19f, p)
            p.color = Color.rgb(205, 222, 230); canvas.drawCircle(x, 135f, 9f, p)
        }
        if (badge) {
            p.color = Color.rgb(255, 214, 127); canvas.drawCircle(318f, 108f, 11f, p)
            p.color = Color.rgb(15, 34, 43); p.textAlign = Paint.Align.CENTER; p.textSize = 16f; canvas.drawText("1", 318f, 114f, p)
        }
        canvas.restore()
    }
}
