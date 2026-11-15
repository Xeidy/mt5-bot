package com.mt5bot.app

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

data class Pos(
    val id: String, val symbol: String, val buy: Boolean, val volume: Double,
    val open: Double, val current: Double, val sl: Double, val tp: Double,
    val profit: Double, val time: String
)

/**
 * Mesin bot. Semua kerja (panggilan MetaApi, logika trading) berjalan di SATU thread
 * worker, jadi tidak ada balapan data. UI hanya membaca snapshot JSON.
 */
object Engine {

    private val exec = Executors.newSingleThreadScheduledExecutor()
    private lateinit var ctx: Context
    private var started = false

    private var cfg: JSONObject = Settings.defaults()
    private var api: MetaApi? = null
    @Volatile private var connected = false
    @Volatile var running = false
        private set
    @Volatile var uiVisible = false
    @Volatile private var status = "Belum terhubung"
    @Volatile private var snapshot = JSONObject()
    private val logs = ArrayDeque<JSONObject>()
    private var lastErr = ""

    private var lastBarIdx = -1L
    private var lastInfo = JSONObject()
    private var atr = 0.0
    private var day: LocalDate? = null
    private var dayStartEquity = 0.0
    private var peakEquity = 0.0
    private var pausedDay: LocalDate? = null
    private var deals = JSONArray()
    private var lastDealsAt = 0L
    private var lastPosCount = -1
    private var spec: JSONObject? = null
    private var specSymbol = ""

    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    // ------------------------------------------------------------------ public
    fun init(c: Context) {
        synchronized(this) {
            if (started) return
            started = true
        }
        ctx = c.applicationContext
        cfg = Settings.load(ctx)
        running = Settings.prefs(ctx).getBoolean("running", false)
        if (running) {
            log("Melanjutkan bot setelah aplikasi dibuka ulang", "warn")
            startService()
        }
        exec.scheduleWithFixedDelay({ tick() }, 0, 3, TimeUnit.SECONDS)
    }

    fun poke() = exec.execute { tick() }

    fun cmdStart() = exec.execute {
        try {
            val a = api ?: throw ApiException(0, "Belum terhubung ke MetaApi")
            val acc = a.accountInfo()
            val demo = acc.optString("type") == "ACCOUNT_TRADE_MODE_DEMO"
            if (!demo && !cfg.getBoolean("allow_real_account"))
                throw ApiException(0, "Akun REAL terdeteksi. Aktifkan 'Izinkan akun real' di Settings bila memang ingin trading uang asli.")
            if (!acc.optBoolean("tradeAllowed", true))
                throw ApiException(0, "Trading tidak diizinkan untuk akun ini (cek investor password?)")
            loadSpec(a, cfg.getString("symbol"), force = true)
            running = true
            Settings.prefs(ctx).edit().putBoolean("running", true).apply()
            pausedDay = null
            peakEquity = acc.optDouble("equity")
            lastBarIdx = -1
            startService()
            log("Bot dimulai: ${cfg.getString("symbol")} ${cfg.getString("timeframe")}", "ok")
            tick()
        } catch (e: Exception) {
            log("Gagal start: ${e.message}", "error")
        }
    }

    fun cmdStop() = exec.execute {
        stopBot("Bot dihentikan (posisi terbuka tetap dibiarkan dengan SL/TP-nya)")
    }

    fun cmdCloseAll() = exec.execute {
        try {
            val n = closeAll("ditutup manual")
            if (n == 0) log("Tidak ada posisi bot yang terbuka")
            lastDealsAt = 0
        } catch (e: Exception) {
            log("Gagal tutup posisi: ${e.message}", "error")
        }
    }

    fun cmdCredentials(token: String, accountId: String) = exec.execute {
        Settings.saveCredentials(ctx, token, accountId)
        api = null
        connected = false
        lastErr = ""
        log("Data akun MetaApi disimpan, menghubungkan…")
        tick()
    }

    fun reloadSettings() = exec.execute {
        cfg = Settings.load(ctx)
        lastBarIdx = -1
        specSymbol = ""
        log("Pengaturan disimpan")
    }

    fun statusJson(): String {
        val s = JSONObject(snapshot.toString())
        s.put("state", status)
        s.put("connected", connected)
        s.put("running", running)
        s.put("settings", JSONObject(Settings.load(ctx).toString()))
        s.put("account_id", Settings.accountId(ctx))
        s.put("has_token", Settings.token(ctx).isNotBlank())
        synchronized(logs) { s.put("logs", JSONArray(logs.take(80))) }
        return s.toString()
    }

