package io.drivegrowth.app

import android.view.View
import android.widget.Button
import io.drivegrowth.app.host.HostPage

internal fun View.mainNavigation(): View? = findViewWithTag("host:destination")

internal fun View.mainPage(page: HostPage): Button? =
    findViewWithTag<Button>("host:section:${page.name}") ?: findViewWithTag("host:page:${page.name}")

internal fun View.openMainPage(page: HostPage) {
    val button = checkNotNull(exposeMainPage(page))
    if (!button.isSelected) check(button.performClick())
}

/** Tests may open a section before looking up a destination within it. */
internal fun View.exposeMainPage(page: HostPage): Button? {
    fun visibleButton(tag: String) = findViewWithTag<Button>(tag)?.takeIf { it.isShown && it.isEnabled }
    visibleButton("host:section:${page.name}")?.let { return it }
    visibleButton("host:page:${page.name}")?.let { return it }
    val entry = when (page) {
        HostPage.COLLECTION -> mainPage(HostPage.GARAGE)
        HostPage.PROGRESSION, HostPage.RANKING -> mainPage(HostPage.RESULTS)
        HostPage.ONBOARDING -> visibleButton("shop:map-settings") ?: mainPage(HostPage.HOME)
        else -> null
    }
    if (entry?.isShown == true && entry.isEnabled) entry.performClick()
    if (page == HostPage.ONBOARDING && visibleButton("host:page:ONBOARDING") == null) {
        visibleButton("shop:map-settings")?.performClick()
    }
    return visibleButton("host:section:${page.name}") ?: visibleButton("host:page:${page.name}")
}

internal fun View.exposeMainRefresh(): Button? {
    findViewWithTag<Button>("host:refresh")?.takeIf { it.isShown && it.isEnabled }?.let { return it }
    findViewWithTag<Button>("shop:map-settings")?.takeIf { it.isShown && it.isEnabled }?.performClick()
    return findViewWithTag<Button>("host:refresh")?.takeIf { it.isShown && it.isEnabled }
}

internal fun View.showSimulationControls() {
    val button = checkNotNull(findViewWithTag<Button>("simulation:controls"))
    if (!button.isSelected) check(button.performClick())
}
