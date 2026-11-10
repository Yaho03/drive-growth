package io.drivegrowth.app.ui.shop

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import io.drivegrowth.app.R
import io.drivegrowth.core.rental.Facility
import kotlin.math.min

/** 직접 그린 중립 2D 자산. 실차 외형/로고/캐릭터를 사용하지 않으며 배치는 표시용이다. */
// 확정 표시 입력을 필수로 받아 코드에서만 만든다. XML용 임의 기본 상태를 만들지 않는다.
@SuppressLint("ViewConstructor")
internal class ShopSceneView(context: Context, private val content: ShopScreenState.Content, private val mode: ShopMode) : View(context) {
    internal val model = shopSceneModel(content)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val bounds = RectF()
    private val sky = Color.rgb(173, 210, 240)
    private val grass = Color.rgb(152, 185, 115)
    private val pavement = Color.rgb(72, 85, 101)
    private val cream = Color.rgb(226, 225, 207)
    private val roof = Color.rgb(209, 89, 57)
    private val palettes = intArrayOf(0xff6d9292.toInt(), 0xff4a73ae.toInt(), 0xffd3c5a4.toInt(), 0xff7f939f.toInt())
    init {
        tag = "shop:scene"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = buildString {
            append(context.getString(R.string.shop_scene_description, content.appearance.sign))
            append(". ")
            append(content.capacity?.let { context.getString(R.string.shop_scene_spaces, it) } ?: context.getString(R.string.shop_scene_spaces_unknown))
            content.vehicles.forEach { append(". ${it.name}: ${it.status}") }
        }
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val desired = (w * .73f).toInt().coerceAtLeast((240 * resources.displayMetrics.density).toInt())
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(desired, heightMeasureSpec))
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = sky
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(bounds, 28f, 28f, paint)
        canvas.save()
        val scale = min(width / 1000f, height / 720f)
        canvas.translate((width - 1000 * scale) / 2, (height - 720 * scale) / 2)
        canvas.scale(scale, scale)
        // 전경의 토대와 도로는 장식이며 별도의 공간/시설 보유 사실을 만들지 않는다.
        box(canvas, 0f, 0f, 620f, 420f, 18f, 0xffa77e51.toInt(), -18f)
        plane(canvas, 0f, 0f, 620f, 420f, 0f, grass)
        if (mode == ShopMode.OVERALL) {
            plane(canvas, 55f, 190f, 465f, 405f, 1f, pavement)
            building(canvas)
            model.installed.forEach { facility(canvas, it) }
            parking(canvas, 70f, 208f, 62f, 88f, 6)
        } else {
            plane(canvas, 25f, 20f, 595f, 405f, 1f, pavement)
            parking(canvas, 48f, 40f, 175f, 85f, 3)
        }
        if (model.slots == null) label(canvas, context.getString(R.string.shop_scene_spaces_unknown), 500f, 665f, 26f, 0xff24384b.toInt())
        canvas.restore()
    }
    private fun point(x: Float, y: Float, z: Float = 0f) = (430f + (x - y) * .83f) to (90f + (x + y) * .48f - z)
    private fun polygon(canvas: Canvas, color: Int, vararg points: Pair<Float, Float>) {
        path.reset(); path.moveTo(points[0].first, points[0].second)
        points.drop(1).forEach { path.lineTo(it.first, it.second) }; path.close()
        paint.style = Paint.Style.FILL; paint.color = color; canvas.drawPath(path, paint)
    }
    private fun plane(canvas: Canvas, x: Float, y: Float, right: Float, bottom: Float, z: Float, color: Int) =
        polygon(canvas, color, point(x,y,z), point(right,y,z), point(right,bottom,z), point(x,bottom,z))
    private fun box(canvas: Canvas, x: Float, y: Float, right: Float, bottom: Float, height: Float, color: Int, floor: Float = 1f) {
        polygon(canvas, shade(color,.74f), point(right,y,floor), point(right,bottom,floor), point(right,bottom,height), point(right,y,height))
        polygon(canvas, shade(color,.87f), point(x,bottom,floor), point(right,bottom,floor), point(right,bottom,height), point(x,bottom,height))
        plane(canvas,x,y,right,bottom,height,color)
    }
    private fun shade(color: Int, amount: Float) = Color.rgb((Color.red(color)*amount).toInt(),(Color.green(color)*amount).toInt(),(Color.blue(color)*amount).toInt())
    private fun building(canvas: Canvas) {
        box(canvas,100f,30f,425f,163f,92f,cream)
        // 승인된 level 공급이 성장한 경우만 외형을 넓힌다. 새 확장 규칙/구매를 계산하지 않는다.
        if (model.expandedBuilding) {
            box(canvas,28f,43f,95f,163f,76f,cream)
            plane(canvas,20f,35f,100f,171f,81f,roof)
        }
        polygon(canvas,0xff37536a.toInt(), point(114f,164f,12f),point(408f,164f,12f),point(408f,164f,65f),point(114f,164f,65f))
        for (x in listOf(188f,264f,340f)) {
            polygon(canvas,cream,point(x,165f,12f),point(x+5,165f,12f),point(x+5,165f,65f),point(x,165f,65f))
        }
        plane(canvas,88f,18f,439f,177f,101f,roof)
        for (i in 0..7) plane(canvas,100f+i*40,165f,140f+i*40,200f,79f,if(i%2==0)cream else roof)
        val sign = point(265f,65f,116f)
        paint.color = content.appearance.accentArgb
        canvas.drawRoundRect(RectF(sign.first-137,sign.second-21,sign.first+137,sign.second+23),8f,8f,paint)
        val signColor = content.appearance.accentArgb
        val signInk = if (Color.red(signColor)*.299 + Color.green(signColor)*.587 + Color.blue(signColor)*.114 > 160) 0xff152638.toInt() else Color.WHITE
        label(canvas,content.appearance.sign,sign.first,sign.second+10,29f,signInk,250f)
        // 식재는 장식이며 미확인 시설의 설치 사실을 표현하지 않는다.
        for ((x,y) in listOf(20f to 25f,20f to 350f,600f to 400f)) {
            val p=point(x,y); paint.color=0xff52784a.toInt();canvas.drawOval(RectF(p.first-25,p.second-30,p.first+25,p.second+9),paint)
            paint.color=0xff759754.toInt();canvas.drawCircle(p.first-4,p.second-20,22f,paint)
        }
    }
    private fun parking(canvas: Canvas, x: Float, y: Float, stepX: Float, stepY: Float, columns: Int) {
        val count = model.slots ?: model.vehicles.size
        repeat(count) { i ->
            val left=x+(i%columns)*stepX;val top=y+(i/columns)*stepY
            if (model.slots != null) {
                paint.color=0xffd5dce1.toInt();paint.style=Paint.Style.STROKE;paint.strokeWidth=2.5f
                val a=point(left,top,2f);val b=point(left,top+stepY-8,2f);val c=point(left+stepX-8,top+stepY-8,2f)
                canvas.drawLine(a.first,a.second,b.first,b.second,paint);canvas.drawLine(b.first,b.second,c.first,c.second,paint)
                paint.style=Paint.Style.FILL
            }
            model.vehicles.getOrNull(i)?.let { car(canvas,left+8,top+10,if(mode==ShopMode.VEHICLES)92f else 39f,stepY-25,it.id.value.hashCode()) }
        }
    }
    private fun car(canvas: Canvas,x:Float,y:Float,w:Float,h:Float,key:Int) {
        val color=palettes[(key and Int.MAX_VALUE)%palettes.size]
        val base=point(x+w*.5f,y+h*.5f)
        paint.color=0x30203040;canvas.drawOval(RectF(base.first-35,base.second-8,base.first+37,base.second+18),paint)
        for (dy in listOf(h*.2f,h*.8f)) {
            val p=point(x+w,y+dy,7f);paint.color=0xff23313d.toInt();canvas.drawCircle(p.first,p.second,7f,paint)
        }
        box(canvas,x,y,x+w,y+h,22f,color)
        box(canvas,x+4,y+12,x+w-4,y+h-13,37f,0xff254253.toInt())
        plane(canvas,x+5,y+19,x+w-5,y+h-19,39f,color)
        val light=point(x+w*.5f,y+h+1,14f);paint.color=0xffe9ece1.toInt();canvas.drawRoundRect(RectF(light.first-8,light.second-2,light.first+8,light.second+2),2f,2f,paint)
    }
    private fun facility(canvas:Canvas,f:Facility) {
        val y=when(f){Facility.FUEL_STATION->45f;Facility.INSURANCE->173f;Facility.CAR_WASH->292f}
        plane(canvas,485f,y,600f,y+93,2f,0xffd4d6c6.toInt())
        when(f) {
            Facility.FUEL_STATION -> {
                box(canvas,557f,y+45,580f,y+68,38f,0xff3e8067.toInt())
                box(canvas,490f,y+4,594f,y+68,72f,0xffe4e9df.toInt())
            }
            Facility.INSURANCE -> box(canvas,500f,y+10,585f,y+76,65f,0xffc3cbbc.toInt())
            Facility.CAR_WASH -> {
                box(canvas,493f,y+12,590f,y+78,65f,0xff779eac.toInt())
                polygon(canvas,0xff254253.toInt(),point(510f,y+79,4f),point(575f,y+79,4f),point(575f,y+79,48f),point(510f,y+79,48f))
            }
        }
    }
    private fun label(canvas:Canvas,text:String,x:Float,y:Float,size:Float,color:Int,maxWidth:Float=900f) {
        paint.color=color;paint.textSize=size;paint.textAlign=Paint.Align.CENTER
        val shown=if(paint.measureText(text)>maxWidth) text.take(10)+"…" else text
        canvas.drawText(shown,x,y,paint)
    }
}
