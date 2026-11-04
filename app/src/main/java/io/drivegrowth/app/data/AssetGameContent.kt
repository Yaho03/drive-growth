package io.drivegrowth.app.data

import android.content.res.AssetManager
import io.drivegrowth.core.content.ContentLoad
import io.drivegrowth.core.content.GameContentLoader
import java.io.IOException

internal class AssetGameContent(private val read: (String) -> String) {
    constructor(assets: AssetManager) : this({ path ->
        assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
    })

    fun load(): ContentLoad = try {
        GameContentLoader.load(read("content/vehicle-facts.csv"), read("content/game-content.csv"))
    } catch (_: IOException) {
        ContentLoad.Rejected("콘텐츠 파일을 읽을 수 없습니다.")
    }
}
