package com.kanka.makro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display

/**
 * Tek MediaProjection + tek VirtualDisplay.
 * Android 14+ / HyperOS ikinci yakalamayi yasaklar: ayni ImageReader karesi
 * hem bota (ScreenSampler) hem video kodlayiciya gider.
 */
class CaptureService : Service() {

    companion object {
        @Volatile var instance: CaptureService? = null
    }

    fun gercekBoyut(): Pair<Int, Int> = realSize()

    private var projection: MediaProjection? = null
    private var vDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val thread = HandlerThread("capture").apply { start() }
    private val handler = Handler(thread.looper)
    private var lastKeep = 0L
    private var lastVideo = 0L
    private var curW = 0
    private var curH = 0
    private var bekleyenVideo = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        startForegroundCompat()
        val videoIste = intent?.getBooleanExtra("video", false) == true
        if (videoIste) bekleyenVideo = true

        if (projection != null) {
            if (bekleyenVideo) {
                handler.post {
                    val ok = videoBaslat()
                    MacroService.instance?.videoKayitSonucu(ok)
                }
            }
            return START_NOT_STICKY
        }
        if (intent == null) return START_NOT_STICKY

        val code = intent.getIntExtra("code", 0)
        val data = getData(intent)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = try {
            mpm.getMediaProjection(code, data)
        } catch (e: Exception) {
            null
        }
        if (p == null) {
            stopSelf()
            if (bekleyenVideo) MacroService.instance?.videoKayitSonucu(false)
            return START_NOT_STICKY
        }
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (VideoKaydedici.kayitta) VideoKaydedici.durdur(applicationContext)
                release()
                stopSelf()
                MacroService.instance?.projeksiyonKesildi()
            }

            override fun onCapturedContentResize(width: Int, height: Int) {
                handler.post {
                    if (projection != null) {
                        val (w, h) = realSize()
                        if (w != curW || h != curH) rebuild()
                    }
                }
            }
        }, handler)

        handler.post {
            createDisplay()
            ScreenSampler.running = true
            MacroService.instance?.ekranYedekDurdur()
            if (bekleyenVideo) {
                val ok = videoBaslat()
                MacroService.instance?.videoKayitSonucu(ok)
            }
        }
        handler.postDelayed(yonKontrol, 1000)
        return START_NOT_STICKY
    }

    fun videoBaslat(): Boolean {
        val r = reader
        if (r == null || projection == null) return false
        bekleyenVideo = false
        val ok = VideoKaydedici.baslat(applicationContext, r.width, r.height)
        guncelleBildirim()
        return ok
    }

    @Suppress("DEPRECATION")
    private fun getData(i: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra("data", Intent::class.java)
        else i.getParcelableExtra("data")

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("cap", "Ekran okuma", NotificationManager.IMPORTANCE_LOW)
        )
        val n = bildirimOlustur()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    private fun bildirimOlustur(): Notification {
        val yazi = if (VideoKaydedici.kayitta) "Makro + video kaydı" else "HP/MP okunuyor"
        return Notification.Builder(this, "cap")
            .setContentTitle("Projeindirpedal")
            .setContentText(yazi)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    private fun guncelleBildirim() {
        try {
            getSystemService(NotificationManager::class.java).notify(1, bildirimOlustur())
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun realSize(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        val d = (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        d.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    /** handler thread'inde calisir */
    private fun createDisplay() {
        val p = projection ?: return
        val (w, h) = realSize()
        curW = w
        curH = h
        val vw = ((w * ScreenSampler.SCALE).toInt() and 1.inv()).coerceAtLeast(2)
        val vh = ((h * ScreenSampler.SCALE).toInt() and 1.inv()).coerceAtLeast(2)

        val r = ImageReader.newInstance(vw, vh, PixelFormat.RGBA_8888, 5)
        r.setOnImageAvailableListener({ rd ->
            val img = try {
                rd.acquireLatestImage()
            } catch (e: Exception) {
                null
            } ?: return@setOnImageAvailableListener
            val now = SystemClock.uptimeMillis()
            if (VideoKaydedici.kayitta && now - lastVideo >= 50) {
                lastVideo = now
                try {
                    VideoKaydedici.kareYaz(img)
                } catch (_: Exception) {
                }
            }
            if (now - lastKeep >= 90) {
                lastKeep = now
                ScreenSampler.offer(img)
            } else {
                img.close()
            }
        }, handler)
        reader = r

        val dpi = resources.displayMetrics.densityDpi
        val vd = vDisplay
        if (vd == null) {
            vDisplay = p.createVirtualDisplay(
                "makro", vw, vh, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, handler
            )
        } else {
            vd.resize(vw, vh, dpi)
            vd.surface = r.surface
        }
    }

    private var sonYenileme = 0L

    private val yonKontrol = object : Runnable {
        override fun run() {
            if (projection == null) return
            val (w, h) = realSize()
            val simdi = SystemClock.uptimeMillis()
            val kesik = simdi - ScreenSampler.lastFrameAt > 2500 && simdi - sonYenileme > 2500
            if (w != curW || h != curH || kesik) {
                sonYenileme = simdi
                rebuild()
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun rebuild() {
        val vid = VideoKaydedici.kayitta
        if (vid) VideoKaydedici.durdur(applicationContext)
        val old = reader
        createDisplay()
        ScreenSampler.clear()
        old?.setOnImageAvailableListener(null, null)
        old?.close()
        if (vid) {
            handler.postDelayed({
                if (projection != null && !VideoKaydedici.kayitta) videoBaslat()
            }, 400)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.postDelayed({
            if (projection == null) return@postDelayed
            val (w, h) = realSize()
            if (w != curW || h != curH) rebuild()
        }, 300)
    }

    private fun release() {
        instance = null
        handler.removeCallbacks(yonKontrol)
        ScreenSampler.running = false
        ScreenSampler.clear()
        vDisplay?.release()
        vDisplay = null
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        reader = null
        projection = null
        bekleyenVideo = false
        guncelleBildirim()
    }

    override fun onDestroy() {
        if (VideoKaydedici.kayitta) VideoKaydedici.durdur(applicationContext)
        handler.post {
            val p = projection
            release()
            try {
                p?.stop()
            } catch (e: Exception) {
            }
        }
        thread.quitSafely()
        super.onDestroy()
    }
}
