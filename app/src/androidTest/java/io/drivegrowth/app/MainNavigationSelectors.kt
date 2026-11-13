package io.drivegrowth.app

import android.view.View
import android.widget.Button
import io.drivegrowth.app.host.HostPage

internal fun View.mainNavigation(): View? = findViewWithTag("host:destination")

internal fun View.mainPage(page: HostPage): Button? = findViewWithTag("host:page:${page.name}")

internal fun View.openMainPage(page: HostPage) {
    val button = checkNotNull(mainPage(page))
    check(button.isShown && button.isEnabled)
    if (!button.isSelected) check(button.performClick())
}

internal fun View.showSimulationControls() {
    val button = checkNotNull(findViewWithTag<Button>("simulation:controls"))
    if (!button.isSelected) check(button.performClick())
}
