package io.drivegrowth.app.ui.shop

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.view.View
import android.view.ViewGroup
import io.drivegrowth.core.contract.*

/** 이번 수정의 고정 표시 시험 전용. 제품 저장/SDK를 호출하지 않는다. */
class ShopRestoreDeviceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val old = ShopPreviewStates.normal().copy(revision = Revision(3))
        val latest = old.copy(revision = Revision(5), capacity = 7, balance = GameMoney(900))
        val saved = ShopInteraction({}).apply { render(latest, true) }.uiState
        var submissions = 0
        val view = ShopView(this) { if (it is ShopEvent.Submit) submissions++ }
        view.render(old, true)
        view.findViewWithTag<Button>("shop:preview:expand").performClick()
        val oldConfirm = view.findViewWithTag<Button>("shop:confirm")
        view.restoreUiState(saved); oldConfirm.performClick(); view.render(old, true)
        fun texts(root: View): String = (if (root is TextView) root.text.toString() else "") +
            if (root is ViewGroup) (0 until root.childCount).joinToString("\n") { texts(root.getChildAt(it)) } else ""
        check(submissions == 0 && !texts(view).contains("2,400"))
        android.util.Log.i("Shop55RestoreC", "PASS firstRestore loading=true staleSubmit=0 oldBalanceHidden=true")
        if (intent.getBooleanExtra("latest", false)) {
            view.render(latest, true)
            check(texts(view).contains("900 게임머니"))
            android.util.Log.i("Shop55RestoreC", "PASS latestRevision=5 latestBalance=900")
        }
        setContentView(view)
    }
}
