package com.omega.autoplay

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File

class MainActivity : Activity() {
    private val reqModel = 11
    private val reqNames = 12
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var tvOverlay: TextView
    private lateinit var tvA11y: TextView
    private lateinit var tvShell: TextView
    private lateinit var tvGame: TextView
    private lateinit var tvModel: TextView
    private lateinit var tvRun: TextView
    private lateinit var tvLog: TextView
    private var resumed = false
    private val ticker = object : Runnable {
        override fun run() {
            if (!resumed) return
            refresh()
            ui.postDelayed(this, 1200)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Prefs.init(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 5)
        }
        val sv = ScrollView(this)
        sv.setBackgroundColor(Ui.BG)
        sv.layoutDirection = View.LAYOUT_DIRECTION_RTL
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 28), Ui.dp(this, 16), Ui.dp(this, 32))
        sv.addView(root)
        setContentView(sv)

        root.addView(Ui.text(this, "AutoPlay Ω", 30f, Ui.ACC, true))
        val sub = Ui.text(this, "بوت يلعب لعبتك الأوفلاين بدالك: رؤية مباشرة من الشاشة + تحكم باللمس", 13f, Ui.SUB)
        sub.setPadding(0, 0, 0, Ui.dp(this, 14))
        root.addView(sub)

        fun section(title: String): LinearLayout {
            val c = Ui.card(this)
            c.addView(Ui.text(this, title, 16f, Ui.TXT, true))
            root.addView(c)
            return c
        }

        val c1 = section("١) الصلاحيات")
        tvOverlay = Ui.text(this, "", 13f, Ui.SUB); c1.addView(tvOverlay)
        c1.addView(Ui.button(this, "السماح بالظهور فوق التطبيقات") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        tvA11y = Ui.text(this, "", 13f, Ui.SUB); c1.addView(tvA11y)
        c1.addView(Ui.button(this, "تفعيل خدمة إمكانية الوصول") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })

        val c2 = section("٢) الشل المدمج (اختياري: أدق وأسرع)")
        c2.addView(Ui.text(this, "بيتصل بـ Wireless debugging بتاع موبايلك نفسه من غير أي برنامج خارجي. بتفعّله مرة واحدة بكود من 6 أرقام.", 12f, Ui.SUB))
        tvShell = Ui.text(this, "", 13f, Ui.SUB); c2.addView(tvShell)
        c2.addView(Ui.button(this, "تفعيل الشل (كود 6 أرقام)") { startPairing() })
        c2.addView(Ui.button(this, "اختبار الشل") { testShell() })

        val c3 = section("٣) لعبتك")
        tvGame = Ui.text(this, "", 13f, Ui.SUB); c3.addView(tvGame)
        c3.addView(Ui.button(this, "اختار لعبتك") { pickApp() })
        c3.addView(Ui.button(this, "افتح اللعبة") { launchGame() })

        val c4 = section("٤) النموذج (عين البوت)")
        tvModel = Ui.text(this, "", 13f, Ui.SUB); c4.addView(tvModel)
        c4.addView(Ui.button(this, "استيراد نموذج (.tflite / .onnx) من التنزيلات") { openPicker(reqModel) })
        c4.addView(Ui.button(this, "استيراد أسماء الكلاسات (data.yaml / zip / txt)") { openPicker(reqNames) })
        c4.addView(Ui.button(this, "ربط الكلاسات بالأدوار") { mapRoles() })
        c4.addView(Ui.button(this, "إزالة النموذج", Ui.BAD) {
            ModelStore.remove(this)
            Prefs.classRoles = "{}"
            toast("اتشال")
            refresh()
        })

        val c5 = section("٥) الأزرار")
        c5.addView(Ui.text(this, "لو النموذج مش بيتعرف على أزرار لعبتك: شغّل البوت مرة داخل اللعبة (أو اضغط الزر العايم ضغطة طويلة) عشان تتاخد لقطة، وبعدين حدد مكان كل زر عليها.", 12f, Ui.SUB))
        c5.addView(Ui.button(this, "ضبط مواضع الأزرار على لقطة من لعبتك") { startActivity(Intent(this, LayoutActivity::class.java)) })
        c5.addView(Ui.button(this, "مسح الأزرار المضبوطة والمتعلَّمة", Ui.BAD) {
            Prefs.layoutJson = ""
            toast("اتمسحت")
        })

        val c6 = section("٦) الإعدادات")
        c6.addView(Ui.button(this, "أسلوب اللعب: " + styleName(Prefs.style)) { }.also { bt ->
            bt.setOnClickListener {
                Prefs.style = (Prefs.style + 1) % 3
                bt.text = "أسلوب اللعب: " + styleName(Prefs.style)
            }
        })
        c6.addView(Ui.button(this, "طريقة اللمس: " + backendName(Prefs.backend)) { }.also { bt ->
            bt.setOnClickListener {
                Prefs.backend = (Prefs.backend + 1) % 3
                bt.text = "طريقة اللمس: " + backendName(Prefs.backend)
            }
        })
        seekRow(c6, "حساسية الكشف", 10, 80, (Prefs.conf * 100).toInt(), { "$it%" }) { Prefs.conf = it / 100f }
        seekRow(c6, "قوة التصويب", 10, 100, (Prefs.aimGain * 100).toInt(), { "$it%" }) { Prefs.aimGain = it / 100f }
        seekRow(c6, "عدد أنوية المعالج للنموذج", 1, 8, Prefs.threads, { "$it" }) { Prefs.threads = it }
        seekRow(c6, "أقصى عدد صور في الثانية (أقل = حرارة أقل واللعبة أسلس)", 5, 25, Prefs.maxFps, { "$it" }) { Prefs.maxFps = it }
        switchRow(c6, "عكس اتجاه التصويب الرأسي", Prefs.invertY) { Prefs.invertY = it }
        switchRow(c6, "زر خفض الصوت = إيقاف طوارئ", Prefs.volStop) { Prefs.volStop = it }
        switchRow(c6, "رسم مربعات الكشف فوق اللعبة (للتجربة فقط)", Prefs.debugBoxes) { Prefs.debugBoxes = it }
        switchRow(c6, "تسريع GPU لنموذج tflite (تجريبي)", Prefs.useGpu) { Prefs.useGpu = it }
        switchRow(c6, "تسريع NNAPI لنموذج onnx (تجريبي)", Prefs.useNnapi) { Prefs.useNnapi = it }
        c6.addView(Ui.text(this, "تغيير الأنوية أو GPU/NNAPI أو رسم المربعات بيسري عند تشغيل الخدمة من جديد.", 11f, Ui.SUB))

        val c7 = section("٧) التشغيل")
        tvRun = Ui.text(this, "", 13f, Ui.SUB); c7.addView(tvRun)
        c7.addView(Ui.button(this, "تشغيل العوّامة ▶", Ui.ACC) { runBot() })
        c7.addView(Ui.button(this, "إيقاف الخدمة", Ui.BAD) {
            if (BotService.instance != null) {
                startService(Intent(this, BotService::class.java).setAction(BotService.ACT_STOP))
            } else {
                toast("الخدمة مش شغّالة")
            }
        })
        c7.addView(Ui.text(this, "بعد التشغيل افتح لعبتك: هتلاقي زر ▶ عايم. اضغطه يلعب، واضغطه تاني (أو زر خفض الصوت) يوقف.", 12f, Ui.SUB))

        val c8 = section("٨) السجل")
        tvLog = Ui.text(this, "", 10f, Ui.SUB)
        tvLog.typeface = android.graphics.Typeface.MONOSPACE
        c8.addView(tvLog)
        c8.addView(Ui.button(this, "نسخ السجل") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("autoplay-log", AppLog.dump()))
            toast("اتنسخ")
        })
        c8.addView(Ui.button(this, "مسح السجل") {
            AppLog.clear()
            refresh()
        })
        refresh()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        ui.post(ticker)
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    }

    private fun styleName(i: Int) = when (i) { 0 -> "حذر"; 2 -> "هجومي"; else -> "متوازن" }
    private fun backendName(i: Int) = when (i) { 1 -> "شل فقط"; 2 -> "إمكانية الوصول فقط"; else -> "تلقائي" }

    private fun seekRow(parent: LinearLayout, label: String, lo: Int, hi: Int, cur: Int, fmt: (Int) -> String, onChange: (Int) -> Unit) {
        val tv = Ui.text(this, label + ": " + fmt(cur), 13f, Ui.SUB)
        tv.setPadding(0, Ui.dp(this, 10), 0, 0)
        val sb = SeekBar(this)
        sb.max = hi - lo
        sb.progress = (cur - lo).coerceIn(0, hi - lo)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val v = p + lo
                tv.text = label + ": " + fmt(v)
                if (fromUser) onChange(v)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        parent.addView(tv)
        parent.addView(sb)
    }

    private fun switchRow(parent: LinearLayout, label: String, cur: Boolean, onChange: (Boolean) -> Unit) {
        val s = Switch(this)
        s.text = label
        s.isChecked = cur
        s.setTextColor(Ui.TXT)
        s.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4))
        s.setOnCheckedChangeListener { _, v -> onChange(v) }
        parent.addView(s)
    }

    private fun a11yOn(): Boolean {
        if (BotAccessibilityService.instance != null) return true
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, BotAccessibilityService::class.java).flattenToString()
        return s.split(':').any { it.equals(me, true) }
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    private fun modelText(): String {
        if (!ModelStore.hasModel(this)) return "⚪ مفيش نموذج: وضع تجريبي (البوت يمشي ويلفّ بس)"
        val names = ModelStore.savedNames(this)
        var f = File(filesDir, "model.tflite")
        if (!f.exists()) f = File(filesDir, "model.onnx")
        val size = if (f.exists()) (f.length() / 1024 / 1024).toString() + "MB" else "جوه التطبيق"
        if (names.isEmpty()) return "✅ نموذج محمّل ($size). أسماء الكلاسات هتتقري عند أول تشغيل"
        val roles = Names.effectiveRoles(names, Roles.parseOverrides(Prefs.classRoles))
        val used = roles.count { it != Role.IGNORE }
        val hud = roles.any { it in Role.HUD }
        return "✅ نموذج محمّل ($size) — " + names.size + " كلاس، منهم " + used + " مفهومين للبوت\n" +
            (if (hud) "بيتعرف على الأزرار من النموذج" else "النموذج مفيهوش أزرار: اضبطها يدويًا من (ضبط الأزرار)")
    }

    private fun refresh() {
        tvOverlay.text = (if (Settings.canDrawOverlays(this)) "✅ " else "❌ ") + "الظهور فوق التطبيقات"
        tvA11y.text = (if (a11yOn()) "✅ " else "❌ ") + "خدمة إمكانية الوصول"
        tvShell.text = if (Prefs.shellPaired) "✅ الشل متفعّل (لو فصلت الـ Wi-Fi شغّل Wireless debugging تاني)" else "⚪ الشل مش متفعّل (اختياري)"
        val pkg = Prefs.targetPkg
        tvGame.text = if (pkg.isEmpty()) "❌ لسه ما اخترتش لعبة" else "✅ " + appLabel(pkg) + "\n" + pkg
        tvModel.text = modelText()
        tvRun.text = "الحالة: " + BotService.status + (if (BotService.instance != null) "" else "  (الخدمة مقفولة)")
        tvLog.text = AppLog.dump().lines().takeLast(40).joinToString("\n")
    }

    private fun startPairing() {
        val i = Intent(this, PairingService::class.java).setAction(PairingService.ACTION_START)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        AlertDialog.Builder(this)
            .setTitle("تفعيل الشل بالستة أرقام")
            .setMessage(
                "١) هتفتح إعدادات المطوّر: شغّل Wireless debugging (لازم تكون على Wi-Fi).\n" +
                    "٢) اضغط Pair device with pairing code، هيظهر كود من 6 أرقام.\n" +
                    "٣) من غير ما تقفل الشاشة دي، اسحب شريط الإشعارات واكتب الكود في إشعار AutoPlay."
            )
            .setPositiveButton("افتح الإعدادات") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                } catch (e: Exception) {
                    toast("افتح إعدادات المطوّر يدويًا")
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun testShell() {
        toast("بجرّب الاتصال...")
        Thread {
            val s = AdbShellInput(this)
            val e = s.prepare()
            try { s.close() } catch (_: Throwable) { }
            runOnUiThread {
                if (e == null) Prefs.shellPaired = true
                toast(e ?: "الشل شغّال ✔")
                refresh()
            }
        }.start()
    }

    private fun pickApp() {
        val pm = packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(i, 0)
            .map { Pair(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        val items = apps.map { it.second + "\n" + it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("اختار لعبتك")
            .setItems(items) { _, w ->
                val pkg = apps[w].first
                if (Safety.isBlocked(pkg)) {
                    toast("دي لعبة أونلاين معروفة. التطبيق للعبتك الأوفلاين بس")
                } else {
                    Prefs.targetPkg = pkg
                    refresh()
                }
            }
            .show()
    }

    private fun launchGame() {
        val pkg = Prefs.targetPkg
        val li = if (pkg.isEmpty()) null else packageManager.getLaunchIntentForPackage(pkg)
        if (li == null) toast("اختار لعبتك الأول") else startActivity(li)
    }

    private fun runBot() {
        if (!Settings.canDrawOverlays(this)) { toast("اسمح بالظهور فوق التطبيقات الأول"); return }
        if (!a11yOn() && !Prefs.shellPaired) { toast("فعّل خدمة إمكانية الوصول (أو الشل)"); return }
        val pkg = Prefs.targetPkg
        if (pkg.isEmpty()) { toast("اختار لعبتك الأول"); return }
        if (Safety.isBlocked(pkg)) { toast("دي لعبة أونلاين معروفة. التطبيق للعبتك الأوفلاين بس"); return }
        if (BotService.instance != null) { toast("الخدمة شغّالة: افتح لعبتك واضغط ▶"); return }
        startActivity(Intent(this, CaptureActivity::class.java))
        toast("وافق على التقاط الشاشة، وبعدها افتح لعبتك وهتلاقي زر ▶")
    }

    private fun openPicker(req: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        @Suppress("DEPRECATION")
        startActivityForResult(i, req)
    }

    @Deprecated("framework callback")
    override fun onActivityResult(rc: Int, res: Int, data: Intent?) {
        super.onActivityResult(rc, res, data)
        if (res != RESULT_OK) return
        val uri = data?.data ?: return
        if (rc == reqModel) importModel(uri)
        if (rc == reqNames) importNames(uri)
    }

    private fun fileName(uri: Uri): String {
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && idx >= 0) return c.getString(idx)
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: "file"
    }

    private fun importModel(uri: Uri) {
        toast("بستورد النموذج...")
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null || bytes.size < 10_000) {
                    runOnUiThread { toast("الملف مش نموذج صالح") }
                    return@Thread
                }
                ModelStore.save(this, bytes)
                val (y, info) = ModelStore.build(this)
                y?.close()
                runOnUiThread {
                    toast(if (info.ok) "تمام ✔ " + info.msg else info.msg)
                    refresh()
                }
            } catch (t: Throwable) {
                runOnUiThread { toast("فشل الاستيراد: " + t.message) }
            }
        }.start()
    }

    private fun importNames(uri: Uri) {
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                val names = Names.parseImport(fileName(uri), bytes)
                runOnUiThread {
                    if (names.isEmpty()) {
                        toast("مالقيتش أسماء كلاسات في الملف ده")
                    } else {
                        ModelStore.saveNames(this, names)
                        toast("تم: " + names.size + " كلاس")
                        refresh()
                    }
                }
            } catch (t: Throwable) {
                runOnUiThread { toast("فشل: " + t.message) }
            }
        }.start()
    }

    private fun mapRoles() {
        val names = ModelStore.savedNames(this)
        if (names.isEmpty()) {
            toast("استورد نموذج أو أسماء الكلاسات الأول")
            return
        }
        val ov = HashMap(Roles.parseOverrides(Prefs.classRoles))
        val all = Role.values()
        val cur = Array(names.size) { i ->
            val o = ov[names[i]]
            (if (o != null) all.firstOrNull { it.name == o } else null) ?: Names.defaultRole(names, i)
        }
        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8))
        sv.addView(col)
        sv.layoutDirection = View.LAYOUT_DIRECTION_RTL
        for (i in names.indices) {
            val bt = Ui.button(this, names[i] + "  ←  " + cur[i].ar, Ui.ACC2) { }
            bt.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle(names[i])
                    .setItems(all.map { it.ar }.toTypedArray()) { _, w ->
                        cur[i] = all[w]
                        bt.text = names[i] + "  ←  " + cur[i].ar
                    }
                    .show()
            }
            col.addView(bt)
        }
        AlertDialog.Builder(this)
            .setTitle("ربط الكلاسات بالأدوار")
            .setView(sv)
            .setPositiveButton("حفظ") { _, _ ->
                for (i in names.indices) {
                    if (cur[i] != Names.defaultRole(names, i)) ov[names[i]] = cur[i].name else ov.remove(names[i])
                }
                Prefs.classRoles = JSONObject(ov as Map<*, *>).toString()
                refresh()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }
}
