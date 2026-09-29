package com.kanka.makro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** type: saldiri, hedef, hp_pot, mp_pot, skill */
data class Nokta(
    var name: String,
    var type: String,
    var x: Int,
    var y: Int,
    var cd: Float = 0f,
    var on: Boolean = true
)

data class RenkNokta(val x: Int, val y: Int, val color: Int) {
    fun toJson(): JSONObject = JSONObject().put("x", x).put("y", y).put("c", color)

    companion object {
        fun from(o: JSONObject?) = o?.let { RenkNokta(it.getInt("x"), it.getInt("y"), it.getInt("c")) }
    }
}

/** Ekranda aranacak kucuk goruntu (izgara cozunurlugunde RGB) */
class Sablon(
    val w: Int,
    val h: Int,
    val px: IntArray,
    val tol: Int = 0,       // 0 = genel toleransi kullan
    val tx: Float = 0.5f,   // dokunulacak nokta (sablon icinde oran)
    val ty: Float = 0.5f
) {
    fun toJson(): JSONObject {
        val a = JSONArray()
        for (v in px) a.put(v)
        return JSONObject().put("w", w).put("h", h).put("px", a)
            .put("tol", tol).put("tx", tx.toDouble()).put("ty", ty.toDouble())
    }

    companion object {
        fun from(o: JSONObject?): Sablon? {
            if (o == null) return null
            val a = o.optJSONArray("px") ?: return null
            val w = o.getInt("w")
            val h = o.getInt("h")
            if (a.length() != w * h) return null
            return Sablon(
                w, h, IntArray(a.length()) { a.getInt(it) },
                o.optInt("tol", 0), o.optDouble("tx", 0.5).toFloat(), o.optDouble("ty", 0.5).toFloat()
            )
        }
    }
}

/** Pazarda satilacak esya: envanterdeki ikonu (yari cozunurluk) ve fiyati */
class PazarEsya(val w: Int, val h: Int, val px: IntArray, var fiyat: Long) {
    fun toJson(): JSONObject = JSONObject().put("w", w).put("h", h).put("fiyat", fiyat)
        .put("px", JSONArray().apply { px.forEach { put(it) } })

    companion object {
        fun from(o: JSONObject?): PazarEsya? {
            if (o == null) return null
            val a = o.optJSONArray("px") ?: return null
            val w = o.optInt("w"); val h = o.optInt("h")
            if (w <= 0 || a.length() != w * h) return null
            return PazarEsya(w, h, IntArray(a.length()) { a.getInt(it) }, o.optLong("fiyat", 0L))
        }
    }
}

/** Kilitli mob ismi: harflerin sekli (renk maskesi), yari cozunurlukte */
class Kilit(val w: Int, val h: Int, val renk: Int, val bits: String) {
    fun toJson(): JSONObject = JSONObject().put("w", w).put("h", h).put("renk", renk).put("bits", bits)

    companion object {
        fun from(o: JSONObject?): Kilit? {
            if (o == null) return null
            val b = o.optString("bits")
            val w = o.optInt("w")
            val h = o.optInt("h")
            if (b.length != w * h) return null
            return Kilit(w, h, o.optInt("renk"), b)
        }
    }
}

class Config {
    val points = mutableListOf<Nokta>()
    var hp: RenkNokta? = null
    var mp: RenkNokta? = null
    var tgtBar: RenkNokta? = null
    var openT: Sablon? = null
    var collectT: Sablon? = null
    var collectT2: Sablon? = null   // yedek: sadece Collect All butonu

    // Genel
    var minutes = 30
    var radius = 18
    var tol = 70
    var ttol = 30

    // Dokunuslar arasi bekleme (ms)
    var minDelay = 180
    var maxDelay = 550

    // Mola
    var pauseChance = 3      // % (her dokunustan sonra)
    var pauseMin = 1200
    var pauseMax = 3500

    // Hedef
    var tgtMin = 2500        // bar kayitli degilse: periyodik secim (ms)
    var tgtMax = 5000
    var tgtFast = 700        // bar kayitliysa: hedef yokken tekrar deneme (ms)

    // Pot ve skill
    var potCd = 1500         // ayni pot icin en az bekleme (ms)
    var skMin = 250          // skill cooldown'una eklenen rastgele sure (ms)
    var skMax = 1500

    // Kutu
    var lootEvery = 150      // ekran tarama araligi (ms)
    var collectWait = 3000   // Open'dan sonra Collect All bekleme (ms)

