package io.drivegrowth.app.ui.gacha
import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout
import io.drivegrowth.core.contract.*
/** 격리 표시시험 전용. 제품 저장/차량SDK를 호출하지 않는다. */
class GachaReviewHostActivity:Activity() {
 lateinit var view:GachaView
 val events=mutableListOf<GachaEvent>()
 private lateinit var root:FrameLayout
 var frame=GachaPreviewStates.ready()
 var enabled=true
 var requestedWidth=936
 override fun onCreate(savedInstanceState:Bundle?) {super.onCreate(savedInstanceState);root=FrameLayout(this);setContentView(root);replace()}
 fun replace(){ val saved=if(::view.isInitialized)view.uiState else GachaUiState();if(::view.isInitialized)view.release();view=GachaView(this,events::add,saved);root.removeAllViews();root.addView(view,FrameLayout.LayoutParams((requestedWidth*resources.displayMetrics.density).toInt(),-1));view.render(frame,enabled) }
 fun width(dp:Int){requestedWidth=dp;view.layoutParams=FrameLayout.LayoutParams((dp*resources.displayMetrics.density).toInt(),-1)}
 fun restrict(value:Boolean){enabled=!value;view.render(frame,enabled)}
 fun show(value:GachaFrame){frame=value;view.render(frame,enabled)}
}
