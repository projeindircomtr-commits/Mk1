package com.kanka.makro

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Uyelik kontrolu. Sunucu cevabi ECDSA ile imzalidir; imza, cihaz kodu ve
 * rastgele "nonce" tutmazsa kabul edilmez (sahte sunucu / eski cevap ise yaramaz).
 * Sure sunucu saatine gore hesaplanir (telefon tarihi degistirilse de etkilemez).
 */
object Lisans {

    class Sonuc(
        val ok: Boolean,
        val mesaj: String,
        val isim: String = "",
        val bitis: Long = 0L,
        val ag: Boolean = false,  // internet/sunucuya ulasilamadi
        val guncelleUrl: String? = null  // eski surum: bu linkten guncellenmeli
    )

    /** Sunucu "yeni surum gerekli" dediyse makro calismaz */
    @Volatile var guncelleGerekli = false
    @Volatile var guncelleUrl = ""
    @Volatile var guncelleMesaj = ""

    @Suppress("DEPRECATION")
    fun surumKodu(ctx: Context): Int = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else pi.versionCode
    } catch (e: Exception) {
        0
    }

    fun guncelleIsaretle(url: String, mesaj: String) {
        guncelleGerekli = true
        guncelleUrl = url
        guncelleMesaj = mesaj
        okAt = 0L   // lisans da gecersiz sayilir
    }

    // Son basarili kontrol (bellekte)
    @Volatile private var okAt = 0L        // elapsedRealtime (ms)
    @Volatile private var sunucuZaman = 0L // sunucu saati (sn)
    @Volatile private var bitis = 0L
    @Volatile var isim = ""
        private set

    /**
     * Basarili kontrolden sonra sunucuya ulasilamazsa en fazla bu kadar idare eder
     * (sunucu cokerse herkesin makrosu hemen durmasin). Uyelik bitisi yine gecerli.
     */
    private const val TOLERANS_MS = 6 * 60 * 60 * 1000L

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("lisans", Context.MODE_PRIVATE)

    fun cihazKodu(ctx: Context): String {
        val id = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "yok"
        val d = MessageDigest.getInstance("SHA-256").digest(("makro:" + id).toByteArray())
        val hex = d.take(6).joinToString("") { "%02X".format(it) }
        return hex.substring(0, 6) + "-" + hex.substring(6, 12)
    }

    fun kayitliKullanici(ctx: Context): String = prefs(ctx).getString("k", "") ?: ""

    /** Mesaj/surum kontrolu icin sunucunun verdigi oturum anahtari */
    fun token(ctx: Context): String = prefs(ctx).getString("t", "") ?: ""

    /** Lisans adresinin yanindaki baska bir sayfa (ör. mesaj.php) */
    fun yanUrl(ctx: Context, dosya: String): String? {
        val u = ayar(ctx)?.optString("url") ?: return null
        if (!u.endsWith("api.php")) return null
        return u.removeSuffix("api.php") + dosya
    }

    fun cikis(ctx: Context) {
        prefs(ctx).edit().clear().apply()
        okAt = 0L
        bitis = 0L
        isim = ""
    }

    /** Son basarili kontrolu diske yazar (uygulama kapanip acilsa da tolerans surer) */
    private fun kaydetSonDurum(ctx: Context) {
        prefs(ctx).edit()
            .putLong("okDuvar", System.currentTimeMillis())
            .putLong("sz", sunucuZaman)
            .putLong("bt", bitis)
            .putString("is", isim)
            .apply()
    }

    /** Uygulama acilisinda: son basarili kontrolu diskten geri yukle */
    fun yukle(ctx: Context) {
        if (okAt != 0L) return
        val p = prefs(ctx)
        val okDuvar = p.getLong("okDuvar", 0L)
        if (okDuvar == 0L || (p.getString("k", "") ?: "").isEmpty()) return
        val gecen = System.currentTimeMillis() - okDuvar
        // Saat geri alinmissa ya da tolerans gectiyse gecersiz
        if (gecen < 0 || gecen > TOLERANS_MS) return
        okAt = SystemClock.elapsedRealtime() - gecen
        sunucuZaman = p.getLong("sz", 0L)
        bitis = p.getLong("bt", 0L)
        isim = p.getString("is", "") ?: ""
    }

    fun gecerliSimdi(): Boolean {
        if (okAt == 0L) return false
        val gecen = SystemClock.elapsedRealtime() - okAt
        if (gecen > TOLERANS_MS || gecen < 0) return false
        return sunucuZaman + gecen / 1000 < bitis
    }

    /** Kalan sure (sn), gecersizse 0 */
    fun kalanSn(): Long {
        if (!gecerliSimdi()) return 0L
        val gecen = SystemClock.elapsedRealtime() - okAt
        return (bitis - (sunucuZaman + gecen / 1000)).coerceAtLeast(0L)
    }

    fun sonKontroldenBeriMs(): Long =
        if (okAt == 0L) Long.MAX_VALUE else SystemClock.elapsedRealtime() - okAt

    private fun ayar(ctx: Context): JSONObject? = try {
        JSONObject(ctx.assets.open("lisans.json").bufferedReader().use { it.readText() })
    } catch (e: Exception) {
        null
    }

    /** Ag islemi yapar: ANA THREAD'DE CAGIRMA */
    fun kontrolEt(ctx: Context, kullanici: String, sifre: String): Sonuc {
        val a = ayar(ctx) ?: return Sonuc(false, "Lisans sunucusu ayarlanmamış (lisans.json yok)")
        val url = a.optString("url")
        val pubB64 = a.optString("pub")
        if (url.isEmpty() || pubB64.isEmpty()) return Sonuc(false, "lisans.json hatalı")

        val cihaz = cihazKodu(ctx).replace("-", "")
        val nb = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val nonce = nb.joinToString("") { "%02x".format(it) }

        val cevap: String
        try {
            val body = listOf(
                "kullanici" to kullanici, "sifre" to sifre, "cihaz" to cihaz, "nonce" to nonce,
                "surum" to surumKodu(ctx).toString()
            )
                .joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 10000
            c.readTimeout = 10000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(body.toByteArray()) }
            // 503 vb. hata kodlari istisna firlatir -> asagida "ag" sayilir
            cevap = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
        } catch (e: Exception) {
            return Sonuc(false, "Sunucuya ulaşılamadı, interneti kontrol et", ag = true)
        }

        val j = try {
            JSONObject(cevap)
        } catch (e: Exception) {
            // Hosting hata sayfasi vb.: uyeligi gecersiz sayma, gecici ariza
            return Sonuc(false, "Sunucu şu an yanıt vermiyor", ag = true)
        }
        return try {
            if (j.optInt("ok") != 1) {
                if (j.optInt("sunucu") == 1) return Sonuc(false, j.optString("mesaj", "Sunucu hatası"), ag = true)
                val g = j.optString("guncelle", "")
                if (j.has("guncelle")) {
                    guncelleIsaretle(g, j.optString("mesaj"))
                    return Sonuc(false, j.optString("mesaj", "Yeni sürüm gerekli"), guncelleUrl = g)
                }
                return Sonuc(false, j.optString("mesaj", "Giriş başarısız"))
            }
            guncelleGerekli = false
            val isimS = j.getString("isim")
            val bitisS = j.getLong("bitis")
            val zaman = j.getLong("zaman")
            val metin = "v1|1|$kullanici|$cihaz|$bitisS|$isimS|$nonce|$zaman"

            val pk = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(Base64.decode(pubB64, Base64.DEFAULT)))
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(pk)
            sig.update(metin.toByteArray(Charsets.UTF_8))
            if (!sig.verify(Base64.decode(j.getString("imza"), Base64.DEFAULT))) {
                return Sonuc(false, "Sunucu doğrulanamadı")
            }
            if (zaman >= bitisS) return Sonuc(false, "Üyelik süresi doldu")

            prefs(ctx).edit().putString("k", kullanici).putString("s", sifre)
                .putString("t", j.optString("token", "")).apply()
            okAt = SystemClock.elapsedRealtime()
            sunucuZaman = zaman
            bitis = bitisS
            isim = isimS
            kaydetSonDurum(ctx)
            Sonuc(true, "Giriş başarılı", isimS, bitisS)
        } catch (e: Exception) {
            Sonuc(false, "Sunucu cevabı okunamadı", ag = true)
        }
    }

    /** Kayitli bilgilerle arka planda kontrol, sonuc ana thread'e doner */
    fun arkaPlanKontrol(ctx: Context, sonra: (Sonuc) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val k = prefs(app).getString("k", "") ?: ""
            val s = prefs(app).getString("s", "") ?: ""
            val r = if (k.isEmpty()) Sonuc(false, "Önce uygulamadan giriş yap") else kontrolEt(app, k, s)
            Handler(Looper.getMainLooper()).post { sonra(r) }
        }.start()
    }

    /** Verilen bilgilerle giris (arka planda) */
    fun giris(ctx: Context, k: String, s: String, sonra: (Sonuc) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val r = kontrolEt(app, k, s)
            Handler(Looper.getMainLooper()).post { sonra(r) }
        }.start()
    }
}
