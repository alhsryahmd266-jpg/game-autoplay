package com.omega.autoplay

import android.graphics.PointF
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.hypot

enum class Role(val ar: String) {
    ENEMY("عدو"), ENEMY_HEAD("رأس عدو"), ALLY("صديق"), LOOT("غنيمة / سلاح"),
    JOYSTICK("عصا الحركة"), FIRE("زر النار"), JUMP("قفز"), CROUCH("انحناء"), PRONE("انبطاح"),
    RELOAD("تعبئة"), SWAP("تبديل سلاح"), HEAL("علاج"), INTERACT("التقاط / تفاعل"), SPRINT("جري"),
    SCOPE("تصويب"), GRENADE("قنبلة"), MELEE("سكين"),
    HEALTH_BAR("شريط الصحة"), LOW_HEALTH("صحة منخفضة"), MINIMAP("الخريطة"), ZONE_TARGET("مركز الزون"),
    ZONE_WARNING("تحذير / حافة الزون"), CROSSHAIR("مؤشر التصويب"), MENU_BUTTON("زر قائمة"), IGNORE("تجاهل");

    val isControl: Boolean get() = this in CONTROLS

    companion object {
        val CONTROLS = setOf(JOYSTICK, FIRE, JUMP, CROUCH, PRONE, RELOAD, SWAP, HEAL, INTERACT, SPRINT, SCOPE, GRENADE, MELEE)
        val HUD = CONTROLS + setOf(HEALTH_BAR, MINIMAP)
    }
}

object Roles {
    private val table: List<Pair<Role, List<String>>> = listOf(
        Role.ALLY to listOf("ally", "friend", "friendly", "teammate", "team", "mate", "squad"),
        Role.ENEMY_HEAD to listOf("head", "headshot"),
        Role.LOW_HEALTH to listOf("lowhealth", "lowhp"),
        Role.ZONE_TARGET to listOf("safezone", "zonecenter", "zonetarget", "circlecenter"),
        Role.ZONE_WARNING to listOf("zone", "storm", "gas", "outside", "danger", "edge"),
        Role.MINIMAP to listOf("minimap", "radar", "map"),
        Role.HEALTH_BAR to listOf("health", "hp", "healthbar", "hpbar", "life"),
        Role.CROSSHAIR to listOf("crosshair", "reticle"),
        Role.JOYSTICK to listOf("joystick", "joy", "stick", "dpad", "move", "movement"),
        Role.FIRE to listOf("fire", "shoot", "attack", "trigger"),
        Role.JUMP to listOf("jump"),
        Role.CROUCH to listOf("crouch", "squat", "duck"),
        Role.PRONE to listOf("prone", "slide"),
        Role.RELOAD to listOf("reload"),
        Role.SWAP to listOf("swap", "switch"),
        Role.HEAL to listOf("heal", "bandage", "medic", "firstaid"),
        Role.INTERACT to listOf("interact", "grab", "take", "use", "open", "pick"),
        Role.SPRINT to listOf("sprint", "run"),
        Role.SCOPE to listOf("scope", "ads", "aim"),
        Role.GRENADE to listOf("grenade", "nade"),
        Role.MELEE to listOf("melee", "knife"),
        Role.MENU_BUTTON to listOf("continue", "again", "retry", "respawn", "next", "confirm", "ok", "play", "start", "skip", "close", "lobby", "deploy", "accept", "claim", "revive"),
        Role.ENEMY to listOf("enemy", "opponent", "hostile", "foe", "bot", "target", "soldier", "npc", "hunter", "person", "human"),
        Role.LOOT to listOf("loot", "item", "drop", "crate", "chest", "ammo", "weapon", "gun", "rifle", "medkit", "supply", "pickup", "armor", "vest", "helmet")
    )

    fun guess(name: String): Role {
        val n = name.lowercase(Locale.ROOT)
        val direct = Role.values().firstOrNull { it.name.equals(n.replace(' ', '_'), ignoreCase = true) }
        if (direct != null) return direct
        val tokens = n.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        val joined = tokens.joinToString("")
        for ((role, kws) in table) {
            for (k in kws) {
                if (tokens.contains(k) || (k.length >= 5 && joined.contains(k))) return role
            }
        }
        return Role.IGNORE
    }

    fun map(names: List<String>, overrides: Map<String, String>): Array<Role> = Array(names.size) { i ->
        val o = overrides[names[i]]
        val forced = if (o != null) Role.values().firstOrNull { it.name == o } else null
        forced ?: guess(names[i])
    }

    fun parseOverrides(json: String): Map<String, String> {
        val m = HashMap<String, String>()
        try {
            val o = JSONObject(json)
            for (k in o.keys()) m[k] = o.getString(k)
        } catch (_: Exception) {
        }
        return m
    }
}

class Frame(val w: Int, val h: Int, val px: ByteArray, val t: Long)