    // Kutu toplama acik mi
    var lootOn = true

    // Otomatik ekran tanima: HP/MP, hedef bari ve kutular her cihazda kendiliginden bulunur
    var otoArayuz = true

    // Minor (iyilestirme skili): can bu %'nin altindayken basilir
    var minorYuzde = 80
    var minorAktif = true   // Minor ac/kapa (oyun ici ayarlardan)

    // Alan siniri (Farm): secili mobun secim halkasi karakter etrafindaki dikdortgenin disindaysa birak
    var alanAktif = false
    var alanW = 36    // dikdortgen genisligi (ekran genisliginin %'si)
    var alanH = 62    // dikdortgen yuksekligi (ekran yuksekliginin %'si)
    var alanCy = 46   // dikdortgen merkezinin dikey konumu (ekran yuksekliginin %'si); yatayda hep ortada

    // Mesafe siniri sonrasi bekleme: uzak mob birakilinca ayni moba tekrar kosmamak icin (sn).
    // Ust uste her uzak mobda bekleme uzar (en fazla 20 sn), vurus alinca sifirlanir.
    var menzilBekleSn = 4

    // Mesafe siniri: mob secildikten sonra bu kadar sn icinde vurulmaya baslanmazsa (uzak) birak. 0 = kapali
    var menzilSn = 3          // mesafe siniri suresi (sn); acik/kapali menzilAktif ile
    var menzilAktif = false   // mesafe siniri aktif mi (sure kapatinca kaybolmaz)

    // Pot esikleri (%): otomatik tanimada barin doluluguna gore
    var hpYuzde = 74
    var mpYuzde = 25

    // Sadece bu moblara vur (bos = hepsi)
    val kilitler = mutableListOf<Kilit>()

    // Tuslar hazir ayardan mi (kullanici duzenlemediyse ekrana gore olceklenir)
    var tuslarOto = true

    // Guvenlik
    var hpStop = 25          // HP bu kadar sn dusuk kalirsa dur (0 = kapali)

    fun toJson(): JSONObject {
        val o = JSONObject()
        val arr = JSONArray()
        for (p in points) {
            arr.put(
                JSONObject().put("name", p.name).put("type", p.type)
                    .put("x", p.x).put("y", p.y).put("cd", p.cd.toDouble()).put("on", p.on)
            )
        }
        o.put("points", arr)
        hp?.let { o.put("hp", it.toJson()) }
        mp?.let { o.put("mp", it.toJson()) }
        tgtBar?.let { o.put("tgtBar", it.toJson()) }
        openT?.let { o.put("openT", it.toJson()) }
        collectT?.let { o.put("collectT", it.toJson()) }
        collectT2?.let { o.put("collectT2", it.toJson()) }
        o.put("minutes", minutes).put("radius", radius).put("tol", tol).put("ttol", ttol)
            .put("minDelay", minDelay).put("maxDelay", maxDelay)
            .put("pauseChance", pauseChance).put("pauseMin", pauseMin).put("pauseMax", pauseMax)
            .put("tgtMin", tgtMin).put("tgtMax", tgtMax).put("tgtFast", tgtFast)
            .put("potCd", potCd).put("skMin", skMin).put("skMax", skMax)
            .put("lootEvery", lootEvery).put("collectWait", collectWait)
            .put("hpStop", hpStop).put("lootOn", lootOn).put("otoArayuz", otoArayuz).put("tuslarOto", tuslarOto)
            .put("hpYuzde", hpYuzde).put("mpYuzde", mpYuzde).put("menzilSn", menzilSn).put("menzilAktif", menzilAktif).put("minorYuzde", minorYuzde).put("minorAktif", minorAktif)
            .put("menzilBekleSn", menzilBekleSn).put("alanAktif", alanAktif).put("alanW", alanW).put("alanH", alanH).put("alanCy", alanCy)
            .put("kilitler", JSONArray().apply { kilitler.forEach { put(it.toJson()) } })
        return o
    }

    fun save(ctx: Context) {
        prefs(ctx).edit().putString(anahtar(ctx), toJson().toString()).apply()
    }

