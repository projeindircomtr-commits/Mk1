package com.kanka.makro

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {

    companion object {
        const val EXTRA_AUTO = "autoCapture"

        // Knight Online arayuzu (oyundaki CHAR INFO / SKILL PAGE pencereleri gibi)
        private const val BG = 0xFF070707.toInt()          // siyah
        private const val CARD = 0xFF121212.toInt()        // panel
        private const val ACCENT = 0xFFD2A866.toInt()      // mat altin (oyundaki etiket yazisi)
        private const val GREEN = 0xFFB8862B.toInt()       // baslat: altin degrade
        private const val LACI = 0xFF2A3240.toInt()        // celik-lacivert buton
        private const val PARSOMEN = 0xFFEDE3C8.toInt()    // krem yazi
        private const val CERCEVE = 0xFF8C7A55.toInt()     // bronz cerceve (oyundaki paneller)
        private const val BEJ = 0xFFC9AE78.toInt()         // buton kenari (oyundaki gibi)
        private const val RED = 0xFFB33A3A.toInt()
        private const val MUTED = 0xFF9AA4B2.toInt()
    }

    /** Panelden ▶ ile acildiysa: izni al, sonra oyuna geri don */
    private var autoMode = false

    /** Video kaydi icin ekran izni alindiysa CaptureService video de acsin */
    private var videoSonra = false

    /** Buyuk butondan: izin gelince oyunu ac ve baslat */
    private var launchAfterCapture = false

    // ---------- Gelismis ayar alanlari ----------
    private class Alan(
        val label: String,
        val get: (Config) -> Int,
        val set: (Config, Int) -> Unit,
        val min: Int,
        val max: Int
    )

    private val gruplar: List<Pair<String, List<Alan>>> = listOf(
        "Genel" to listOf(
            Alan("Dokunma sapması (piksel)", { it.radius }, { c, v -> c.radius = v }, 0, 200),
            Alan("HP/MP/hedef renk toleransı", { it.tol }, { c, v -> c.tol = v }, 5, 700),
            Alan("Kutu arama toleransı", { it.ttol }, { c, v -> c.ttol = v }, 5, 120)
        ),
        "Dokunuşlar arası bekleme" to listOf(
            Alan("En kısa (ms)", { it.minDelay }, { c, v -> c.minDelay = v }, 50, 10000),
            Alan("En uzun (ms)", { it.maxDelay }, { c, v -> c.maxDelay = v }, 60, 20000)
        ),
        "Mola" to listOf(
            Alan("Mola ihtimali (%)", { it.pauseChance }, { c, v -> c.pauseChance = v }, 0, 50),
            Alan("En kısa mola (ms)", { it.pauseMin }, { c, v -> c.pauseMin = v }, 100, 60000),
            Alan("En uzun mola (ms)", { it.pauseMax }, { c, v -> c.pauseMax = v }, 100, 120000)
        ),
        "Mob seçme" to listOf(
            Alan("Hedef barı yokken: en kısa aralık (ms)", { it.tgtMin }, { c, v -> c.tgtMin = v }, 300, 60000),
            Alan("Hedef barı yokken: en uzun aralık (ms)", { it.tgtMax }, { c, v -> c.tgtMax = v }, 300, 60000),
            Alan("Hedef barı varken: tekrar deneme (ms)", { it.tgtFast }, { c, v -> c.tgtFast = v }, 200, 10000)
        ),
        "Pot ve skill" to listOf(
            Alan("Aynı pot için en az bekleme (ms)", { it.potCd }, { c, v -> c.potCd = v }, 300, 30000),
            Alan("Skill'e eklenen gecikme: en az (ms)", { it.skMin }, { c, v -> c.skMin = v }, 0, 10000),
            Alan("Skill'e eklenen gecikme: en çok (ms)", { it.skMax }, { c, v -> c.skMax = v }, 0, 20000)
        ),
        "Kutu toplama" to listOf(
            Alan("Ekran tarama aralığı (ms)", { it.lootEvery }, { c, v -> c.lootEvery = v }, 100, 10000),
            Alan("Open'dan sonra Collect All bekleme (ms)", { it.collectWait }, { c, v -> c.collectWait = v }, 500, 15000)
        ),
        "Güvenlik" to listOf(
            Alan("HP bu kadar sn düşük kalırsa dur (0 = kapalı)", { it.hpStop }, { c, v -> c.hpStop = v }, 0, 600)
        )
    )

    private val alanEdits = ArrayList<Pair<Alan, EditText>>()
    private val cdEdits = HashMap<Int, EditText>()
    private val onChecks = HashMap<Int, CheckBox>()

    // ---------- Gorunumler ----------
    private lateinit var lisansDurum: TextView
    private lateinit var girisBox: LinearLayout
    private lateinit var cikisBtn: Button
    private lateinit var guncelleBtn: Button
    private lateinit var girisBtn: Button
    private lateinit var siteUyari: LinearLayout
    private lateinit var kEdit: EditText
    private lateinit var sEdit: EditText
    private lateinit var step1: TextView
    private lateinit var step2: TextView
    private lateinit var step3: TextView
    private lateinit var btn1: Button
    private lateinit var btn2: Button
    private lateinit var btn3: Button
    private lateinit var restrictHint: TextView
    private lateinit var startBtn: Button
    private lateinit var minutesTv: TextView
    private lateinit var speedNormal: Button
    private lateinit var speedFast: Button
    private lateinit var speedSeri: Button
    private lateinit var lootSwitch: Switch
    private lateinit var modFarmBtn: Button
    private lateinit var modPkBtn: Button
    private lateinit var ayarBaslik: TextView
    private lateinit var sinifSatir: LinearLayout
    private val sinifBtnler = HashMap<String, Button>()
    private lateinit var otoSwitch: Switch
    private lateinit var hpTv: TextView
    private lateinit var mpTv: TextView
    private lateinit var kilitTv: TextView
    private lateinit var advBox: LinearLayout
    private lateinit var advToggle: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var profBox: LinearLayout
    private lateinit var profName: EditText
    private var cfg = Config()
    private var web: WebView? = null

    /** Giris yapilmadan gizli kalan bolumler */
    private val uyeBolumleri = ArrayList<View>()
    private var gelismisAcik = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ================= Kurulum =================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        Lisans.yukle(this)

        // Ilk acilista MykoMobile ayarlari hazir gelsin
        if (Config.load(this).points.isEmpty()) Preset.apply(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(40))
            setBackgroundColor(BG)
        }

        // Baslik
        root.addView(TextView(this).apply {
            text = "Projeindirpedal"
            textSize = 32f
            typeface = SERIF_BOLD
            setTextColor(ACCENT)
            setShadowLayer(8f, 0f, 2f, 0xFF000000.toInt())
        })
        root.addView(TextView(this).apply {
            text = "MykoMobile için hazır  •  Yapımcı: Muhammed Salman"
            textSize = 14f
            setTextColor(ACCENT)
            setPadding(0, 0, 0, dp(14))
        })

        // --- Uyelik karti ---
        val lk = card(root)
        lk.addView(cardTitle("Üyelik"))
        lisansDurum = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(dp(4), 0, 0, dp(6))
        }
        lk.addView(lisansDurum)
        girisBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        kEdit = EditText(this).apply {
            hint = "Kullanıcı adı"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(MUTED)
        }
        sEdit = EditText(this).apply {
            hint = "Şifre"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(MUTED)
        }
        // Giris icin once site ziyareti (bir kere)
        siteUyari = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xFF2A2230.toInt(), 10)
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }
        siteUyari.addView(TextView(this).apply {
            text = "Giriş yapabilmek için önce sitemizi ziyaret et:"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(6))
        })
        siteUyari.addView(smallButton("🌐 Siteye git") { siteyeGit() }.apply {
            background = rounded(ACCENT, 10)
            setTextColor(0xFF12161C.toInt())
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        girisBox.addView(siteUyari, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })
        girisBox.addView(kEdit)
        girisBox.addView(sEdit)
        girisBtn = smallButton("Giriş yap") { girisYap() }
        girisBox.addView(girisBtn)
        girisBox.addView(TextView(this).apply {
            text = "Cihaz kodu: " + Lisans.cihazKodu(this@MainActivity)
            textSize = 12f
            setTextColor(MUTED)
            setPadding(dp(4), dp(8), 0, 0)
        })
        lk.addView(girisBox)
        cikisBtn = smallButton("Çıkış yap") {
            Lisans.cikis(this)
            lisansYenile()
        }
        lk.addView(cikisBtn)
        guncelleBtn = smallButton("⬇ Yeni sürümü indir") { siteAc(Uri.parse(Lisans.guncelleUrl)) }
        lk.addView(guncelleBtn)

        // --- Mod secimi: Farm / PK (her modun kendi ayarlari) ---
        val modKart = card(root)
        uyeBolumleri.add(modKart)
        modKart.addView(cardTitle("Mod"))
        val modSatir = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modFarmBtn = Button(this).apply {
            text = "🌾  FARM"
            textSize = 17f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener { modSec(false) }
        }
        modPkBtn = Button(this).apply {
            text = "⚔  PK"
            textSize = 17f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener { modSec(true) }
        }
        modSatir.addView(modFarmBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        modSatir.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        modSatir.addView(modPkBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        modKart.addView(modSatir)
        // PK karakter secimi
        sinifSatir = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        Config.SINIFLAR.forEachIndexed { i, (kod, ad) ->
            val b = Button(this).apply {
                text = ad
                textSize = 13f
                isAllCaps = false
                setTextColor(Color.WHITE)
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { modSec(true, kod) }
            }
            sinifBtnler[kod] = b
            if (i > 0) sinifSatir.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
            sinifSatir.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        modKart.addView(sinifSatir)
        modKart.addView(TextView(this).apply {
            text = "Farm: mob keser, kutu toplar.  PK: oyuncuya kılıçla saldırır, skilleri senin sıranla basar. " +
                "Her modun tuş düzeni ve ayarları ayrı saklanır."
            textSize = 12f
            setTextColor(MUTED)
            setPadding(dp(4), dp(8), 0, 0)
        })

        // --- Kurulum karti ---
        val setup = card(root)
        uyeBolumleri.add(setup)
        setup.addView(cardTitle("Kurulum (bir kere)"))
        val r1 = stepRow(setup, "1. Erişilebilirlik izni", "İzin ver") { openAccessibility() }
        step1 = r1.first; btn1 = r1.second
        restrictHint = TextView(this).apply {
            textSize = 13f
            setTextColor(MUTED)
            setPadding(dp(4), 0, dp(4), dp(8))
            text = "Projeindirpedal gri görünüyorsa: aşağıdaki butona bas → sağ üstte ⋮ → " +
                "\"Kısıtlı ayarlara izin ver\". Sonra tekrar \"İzin ver\"."
        }
        setup.addView(restrictHint)
        setup.addView(smallButton("Kısıtlı ayar sayfasını aç") { openAppDetails() })
        val r2 = stepRow(setup, "2. Arka planda kapanmasın", "Ayarla") { fixBattery() }
        step2 = r2.first; btn2 = r2.second
        val r3 = stepRow(setup, "3. Oyun yüklü", "Kontrol") { refreshSteps() }
        step3 = r3.first; btn3 = r3.second

        // --- Buyuk baslat ---
        startBtn = Button(this).apply {
            text = "▶  OYUNU AÇ VE BAŞLAT"
            textSize = 20f
            isAllCaps = false
            setTextColor(0xFF1A1206.toInt())
            setTypeface(typeface, Typeface.BOLD)
            background = rounded(GREEN, 16)
            setPadding(0, dp(18), 0, dp(18))
            setOnClickListener { startAll() }
        }
        root.addView(startBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16); bottomMargin = dp(6) })
        uyeBolumleri.add(startBtn)
        root.addView(TextView(this).apply {
            uyeBolumleri.add(this)
            text = "Oyunda sol üstteki panelden ⏸ ile durdurabilirsin."
            textSize = 13f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(14))
        })

        // --- Basit ayarlar ---
        val quick = card(root)
        uyeBolumleri.add(quick)
        ayarBaslik = cardTitle("Ayarlar")
        quick.addView(ayarBaslik)

        val mRow = row()
        mRow.addView(label("Çalışma süresi"), weight1())
        mRow.addView(smallButton("−") { changeMinutes(-10) })
        minutesTv = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = degerKutusu()
            setPadding(0, dp(6), 0, dp(6))
        }
        mRow.addView(minutesTv, LinearLayout.LayoutParams(dp(84), LinearLayout.LayoutParams.WRAP_CONTENT))
        mRow.addView(smallButton("+") { changeMinutes(10) })
        quick.addView(mRow)

        // Pot esikleri
        val hRow = row()
        hRow.addView(label("❤ HP potu, can şunun altına inince"), weight1())
        hRow.addView(smallButton("−") { yuzdeDegis(true, -5) })
        hpTv = TextView(this).apply {
            textSize = 17f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = degerKutusu(); setPadding(0, dp(6), 0, dp(6))
        }
        hRow.addView(hpTv, LinearLayout.LayoutParams(dp(58), LinearLayout.LayoutParams.WRAP_CONTENT))
        hRow.addView(smallButton("+") { yuzdeDegis(true, 5) })
        quick.addView(hRow)

        val pRow2 = row()
        pRow2.addView(label("💧 MP potu, mana şunun altına inince"), weight1())
        pRow2.addView(smallButton("−") { yuzdeDegis(false, -5) })
        mpTv = TextView(this).apply {
            textSize = 17f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = degerKutusu(); setPadding(0, dp(6), 0, dp(6))
        }
        pRow2.addView(mpTv, LinearLayout.LayoutParams(dp(58), LinearLayout.LayoutParams.WRAP_CONTENT))
        pRow2.addView(smallButton("+") { yuzdeDegis(false, 5) })
        quick.addView(pRow2)

        // Mob kilidi
        val kRow = row()
        kilitTv = TextView(this).apply { textSize = 15f; setTextColor(Color.WHITE) }
        kRow.addView(kilitTv, weight1())
        kRow.addView(smallButton("Kaldır") {
            val c = Config.load(this)
            c.kilitler.clear()
            c.save(this)
            refresh()
            toast("Kilitler kaldırıldı")
        })
        quick.addView(kRow)
        quick.addView(TextView(this).apply {
            text = "Mob kilitlemek için: oyunda mobu seç → panelde ⋯ → 🎯 Seçili mobu kilitle. Birden fazla mob kilitleyebilirsin."
            textSize = 12f
            setTextColor(MUTED)
            setPadding(dp(4), 0, 0, dp(6))
        })

        val sRow = row()
        sRow.addView(label("Hız"), weight1())
        speedNormal = smallButton("Normal") { setSpeed(0) }
        speedFast = smallButton("Hızlı") { setSpeed(1) }
        speedSeri = smallButton("Seri") { setSpeed(2) }
        sRow.addView(speedNormal)
        sRow.addView(speedFast)
        sRow.addView(speedSeri)
        quick.addView(sRow)

        val lRow = row()
        lRow.addView(label("Kutuları topla"), weight1())
        lootSwitch = Switch(this).apply {
            setOnCheckedChangeListener { _, on ->
                val c = Config.load(this@MainActivity)
                if (c.lootOn != on) {
                    c.lootOn = on
                    c.save(this@MainActivity)
                }
            }
        }
        lRow.addView(lootSwitch)
        quick.addView(lRow)

        val oRow = row()
        oRow.addView(label("Ekranı otomatik tanı"), weight1())
        otoSwitch = Switch(this).apply {
            setOnCheckedChangeListener { _, on ->
                val c = Config.load(this@MainActivity)
                if (c.otoArayuz != on) {
                    c.otoArayuz = on
                    c.save(this@MainActivity)
                }
            }
        }
        oRow.addView(otoSwitch)
        quick.addView(oRow)
        quick.addView(TextView(this).apply {
            text = "Açıkken HP/MP barı, mobun can barı ve kutular her telefon ve tablette kendiliğinden bulunur."
            textSize = 12f
            setTextColor(MUTED)
            setPadding(dp(4), 0, 0, 0)
        })

        // --- Site onizlemesi ---
        val site = card(root)
        site.addView(cardTitle("projeindir.com.tr"))
        try {
            val w = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                setBackgroundColor(CARD)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                        // Yonlendirmeler (http->https vb.) icinde kalsin
                        if (req.isRedirect) return false
                        // Dokunulan linkler tarayicida acilsin
                        siteAc(req.url)
                        return true
                    }
                }
                loadUrl("https://projeindir.com.tr")
            }
            web = w
            site.addView(w, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(240)
            ))
        } catch (e: Exception) {
            // Cihazda WebView yoksa sadece buton kalsin
        }
        site.addView(smallButton("🌐 Siteye git") { siteyeGit() },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) })

        // --- Gelismis ---
        advToggle = TextView(this).apply {
            text = "Gelişmiş ayarlar  ▾"
            textSize = 16f
            setTextColor(ACCENT)
            setPadding(dp(4), dp(20), dp(4), dp(10))
            setOnClickListener { toggleAdvanced() }
        }
        root.addView(advToggle)
        uyeBolumleri.add(advToggle)
        advBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        root.addView(advBox)
        buildAdvanced(advBox)

        root.addView(TextView(this).apply {
            text = "Projeindirpedal  •  Sürüm ${Lisans.surumKodu(this@MainActivity)}  •  Yapımcı: Muhammed Salman"
            textSize = 12f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            addView(root)
        })
        handleAuto(intent)
        handleVideoIstek(intent)

        // Panel mesajlari bildirim olarak gelebilsin (Android 13+ izin ister)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 6)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAuto(intent)
        handleVideoIstek(intent)
    }

    override fun onResume() {
        super.onResume()
        web?.onResume()
        refresh()
        lisansYenile()
        // Kayitli giris varsa sessizce dogrula
        if (!Lisans.gecerliSimdi() && Lisans.kayitliKullanici(this).isNotEmpty()) {
            lisansDurum.text = "Kontrol ediliyor..."
            Lisans.arkaPlanKontrol(this) { r ->
                if (!r.ok) toast(r.mesaj)
                lisansYenile()
            }
        }
    }

    private fun lisansYenile() {
        val ok = Lisans.gecerliSimdi()
        val gnc = Lisans.guncelleGerekli
        if (ok) {
            val k = Lisans.kalanSn()
            val g = k / 86400
            val sa = (k % 86400) / 3600
            val kalan = if (g > 0) "$g gün $sa saat" else "$sa saat ${(k % 3600) / 60} dk"
            lisansDurum.text = "✅ ${Lisans.isim}  •  $kalan kaldı"
        } else if (gnc) {
            lisansDurum.text = "⬆ " + Lisans.guncelleMesaj.ifEmpty { "Yeni sürüm çıktı. Devam etmek için güncelle." }
        } else {
            lisansDurum.text = "❌ Giriş yapılmadı"
        }
        guncelleBtn.visibility = if (gnc && Lisans.guncelleUrl.isNotEmpty()) View.VISIBLE else View.GONE
        girisBox.visibility = if (ok) View.GONE else View.VISIBLE
        cikisBtn.visibility = if (ok) View.VISIBLE else View.GONE
        // Uye degilse hicbir ayar/secenek gorunmesin
        val g = if (ok) View.VISIBLE else View.GONE
        for (v in uyeBolumleri) v.visibility = g
        advBox.visibility = if (ok && gelismisAcik) View.VISIBLE else View.GONE
        if (kEdit.text.isEmpty()) kEdit.setText(Lisans.kayitliKullanici(this))
        girisKilidi()
    }

    private fun siteGidildi(): Boolean =
        getSharedPreferences("genel", MODE_PRIVATE).getBoolean("siteGidildi", false)

    /** Siteyi acar ve giris kilidini kalici olarak kaldirir */
    private fun siteyeGit() {
        getSharedPreferences("genel", MODE_PRIVATE).edit().putBoolean("siteGidildi", true).apply()
        siteAc(Uri.parse("https://projeindir.com.tr"))
        girisKilidi()
    }

    /** Site ziyaret edilmediyse giris alanlari kilitli */
    private fun girisKilidi() {
        val acik = siteGidildi()
        siteUyari.visibility = if (acik) View.GONE else View.VISIBLE
        for (v in listOf<View>(kEdit, sEdit, girisBtn)) {
            v.isEnabled = acik
            v.alpha = if (acik) 1f else 0.35f
        }
    }

    private fun girisYap() {
        if (!siteGidildi()) {
            toast("Önce 🌐 Siteye git'e bas"); return
        }
        val k = kEdit.text.toString().trim()
        val s = sEdit.text.toString()
        if (k.isEmpty() || s.isEmpty()) {
            toast("Kullanıcı adı ve şifre yaz"); return
        }
        lisansDurum.text = "Giriş yapılıyor..."
        Lisans.giris(this, k, s) { r ->
            toast(r.mesaj)
            if (r.ok) sEdit.setText("")
            lisansYenile()
        }
    }

    override fun onPause() {
        super.onPause()
        web?.onPause()
        saveAll()
    }

    override fun onDestroy() {
        web?.destroy()
        web = null
        super.onDestroy()
    }

    private fun siteAc(u: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, u))
        } catch (e: Exception) {
            toast("Tarayıcı açılamadı")
        }
    }

    private fun handleAuto(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_AUTO, false) == true) {
            i.removeExtra(EXTRA_AUTO)
            autoMode = true
            askCapture()
        }
    }

    // ================= UI yardimcilari =================

    private fun acik(c: Int, oran: Float): Int {
        val a = (c ushr 24) and 0xff
        fun k(v: Int) = (v + (255 - v) * oran).toInt().coerceIn(0, 255)
        return (a shl 24) or (k((c shr 16) and 0xff) shl 16) or (k((c shr 8) and 0xff) shl 8) or k(c and 0xff)
    }

    /** Panel (koyu) -> gri metal cerceve; buton -> celik degrade + bej kenar */
    private fun rounded(color: Int, r: Int): GradientDrawable {
        val panelMi = ((color shr 16) and 0xff) < 40 && ((color shr 8) and 0xff) < 40 && (color and 0xff) < 40
        return if (panelMi) GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(4).toFloat()
            setStroke(dp(3), CERCEVE)
        } else GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(acik(color, 0.28f), color)).apply {
            cornerRadius = dp(4).toFloat()
            setStroke(dp(2), BEJ)
        }
    }

    /** Oyundaki deger kutulari gibi: siyah zemin, beyaz ince cerceve */
    private fun degerKutusu() = GradientDrawable().apply {
        setColor(0xFF000000.toInt())
        cornerRadius = dp(2).toFloat()
        setStroke(dp(2), 0xFFD8D8D8.toInt())
    }

    private val SERIF_BOLD = Typeface.create(Typeface.SERIF, Typeface.BOLD)

    private fun card(parent: LinearLayout): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, 16)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        parent.addView(c, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) })
        return c
    }

    private fun cardTitle(t: String) = TextView(this).apply {
        text = t
        textSize = 18f
        typeface = SERIF_BOLD
        setTextColor(ACCENT)
        setPadding(dp(4), 0, 0, dp(8))
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 15f
        setTextColor(Color.WHITE)
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(4), 0, dp(4))
    }

    private fun weight1() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    private fun smallButton(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        typeface = SERIF_BOLD
        setTextColor(PARSOMEN)
        background = rounded(LACI, 10)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        setOnClickListener { onClick() }
    }

    private fun stepRow(parent: LinearLayout, title: String, action: String, onClick: () -> Unit): Pair<TextView, Button> {
        val r = row()
        val tv = TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(Color.WHITE)
        }
        r.addView(tv, weight1())
        val b = smallButton(action, onClick)
        r.addView(b)
        parent.addView(r)
        return tv to b
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ================= Durum =================

    private fun accEnabled(): Boolean =
        Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains(packageName) == true

    private fun batteryOk(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun setStep(tv: TextView, b: Button, base: String, ok: Boolean) {
        tv.text = (if (ok) "✅  " else "❌  ") + base
        b.visibility = if (ok) View.GONE else View.VISIBLE
    }

    private fun refreshSteps() {
        val acc = accEnabled()
        setStep(step1, btn1, "Erişilebilirlik izni", acc)
        restrictHint.visibility = if (acc) View.GONE else View.VISIBLE
        (restrictHint.parent as? LinearLayout)?.let { p ->
            val idx = p.indexOfChild(restrictHint)
            if (idx >= 0 && idx + 1 < p.childCount) p.getChildAt(idx + 1).visibility = restrictHint.visibility
        }
        setStep(step2, btn2, "Arka planda kapanmasın", batteryOk())
        setStep(step3, btn3, "Oyun yüklü (MykoMobile)", findGame() != null)
    }

    private fun refresh() {
        cfg = Config.load(this)
        refreshSteps()
        minutesTv.text = "${cfg.minutes} dk"
        val mod = when {
            cfg.maxDelay <= 150 -> 2
            cfg.maxDelay <= 320 -> 1
            else -> 0
        }
        val kapali = LACI
        speedNormal.background = rounded(if (mod == 0) ACCENT else kapali, 10)
        speedFast.background = rounded(if (mod == 1) ACCENT else kapali, 10)
        speedSeri.background = rounded(if (mod == 2) ACCENT else kapali, 10)
        lootSwitch.isChecked = cfg.lootOn
        val pk = Config.pkMi(this)
        modFarmBtn.background = rounded(if (!pk) ACCENT else LACI, 14)
        modPkBtn.background = rounded(if (pk) ACCENT else LACI, 14)
        modFarmBtn.setTextColor(if (!pk) 0xFF12161C.toInt() else Color.WHITE)
        modPkBtn.setTextColor(if (pk) 0xFF12161C.toInt() else Color.WHITE)
        ayarBaslik.text = if (pk) "Ayarlar • PK • " + Config.sinifAd(this) else "Ayarlar • Farm"
        sinifSatir.visibility = if (pk) View.VISIBLE else View.GONE
        val sn = Config.sinif(this)
        for ((kod, b) in sinifBtnler) {
            val sec = pk && kod == sn
            b.background = rounded(if (sec) ACCENT else LACI, 12)
            b.setTextColor(if (sec) 0xFF12161C.toInt() else Color.WHITE)
        }
        hpTv.text = "%${cfg.hpYuzde}"
        mpTv.text = "%${cfg.mpYuzde}"
        kilitTv.text = if (cfg.kilitler.isEmpty()) "🎯 Mob kilidi: yok (her moba vurur)"
        else "🎯 Kilitli mob: ${cfg.kilitler.size}"
        otoSwitch.isChecked = cfg.otoArayuz
        for ((a, e) in alanEdits) e.setText(a.get(cfg).toString())
        buildList()
        buildProfiles()
    }

    // ================= Basit ayar islemleri =================

    private fun yuzdeDegis(hp: Boolean, d: Int) {
        saveAll()
        val c = Config.load(this)
        if (hp) c.hpYuzde = (c.hpYuzde + d).coerceIn(10, 95)
        else c.mpYuzde = (c.mpYuzde + d).coerceIn(5, 95)
        c.save(this)
        refresh()
    }

    private fun modSec(pk: Boolean, sinif: String? = null) {
        if (!Config.pazarMi(this) && Config.pkMi(this) == pk && (!pk || sinif == null || sinif == Config.sinif(this))) return
        saveAll()
        val servis = MacroService.instance
        if (servis != null) servis.modSec(pk, sinif) else Config.modDegistir(this, pk, sinif)
        // Servis ui thread'inde degistirir; kisa gecikmeyle yenile
        modFarmBtn.postDelayed({ refresh() }, 150)
    }

    private fun changeMinutes(d: Int) {
        saveAll()
        val c = Config.load(this)
        c.minutes = (c.minutes + d).coerceIn(10, 600)
        c.save(this)
        refresh()
    }

    private fun setSpeed(mod: Int) {
        saveAll()
        val c = Config.load(this)
        when (mod) {
            2 -> { // Seri: beklemesiz, molasiz
                c.minDelay = 50; c.maxDelay = 110; c.pauseChance = 0
                c.skMin = 0; c.skMax = 150
            }
            1 -> {
                c.minDelay = 120; c.maxDelay = 300; c.pauseChance = 2
                c.skMin = 150; c.skMax = 700
            }
            else -> {
                c.minDelay = 180; c.maxDelay = 550; c.pauseChance = 3
                c.skMin = 250; c.skMax = 1500
            }
        }
        c.save(this)
        refresh()
    }

    private fun toggleAdvanced() {
        val show = advBox.visibility != View.VISIBLE
        gelismisAcik = show
        advBox.visibility = if (show) View.VISIBLE else View.GONE
        advToggle.text = if (show) "Gelişmiş ayarlar  ▴" else "Gelişmiş ayarlar  ▾"
    }

    // ================= Kurulum adimlari =================

    private fun openAccessibility() {
        toast("Listeden 'Projeindirpedal'ı bul ve aç")
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
        }
    }

    private fun openAppDetails() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
            toast("Sağ üstte ⋮ → Kısıtlı ayarlara izin ver")
        } catch (e: Exception) {
        }
    }

    private fun fixBattery() {
        // 1) Android pil optimizasyonu
        if (!batteryOk()) {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
                return
            } catch (e: Exception) {
            }
        }
        // 2) Xiaomi otomatik baslatma sayfasi (varsa)
        try {
            startActivity(Intent().setComponent(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            ))
            toast("Listede 'Projeindirpedal'ı aç")
        } catch (e: Exception) {
            openAppDetails()
        }
    }

    @Suppress("DEPRECATION")
    private fun findGame(): Intent? {
        return try {
            val pm = packageManager
            val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val hit = pm.queryIntentActivities(q, 0).firstOrNull {
                it.activityInfo.packageName.contains("myko", true) ||
                    it.loadLabel(pm).toString().contains("myko", true)
            } ?: return null
            pm.getLaunchIntentForPackage(hit.activityInfo.packageName)
        } catch (e: Exception) {
            null
        }
    }

    // ================= Buyuk baslat =================

    private fun startAll() {
        saveAll()
        if (!Lisans.gecerliSimdi()) {
            toast("Önce üyelik girişi yap")
            return
        }
        if (!accEnabled() || MacroService.instance == null) {
            AlertDialog.Builder(this)
                .setTitle("Önce izin gerekli")
                .setMessage("Kurulum kartındaki 1. adımı yap: \"İzin ver\" → listeden Projeindirpedal → aç.")
                .setPositiveButton("İzin ver") { _, _ -> openAccessibility() }
                .setNegativeButton("Kapat", null)
                .show()
            return
        }
        if (Config.load(this).points.isEmpty()) Preset.apply(this)
        if (!ScreenSampler.running) {
            launchAfterCapture = true
            toast("Açılan pencerede \"Tüm ekran\"ı seç ve başlat")
            askCapture()
            return
        }
        launchGameAndStart()
    }

    private fun launchGameAndStart() {
        val game = findGame()
        if (game == null) {
            AlertDialog.Builder(this)
                .setMessage("MykoMobile bulunamadı. Oyunu kendin aç, karakterin oyuna girince sol üstteki panelde ▶'a bas.")
                .setPositiveButton("Tamam", null)
                .show()
            return
        }
        try {
            startActivity(game)
        } catch (e: Exception) {
            toast("Oyun açılamadı"); return
        }
        // Oyun yuklensin diye biraz bekle, sonra baslat
        MacroService.instance?.let {
            if (!it.isRunning()) it.startFromApp(4000)
        }
        toast("Makro 4 saniye sonra başlayacak")
    }

    // ================= Gelismis bolum =================

    private fun buildAdvanced(box: LinearLayout) {
        // Hazir ayar
        val pre = card(box)
        pre.addView(cardTitle("Hazır ayar"))
        pre.addView(smallButton("⚡ MykoMobile ayarlarını yeniden yükle") {
            AlertDialog.Builder(this)
                .setMessage("Tüm tuşlar, barlar ve kutu butonları MykoMobile için yeniden ayarlansın mı?")
                .setPositiveButton("Yükle") { _, _ ->
                    saveAll(); Preset.apply(this); refresh(); toast("Yüklendi")
                }
                .setNegativeButton("Vazgeç", null)
                .show()
        })
        pre.addView(TextView(this).apply {
            textSize = 13f
            setTextColor(MUTED)
            setPadding(dp(4), dp(8), dp(4), 0)
            text = "Kendi tuşlarını kaydetmek için oyunda panelde ⋯ menüsünü kullan."
        })

        // Tuslar
        val keys = card(box)
        keys.addView(cardTitle("Kayıtlı tuşlar"))
        keys.addView(TextView(this).apply {
            textSize = 13f
            setTextColor(MUTED)
            setPadding(dp(4), 0, 0, dp(6))
            text = "Skill'ler sırayla öncelik alır. ▲▼ ile sırala, kutucukla aç/kapat."
        })
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        keys.addView(listBox)

        // Sureler
        val times = card(box)
        times.addView(cardTitle("Süreler"))
        for ((grup, alanlar) in gruplar) {
            times.addView(TextView(this).apply {
                text = grup
                textSize = 14f
                setTextColor(ACCENT)
                setPadding(dp(4), dp(10), 0, dp(2))
            })
            for (a in alanlar) alanEdits.add(a to numField(times, a.label))
        }
        times.addView(smallButton("Kaydet") { saveAll(); toast("Kaydedildi") })

        // Profiller
        val prof = card(box)
        prof.addView(cardTitle("Profiller"))
        val pRow = row()
        profName = EditText(this).apply {
            hint = "Profil adı"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(MUTED)
        }
        pRow.addView(profName, weight1())
        pRow.addView(smallButton("Kaydet") {
            val n = profName.text.toString().trim()
            if (n.isEmpty()) {
                toast("Profil adı yaz")
            } else {
                saveAll()
                Config.saveProfile(this, n, Config.load(this))
                profName.setText("")
                buildProfiles()
                toast("'$n' kaydedildi")
            }
        })
        prof.addView(pRow)
        profBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        prof.addView(profBox)

        // Temizlik
        val clean = card(box)
        clean.addView(cardTitle("Temizlik"))
        clean.addView(smallButton("Tüm tuşları ve şablonları sil") {
            AlertDialog.Builder(this)
                .setMessage("Kayıtlı tüm tuşlar, bar noktaları ve kutu şablonları silinsin mi?")
                .setPositiveButton("Sil") { _, _ ->
                    saveAll()
                    val c = Config.load(this)
                    c.points.clear(); c.hp = null; c.mp = null; c.tgtBar = null
                    c.openT = null; c.collectT = null; c.collectT2 = null
                    c.save(this)
                    refresh()
                }
                .setNegativeButton("Vazgeç", null)
                .show()
        })
        clean.addView(smallButton("Ekran okumayı durdur") {
            stopService(Intent(this, CaptureService::class.java))
        })
    }

    private fun numField(parent: LinearLayout, labelText: String): EditText {
        val r = row()
        r.addView(TextView(this).apply {
            text = labelText
            textSize = 14f
            setTextColor(Color.WHITE)
        }, weight1())
        val e = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.END
            setTextColor(Color.WHITE)
        }
        r.addView(e, LinearLayout.LayoutParams(dp(90), LinearLayout.LayoutParams.WRAP_CONTENT))
        parent.addView(r)
        return e
    }

    private fun trimFloat(f: Float) = if (f == f.toLong().toFloat()) f.toLong().toString() else f.toString()

    private fun buildList() {
        listBox.removeAllViews()
        cdEdits.clear()
        onChecks.clear()
        if (cfg.points.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = "Henüz tuş yok."
                setTextColor(MUTED)
            })
            return
        }
        cfg.points.forEachIndexed { i, p ->
            val r = row()
            if (p.type == "skill") {
                val cb = CheckBox(this).apply { isChecked = p.on }
                onChecks[i] = cb
                r.addView(cb)
            }
            r.addView(TextView(this).apply {
                text = p.name
                textSize = 14f
                setTextColor(Color.WHITE)
            }, weight1())
            if (p.type == "skill") {
                r.addView(TextView(this).apply { text = "sn"; textSize = 12f; setTextColor(MUTED) })
                val e = EditText(this).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    setText(trimFloat(p.cd))
                    gravity = Gravity.END
                    setTextColor(Color.WHITE)
                }
                cdEdits[i] = e
                r.addView(e, LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            r.addView(smallButton("▲") { move(i, -1) })
            r.addView(smallButton("▼") { move(i, 1) })
            r.addView(smallButton("✕") { removeAt(i) })
            listBox.addView(r)
        }
    }

    private fun renumberSkills(c: Config) {
        var n = 1
        c.points.forEach { if (it.type == "skill") it.name = "Skill ${n++}" }
    }

    private fun move(i: Int, dir: Int) {
        saveAll()
        val j = i + dir
        if (j < 0 || j >= cfg.points.size) return
        val tmp = cfg.points[i]
        cfg.points[i] = cfg.points[j]
        cfg.points[j] = tmp
        renumberSkills(cfg)
        cfg.save(this)
        buildList()
    }

    private fun removeAt(i: Int) {
        saveAll()
        if (i >= cfg.points.size) return
        cfg.points.removeAt(i)
        renumberSkills(cfg)
        cfg.save(this)
        buildList()
    }

    private fun buildProfiles() {
        profBox.removeAllViews()
        val names = Config.profiles(this)
        if (names.isEmpty()) {
            profBox.addView(TextView(this).apply {
                text = "Kayıtlı profil yok."
                setTextColor(MUTED)
            })
            return
        }
        for (n in names) {
            val r = row()
            r.addView(TextView(this).apply {
                text = n
                textSize = 15f
                setTextColor(Color.WHITE)
            }, weight1())
            r.addView(smallButton("Yükle") {
                if (Config.loadProfile(this, n)) {
                    refresh(); toast("'$n' yüklendi")
                } else toast("Profil okunamadı")
            })
            r.addView(smallButton("Sil") {
                AlertDialog.Builder(this)
                    .setMessage("'$n' profili silinsin mi?")
                    .setPositiveButton("Sil") { _, _ -> Config.deleteProfile(this, n); buildProfiles() }
                    .setNegativeButton("Vazgeç", null)
                    .show()
            })
            profBox.addView(r)
        }
    }

    /** Ekrandaki degerleri en guncel kayda yazar */
    private fun saveAll() {
        if (alanEdits.isEmpty()) return
        val latest = Config.load(this)
        for ((a, e) in alanEdits) {
            val v = e.text.toString().trim().toIntOrNull() ?: a.get(latest)
            a.set(latest, v.coerceIn(a.min, a.max))
        }
        if (latest.maxDelay <= latest.minDelay) latest.maxDelay = latest.minDelay + 1
        if (latest.pauseMax < latest.pauseMin) latest.pauseMax = latest.pauseMin
        if (latest.tgtMax < latest.tgtMin) latest.tgtMax = latest.tgtMin
        if (latest.skMax < latest.skMin) latest.skMax = latest.skMin

        val sameList = latest.points.size >= cfg.points.size &&
            cfg.points.indices.all { latest.points[it].x == cfg.points[it].x && latest.points[it].y == cfg.points[it].y }
        if (sameList) {
            for ((i, e) in cdEdits) {
                val v = e.text.toString().replace(',', '.').toFloatOrNull() ?: continue
                if (latest.points[i].type == "skill") latest.points[i].cd = v.coerceIn(0f, 3600f)
            }
            for ((i, cb) in onChecks) {
                if (latest.points[i].type == "skill") latest.points[i].on = cb.isChecked
            }
        }
        latest.save(this)
        cfg = latest
    }

    // ================= Ekran yakalama izni =================

    /** Android 14+: tam ekran (tek uygulama penceresi degil) — HyperOS'ta oyun kaydi icin sart */
    @Suppress("DEPRECATION")
    private fun ekranIzinIntent(): Intent {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
    }

    /** Video: ikinci projeksiyon YOK. Capture aciksa ayni karelerden kayit; degilse tek izin. */
    private fun handleVideoIstek(i: Intent?) {
        if (i?.getBooleanExtra("video_kayit_iste", false) != true) return
        i.removeExtra("video_kayit_iste")
        val cap = CaptureService.instance
        if (cap != null && ScreenSampler.running) {
            val ok = cap.videoBaslat()
            MacroService.instance?.videoKayitSonucu(ok)
            moveTaskToBack(true)
            return
        }
        videoSonra = true
        toast("Açılan pencerede \"Tüm ekran\"ı seç — makro ve video aynı izni kullanır")
        askCapture()
    }

    @Suppress("DEPRECATION")
    private fun askCapture() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 5)
        }
        if (ScreenSampler.running) {
            if (videoSonra) {
                videoSonra = false
                val ok = CaptureService.instance?.videoBaslat() ?: false
                MacroService.instance?.videoKayitSonucu(ok)
            }
            afterCapture(true)
            return
        }
        startActivityForResult(ekranIzinIntent(), 100)
    }

    private fun afterCapture(ok: Boolean) {
        if (autoMode) {
            autoMode = false
            moveTaskToBack(true)
            return
        }
        if (videoSonra) {
            moveTaskToBack(true)
            return
        }
        if (launchAfterCapture) {
            launchAfterCapture = false
            if (ok) {
                startBtn.postDelayed({ launchGameAndStart() }, 700)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 100) return
        if (resultCode != RESULT_OK || data == null) {
            toast("İzin verilmedi")
            if (videoSonra) {
                videoSonra = false
                MacroService.instance?.videoKayitSonucu(false)
            }
            afterCapture(false)
            return
        }
        val isteVideo = videoSonra
        startForegroundService(
            Intent(this, CaptureService::class.java)
                .putExtra("code", resultCode)
                .putExtra("data", data)
                .putExtra("video", isteVideo)
        )
        videoSonra = false
        if (isteVideo) {
            startBtn.postDelayed({ moveTaskToBack(true) }, 400)
        }
        afterCapture(true)
    }
}
