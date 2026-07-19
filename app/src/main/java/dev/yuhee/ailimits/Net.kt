package dev.yuhee.ailimits

import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

data class HttpResult(val code: Int, val body: String)

object Net {
    fun get(url: String, headers: Map<String, String>): HttpResult =
        request("GET", url, null, headers)

    fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): HttpResult =
        request("POST", url, json, headers + mapOf("Content-Type" to "application/json"))

    fun postForm(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()): HttpResult {
        val body = form.entries.joinToString("&") { (k, v) ->
            java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8")
        }
        return request("POST", url, body, headers + mapOf("Content-Type" to "application/x-www-form-urlencoded"))
    }

    private fun request(method: String, url: String, body: String?, headers: Map<String, String>): HttpResult {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            return HttpResult(code, text)
        } finally {
            conn.disconnect()
        }
    }
}