    companion object {
        private fun prefs(ctx: Context) =
            ctx.applicationContext.getSharedPreferences("makro", Context.MODE_PRIVATE)

        private fun profPrefs(ctx: Context) =
            ctx.applicationContext.getSharedPreferences("makro_profiller", Context.MODE_PRIVATE)

        // ---------- Mod: Farm / PK (her modun kendi ayarlari) ----------
        private fun genel(ctx: Context) =
            ctx.applicationContext.getSharedPreferences("genel", Context.MODE_PRIVATE)

        /** Secili bot: "farm", "pk" ya da "pazar" */
        fun bot(ctx: Context): String =
            genel(ctx).getString("bot", null) ?: if (genel(ctx).getBoolean("pk", false)) "pk" else "farm"

        fun pazarMi(ctx: Context): Boolean = bot(ctx) == "pazar"

        fun pazarSec(ctx: Context) {
            genel(ctx).edit().putString("bot", "pazar").commit()
        }

        fun pkMi(ctx: Context): Boolean = bot(ctx) == "pk"

        // ---------- Pazar: satilacak esyalar (ikon + fiyat) ve kontrol araligi ----------
        fun pazarEsyalar(ctx: Context): MutableList<PazarEsya> {
            val out = mutableListOf<PazarEsya>()
            try {
                val a = JSONArray(genel(ctx).getString("pazarEsyalar", "[]"))
                for (i in 0 until a.length()) PazarEsya.from(a.optJSONObject(i))?.let { out.add(it) }
            } catch (e: Exception) {
            }
            return out
        }

        fun pazarEsyalarYaz(ctx: Context, l: List<PazarEsya>) {
            val a = JSONArray()
            l.forEach { a.put(it.toJson()) }
            genel(ctx).edit().putString("pazarEsyalar", a.toString()).commit()
        }

        fun pazarDk(ctx: Context): Int = genel(ctx).getInt("pazarDk", 30)

        fun pazarDkYaz(ctx: Context, dk: Int) {
            genel(ctx).edit().putInt("pazarDk", dk).commit()
        }

        /** Ekran kaydi uyumlu mod: ekran paylasimi yerine erisilebilirlik ekran goruntusu (~3/sn) */
        /** PK karakterleri: her birinin tus duzeni ve ayarlari ayri */
        // Sira: Mage, Warrior, Asas/Okcu (oyunda ayni sinif), Priest. Anahtarlar degismedi (kayitlar korunur)
        val SINIFLAR = listOf(
            "mage" to "🔥 Mage", "warrior" to "🛡 Warrior", "asas" to "🗡 Asas/Okçu", "priest" to "✨ Priest"
        )

        fun sinif(ctx: Context): String = genel(ctx).getString("pkSinif", "asas") ?: "asas"

        /** Minor sadece Asas/Okcu'da (farm'da da acik) */
        fun minorVar(ctx: Context): Boolean = !pkMi(ctx) || sinif(ctx) == "asas"

        fun sinifAd(ctx: Context): String = SINIFLAR.firstOrNull { it.first == sinif(ctx) }?.second ?: "🗡 Asas/Okçu"

        private fun anahtar(ctx: Context) = if (pkMi(ctx)) "cfg_pk_" + sinif(ctx) else "cfg"

        /**
         * Modu (ve PK'da karakteri) degistirir. Bir karaktere ilk geciste
         * baslangic olarak onceki PK ayari ya da farm ayari kopyalanir.
         */
        fun modDegistir(ctx: Context, pk: Boolean, yeniSinif: String? = null) {
            if (yeniSinif != null) genel(ctx).edit().putString("pkSinif", yeniSinif).commit()
            if (pk) {
                val k = "cfg_pk_" + sinif(ctx)
                if (prefs(ctx).getString(k, null) == null) {
                    val kaynak = prefs(ctx).getString("cfg_pk", null) ?: prefs(ctx).getString("cfg", null)
                    kaynak?.let { js ->
                        // Minor sadece Asas'ta var: diger karakterlere tasima
                        val yaz = if (sinif(ctx) == "asas") js
                        else parse(js)?.let { c -> c.points.removeAll { it.type == "minor" }; c.toJson().toString() } ?: js
                        prefs(ctx).edit().putString(k, yaz).commit()
                    }
                }
            }
            genel(ctx).edit().putBoolean("pk", pk).putString("bot", if (pk) "pk" else "farm").commit()
        }

        fun load(ctx: Context): Config {
            val s = prefs(ctx).getString(anahtar(ctx), null) ?: return Config()
            return parse(s) ?: Config()
        }

        fun parse(s: String): Config? {
            val c = Config()
            try {
                val o = JSONObject(s)
                val arr = o.optJSONArray("points") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    c.points.add(
                        Nokta(
                            p.getString("name"), p.getString("type"),
                            p.getInt("x"), p.getInt("y"),
                            p.optDouble("cd", 0.0).toFloat(), p.optBoolean("on", true)
                        )
                    )
                }
                c.hp = RenkNokta.from(o.optJSONObject("hp"))
                c.mp = RenkNokta.from(o.optJSONObject("mp"))
                c.tgtBar = RenkNokta.from(o.optJSONObject("tgtBar"))
                c.openT = Sablon.from(o.optJSONObject("openT"))
                c.collectT = Sablon.from(o.optJSONObject("collectT"))
                c.collectT2 = Sablon.from(o.optJSONObject("collectT2"))
                c.minutes = o.optInt("minutes", c.minutes).coerceIn(10, 600)
                c.radius = o.optInt("radius", c.radius)
                c.tol = o.optInt("tol", c.tol)
                c.ttol = o.optInt("ttol", c.ttol)
                c.minDelay = o.optInt("minDelay", c.minDelay)
                c.maxDelay = o.optInt("maxDelay", c.maxDelay)
                c.pauseChance = o.optInt("pauseChance", c.pauseChance)
                c.pauseMin = o.optInt("pauseMin", c.pauseMin)
                c.pauseMax = o.optInt("pauseMax", c.pauseMax)
                c.tgtMin = o.optInt("tgtMin", c.tgtMin)
                c.tgtMax = o.optInt("tgtMax", c.tgtMax)
                c.tgtFast = o.optInt("tgtFast", c.tgtFast)
                c.potCd = o.optInt("potCd", c.potCd)
                c.skMin = o.optInt("skMin", c.skMin)
                c.skMax = o.optInt("skMax", c.skMax)
                c.lootEvery = o.optInt("lootEvery", c.lootEvery)
                c.collectWait = o.optInt("collectWait", c.collectWait)
                c.hpStop = o.optInt("hpStop", c.hpStop)
                c.lootOn = o.optBoolean("lootOn", true)
                c.otoArayuz = o.optBoolean("otoArayuz", true)
                c.tuslarOto = o.optBoolean("tuslarOto", true)
                // Eski kayitlar: menzilSn > 0 ise mesafe siniri aciktir, 0 ise kapali
                val eskiSn = o.optInt("menzilSn", 0)
                c.menzilAktif = if (o.has("menzilAktif")) o.optBoolean("menzilAktif", false) else eskiSn > 0
                c.menzilSn = (if (eskiSn <= 0) 3 else eskiSn).coerceIn(1, 15)
                c.minorYuzde = o.optInt("minorYuzde", 80).coerceIn(20, 99)
                c.minorAktif = o.optBoolean("minorAktif", true)
                c.menzilBekleSn = o.optInt("menzilBekleSn", 4).coerceIn(1, 15)
                c.alanAktif = o.optBoolean("alanAktif", false)
                c.alanW = o.optInt("alanW", 36).coerceIn(10, 95)
                c.alanH = o.optInt("alanH", 62).coerceIn(10, 95)
                c.alanCy = o.optInt("alanCy", 46).coerceIn(20, 80)
                c.hpYuzde = o.optInt("hpYuzde", 74).coerceIn(10, 95)
                c.mpYuzde = o.optInt("mpYuzde", 25).coerceIn(5, 95)
                o.optJSONArray("kilitler")?.let { a ->
                    for (i in 0 until a.length()) Kilit.from(a.optJSONObject(i))?.let { c.kilitler.add(it) }
                }
            } catch (e: Exception) {
                return null
            }
            return c
        }

        // ---------- Profiller ----------
        fun profiles(ctx: Context): List<String> =
            profPrefs(ctx).all.keys.sortedBy { it.lowercase() }

        fun saveProfile(ctx: Context, name: String, c: Config) {
            profPrefs(ctx).edit().putString(name, c.toJson().toString()).apply()
        }

        fun loadProfile(ctx: Context, name: String): Boolean {
            val s = profPrefs(ctx).getString(name, null) ?: return false
            val c = parse(s) ?: return false
            c.save(ctx)
            return true
        }

        fun deleteProfile(ctx: Context, name: String) {
            profPrefs(ctx).edit().remove(name).apply()
        }

        fun label(type: String) = when (type) {
            "saldiri" -> "Kılıç"
            "minor" -> "Minor"
            "hedef" -> "Mob seç"
            "hp_pot" -> "HP pot"
            "mp_pot" -> "MP pot"
            "skill" -> "Skill"
            else -> type
        }
    }
}
