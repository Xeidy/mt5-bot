package com.mt5bot.app

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class Candle(val time: Long, val open: Double, val high: Double, val low: Double, val close: Double)

data class Signal(val side: String?, val atr: Double, val info: JSONObject)

/**
 * Strategi "EMA Trend + RSI" (sama dengan versi PC):
 *  - Buy : EMA cepat memotong ke atas EMA lambat, harga > EMA tren, 50 < RSI < overbought
 *  - Sell: kebalikannya
 * Hanya memakai candle yang sudah tutup.
 */
object Strategy {

    fun ema(values: List<Double>, period: Int): DoubleArray {
        val k = 2.0 / (period + 1)
        val out = DoubleArray(values.size)
        for (i in values.indices) {
            out[i] = if (i == 0) values[0] else values[i] * k + out[i - 1] * (1 - k)
        }
        return out
    }

    fun rsi(closes: List<Double>, period: Int = 14): DoubleArray {
        val out = DoubleArray(closes.size) { 50.0 }
        var gain = 0.0
        var loss = 0.0
        for (i in 1 until closes.size) {
            val ch = closes[i] - closes[i - 1]
            val g = max(ch, 0.0)
            val l = max(-ch, 0.0)
            if (i <= period) {
                gain += g / period
                loss += l / period
            } else {
                gain = (gain * (period - 1) + g) / period
                loss = (loss * (period - 1) + l) / period
            }
            if (i >= period) out[i] = if (loss == 0.0) 100.0 else 100 - 100 / (1 + gain / loss)
        }
        return out
    }

    fun atr(candles: List<Candle>, period: Int = 14): Double {
        var a = 0.0
        var prevClose = Double.NaN
        candles.forEachIndexed { i, c ->
            val tr = if (prevClose.isNaN()) c.high - c.low
            else max(c.high - c.low, max(abs(c.high - prevClose), abs(c.low - prevClose)))
            prevClose = c.close
            a = if (i == 0) tr else (a * (period - 1) + tr) / period
        }
        return a
    }

    fun signal(closed: List<Candle>, cfg: JSONObject): Signal {
        val fastP = cfg.getInt("ema_fast")
        val slowP = cfg.getInt("ema_slow")
        val trendP = cfg.getInt("ema_trend")
        val need = max(max(trendP, slowP), 30) + 5
        if (closed.size < need) {
            return Signal(null, 0.0, JSONObject().put("info", "data candle belum cukup"))
        }
        val closes = closed.map { it.close }
        val f = ema(closes, fastP)
        val s = ema(closes, slowP)
        val t = ema(closes, trendP)
        val r = rsi(closes)
        val a = atr(closed)
        val n = closes.size - 1

        val info = JSONObject()
            .put("ema_fast", round(f[n], 5))
            .put("ema_slow", round(s[n], 5))
            .put("ema_trend", round(t[n], 5))
            .put("rsi", round(r[n], 1))
            .put("atr", round(a, 5))

        val crossUp = f[n - 1] <= s[n - 1] && f[n] > s[n]
        val crossDn = f[n - 1] >= s[n - 1] && f[n] < s[n]
        val ob = cfg.getDouble("rsi_overbought")
        val os = cfg.getDouble("rsi_oversold")

        if (crossUp && closes[n] > t[n] && r[n] > 50 && r[n] < ob) return Signal("buy", a, info)
        if (crossDn && closes[n] < t[n] && r[n] < 50 && r[n] > os) return Signal("sell", a, info)
        return Signal(null, a, info)
    }

    /** Lot agar kerugian saat SL kena ≈ riskMoney. 0 jika di bawah lot minimum. */
    fun lotForRisk(
        riskMoney: Double, slDistance: Double, tickSize: Double, tickValue: Double,
        volMin: Double, volMax: Double, volStep: Double
    ): Double {
        if (slDistance <= 0 || tickSize <= 0 || tickValue <= 0 || volStep <= 0) return 0.0
        val lossPerLot = slDistance / tickSize * tickValue
        val steps = floor(riskMoney / lossPerLot / volStep + 1e-9)
        var lot = BigDecimal.valueOf(volStep).multiply(BigDecimal.valueOf(steps)).toDouble()
        lot = min(lot, volMax)
        return if (lot >= volMin) normLot(lot, volStep) else 0.0
    }

    fun normLot(lot: Double, step: Double): Double {
        val scale = max(0, BigDecimal.valueOf(step).stripTrailingZeros().scale())
        return BigDecimal.valueOf(lot).setScale(scale, RoundingMode.DOWN).toDouble()
    }

    fun round(v: Double, digits: Int): Double =
        if (v.isNaN() || v.isInfinite()) v
        else BigDecimal.valueOf(v).setScale(digits, RoundingMode.HALF_UP).toDouble()
}
