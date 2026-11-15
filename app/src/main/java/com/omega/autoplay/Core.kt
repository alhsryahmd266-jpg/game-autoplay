package com.omega.autoplay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private val buf = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun d(msg: String) {
        Log.d("AutoPlay", msg)
        buf.addLast(fmt.format(Date()) + "  " + msg)
        while (buf.size > 400) buf.removeFirst()
    }

    @Synchronized
    fun dump(): String = buf.joinToString("\n")

    @Synchronized
    fun clear() {
        buf.clear()
    }
}

object BotState {
    @Volatile var running = false
    @Volatile var stopRequest: (() -> Unit)? = null
}

object Util {
    const val CHANNEL = "autoplay"

    fun ensureChannel(c: Context) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "AutoPlay", NotificationManager.IMPORTANCE_LOW))
        }
    }
}

/** The target must be the user's own offline game: well-known online games are refused. */
object Safety {
    private val blocked = listOf(
        "com.activision.callofduty", "com.garena.game.codm", "com.tencent.tmgp", "com.tencent.ig",
        "com.pubg.", "com.vng.pubgmobile", "com.rekoo.pubgm", "com.dts.freefire", "com.garena.game",
        "com.epicgames.fortnite", "com.ea.gp.apexlegendsmobilefps", "com.mobile.legends",
        "com.roblox.client", "com.supercell.", "com.proximabeta", "com.netease.newspike",
        "com.axlebolt.standoff2", "com.criticalforceentertainment", "com.riotgames"
    )

    fun isBlocked(pkg: String): Boolean = blocked.any { pkg.startsWith(it) }
}

object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(c: Context) {
        if (!::sp.isInitialized) sp = c.applicationContext.getSharedPreferences("autoplay", Context.MODE_PRIVATE)
    }

    private fun str(k: String, d: String): String = sp.getString(k, d) ?: d
    private fun putS(k: String, v: String) { sp.edit().putString(k, v).apply() }
    private fun putF(k: String, v: Float) { sp.edit().putFloat(k, v).apply() }
    private fun putI(k: String, v: Int) { sp.edit().putInt(k, v).apply() }
    private fun putB(k: String, v: Boolean) { sp.edit().putBoolean(k, v).apply() }

    var targetPkg: String
        get() = str("pkg", "")
        set(v) = putS("pkg", v)
    var conf: Float
        get() = sp.getFloat("conf", 0.35f)
        set(v) = putF("conf", v)
    var aimGain: Float
        get() = sp.getFloat("aim", 0.5f)
        set(v) = putF("aim", v)
    var threads: Int
        get() = sp.getInt("threads", 4)
        set(v) = putI("threads", v)
    var style: Int
        get() = sp.getInt("style", 1)
        set(v) = putI("style", v)
    var backend: Int
        get() = sp.getInt("backend", 0)
        set(v) = putI("backend", v)
    var invertY: Boolean
        get() = sp.getBoolean("invertY", false)
        set(v) = putB("invertY", v)
    var debugBoxes: Boolean
        get() = sp.getBoolean("debug", false)
        set(v) = putB("debug", v)
    var volStop: Boolean
        get() = sp.getBoolean("volstop", true)
        set(v) = putB("volstop", v)
    var useGpu: Boolean
        get() = sp.getBoolean("gpu", false)
        set(v) = putB("gpu", v)
    var useNnapi: Boolean
        get() = sp.getBoolean("nnapi", false)
        set(v) = putB("nnapi", v)
    var ovX: Int
        get() = sp.getInt("ovx", 24)
        set(v) = putI("ovx", v)
    var ovY: Int
        get() = sp.getInt("ovy", 180)
        set(v) = putI("ovy", v)
    var classRoles: String
        get() = str("roles", "{}")
        set(v) = putS("roles", v)
    var layoutJson: String
        get() = str("layout", "")
        set(v) = putS("layout", v)
    var lastNames: String
        get() = str("names", "")
        set(v) = putS("names", v)
    var shellPaired: Boolean
        get() = sp.getBoolean("shellPaired", false)
        set(v) = putB("shellPaired", v)
}
