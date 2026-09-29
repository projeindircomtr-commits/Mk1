package com.kanka.makro

import android.content.ContentValues
import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.MediaStore
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Ekran videosunu (H.264 / MP4) kaydeder.
 *
 * Android 14+ / HyperOS: ayni anda TEK MediaProjection + TEK VirtualDisplay serbest.
 * Ikinci ekran izni (eski yontem) birincisini onStop ile oldurur — makro kor kalir,
 * video de acilmaz. Bu yuzden VideoKaydedici KENDI projeksiyonunu acmaz; CaptureService
 * ImageReader karelerini (botun zaten okudugu ayni goruntu) H.264'e kodlar.
 */
object VideoKaydedici {

    @Volatile var kayitta = false
        private set

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerBasladi = false
    private var pfd: android.os.ParcelFileDescriptor? = null
    private var cikanUri: Uri? = null
    private var cikanDosya: File? = null
    private var baslangicMs = 0L
    private var startNs = 0L
    private var encW = 0
    private var encH = 0
    private var yuv: ByteArray = ByteArray(0)
    @Volatile private var busy = false
    @Volatile private var drainDevam = false
    @Volatile private var appCtx: Context? = null

    private val thread = HandlerThread("video-kayit").apply { start() }
    private val handler = Handler(thread.looper)

