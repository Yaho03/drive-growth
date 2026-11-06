package io.drivegrowth.navidiagnostic

/** UTF-8 최대 4바이트인 코드 포인트 600개씩 나눠 Logcat에서 payload 손실을 피한다. */
internal fun logChunks(text: String): List<String> = buildList {
    var offset = 0
    while (offset < text.length) {
        val end = text.offsetByCodePoints(offset, minOf(600, text.codePointCount(offset, text.length)))
        add(text.substring(offset, end))
        offset = end
    }
}