class Det(val cls: Int, val role: Role, val conf: Float, val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    val cx: Float get() = (x1 + x2) / 2f
    val cy: Float get() = (y1 + y2) / 2f
    val w: Float get() = x2 - x1
    val h: Float get() = y2 - y1
}

class Command {
    var moveX = 0f
    var moveY = 0f
    var lookDx = 0f
    var lookDy = 0f
    var fire = false
    val taps = ArrayList<PointF>()
    var status = ""
    fun tap(p: PointF?) {
        if (p != null) taps.add(PointF(p.x, p.y))
    }
}

class World(
    val t: Long,
    val enemies: List<Det>,
    val heads: List<Det>,
    val allies: List<Det>,
    val loot: List<Det>,
    val ctl: Map<Role, Det>,
    val health: Float?,
    val lowHealth: Boolean,
    val zoneWarn: Det?,
    val minimap: Det?,
    val zoneTarget: Det?,
    val menu: Det?,
    val hudVisible: Boolean,
    val motion: Float,
    val cross: PointF
)

/** Where the on-screen controls are (normalized 0..1 of the landscape screen). */
class Layout {
    val pos = HashMap<Role, PointF>()
    val locked = HashSet<Role>()
    private val learned = HashSet<Role>()
    private val outliers = HashMap<Role, Int>()
    var joyR = 0.11f
    var lookHalfW = 0.12f
    val look = PointF(0.66f, 0.45f)
    val cross = PointF(0.5f, 0.45f)
    private var updates = 0

    init {
        defaults()
    }

    private fun defaults() {
        pos[Role.JOYSTICK] = PointF(0.16f, 0.70f)
        pos[Role.FIRE] = PointF(0.87f, 0.66f)
    }

    fun get(r: Role): PointF? = pos[r]
    fun has(r: Role): Boolean = pos.containsKey(r)

    fun reset() {
        pos.clear(); locked.clear(); learned.clear(); outliers.clear()
        joyR = 0.11f; lookHalfW = 0.12f
        look.set(0.66f, 0.45f); cross.set(0.5f, 0.45f)
        defaults()
    }

    fun place(r: Role, x: Float, y: Float) {
        pos[r] = PointF(x, y)
        locked.add(r)
        learned.add(r)
    }

    fun learn(r: Role, d: Det, aspect: Float) {
        if (r in locked || d.conf < 0.45f) return
        val old = pos[r]
        if (old == null || r !in learned) {
            pos[r] = PointF(d.cx, d.cy)
            learned.add(r)
            updates++
        } else {
            val dist = hypot((d.cx - old.x) * aspect, d.cy - old.y)
            if (dist < 0.08f) {
                old.x += (d.cx - old.x) * 0.15f
                old.y += (d.cy - old.y) * 0.15f
                outliers[r] = 0
                updates++
            } else {
                val n = (outliers[r] ?: 0) + 1
                outliers[r] = n
                if (n > 12) {
                    pos[r] = PointF(d.cx, d.cy)
                    outliers[r] = 0
                }
            }
        }
        if (r == Role.JOYSTICK) joyR = 0.9f * joyR + 0.1f * (d.w * aspect / 2f).coerceIn(0.04f, 0.25f)
    }

    fun toJson(): String {
        val o = JSONObject()
        val p = JSONObject()
        for ((r, v) in pos) {
            if (r in learned || r in locked) p.put(r.name, JSONArray().put(v.x.toDouble()).put(v.y.toDouble()))
        }
        o.put("pos", p)
        o.put("locked", JSONArray(locked.map { it.name }))
        o.put("joyR", joyR.toDouble())
        o.put("lookHalfW", lookHalfW.toDouble())
        o.put("look", JSONArray().put(look.x.toDouble()).put(look.y.toDouble()))
        o.put("cross", JSONArray().put(cross.x.toDouble()).put(cross.y.toDouble()))
        return o.toString()
    }

    fun load(s: String) {
        if (s.isBlank()) return
        try {
            val o = JSONObject(s)
            val p = o.optJSONObject("pos")
            if (p != null) {
                for (k in p.keys()) {
                    val r = Role.values().firstOrNull { it.name == k } ?: continue
                    val a = p.getJSONArray(k)
                    pos[r] = PointF(a.getDouble(0).toFloat(), a.getDouble(1).toFloat())
                    learned.add(r)
                }
            }
            val l = o.optJSONArray("locked")
            if (l != null) for (i in 0 until l.length()) {
                val r = Role.values().firstOrNull { it.name == l.getString(i) }
                if (r != null) locked.add(r)
            }
            joyR = o.optDouble("joyR", joyR.toDouble()).toFloat()
            lookHalfW = o.optDouble("lookHalfW", lookHalfW.toDouble()).toFloat()
            o.optJSONArray("look")?.let { look.set(it.getDouble(0).toFloat(), it.getDouble(1).toFloat()) }
            o.optJSONArray("cross")?.let { cross.set(it.getDouble(0).toFloat(), it.getDouble(1).toFloat()) }
        } catch (e: Exception) {
            AppLog.d("layout load failed: " + e.message)
        }
    }
}
