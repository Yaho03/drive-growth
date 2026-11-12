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
    private var restInMainButton: Button? = null
    private var restOpening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        restOpening = savedInstanceState?.getBoolean("restDemoOpening") == true
        super.onCreate(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("restDemoOpening", restOpening)
    }

    override fun addSimulationActions(content: LinearLayout) {
        val style = GarageStyle(this)
        val entry = style.button("휴식 시연 (별도)", enabled = false) {
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
        val restInMain = style.button("휴식 포함 모의주행", enabled = false, onClick = ::startRestSimulation).apply {
            tag = "simulation:rest-start"
            visibility = View.GONE
        }
        restInMainButton = restInMain
        content.addView(style.row().apply {
            addView(entry, LinearLayout.LayoutParams(0, -2, 1f))
            addView(restInMain, LinearLayout.LayoutParams(0, -2, 1f))
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
        restInMainButton?.apply {
            val inMainEnabled = enabled && canStartRestSimulation()
            visibility = if (inMainEnabled) View.VISIBLE else View.GONE
            isEnabled = inMainEnabled
        }
    }

    private companion object {
        const val REST_DEMO_REQUEST = 1
    }
}
