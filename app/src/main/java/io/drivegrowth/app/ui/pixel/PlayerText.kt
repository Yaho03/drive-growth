package io.drivegrowth.app.ui.pixel

/**
 * 저장·계산 계층이 붙인 개발용 꼬리표(원장·콘텐츠 버전·저장 상태)를 화면에서만 걷어 낸다.
 * 상태 객체의 값은 그대로 두므로 저장·검증 코드와 시험에는 영향이 없다.
 */
internal object PlayerText {
    private val devParts = setOf("저장 상태", "모의 원장", "차량 원장", "시험 콘텐츠")

    /** "모의 원장 · 콘텐츠 trial-… · 시험 콘텐츠" → "시연 모드", 차량 입력이면 빈 값. */
    fun source(label: String?): String? = when {
        label == null -> null
        label.contains("모의") || label.contains("시연") -> "시연 모드"
        else -> null
    }

    /** "내 가게 · 저장 상태 · 모의 원장" → "내 가게" */
    fun shopName(name: String): String =
        name.split(" · ").filter { part -> part !in devParts && !part.startsWith("콘텐츠 ") }.joinToString(" · ").ifBlank { "내 가게" }

    /** 미터를 "36km"·"4.2km"로 */
    fun km(meters: Long): String = if (meters % 1000 == 0L) "${meters / 1000}km" else String.format("%.1fkm", meters / 1000.0)
}
