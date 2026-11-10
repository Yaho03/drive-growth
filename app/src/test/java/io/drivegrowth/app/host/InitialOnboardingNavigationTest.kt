package io.drivegrowth.app.host

import org.junit.Assert.*
import org.junit.Test

class InitialOnboardingNavigationTest {
    @Test fun delayedIntroCannotReplaceExplicitRankingAndBackStillReturnsHome() {
        val nav=HostNavigation(page=HostPage.RANKING)
        val routed=nav.acceptInitialIntro("new-profile",null,false)
        assertEquals(HostPage.RANKING,nav.page)
        assertEquals("new-profile",routed)
        assertFalse(nav.back());assertEquals(HostPage.HOME,nav.page)
        assertEquals(routed,nav.acceptInitialIntro("new-profile",routed,false))
        assertEquals(HostPage.HOME,nav.page)
    }
    @Test fun explicitReturnHomeBeforeIntroKeepsHomeInsteadOfStartingAnotherRoute() {
        val nav=HostNavigation(page=HostPage.RANKING)
        nav.back()
        assertEquals(HostPage.HOME,nav.page)
        nav.acceptInitialIntro("new-profile",null,false)
        assertEquals(HostPage.HOME,nav.page)
    }
    @Test fun restoredPageAndItsReturnDestinationSurviveLateIntro() {
        val nav=HostNavigation(page=HostPage.GARAGE,returnPage=HostPage.GACHA)
        nav.acceptInitialIntro("new-profile",null,false)
        assertEquals(HostPage.GARAGE,nav.page);assertEquals(HostPage.GACHA,nav.returnPage)
    }
    @Test fun untouchedHomeStillAutomaticallyEntersFirstIntro() {
        val nav=HostNavigation()
        assertEquals("new-profile",nav.acceptInitialIntro("new-profile",null,true))
        assertEquals(HostPage.ONBOARDING,nav.page)
    }
    @Test fun homeRecreatedBeforeAnyExplicitNavigationRetainsAutomaticEntryIntent() {
        val restored=HostNavigation(page=HostPage.HOME)
        restored.acceptInitialIntro("new-profile",null,true)
        assertEquals(HostPage.ONBOARDING,restored.page)
    }
    @Test fun missingProfileCannotInventAnIntroAndSameProfileNeverRoutesTwice() {
        val nav=HostNavigation()
        assertNull(nav.acceptInitialIntro(null,null,true));assertEquals(HostPage.HOME,nav.page)
        nav.acceptInitialIntro("new-profile","new-profile",true)
        assertEquals(HostPage.HOME,nav.page)
    }
}
