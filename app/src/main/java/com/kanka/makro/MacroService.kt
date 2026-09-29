package com.kanka.makro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.ContentValues
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.BatteryManager
import android.os.PowerManager
import android.content.IntentFilter
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Random
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import org.json.JSONObject

class MacroService : AccessibilityService() {

    companion object {
        /** Uygulama ekranindan servise ulasmak icin (ayni surec) */
        @Volatile
        var instance: MacroService? = null
    }

    private enum class Mod { BUTON, HP, MP, HEDEF_BAR, OPEN, COLLECT }

    // Oyun icinde tus duzenleme ekrani
    private var editor: FrameLayout? = null

    private class Hedef(val x: Float, val y: Float, val r: Float, val fast: Boolean = false)

    private enum class Loot { BOS, COLLECT_BEKLE, SONRAKI_KUTU }

    private lateinit var wm: WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private val worker = HandlerThread("macro").apply { start() }
    private val h = Handler(worker.looper)

    // Kayit uyumlu yedek: MediaProjection kesilince erisilebilirlik ekran goruntusu
    @Volatile private var ssMod = false
    private val ssExec = Executors.newSingleThreadExecutor()

    // Ag islemleri (mesaj / surum kontrolu) icin ayri thread
    private val agThread = HandlerThread("ag").apply { start() }
    private val ah = Handler(agThread.looper)

    // Mesaj karti (oyunun ustunde)
    private var kart: View? = null
    private val mesajKuyrugu = ArrayDeque<Pair<String, String>>()
    private val rnd = Random()

    private var panel: View? = null
    private var overlay: View? = null
    private var playBtn: TextView? = null
    private var statusTv: TextView? = null

    @Volatile
    private var running = false

    // Adim dongusu ve dokunus durumu (kutu gozcusu araya girebilsin diye)
    private val stepR = Runnable { step() }
    private var tapping = false
    private var pendingStart = false
    private var lisansKontrolde = false
    private var sonLisansKontrol = 0L
    @Volatile private var cfg = Config()

    // Motor durumu (sadece worker thread'inde degisir)
    private var endAt = 0L
    private var nextTarget = 0L
    private var lastHpPot = 0L
    private var lastMpPot = 0L
    private var hpLowSince = 0L
    private val skillReady = HashMap<Int, Long>()
    private val skillLast = HashMap<Int, Long>()
    private var tgtStrip = IntArray(3)

    // Otomatik ekran tanima
    private var otoMod = false
    @Volatile private var olcek: Preset.Olcek? = null
    private var sonTarama = 0L

    // Koruma
    private var dcT: Sablon? = null
    private var dcSay = 0
    private var sonDcKontrol = 0L
    private var hpSol = IntArray(2)
    private var hpBosSince = 0L

    // Takili hedef
    private var oncekiCanli = false
    private var sonYuzde = 1f
    private var ilerlemeAt = 0L
    private var iptalBekliyor = false
    // PK modu
    @Volatile private var pkAktif = false
    private var pkSira = 0
    // Minor ac-kapa durumu (1. basis acar ve mana yer, 2. basis kapatir)
    @Volatile private var minorAcik = false
    private var minorSon = 0L
    private var modBtn: TextView? = null

    // Mesafe siniri sonrasi bekleme
    @Volatile private var menzilBekleUntil = 0L
    private var menzilXAt = 0L
    private var menzilArdArda = 0
    private var iptalAt = 0L             // son X (iptal) zamani: iptal edilen mob "kesildi" sayilmasin

    // Alan siniri
    private var alanBitti = false        // bu hedef icin alan kontrolu tamamlandi
    private var alanArdArda = 0          // ust uste alan disi hedef sayisi
    @Volatile private var alanBekleUntil = 0L
    private var alanView: View? = null
    // Halka tespiti icin yeniden kullanilan tamponlar (her cagrida MB'larca dizi ayirma)
    private var alanMask = BooleanArray(0)
    private var alanVis = BooleanArray(0)
    private var alanStack = IntArray(0)

    // Mesafe siniri
    private var hedefBaslangic = 0L
    private var ilkVurus = false

    // Bar dolulugu ve mob kilidi (otomatik tanimadan gelir)
    private var hpBar = IntArray(3)
    private var mpBar = IntArray(3)
    private var isimRect = IntArray(4)
    private var kilitKotu = 0
    private var kilitUyarildi = false
    @Volatile private var kilitBekleUntil = 0L   // kilitli mob yok: bu ana kadar hicbir sey yapma

    // Guvenlik: oyun onde mi, ekran acik mi, goruntu taze mi
    @Volatile private var sonPaket = ""      // en son one gelen uygulama
    @Volatile private var oyunPaketi = ""    // ▶'a basildiginda ondeki uygulama
    @Volatile private var bekleNeden = ""    // panelde gosterilecek bekleme sebebi


    // Istatistik
    @Volatile private var kesilen = 0
    @Volatile private var toplanan = 0
    @Volatile private var basilanPot = 0
    private var taramaHata = 0
    private var nextLootScan = 0L
    private var lootPhase = Loot.BOS
    private var phaseUntil = 0L
    private var lootPauseUntil = 0L
    private var collectStreak = 0
    private var lastOpenX = -9999f
    private var lastOpenY = -9999f
    private var lastOpenAt = 0L
    private var collectedSinceOpen = true
    // Envanter doluyken Close ile kapatilan kutu: ayni kutuya bir sure tekrar basma
    private var doluKutuX = -9999f
    private var doluKutuY = -9999f
    private var doluKutuAt = 0L
    private var warnedCollect = false

    @Volatile
    private var screenW = 1080

    @Volatile
    private var screenH = 2400

