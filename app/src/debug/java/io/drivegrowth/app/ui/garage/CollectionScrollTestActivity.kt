package io.drivegrowth.app.ui.garage

import android.app.Activity
import android.os.Bundle

/** 고정 도감의 실제 배치 시험만 위한 debug 호스트. 제품 탐색·차량 SDK·저장을 호출하지 않는다. */
class CollectionScrollTestActivity : Activity() {
    lateinit var collection: CollectionView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        collection = CollectionView(this) {}
        setContentView(collection)
        collection.render(GaragePreviewStates.collection, true)
    }
}
