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

/** Setup SMC: harga limit entry, SL, TP + keterangan. */
data class SmcSetup(val buy: Boolean, val entry: Double, val sl: Double, val tp: Double, val note: String)

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

    fun signal(closed: List<Candle>, cfg: JSONObject): Signal = when (cfg.optString("strategy")) {
        "scalp" -> scalp(closed, cfg)
        "trend" -> trend(closed, cfg)
        else -> emaRsi(closed, cfg)
    }

    /**
     * Trend Breakout (hasil backtest terbaik):
     *  - Buy : close menembus high tertinggi N candle sebelumnya DAN close > EMA tren (200)
     *  - Sell: close menembus low terendah N candle sebelumnya DAN close < EMA tren
     *  - SL lebar (3 ATR), tanpa TP; profit dikunci oleh trailing stop (5 ATR).
     * Jarang entry, win rate ~33%, tapi rata-rata menang ±2× rata-rata kalah.
     */
    private fun trend(closed: List<Candle>, cfg: JSONObject): Signal {
        val n = cfg.optInt("donchian_n", 55)
        val trendP = cfg.getInt("ema_trend")
        if (closed.size < max(trendP, n) + 20) {
            return Signal(null, 0.0, JSONObject().put("info", "data candle belum cukup"))
        }
        val closes = closed.map { it.close }
        val t = ema(closes, trendP)
        val a = atr(closed)
        val i = closed.size - 1
        var hh = Double.NEGATIVE_INFINITY
        var ll = Double.POSITIVE_INFINITY
        for (k in i - n until i) {
            hh = max(hh, closed[k].high)
            ll = min(ll, closed[k].low)
        }
        val c = closes[i]
        val info = JSONObject()
            .put("high_n", round(hh, 5))
            .put("low_n", round(ll, 5))
            .put("ema_trend", round(t[i], 5))
            .put("atr", round(a, 5))
        if (c > hh && c > t[i]) return Signal("buy", a, info)
        if (c < ll && c < t[i]) return Signal("sell", a, info)
        return Signal(null, a, info)
    }

    /**
     * Mode scalping agresif: entry hampir di setiap candle mengikuti momentum terakhir.
     *  - Buy : candle terakhir naik (close > open) dan close di atas EMA cepat
     *  - Sell: candle terakhir turun dan close di bawah EMA cepat
     */
    private fun scalp(closed: List<Candle>, cfg: JSONObject): Signal {
        val fastP = cfg.getInt("ema_fast")
        if (closed.size < max(fastP, 20) + 2) {
            return Signal(null, 0.0, JSONObject().put("info", "data candle belum cukup"))
        }
        val closes = closed.map { it.close }
        val f = ema(closes, fastP)
        val r = rsi(closes)
        val a = atr(closed)
        val n = closes.size - 1
        val last = closed[n]
        val info = JSONObject()
            .put("ema_fast", round(f[n], 5))
            .put("rsi", round(r[n], 1))
            .put("atr", round(a, 5))
        if (last.close > last.open && last.close > f[n]) return Signal("buy", a, info)
        if (last.close < last.open && last.close < f[n]) return Signal("sell", a, info)
        return Signal(null, a, info)
    }

    private fun emaRsi(closed: List<Candle>, cfg: JSONObject): Signal {
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

    /**
     * SMC / ICT: Liquidity sweep -> Displacement (BOS) -> Fair Value Gap.
     * Dipanggil untuk candle terakhir yang sudah tutup (indeks i):
     *  Bullish: dalam 12 candle terakhir ada candle yang menembus LOW hari sebelumnya (sweep),
     *           candle i-1 bullish besar (body >= k×ATR) dan close-nya menembus high 10 candle
     *           sebelumnya (BOS), lalu ada gap: low[i] > high[i-2] (FVG).
     *  Entry : Buy Limit di tengah FVG. SL: di bawah low candle i-2 / i-1 − 0.1 ATR. TP: RR × risiko.
     *  Bearish kebalikannya. Hanya di killzone London (07–10 UTC) & New York (12–15 UTC).
     */
    fun smc(closed: List<Candle>, cfg: JSONObject): Pair<SmcSetup?, JSONObject> {
        val info = JSONObject()
        val n = closed.size
        if (n < 120) return Pair(null, info.put("info", "data candle belum cukup"))
        val i = n - 1
        val a = atr(closed)
        info.put("atr", round(a, 5))
        val k = cfg.optDouble("smc_impulse_atr", 1.5)
        val rr = cfg.optDouble("smc_rr", 3.0)
        val hour = java.time.Instant.ofEpochSecond(closed[i].time).atZone(java.time.ZoneOffset.UTC).hour
        if (cfg.optBoolean("smc_killzone", true) && !((hour in 7..9) || (hour in 12..14))) {
            return Pair(null, info.put("info", "di luar killzone"))
        }
        // high/low hari sebelumnya (UTC) untuk tiap candle
        val dayOf = closed.map { it.time / 86400 }
        val dayHigh = HashMap<Long, Double>(); val dayLow = HashMap<Long, Double>()
        for (j in 0 until n) {
            val d = dayOf[j]
            dayHigh[d] = max(dayHigh[d] ?: Double.NEGATIVE_INFINITY, closed[j].high)
            dayLow[d] = min(dayLow[d] ?: Double.POSITIVE_INFINITY, closed[j].low)
        }
        var sweptLow = false; var sweptHigh = false
        for (j in max(0, i - 11)..i) {
            val pdh = dayHigh[dayOf[j] - 1]; val pdl = dayLow[dayOf[j] - 1]
            if (pdl != null && closed[j].low < pdl) sweptLow = true
            if (pdh != null && closed[j].high > pdh) sweptHigh = true
        }
        var hh = Double.NEGATIVE_INFINITY; var ll = Double.POSITIVE_INFINITY
        for (j in i - 11..i - 2) { hh = max(hh, closed[j].high); ll = min(ll, closed[j].low) }
        val c0 = closed[i]; val c1 = closed[i - 1]; val c2 = closed[i - 2]
        val body = abs(c1.close - c1.open)
        info.put("sweep_low", sweptLow).put("sweep_high", sweptHigh)

        if (sweptLow && c0.low - c2.high >= 0.1 * a && c1.close > c1.open && body >= k * a && c1.close > hh) {
            val entry = (c0.low + c2.high) / 2
            val sl = min(c2.low, c1.low) - 0.1 * a
            val risk = entry - sl
            if (risk > 0) return Pair(SmcSetup(true, entry, sl, entry + rr * risk,
                "Sweep low → BOS → FVG bullish"), info)
        }
        if (sweptHigh && c2.low - c0.high >= 0.1 * a && c1.close < c1.open && body >= k * a && c1.close < ll) {
            val entry = (c0.high + c2.low) / 2
            val sl = max(c2.high, c1.high) + 0.1 * a
            val risk = sl - entry
            if (risk > 0) return Pair(SmcSetup(false, entry, sl, entry - rr * risk,
                "Sweep high → BOS → FVG bearish"), info)
        }
        return Pair(null, info)
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
