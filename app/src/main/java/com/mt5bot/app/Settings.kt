package com.mt5bot.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

object Settings {

    fun defaults(): JSONObject = JSONObject()
        .put("symbols", "XAUUSD")
        .put("strategy", "ema_rsi")
        .put("allow_stacking", false)
        .put("timeframe", "M1")
        .put("risk_mode", "percent")
        .put("risk_percent", 1.0)
        .put("fixed_lot", 0.01)
        .put("positions_per_signal", 1)
        .put("max_open_positions", 15)
        .put("max_symbols_open", 3)
        .put("sl_atr_mult", 1.5)
        .put("tp_atr_mult", 2.0)
        .put("breakeven_atr", 1.0)
        .put("trailing_atr", 1.0)
        .put("close_on_opposite", true)
        .put("max_daily_loss_percent", 5.0)
        .put("max_drawdown_percent", 20.0)
        .put("max_spread_points", 60)
        .put("session_start", "00:00")
        .put("session_end", "23:59")
        .put("allow_real_account", false)
        .put("ema_fast", 9)
        .put("ema_slow", 21)
        .put("ema_trend", 50)
        .put("rsi_overbought", 70.0)
        .put("rsi_oversold", 30.0)
        .put("magic", 20261116)

    /** 28 pair forex mayor & minor + emas, dipakai saat Pair = ALL. */
    val ALL_PAIRS = listOf(
        "EURUSD", "GBPUSD", "USDJPY", "USDCHF", "USDCAD", "AUDUSD", "NZDUSD",
        "EURGBP", "EURJPY", "EURCHF", "EURCAD", "EURAUD", "EURNZD",
        "GBPJPY", "GBPCHF", "GBPCAD", "GBPAUD", "GBPNZD",
        "AUDJPY", "AUDCHF", "AUDCAD", "AUDNZD",
        "NZDJPY", "NZDCHF", "NZDCAD", "CADJPY", "CADCHF", "CHFJPY",
        "XAUUSD"
    )

    val TIMEFRAMES = mapOf(
        "M1" to 60L, "M5" to 300L, "M15" to 900L, "M30" to 1800L, "H1" to 3600L, "H4" to 14400L
    )
    val TF_API = mapOf(
        "M1" to "1m", "M5" to "5m", "M15" to "15m", "M30" to "30m", "H1" to "1h", "H4" to "4h"
    )

    fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences("mt5bot", Context.MODE_PRIVATE)

    fun load(c: Context): JSONObject {
        val d = defaults()
        val saved = prefs(c).getString("settings", null) ?: return d
        try {
            val u = JSONObject(saved)
            for (k in u.keys()) if (d.has(k)) d.put(k, u.get(k))
        } catch (_: Exception) {
        }
        return d
    }

    /** Validasi & simpan. Return pesan error, atau null jika sukses. */
    fun validateAndSave(c: Context, input: JSONObject): String? {
        val cur = load(c)
        val def = defaults()
        try {
            for (k in input.keys()) {
                if (!def.has(k) || k == "magic") continue
                val raw = input.get(k)
                val v: Any = when (def.get(k)) {
                    is Boolean -> if (raw is Boolean) raw else raw.toString().lowercase() in setOf("true", "1", "on")
                    is Int -> raw.toString().trim().toDouble().toInt()
                    is Double -> raw.toString().trim().toDouble()
                    else -> raw.toString().trim()
                }
                cur.put(k, v)
            }
        } catch (e: Exception) {
            return "Ada isian yang bukan angka"
        }
        val syms = cur.getString("symbols").split(",", " ", ";").map { it.trim() }.filter { it.isNotEmpty() }
        if (syms.isEmpty()) return "Pair wajib diisi (atau ALL untuk semua pair)"
        cur.put("symbols", if (syms.any { it.equals("ALL", true) }) "ALL" else syms.joinToString(",") { it.uppercase() })
        if (cur.getInt("max_symbols_open") < 1) return "Maks pair bersamaan minimal 1"
        if (cur.getString("timeframe") !in TIMEFRAMES) return "Timeframe tidak valid"
        if (cur.getString("risk_mode") !in setOf("percent", "fixed")) return "Mode lot tidak valid"
        if (cur.getString("strategy") !in setOf("ema_rsi", "scalp")) return "Strategi tidak valid"
        val risk = cur.getDouble("risk_percent")
        if (risk <= 0 || risk > 20) return "Risiko per sinyal harus 0–20%"
        if (cur.getDouble("fixed_lot") <= 0) return "Lot harus > 0"
        val pps = cur.getInt("positions_per_signal")
        if (pps < 1 || pps > 50) return "Posisi per sinyal harus 1–50"
        if (cur.getInt("max_open_positions") > 200) return "Maks posisi terbuka maksimal 200"
        if (cur.getInt("max_open_positions") < 1) return "Maks posisi minimal 1"
        if (cur.getDouble("sl_atr_mult") <= 0) return "SL harus > 0 (bot selalu memakai stop loss)"
        if (cur.getDouble("tp_atr_mult") < 0) return "TP tidak boleh negatif"
        if (cur.getDouble("max_daily_loss_percent") <= 0) return "Maks rugi harian harus > 0"
        if (cur.getDouble("max_drawdown_percent") <= 0) return "Maks drawdown harus > 0"
        if (cur.getInt("ema_fast") < 2 || cur.getInt("ema_fast") >= cur.getInt("ema_slow"))
            return "EMA cepat harus lebih kecil dari EMA lambat"
        if (cur.getInt("ema_trend") > 300) return "EMA tren maksimal 300"
        val hhmm = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
        if (!hhmm.matches(cur.getString("session_start")) || !hhmm.matches(cur.getString("session_end")))
            return "Format jam harus HH:MM"
        prefs(c).edit().putString("settings", cur.toString()).apply()
        return null
    }

    fun token(c: Context) = prefs(c).getString("token", "") ?: ""
    fun accountId(c: Context) = prefs(c).getString("account_id", "") ?: ""

    fun saveCredentials(c: Context, token: String, accountId: String) {
        val e = prefs(c).edit()
        if (token.isNotBlank()) e.putString("token", token.trim())
        e.putString("account_id", accountId.trim()).apply()
    }
}