    /** CaptureService hazir karelerden kayit. w/h ImageReader boyutu (cift sayi). */
    fun baslat(ctx: Context, w: Int, h: Int): Boolean {
        if (kayitta) return true
        val ww = (w and 1.inv()).coerceAtLeast(2)
        val hh = (h and 1.inv()).coerceAtLeast(2)
        val app = ctx.applicationContext
        if (android.os.Looper.myLooper() == handler.looper) {
            return try {
                baslatIc(app, ww, hh)
                true
            } catch (e: Exception) {
                temizle()
                false
            }
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        var ok = false
        handler.post {
            ok = try {
                baslatIc(app, ww, hh)
                true
            } catch (e: Exception) {
                temizle()
                false
            }
            latch.countDown()
        }
        try {
            latch.await(4, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
        return ok
    }

    private fun baslatIc(ctx: Context, w: Int, h: Int) {
        appCtx = ctx
        val pikseller = w.toLong() * h.toLong()
        val bitRate = when {
            pikseller >= 1920L * 1080L -> 8_000_000
            pikseller >= 1280L * 720L -> 5_000_000
            else -> 2_800_000
        }
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 20)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val enc = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        } catch (e: Exception) {
            throw e
        }
        try {
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            // Bazi Xiaomi/HyperOS kodlayicilari Flexible ister
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        enc.start()
        codec = enc
        encW = w
        encH = h
        yuv = ByteArray(w * h * 3 / 2)

        val ad = "Projeindirpedal_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date()) + ".mp4"
        val (mx, uri, dosya, pf) = muxerOlustur(ctx, ad)
        muxer = mx
        cikanUri = uri
        cikanDosya = dosya
        pfd = pf
        muxerBasladi = false
        trackIndex = -1
        startNs = SystemClock.elapsedRealtimeNanos()
        baslangicMs = SystemClock.elapsedRealtime()
        drainDevam = true
        kayitta = true
        busy = false
        handler.post(drainGorevi)
    }

    /** API 29+: MediaStore uzerinden dogrudan Movies/Projeindirpedal klasorune (Galeri'de gorunur) */
    private fun muxerOlustur(ctx: Context, ad: String): DortluResult {
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, ad)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Projeindirpedal")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
                ?: throw IllegalStateException("MediaStore insert basarisiz")
            val pf = ctx.contentResolver.openFileDescriptor(uri, "rw")
                ?: throw IllegalStateException("Dosya tanimlayici alinamadi")
            val mx = MediaMuxer(pf.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            return DortluResult(mx, uri, null, pf)
        } else {
            val klasor = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Projeindirpedal")
            if (!klasor.exists()) klasor.mkdirs()
            val dosya = File(klasor, ad)
            val mx = MediaMuxer(dosya.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            return DortluResult(mx, null, dosya, null)
        }
    }

    private data class DortluResult(
        val muxer: MediaMuxer, val uri: Uri?, val dosya: File?, val pfd: android.os.ParcelFileDescriptor?
    )

    /**
     * CaptureService ImageReader karesi. Image kapatilmaz; pikseller kopyalanir.
     * Encoder mesgulse kare atilir (kasma olmasin).
     */
    fun kareYaz(img: Image) {
        if (!kayitta || busy) return
        val w = img.width
        val height = img.height
        if (w < 2 || height < 2) return
        val rgba = kopyaRgba(img) ?: return
        busy = true
        val ww = w
        val hh = height
        handler.post {
            try {
                encodeRgba(rgba, ww, hh)
            } catch (_: Exception) {
                bitir()
            } finally {
                busy = false
            }
        }
    }

    private fun kopyaRgba(img: Image): ByteArray? {
        return try {
            val plane = img.planes[0]
            val buf = plane.buffer.duplicate()
            val w = img.width
            val h = img.height
            val rs = plane.rowStride
            val ps = plane.pixelStride
            val out = ByteArray(w * h * 4)
            if (ps == 4 && rs == w * 4) {
                buf.position(0)
                val n = (w * h * 4).coerceAtMost(buf.remaining())
                buf.get(out, 0, n)
            } else {
                val row = ByteArray(rs)
                for (y in 0 until h) {
                    buf.position(y * rs)
                    val n = rs.coerceAtMost(buf.remaining())
                    if (n <= 0) break
                    buf.get(row, 0, n)
                    var di = y * w * 4
                    var si = 0
                    for (x in 0 until w) {
                        if (si + 2 >= row.size) break
                        out[di] = row[si]
                        out[di + 1] = row[si + 1]
                        out[di + 2] = row[si + 2]
                        out[di + 3] = if (si + 3 < row.size) row[si + 3] else 0xFF.toByte()
                        di += 4
                        si += ps
                    }
                }
            }
            out
        } catch (_: Exception) {
            null
        }
    }

    private fun encodeRgba(rgba: ByteArray, w: Int, h: Int) {
        val enc = codec ?: return
        if (!kayitta) return
        val cw = if (w == encW) w else encW
        val ch = if (h == encH) h else encH
        if (yuv.size != cw * ch * 3 / 2) yuv = ByteArray(cw * ch * 3 / 2)
        rgbaToNv12(rgba, w, h, yuv, cw, ch)
        val idx = enc.dequeueInputBuffer(8_000)
        if (idx < 0) {
            drain()
            return
        }
        val inBuf = enc.getInputBuffer(idx) ?: return
        inBuf.clear()
        val n = yuv.size.coerceAtMost(inBuf.remaining())
        inBuf.put(yuv, 0, n)
        val pts = (SystemClock.elapsedRealtimeNanos() - startNs) / 1000
        enc.queueInputBuffer(idx, 0, n, pts, 0)
        drain()
    }

    /** RGBA8888 -> NV12 (YUV420 semi-planar). Boyut farkliysa en-boy kirpar. */
    private fun rgbaToNv12(rgba: ByteArray, sw: Int, sh: Int, nv12: ByteArray, dw: Int, dh: Int) {
        val w = dw.coerceAtMost(sw) and 1.inv()
        val h = dh.coerceAtMost(sh) and 1.inv()
        val ySize = dw * dh
        for (y in 0 until h) {
            var si = y * sw * 4
            val yRow = y * dw
            for (x in 0 until w) {
                val r = rgba[si].toInt() and 0xff
                val g = rgba[si + 1].toInt() and 0xff
                val b = rgba[si + 2].toInt() and 0xff
                var Y = (77 * r + 150 * g + 29 * b) shr 8
                if (Y < 0) Y = 0 else if (Y > 255) Y = 255
                nv12[yRow + x] = Y.toByte()
                si += 4
            }
        }
        var uvi = ySize
        var yy = 0
        while (yy < h) {
            var si = yy * sw * 4
            var x = 0
            while (x < w) {
                val r = rgba[si].toInt() and 0xff
                val g = rgba[si + 1].toInt() and 0xff
                val b = rgba[si + 2].toInt() and 0xff
                var U = (((-43 * r - 85 * g + 128 * b) shr 8) + 128)
                var V = (((128 * r - 107 * g - 21 * b) shr 8) + 128)
                if (U < 0) U = 0 else if (U > 255) U = 255
                if (V < 0) V = 0 else if (V > 255) V = 255
                if (uvi + 1 < nv12.size) {
                    nv12[uvi] = U.toByte()
                    nv12[uvi + 1] = V.toByte()
                }
                uvi += 2
                si += 8
                x += 2
            }
            yy += 2
        }
    }

    private val drainGorevi = object : Runnable {
        override fun run() {
            if (!drainDevam) return
            try {
                drain()
            } catch (_: Exception) {
                bitir()
                return
            }
            if (drainDevam) handler.postDelayed(this, 40)
        }
    }

    private fun drain() {
        val enc = codec ?: return
        val mx = muxer ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = enc.dequeueOutputBuffer(info, 0)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerBasladi) {
                        trackIndex = mx.addTrack(enc.outputFormat)
                        mx.start()
                        muxerBasladi = true
                    }
                }
                idx >= 0 -> {
                    val buf: ByteBuffer? = enc.getOutputBuffer(idx)
                    if (buf != null && info.size > 0 && muxerBasladi) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        mx.writeSampleData(trackIndex, buf, info)
                    }
                    enc.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        bitir()
                        return
                    }
                }
                else -> return
            }
        }
    }

    fun durdur(ctx: Context) {
        if (!kayitta) return
        appCtx = ctx.applicationContext
        handler.post {
            try {
                val enc = codec
                if (enc != null) {
                    val idx = enc.dequeueInputBuffer(50_000)
                    if (idx >= 0) {
                        val pts = (SystemClock.elapsedRealtimeNanos() - startNs) / 1000
                        enc.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    }
                    drain()
                }
            } catch (_: Exception) {
            }
            bitir()
        }
    }

    private fun bitir() {
        if (!kayitta && codec == null) return
        drainDevam = false
        kayitta = false
        busy = false
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        try { if (muxerBasladi) muxer?.stop() } catch (_: Exception) {}
        try { muxer?.release() } catch (_: Exception) {}
        val uri = cikanUri
        val pf = pfd
        try { pf?.close() } catch (_: Exception) {}
        val ctx = appCtx
        if (uri != null && ctx != null) {
            try {
                val cv = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                ctx.contentResolver.update(uri, cv, null, null)
            } catch (_: Exception) {
            }
        } else {
            cikanDosya?.let { f ->
                try {
                    ctx?.sendBroadcast(
                        android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(f))
                    )
                } catch (_: Exception) {
                }
            }
        }
        codec = null
        muxer = null
        pfd = null
        cikanUri = null
        cikanDosya = null
        muxerBasladi = false
        yuv = ByteArray(0)
    }

    fun sureSaniye(): Long =
        if (kayitta) (SystemClock.elapsedRealtime() - baslangicMs) / 1000 else 0

    private fun temizle() {
        drainDevam = false
        kayitta = false
        busy = false
        try { codec?.release() } catch (_: Exception) {}
        try { muxer?.release() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
        codec = null; muxer = null; pfd = null; cikanUri = null; cikanDosya = null
        yuv = ByteArray(0)
    }
}