    // ================= Yasam dongusu =================

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        instance = this
        ui.postDelayed(sistemIzle, 30_000)
        Lisans.yukle(this)
        // Beklenmedik cokmeleri kaydet (panele raporlanir)
        val onceki = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            hataKaydet("ÇÖKME", e)
            onceki?.uncaughtException(t, e)
        }
        updateScreenSize()
        showPanel()
        ah.postDelayed(mesajDongu, 3000)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val p = event.packageName?.toString() ?: return
        if (gormezdenGel(p)) return
        sonPaket = p
        ui.post { oyunGorunurluk() }
    }

    /**
     * Geri tusu: makronun acik bir penceresi varsa onu kapatir (panele doner) ve tusu yutar.
     * Acik pencere yoksa tus oyuna gider.
     */
    override fun onKeyEvent(event: android.view.KeyEvent?): Boolean {
        if (event == null || event.keyCode != android.view.KeyEvent.KEYCODE_BACK) return false
        if (overlay == null && editor == null && kart == null) return false
        if (event.action == android.view.KeyEvent.ACTION_UP) {
            ui.post {
                when {
                    overlay != null -> removeOverlay()
                    editor != null -> closeEditor()
                    kart != null -> kartKapat()
                }
            }
        }
        return true
    }

    /** MykoMobile'in paket adi (yuklu uygulamalardan bulunur, bir kez) */
    private var oyunPaketAdi: String? = null
    private var oyunPaketArandi = false

    @Suppress("DEPRECATION")
    private fun oyunPaketiBul(): String? {
        if (oyunPaketArandi) return oyunPaketAdi
        oyunPaketArandi = true
        oyunPaketAdi = try {
            val pm = packageManager
            val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(q, 0).firstOrNull {
                it.activityInfo.packageName.contains("myko", true) ||
                    it.loadLabel(pm).toString().contains("myko", true)
            }?.activityInfo?.packageName
        } catch (e: Exception) {
            null
        }
        return oyunPaketAdi
    }

    private fun oyundaMi(): Boolean {
        val p = sonPaket
        if (p.isEmpty()) return true   // henuz bilgi yok: gizleme
        val oyun = oyunPaketiBul()
        return (oyun != null && p == oyun) || p.contains("myko", true) ||
            (oyunPaketi.isNotEmpty() && p == oyunPaketi)
    }

    /**
     * Makro sadece oyunun icinde: oyundan cikinca panel gizlenir ve acik kalan
     * butun pencereler (duzenleme, kayit, menu) kapanir; baska yerde dokunus engellenmez.
     */
    private fun oyunGorunurluk() {
        if (oyundaMi()) {
            panel?.visibility = View.VISIBLE
            // Oyun disindayken gelen mesajlari simdi goster
            if (kart == null && mesajKuyrugu.isNotEmpty()) siradakiMesaj()
        } else {
            removeOverlay()
            closeEditor()
            kartKapat()
            panel?.visibility = View.GONE
        }
    }

    /** Bu paketler one gelse de "oyundan cikildi" sayilmaz */
    private fun gormezdenGel(p: String): Boolean =
        p == packageName || p == "com.android.systemui" || p == "android" ||
            p.contains("inputmethod") || p.contains("keyboard") || p.contains(".ime") ||
            p.contains("permissioncontroller") || p.contains("securitycenter")

    override fun onInterrupt() {
        stopMacro()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenSize()
    }

    override fun onDestroy() {
        instance = null
        ui.removeCallbacksAndMessages(null)   // ana thread'de bekleyen post/postDelayed cagrilari (statusTick, mesaj kartlari vb.)
        if (VideoKaydedici.kayitta) VideoKaydedici.durdur(this)
        ssMod = false
        try { ssExec.shutdownNow() } catch (_: Exception) {}
        alanView?.let { safeRemove(it) }
        alanView = null
        kartKapat()
        closeEditor()
        stopMacro()
        removeOverlay()
        panel?.let { safeRemove(it) }
        panel = null
        worker.quitSafely()
        ah.removeCallbacksAndMessages(null)
        agThread.quitSafely()
        pazarCalisiyor = false
        pzThread.quitSafely()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun updateScreenSize() {
        val dm = DisplayMetrics()
        (getSystemService(DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(dm)
        screenW = dm.widthPixels
        screenH = dm.heightPixels
    }

    // ================= Yardimcilar =================

    // ================= Sistem kaydi (pil / sicaklik / termal durum / bellek) =================
    // Bot calisirken 30 sn'de bir bir satir yazar. Telefon aniden kapanirsa son satir
    // kapanmadan hemen onceki durumu gosterir. commit() ile aninda kalici yazilir.

    private fun sistemIzle_ornekle() {
        val bi = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val seviye = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val olcek100 = bi?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pil = if (seviye >= 0 && olcek100 > 0) seviye * 100 / olcek100 else -1
        val ham = bi?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val sicak = if (ham == Int.MIN_VALUE) Float.NaN else ham / 10f
        val durum = bi?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val sarjda = durum == BatteryManager.BATTERY_STATUS_CHARGING || durum == BatteryManager.BATTERY_STATUS_FULL
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val termal = if (Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus else -1
        val rt = Runtime.getRuntime()
        val heapMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val bot = when {
            pazarCalisiyor -> "pazar"
            running -> if (pkAktif) "pk" else "farm"
            else -> "-"
        }
        val saat = java.text.SimpleDateFormat("dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val satir = "$saat $bot pil%$pil${if (sarjda) "⚡" else ""} " +
            (if (sicak.isNaN()) "?°C" else "%.1f°C".format(sicak)) + " termal$termal heap${heapMb}MB"
        val pr = getSharedPreferences("sistem", MODE_PRIVATE)
        val eski = pr.getString("log", "") ?: ""
        val yeni = (eski.split("\n").filter { it.isNotBlank() } + satir).takeLast(40).joinToString("\n")
        pr.edit().putString("log", yeni).commit()

        // Koruma: sistem agir kisitlamaya girdiyse (SEVERE ve ustu) ya da pil cok sicaksa dur
        if ((termal >= 3 || (!sicak.isNaN() && sicak >= 46f)) && (running || pazarCalisiyor)) {
            val neden = if (termal >= 3) "Telefon aşırı ısındı (termal seviye $termal)"
            else "Pil sıcaklığı %.1f°C".format(sicak)
            bildirim(4200, "🌡 Bot durduruldu", "$neden. Soğuyunca tekrar başlat.")
            if (pazarCalisiyor) pazarDurdur()
            if (running) stopMacro("$neden. Makro durdu")
        }
    }

    private val sistemIzle: Runnable = object : Runnable {
        override fun run() {
            try {
                if (running || pazarCalisiyor) sistemIzle_ornekle()
            } catch (e: Exception) {
                hataKaydet("sistem", e)
            }
            ui.postDelayed(this, 30_000)
        }
    }

    // ================= Alan siniri =================

    /** Alan dikdortgeni: yatayda ekranin ortasi, dikeyde alanCy, boyutlar ekran yuzdesi */
    private fun alanIcinde(x: Float, y: Float): Boolean {
        val cx = screenW * 0.5f
        val cy = screenH * cfg.alanCy / 100f
        val hw = screenW * cfg.alanW / 200f
        val hh = screenH * cfg.alanH / 200f
        return kotlin.math.abs(x - cx) <= hw && kotlin.math.abs(y - cy) <= hh
    }

    /**
     * Secili mobun ayagindaki sari secim halkasini arar (renk + bos elips sekli).
     * Bulursa halkanin ekran merkezi (x, y), bulamazsa null. HUD bolgeleri disarida birakilir.
     */
    private fun secimHalkasi(): FloatArray? {
        val s = olcek?.s ?: (screenW / 2712f)
        val yUst = (screenH * 0.10f).toInt()
        val yAlt = (screenH * 0.88f).toInt()
        val kr = ScreenSampler.bolge(0, yUst, screenW - 1, yAlt) ?: return null
        val w = kr.first
        val h = kr.second
        val px = kr.third
        if (w < 8 || h < 8) return null
        val sc = ScreenSampler.SCALE
        val n = w * h
        if (alanMask.size != n) {
            alanMask = BooleanArray(n)
            alanVis = BooleanArray(n)
            alanStack = IntArray(n)
        } else {
            alanMask.fill(false)
            alanVis.fill(false)
        }
        val mask = alanMask
        for (y in 0 until h) {
            val sy = (y / sc + yUst) / screenH
            for (x in 0 until w) {
                val sx = (x / sc) / screenW
                if (sx < 0.24f && sy < 0.36f) continue    // sol ust: can/mana + gorev listesi
                if (sx < 0.18f && sy > 0.60f) continue    // sol alt: sohbet / bilgi yazilari
                if (sx > 0.64f && sy > 0.42f) continue    // sag alt: skill dugmeleri
                if (sx > 0.85f && sy < 0.45f) continue    // sag ust: kanal + kamera dugmeleri
                val c = px[y * w + x]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                if (r > 185 && g > 165 && r - b > 30) mask[y * w + x] = true
            }
        }
        val vis = alanVis
        val stack = alanStack
        var best: FloatArray? = null
        var bestD = Float.MAX_VALUE
        val cxS = screenW * 0.5f
        val cyS = screenH * (cfg.alanCy / 100f)
        val minW = 40f * s * sc
        val maxW = 450f * s * sc
        for (start in 0 until w * h) {
            if (!mask[start] || vis[start]) continue
            var sp = 0
            stack[sp++] = start
            vis[start] = true
            var minX = w
            var maxX = 0
            var minY = h
            var maxY = 0
            var cnt = 0
            while (sp > 0) {
                val i = stack[--sp]
                val x = i % w
                val y = i / w
                cnt++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                // 2 piksellik bosluklari da birlestir (ince halka cizgisi)
                for (dy in -2..2) for (dx in -2..2) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val ni = ny * w + nx
                    if (mask[ni] && !vis[ni]) {
                        vis[ni] = true
                        stack[sp++] = ni
                    }
                }
            }
            val bw = (maxX - minX + 1).toFloat()
            val bh = (maxY - minY + 1).toFloat()
            if (bw < minW || bw > maxW || bh < minW * 0.3f) continue
            val asp = bw / bh
            if (asp < 0.6f || asp > 4.5f) continue
            if (cnt < 25 || cnt / (bw * bh) > 0.55f) continue   // dolu yuzey degil, bos halka olmali
            val sxC = ((minX + maxX) / 2f) / sc
            val syC = ((minY + maxY) / 2f) / sc + yUst
            val d = (sxC - cxS) * (sxC - cxS) + (syC - cyS) * (syC - cyS)
            if (d < bestD) {
                bestD = d
                best = floatArrayOf(sxC, syC)
            }
        }
        return best
    }

    /** Alan dikdortgenini (ve varsa bulunan halka noktasini) 4 sn ekranda gosterir; dokunuslari engellemez */
    private fun alanCerceveGoster(nokta: FloatArray? = null) {
        ui.post {
            alanView?.let { safeRemove(it) }
            alanView = null
            val c = Config.load(this)
            val cx = screenW * 0.5f
            val cy = screenH * c.alanCy / 100f
            val hw = screenW * c.alanW / 200f
            val hh = screenH * c.alanH / 200f
            val cerceve = android.graphics.Paint().apply {
                color = 0xFFFF7A2E.toInt()
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = dp(3).toFloat()
            }
            val dolu = android.graphics.Paint().apply {
                color = 0xFFFFEB3B.toInt()
                style = android.graphics.Paint.Style.FILL
            }
            val v = object : View(this@MacroService) {
                override fun onDraw(canvas: android.graphics.Canvas) {
                    canvas.drawRect(cx - hw, cy - hh, cx + hw, cy + hh, cerceve)
                    if (nokta != null) canvas.drawCircle(nokta[0], nokta[1], dp(10).toFloat(), dolu)
                }
            }
            val p = lp(screenW, screenH).apply {
                x = 0
                y = 0
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            try {
                wm.addView(v, p)
                alanView = v
                ui.postDelayed({
                    if (alanView === v) {
                        safeRemove(v)
                        alanView = null
                    }
                }, 4000)
            } catch (e: Exception) {
                alanView = null
            }
        }
    }

    /** Menuden: bir mobu sec, sonra bas. Halkayi bulup alanin icinde/disinda oldugunu soyler */
    private fun alanTesti() {
        removeOverlay()
        if (running || pazarCalisiyor) { toast("Önce botu durdur"); return }
        if (!ScreenSampler.running) { toast("Ekran okuma kapalı: önce ▶ ile bir kere başlatıp durdur"); return }
        if (!oyundaMi()) { toast("Önce oyunu aç"); return }
        cfg = Config.load(this)
        h.post {
            val halka = try { secimHalkasi() } catch (e: Exception) { null }
            if (halka == null) {
                toast("📐 Seçim halkası bulunamadı. Bir mobu seçili tutup tekrar dene")
            } else {
                val ic = alanIcinde(halka[0], halka[1])
                toast(if (ic) "📐 Halka bulundu: alanın İÇİNDE ✓ (saldırırdı)" else "📐 Halka bulundu: alanın DIŞINDA ✗ (bırakırdı)")
            }
            alanCerceveGoster(halka)
        }
    }

    /**
     * Panelden video kaydini ac/kapat.
     * Android 14+ / HyperOS: ikinci ekran izni BIRINCIYI oldurur. CaptureService
     * zaten aciksa AYNI karelerden kayit baslar; yoksa tek izin istenir.
     */
    private fun videoKayitToggle() {
        if (VideoKaydedici.kayitta) {
            VideoKaydedici.durdur(this)
            toast("⏺ Video kaydedildi: Galeri → Videolar → Projeindirpedal")
            return
        }
        if (!oyundaMi()) { toast("Önce oyunu aç"); return }
        val cap = CaptureService.instance
        if (cap != null && ScreenSampler.running) {
            val ok = cap.videoBaslat()
            videoKayitSonucu(ok)
            return
        }
        removeOverlay()
        closeEditor()
        kartKapat()
        panel?.visibility = View.GONE
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra("video_kayit_iste", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            panel?.visibility = View.VISIBLE
            toast("Video izni istenemedi, tekrar dene")
        }
    }

    /** MainActivity / CaptureService sonucunu buraya bildirir */
    fun videoKayitSonucu(basladi: Boolean) {
        ui.post {
            if (oyundaMi()) panel?.visibility = View.VISIBLE
            if (basladi) toast("🎬 Video kaydı başladı — makro aynı anda çalışır")
            else toast("Video kaydı başlatılamadı ya da izin verilmedi")
        }
    }

    // ================= Kamera testi =================
    // Sagdaki bos bir noktadan yatay surukleyip geri getirir. Amac: bu hareketin senin
    // telefonunda oyun kamerasini gercekten cevirip cevirmedigini (ve hangi yone) gormek.

    private fun kameraSurukle(x1: Float, y: Float, x2: Float, done: () -> Unit) {
        val p = Path().apply { moveTo(x1, y); lineTo(x2, y + 2f) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 400)).build()
        val ok = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { done() }
            override fun onCancelled(gestureDescription: GestureDescription?) { done() }
        }, h)
        if (!ok) done()
    }

    private fun kameraTesti() {
        removeOverlay()
        if (running || pazarCalisiyor) { toast("Önce botu durdur"); return }
        if (!oyundaMi()) { toast("Önce oyunu aç"); return }
        val y = screenH * 0.30f
        val x0 = screenW * 0.62f
        val x1 = x0 + screenW * 0.16f
        toast("🎥 Kamera testi: önce SAĞA, sonra SOLA sürükleniyor. Kameranın ne yaptığına bak")
        h.postDelayed({
            kameraSurukle(x0, y, x1) {
                h.postDelayed({ kameraSurukle(x1, y, x0) {} }, 800)
            }
        }, 600)
    }

    private fun sistemKaydiGoster() {
        val satirlar = (getSharedPreferences("sistem", MODE_PRIVATE).getString("log", "") ?: "")
            .split("\n").filter { it.isNotBlank() }.takeLast(8)
        val liste = ArrayList<Pair<String, () -> Unit>>()
        if (satirlar.isEmpty()) liste.add("Henüz kayıt yok (bot çalışırken 30 sn'de bir yazılır)" to {})
        satirlar.forEach { liste.add(it to {}) }
        liste.add("🗑 Kaydı temizle" to {
            getSharedPreferences("sistem", MODE_PRIVATE).edit().remove("log").commit()
            toast("Sistem kaydı temizlendi")
        })
        showMenu("🌡 Son sistem kayıtları", liste)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rand(a: Int, b: Int): Long {
        val lo = minOf(a, b)
        val hi = maxOf(a, b)
        return (lo + rnd.nextInt((hi - lo + 1).coerceAtLeast(1))).toLong()
    }

    private fun toast(msg: String) = ui.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        try {
            val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 600), -1))
        } catch (e: Exception) {
        }
    }

    private fun safeRemove(v: View) {
        try {
            wm.removeView(v)
        } catch (e: Exception) {
        }
    }

    private fun lp(w: Int, hh: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            w, hh,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

    // Knight Online arayuzu: siyah panel + metalik gri cerceve, celik degrade butonlar
    private val KO_LACI = 0xFF2A3240.toInt()          // buton (celik-lacivert)
    private val KO_CERCEVE = 0xFF8C7A55.toInt()       // bronz cerceve (oyundaki paneller)
    private val KO_BEJ = 0xFFC9AE78.toInt()           // buton kenari (oyundaki gibi)

    private fun acik(c: Int, oran: Float): Int {
        val a = (c ushr 24) and 0xff
        val r = ((c shr 16) and 0xff); val g = ((c shr 8) and 0xff); val b = (c and 0xff)
        fun k(v: Int) = (v + (255 - v) * oran).toInt().coerceIn(0, 255)
        return (a shl 24) or (k(r) shl 16) or (k(g) shl 8) or k(b)
    }

    /**
     * Koyu renkler (panel/pencere) -> siyah zemin + gri metal cerceve.
     * Diger renkler (butonlar) -> yukaridan asagi koyulasan degrade + ince bej kenar.
     */
    private fun rounded(color: Int): GradientDrawable {
        val r = (color shr 16) and 0xff; val g = (color shr 8) and 0xff; val b = color and 0xff
        val panelMi = r < 40 && g < 40 && b < 40
        return if (panelMi) GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(4).toFloat()
            setStroke(dp(3), KO_CERCEVE)
        } else GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(acik(color, 0.28f), color)).apply {
            cornerRadius = dp(4).toFloat()
            setStroke(dp(2), KO_BEJ)
        }
    }

    /** Oyundaki gibi buton zemini: 0 = celik, 1 = altin (onay), 2 = koyu kirmizi (iptal) */
    private fun koButon(tur: Int = 0): GradientDrawable = when (tur) {
        1 -> GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFFB8955A.toInt(), 0xFF7A5E32.toInt(), 0xFF4A3A20.toInt())).apply {
            cornerRadius = dp(4).toFloat(); setStroke(dp(2), 0xFFF0D59A.toInt())
        }
        2 -> GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFF7A3A34.toInt(), 0xFF4E1F1B.toInt(), 0xFF2E110F.toInt())).apply {
            cornerRadius = dp(4).toFloat(); setStroke(dp(2), KO_BEJ)
        }
        else -> rounded(KO_LACI)
    }

    private fun btn(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
        setTextColor(0xFFF3E6C4.toInt())
        textSize = 15f
        gravity = Gravity.CENTER
        setPadding(dp(11), dp(9), dp(11), dp(9))
        setOnClickListener { onClick() }
    }

    private fun menuBtn(text: String, onClick: () -> Unit) =
        btn(text, onClick).apply { background = rounded(KO_LACI) }

    // ================= Yuzen panel =================

    @SuppressLint("ClickableViewAccessibility")
    private fun showPanel() {
        if (panel != null) return
        val params = lp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
            .apply { x = dp(8); y = dp(60) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(0xE60E0E0E.toInt())
        }

        val drag = btn("⠿") {}
        drag.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0
            var sy = 0
            var tx = 0f
            var ty = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = params.x; sy = params.y; tx = e.rawX; ty = e.rawY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = sx + (e.rawX - tx).toInt()
                        params.y = sy + (e.rawY - ty).toInt()
                        try {
                            wm.updateViewLayout(root, params)
                        } catch (ex: Exception) {
                        }
                    }
                }
                return true
            }
        })

        val play = btn("▶") { toggle() }
        playBtn = play
        val st = TextView(this).apply {
            text = "Hazır"
            setTextColor(0xFFB0FFB0.toInt())
            textSize = 12f
            setPadding(dp(6), 0, dp(10), 0)
        }
        statusTv = st

        val mb = btn(modYazi()) { modMenu() }.apply { setTextColor(0xFFE0B04A.toInt()) }
        modBtn = mb
        root.addView(drag)
        root.addView(play)
        root.addView(mb)
        root.addView(btn("⋯") { showMainMenu() })
        root.addView(st)

        try {
            wm.addView(root, params)
            panel = root
        } catch (e: Exception) {
            toast("Panel açılamadı: ${e.message}")
        }
    }

    // ================= Menuler =================

    private fun removeOverlay() {
        overlay?.let { safeRemove(it) }
        overlay = null
    }

    /**
     * Kutuyu sabit genislikte, olculmus yukseklikte (ekrana sigmazsa kaydirilabilir)
     * ekranin ortasinda gosterir. WRAP_CONTENT bazi cihazlarda pencereyi ezdigi icin.
     */
    private fun ortadaGoster(box: View, genislikPx: Int) {
        updateScreenSize()
        val sc = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            addView(box, ViewGroup.LayoutParams(genislikPx, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        box.measure(
            View.MeasureSpec.makeMeasureSpec(genislikPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val ekranH = minOf(screenW, screenH)
        val yukseklik = minOf(box.measuredHeight, ekranH - dp(24))
        overlay = sc
        try {
            wm.addView(sc, lp(genislikPx, yukseklik).apply { gravity = Gravity.CENTER })
        } catch (e: Exception) {
            overlay = null
        }
    }

    private fun showMenu(title: String, items: List<Pair<String, () -> Unit>>) {
        removeOverlay()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xF20E0E0E.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        box.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(8), dp(4), dp(8), dp(8))
        })
        val cols = if (items.size > 5) 2 else 1
        val bw = if (cols == 2) dp(170) else dp(220)
        var rowView: LinearLayout? = null
        items.forEachIndexed { idx, (label, action) ->
            if (idx % cols == 0) {
                val nr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                rowView = nr
                box.addView(nr)
                box.addView(View(this), LinearLayout.LayoutParams(1, dp(4)))
            }
            val rv = rowView!!
            if (idx % cols == 1) rv.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
            rv.addView(menuBtn(label) { removeOverlay(); action() },
                LinearLayout.LayoutParams(bw, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        box.addView(btn("İptal") { removeOverlay() }.apply { background = rounded(0xCC8B0000.toInt()) },
            LinearLayout.LayoutParams(bw, LinearLayout.LayoutParams.WRAP_CONTENT))
        ortadaGoster(box, cols * bw + (cols - 1) * dp(6) + dp(16))
    }

    private fun showMainMenu() {
        if (running) {
            toast("Menü için önce makroyu durdur (⏸)"); return
        }
        if (!Lisans.gecerliSimdi()) {
            toast("Önce uygulamadan üye girişi yap")
            openApp()
            return
        }
        showMenu(
            "Menü",
            listOf(
                "⚙ Ayarlar" to { ayarKarti() },
                "🛠 Tuşları düzenle" to { openEditor() },
                "🎨 Bar kaydet (HP/MP/hedef)" to { showBarChooser() },
                "📦 Kutu butonu kaydet" to { showLootChooser() },
                "🧪 Ekran testi" to { ekranTesti() },
                "🌡 Sistem kaydı" to { sistemKaydiGoster() },
                (if (VideoKaydedici.kayitta) "⏺ Video kaydı: AÇIK (${VideoKaydedici.sureSaniye()} sn) — durdur"
                 else "🎬 Video kaydı başlat") to { videoKayitToggle() },
                "🎥 Kamera testi" to { kameraTesti() },
                "📐 Alan testi" to { alanTesti() },
                "🎯 Seçili mobu kilitle" to { mobuKilitle() },
                "🔓 Mob kilitlerini kaldır" to {
                    val c = Config.load(this)
                    c.kilitler.clear()
                    c.save(this)
                    toast("Kilitler kaldırıldı, her moba vurulacak")
                },
                "⚙ Uygulamayı aç" to {
                    try {
                        startActivity(
                            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (e: Exception) {
                    }
                }
            )
        )
    }

    // ================= Oyun icinde ayarlar (uygulamaya gecmeden) =================

    private fun ayarKarti() {
        removeOverlay()
        val c = Config.load(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xF20E0E0E.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        box.addView(TextView(this).apply {
            text = "⚙ " + (if (Config.pkMi(this@MacroService)) "PK Bot • " + Config.sinifAd(this@MacroService) else "Farm Bot") + " ayarları"
            setTextColor(0xFFE0B04A.toInt())
            textSize = 16f
            setPadding(0, 0, 0, dp(6))
        })

        fun kaydet(degis: (Config) -> Unit) {
            val cc = Config.load(this)
            degis(cc)
            cc.save(this)
        }

        // - deger + satiri
        fun satir(ad: String, deger: () -> String, eksi: () -> Unit, arti: () -> Unit) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            r.addView(TextView(this).apply {
                text = ad
                setTextColor(Color.WHITE)
                textSize = 14f
            }, LinearLayout.LayoutParams(dp(150), LinearLayout.LayoutParams.WRAP_CONTENT))
            val v = TextView(this).apply {
                text = deger()
                setTextColor(Color.WHITE)
                textSize = 16f
                gravity = Gravity.CENTER
            }
            r.addView(menuBtn("−") { eksi(); v.text = deger() })
            r.addView(v, LinearLayout.LayoutParams(dp(70), LinearLayout.LayoutParams.WRAP_CONTENT))
            r.addView(menuBtn("+") { arti(); v.text = deger() })
            box.addView(r)
            box.addView(View(this), LinearLayout.LayoutParams(1, dp(4)))
        }

        satir("❤ HP potu", { "%" + Config.load(this).hpYuzde },
            { kaydet { it.hpYuzde = (it.hpYuzde - 5).coerceIn(10, 95) } },
            { kaydet { it.hpYuzde = (it.hpYuzde + 5).coerceIn(10, 95) } })
        satir("💧 MP potu", { "%" + Config.load(this).mpYuzde },
            { kaydet { it.mpYuzde = (it.mpYuzde - 5).coerceIn(5, 95) } },
            { kaydet { it.mpYuzde = (it.mpYuzde + 5).coerceIn(5, 95) } })
        if (Config.minorVar(this)) {
            val minorBtn = menuBtn("") {}
            fun minorYaz() {
                val ak = Config.load(this).minorAktif
                minorBtn.text = if (ak) "💚 Minor: AKTİF" else "💚 Minor: PASİF"
                minorBtn.background = if (ak) koButon(1) else koButon()
            }
            minorBtn.setOnClickListener {
                val c = Config.load(this)
                c.minorAktif = !c.minorAktif
                c.save(this)
                cfg.minorAktif = c.minorAktif
                // Pasife alinirken Minor aciksa bir kez basip kapat (mana yemesin)
                if (!c.minorAktif && minorAcik) {
                    minorAcik = false
                    cfg.points.firstOrNull { it.type == "minor" }?.let { m ->
                        try {
                            val p = Path().apply { moveTo(m.x.toFloat(), m.y.toFloat()); lineTo(m.x + 1f, m.y + 1f) }
                            dispatchGesture(GestureDescription.Builder()
                                .addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null)
                        } catch (e: Exception) {
                        }
                    }
                }
                minorYaz()
            }
            minorYaz()
            box.addView(minorBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            box.addView(View(this), LinearLayout.LayoutParams(1, dp(4)))
            satir("💚 Minor, can altında", { "%" + Config.load(this).minorYuzde },
                { kaydet { it.minorYuzde = (it.minorYuzde - 5).coerceIn(20, 99) } },
                { kaydet { it.minorYuzde = (it.minorYuzde + 5).coerceIn(20, 99) } })
        }
        if (!Config.pkMi(this) && !Config.pazarMi(this)) {
            // AKTIF/PASIF anahtari: sure/boyut ayarlari kapatinca da saklanir
            fun anahtar(baslik: String, oku: (Config) -> Boolean, yaz: (Config, Boolean) -> Unit, acilinca: () -> Unit = {}) {
                val b = menuBtn("") {}
                fun goster() {
                    val ak = oku(Config.load(this))
                    b.text = baslik + ": " + (if (ak) "AKTİF" else "PASİF")
                    b.background = if (ak) koButon(1) else koButon()
                }
                b.setOnClickListener {
                    kaydet { yaz(it, !oku(it)) }
                    goster()
                    if (oku(Config.load(this))) acilinca()
                }
                goster()
                box.addView(b, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                box.addView(View(this), LinearLayout.LayoutParams(1, dp(4)))
            }

            // --- Alan siniri (ekranda karakter etrafinda dikdortgen) ---
            anahtar("📐 Alan sınırı", { it.alanAktif }, { c, v -> c.alanAktif = v }) { alanCerceveGoster() }
            satir("↔ Alan genişliği", { "%" + Config.load(this).alanW },
                { kaydet { it.alanW = (it.alanW - 5).coerceIn(10, 95) }; alanCerceveGoster() },
                { kaydet { it.alanW = (it.alanW + 5).coerceIn(10, 95) }; alanCerceveGoster() })
            satir("↕ Alan yüksekliği", { "%" + Config.load(this).alanH },
                { kaydet { it.alanH = (it.alanH - 5).coerceIn(10, 95) }; alanCerceveGoster() },
                { kaydet { it.alanH = (it.alanH + 5).coerceIn(10, 95) }; alanCerceveGoster() })
            satir("⇅ Alan konumu (üst/alt)", { "%" + Config.load(this).alanCy },
                { kaydet { it.alanCy = (it.alanCy - 2).coerceIn(20, 80) }; alanCerceveGoster() },
                { kaydet { it.alanCy = (it.alanCy + 2).coerceIn(20, 80) }; alanCerceveGoster() })

            // --- Mesafe siniri (sure: bu surede vurulmaya baslanmazsa birak) ---
            anahtar("🏃 Mesafe sınırı", { it.menzilAktif }, { c, v -> c.menzilAktif = v })
            satir("⏱ Mesafe süresi", { "${Config.load(this).menzilSn} sn" },
                { kaydet { it.menzilSn = (it.menzilSn - 1).coerceIn(1, 15) } },
                { kaydet { it.menzilSn = (it.menzilSn + 1).coerceIn(1, 15) } })
            satir("⏳ Uzak mob sonrası bekleme", { "${Config.load(this).menzilBekleSn} sn" },
                { kaydet { it.menzilBekleSn = (it.menzilBekleSn - 1).coerceIn(1, 15) } },
                { kaydet { it.menzilBekleSn = (it.menzilBekleSn + 1).coerceIn(1, 15) } })
        }
        satir("⏱ Süre", { "${Config.load(this).minutes} dk" },
            { kaydet { it.minutes = (it.minutes - 10).coerceIn(10, 600) } },
            { kaydet { it.minutes = (it.minutes + 10).coerceIn(10, 600) } })

        // Hiz secimi
        val hizSatir = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        hizSatir.addView(TextView(this).apply {
            text = "⚡ Hız"
            setTextColor(Color.WHITE)
            textSize = 14f
        }, LinearLayout.LayoutParams(dp(150), LinearLayout.LayoutParams.WRAP_CONTENT))
        val hizlar = ArrayList<TextView>()
        fun hizMod(cc: Config) = when {
            cc.maxDelay <= 150 -> 2
            cc.maxDelay <= 320 -> 1
            else -> 0
        }
        fun hizBoya() {
            val m = hizMod(Config.load(this))
            hizlar.forEachIndexed { i, b ->
                b.background = rounded(if (i == m) 0xFFE0B04A.toInt() else 0xFF3A3A3A.toInt())
            }
        }
        listOf("Normal", "Hızlı", "Seri").forEachIndexed { i, ad ->
            val b = btn(ad) {
                kaydet {
                    when (i) {
                        2 -> { it.minDelay = 50; it.maxDelay = 110; it.pauseChance = 0; it.skMin = 0; it.skMax = 150 }
                        1 -> { it.minDelay = 120; it.maxDelay = 300; it.pauseChance = 2; it.skMin = 150; it.skMax = 700 }
                        else -> { it.minDelay = 180; it.maxDelay = 550; it.pauseChance = 3; it.skMin = 250; it.skMax = 1500 }
                    }
                }
                hizBoya()
            }
            hizlar.add(b)
            hizSatir.addView(b)
            hizSatir.addView(View(this), LinearLayout.LayoutParams(dp(4), 1))
        }
        hizBoya()
        box.addView(hizSatir)
        box.addView(View(this), LinearLayout.LayoutParams(1, dp(4)))

        // Kutu toplama ac/kapa
        val kutuBtn = menuBtn("") {}
        fun kutuYaz() {
            kutuBtn.text = if (Config.load(this).lootOn) "📦 Kutu toplama: AÇIK" else "📦 Kutu toplama: KAPALI"
        }
        kutuBtn.setOnClickListener {
            kaydet { it.lootOn = !it.lootOn }
            kutuYaz()
        }
        kutuYaz()
        box.addView(kutuBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(View(this), LinearLayout.LayoutParams(1, dp(4)))

        box.addView(TextView(this).apply {
            text = if (c.kilitler.isEmpty()) "🎯 Mob kilidi yok (her moba vurur)"
            else "🎯 ${c.kilitler.size} mob kilitli"
            setTextColor(0xFFB0B8C4.toInt())
            textSize = 13f
            setPadding(0, dp(2), 0, dp(8))
        })

        box.addView(btn("Kapat ✓") { removeOverlay() }.apply { background = rounded(0xFF2E9E5B.toInt()) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        ortadaGoster(box, dp(440))
    }

    // ================= Oyun icinde tus duzenleme =================

    private fun closeEditor() {
        editor?.let { safeRemove(it) }
        editor = null
    }

    private fun etiket(p: Nokta): String = when (p.type) {
        "saldiri" -> "⚔"
        "hedef" -> "🎯"
        "hp_pot" -> "HP"
        "minor" -> "💚"
        "mp_pot" -> "MP"
        else -> {
            val no = p.name.removePrefix("Skill ").trim()
            val sure = if (p.cd <= 0f) "∞" else (if (p.cd == p.cd.toLong().toFloat()) "${p.cd.toLong()}s" else "${p.cd}s")
            "S$no\n$sure"
        }
    }

    private fun renk(p: Nokta): Int = when {
        p.type == "saldiri" -> 0xE0C0392B.toInt()
        p.type == "hedef" -> 0xE0D35400.toInt()
        p.type == "hp_pot" -> 0xE0B03030.toInt()
        p.type == "mp_pot" -> 0xE02E5BBA.toInt()
        !p.on -> 0xC0555555.toInt()
        else -> 0xE0208A4E.toInt()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun openEditor() {
        if (running) {
            toast("Önce makroyu durdur"); return
        }
        removeOverlay()
        closeEditor()
        updateScreenSize()
        val root = FrameLayout(this).apply {
            setBackgroundColor(0x33000000)
            isClickable = true
        }
        editor = root
        root.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                if (overlay != null) {
                    removeOverlay()     // acik menuyu kapat
                } else {
                    val x = e.rawX.toInt()
                    val y = e.rawY.toInt()
                    editorAdd(x, y)
                }
            }
            true
        }
        try {
            wm.addView(root, lp(screenW, screenH).apply { x = 0; y = 0 })
        } catch (e: Exception) {
            editor = null
            toast("Düzenleme ekranı açılamadı"); return
        }
        editorRefresh()
    }

    /** Isaretleri ve ust cubugu yeniden ciz */
    private fun editorRefresh() {
        val root = editor ?: return
        root.removeAllViews()
        val cf = Config.load(this)
        val size = dp(44)
        cf.points.forEachIndexed { i, p ->
            val m = TextView(this).apply {
                text = etiket(p)
                setTextColor(Color.WHITE)
                textSize = 11f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(renk(p))
                    setStroke(dp(2), Color.WHITE)
                }
                setOnClickListener { if (overlay != null) removeOverlay() else editorPointMenu(i) }
            }
            root.addView(m, FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = (p.x - size / 2).coerceAtLeast(0)
                topMargin = (p.y - size / 2).coerceAtLeast(0)
            })
        }

        // Ust cubuk: bilgi + Hepsini sil + Bitti
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(0xE0202020.toInt())
            setPadding(dp(10), dp(4), dp(6), dp(4))
        }
        bar.addView(TextView(this).apply {
            text = "Boş slota dokun: ekle  •  Etikete dokun: değiştir"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, 0, dp(10), 0)
        })
        bar.addView(btn("Hepsini sil") {
            if (overlay != null) removeOverlay()
            showMenu("Tüm tuşlar silinsin mi?", listOf("Evet, hepsini sil" to {
                val cf2 = Config.load(this)
                cf2.points.clear()
                cf2.tuslarOto = false
                    cf2.save(this)
                editorRefresh()
            }))
        }.apply { background = rounded(0xCC8B0000.toInt()) })
        bar.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
        bar.addView(btn("Bitti ✓") {
            removeOverlay()
            closeEditor()
            toast("Kaydedildi")
        }.apply { background = rounded(0xFF2E9E5B.toInt()) })
        root.addView(bar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL or Gravity.START
        ).apply { leftMargin = dp(12) })
    }

    private fun turListesi(onPick: (String) -> Unit): List<Pair<String, () -> Unit>> = listOf(
        "✨ Skill" to { onPick("skill") },
        "❤ HP pot" to { onPick("hp_pot") },
        "💧 MP pot" to { onPick("mp_pot") },
        "🎯 Mob seç" to { onPick("hedef") },
        "⚔ Kılıç (PK)" to { onPick("saldiri") }
    ) + (if (Config.minorVar(this)) listOf("💚 Minor" to { onPick("minor") }) else emptyList())

    /**
     * Sure secici (oyun icinde): istedigin saniyeyi - / + ile ayarla, hazir secenekler de var.
     * 0 = surekli (beklemeden).
     */
    private fun cdMenu(baslangic: Float = 1f, onPick: (Float) -> Unit) {
        removeOverlay()
        var deger = baslangic.coerceIn(0f, 3600f)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xF20E0E0E.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }
        box.addView(TextView(this).apply {
            text = "⏱ Bu tuş kaç saniyede bir basılsın?"
            setTextColor(0xFFE0B04A.toInt()); textSize = 15f
            setPadding(0, 0, 0, dp(6))
        })
        val goster = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 28f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(8))
        }
        fun yaz() {
            goster.text = if (deger <= 0f) "∞ sürekli" else {
                val r = Math.round(deger * 10) / 10f
                (if (r == r.toLong().toFloat()) "${r.toLong()}" else "$r") + " sn"
            }
        }
        yaz()
        box.addView(goster, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        fun satir(adimlar: List<Pair<String, () -> Unit>>) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            adimlar.forEachIndexed { i, (ad, is_) ->
                if (i > 0) r.addView(View(this), LinearLayout.LayoutParams(dp(5), 1))
                r.addView(menuBtn(ad) { is_(); yaz() },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            box.addView(r)
            box.addView(View(this), LinearLayout.LayoutParams(1, dp(5)))
        }
        fun ekle(d: Float) {
            deger = (Math.round((deger + d) * 10) / 10f).coerceIn(0f, 3600f)
        }
        satir(listOf("−10" to { ekle(-10f) }, "−1" to { ekle(-1f) }, "−0.1" to { ekle(-0.1f) },
            "+0.1" to { ekle(0.1f) }, "+1" to { ekle(1f) }, "+10" to { ekle(10f) }))
        satir(listOf("∞" to { deger = 0f }, "0.5" to { deger = 0.5f }, "1" to { deger = 1f },
            "2" to { deger = 2f }, "5" to { deger = 5f }, "60" to { deger = 60f }, "100" to { deger = 100f }))

        val alt = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        alt.addView(btn("✓ Tamam") { removeOverlay(); onPick(deger) }.apply { background = rounded(0xFF2E9E5B.toInt()) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        alt.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
        alt.addView(btn("İptal") { removeOverlay() }.apply { background = rounded(0xCC8B0000.toInt()) },
            LinearLayout.LayoutParams(dp(100), LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(alt)
        ortadaGoster(box, dp(430))
    }

    /** Bos yere dokunuldu: yeni tus ekle */
    private fun editorAdd(x: Int, y: Int) {
        showMenu("Bu slot ne olsun?", turListesi { type ->
            if (type == "minor") {
                cdMenu { cd ->
                    val cf = Config.load(this)
                    cf.points.removeAll { it.type == "minor" }
                    cf.points.add(Nokta("Minor", "minor", x, y, cd, true))
                    cf.tuslarOto = false
                    cf.save(this)
                    editorRefresh()
                }
            } else if (type == "skill") {
                cdMenu { cd ->
                    val cf = Config.load(this)
                    cf.points.add(Nokta("Skill", "skill", x, y, cd, true))
                    renumber(cf)
                    cf.tuslarOto = false
                    cf.save(this)
                    editorRefresh()
                }
            } else {
                val cf = Config.load(this)
                cf.points.removeAll { it.type == type }
                cf.points.add(Nokta(Config.label(type), type, x, y))
                cf.tuslarOto = false
                    cf.save(this)
                editorRefresh()
            }
        })
    }

    /** Var olan isarete dokunuldu */
    private fun editorPointMenu(i: Int) {
        val cf = Config.load(this)
        if (i >= cf.points.size) return
        val p = cf.points[i]
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (p.type == "skill") {
            val skiller = cf.points.indices.filter { cf.points[it].type == "skill" }
            val sira = skiller.indexOf(i)
            items.add("⏱ Süre (${if (p.cd <= 0f) "∞" else "${p.cd} sn"})" to {
                cdMenu(p.cd) { cd ->
                    val c = Config.load(this)
                    if (i < c.points.size) {
                        c.points[i].cd = cd
                    c.save(this)
                    }
                    editorRefresh()
                }
            })
            if (sira > 0) items.add("◀ Sırada öne al" to {
                val c = Config.load(this)
                val j = skiller[sira - 1]
                if (i < c.points.size && j < c.points.size) {
                    val t = c.points[i]; c.points[i] = c.points[j]; c.points[j] = t
                    renumber(c); c.tuslarOto = false; c.save(this)
                }
                editorRefresh()
            })
            if (sira < skiller.size - 1) items.add("▶ Sırada arkaya al" to {
                val c = Config.load(this)
                val j = skiller[sira + 1]
                if (i < c.points.size && j < c.points.size) {
                    val t = c.points[i]; c.points[i] = c.points[j]; c.points[j] = t
                    renumber(c); c.tuslarOto = false; c.save(this)
                }
                editorRefresh()
            })
            items.add((if (p.on) "⏸ Kapat" else "▶ Aç") to {
                val c = Config.load(this)
                if (i < c.points.size) {
                    c.points[i].on = !c.points[i].on
                    c.save(this)
                }
                editorRefresh()
            })
        }
        items.add("🔁 Türünü değiştir" to {
            showMenu("Yeni tür", turListesi { type ->
                val uygula = { cd: Float ->
                    val c = Config.load(this)
                    if (i < c.points.size) {
                        val old = c.points[i]
                        c.points.removeAt(i)
                        if (type != "skill") c.points.removeAll { it.type == type }
                        c.points.add(Nokta(Config.label(type), type, old.x, old.y, cd, true))
                        renumber(c)
                        c.tuslarOto = false
                    c.save(this)
                    }
                    editorRefresh()
                }
                if (type == "skill") cdMenu { cd -> uygula(cd) } else uygula(0f)
            })
        })
        items.add("🗑 Sil" to {
            val c = Config.load(this)
            if (i < c.points.size) {
                c.points.removeAt(i)
                renumber(c)
                c.tuslarOto = false
                    c.save(this)
            }
            editorRefresh()
        })
        showMenu("${p.name}", items)
    }

    private fun renumber(c: Config) {
        var n = 1
        c.points.forEach { if (it.type == "skill") it.name = "Skill ${n++}" }
    }

    /** Uygulamanin ekrandan gordugu goruntuyu Indirilenler'e kaydeder + sonucu yazar */
    private fun ekranTesti() {
        if (!ScreenSampler.running) {
            toast("Ekran okuma kapalı. Önce ▶'a bas ya da uygulamadan izin ver"); return
        }
        toast("Test ediliyor…")
        h.post {
            val o = try {
                Preset.olcekBul(this)
            } catch (e: Exception) {
                null
            }
            ScreenSampler.bekle(600)
            val yas = SystemClock.uptimeMillis() - ScreenSampler.lastFrameAt
            val bmp = ScreenSampler.tamKare()
            val kayit = if (bmp != null) pngKaydet(bmp, "pedal_test_${System.currentTimeMillis() / 1000}.png") else false
            val (w, hh) = Preset.landscapeSize(this)
            val kare = if (bmp != null) "${bmp.width}x${bmp.height}" else "yok"
            val sonuc = if (o != null) "BULUNDU ölçek %.2f".format(o.s)
            else "bulunamadı (fark ${Preset.sonFark}, en yakın %.2f)".format(Preset.sonOlcekDegeri)
            toast("Ekran ${w}x$hh • kare $kare (${yas} ms önce) • aslan $sonuc" +
                if (kayit) " • İndirilenler'e kaydedildi" else " • resim kaydedilemedi")
        }
    }

    private fun pngKaydet(bmp: Bitmap, ad: String): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return try {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, ad)
                put(MediaStore.Downloads.MIME_TYPE, "image/png")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return false
            contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            true
        } catch (e: Exception) {
            false
        }
    }

    // ================= Panelden mesaj ve surum kontrolu =================

    /** Dakikada bir: yeni mesaj var mi, surum yeterli mi? */
    private val mesajDongu = object : Runnable {
        override fun run() {
            try {
                mesajKontrol()
            } catch (e: Exception) {
            }
            ah.postDelayed(this, 60_000)
        }
    }

    private fun mesajKontrol() {
        val token = Lisans.token(this)
        val url = Lisans.yanUrl(this, "mesaj.php") ?: return
        if (token.isEmpty()) return
        val pr = getSharedPreferences("mesaj", MODE_PRIVATE)
        val son = pr.getInt("son", 0)
        val hp = getSharedPreferences("hata", MODE_PRIVATE)
        val hataMetni = if (!hp.getBoolean("gonderildi", true)) hp.getString("son", "") ?: "" else ""
        val tam = url + "?token=" + URLEncoder.encode(token, "UTF-8") +
            "&son=" + son + "&surum=" + Lisans.surumKodu(this) +
            (if (hataMetni.isNotEmpty()) "&hata=" + URLEncoder.encode(hataMetni, "UTF-8") +
                "&hz=" + hp.getLong("zaman", 0L) else "")
        val bag = URL(tam).openConnection() as HttpURLConnection
        bag.connectTimeout = 8000
        bag.readTimeout = 8000
        val cevap = bag.inputStream.bufferedReader().use { it.readText() }
        bag.disconnect()
        val j = JSONObject(cevap)
        if (j.optInt("ok") != 1) return
        if (hataMetni.isNotEmpty()) hp.edit().putBoolean("gonderildi", true).apply()

        // Eski surum: makroyu durdur, guncelleme iste
        val g = j.optJSONObject("guncelle")
        if (g != null) {
            val ilk = !Lisans.guncelleGerekli
            Lisans.guncelleIsaretle(g.optString("guncelle"), g.optString("mesaj"))
            if (ilk) bildirim(999_999, "⬆ Güncelleme gerekli", Lisans.guncelleMesaj.ifEmpty { "Yeni sürüm çıktı. Devam etmek için güncelle." })
            ui.post {
                if (running) stopMacro()
                guncelleKarti()
            }
            return
        }
        // Uyelik kapatildi/bitti
        if (!j.optBoolean("aktif", true)) {
            ui.post { if (running) stopMacro("Üyelik aktif değil. Makro durdu") }
        }
        // Yeni mesajlar
        val m = j.optJSONArray("mesajlar") ?: return
        var enSon = son
        for (i in 0 until m.length()) {
            val o = m.getJSONObject(i)
            enSon = maxOf(enSon, o.optInt("id"))
            val saat = java.text.SimpleDateFormat("HH:mm", java.util.Locale("tr"))
                .format(java.util.Date(o.optLong("zaman") * 1000))
            val metin = o.optString("metin")
            bildirim(o.optInt("id"), "📢 Projeindirpedal • $saat", metin)
            ui.post { mesajGoster("📢 Mesaj • $saat", metin) }
        }
        if (enSon != son) pr.edit().putInt("son", enSon).apply()
    }

    /** Bildirim cubugu + kilit ekrani + ses */
    private fun bildirim(id: Int, baslik: String, metin: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel("mesaj", "Mesajlar", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Panelden gelen mesajlar"
                    enableVibration(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
            val pi = PendingIntent.getActivity(
                this, id,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = Notification.Builder(this, "mesaj")
                .setSmallIcon(R.drawable.ic_bildirim)
                .setContentTitle(baslik)
                .setContentText(metin)
                .setStyle(Notification.BigTextStyle().bigText(metin))
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            nm.notify(10_000 + id, n)
        } catch (e: Exception) {
        }
    }

    private fun mesajGoster(baslik: String, metin: String) {
        mesajKuyrugu.addLast(baslik to metin)
        // Oyun disinda kart acma; bildirim zaten gitti, oyuna donunce gosterilir
        if (kart == null && oyundaMi()) siradakiMesaj()
    }

    private fun siradakiMesaj() {
        val (b, m) = mesajKuyrugu.removeFirstOrNull() ?: return
        kartGoster(b, m, listOf("Tamam" to { kartKapat(); siradakiMesaj() }))
        vibrate()
    }

    private fun guncelleKarti() {
        val url = Lisans.guncelleUrl
        val metin = Lisans.guncelleMesaj.ifEmpty { "Yeni sürüm çıktı. Devam etmek için güncelle." }
        val butonlar = ArrayList<Pair<String, () -> Unit>>()
        if (url.isNotEmpty()) {
            butonlar.add("⬇ İndir" to {
                kartKapat()
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    toast("Link açılamadı")
                }
            })
        }
        butonlar.add("Kapat" to { kartKapat() })
        kartGoster("⬆ Güncelleme gerekli", metin, butonlar)
        statusTv?.text = "Güncelle"
        vibrate()
    }

    private fun kartKapat() {
        kart?.let { safeRemove(it) }
        kart = null
    }

    /** Oyunun ustunde buyuk bilgi karti */
    private fun kartGoster(baslik: String, metin: String, butonlar: List<Pair<String, () -> Unit>>) {
        kartKapat()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xF20E0E0E.toInt())
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        box.addView(TextView(this).apply {
            text = baslik
            setTextColor(0xFFE0B04A.toInt())
            textSize = 14f
        })
        box.addView(TextView(this).apply {
            text = metin
            setTextColor(Color.WHITE)
            textSize = 19f
            setPadding(0, dp(6), 0, dp(12))
        }, LinearLayout.LayoutParams(dp(340), LinearLayout.LayoutParams.WRAP_CONTENT))
        val satir = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        for ((ad, is_) in butonlar) {
            satir.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
            satir.addView(btn(ad) { is_() }.apply { background = rounded(0xFF2E9E5B.toInt()) })
        }
        box.addView(satir, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        // Sabit genislik + olculmus yukseklik (WRAP_CONTENT bazi cihazlarda eziliyor)
        val gen = dp(376)
        box.measure(
            View.MeasureSpec.makeMeasureSpec(gen, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        kart = box
        try {
            wm.addView(box, lp(gen, minOf(box.measuredHeight, minOf(screenW, screenH) - dp(24)))
                .apply { gravity = Gravity.CENTER })
        } catch (e: Exception) {
            kart = null
        }
    }

    /** Hatayi kisa haliyle kaydeder; dakikalik kontrolde sunucuya gonderilir */
    private fun hataKaydet(yer: String, e: Throwable) {
        try {
            val iz = e.stackTrace.take(3).joinToString(" < ") { "${it.fileName}:${it.lineNumber}" }
            val metin = "$yer: ${e.javaClass.simpleName}: ${e.message ?: ""} @ $iz".take(480)
            getSharedPreferences("hata", MODE_PRIVATE).edit()
                .putString("son", metin).putLong("zaman", System.currentTimeMillis() / 1000)
                .putBoolean("gonderildi", false).commit()
        } catch (x: Exception) {
        }
    }

    /** Dokunmak guvenli mi? Degilse sebebini dondurur */
    private fun dokunmaEngeli(now: Long): String? {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) return "🌙"
        if (!oyundaMi()) return "⏸"   // sadece MykoMobile ekrandayken dokun
        if (ScreenSampler.running && now - ScreenSampler.lastFrameAt > 3000) return "📷"
        return null
    }

    // ================= 🏪 Pazar Bot =================
    // Pazar kur: Commands -> OPEN MERCHANT -> ogretilen esyalara 2 kez dokun -> fiyat -> OK -> OK
    // Kontrol (her N dk): Commands -> SLAVE MERCHANT -> satilanlarin parasini al ->
    //   hepsi satildiysa Close Slave Merchant -> pazari yeniden kur.

    private val pzThread = HandlerThread("pazar").apply { start() }
    private val pzH = Handler(pzThread.looper)
    @Volatile private var pazarCalisiyor = false
    private var pzSonrakiKontrol = 0L
    private var pzArdArdaHata = 0   // ust uste basarisiz kurulum: bir sinir sonra guvenli dur

    private class PzSablonlar(
        val commands: Sablon, val openM: Sablon, val slaveM: Sablon, val create: Sablon,
        val fiyat: Sablon, val slave: Sablon, val satilmadi: Sablon, val slaveKapat: Sablon
    )
    private var pzT: PzSablonlar? = null

    private fun pzSablonYukle(s: Float): PzSablonlar? {
        fun y(ad: String, tol: Int) = Preset.loadTemplateF(this, ad, s, tol, 0.5f, 0.5f)
        return PzSablonlar(
            y("pz_commands.png", 24) ?: return null,
            y("pz_open_merchant.png", 18) ?: return null,
            y("pz_slave_merchant.png", 19) ?: return null,
            y("pz_create_baslik.png", 19) ?: return null,
            y("pz_fiyat_baslik.png", 17) ?: return null,
            y("pz_slave_baslik.png", 29) ?: return null,
            y("pz_satilmadi.png", 23) ?: return null,
            y("pz_slave_kapat.png", 21) ?: return null
        )
    }

    private fun pzUyu(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
        }
    }

    /** Pazar thread'inden bekleyerek dokun (cift = iki kez hizli) */
    private fun pzDokun(x: Float, y: Float, cift: Boolean = false) {
        val latch = java.util.concurrent.CountDownLatch(1)
        val b = GestureDescription.Builder()
        b.addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y); lineTo(x + 1f, y + 1f) }, 0, 50))
        if (cift) b.addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y); lineTo(x + 1f, y) }, 170, 50))
        ui.post {
            try {
                dispatchGesture(b.build(), object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(d: GestureDescription?) { latch.countDown() }
                    override fun onCancelled(d: GestureDescription?) { latch.countDown() }
                }, null)
            } catch (e: Exception) {
                latch.countDown()
            }
        }
        try {
            latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
        }
    }

    /** Sablonu en fazla ms boyunca arar; bulursa sol-ust kosesinin ekran konumu */
    private fun pzBekle(t: Sablon, ms: Long): FloatArray? {
        val son = SystemClock.uptimeMillis() + ms
        while (pazarCalisiyor || pzOgretme) {
            ScreenSampler.grab()?.let { f ->
                findT(f, t)?.let { p ->
                    return floatArrayOf(ScreenSampler.toScreen(p[0].toFloat()), ScreenSampler.toScreen(p[1].toFloat()))
                }
            }
            if (SystemClock.uptimeMillis() > son) return null
            pzUyu(150)
        }
        return null
    }

    private fun pzS() = olcek?.s ?: (screenW / 2712f)

    /** Bir noktanin etrafindaki ortalama parlaklik (0-255) */
    private fun pzParlaklik(x: Float, y: Float, r: Float): Int {
        val k = ScreenSampler.bolge((x - r).toInt(), (y - r).toInt(), (x + r).toInt(), (y + r).toInt()) ?: return 0
        var t = 0L
        for (c in k.third) t += (((c shr 16) and 0xff) * 299 + ((c shr 8) and 0xff) * 587 + (c and 0xff) * 114) / 1000
        return if (k.third.isEmpty()) 0 else (t / k.third.size).toInt()
    }

    /** Envanter kutusundaki ikon (sag-alt adet sayisi haric), yari cozunurluk */
    private fun pzIkon(cx: Float, cy: Float): Triple<Int, Int, IntArray>? {
        val s = pzS()
        return ScreenSampler.bolge((cx - 38 * s).toInt(), (cy - 38 * s).toInt(), (cx + 20 * s).toInt(), (cy + 20 * s).toInt())
    }

    private fun pzIkonUyar(k: Triple<Int, Int, IntArray>, e: PazarEsya): Boolean {
        val (w, hh, px) = k
        if (kotlin.math.abs(w - e.w) > 1 || kotlin.math.abs(hh - e.h) > 1) return false
        val ww = minOf(w, e.w)
        val h2 = minOf(hh, e.h)
        var best = Long.MAX_VALUE
        for (dy in -1..1) for (dx in -1..1) {
            var top = 0L
            var n = 0
            for (y in 1 until h2 - 1) for (x in 1 until ww - 1) {
                val yy = y + dy
                val xx = x + dx
                if (yy !in 0 until hh || xx !in 0 until w) continue
                top += ScreenSampler.diff(px[yy * w + xx] and 0xFFFFFF, e.px[y * e.w + x] and 0xFFFFFF)
                n++
            }
            if (n > 0) best = minOf(best, top / n)
        }
        return best < 75
    }

    /** Oyunun fiyat alanina yazar (erisilebilirlik ile); olmazsa klavyenin rakam tuslarina basar */
    private fun pzFiyatYaz(fiyat: Long) {
        var yazildi = false
        val latch = java.util.concurrent.CountDownLatch(1)
        ui.post {
            try {
                val root = rootInActiveWindow
                var n = root?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
                if (n == null && root != null) n = pzYaziAlaniBul(root)
                if (n != null) {
                    val b = android.os.Bundle()
                    b.putCharSequence(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        fiyat.toString()
                    )
                    yazildi = n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, b)
                }
            } catch (e: Exception) {
            }
            latch.countDown()
        }
        try { latch.await(2, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) {}
        if (!yazildi) {
            // Yedek: sayi klavyesi (ekran oranlari, oyunun acildigi klavye duzeni)
            val tus = mapOf(
                '1' to (432f to 758f), '2' to (1039f to 758f), '3' to (1647f to 758f),
                '4' to (432f to 890f), '5' to (1039f to 890f), '6' to (1647f to 890f),
                '7' to (432f to 1019f), '8' to (1039f to 1019f), '9' to (1647f to 1019f),
                '0' to (1039f to 1148f)
            )
            for (ch in fiyat.toString()) {
                val (kx, ky) = tus[ch] ?: continue
                pzDokun(kx / 2712f * screenW, ky / 1220f * screenH)
                pzUyu(120)
            }
        }
        pzUyu(300)
        // Klavyedeki TAMAM / onay
        val tamam = java.util.concurrent.CountDownLatch(1)
        var basildi = false
        ui.post {
            try {
                val root = rootInActiveWindow
                for (ad in listOf("TAMAM", "Tamam", "OK", "Done", "Bitti")) {
                    val l = root?.findAccessibilityNodeInfosByText(ad) ?: continue
                    val d = l.firstOrNull { it.isClickable } ?: l.firstOrNull()?.parent
                    if (d != null && d.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
                        basildi = true; break
                    }
                }
            } catch (e: Exception) {
            }
            tamam.countDown()
        }
        try { tamam.await(2, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) {}
        if (!basildi) pzDokun(2253f / 2712f * screenW, 1148f / 1220f * screenH)   // klavyedeki ✓
    }

    private fun pzYaziAlaniBul(n: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
        if (n.isEditable) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            pzYaziAlaniBul(c)?.let { return it }
        }
        return null
    }

    private fun pzDurum(t: String) = ui.post { statusTv?.text = t }

    /** Pazari kurar; konan esya sayisi (-1 = hata) */
    private fun pazarKur(): Int {
        val t = pzT ?: return -1
        val s = pzS()
        val esyalar = Config.pazarEsyalar(this)
        if (esyalar.isEmpty()) return -1
        pzDurum("🏪 Pazar kuruluyor")
        val cm = pzBekle(t.commands, 2500) ?: return -1
        pzDokun(cm[0] + 77 * s, cm[1] + 24 * s)
        val om = pzBekle(t.openM, 3000) ?: return -1
        pzDokun(om[0] + 135 * s, om[1] + 30 * s)
        val cr = pzBekle(t.create, 3500) ?: return -1
        pzUyu(500)
        var konan = 0
        loop@ for (r in 0 until 4) for (c in 0 until 7) {
            if (!pazarCalisiyor) return konan
            if (konan >= 12) break@loop
            val cx = cr[0] + (-144 + 100 * c) * s
            val cy = cr[1] + (395 + 100.67f * r) * s
            val ikon = pzIkon(cx, cy) ?: continue
            val e = esyalar.firstOrNull { pzIkonUyar(ikon, it) } ?: continue
            pzDokun(cx, cy, cift = true)
            val fp = pzBekle(t.fiyat, 2500) ?: continue
            pzDokun(fp[0] + 276 * s, fp[1] + 371 * s)      // fiyat alani
            pzUyu(900)
            pzFiyatYaz(e.fiyat)
            pzUyu(700)
            val fp2 = pzBekle(t.fiyat, 1500)
            if (fp2 != null) pzDokun(fp2[0] + 163 * s, fp2[1] + 464 * s)   // OK
            pzUyu(900)
            konan++
            pzDurum("🏪 $konan eşya kondu")
        }
        if (konan == 0) {
            pzDokun(cr[0] + 253 * s, cr[1] + 804 * s)   // CANCEL
            return 0
        }
        pzDokun(cr[0] + 66 * s, cr[1] + 804 * s)        // OK: pazar acilir
        pzUyu(1500)
        return konan
    }

    /** Satislari kontrol eder: paralari alir, hepsi satildiysa pazari yeniden kurar */
    private fun pazarKontrol() {
        val t = pzT ?: return
        val s = pzS()
        pzDurum("🏪 Kontrol")
        val cm = pzBekle(t.commands, 2500) ?: return
        pzDokun(cm[0] + 77 * s, cm[1] + 24 * s)
        val sm = pzBekle(t.slaveM, 3000) ?: return
        pzDokun(sm[0] + 135 * s, sm[1] + 29 * s)
        val sb = pzBekle(t.slave, 3500)
        if (sb == null) {
            // Pazar yok gibi: dogrudan kur
            kurVeBildir(); return
        }
        pzUyu(600)
        var alinan = 0
        for (k in 0 until 6) {
            val ry = sb[1] + (209 + 104 * k) * s
            val ikonVar = pzParlaklik(sb[0] - 239 * s, ry, 20 * s) > 35
            if (!ikonVar) continue
            val cx = sb[0] + 513 * s
            if (pzParlaklik(cx, ry, 22 * s) > 45) {   // para isareti
                pzDokun(cx, ry)
                alinan++
                pzUyu(800)
            }
        }
        pzUyu(1200)
        val f = ScreenSampler.grab()
        val kalanVar = f != null && findT(f, t.satilmadi) != null
        if (alinan > 0) bildirim(4100, "🏪 Pazar", "$alinan eşya satıldı, paraları toplandı")
        if (!kalanVar) {
            // Hepsi satildi: pazari kapat ve yeniden kur
            val kp = pzBekle(t.slaveKapat, 2000)
            if (kp != null) pzDokun(kp[0] + 210 * s, kp[1] + 25 * s)
            else pzDokun(sb[0] + 561 * s, sb[1] + 23 * s)
            pzUyu(2000)
            kurVeBildir()
        } else {
            pzDokun(sb[0] + 561 * s, sb[1] + 23 * s)    // X ile kapat
            pzUyu(600)
        }
    }

    private fun kurVeBildir() {
        val n = pazarKur()
        when {
            n > 0 -> {
                pzArdArdaHata = 0
                bildirim(4101, "🏪 Pazar kuruldu", "$n eşya satışa kondu")
            }
            n == 0 -> {
                pzArdArdaHata = 0
                bildirim(4102, "🏪 Pazar", "Satılacak eşya kalmadı, Pazar Bot durdu")
                ui.post { pazarDurdur() }
            }
            else -> {
                pzArdArdaHata++
                if (pzArdArdaHata >= 3) {
                    bildirim(4103, "🏪 Pazar Bot durdu",
                        "Commands/Merchant ekranı $pzArdArdaHata kez art arda tanınamadı. " +
                            "Oyun ekranını kontrol et, gerekirse tekrar başlat.")
                    ui.post { pazarDurdur() }
                } else {
                    toast("🏪 Pazar kurulamadı ($pzArdArdaHata/3), tekrar denenecek")
                }
            }
        }
    }

    private val pazarDongu = Runnable {
        try {
            // Ekrani tani ve sablonlari yukle
            val o = olcek ?: Preset.olcekBul(this)
            if (o == null) {
                toast("Oyun ekranı tanınamadı"); ui.post { pazarDurdur() }; return@Runnable
            }
            olcek = o
            pzT = pzSablonYukle(o.s)
            if (pzT == null) {
                toast("Pazar görüntüleri yüklenemedi"); ui.post { pazarDurdur() }; return@Runnable
            }
            // Ilk tur: once kontrol et (pazar zaten aciksa bozma), gerekirse kur
            pazarKontrol()
            while (pazarCalisiyor) {
                pzSonrakiKontrol = SystemClock.uptimeMillis() + Config.pazarDk(this) * 60_000L
                while (pazarCalisiyor && SystemClock.uptimeMillis() < pzSonrakiKontrol) {
                    val kalan = (pzSonrakiKontrol - SystemClock.uptimeMillis()) / 1000
                    pzDurum("🏪 %d:%02d".format(kalan / 60, kalan % 60))
                    pzUyu(1000)
                }
                if (!pazarCalisiyor) break
                // Oyun ekranda degilse bekle
                while (pazarCalisiyor && !oyundaMi()) {
                    pzDurum("🏪 ⏸"); pzUyu(2000)
                }
                if (pazarCalisiyor) pazarKontrol()
            }
        } catch (e: Exception) {
            hataKaydet("pazar", e)
        }
    }

    private fun pazarBaslat() {
        pzArdArdaHata = 0
        if (Config.pazarEsyalar(this).isEmpty()) {
            toast("Önce satılacak eşyaları ekle: ⋯ Bot seç → 🏪 Pazar Bot → ➕ Eşya ekle")
            pazarKarti(); return
        }
        pazarCalisiyor = true
        playBtn?.text = "⏸"
        pzDurum("🏪 Başlıyor")
        pzH.post(pazarDongu)
    }

    private fun pazarDurdur() {
        pazarCalisiyor = false
        pzH.removeCallbacksAndMessages(null)
        playBtn?.text = "▶"
        statusTv?.text = "🏪 Durdu"
    }

    // ---------- Pazar ayarlari ve esya ogretme ----------
    @Volatile private var pzOgretme = false

    private fun pazarKarti() {
        removeOverlay()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xF00E0D08.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }
        box.addView(TextView(this).apply {
            text = "🏪 Pazar Bot ayarları"
            setTextColor(0xFFD2A866.toInt()); textSize = 17f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
        })
        box.addView(TextView(this).apply {
            text = "Pazarı kurar, satışları her ${Config.pazarDk(this@MacroService)} dakikada kontrol edip paraları toplar, " +
                "hepsi satılınca aynı yerde yeniden kurar. Sadece listedeki eşyaları satar."
            setTextColor(0xFFB0B8C4.toInt()); textSize = 12f
            setPadding(0, dp(4), 0, dp(8))
        })
        val esyalar = Config.pazarEsyalar(this)
        if (esyalar.isEmpty()) {
            box.addView(TextView(this).apply {
                text = "Henüz eşya yok. Commands → OPEN MERCHANT'ı aç, sonra ➕ Eşya ekle."
                setTextColor(Color.WHITE); textSize = 13f
                setPadding(0, 0, 0, dp(6))
            })
        }
        esyalar.forEachIndexed { i, e ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val ikon = ImageView(this).apply {
                setImageBitmap(Bitmap.createBitmap(e.px.map { it or (0xFF shl 24) }.toIntArray(), e.w, e.h, Bitmap.Config.ARGB_8888))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            r.addView(ikon, LinearLayout.LayoutParams(dp(34), dp(34)))
            r.addView(TextView(this).apply {
                text = "  " + "%,d".format(e.fiyat).replace(',', '.') + " coin"
                setTextColor(Color.WHITE); textSize = 14f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(menuBtn("✎") { pzFiyatSor(e.fiyat) { yeni -> val l = Config.pazarEsyalar(this); if (i < l.size) { l[i].fiyat = yeni; Config.pazarEsyalarYaz(this, l) }; pazarKarti() } })
            r.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
            r.addView(menuBtn("🗑") { val l = Config.pazarEsyalar(this); if (i < l.size) l.removeAt(i); Config.pazarEsyalarYaz(this, l); pazarKarti() })
            box.addView(r)
            box.addView(View(this), LinearLayout.LayoutParams(1, dp(4)))
        }
        box.addView(menuBtn("➕ Eşya ekle (OPEN MERCHANT açıkken)") { pzEsyaOgret() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(View(this), LinearLayout.LayoutParams(1, dp(6)))
        val dr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        dr.addView(TextView(this).apply { text = "⏱ Kontrol aralığı"; setTextColor(Color.WHITE); textSize = 14f },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        dr.addView(menuBtn("−") { Config.pazarDkYaz(this, (Config.pazarDk(this) - 5).coerceIn(5, 240)); pazarKarti() })
        dr.addView(TextView(this).apply {
            text = "${Config.pazarDk(this@MacroService)} dk"; setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(70), LinearLayout.LayoutParams.WRAP_CONTENT))
        dr.addView(menuBtn("+") { Config.pazarDkYaz(this, (Config.pazarDk(this) + 5).coerceIn(5, 240)); pazarKarti() })
        box.addView(dr)
        box.addView(View(this), LinearLayout.LayoutParams(1, dp(10)))
        val ar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ar.addView(btn("▶ BAŞLAT") { removeOverlay(); startMacro() }.apply { background = koButon(1) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        ar.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        ar.addView(btn("Kapat") { removeOverlay() }.apply { background = koButon() },
            LinearLayout.LayoutParams(dp(110), LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(ar)
        ortadaGoster(box, dp(430))
    }

    /** Create a Merchant acikken envanterdeki bir esyaya dokunarak listeye ekle */
    @SuppressLint("ClickableViewAccessibility")
    private fun pzEsyaOgret() {
        removeOverlay()
        if (!ScreenSampler.running) {
            toast("Ekran okuma kapalı: önce ▶ ile bir kere başlatıp durdur"); return
        }
        pzOgretme = true
        pzH.post {
            try {
                val o = olcek ?: Preset.olcekBul(this)
                if (o == null) { toast("Oyun ekranı tanınamadı"); return@post }
                olcek = o
                if (pzT == null) pzT = pzSablonYukle(o.s)
                val t = pzT ?: return@post
                val cr = pzBekle(t.create, 800)
                if (cr == null) {
                    toast("Önce Commands → OPEN MERCHANT'ı aç, eşyalar görünürken ➕'ya bas"); return@post
                }
                ui.post { pzDokunusYakala(cr) }
            } finally {
                pzOgretme = false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun pzDokunusYakala(cr: FloatArray) {
        removeOverlay()
        val v = FrameLayout(this).apply { setBackgroundColor(0x33000000); isClickable = true }
        v.addView(TextView(this).apply {
            text = "Satmak istediğin eşyaya dokun"
            setTextColor(Color.WHITE); textSize = 14f
            background = rounded(0xE6000000.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }, FrameLayout.LayoutParams(dp(250), FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = dp(24) })
        v.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                val tx = e.rawX
                val ty = e.rawY
                removeOverlay()
                val s = pzS()
                // En yakin envanter kutusu
                var en = Float.MAX_VALUE
                var bx = 0f
                var by = 0f
                for (r in 0 until 4) for (c in 0 until 7) {
                    val cx = cr[0] + (-144 + 100 * c) * s
                    val cy = cr[1] + (395 + 100.67f * r) * s
                    val d = (cx - tx) * (cx - tx) + (cy - ty) * (cy - ty)
                    if (d < en) { en = d; bx = cx; by = cy }
                }
                ui.postDelayed({
                    val k = pzIkon(bx, by)
                    if (k == null) {
                        toast("İkon okunamadı, tekrar dene")
                    } else {
                        pzFiyatSor(0L) { fiyat ->
                            val l = Config.pazarEsyalar(this)
                            l.add(PazarEsya(k.first, k.second, k.third, fiyat))
                            Config.pazarEsyalarYaz(this, l)
                            toast("🏪 Eklendi: " + "%,d".format(fiyat).replace(',', '.') + " coin")
                            pazarKarti()
                        }
                    }
                }, 400)
            }
            true
        }
        overlay = v
        try {
            wm.addView(v, lp(screenW, screenH).apply { x = 0; y = 0 })
        } catch (e: Exception) {
            overlay = null
        }
    }

    /** Fiyat girisi: klavye acilabilen pencere */
    private fun pzFiyatSor(baslangic: Long, sonra: (Long) -> Unit) {
        removeOverlay()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xF00E0D08.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }
        box.addView(TextView(this).apply {
            text = "💰 Bu eşyanın fiyatı (adet başı)"
            setTextColor(0xFFD2A866.toInt()); textSize = 15f
            setPadding(0, 0, 0, dp(6))
        })
        val et = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE); textSize = 20f
            hint = "ör. 9599866"
            setHintTextColor(0xFF777777.toInt())
            if (baslangic > 0) setText(baslangic.toString())
        }
        box.addView(et, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val ar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        ar.addView(btn("✓ Kaydet") {
            val f = et.text.toString().filter { it.isDigit() }.toLongOrNull() ?: 0L
            if (f <= 0L) { toast("Fiyat gir"); return@btn }
            removeOverlay(); sonra(f)
        }.apply { background = koButon(1) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        ar.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        ar.addView(btn("İptal") { removeOverlay(); pazarKarti() }.apply { background = koButon(2) },
            LinearLayout.LayoutParams(dp(100), LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(ar)
        // Klavye acilabilsin diye odaklanabilir pencere
        val p = WindowManager.LayoutParams(
            dp(380), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(20)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        overlay = box
        try {
            wm.addView(box, p)
            et.requestFocus()
        } catch (e: Exception) {
            overlay = null
        }
    }

    // ================= Ekran kaydi uyumlu yedek =================

    /**
     * Sistem ekran paylasimini kesti. Eski kod botu durduruyordu; HyperOS'ta video
     * izni de bunu tetiklerdi. Simdi erisilebilirlik ekran goruntusune gecer,
     * makro durmaz (video durur cunku projeksiyon gitti).
     */
    fun projeksiyonKesildi() {
        ui.post {
            if (VideoKaydedici.kayitta) {
                try { VideoKaydedici.durdur(this) } catch (_: Exception) {}
            }
            if (running || pazarCalisiyor) {
                ekranYedekBaslat()
                toast("📷 Ekran paylaşımı kesildi — yedek okuma açıldı, makro devam")
            }
        }
    }

    fun ekranYedekBaslat() {
        if (Build.VERSION.SDK_INT < 30) return
        if (ssMod) return
        ssMod = true
        ScreenSampler.running = true
        ui.post { ssAl() }
    }

    fun ekranYedekDurdur() {
        ssMod = false
    }

    private fun ssAl() {
        if (!ssMod) return
        if (Build.VERSION.SDK_INT < 30) return
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, ssExec, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val hw = screenshot.hardwareBuffer
                        val raw = Bitmap.wrapHardwareBuffer(hw, screenshot.colorSpace)
                        val scaled = if (raw != null) {
                            val vw = ((raw.width * ScreenSampler.SCALE).toInt() and 1.inv()).coerceAtLeast(2)
                            val vh = ((raw.height * ScreenSampler.SCALE).toInt() and 1.inv()).coerceAtLeast(2)
                            val s = Bitmap.createScaledBitmap(raw, vw, vh, true)
                            if (s !== raw) raw.recycle()
                            s
                        } else null
                        try { hw.close() } catch (_: Exception) {}
                        if (scaled != null) ScreenSampler.offerBitmap(scaled)
                    } catch (_: Exception) {
                    }
                    if (ssMod) ui.postDelayed({ ssAl() }, 350)
                }

                override fun onFailure(errorCode: Int) {
                    if (ssMod) ui.postDelayed({ ssAl() }, 700)
                }
            })
        } catch (_: Exception) {
            if (ssMod) ui.postDelayed({ ssAl() }, 700)
        }
    }

    private fun showBarChooser() {
        if (running) {
            toast("Önce makroyu durdur"); return
        }
        showMenu(
            "Hangi bar? (bar DOLUYKEN kaydet)",
            listOf(
                "HP barı" to { startRecord(Mod.HP) },
                "MP barı" to { startRecord(Mod.MP) },
                "Hedef mobun can barı" to { startRecord(Mod.HEDEF_BAR) }
            )
        )
    }

    private fun showLootChooser() {
        if (running) {
            toast("Önce makroyu durdur"); return
        }
        showMenu(
            "Buton ekranda GÖRÜNÜRKEN kaydet",
            listOf(
                "'Open' butonu" to { startRecord(Mod.OPEN) },
                "'Collect All' butonu" to { startRecord(Mod.COLLECT) }
            )
        )
    }

    private fun showTypeChooser(x: Int, y: Int) {
        showMenu(
            "Bu tuş ne? ($x, $y)",
            (listOf("hedef", "skill", "hp_pot", "mp_pot", "saldiri") +
                (if (Config.minorVar(this)) listOf("minor") else emptyList())).map { t ->
                Config.label(t) to { savePoint(t, x, y) }
            }
        )
    }

    // ================= Kayit =================

    @SuppressLint("ClickableViewAccessibility")
    private fun startRecord(mode: Mod) {
        if (running) {
            toast("Önce makroyu durdur"); return
        }
        if (mode != Mod.BUTON && !ScreenSampler.running) {
            toast("Önce uygulamadan 'Ekran okumayı başlat'a bas"); return
        }
        removeOverlay()
        updateScreenSize()

        // Tam ekran, boyutu elle verilen kayit katmani (MATCH_PARENT bazi cihazlarda sorun cikariyor)
        val v = FrameLayout(this).apply {
            setBackgroundColor(0x44000000)
            isClickable = true
        }
        val info = TextView(this).apply {
            text = when (mode) {
                Mod.BUTON -> "Kaydedilecek tuşa dokun"
                Mod.HP -> "HP barı DOLUYKEN, pot basılacak seviyeye dokun"
                Mod.MP -> "MP barı DOLUYKEN, pot basılacak seviyeye dokun"
                Mod.HEDEF_BAR -> "Bir mob seçiliyken, onun can barının SOL ucuna yakın kırmızı kısma dokun"
                Mod.OPEN -> "'Open' butonunun SOL ÜST köşesine dokun (biraz içinden)"
                Mod.COLLECT -> "'Collect All' butonunun SOL ÜST köşesine dokun (biraz içinden)"
            }
            setTextColor(Color.WHITE)
            textSize = 14f
            background = rounded(0xDD000000.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val cancel = btn("İptal") { removeOverlay() }.apply { background = rounded(0xCC8B0000.toInt()) }
        // Talimat kutusu: sabit genislik, ekranin sol ortasinda (oyun tuslarini kapatmaz)
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        top.addView(info, LinearLayout.LayoutParams(dp(240), LinearLayout.LayoutParams.WRAP_CONTENT))
        top.addView(View(this), LinearLayout.LayoutParams(1, dp(6)))
        top.addView(cancel, LinearLayout.LayoutParams(dp(120), LinearLayout.LayoutParams.WRAP_CONTENT))
        v.addView(
            top,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL or Gravity.START
            ).apply { leftMargin = dp(16) }
        )

        val iki = mode == Mod.OPEN || mode == Mod.COLLECT
        var fx = -1
        var fy = -1
        v.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                val x = e.rawX.toInt()
                val y = e.rawY.toInt()
                if (iki && fx < 0) {
                    fx = x
                    fy = y
                    info.text = "Şimdi aynı butonun SAĞ ALT köşesine dokun (biraz içinden)"
                } else {
                    removeOverlay()
                    when {
                        iki -> onTemplate(mode, fx, fy, x, y)
                        mode == Mod.BUTON -> showTypeChooser(x, y)
                        else -> onColorPoint(mode, x, y)
                    }
                }
            }
            true
        }

        overlay = v
        try {
            wm.addView(v, lp(screenW, screenH).apply { x = 0; y = 0 })
        } catch (e: Exception) {
            overlay = null
            toast("Kayıt ekranı açılamadı")
        }
    }

    private fun onColorPoint(mode: Mod, x: Int, y: Int) {
        // Kayit ekrani kapandiktan sonra temiz kareyi bekle
        ui.postDelayed({
            val c = ScreenSampler.readPixel(x, y)
            if (c < 0) {
                toast("Renk okunamadı, tekrar dene")
            } else {
                val cf = Config.load(this)
                cf.otoArayuz = false   // elle kayit: otomatik tanima kapanir
                val rn = RenkNokta(x, y, c)
                val ad = when (mode) {
                    Mod.HP -> { cf.hp = rn; "HP" }
                    Mod.MP -> { cf.mp = rn; "MP" }
                    else -> { cf.tgtBar = rn; "Hedef barı" }
                }
                cf.save(this)
                toast("$ad kaydedildi (%d,%d) #%06X".format(x, y, c))
            }
        }, 500)
    }

    private fun onTemplate(mode: Mod, x1: Int, y1: Int, x2: Int, y2: Int) {
        ui.postDelayed({
            val f = ScreenSampler.grab()
            val t = if (f == null) null else ScreenSampler.crop(f, x1, y1, x2, y2)
            if (t == null) {
                toast("Kaydedilemedi. Butonun köşelerine daha geniş dokun")
            } else {
                val cf = Config.load(this)
                cf.otoArayuz = false   // elle kayit: otomatik tanima kapanir
                if (mode == Mod.OPEN) {
                    cf.openT = t
                } else {
                    cf.collectT = t
                    cf.collectT2 = null
                }
                cf.save(this)
                toast(if (mode == Mod.OPEN) "Open kaydedildi ✓" else "Collect All kaydedildi ✓")
            }
        }, 500)
    }

    private fun savePoint(type: String, x: Int, y: Int) {
        val cf = Config.load(this)
        if (type == "skill") {
            val n = cf.points.count { it.type == "skill" } + 1
            cf.points.add(Nokta("Skill $n", "skill", x, y, 10f))
            toast("Skill $n kaydedildi. Cooldown'u uygulamadan ayarla")
        } else {
            cf.points.removeAll { it.type == type }
            cf.points.add(Nokta(Config.label(type), type, x, y))
            toast("${Config.label(type)} kaydedildi")
        }
        cf.save(this)
    }

    // ================= Motor =================

    /** Uygulamadaki buyuk BASLAT butonu: oyun acildiktan sonra baslat */
    fun startFromApp(delayMs: Long) {
        ui.postDelayed({
            if (!running && !pendingStart) startMacro()
        }, delayMs)
    }

    fun isRunning() = running

    private fun modYazi() = when {
        Config.pazarMi(this) -> "🏪 Pazar Bot ▾"
        Config.pkMi(this) -> "⚔ PK Bot • ${Config.sinifAd(this)} ▾"
        else -> "🌾 Farm Bot ▾"
    }

    /** Oyun ici ana menu: Farm Bot / PK Bot / Pazar Bot */
    private fun modMenu() {
        val pk = Config.pkMi(this)
        showMenu("Bot seç", listOf(
            (if (!pk) "✓ " else "") + "🌾 Farm Bot" to {
                modSec(false)
                ui.postDelayed({ ayarKarti() }, 250)
            },
            (if (pk) "✓ " else "") + "⚔ PK Bot" to { pkKarakterMenu() },
            (if (Config.pazarMi(this)) "✓ " else "") + "🏪 Pazar Bot" to {
                if (running) stopMacro()
                Config.pazarSec(this)
                modBtn?.text = modYazi()
                statusTv?.text = "🏪 Pazar hazır"
                pazarKarti()
            }
        ))
    }

    /** PK Bot: once karakter, sonra o karakterin ayarlari */
    private fun pkKarakterMenu() {
        val pk = Config.pkMi(this)
        val sn = Config.sinif(this)
        val liste = ArrayList<Pair<String, () -> Unit>>()
        for ((kod, ad) in Config.SINIFLAR) {
            liste.add((if (pk && sn == kod) "✓ " else "") + ad to {
                modSec(true, kod)
                ui.postDelayed({ ayarKarti() }, 250)
            })
        }
        showMenu("⚔ PK Bot • karakter seç", liste)
    }

    /** Modu secer; her modun kendi tus duzeni ve ayarlari yuklenir (uygulamadan da cagrilir) */
    fun modSec(pk: Boolean, sinif: String? = null) {
        ui.post {
            if (!Config.pazarMi(this) && Config.pkMi(this) == pk && (!pk || sinif == null || sinif == Config.sinif(this))) {
                return@post
            }
            if (running) stopMacro()
            if (pazarCalisiyor) pazarDurdur()
            Config.modDegistir(this, pk, sinif)
            cfg = Config.load(this)
            modBtn?.text = modYazi()
            statusTv?.text = if (pk) "PK hazır" else "Farm hazır"
            toast(if (pk) "⚔ PK Bot • ${Config.sinifAd(this)}" else "🌾 Farm Bot")
        }
    }

    private fun toggle() {
        when {
            pazarCalisiyor -> pazarDurdur()
            running -> stopMacro()
            pendingStart -> {
                pendingStart = false
                statusTv?.text = "Hazır"
            }
            else -> startMacro()
        }
    }

    /** Ekran izni yoksa izni iste, verilince makroyu kendiliginden baslat */
    private fun requestCaptureThenStart() {
        pendingStart = true
        statusTv?.text = "İzin..."
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_AUTO, true)
            )
        } catch (e: Exception) {
            pendingStart = false
            statusTv?.text = "Hazır"
            toast("Uygulamayı açıp 'Ekran okumayı başlat'a bas")
            return
        }
        val deadline = SystemClock.uptimeMillis() + 30_000
        ui.postDelayed(object : Runnable {
            override fun run() {
                if (!pendingStart) return
                if (ScreenSampler.running) {
                    // Oyuna donulmesini ve ilk karelerin gelmesini bekle
                    ui.postDelayed({
                        if (pendingStart) {
                            pendingStart = false
                            startMacro()
                        }
                    }, 1500)
                    return
                }
                if (SystemClock.uptimeMillis() > deadline) {
                    pendingStart = false
                    statusTv?.text = "Hazır"
                    toast("Ekran izni verilmedi, makro başlamadı")
                    return
                }
                ui.postDelayed(this, 400)
            }
        }, 400)
    }

    private val statusTick = object : Runnable {
        override fun run() {
            if (!running) return
            val left = ((endAt - SystemClock.uptimeMillis()) / 1000).coerceAtLeast(0)
            // Uyelik: 10 dakikada bir sunucudan tekrar kontrol
            val simdi = SystemClock.uptimeMillis()
            if (!Lisans.gecerliSimdi()) {
                stopMacro("Üyelik doğrulanamadı ya da süresi doldu. Makro durdu")
                return
            }
            if (!lisansKontrolde && simdi - sonLisansKontrol > 10 * 60 * 1000L) {
                sonLisansKontrol = simdi
                lisansKontrolde = true
                Lisans.arkaPlanKontrol(this@MacroService) { r ->
                    lisansKontrolde = false
                    // Internet gecici koptuysa hemen durdurma; sadece sunucu "hayir" derse dur
                    if (!r.ok && !r.ag && running) stopMacro("Üyelik: ${r.mesaj}. Makro durdu")
                }
            }
            val ne = when {
                bekleNeden.isNotEmpty() -> bekleNeden
                otoMod && olcek == null -> "🔍"
                simdi < kilitBekleUntil || simdi < alanBekleUntil || simdi < menzilBekleUntil -> "💤"
                lootPhase != Loot.BOS -> "📦"
                else -> "⚔"
            }
            statusTv?.text = (if (pkAktif) "PK " else "") + "$ne %d:%02d".format(left / 60, left % 60) +
                "  🗡$kesilen 📦$toplanan 🧪$basilanPot"
            ui.postDelayed(this, 1000)
        }
    }

    private fun openApp() {
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
        }
    }

    private fun startMacro() {
        removeOverlay()
        closeEditor()
        if (!oyundaMi()) {
            toast("Önce oyunu (MykoMobile) aç, sonra ▶")
            return
        }
        // 🏪 Pazar Bot: ekran okuma gerekli, sonra pazar dongusu
        if (Config.pazarMi(this)) {
            if (!ScreenSampler.running) {
                requestCaptureThenStart(); return
            }
            pazarBaslat(); return
        }
        if (Lisans.guncelleGerekli) {
            guncelleKarti()
            return
        }
        // Uyelik kontrolu
        if (!Lisans.gecerliSimdi()) {
            if (lisansKontrolde) return
            lisansKontrolde = true
            statusTv?.text = "Lisans..."
            Lisans.arkaPlanKontrol(this) { r ->
                lisansKontrolde = false
                if (r.ok) {
                    startMacro()
                } else {
                    statusTv?.text = "Giriş yok"
                    toast(r.mesaj)
                    openApp()
                }
            }
            return
        }
        cfg = Config.load(this)
        // Hic ayar yoksa MykoMobile hazir ayarini otomatik yukle
        if (cfg.points.isEmpty()) {
            Preset.apply(this)
            cfg = Config.load(this)
            toast("MykoMobile ayarları otomatik yüklendi")
        }
        val wantsScreen = cfg.hp != null || cfg.mp != null || cfg.tgtBar != null ||
            cfg.openT != null || cfg.collectT != null
        if (wantsScreen && !ScreenSampler.running) {
            requestCaptureThenStart()
            return
        }
        pkAktif = Config.pkMi(this)
        alanBitti = false
        alanArdArda = 0
        alanBekleUntil = 0L
        menzilBekleUntil = 0L
        menzilArdArda = 0
        pkSira = 0
        minorAcik = false
        minorSon = 0L
        if (pkAktif && cfg.points.none { it.type == "saldiri" }) {
            toast("PK için ⚔ Kılıç tuşunu ata: ⋯ → 🛠 Tuşları düzenle"); return
        }
        if (!pkAktif && cfg.points.none { it.type == "hedef" || (it.type == "skill" && it.on) }) {
            toast("Önce + ile saldırı / mob seç / skill tuşu kaydet"); return
        }
        val needScreen = cfg.hp != null || cfg.mp != null || cfg.tgtBar != null ||
            cfg.openT != null || cfg.collectT != null
        if (needScreen && !ScreenSampler.running) {
            toast("Uyarı: ekran okuma kapalı. HP/MP, hedef barı ve kutu çalışmayacak")
        }
        updateScreenSize()
        tgtStrip = Preset.tgtStrip(this, cfg.tgtBar)
        otoMod = cfg.otoArayuz
        olcek = null
        sonTarama = 0L
        dcSay = 0
        sonDcKontrol = 0L
        hpBosSince = 0L
        oncekiCanli = false
        sonYuzde = 1f
        ilerlemeAt = 0L
        iptalBekliyor = false
        kesilen = 0
        toplanan = 0
        basilanPot = 0
        kilitKotu = 0
        kilitUyarildi = false
        kilitBekleUntil = 0L
        hpBar = IntArray(3)
        mpBar = IntArray(3)
        dcT = if (cfg.otoArayuz) null
        else Preset.loadTemplateF(this, "disconnect.png", Preset.landscapeSize(this).first / 2712f, 20, 0.5f, 0.5f)
        hpSol = intArrayOf(-1, -1)
        taramaHata = 0
        h.removeCallbacksAndMessages(null)
        h.post {
            val now = SystemClock.uptimeMillis()
            endAt = now + cfg.minutes * 60_000L
            nextTarget = 0L
            lastHpPot = 0L
            lastMpPot = 0L
            hpLowSince = 0L
            skillReady.clear()
            skillLast.clear()
            nextLootScan = 0L
            lootPhase = Loot.BOS
            phaseUntil = 0L
            lootPauseUntil = 0L
            collectStreak = 0
            lastOpenAt = 0L
            collectedSinceOpen = true
            doluKutuAt = 0L
        }
        running = true
        oyunPaketi = sonPaket   // su an ondeki uygulama = oyun
        bekleNeden = ""

        sonLisansKontrol = SystemClock.uptimeMillis()
        playBtn?.text = "⏸"
        stepPlanla( 700)
        h.postDelayed(kutuGozcu, 900)
        ui.removeCallbacks(statusTick)
        ui.post(statusTick)
    }

    private fun stopMacro(reason: String? = null) {
        // Minor acik kaldiysa kapat (mana akip gitmesin)
        if (minorAcik) {
            minorAcik = false
            cfg.points.firstOrNull { it.type == "minor" }?.let { m ->
                try {
                    val p = Path().apply { moveTo(m.x.toFloat(), m.y.toFloat()); lineTo(m.x + 1f, m.y + 1f) }
                    dispatchGesture(GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null)
                } catch (e: Exception) {
                }
            }
        }
        running = false
        h.removeCallbacksAndMessages(null)
        ui.removeCallbacks(statusTick)
        ui.post {
            playBtn?.text = "▶"
            statusTv?.text = "Durdu"
        }
        if (reason != null) {
            toast(reason)
            vibrate()
        }
    }

    /** worker thread'inde calisir; her adimda tek dokunus, bitince sonraki planlanir */
    /** Adimi planla; ayni anda tek adim bekler (kutu araya girince cift dongu olusmasin) */
    private fun stepPlanla(ms: Long) {
        h.removeCallbacks(stepR)
        h.postDelayed(stepR, ms)
    }

    private fun step() {
        if (!running) return
        try {
            stepIc()
        } catch (e: Exception) {
            // Makro olmesin: hatayi kaydet, 1 sn sonra devam et
            hataKaydet("adım", e)
            tapping = false
            if (running) stepPlanla( 1000)
        }
    }

    private fun stepIc() {
        if (!running) return
        // Oyun arkadaysa / ekran kapaliysa / goruntu yoksa DOKUNMA
        val engel = dokunmaEngeli(SystemClock.uptimeMillis())
        if (engel != null) {
            bekleNeden = engel
            stepPlanla( 500)
            return
        }
        bekleNeden = ""
        val now = SystemClock.uptimeMillis()
        if (now >= endAt) {
            stopMacro("Süre bitti, makro durdu"); return
        }
        // Otomatik ekran tanima: once olcegi bul, sonra her seyi ona gore yerlestir
        if (otoMod && olcek == null) {
            if (now - sonTarama >= 1500) {
                sonTarama = now
                val o = try {
                    Preset.olcekBul(this)
                } catch (e: Exception) {
                    null
                }
                if (o != null) {
                    olcekUygula(o)
                } else if (++taramaHata >= 3) {
                    // Yedek olcek SADECE HP bari o olcekte gercekten gorunuyorsa kullanilir.
                    // Gorunmuyorsa yanlis yerlere dokunmamak icin bekle ve tekrar tara.
                    val yo = Preset.varsayilanOlcek(this)
                    if (yedekOlcekMakul(yo)) {
                        olcekUygula(yo, yedek = true)
                    } else {
                        bekleNeden = "🔍"
                        if (taramaHata % 10 == 3) {
                            toast("Ekran tanınamadı. Oyun ekranda ve HP barı görünüyor mu? ⋯ → 🧪 Ekran testi")
                        }
                        stepPlanla(1500)
                        return
                    }
                } else if (cfg.tuslarOto) {
                    // Hazir tuslar bu ekranda yanlis olabilir: oyun gorunene kadar dokunma
                    stepPlanla( 1500)
                    return
                }
            } else if (cfg.tuslarOto) {
                stepPlanla( 300)
                return
            }
        }
        if (korumaKontrol(now)) return
        if (safetyStop(now)) return

        // Pot, kutu (Open/Collect) ve skill/saldiri BIRLIKTE, ayni anda basilir (iki el gibi):
        // biri digerini bekletmez, digeri de onu bekletmez.
        val r = cfg.radius.toFloat()
        val pots = (potActions(now) + listOfNotNull(minorAction(now)))
            .map { Hedef(it.x.toFloat(), it.y.toFloat(), r) }
        val hedefler = pickTargets(now)
        val list = pots + hedefler
        if (list.isEmpty()) {
            // Kutu toplarken cok sik kontrol et, normalde biraz bekle
            stepPlanla( if (lootPhase != Loot.BOS) 60L else 200L)
            return
        }
        tapping = true
        tapMulti(list) {
            tapping = false
            val hizli = hedefler.any { it.fast } || lootPhase != Loot.BOS
            if (running) stepPlanla( if (hizli) rand(40, 90) else nextDelay())
        }
    }

    /**
     * Kutu gozcusu: saldiridan bagimsiz, 0.12 sn'de bir ekrana bakar. Open/Collect All
     * gorunce bekleyen saldiri adimini iptal edip hemen basar.
     */
    private val kutuGozcu = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                gozcuIc()
            } catch (e: Exception) {
                hataKaydet("kutu", e)
            }
            h.postDelayed(this, 120)
        }

        private fun gozcuIc() {
            // Kutu skilden oncelikli. step() zaten ayni anda basiyor; bu gozcu sadece
            // step() bir sonraki adimi beklerken (uzun bekleme sirasinda) devreye girer.
            if (!tapping && (!otoMod || olcek != null) &&
                dokunmaEngeli(SystemClock.uptimeMillis()) == null
            ) {
                val now = SystemClock.uptimeMillis()
                val t = lootAction(now)
                if (t != null) {
                    h.removeCallbacks(stepR)
                    tapping = true
                    tap(t.x, t.y, t.r) {
                        tapping = false
                        if (running) stepPlanla( rand(40, 90))
                    }
                }
            }
        }
    }

    private fun isLow(cp: RenkNokta): Boolean {
        val c = ScreenSampler.readPixel(cp.x, cp.y)
        if (c < 0) return false
        if (otoMod && olcek != null) {
            // Barin ne kadari dolu? (ortadaki "565/692" yazisindan etkilenmez)
            return if (cp === cfg.mp) barDoluluk(mpBar, false) < cfg.mpYuzde / 100f
            else barDoluluk(hpBar, true) < cfg.hpYuzde / 100f
        }
        return ScreenSampler.diff(c, cp.color) > cfg.tol
    }

    /** Disconnect penceresi ve olum kontrolu. Durdurduysa true */
    private fun korumaKontrol(now: Long): Boolean {
        if (!ScreenSampler.running) return false
        // Disconnect: ~1.5 sn'de bir, ust uste 2 kez gorulurse dur
        val d = dcT
        if (d != null && now - sonDcKontrol > 1500) {
            sonDcKontrol = now
            val f = ScreenSampler.grab()
            if (f != null && findT(f, d) != null) {
                dcSay++
                if (dcSay >= 2) {
                    stopMacro("Bağlantı koptu (Disconnect). Makro durdu")
                    return true
                }
            } else {
                dcSay = 0
            }
        }
        // Olum: HP barinin en solu bile bossa can sifirdir
        if (hpSol[0] >= 0) {
            val c = ScreenSampler.readPixel(hpSol[0], hpSol[1])
            if (c >= 0 && !isRed(c)) {
                if (hpBosSince == 0L) hpBosSince = now
                else if (now - hpBosSince > 3000) {
                    stopMacro("Karakter ölmüş görünüyor. Makro durdu")
                    return true
                }
            } else {
                hpBosSince = 0L
            }
        }
        return false
    }

    /** HP uzun sure dusuk kaldiysa (pot bitti / oldun) durdur */
    private fun safetyStop(now: Long): Boolean {
        val hp = cfg.hp ?: return false
        if (cfg.hpStop <= 0 || !ScreenSampler.running) return false
        if (isLow(hp)) {
            if (hpLowSince == 0L) hpLowSince = now
            else if (now - hpLowSince > cfg.hpStop * 1000L) {
                stopMacro("HP ${cfg.hpStop} sn boyunca düşük kaldı: pot bitmiş ya da ölmüş olabilirsin. Makro durdu")
                return true
            }
        } else {
            hpLowSince = 0L
        }
        return false
    }

    /** Ayni adimda hem kutu hem skill/saldiri gerekiyorsa ikisi de doner (birlikte, ayni anda basilir) */
    private fun pickTargets(now: Long): List<Hedef> {
        val r = cfg.radius.toFloat()
        val out = ArrayList<Hedef>(2)
        lootAction(now)?.let { out.add(it) }
        // Kutu varken de skill/saldiri secimi devam eder: hicbiri digerini beklemez
        pick(now)?.let { out.add(Hedef(it.x.toFloat(), it.y.toFloat(), r, fast = out.isNotEmpty())) }
        return out
    }

    /** Gereken potlar (HP, MP ya da ikisi) - beklemeden, saldiriyla birlikte basilir */
    private fun potActions(now: Long): List<Nokta> {
        val out = ArrayList<Nokta>(2)
        val hp = cfg.hp
        val potBekle = if (pkAktif) 450 else cfg.potCd
        if (hp != null && now - lastHpPot > potBekle && isLow(hp)) {
            cfg.points.firstOrNull { it.type == "hp_pot" }?.let {
                basilanPot++
                lastHpPot = now
                out.add(it)
            }
        }
        val mp = cfg.mp
        if (mp != null && now - lastMpPot > potBekle && isLow(mp)) {
            cfg.points.firstOrNull { it.type == "mp_pot" }?.let {
                basilanPot++
                lastMpPot = now
                out.add(it)
            }
        }
        return out
    }

    /** Bulunan olcege gore barlari, hedef barini, kutulari ve tuslari yerlestir (sadece bellekte) */
    /** Yedek olcekte HP barinin sol kismi kirmizi mi? (barin gercekten orada oldugunu dogrular) */
    private fun yedekOlcekMakul(o: Preset.Olcek): Boolean {
        if (!ScreenSampler.hasFrame()) return false
        val y = Preset.solUst(o, 0, 26)[1]
        for (xr in intArrayOf(75, 100, 125)) {
            val c = ScreenSampler.readPixel(Preset.solUst(o, xr, 26)[0], y)
            if (c >= 0 && isRed(c)) return true
        }
        return false
    }

    private fun olcekUygula(o: Preset.Olcek, yedek: Boolean = false) {
        olcek = o
        collectAnkorHazirla(o)
        openBandHazirla(o)
        val hp = Preset.solUst(o, 315, 26)
        val mp = Preset.solUst(o, 150, 68)
        val tb = Preset.ustOrta(o, 1262, 60)
        cfg.hp = RenkNokta(hp[0], hp[1], 0)
        cfg.mp = RenkNokta(mp[0], mp[1], 0)
        cfg.tgtBar = RenkNokta(tb[0], tb[1], 0)
        val a = Preset.ustOrta(o, 1195, 66)
        val b = Preset.ustOrta(o, 1560, 66)
        tgtStrip = intArrayOf(a[0], b[0], a[1])
        Preset.loadTemplateF(this, "open.png", o.s, 20, 0.5f, 0.5f)?.let { cfg.openT = it }
        Preset.loadTemplateF(this, "collect.png", o.s, 25, 0.5f, 0.22f)?.let { cfg.collectT = it }
        Preset.loadTemplateF(this, "collect_btn.png", o.s, 19, 0.5f, 0.5f)?.let { cfg.collectT2 = it }
        dcT = Preset.loadTemplateF(this, "disconnect.png", o.s, 20, 0.5f, 0.5f)
        hpSol = Preset.solUst(o, 72, 26)
        val h1 = Preset.solUst(o, 62, 26)
        val h2 = Preset.solUst(o, 405, 26)
        hpBar = intArrayOf(h1[0], h2[0], h1[1])
        val m1 = Preset.solUst(o, 62, 70)
        val m2 = Preset.solUst(o, 407, 70)
        mpBar = intArrayOf(m1[0], m2[0], m1[1])
        isimRect = isimAlani(o)
        if (cfg.tuslarOto) {
            val yeni = Preset.otoTuslar(o, cfg.points)
            cfg.points.clear()
            cfg.points.addAll(yeni)
            skillReady.clear()
            skillLast.clear()
        }
        if (yedek) {
            toast("Ekran tanınamadı, varsayılan ayarla devam. ⋯ → 🧪 Ekran testi ile kontrol edebilirsin")
        } else {
            toast("Ekran tanındı ✓ (ölçek %.2f)".format(o.s))
        }
    }

    private fun isBlue(c: Int): Boolean {
        val r = (c shr 16) and 0xff
        val g = (c shr 8) and 0xff
        val b = c and 0xff
        return b >= 110 && b > r * 1.6f && b > g * 1.2f
    }

    private fun isRed(c: Int): Boolean {
        val r = (c shr 16) and 0xff
        val g = (c shr 8) and 0xff
        val b = c and 0xff
        return r >= 90 && r > g * 2 && r > b * 2
    }

    /** Barin doluluk orani (0..1): rengin barda ne kadar saga uzandigi */
    private fun barDoluluk(bar: IntArray, kirmizi: Boolean): Float {
        val x1 = bar[0]
        val x2 = bar[1]
        if (x2 <= x1) return 1f
        val n = 40
        var son = -1
        for (k in 0 until n) {
            val c = ScreenSampler.readPixel(x1 + (x2 - x1) * k / (n - 1), bar[2])
            if (c >= 0 && (if (kirmizi) isRed(c) else isBlue(c))) son = k
        }
        return (son + 1).toFloat() / n
    }

    // ---------- Mob kilidi (isim sekli) ----------

    private fun isimAlani(o: Preset.Olcek): IntArray {
        val a = Preset.ustOrta(o, 1100, 0)
        val b = Preset.ustOrta(o, 1640, 44)
        return intArrayOf(a[0], a[1], b[0], b[1])
    }

    private fun renkFark(a: Int, b: Int) = ScreenSampler.diff(a, b)

    /** Bolgedeki isim renginde olan pikseller */
    private fun isimMaske(px: IntArray, renk: Int): BooleanArray =
        BooleanArray(px.size) { renkFark(px[it] and 0xFFFFFF, renk) < 90 }

    /** Hedefin ismi kilitli moblardan biri mi? */
    private fun isimUygun(): Boolean {
        val r = ScreenSampler.bolge(isimRect[0], isimRect[1], isimRect[2], isimRect[3]) ?: return true
        val (w, h, px) = r
        var olcuUyan = false
        for (k in cfg.kilitler) {
            if (k.w != w || k.h != h) continue
            olcuUyan = true
            val m = isimMaske(px, k.renk)
            var best = 0f
            for (dx in -3..3) {
                var kesisim = 0
                var birlesim = 0
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val a = k.bits[y * w + x] == '1'
                        val xx = x - dx
                        val b = xx in 0 until w && m[y * w + xx]
                        if (a && b) kesisim++
                        if (a || b) birlesim++
                    }
                }
                if (birlesim > 0) best = maxOf(best, kesisim.toFloat() / birlesim)
            }
            if (best >= 0.65f) return true
        }
        if (!olcuUyan) {
            if (!kilitUyarildi) {
                kilitUyarildi = true
                toast("Mob kilidi başka bir ekranda yapılmış. ⋯ menüsünden yeniden kilitle")
            }
            return true
        }
        return false
    }

    /** Su an secili mobun ismini kilide ekle */
    private fun mobuKilitle() {
        if (!ScreenSampler.running) {
            toast("Ekran okuma kapalı. Önce ▶ ile bir kere başlat"); return
        }
        toast("Mob ismi okunuyor…")
        h.post {
            val o = olcek ?: try { Preset.olcekBul(this) } catch (e: Exception) { null }
            if (o == null) {
                toast("Oyun ekranı tanınamadı"); return@post
            }
            val a = isimAlani(o)
            val r = ScreenSampler.bolge(a[0], a[1], a[2], a[3])
            if (r == null) {
                toast("Ekran görüntüsü alınamadı"); return@post
            }
            val (w, hh, px) = r
            // Ismin rengi: bolgedeki canli (doygun) piksellerin ortancasi
            val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
            for (c in px) {
                val rr = (c shr 16) and 0xff
                val gg = (c shr 8) and 0xff
                val bb = c and 0xff
                val mx = maxOf(rr, gg, bb)
                val mn = minOf(rr, gg, bb)
                if (mx - mn > 80 && mx > 120) { rs.add(rr); gs.add(gg); bs.add(bb) }
            }
            if (rs.size < 20) {
                toast("İsim bulunamadı. Önce oyunda bir mob seç, ismi üstte görünsün"); return@post
            }
            rs.sort(); gs.sort(); bs.sort()
            val renk = (rs[rs.size / 2] shl 16) or (gs[gs.size / 2] shl 8) or bs[bs.size / 2]
            val m = isimMaske(px, renk)
            val bits = StringBuilder(m.size)
            for (b in m) bits.append(if (b) '1' else '0')
            val c = Config.load(this)
            c.kilitler.add(Kilit(w, hh, renk, bits.toString()))
            c.save(this)
            toast("🎯 Mob kilitlendi. Toplam ${c.kilitler.size} mob, sadece bunlara vurulacak")
        }
    }

    /** Hedefin kalan cani (0..1): barda kirmizinin ne kadar saga uzandigi */
    private fun hedefYuzde(): Float {
        val x1 = tgtStrip[0]
        val x2 = tgtStrip[1]
        val y = tgtStrip[2]
        if (x2 <= x1) return 1f
        val n = 30
        var son = -1
        for (k in 0 until n) {
            val c = ScreenSampler.readPixel(x1 + (x2 - x1) * k / (n - 1), y)
            if (c >= 0 && isRed(c)) son = k
        }
        return (son + 1).toFloat() / n
    }

    /** Hedef barinin herhangi bir yerinde kirmizi var mi? */
    private fun targetAlive(bar: RenkNokta): Boolean {
        val x1 = tgtStrip[0]
        val x2 = tgtStrip[1]
        val y = tgtStrip[2]
        if (x2 > x1) {
            val n = 12
            for (k in 0 until n) {
                val x = x1 + (x2 - x1) * k / (n - 1)
                val c = ScreenSampler.readPixel(x, y)
                if (c >= 0 && isRed(c)) return true
            }
            return false
        }
        return !isLow(bar)
    }

    /**
     * Minor ac-kapa calisir: can esigin altina inince BIR KEZ basip acar (HP dolar, mana yer),
     * can %97'ye gelince BIR KEZ basip kapatir (mana yemeyi birakir). Arada dokunmaz.
     */
    private fun minorAction(now: Long): Nokta? {
        if (pkAktif && Config.sinif(this) != "asas") return null   // Minor sadece Asas'ta
        if (!cfg.minorAktif) return null
        val m = cfg.points.firstOrNull { it.type == "minor" && it.on } ?: return null
        if (olcek == null || hpBar[1] <= hpBar[0]) return null
        // Minor aninda tepki veriyor; sadece ayni ekran karesine iki kez basmamak icin kisa bekleme
        if (now - minorSon < 150) return null
        val hp = barDoluluk(hpBar, true)
        if (!minorAcik && hp < cfg.minorYuzde / 100f) {
            minorAcik = true
            minorSon = now
            return m
        }
        if (minorAcik && hp >= 0.97f) {
            minorAcik = false
            minorSon = now
            return m
        }
        return null
    }

    /**
     * PK: kilicla hedef sec; hedef varken skilleri SENIN SIRANLA bas (1-2-3-...-1).
     * Siradaki kisa sure icinde hazir olacaksa onu bekler (sira bozulmaz), uzun bekleyecekse atlar.
     */
    private fun pkSec(now: Long): Nokta? {
        val pts = cfg.points
        val kilic = pts.firstOrNull { it.type == "saldiri" }
        val bar = cfg.tgtBar
        val alive = bar != null && ScreenSampler.running && targetAlive(bar)
        if (!alive) {
            if (kilic != null && now >= nextTarget) {
                nextTarget = now + rand(300, 450)
                return kilic
            }
            return null
        }
        val skills = pts.indices.filter { pts[it].type == "skill" && pts[it].on }
        if (skills.isEmpty()) return kilic
        for (deneme in skills.indices) {
            val i = skills[pkSira % skills.size]
            val kalan = (skillReady[i] ?: 0L) - now
            if (kalan <= 0) {
                skillReady[i] = now + (pts[i].cd * 1000).toLong() + rand(0, 80)
                pkSira = (pkSira + 1) % skills.size
                return pts[i]
            }
            if (kalan <= 600) return null       // az kaldi: sirayi koru, bekle
            pkSira = (pkSira + 1) % skills.size // uzun bekleyecek: atla
        }
        return null
    }

    private fun pick(now: Long): Nokta? {
        if (pkAktif) return pkSec(now)
        val pts = cfg.points
        val target = pts.firstOrNull { it.type == "hedef" }
        val bar = cfg.tgtBar

        if (target != null && bar != null && ScreenSampler.running) {
            // Akilli mod: hedef barina bak
            val alive = targetAlive(bar)
            if (oncekiCanli && !alive) {
                if (now - iptalAt > 2500) kesilen++   // X ile birakilan mob kesildi sayilmaz
                if (now >= menzilBekleUntil) nextTarget = 0L   // mob oldu: yeni hedefi hemen sec (kutu paralel toplanir)
            }
            if (alive && !oncekiCanli) {
                // Yeni hedef: ilerleme sayacini ve mesafe sayacini baslat
                sonYuzde = hedefYuzde()
                ilerlemeAt = now
                hedefBaslangic = now
                ilkVurus = false
                alanBitti = false
            }
            oncekiCanli = alive
            // Uzak mob birakildiktan sonra bekleme: skill/mob secimi yok (potlar ayri calisir).
            // Hedef hala secili kalmissa X'i tekrar gonder.
            if (now < menzilBekleUntil) {
                if (alive && now - menzilXAt > 900) {
                    menzilXAt = now
                    val ol = olcek
                    if (ol != null) {
                        val x = Preset.sagAlt(ol, 2632, 926)
                        return Nokta("İptal", "iptal", x[0], x[1])
                    }
                }
                return null
            }
            if (alive) {
                // Takili hedef: 12 sn boyunca cani azalmiyorsa birak, yenisini sec
                val y = hedefYuzde()
                val o0 = olcek
                if (y < sonYuzde - 0.02f) {
                    sonYuzde = y
                    ilerlemeAt = now
                    ilkVurus = true
                    menzilArdArda = 0
                } else if (!ilkVurus && cfg.menzilAktif && o0 != null &&
                    now - hedefBaslangic > cfg.menzilSn * 1000L
                ) {
                    // Mesafe siniri: bu surede vurulmaya baslanmadi -> mob uzakta, birak
                    hedefBaslangic = now
                    ilkVurus = true
                    // Ayni uzak moba tekrar kosmamak icin bekle; ust uste olursa bekleme uzar
                    menzilArdArda++
                    val bekleSn = minOf(cfg.menzilBekleSn * menzilArdArda, 20)
                    menzilBekleUntil = now + bekleSn * 1000L
                    menzilXAt = now
                    iptalAt = now
                    nextTarget = menzilBekleUntil
                    val x = Preset.sagAlt(o0, 2632, 926)
                    return Nokta("İptal", "iptal", x[0], x[1])
                } else if (now - ilerlemeAt > 12_000) {
                    ilerlemeAt = now
                    sonYuzde = 1f
                    val ol = olcek
                    if (ol != null) {
                        // Once hedefi iptal et (X), sonraki adimda yeni mob sec
                        val x = Preset.sagAlt(ol, 2632, 926)
                        iptalBekliyor = true
                        iptalAt = now
                        nextTarget = 0L
                        return Nokta("İptal", "iptal", x[0], x[1])
                    }
                    nextTarget = 0L
                    return target
                }
            }
            // Alan siniri: secili mobun halkasi dikdortgenin disindaysa X ile birak.
            // Halka bulunamazsa engelleme (guvenli taraf: normal calismaya devam).
            if (alive && cfg.alanAktif && !alanBitti) {
                val halka = secimHalkasi()
                if (halka != null) {
                    alanBitti = true
                    if (alanIcinde(halka[0], halka[1])) {
                        alanArdArda = 0
                    } else {
                        alanArdArda++
                        if (alanArdArda >= 4) {
                            // Ust uste 4 hedef alan disi: alanda mob yok, biraz bekle
                            alanBekleUntil = now + 4000
                            alanArdArda = 0
                        }
                        nextTarget = now + rand(350, 500)
                        iptalAt = now
                        val ol = olcek
                        if (ol != null) {
                            val x = Preset.sagAlt(ol, 2632, 926)
                            return Nokta("İptal", "iptal", x[0], x[1])
                        }
                    }
                } else if (now - hedefBaslangic > 1800) {
                    alanBitti = true
                }
            }
            // Alan kontrolu bitmeden (en fazla 0,7 sn) skill basma: karakter uzak moba kosmaya baslamasin
            if (alive && cfg.alanAktif && !alanBitti && now - hedefBaslangic < 700) return null
            // Mob kilidi
            val o = olcek
            if (cfg.kilitler.isNotEmpty() && o != null) {
                // Bekleme modu: etrafta kilitli mob yok, skill/saldiri/secim tamamen durur
                if (now < kilitBekleUntil) return null
                if (alive) {
                    if (isimUygun()) {
                        kilitKotu = 0
                    } else {
                        // Yanlis mob: karakter ona yurumesin diye hemen hedefi iptal et (X)
                        kilitKotu++
                        if (kilitKotu >= 6) {
                            // Ust uste 6 yanlis mob: kilitli mob kalmadi, 8 sn bekle
                            kilitBekleUntil = now + 8000
                            kilitKotu = 5   // bekleme sonrasi tek deneme; tutmazsa yine bekle
                        }
                        nextTarget = now + rand(350, 500)
                        iptalAt = now
                        val x = Preset.sagAlt(o, 2632, 926)
                        return Nokta("İptal", "iptal", x[0], x[1])
                    }
                }
            }
            if (!alive) {
                // Alan icinde mob yok: kisa sure bekle (potlar ayri calismaya devam eder)
                if (cfg.alanAktif && now < alanBekleUntil) return null
                // Hedef yok / oldu: sadece yeni mob sec, skill harcama
                if (now >= nextTarget) {
                    nextTarget = now + rand(cfg.tgtFast, cfg.tgtFast + 400)
                    return target
                }
                return null
            }
        } else if (target != null && now >= nextTarget) {
            // Basit mod: belirli araliklarla mob sec
            nextTarget = now + rand(cfg.tgtMin, cfg.tgtMax)
            return target
        }

        // Hazir skiller arasindan en uzun suredir basilmayani sec (sirayla doner).
        // Esitlikte listedeki sira onceliklidir. Bekleme 0 = surekli.
        var best = -1
        var bestLast = Long.MAX_VALUE
        for (i in pts.indices) {
            val p = pts[i]
            if (p.type != "skill" || !p.on) continue
            if (now < (skillReady[i] ?: 0L)) continue
            val last = skillLast[i] ?: 0L
            if (last < bestLast) {
                bestLast = last
                best = i
            }
        }
        if (best >= 0) {
            val p = pts[best]
            skillLast[best] = now
            skillReady[best] = now + if (p.cd > 0f) {
                (p.cd * 1000).toLong() + rand(cfg.skMin, cfg.skMax)
            } else 0L
            return p
        }

        // Capraz kilic (saldiri) tusu kullanilmaz: mob secimi + skiller yeterli
        return null
    }

    // ---------- Kutu ----------

    private fun findT(f: ScreenSampler.Frame, t: Sablon): IntArray? =
        ScreenSampler.find(f, t, if (t.tol > 0) t.tol else cfg.ttol)

    private fun sablonHedef(t: Sablon, pos: IntArray): Hedef {
        val cx = ScreenSampler.toScreen(pos[0] + t.w * t.tx)
        val cy = ScreenSampler.toScreen(pos[1] + t.h * t.ty)
        val r = minOf(
            cfg.radius.toFloat(),
            ScreenSampler.toScreen(t.w.toFloat()) * 0.15f,
            ScreenSampler.toScreen(t.h.toFloat()) * 0.07f
        )
        return Hedef(cx, cy, r)
    }

    private fun scanGap(): Long {
        // Kutu taramasi her zaman seri: en fazla 150 ms
        val b = minOf(cfg.lootEvery, 150).coerceAtLeast(100)
        return rand((b * 0.8).toInt(), (b * 1.2).toInt())
    }

    /**
     * Kutu akisi:
     *  BOS           -> Open gorulurse bas, COLLECT_BEKLE'ye gec
     *  COLLECT_BEKLE -> Collect All cikinca bas, SONRAKI_KUTU'ya gec
     *  SONRAKI_KUTU  -> kisa sure baska Open var mi bak (seri toplama), yoksa saldiriya don
     */
    // Collect All penceresi oyunda EKRANDA SABIT bir yerde acilir (karakterin/kameranin
    // yerine gore degismez). Bunu her karede tum ekranda aramak yerine, sadece o sabit
    // noktanin cevresinde arayarak hem cok hizlanir hem de kutu ile saldiri birbirini
    // geciktirmez. Sabit nokta, referans ekranda (2712x1220) olculmustur: (398, 540).
    private var collectAnkorGX = -1
    private var collectAnkorGY = -1

    private fun collectAnkorHazirla(o: Preset.Olcek) {
        val p = Preset.solUst(o, 398, 540)
        collectAnkorGX = (p[0] * ScreenSampler.SCALE / ScreenSampler.GRID).toInt()
        collectAnkorGY = (p[1] * ScreenSampler.SCALE / ScreenSampler.GRID).toInt()
    }

    // Open (kutu/mob dusunca cikan "Open" yazisi) HUD'a gore degil kameraya/karaktere gore
    // hareket eder, ama olculdugune gore ekranin daima ayni dikey bandinda kalir: ustte
    // can/mana/gorev seridi, altta menu cubugu asilmaz. Sadece bu bantta aramak yeter.
    private var openBandGY1 = -1
    private var openBandGY2 = -1

    private fun openBandHazirla(o: Preset.Olcek) {
        val ust = Preset.solUst(o, 0, 358)
        val alt = Preset.solUst(o, 0, 1114)
        openBandGY1 = (ust[1] * ScreenSampler.SCALE / ScreenSampler.GRID).toInt()
        openBandGY2 = (alt[1] * ScreenSampler.SCALE / ScreenSampler.GRID).toInt()
    }

    /** Open sablonunu sadece dikey bantta arar (tam ekran degil) */
    private fun findOpen(f: ScreenSampler.Frame, op: Sablon): IntArray? {
        if (openBandGY1 < 0) return findT(f, op)
        return ScreenSampler.findBand(f, op, if (op.tol > 0) op.tol else cfg.ttol, openBandGY1, openBandGY2)
    }

    /** Collect All penceresini ara: sabit konumun cevresinde, once tum pencere, olmazsa sadece buton */
    private fun findCollect(f: ScreenSampler.Frame): Pair<Sablon, IntArray>? {
        if (collectAnkorGX < 0) return null
        val m = 60   // izgara birimi (~120 ekran pikseli): kucuk cihaz/olcek sapmalarini tolere eder
        cfg.collectT?.let { t ->
            ScreenSampler.findNear(f, t, if (t.tol > 0) t.tol else cfg.ttol, collectAnkorGX, collectAnkorGY, m)
                ?.let { return t to it }
        }
        cfg.collectT2?.let { t ->
            ScreenSampler.findNear(f, t, if (t.tol > 0) t.tol else cfg.ttol, collectAnkorGX, collectAnkorGY, m)
                ?.let { return t to it }
        }
        return null
    }

    /** Ayni Open'a, kutu toplanmadan tekrar basma */
    private fun openBlocked(now: Long, op: Sablon, pos: IntArray): Boolean {
        // Dolu envanter yuzunden kapatilan kutu: 45 sn ayni yerdeki Open'a basma
        if (now - doluKutuAt < 45_000) {
            val d = sablonHedef(op, pos)
            if (kotlin.math.abs(d.x - doluKutuX) < 80 && kotlin.math.abs(d.y - doluKutuY) < 80) return true
        }
        if (collectedSinceOpen) return false
        if (now - lastOpenAt > 1200) return false   // sadece cift basmayi onle
        val t = sablonHedef(op, pos)
        return kotlin.math.abs(t.x - lastOpenX) < 80 && kotlin.math.abs(t.y - lastOpenY) < 80
    }

    /**
     * Kutu akisi:
     *  BOS           -> Open gorulurse BIR KERE bas, COLLECT_BEKLE'ye gec
     *  COLLECT_BEKLE -> sadece Collect All ara (Open'a basma), cikinca bas
     *  SONRAKI_KUTU  -> kisa sure yeni kutu var mi bak (seri toplama), yoksa saldiriya don
     */
    private fun lootAction(now: Long): Hedef? {
        val op = cfg.openT
        val hasCollect = cfg.collectT != null || cfg.collectT2 != null
        if (op == null && !hasCollect) return null
        if (pkAktif) return null
        if (!cfg.lootOn || !ScreenSampler.running || now < lootPauseUntil) {
            lootPhase = Loot.BOS
            return null
        }
        if (now < nextLootScan) return null
        val f = ScreenSampler.grab() ?: return null

        when (lootPhase) {
            Loot.COLLECT_BEKLE -> {
                findCollect(f)?.let { (t, pos) -> return collectHit(now, t, pos) }
                if (now > phaseUntil) {
                    lootPhase = Loot.BOS
                    nextLootScan = now + scanGap()
                } else {
                    nextLootScan = now + rand(60, 100)
                }
                return null
            }
            Loot.SONRAKI_KUTU -> {
                findCollect(f)?.let { (t, pos) -> return collectHit(now, t, pos) }
                if (op != null) {
                    val pos = findOpen(f, op)
                    if (pos != null && !openBlocked(now, op, pos)) return openHit(now, op, pos)
                }
                if (now > phaseUntil) {
                    lootPhase = Loot.BOS
                    nextLootScan = now + scanGap()
                } else {
                    nextLootScan = now + rand(60, 110)
                }
                return null
            }
            Loot.BOS -> {}
        }

        nextLootScan = now + scanGap()
        // Acik kalmis kutu penceresi varsa once onu topla
        findCollect(f)?.let { (t, pos) -> return collectHit(now, t, pos) }
        if (op != null) {
            val pos = findOpen(f, op)
            if (pos != null && !openBlocked(now, op, pos)) return openHit(now, op, pos)
        }
        return null
    }

    private fun openHit(now: Long, op: Sablon, pos: IntArray): Hedef {
        collectStreak = 0
        lootPhase = Loot.COLLECT_BEKLE
        phaseUntil = now + cfg.collectWait
        nextLootScan = now + rand(100, 150)
        val t = sablonHedef(op, pos)
        lastOpenX = t.x
        lastOpenY = t.y
        lastOpenAt = now
        collectedSinceOpen = false
        return Hedef(t.x, t.y, t.r, fast = true)
    }

    /** Collect All penceresindeki Close butonunun yeri (eslesen sablona gore) */
    private fun closeHedef(co: Sablon, pos: IntArray): Hedef {
        // Tum pencere sablonunda Close alt kisimda (%74), sadece-buton sablonunda butonun altinda
        val ty = if (co === cfg.collectT2) 2.15f else 0.74f
        val cx = ScreenSampler.toScreen(pos[0] + co.w * 0.5f)
        val cy = ScreenSampler.toScreen(pos[1] + co.h * ty)
        return Hedef(cx, cy, minOf(cfg.radius.toFloat(), ScreenSampler.toScreen(co.w.toFloat()) * 0.15f, dp(5).toFloat()), fast = true)
    }

    /**
     * Collect All: envanter doluysa bile ayni esyalar ust uste eklenebildigi icin once
     * iki kez Collect All denenir; pencere yine kapanmazsa Close ile kapatilip devam edilir.
     */
    private fun collectHit(now: Long, co: Sablon, pos: IntArray): Hedef? {
        collectStreak++
        lootPhase = Loot.SONRAKI_KUTU
        phaseUntil = now + 700
        nextLootScan = now + rand(130, 190)
        if (collectStreak >= 3) {
            // 2 kez Collect All'a rağmen acik: envanter dolu, Close ile kapat ve devam et
            doluKutuX = lastOpenX
            doluKutuY = lastOpenY
            doluKutuAt = now
            return closeHedef(co, pos)
        }
        collectedSinceOpen = true
        toplanan++
        val t = sablonHedef(co, pos)
        return Hedef(t.x, t.y, t.r, fast = true)
    }

    // ---------- Zamanlama ve dokunus ----------

    /** Parmagin ekranda kalma suresi: Seri modda kisa */
    private fun basmaSuresi(): Long = if (cfg.maxDelay <= 150) rand(35, 70) else rand(55, 140)

    private fun nextDelay(): Long {
        if (pkAktif) return rand(40, 90)
        val lo = cfg.minDelay.coerceAtLeast(50)
        val hi = cfg.maxDelay.coerceAtLeast(lo + 1)
        // Iki rastgele sayinin ortalamasi: ortaya yakin, dogal dagilim
        var d = (rand(lo, hi) + rand(lo, hi)) / 2
        if (rnd.nextInt(100) < cfg.pauseChance.coerceIn(0, 100)) d += rand(cfg.pauseMin, cfg.pauseMax)
        return d
    }

    private fun stroke(x: Float, y: Float, radius: Float, start: Long): GestureDescription.StrokeDescription {
        val r = radius.coerceAtLeast(0f)
        val maxX = (screenW - 2).toFloat().coerceAtLeast(2f)
        val maxY = (screenH - 2).toFloat().coerceAtLeast(2f)
        val dx = (rnd.nextGaussian() * r / 2.5).toFloat().coerceIn(-r, r)
        val dy = (rnd.nextGaussian() * r / 2.5).toFloat().coerceIn(-r, r)
        val sx = (x + dx).coerceIn(1f, maxX)
        val sy = (y + dy).coerceIn(1f, maxY)
        val ex = (sx + rnd.nextInt(7) - 3).coerceIn(1f, maxX)
        val ey = (sy + rnd.nextInt(7) - 3).coerceIn(1f, maxY)
        val path = Path().apply {
            moveTo(sx, sy)
            lineTo(ex, ey)
        }
        return GestureDescription.StrokeDescription(path, start, basmaSuresi())
    }

    /** Birden fazla noktaya ayni anda (farkli parmaklarla) dokun */
    private fun tapMulti(list: List<Hedef>, done: () -> Unit) {
        if (list.size == 1) {
            tap(list[0].x, list[0].y, list[0].r, done); return
        }
        val g = try {
            val b = GestureDescription.Builder()
            list.forEachIndexed { i, t ->
                // Diger parmaklar cok hafif gecikmeli, insan gibi
                b.addStroke(stroke(t.x, t.y, t.r, if (i == 0) 0L else rand(0, 40)))
            }
            b.build()
        } catch (e: Exception) {
            null
        }
        if (g == null) {
            // Coklu dokunus desteklenmezse sirayla hizlica bas
            tapSeq(list, 0, done)
            return
        }
        val ok = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                done()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                done()
            }
        }, h)
        if (!ok) tapSeq(list, 0, done)
    }

    private fun tapSeq(list: List<Hedef>, i: Int, done: () -> Unit) {
        if (i >= list.size) {
            done(); return
        }
        tap(list[i].x, list[i].y, list[i].r) {
            if (i + 1 >= list.size) done()
            else h.postDelayed({ tapSeq(list, i + 1, done) }, rand(35, 80))
        }
    }

    private fun tap(x: Float, y: Float, radius: Float, done: () -> Unit) {
        val r = radius.coerceAtLeast(0f)
        val maxX = (screenW - 2).toFloat().coerceAtLeast(2f)
        val maxY = (screenH - 2).toFloat().coerceAtLeast(2f)
        val dx = (rnd.nextGaussian() * r / 2.5).toFloat().coerceIn(-r, r)
        val dy = (rnd.nextGaussian() * r / 2.5).toFloat().coerceIn(-r, r)
        val sx = (x + dx).coerceIn(1f, maxX)
        val sy = (y + dy).coerceIn(1f, maxY)
        val ex = (sx + rnd.nextInt(7) - 3).coerceIn(1f, maxX)
        val ey = (sy + rnd.nextInt(7) - 3).coerceIn(1f, maxY)

        val path = Path().apply {
            moveTo(sx, sy)
            lineTo(ex, ey)
        }
        val g = try {
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, basmaSuresi()))
                .build()
        } catch (e: Exception) {
            h.postDelayed({ done() }, 300); return
        }
        val ok = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                done()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                done()
            }
        }, h)
        if (!ok) h.postDelayed({ done() }, 300)
    }
}
