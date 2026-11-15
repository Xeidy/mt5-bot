package com.mt5bot.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class ApiException(val code: Int, message: String) : Exception(message)

/**
 * Klien REST MetaApi (metaapi.cloud).
 * Semua method bersifat blocking; panggil hanya dari thread worker Engine.
 */
class MetaApi(private val token: String, val accountId: String) {

    private val provisioning = "https://mt-provisioning-api-v1.agiliumtrade.agiliumtrade.ai"
    var region = "new-york"; private set
    private var clientHost = ""
    private var marketHost = ""

    private fun request(method: String, url: String, body: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.setRequestProperty("auth-token", token)
            c.setRequestProperty("Accept", "application/json")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = try {
                    JSONObject(text).optString("message", text)
                } catch (e: Exception) {
                    text
                }
                throw ApiException(code, "MetaApi HTTP $code: ${msg.take(200)}")
            }
            return text
        } finally {
            c.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun base() = "$clientHost/users/current/accounts/$accountId"

    /** Ambil info akun di MetaApi (state, region) dan tentukan host API. */
    fun init(): JSONObject {
        val acc = JSONObject(request("GET", "$provisioning/users/current/accounts/$accountId"))
        region = acc.optString("region", "new-york").ifBlank { "new-york" }
        val domain = try {
            JSONObject(request("GET", "$provisioning/users/current/servers/mt-client-api"))
                .optString("domain", "agiliumtrade.ai")
        } catch (e: ApiException) {
            "agiliumtrade.ai"
        }.ifBlank { "agiliumtrade.ai" }
        clientHost = "https://mt-client-api-v1.$region.$domain"
        marketHost = "https://mt-market-data-client-api-v1.$region.$domain"
        return acc
    }

    fun deploy() {
        request("POST", "$provisioning/users/current/accounts/$accountId/deploy")
    }

    fun accountInfo() = JSONObject(request("GET", "${base()}/account-information"))
    fun positions() = JSONArray(request("GET", "${base()}/positions"))
    fun specification(symbol: String) =
        JSONObject(request("GET", "${base()}/symbols/${enc(symbol)}/specification"))

    fun price(symbol: String) =
        JSONObject(request("GET", "${base()}/symbols/${enc(symbol)}/current-price"))

    fun deals(fromIso: String, toIso: String) =
        JSONArray(request("GET", "${base()}/history-deals/time/$fromIso/$toIso"))

    fun candles(symbol: String, timeframe: String, limit: Int) = JSONArray(
        request(
            "GET",
            "$marketHost/users/current/accounts/$accountId/historical-market-data/symbols/" +
                "${enc(symbol)}/timeframes/$timeframe/candles?limit=$limit"
        )
    )

    fun trade(body: JSONObject): JSONObject {
        val r = JSONObject(request("POST", "${base()}/trade", body.toString()))
        val code = r.optInt("numericCode", -1)
        if (code !in OK_CODES) {
            throw ApiException(
                code,
                "Order ditolak: ${r.optString("stringCode")} ${r.optString("message")}".trim()
            )
        }
        return r
    }

    companion object {
        private val OK_CODES = setOf(0, 10008, 10009, 10010, 10025)
    }
}
