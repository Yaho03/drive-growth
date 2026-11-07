package io.drivegrowth.app.ui.review
import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import io.drivegrowth.app.ui.delivery.*
class Host53Activity : Activity() {
    private lateinit var box: LinearLayout
    lateinit var screen: DeliveryView
    val requests = mutableListOf<DeliveryRequest>()
    fun show(width: Int, frame: DeliveryFrame = DeliveryPreviewStates.offers, enabled: Boolean = true) {
        box.removeAllViews(); screen = DeliveryView(this, { requests.add(it) }); box.addView(screen, LinearLayout.LayoutParams(minOf(dp(width), box.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels), -1)); screen.render(frame, enabled)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(TextView(this).apply { text = "#53 격리 기기 시험 · 고정 입력 · 실제 저장/차량 입력 아님"; textSize = 14f })
        box = LinearLayout(this)
        root.addView(box, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root); show(320)
    }
}
