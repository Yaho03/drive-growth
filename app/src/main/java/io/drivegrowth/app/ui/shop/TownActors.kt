package io.drivegrowth.app.ui.shop

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/** 마을 원본의 1536×1024 좌표에서 장식 인물을 그린다. */
internal class TownActors(context: Context) {
    private val sprites = loadSprites(context.assets)
    private val paint = Paint().apply { isFilterBitmap = false }
    private val bounds = RectF()

    fun draw(canvas: Canvas, elapsedSeconds: Float, fuelInstalled: Boolean, washInstalled: Boolean) {
        val poses = routes.mapNotNull { route ->
            when (route.facility) {
                Place.FUEL -> if (!fuelInstalled) return@mapNotNull null
                Place.WASH -> if (!washInstalled) return@mapNotNull null
                null -> Unit
            }
            val time = elapsedSeconds + route.offset
            val phase = (time % route.duration) / route.duration
            val progress = when {
                phase < .1f -> 0f
                phase < .45f -> (phase - .1f) / .35f
                phase < .6f -> 1f
                phase < .95f -> 1f - (phase - .6f) / .35f
                else -> 0f
            }
            val walking = elapsedSeconds > 0f && (phase in .1f..<.45f || phase in .6f..<.95f)
            val frame = when {
                route.sprite != "owner" -> route.sprite
                walking -> "walk${(time * 10f).toInt() % 8 + 1}"
                elapsedSeconds == 0f -> "idle5"
                else -> idleFrames[(time * 2f).toInt() % idleFrames.size]
            }
            val bob = if (walking && route.sprite != "owner" && (time * 6f).toInt() % 2 == 1) 1.4f else 0f
            Pose(
                route.fromX + (route.toX - route.fromX) * progress,
                route.fromY + (route.toY - route.fromY) * progress - bob,
                route.width,
                frame,
                phase in .53f..<.95f,
            )
        }
        poses.sortedBy { it.y }.forEach { pose ->
            val sprite = sprites.getValue(pose.frame)
            val halfWidth = pose.width / 2f
            val height = pose.width * sprite.height / sprite.width
            bounds.set(pose.x - halfWidth, pose.y - height, pose.x + halfWidth, pose.y)
            canvas.save()
            if (pose.flip) canvas.scale(-1f, 1f, pose.x, pose.y)
            canvas.drawBitmap(sprite, null, bounds, paint)
            canvas.restore()
        }
    }

    private data class Pose(val x: Float, val y: Float, val width: Float, val frame: String, val flip: Boolean)
    private enum class Place { FUEL, WASH }
    private data class Route(
        val sprite: String,
        val fromX: Float,
        val fromY: Float,
        val toX: Float,
        val toY: Float,
        val duration: Float,
        val offset: Float,
        val width: Float = 32f,
        val facility: Place? = null,
    )

    companion object {
        private val routes = listOf(
            Route("guest", 595f, 580f, 825f, 695f, 22f, 0f),
            Route("guest", 745f, 750f, 945f, 850f, 24f, 8f),
            Route("mechanic", 1175f, 660f, 1285f, 715f, 16f, 4f, facility = Place.WASH),
            Route("fuel", 130f, 318f, 300f, 302f, 18f, 6f, facility = Place.FUEL),
            Route("mechanic", 720f, 595f, 940f, 705f, 21f, 11f),
            Route("owner", 670f, 665f, 790f, 725f, 20f, 2f, width = 64f),
        )
        private val idleFrames = listOf("idle5", "idle5", "idle3", "idle4", "idle5", "idle5")
        private var cachedSprites: Map<String, Bitmap>? = null

        @Synchronized
        private fun loadSprites(assets: AssetManager): Map<String, Bitmap> = cachedSprites ?: (
            listOf("guest", "mechanic", "fuel") + (1..8).map { "walk$it" } + idleFrames.distinct()
        ).associateWith { name ->
            assets.open("pixel/prototype-actors/$name.png").use { stream ->
                requireNotNull(BitmapFactory.decodeStream(stream)).apply { density = Bitmap.DENSITY_NONE }
            }
        }.also { cachedSprites = it }
    }
}
