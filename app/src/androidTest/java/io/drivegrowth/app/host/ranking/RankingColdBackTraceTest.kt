package io.drivegrowth.app.host.ranking

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.util.Log
import android.view.View
import android.view.ViewGroup
import io.drivegrowth.app.MainActivity
import io.drivegrowth.app.host.HostNavigation
import junit.framework.TestResult
import org.json.JSONObject
import java.lang.ref.WeakReference

/** 원본 정식 시험을 그대로 실행하며 cold NEW를 관찰한다. gate/입력/DB를 바꾸지 않는다. */
@Suppress("DEPRECATION")
class RankingColdBackTraceTest : InstrumentationTestCase() {
    fun testColdOriginalFormalFlowWithPassiveMainBoundaryTrace() {
        val context=instrumentation.targetContext
        assertTrue("제품 패키지에서 실행 금지",context.packageName!="io.drivegrowth.app")
        for(name in listOf("game-ledger-vehicle.db","game-ledger-simulation.db"))
            assertFalse("cold NEW는 새 격리 패키지가 필요하며 기존 DB를 삭제하지 않는다: $name",context.getDatabasePath(name).exists())
        val app=context.applicationContext as Application
        val handler=Handler(Looper.getMainLooper());var current=WeakReference<MainActivity>(null)
        var running=true;var previous="";val trace=mutableListOf<String>()
        fun field(activity:MainActivity,name:String):Any? = runCatching {
            MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.get(activity)
        }.getOrNull()
        fun tree(v:View):List<String> = listOf(v.javaClass.simpleName)+if(v is ViewGroup)(0 until v.childCount).flatMap{tree(v.getChildAt(it))}else emptyList()
        fun snapshot(reason:String) {
            val a=current.get()?:return
            val nav=field(a,"navigation") as? HostNavigation
            val enabled=runCatching{MainActivity::class.java.getDeclaredMethod("allowed").apply{isAccessible=true}.invoke(a)}.getOrNull()
            val views=tree(a.window.decorView).filter{it in setOf("ShopView","RankingView","OnboardingView")}
            val signature=listOf(System.identityHashCode(a),nav?.page,nav?.returnPage,enabled,field(a,"routedNewProfile"),
                field(a,"onboardingState")?.javaClass?.simpleName,views,a.hasWindowFocus(),a.isFinishing).joinToString("|")
            if(reason!="sample"||signature!=previous) {
                previous=signature
                val row=JSONObject().put("elapsed",SystemClock.elapsedRealtime()).put("utc",System.currentTimeMillis())
                    .put("reason",reason).put("activity",System.identityHashCode(a)).put("page",nav?.page?.name)
                    .put("returnPage",nav?.returnPage?.name).put("allowed",enabled).put("routedProfile",field(a,"routedNewProfile"))
                    .put("onboarding",field(a,"onboardingState")?.javaClass?.simpleName).put("attached",views.joinToString(","))
                    .put("focused",a.hasWindowFocus()).put("finishing",a.isFinishing).toString()
                trace+=row;Log.i("DGColdRankingBack",row)
            }
        }
        val sampler=object:Runnable{override fun run(){if(running){snapshot("sample");handler.postDelayed(this,16)}}}
        val callbacks=object:Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a:Activity,b:Bundle?){if(a is MainActivity){current=WeakReference(a);snapshot("created")}}
            override fun onActivityStarted(a:Activity){if(a is MainActivity){current=WeakReference(a);snapshot("started")}}
            override fun onActivityResumed(a:Activity){if(a is MainActivity){current=WeakReference(a);snapshot("resumed")}}
            override fun onActivityPaused(a:Activity){if(a is MainActivity)snapshot("paused")}
            override fun onActivityStopped(a:Activity){if(a is MainActivity)snapshot("stopped")}
            override fun onActivitySaveInstanceState(a:Activity,b:Bundle){if(a is MainActivity)snapshot("saved")}
            override fun onActivityDestroyed(a:Activity){if(a is MainActivity)snapshot("destroyed")}
        }
        val result=TestResult()
        try {
            instrumentation.runOnMainSync{app.registerActivityLifecycleCallbacks(callbacks);handler.post(sampler)}
            val original=RankingMainActivityTest()
            original.injectInstrumentation(instrumentation)
            original.name="testMainExplicitDemoSelectionRecreationAndBackPreserveActualRecords"
            original.run(result) // 원본 method/HOME 기대/대기시간을 변경하지 않는다.
        } finally {
            instrumentation.runOnMainSync{running=false;handler.removeCallbacks(sampler);app.unregisterActivityLifecycleCallbacks(callbacks)}
            context.cacheDir.resolve("ranking-cold-back-trace.jsonl").writeText(trace.joinToString("\n",postfix="\n"))
        }
        val failures=result.failures().toList().joinToString("\n"){it.trace()}
        val errors=result.errors().toList().joinToString("\n"){it.trace()}
        Log.i("DGColdRankingBack","original failures=${result.failureCount()} errors=${result.errorCount()}\n$failures\n$errors")
        assertEquals("원본 정식 시험 오류: $errors",0,result.errorCount())
        assertEquals("원본 HOME 기대 실패를 보존: $failures",0,result.failureCount())
    }
}
