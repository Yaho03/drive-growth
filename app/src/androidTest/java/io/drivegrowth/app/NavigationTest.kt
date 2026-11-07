package io.drivegrowth.app

import android.app.ActivityManager
import android.os.SystemClock
import android.test.ActivityInstrumentationTestCase2
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/** 실제 Task 숨김을 검증한다. 차량 신호나 게임 데이터를 주입하지 않는다. */
@Suppress("DEPRECATION")
class NavigationTest : ActivityInstrumentationTestCase2<MainActivity>(MainActivity::class.java) {
    fun testTopLevelBackHidesTask() {
        val current = activity
        instrumentation.waitForIdleSync()
        val deadline = SystemClock.uptimeMillis() + 5000
        while (!current.hasWindowFocus() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue("시험 시작 시 앱 창이 전면이어야 한다", current.hasWindowFocus())
        assertTrue("중복 Activity가 없는 최상위 시험이어야 한다", current.isTaskRoot)
        instrumentation.runOnMainSync { current.onBackPressed() }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(500)
        assertFalse("최상위 Back은 앱 Task를 숨겨야 한다", current.hasWindowFocus())
        assertTrue("포커스 이동만으로는 부족하며 게임 창도 숨겨야 한다",
            current.window.decorView.windowVisibility != View.VISIBLE)
        assertFalse("Task 숨김은 Activity 종료가 아니다", current.isFinishing)
        fun restricted(view: View): Boolean =
            view is TextView && view.text == "주차 후 조작이 허용되면 이용할 수 있습니다." ||
                view is ViewGroup && (0 until view.childCount).any { restricted(view.getChildAt(it)) }
        assertTrue("숨김 시 이전 게임 콘텐츠를 제거하고 차량 판정을 초기화해야 한다",
            restricted(current.window.decorView))
        val tasks = current.getSystemService(ActivityManager::class.java).appTasks
        instrumentation.runOnMainSync { tasks.first { it.taskInfo.persistentId == current.taskId }.moveToFront() }
        val returnDeadline = SystemClock.uptimeMillis() + 5000
        while (!current.hasWindowFocus() && SystemClock.uptimeMillis() < returnDeadline) SystemClock.sleep(50)
        assertTrue("최근 Task로 복귀하면 같은 창이 다시 표시되어야 한다", current.hasWindowFocus())
        assertEquals(View.VISIBLE, current.window.decorView.windowVisibility)
    }
}
