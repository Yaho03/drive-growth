package io.drivegrowth.app.ui.presentation

import android.media.MediaMetadataRetriever
import android.test.InstrumentationTestCase

@Suppress("DEPRECATION")
class PrototypeMusicDeviceTest : InstrumentationTestCase() {
    fun testOnlyTitleAndRestHaveMusicAndBothPackagedTracksAreReadable() {
        val tracks = PrototypeAudio.Scene.entries.mapNotNull { scene ->
            scene.fileName?.let { scene to it }
        }.toMap()
        assertEquals(mapOf(PrototypeAudio.Scene.TITLE to "title.mp3", PrototypeAudio.Scene.REST to "rest.mp3"), tracks)
        val assets = instrumentation.targetContext.assets
        for ((file, minimumDurationMs) in listOf("title.mp3" to 145_000L, "rest.mp3" to 175_000L)) {
            val metadata = MediaMetadataRetriever()
            try {
                assets.openFd("bgm/$file").use {
                    metadata.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                }
                val duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                assertTrue("$file has unexpected duration: $duration", duration != null && duration >= minimumDurationMs)
            } finally {
                metadata.release()
            }
        }
    }
}
