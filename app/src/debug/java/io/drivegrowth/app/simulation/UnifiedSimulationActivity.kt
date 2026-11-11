package io.drivegrowth.app.simulation

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import io.drivegrowth.app.MainActivity
import io.drivegrowth.app.ui.garage.GarageStyle

/** 별도 debug 패키지의 시연 런처. 기존 Main/Views와 원장을 그대로 사용한다. */
class UnifiedSimulationActivity : MainActivity() {
    private var restButton: Button? = null
    private var restOpening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        restOpening = savedInstanceState?.getBoolean("restDemoOpening") == true
        super.onCreate(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("restDemoOpening", restOpening)
    }

    @Suppress("DEPRECATION")
    override fun setContentView(view: View) {
        val style = GarageStyle(this)
        val entry = style.button("휴식 모의 시연 · 별도 원장", enabled = false) {
            if (!restOpening && allowed()) {
                restOpening = true
                updateRestEntry()
                startActivityForResult(Intent(this, RestSimulationActivity::class.java), REST_DEMO_REQUEST)
            }
        }.apply {
            tag = "rest-simulation:entry"
            visibility = View.GONE
        }
        restButton = entry
        super.setContentView(style.column().apply {
            setBackgroundColor(style.base)
            addView(entry)
            addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        })
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REST_DEMO_REQUEST) {
            restOpening = false
            updateRestEntry()
        }
    }

    override fun onControlsAvailabilityChanged(allowed: Boolean) = updateRestEntry(allowed)

    private fun updateRestEntry(allowed: Boolean = allowed()) {
        val enabled = !restOpening && allowed
        restButton?.apply {
            visibility = if (enabled) View.VISIBLE else View.GONE
            isEnabled = enabled
        }
    }

    private companion object {
        const val REST_DEMO_REQUEST = 1
    }
}
