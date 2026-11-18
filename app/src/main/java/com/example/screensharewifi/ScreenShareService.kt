package com.example.screensharewifi

import android.app.*
import android.content.*
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.hardware.display.DisplayManager
import android.os.*
import java.io.*
import java.net.*
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class ScreenShareService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var server: ServerSocket? = null
    private val clients = java.util.concurrent.CopyOnWriteArrayList<Socket>()
    private val pool = Executors.newCachedThreadPool()
    private var running = false

    override fun onCreate() { super.onCreate(); createChannel() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_STICKY
        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: return START_NOT_STICKY
        val data = intent.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
        startForeground(1001, notification())
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(resultCode, data)
        projection?.registerCallback(object: MediaProjection.Callback(){ override fun onStop(){ stopSelf() } }, Handler(Looper.getMainLooper()))
        startCapture()
        startServer()
        running = true
        return START_STICKY
    }

    private fun startCapture() {
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader!!.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]; val buffer = plane.buffer
                val pixelStride = plane.pixelStride; val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * w
                val bitmap = android.graphics.Bitmap.createBitmap(w + rowPadding / pixelStride, h, android.graphics.Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)
                val cropped = if (bitmap.width != w) android.graphics.Bitmap.createBitmap(bitmap, 0, 0, w, h) else bitmap
                val out = ByteArrayOutputStream(); cropped.compress(android.graphics.Bitmap.CompressFormat.JPEG, 55, out)
                val jpeg = out.toByteArray()
                if (cropped !== bitmap) cropped.recycle(); bitmap.recycle()
                broadcastFrame(jpeg)
            } catch (_: Exception) { } finally { image.close() }
        }, Handler(Looper.getMainLooper()))
        virtualDisplay = projection?.createVirtualDisplay("WiFiScreenShare", w, h, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null)
    }

    private fun startServer() {
        pool.execute {
            try {
                server = ServerSocket(8080, 20, InetAddress.getByName("0.0.0.0"))
                while (running || !Thread.currentThread().isInterrupted) {
                    val s = server!!.accept(); clients.add(s); pool.execute { handleClient(s) }
                }
            } catch (_: Exception) { }
        }
    }

    private fun handleClient(s: Socket) {
        try {
            s.soTimeout = 5000
            var line = input.readLine() ?: return
            val path = line.split(" ").getOrNull(1) ?: "/"
            while (input.readLine()?.isNotEmpty() == true) { }
            val out = s.getOutputStream()
            if (path == "/stream.mjpg") {
                val header = "HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n"
                out.write(header.toByteArray()); out.flush()
                while (!s.isClosed && running) Thread.sleep(1000)
            } else {
                val html = """<!doctype html><html dir="rtl"><meta name="viewport" content="width=device-width,initial-scale=1"><title>اشتراک صفحه</title><style>body{margin:0;background:#111;color:#fff;font-family:sans-serif;text-align:center}img{max-width:100vw;max-height:100vh;object-fit:contain}</style><img src="/stream.mjpg"><p>اشتراک صفحه زنده</p></html>"""
                val body = html.toByteArray()
                out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); out.write(body); out.flush()
                s.close()
            }
        } catch (_: Exception) { } finally { clients.remove(s); try { s.close() } catch (_: Exception) {} }
    }

    private fun broadcastFrame(jpeg: ByteArray) {
        val packet = ByteArrayOutputStream().apply {
            write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n".toByteArray()); write(jpeg); write("\r\n".toByteArray())
        }.toByteArray()
        for (s in clients) try { s.getOutputStream().write(packet); s.getOutputStream().flush() } catch (_: Exception) { clients.remove(s); try { s.close() } catch (_: Exception) {} }
    }

    private fun notification(): Notification {
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "screen_share").setContentTitle("اشتراک صفحه فعال است").setContentText("http://${localIp()}:8080").setSmallIcon(android.R.drawable.ic_menu_view).setContentIntent(pending).setOngoing(true).build()
    }

    private fun createChannel() { if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("screen_share", "اشتراک صفحه", NotificationManager.IMPORTANCE_LOW)) }
    private fun localIp(): String = try { NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(":") == false }?.hostAddress ?: "IP" } catch (_: Exception) { "IP" }

    override fun onDestroy() { running = false; try { server?.close() } catch (_: Exception) {}; clients.forEach { try { it.close() } catch (_: Exception) {} }; virtualDisplay?.release(); reader?.close(); projection?.stop(); pool.shutdownNow(); super.onDestroy() }
    override fun onBind(intent: Intent?) = null
}