    // ------------------------------------------------------------------ helpers
    private fun log(msg: String, level: String = "info") {
        val t = LocalTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).toString()
        synchronized(logs) {
            logs.addFirst(JSONObject().put("t", t).put("level", level).put("msg", msg))
            while (logs.size > 300) logs.removeLast()
        }
    }

    private fun alert(msg: String) {
        try {
            Notifs.alert(ctx, msg)
        } catch (_: Exception) {
        }
    }

    private fun startService() {
        try {
            ctx.startForegroundService(Intent(ctx, BotService::class.java))
        } catch (e: Exception) {
            log("Tidak bisa menjalankan service: ${e.message}", "error")
        }
    }

    private fun stopBot(msg: String) {
        running = false
        Settings.prefs(ctx).edit().putBoolean("running", false).apply()
        ctx.stopService(Intent(ctx, BotService::class.java))
        log(msg, "warn")
    }

    private fun loadSpec(a: MetaApi, symbol: String, force: Boolean = false): JSONObject {
        if (force || spec == null || specSymbol != symbol) {
            try {
                spec = a.specification(symbol)
            } catch (e: ApiException) {
                if (e.code == 404) throw ApiException(404, "Simbol '$symbol' tidak ada di broker ini. Cek nama persisnya di MT5 (mis. XAUUSDm).")
                throw e
            }
            specSymbol = symbol
        }
        return spec!!
    }

    private fun parsePos(o: JSONObject) = Pos(
        id = o.optString("id"),
        symbol = o.optString("symbol"),
        buy = o.optString("type") == "POSITION_TYPE_BUY",
        volume = o.optDouble("volume", 0.0),
        open = o.optDouble("openPrice", 0.0),
        current = o.optDouble("currentPrice", 0.0),
        sl = o.optDouble("stopLoss", 0.0).let { if (it.isNaN()) 0.0 else it },
        tp = o.optDouble("takeProfit", 0.0).let { if (it.isNaN()) 0.0 else it },
        profit = o.optDouble("profit", 0.0) + o.optDouble("swap", 0.0) + o.optDouble("commission", 0.0),
        time = o.optString("time")
    )

    // ------------------------------------------------------------------ loop
    private fun tick() {
        try {
            if (!running && !uiVisible) return
            val token = Settings.token(ctx)
            val id = Settings.accountId(ctx)
            if (token.isBlank() || id.isBlank()) {
                connected = false
                status = "Isi API token & Account ID di tab Akun"
                return
            }
            if (api == null) connect(token, id)
            step()
            lastErr = ""
        } catch (e: ApiException) {
            if (e.code == 401 || e.code == 403) {
                api = null
                connected = false
                status = "Token MetaApi ditolak"
            }
            fail(e.message ?: "Error MetaApi")
        } catch (e: IOException) {
            connected = false
            fail("Masalah internet: ${e.message}")
        } catch (e: Exception) {
            fail("Error: $e")
        }
    }

    private fun fail(msg: String) {
        if (!connected) status = msg.take(120)
        if (msg != lastErr) {
            log(msg, "error")
            lastErr = msg
        }
    }

    private fun connect(token: String, id: String) {
        status = "Menghubungkan ke MetaApi…"
        val a = MetaApi(token, id)
        val acc = a.init()
        val state = acc.optString("state")
        if (state == "UNDEPLOYED") {
            a.deploy()
            log("Mengaktifkan akun di MetaApi (biasanya 1–2 menit)…", "warn")
        }
        api = a
        log("Terhubung ke MetaApi, region ${a.region}")
    }

    private fun step() {
        val a = api ?: return
        val acc = a.accountInfo()
        if (!connected) {
            val kind = if (acc.optString("type") == "ACCOUNT_TRADE_MODE_DEMO") "DEMO" else "REAL"
            log("Akun ${acc.optLong("login")} (${acc.optString("server")}) - $kind", "ok")
        }
        connected = true
        val magic = cfg.getLong("magic")
        val symbol = cfg.getString("symbol")
        val raw = a.positions()
        val positions = (0 until raw.length()).map { raw.getJSONObject(it) }
            .filter { it.optLong("magic") == magic }
            .map { parsePos(it) }

        val now = System.currentTimeMillis()
        if (now - lastDealsAt > 20_000 || positions.size != lastPosCount) {
            val from = Instant.now().truncatedTo(ChronoUnit.DAYS)
            deals = a.deals(ISO.format(from), ISO.format(Instant.now().plus(1, ChronoUnit.DAYS)))
            lastDealsAt = now
            lastPosCount = positions.size
        }

        val equity = acc.optDouble("equity")
        val today = LocalDate.now(ZoneOffset.UTC)
        if (day != today) {
            day = today
            dayStartEquity = equity
        }
        peakEquity = max(if (peakEquity > 0) peakEquity else equity, equity)
        val dayPl = equity - dayStartEquity
        val dayPlPct = if (dayStartEquity > 0) dayPl / dayStartEquity * 100 else 0.0
        val ddPct = if (peakEquity > 0) (peakEquity - equity) / peakEquity * 100 else 0.0

        var state = "Stopped"
        if (running) {
            state = "Running"
            if (-dayPlPct >= cfg.getDouble("max_daily_loss_percent") && pausedDay != today) {
                closeAll("batas rugi harian")
                pausedDay = today
                val m = "Batas rugi harian ${cfg.getDouble("max_daily_loss_percent")}% tercapai. Bot istirahat sampai besok (UTC)."
                log(m, "error"); alert(m)
            }
            if (ddPct >= cfg.getDouble("max_drawdown_percent")) {
                closeAll("batas drawdown")
                val m = "Drawdown ${"%.1f".format(ddPct)}% ≥ batas. Bot DIHENTIKAN."
                stopBot(m); alert(m)
                state = "Stopped"
            }
        }
        if (running) {
            when {
                pausedDay == today -> state = "Paused (batas harian)"
                !inSession() -> {
                    state = "Di luar jam trading"
                    manage(a, positions, symbol)
                }
                else -> {
                    manage(a, positions, symbol)
                    scan(a, acc, symbol, positions)
                }
            }
        }
        status = state
        publish(acc, positions, dayPl, dayPlPct, ddPct)
        if (running) {
            Notifs.updateService(
                ctx, "$state · Equity ${"%,.2f".format(equity)} · ${positions.size} posisi"
            )
        }
    }

    private fun inSession(): Boolean {
        val now = LocalTime.now(ZoneOffset.UTC).toString().take(5)
        val a = cfg.getString("session_start")
        val b = cfg.getString("session_end")
        return if (a <= b) now in a..b else (now >= a || now <= b)
    }

    private fun closeAll(why: String): Int {
        val a = api ?: return 0
        val magic = cfg.getLong("magic")
        val raw = a.positions()
        var n = 0
        for (i in 0 until raw.length()) {
            val o = raw.getJSONObject(i)
            if (o.optLong("magic") != magic) continue
            try {
                a.trade(JSONObject().put("actionType", "POSITION_CLOSE_ID").put("positionId", o.optString("id")))
                n++
            } catch (e: Exception) {
                log("Gagal tutup #${o.optString("id")}: ${e.message}", "error")
            }
        }
        if (n > 0) log("Tutup $n posisi ($why)", "warn")
        return n
    }

    private fun scan(a: MetaApi, acc: JSONObject, symbol: String, positions: List<Pos>) {
        val tf = cfg.getString("timeframe")
        val tfSec = Settings.TIMEFRAMES[tf] ?: 60L
        val nowSec = System.currentTimeMillis() / 1000
        val idx = nowSec / tfSec
        if (idx == lastBarIdx || nowSec % tfSec < 3) return
        val first = lastBarIdx == -1L
        lastBarIdx = idx

        val limit = min(1000, max(cfg.getInt("ema_trend") * 3, 200) + 5)
        val arr = a.candles(symbol, Settings.TF_API[tf] ?: "1m", limit)
        val closed = (0 until arr.length()).map { arr.getJSONObject(it) }
            .map {
                Candle(
                    Instant.parse(it.getString("time")).epochSecond,
                    it.getDouble("open"), it.getDouble("high"), it.getDouble("low"), it.getDouble("close")
                )
            }
            .sortedBy { it.time }
            .distinctBy { it.time }
            .filter { it.time + tfSec <= nowSec }

        val sig = Strategy.signal(closed, cfg)
        atr = sig.atr
        lastInfo = sig.info
        if (first) {
            log("Memindai market untuk entry pertama…")
            return
        }
        val side = sig.side ?: return
        val buy = side == "buy"

        var mine = positions.filter { it.symbol == symbol }
        if (cfg.getBoolean("close_on_opposite")) {
            val opp = mine.filter { it.buy != buy }
            for (p in opp) {
                try {
                    a.trade(JSONObject().put("actionType", "POSITION_CLOSE_ID").put("positionId", p.id))
                } catch (e: Exception) {
                    log("Gagal tutup #${p.id}: ${e.message}", "error")
                }
            }
            if (opp.isNotEmpty()) {
                log("Sinyal ${side.uppercase()} - tutup ${opp.size} posisi berlawanan")
                mine = mine.filter { it.buy == buy }
            }
        }
        if (mine.isNotEmpty()) return

        val sp = loadSpec(a, symbol)
        val price = a.price(symbol)
        val bid = price.getDouble("bid")
        val ask = price.getDouble("ask")
        val point = sp.optDouble("point", sp.optDouble("tickSize", 0.01))
        val digits = sp.optInt("digits", 2)
        val spread = ((ask - bid) / point).roundToLong()
        if (spread > cfg.getInt("max_spread_points")) {
            log("Sinyal ${side.uppercase()} dilewati: spread $spread > ${cfg.getInt("max_spread_points")} point", "warn")
            return
        }

        var n = cfg.getInt("positions_per_signal")
        n = min(n, cfg.getInt("max_open_positions") - positions.size)
        if (n <= 0) {
            log("Maks posisi terbuka tercapai, sinyal dilewati", "warn")
            return
        }

        val minDist = (sp.optInt("stopsLevel", 0) + 2) * point
        val slDist = max(sig.atr * cfg.getDouble("sl_atr_mult"), minDist)
        val tpMult = cfg.getDouble("tp_atr_mult")
        val tpDist = if (tpMult > 0) max(sig.atr * tpMult, minDist) else 0.0

        val tickSize = sp.optDouble("tickSize", point).let { if (it > 0) it else point }
        val tickValue = price.optDouble("lossTickValue", 0.0)
        val vMin = sp.optDouble("minVolume", 0.01)
        val vMax = sp.optDouble("maxVolume", 100.0)
        val vStep = sp.optDouble("volumeStep", 0.01)

        val lot: Double
        if (cfg.getString("risk_mode") == "percent") {
            val riskMoney = acc.optDouble("balance") * cfg.getDouble("risk_percent") / 100
            lot = Strategy.lotForRisk(riskMoney / n, slDist, tickSize, tickValue, vMin, vMax, vStep)
            if (lot <= 0) {
                log("Sinyal ${side.uppercase()} dilewati: risiko ${cfg.getDouble("risk_percent")}% terlalu kecil " +
                    "untuk lot minimum $vMin (kurangi jumlah posisi atau naikkan risiko)", "warn")
                return
            }
        } else {
            lot = Strategy.normLot(max(vMin, min(cfg.getDouble("fixed_lot"), vMax)), vStep)
        }

        val entry = if (buy) ask else bid
        val sl = Strategy.round(if (buy) entry - slDist else entry + slDist, digits)
        val tp = if (tpDist > 0) Strategy.round(if (buy) entry + tpDist else entry - tpDist, digits) else 0.0

        var opened = 0
        for (i in 0 until n) {
            val body = JSONObject()
                .put("actionType", if (buy) "ORDER_TYPE_BUY" else "ORDER_TYPE_SELL")
                .put("symbol", symbol)
                .put("volume", lot)
                .put("stopLoss", sl)
                .put("comment", "mt5bot ${i + 1}/$n")
                .put("magic", cfg.getLong("magic"))
            if (tp > 0) body.put("takeProfit", tp)
            try {
                a.trade(body)
                opened++
            } catch (e: Exception) {
                log(e.message ?: "Order gagal", "error")
                break
            }
        }
        if (opened > 0) {
            val risk = if (tickValue > 0) slDist / tickSize * tickValue * lot * opened else 0.0
            val m = "${side.uppercase()} ${opened}x $lot lot $symbol @ ${"%.${digits}f".format(entry)} | " +
                "SL $sl TP ${if (tp > 0) tp else "-"} | risiko ≈ ${"%.2f".format(risk)} ${acc.optString("currency")}"
            log(m, "ok")
            alert(m)
            lastDealsAt = 0
        }
    }

    private fun manage(a: MetaApi, positions: List<Pos>, symbol: String) {
        val be = cfg.getDouble("breakeven_atr")
        val tr = cfg.getDouble("trailing_atr")
        if ((be <= 0 && tr <= 0) || atr <= 0) return
        val mine = positions.filter { it.symbol == symbol }
        if (mine.isEmpty()) return
        val sp = loadSpec(a, symbol)
        val digits = sp.optInt("digits", 2)
        val point = sp.optDouble("point", 0.01)
        for (p in mine) {
            val gain = if (p.buy) p.current - p.open else p.open - p.current
            var newSl: Double? = null
            if (be > 0 && gain >= be * atr) newSl = p.open
            if (tr > 0 && gain >= tr * atr) {
                val cand = if (p.buy) p.current - tr * atr else p.current + tr * atr
                if (newSl == null || (p.buy && cand > newSl) || (!p.buy && cand < newSl)) newSl = cand
            }
            if (newSl == null) continue
            val sl = Strategy.round(newSl, digits)
            val better = p.sl == 0.0 || (p.buy && sl > p.sl + point) || (!p.buy && sl < p.sl - point)
            if (!better) continue
            try {
                val body = JSONObject().put("actionType", "POSITION_MODIFY")
                    .put("positionId", p.id).put("stopLoss", sl)
                if (p.tp > 0) body.put("takeProfit", p.tp)
                a.trade(body)
            } catch (e: Exception) {
                log("#${p.id}: ${e.message}", "warn")
            }
        }
    }

    private fun publish(acc: JSONObject, positions: List<Pos>, dayPl: Double, dayPlPct: Double, ddPct: Double) {
        val magic = cfg.getLong("magic")
        val closed = (0 until deals.length()).map { deals.getJSONObject(it) }
            .filter { it.optLong("magic") == magic && it.optString("entryType") == "DEAL_ENTRY_OUT" }
            .sortedByDescending { it.optString("time") }
        val wins = closed.count { it.optDouble("profit", 0.0) > 0 }
        fun dealPl(d: JSONObject) = d.optDouble("profit", 0.0) + d.optDouble("swap", 0.0) + d.optDouble("commission", 0.0)

        val s = JSONObject()
        s.put("mode", "metaapi")
        s.put("account", JSONObject()
            .put("login", acc.optLong("login"))
            .put("server", acc.optString("server"))
            .put("name", acc.optString("name"))
            .put("currency", acc.optString("currency"))
            .put("balance", Strategy.round(acc.optDouble("balance"), 2))
            .put("equity", Strategy.round(acc.optDouble("equity"), 2))
            .put("margin", Strategy.round(acc.optDouble("margin", 0.0), 2))
            .put("free_margin", Strategy.round(acc.optDouble("freeMargin", 0.0), 2))
            .put("margin_level", Strategy.round(acc.optDouble("marginLevel", 0.0).let { if (it.isNaN()) 0.0 else it }, 2))
            .put("leverage", acc.optInt("leverage"))
            .put("is_demo", acc.optString("type") == "ACCOUNT_TRADE_MODE_DEMO"))
        s.put("today", JSONObject()
            .put("pl", Strategy.round(dayPl, 2))
            .put("pl_pct", Strategy.round(dayPlPct, 2))
            .put("drawdown_pct", Strategy.round(ddPct, 2))
            .put("closed", closed.size)
            .put("wins", wins)
            .put("realized", Strategy.round(closed.sumOf { dealPl(it) }, 2)))
        s.put("floating", Strategy.round(positions.sumOf { it.profit }, 2))
        s.put("positions", JSONArray(positions.map {
            JSONObject().put("ticket", it.id).put("symbol", it.symbol)
                .put("side", if (it.buy) "buy" else "sell").put("volume", it.volume)
                .put("open", it.open).put("current", it.current).put("sl", it.sl).put("tp", it.tp)
                .put("profit", Strategy.round(it.profit, 2)).put("time", it.time)
        }))
        s.put("history", JSONArray(closed.take(100).map {
            JSONObject().put("ticket", it.optString("id")).put("symbol", it.optString("symbol"))
                // deal penutup bertipe kebalikan posisi; tampilkan arah posisinya
                .put("side", if (it.optString("type") == "DEAL_TYPE_SELL") "buy" else "sell")
                .put("volume", it.optDouble("volume", 0.0))
                .put("price", it.optDouble("price", 0.0))
                .put("profit", Strategy.round(dealPl(it), 2))
                .put("time", it.optString("time"))
        }))
        s.put("indicators", lastInfo)
        s.put("updated", Instant.now().toString())
        snapshot = s
    }
}
