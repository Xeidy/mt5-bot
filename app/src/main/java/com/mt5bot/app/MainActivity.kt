package com.mt5bot.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Engine.init(this)
        window.statusBarColor = 0xFF0B0B0C.toInt()
        window.navigationBarColor = 0xFF0B0B0C.toInt()

        web = WebView(this)
        web.setBackgroundColor(0xFF0B0B0C.toInt())
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webChromeClient = WebChromeClient() // supaya dialog konfirmasi tampil
        web.addJavascriptInterface(Bridge(this), "Bot")
        web.loadUrl("file:///android_asset/index.html")
        setContentView(web)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        Engine.uiVisible = true
        Engine.poke()
    }

    override fun onPause() {
        Engine.uiVisible = false
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Kembali ke home tanpa mematikan bot
        moveTaskToBack(true)
    }

    fun batteryExempt(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    @SuppressLint("BatteryLife")
    fun requestBatteryExemption() {
        try {
            startActivity(
                Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}

/** Jembatan JavaScript ↔ Kotlin. Dipanggil dari index.html sebagai window.Bot. */
class Bridge(private val act: MainActivity) {

    @JavascriptInterface
    fun status(): String {
        val s = JSONObject(Engine.statusJson())
        s.put("battery_ok", act.batteryExempt())
        return s.toString()
    }

    @JavascriptInterface
    fun start() = Engine.cmdStart()

    @JavascriptInterface
    fun stop() = Engine.cmdStop()

    @JavascriptInterface
    fun closeAll() = Engine.cmdCloseAll()

    @JavascriptInterface
    fun saveSettings(json: String): String {
        return try {
            val err = Settings.validateAndSave(act, JSONObject(json))
            if (err == null) Engine.reloadSettings()
            err ?: ""
        } catch (e: Exception) {
            "Data tidak valid"
        }
    }

    @JavascriptInterface
    fun saveCredentials(token: String, accountId: String) =
        Engine.cmdCredentials(token, accountId)

    @JavascriptInterface
    fun batteryExemption() = act.runOnUiThread { act.requestBatteryExemption() }
}
