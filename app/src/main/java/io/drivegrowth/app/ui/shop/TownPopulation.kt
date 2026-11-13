package io.drivegrowth.app.ui.shop

import kotlin.math.abs
import kotlin.random.Random

/** 마을에만 존재하는 주민 상태. 실제 방문·수리나 저장 동작으로 이어지지 않는다. */
internal class TownPopulation(
    private val parked: List<TownPoint>,
    private val repairs: List<TownPoint>,
    fuelInstalled: Boolean,
    washInstalled: Boolean,
    private val random: Random = Random.Default,
) {
    private val map = TownWalkMap.forParking(parked)
    val residents = roles.filter { (it.sprite != "fuel" || fuelInstalled) && (it.sprite != "wash" || washInstalled) }
        .mapNotNull { role ->
            val home = role.zones.flatMap(map::destinations).minByOrNull { it.distanceTo(role.home) } ?: return@mapNotNull null
            val component = map.component(home) ?: return@mapNotNull null
            Resident(role, home, component, wait = random.nextFloat() * 3f)
        }

    fun step(seconds: Float) {
        for ((index, resident) in residents.withIndex()) {
            if (resident.held) {
                resident.clock += seconds
                continue
            }
            if (resident.pressed) continue
            resident.clock += seconds
            resident.reaction = (resident.reaction - seconds).coerceAtLeast(0f)
            resident.drop = (resident.drop - seconds).coerceAtLeast(0f)
            if (resident.reaction > 0f || resident.drop > 0f) continue
            when (resident.activity) {
                Activity.WALKING -> walk(resident, index, seconds)
                Activity.ENTERING, Activity.INSIDE, Activity.EXITING,
                Activity.WORKING, Activity.INSPECTING, Activity.IDLE -> {
                    resident.wait -= seconds
                    if (resident.wait <= 0f) advance(resident)
                }
            }
        }
    }

    private fun advance(resident: Resident) {
        when (resident.activity) {
            Activity.ENTERING -> resident.pause(Activity.INSIDE, 10f + random.nextFloat() * 15f)
            Activity.INSIDE -> resident.pause(Activity.EXITING, .4f)
            Activity.EXITING, Activity.WORKING, Activity.INSPECTING -> resident.pause(Activity.IDLE, 1f + random.nextFloat())
            else -> chooseRoute(resident)
        }
    }

    private fun chooseRoute(resident: Resident) {
        val role = resident.role
        if (role.repairs && repairs.isNotEmpty()) {
            for (car in repairs) for (offset in repairOffsets) {
                val target = TownPoint(car.x + offset.x, car.y + offset.y)
                if (route(resident, target, Activity.WORKING)) return
            }
        }
        if (role.entryChance > 0f && random.nextFloat() < role.entryChance && route(resident, door, Activity.ENTERING)) return
        if (role.inspects && parked.isNotEmpty() && random.nextFloat() < .35f) {
            val car = parked.random(random)
            if (route(resident, TownPoint(car.x + 61f, car.y + 18f), Activity.INSPECTING)) return
        }
        repeat(5) {
            val point = map.destinations(role.zones.random(random)).randomOrNull(random)
            if (point != null && point.distanceTo(resident.point) >= 16f && route(resident, point, Activity.IDLE)) return
        }
        resident.pause(Activity.IDLE, 2f)
    }

    private fun route(resident: Resident, target: TownPoint, arrival: Activity): Boolean {
        val path = map.path(resident.point, target) ?: return false
        resident.path = path
        resident.waypoint = 0
        resident.arrival = arrival
        resident.activity = Activity.WALKING
        resident.blocked = 0f
        return true
    }

    private fun walk(resident: Resident, index: Int, seconds: Float) {
        val next = resident.path[resident.waypoint]
        val distance = resident.point.distanceTo(next)
        val stride = resident.role.speed * seconds
        val reached = distance <= stride
        val target = if (reached) next else resident.point.toward(next, stride / distance)
        // 격자에서 연결한 구간도 실제 한 발의 위치를 확인한다.
        if (!map.allows(target)) {
            resident.pause(Activity.IDLE, 1f)
            return
        }
        val blocked = residents.indices.any { otherIndex ->
            val other = residents[otherIndex]
            otherIndex != index && other.visible && !other.held &&
                (other.activity != Activity.WALKING || otherIndex < index) &&
                abs(other.point.x - target.x) < 19f && abs(other.point.y - target.y) < 10f &&
                target.distanceTo(other.point) < resident.point.distanceTo(other.point)
        }
        if (blocked) {
            resident.blocked += seconds
            if (resident.blocked > 1.4f) resident.pause(Activity.IDLE, .5f + random.nextFloat())
            return
        }
        resident.blocked = 0f
        if (abs(target.x - resident.point.x) > .1f) resident.flip = target.x < resident.point.x
        resident.point = target
        if (reached && ++resident.waypoint == resident.path.size) {
            resident.pause(resident.arrival, when (resident.arrival) {
                Activity.ENTERING -> .4f
                Activity.WORKING -> 7f
                else -> 1f + random.nextFloat() * 2f
            })
        }
    }

    fun moveHeld(resident: Resident, point: TownPoint) {
        if (resident.held && map.component(point) == resident.homeComponent && map.clearLine(resident.point, point)) {
            resident.point = point
            resident.pause(Activity.IDLE, .8f)
        }
    }

    fun stopReactions() {
        for (resident in residents) {
            resident.pressed = false
            resident.held = false
            resident.reaction = 0f
            resident.drop = 0f
        }
    }

    internal class Resident(val role: Role, var point: TownPoint, val homeComponent: Int, var wait: Float) {
        var activity = Activity.IDLE
        var arrival = Activity.IDLE
        var path = emptyList<TownPoint>()
        var waypoint = 0
        var flip = false
        var blocked = 0f
        var clock = 0f
        var pressed = false
        var held = false
        var reaction = 0f
        var drop = 0f
        val visible get() = activity != Activity.INSIDE
        fun pause(next: Activity, seconds: Float) {
            activity = next
            wait = seconds
            path = emptyList()
        }
    }

    internal enum class Activity { IDLE, WALKING, ENTERING, INSIDE, EXITING, WORKING, INSPECTING }

    internal data class Role(
        val sprite: String,
        val home: TownPoint,
        val zones: List<TownZone>,
        val speed: Float,
        val entryChance: Float = 0f,
        val inspects: Boolean = false,
        val repairs: Boolean = false,
        val tint: Int? = null,
    )

    companion object {
        private val door = TownPoint(806f, 568f)
        private val repairOffsets = listOf(
            TownPoint(16f, 42f), TownPoint(48f, 35f), TownPoint(0f, 48f), TownPoint(-35f, 35f),
            TownPoint(0f, -32f), TownPoint(16f, -32f), TownPoint(32f, -28f), TownPoint(-16f, -32f),
        )
        private val roles = listOf(
            Role("guest", TownPoint(595f, 580f), listOf(TownZone.LOT, TownZone.PLAZA), 36f, entryChance = .06f),
            Role("guest", TownPoint(745f, 750f), listOf(TownZone.LOT, TownZone.RIGHT), 34f),
            Role("mechanic", TownPoint(1175f, 660f), listOf(TownZone.RIGHT, TownZone.LOT), 42f, repairs = true),
            Role("fuel", TownPoint(130f, 318f), listOf(TownZone.FUEL), 30f),
            Role("wash", TownPoint(1285f, 700f), listOf(TownZone.WASH, TownZone.RIGHT), 34f),
            Role("mechanic", TownPoint(720f, 595f), listOf(TownZone.PLAZA, TownZone.LOT), 38f, entryChance = .08f, inspects = true),
            Role("owner", TownPoint(670f, 665f), listOf(TownZone.LOT, TownZone.PLAZA), 28f),
            Role("guest", TownPoint(700f, 720f), listOf(TownZone.LOT, TownZone.PLAZA), 35f, entryChance = .30f, tint = 0xff8de5cb.toInt()),
            Role("guest", TownPoint(880f, 760f), listOf(TownZone.LOT, TownZone.RIGHT, TownZone.PLAZA), 33f,
                entryChance = .25f, tint = 0xffc0a1f4.toInt()),
        )
    }
}
