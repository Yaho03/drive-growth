package io.drivegrowth.app.ui.shop

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF

/** 마을 원본의 1536×1024 좌표에서 장식 인물을 그린다. */
internal class TownActors(context: Context, private val population: TownPopulation? = null) {
    private val sprites = loadSprites(context.assets)
    private val paint = Paint().apply { isFilterBitmap = false }
    private val bounds = RectF()

    private val effect = Paint(Paint.ANTI_ALIAS_FLAG)
    private val heart = Path().apply {
        moveTo(0f, 12f)
        cubicTo(-18f, 0f, -9f, -11f, 0f, -4f)
        cubicTo(9f, -11f, 18f, 0f, 0f, 12f)
        close()
    }
    private val tints = population?.residents.orEmpty().mapNotNull { it.role.tint }.distinct().associateWith {
        PorterDuffColorFilter(it, PorterDuff.Mode.MULTIPLY)
    }
    private val drawOrder = population?.residents.orEmpty().toMutableList()
    private val depth = compareBy<TownPopulation.Resident> { it.held }.thenBy { it.point.y }
    private var lastFrame: Long? = null

    fun update(now: Long, animate: Boolean) {
        if (!animate) {
            stop()
            return
        }
        val previous = lastFrame
        lastFrame = now
        if (previous != null) population?.step(((now - previous) / 1000f).coerceIn(0f, .10f))
    }

    fun stop() {
        lastFrame = null
        population?.stopReactions()
    }

    fun hit(x: Float, y: Float, minimumSize: Float): TownPopulation.Resident? =
        drawOrder.asReversed().firstOrNull { resident ->
            if (!resident.visible) return@firstOrNull false
            val sprite = sprite(resident)
            val width = actorWidth(resident)
            val height = width * sprite.height / sprite.width
            val halfWidth = maxOf(width, minimumSize) / 2f
            val halfHeight = maxOf(height, minimumSize) / 2f
            kotlin.math.abs(x - resident.point.x) <= halfWidth &&
                kotlin.math.abs(y - (resident.point.y - height / 2f)) <= halfHeight
        }

    fun moveHeld(resident: TownPopulation.Resident, x: Float, y: Float) {
        population?.moveHeld(resident, TownPoint(x, y))
    }

    fun draw(canvas: Canvas, animate: Boolean) {
        drawOrder.sortWith(depth)
        for (resident in drawOrder) {
            if (!resident.visible) continue
            val sprite = sprite(resident)
            val width = actorWidth(resident)
            val height = width * sprite.height / sprite.width
            val x = resident.point.x
            val y = resident.point.y
            val pose = resident.activity
            val lift = if (resident.held) 24f else 24f * resident.drop / .24f
            val bob = if ((pose == TownPopulation.Activity.WALKING || resident.held) &&
                (resident.clock * 8f).toInt() % 2 == 0) 2f else 0f
            val doorOffset = when (pose) {
                TownPopulation.Activity.ENTERING -> (1f - resident.wait / .4f) * 8f
                TownPopulation.Activity.EXITING -> resident.wait / .4f * 8f
                else -> 0f
            }
            val bottom = y - lift - bob - doorOffset
            bounds.set(x - width / 2f, bottom - height, x + width / 2f, bottom)
            paint.colorFilter = resident.role.tint?.let(tints::get)
            paint.alpha = if (doorOffset > 0f) ((1f - doorOffset / 8f) * 255).toInt().coerceIn(0, 255) else 255
            canvas.save()
            if (resident.flip) canvas.scale(-1f, 1f, x, bottom)
            if (resident.held) canvas.rotate(if ((resident.clock * 8f).toInt() % 2 == 0) 10f else -10f, x, bottom - height / 2f)
            else if (resident.reaction > 0f) canvas.rotate(if ((resident.reaction * 10f).toInt() % 2 == 0) 6f else -6f, x, bottom)
            canvas.drawBitmap(sprite, null, bounds, paint)
            canvas.restore()
            paint.colorFilter = null
            paint.alpha = 255
            if (animate) drawReaction(canvas, resident, x, bottom - height)
        }
    }

    private fun drawReaction(canvas: Canvas, resident: TownPopulation.Resident, x: Float, top: Float) {
        when {
            resident.held -> {
                effect.color = 0xff9cc2ff.toInt()
                canvas.drawOval(x + 12f, top - 12f, x + 20f, top + 1f, effect)
            }
            resident.reaction > 0f -> {
                effect.color = 0xffffdeb7.toInt()
                val hand = if ((resident.reaction * 10f).toInt() % 2 == 0) 1f else -1f
                canvas.drawCircle(x + 15f, top + 8f + hand * 3f, 3f, effect)
                effect.color = 0xfff08caa.toInt()
                canvas.save()
                canvas.translate(x, top - 12f - (1f - resident.reaction) * 9f)
                canvas.drawPath(heart, effect)
                canvas.restore()
            }
            resident.activity == TownPopulation.Activity.WORKING -> {
                effect.color = 0xffc8d3dc.toInt()
                canvas.drawLine(x - 15f, top + 21f, x - 23f, top + 12f, effect)
                canvas.drawCircle(x - 23f, top + 12f, 3f, effect)
                effect.color = 0xfffcdc64.toInt()
                val reach = if ((resident.clock * 6f).toInt() % 2 == 0) 4f else 8f
                canvas.drawRect(x - reach, top - 10f, x + reach, top - 7f, effect)
                canvas.drawRect(x - 1.5f, top - 8.5f - reach, x + 1.5f, top - 8.5f + reach, effect)
            }
        }
    }

    private fun actorWidth(resident: TownPopulation.Resident) = if (resident.role.sprite == "owner") 64f else 32f

    private fun sprite(resident: TownPopulation.Resident): Bitmap = when (resident.role.sprite) {
        "wash" -> sprites.getValue("mechanic")
        "owner" -> if (resident.activity == TownPopulation.Activity.WALKING)
            sprites.getValue("walk${(resident.clock * 10f).toInt() % 8 + 1}") else sprites.getValue("idle5")
        else -> sprites.getValue(resident.role.sprite)
    }

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
