package com.kanka.makro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import kotlin.math.roundToInt

/**
 * MykoMobile icin hazir ayar. Konumlar 2712x1220 ekran goruntulerinden olculdu,
 * farkli cozunurlukte oranla olceklenir. Renkler ve buton sablonlari gercek oyundan alindi.
 */
object Preset {
    private const val BW = 2712f
    private const val BH = 1220f

    @Suppress("DEPRECATION")
    fun landscapeSize(ctx: Context): Pair<Int, Int> {
        val dm = DisplayMetrics()
        (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(dm)
        return maxOf(dm.widthPixels, dm.heightPixels) to minOf(dm.widthPixels, dm.heightPixels)
    }

    /**
     * Hedef can barinin tamami: [x1, x2, y]. Barin herhangi bir yerinde kirmizi varsa
     * hedef yasiyor demektir (can %1'e insede).
     */
    fun tgtStrip(ctx: Context, bar: RenkNokta?): IntArray {
        val (w, h) = landscapeSize(ctx)
        if (bar == null) {
            return intArrayOf(
                (1195 * w / BW).roundToInt(),
                (1560 * w / BW).roundToInt(),
                (66 * h / BH).roundToInt()
            )
        }
        // Kaydedilen noktaya gore (tablet vb. farkli ekranlarda da dogru yere bakar):
        // nokta barin sol tarafinda; bar saga dogru uzanir.
        return intArrayOf(
            (bar.x - 0.025f * w).roundToInt().coerceAtLeast(0),
            (bar.x + 0.11f * w).roundToInt().coerceAtMost(w - 1),
            (bar.y + 6f * h / BH).roundToInt().coerceAtMost(h - 1)
        )
    }

    fun apply(ctx: Context) {
        val (w, h) = landscapeSize(ctx)
        fun x(v: Int) = (v * w / BW).roundToInt()
        fun y(v: Int) = (v * h / BH).roundToInt()

        val c = Config.load(ctx)
        c.points.clear()
        for (r in REF) c.points.add(Nokta(r.ad, r.type, x(r.x), y(r.y), r.cd, r.on))

        c.hp = RenkNokta(x(315), y(26), 0xC72720)      // HP ~%74 altina inince pot
        c.mp = RenkNokta(x(150), y(68), 0x3A4CCB)      // MP ~%25 altina inince pot
        c.tgtBar = RenkNokta(x(1262), y(60), 0xB22D29) // hedef can bari (sol taraf)

        loadTemplate(ctx, "open.png", w, 20, 0.5f, 0.5f)?.let { c.openT = it }
        // Collect All + Close penceresi birlikte; dokunus ust butonun ortasina (Close'a asla degil)
        loadTemplate(ctx, "collect.png", w, 25, 0.5f, 0.22f)?.let { c.collectT = it }
        loadTemplate(ctx, "collect_btn.png", w, 19, 0.5f, 0.5f)?.let { c.collectT2 = it }
        c.lootEvery = 150
        c.collectWait = 2500
        c.otoArayuz = true
        c.tuslarOto = true
        c.save(ctx)
    }

    private fun loadTemplate(ctx: Context, name: String, w: Int, tol: Int, tx: Float, ty: Float): Sablon? =
        loadTemplateF(ctx, name, w / BW, tol, tx, ty)

    /** s: referans ekrana gore arayuz olcegi */
    fun loadTemplateF(ctx: Context, name: String, s: Float, tol: Int, tx: Float, ty: Float): Sablon? {
        return try {
            val bmp = ctx.assets.open(name).use { BitmapFactory.decodeStream(it) } ?: return null
            val f = s * ScreenSampler.SCALE / ScreenSampler.GRID
            val tw = (bmp.width * f).roundToInt().coerceAtLeast(4)
            val th = (bmp.height * f).roundToInt().coerceAtLeast(3)
            val sc = Bitmap.createScaledBitmap(bmp, tw, th, true)
            val px = IntArray(tw * th)
            sc.getPixels(px, 0, tw, 0, 0, tw, th)
            for (i in px.indices) px[i] = px[i] and 0xFFFFFF
            Sablon(tw, th, px, tol, tx, ty)
        } catch (e: Exception) {
            null
        }
    }

    // ================= Otomatik ekran tanima =================

    /** Bulunan arayuz olcegi ve sol ust ofseti (ekran pikseli) */
    class Olcek(val s: Float, val ox: Int, val oy: Int, val w: Int, val h: Int)

    /** Son aramanin sonucu (teshis icin) */
    @Volatile var sonFark = -1L
    @Volatile var sonOlcekDegeri = 0f

    private class Isaret(val dosya: String, val refX: Int, val refY: Int, val xPay: Float, val esik: Long) {
        var px: IntArray? = null
        var w = 0
        var h = 0
    }

    // Sabit isaretler: once barin yanindaki M karesi, bulunamazsa aslan
    private val ISARETLER = listOf(
        Isaret("m.png", 444, 6, 0.40f, 100),
        Isaret("aslan.png", 0, 0, 0.06f, 90)
    )

    private fun yukle(ctx: Context, i: Isaret): Boolean {
        if (i.px != null) return true
        return try {
            val b = ctx.assets.open(i.dosya).use { BitmapFactory.decodeStream(it) } ?: return false
            i.w = b.width
            i.h = b.height
            val p = IntArray(i.w * i.h)
            b.getPixels(p, 0, i.w, 0, 0, i.w, i.h)
            i.px = p
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Isareti farkli boyutlarda arar: [fark, olcek*1000, x, y] (izgara) */
    private fun ara(f: ScreenSampler.Frame, i: Isaret): LongArray {
        val src = Bitmap.createBitmap(i.px!!, i.w, i.h, Bitmap.Config.ARGB_8888)
        val maxX = (f.w * i.xPay).toInt().coerceAtLeast(1)
        val maxY = (f.h * 0.06f).toInt().coerceAtLeast(1)
        var bestD = Long.MAX_VALUE
        var bestS = 0f
        var bx = 0
        var by = 0
        for (k in 0 until 58) {
            val s = 0.5f + k * 0.03f
            val tw = (i.w * s * ScreenSampler.SCALE / ScreenSampler.GRID).roundToInt().coerceAtLeast(4)
            val th = (i.h * s * ScreenSampler.SCALE / ScreenSampler.GRID).roundToInt().coerceAtLeast(4)
            if (tw > f.w || th > f.h) break
            val t = IntArray(tw * th)
            Bitmap.createScaledBitmap(src, tw, th, true).getPixels(t, 0, tw, 0, 0, tw, th)
            val n = (tw * th).toLong()
            for (y in 0..minOf(maxY, f.h - th)) {
                for (x in 0..minOf(maxX, f.w - tw)) {
                    var sum = 0L
                    var j = 0
                    val sinir = if (bestD == Long.MAX_VALUE) Long.MAX_VALUE else bestD * n
                    while (j < t.size) {
                        val a = f.px[(y + j / tw) * f.w + x + j % tw]
                        val b = t[j]
                        sum += kotlin.math.abs(((a shr 16) and 0xff) - ((b shr 16) and 0xff)) +
                            kotlin.math.abs(((a shr 8) and 0xff) - ((b shr 8) and 0xff)) +
                            kotlin.math.abs((a and 0xff) - (b and 0xff))
                        if (sum > sinir) break
                        j++
                    }
                    if (j == t.size) {
                        val d = sum / n
                        if (d < bestD) {
                            bestD = d; bestS = s; bx = x; by = y
                        }
                    }
                }
            }
        }
        return longArrayOf(bestD, (bestS * 1000).toLong(), bx.toLong(), by.toLong())
    }

    /**
     * Sabit isaretleri (aslan, sonra M karesi) farkli boyutlarda arayip ekranin
     * arayuz olcegini ve sol ust ofsetini bulur. Oyun ekranda degilse null.
     */
    fun olcekBul(ctx: Context): Olcek? {
        ScreenSampler.bekle(600)
        val f = ScreenSampler.grab() ?: return null
        val (w, h) = landscapeSize(ctx)
        for (i in ISARETLER) {
            if (!yukle(ctx, i)) continue
            val r = ara(f, i)
            sonFark = if (r[0] == Long.MAX_VALUE) -1L else r[0]
            sonOlcekDegeri = r[1] / 1000f
            if (r[0] <= i.esik) {
                val s = r[1] / 1000f
                val gx = r[2] * ScreenSampler.GRID / ScreenSampler.SCALE
                val gy = r[3] * ScreenSampler.GRID / ScreenSampler.SCALE
                val ox = (gx - i.refX * s).roundToInt().coerceAtLeast(0)
                val oy = (gy - i.refY * s).roundToInt().coerceAtLeast(0)
                return Olcek(s, ox, oy, w, h)
            }
        }
        return null
    }

    // Referans (2712x1220) tus olculeri. Sira = skill onceligi.
    private class Ref(val ad: String, val type: String, val x: Int, val y: Int, val cd: Float = 0f, val on: Boolean = true)

    private val REF = listOf(
        Ref("Kılıç", "saldiri", 2418, 930),
        Ref("Mob seç", "hedef", 2570, 1008),
        Ref("HP pot", "hp_pot", 2370, 757),
        Ref("MP pot", "mp_pot", 2255, 877),
        Ref("Skill 1", "skill", 2522, 760, 1f),   // kirmizi isin
        Ref("Skill 2", "skill", 2338, 615, 1f),   // mavi isin (ust)
        Ref("Skill 3", "skill", 2108, 862, 1f),   // mavi isin (alt)
        Ref("Skill 4", "skill", 2212, 727, 1f),   // mavi kilic
        Ref("Skill 5", "skill", 2107, 1013, 60f, false), // buff olabilir
        Ref("Skill 6", "skill", 2255, 1013, 60f, false)
    )

    /**
     * Hazir tuslarin bu ekrandaki yerleri (sag alta gore olceklenir).
     * Kullanicinin ayarladigi bekleme/acik-kapali secimleri korunur.
     */
    fun otoTuslar(o: Olcek, eski: List<Nokta>): List<Nokta> =
        REF.map { r ->
            val p = sagAlt(o, r.x, r.y)
            val e = eski.firstOrNull { it.name == r.ad && it.type == r.type }
            Nokta(r.ad, r.type, p[0], p[1], e?.cd ?: r.cd, e?.on ?: r.on)
        }

    /** Tanima olmazsa: eski usul (genislige gore) olcek */
    fun varsayilanOlcek(ctx: Context): Olcek {
        val (w, h) = landscapeSize(ctx)
        return Olcek(w / BW, 0, 0, w, h)
    }

    /** Sol uste gore */
    fun solUst(o: Olcek, xr: Int, yr: Int) =
        intArrayOf((o.ox + xr * o.s).roundToInt(), (o.oy + yr * o.s).roundToInt())

    /** Ust ortaya gore (hedef bari) */
    fun ustOrta(o: Olcek, xr: Int, yr: Int) =
        intArrayOf((o.w / 2f + (xr - BW / 2f) * o.s).roundToInt(), (o.oy + yr * o.s).roundToInt())

    /** Sag alta gore (tuslar) */
    fun sagAlt(o: Olcek, xr: Int, yr: Int) =
        intArrayOf((o.w - (BW - xr) * o.s).roundToInt(), (o.h - (BH - yr) * o.s).roundToInt())
}
