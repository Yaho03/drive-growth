package io.drivegrowth.app

import android.view.View
import android.widget.Button
import io.drivegrowth.app.host.HostPage

internal fun View.mainNavigation(): View? = findViewWithTag("host:destination")

internal fun View.mainGameControls(): View? = mainNavigation()?.takeIf { it.isShown } ?:
    findViewWithTag<View>("host:back")?.takeIf { it.isShown }

internal fun View.mainPage(page: HostPage): Button? = findViewWithTag("host:page:${page.name}")

internal fun View.isMainPageShown(page: HostPage): Boolean = if (page == HostPage.HOME) {
    mainNavigation()?.isShown == true
} else {
    sequenceOf(findViewWithTag<View>("host:current-page:${page.name}"),
        findViewWithTag<View>("host:scene-page:${page.name}")).any { it?.isShown == true }
}

internal fun View.openMainPage(page: HostPage) {
    if (isMainPageShown(page)) return
    if (page == HostPage.HOME) {
        returnToMainMap()
        return
    }
    check(checkNotNull(exposeMainPage(page)).performClick())
}

private fun View.returnToMainMap() {
    if (mainNavigation()?.isShown == true) return
    val close = findViewWithTag<Button>("host:back")?.takeIf { it.isShown && it.isEnabled } ?: return
    repeat(4) {
        if (mainNavigation()?.isShown != true && close.isShown) check(close.performClick())
    }
}

internal fun View.exposeMainPage(page: HostPage): Button? {
    if (isMainPageShown(page)) return null
    fun destination() = mainPage(page)?.takeIf { it.isShown && it.isEnabled }
    destination()?.let { return it }
    if (page == HostPage.PROGRESSION || page == HostPage.ONBOARDING) openMainSettings()
    else returnToMainMap()
    return destination()
}

internal fun View.openMainSettings(): Button? {
    fun visibleButton(tag: String) = findViewWithTag<Button>(tag)?.takeIf { it.isShown && it.isEnabled }
    visibleButton("host:settings-close")?.let { return it }
    returnToMainMap()
    val settings = visibleButton("shop:map-settings") ?: visibleButton("host:home-settings-fallback")
    if (settings?.performClick() != true) return null
    return visibleButton("host:settings-close")
}

internal fun View.exposeMainRefresh(): Button? {
    fun refresh() = findViewWithTag<Button>("host:refresh")?.takeIf { it.isShown && it.isEnabled }
    refresh()?.let { return it }
    openMainSettings() ?: return null
    return refresh()
}

internal fun View.showSimulationControls(host: MainActivity? = null) {
    fun visible(tag: String) = findViewWithTag<Button>(tag)?.takeIf { it.isShown && it.isEnabled }
    if (visible("simulation:controls") != null) return
    val recovery = visible("host:simulation-recovery-settings")
    if (recovery != null || isMainPageShown(HostPage.HOME)) {
        if (recovery != null) check(recovery.performClick()) else checkNotNull(openMainSettings())
        check(checkNotNull(visible("host:simulation-settings")).performClick())
    } else {
        // 격리 계측은 현재 탁송 화면을 유지해 재생·저장 경계를 확인한다.
        checkNotNull(host).openSimulationTool()
        check(visible("simulation:controls") != null)
    }
}
