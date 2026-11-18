package com.example.screensharewifi

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.*
import java.net.NetworkInterface
import android.graphics.Color

class MainActivity : Activity() {
    private lateinit var ipText: TextView
    private val REQ_CAPTURE = 10
    private val REQ_NOTIF = 11

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32,40,32,32) }
        val title = TextView(this).apply { text = "اشتراک صفحه از طریق Wi‑Fi"; textSize = 24f; setTextColor(Color.BLACK) }
        ipText = TextView(this).apply { textSize = 16f; setPadding(0,24,0,24) }
        val start = Button(this).apply { text = "شروع اشتراک صفحه"; setOnClickListener { requestCapture() } }
        val stop = Button(this).apply { text = "توقف اشتراک"; setOnClickListener { stopService(Intent(this@MainActivity, ScreenShareService::class.java)); refresh() } }
        val note = TextView(this).apply { text = "پس از شروع، آدرس http://IP:8080 را در مرورگر دستگاه دیگر باز کنید. سرویس به‌صورت Foreground اجرا می‌شود."; setPadding(0,24,0,0) }
        box.addView(title); box.addView(ipText); box.addView(start); box.addView(stop); box.addView(note)
        setContentView(box); refresh()
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), REQ_NOTIF)
    }

    private fun refresh() { ipText.text = "IP محلی: ${localIp()}\nپورت: 8080" }

    private fun requestCapture() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Deprecated API used intentionally for compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, ScreenShareService::class.java).apply { putExtra("resultCode", resultCode); putExtra("data", data) }
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        }
    }

    private fun localIp(): String {
        return try {
            NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(":") == false }?.hostAddress ?: "پیدا نشد"
        } catch (_: Exception) { "پیدا نشد" }
    }
}
