package com.vinz.wifianalyzer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class SpeedTab(private val act: MainActivity) {

    private var mode = 0 // 0 otomatis, 1 wifi, 2 seluler
    private var running = false
    private var status = "Siap. Tekan MULAI TEST."
    private var gaugeText = "0.0"
    private var ping = "-"
    private var jitter = "-"
    private var down = "-"
    private var up = "-"
    private var ip = "-"
    private var isp = "-"
    private var loc = "-"
    private var colo = "-"
    private var downMbps = 0.0
    private var gaugeV: TextView? = null
    private var statV: TextView? = null
    private var cb: ConnectivityManager.NetworkCallback? = null

    private fun fmt(v: Double): String = String.format(Locale.US, "%.1f", v)

    // ---------- UI ----------

    fun render(c: LinearLayout) {
        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutParams = act.lp(0, 10)
        val names = listOf("Otomatis", "WiFi", "Seluler")
        for (i in names.indices) {
            val t = act.tv(names[i], 14f, Color.WHITE, true)
            t.gravity = Gravity.CENTER
            t.setPadding(0, act.dp(10), 0, act.dp(10))
            t.background = act.rounded(if (i == mode) cAccent else cCard, 12)
            val p = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            p.setMargins(act.dp(3), 0, act.dp(3), 0)
            t.layoutParams = p
            t.setOnClickListener { if (!running) { mode = i; act.refresh(3) } }
            row.addView(t)
        }
        c.addView(row)
        c.addView(act.button(if (running) "Testing..." else "▶  MULAI TEST") { start() })

        val g = LinearLayout(act)
        g.orientation = LinearLayout.VERTICAL
        g.setPadding(act.dp(16), act.dp(20), act.dp(16), act.dp(20))
        g.background = act.rounded(cCard, 18)
        g.layoutParams = act.lp()
        val gv = act.tv(gaugeText, 46f, cAccent, true)
        gv.gravity = Gravity.CENTER
        gv.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val unit = act.tv("Mbps", 14f, cSub)
        unit.gravity = Gravity.CENTER
        unit.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val sv = act.tv(status, 13f, cSub)
        sv.gravity = Gravity.CENTER
        sv.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        gaugeV = gv
        statV = sv
        g.addView(gv)
        g.addView(unit)
        g.addView(sv)
        c.addView(g)

        c.addView(act.card("Hasil", listOf(
            "Ping" to ping,
            "Jitter" to jitter,
            "Download" to down,
            "Upload" to up,
            "Server" to colo
        )))

        c.addView(act.card("Jaringan & Operator", netInfo()))
        c.addView(act.tv(
            "Test memakai data (kira-kira 30-80 MB). Server: Cloudflare. Mode Seluler memaksa test lewat data seluler walau WiFi nyala.",
            11f, cSub
        ).apply { setPadding(act.dp(4), act.dp(4), act.dp(4), act.dp(20)) })
    }

    private fun activeNet(): String {
        return try {
            val ni = act.cm.activeNetworkInfo
            if (ni == null || !ni.isConnected) "Tidak ada koneksi"
            else when (ni.type) {
                ConnectivityManager.TYPE_WIFI -> "WiFi"
                ConnectivityManager.TYPE_MOBILE -> "Data Seluler"
                ConnectivityManager.TYPE_ETHERNET -> "Ethernet"
                else -> ni.typeName ?: "-"
            }
        } catch (e: Exception) { "-" }
    }

    private fun netType(tm: TelephonyManager): String {
        return try {
            val t = if (Build.VERSION.SDK_INT >= 24) tm.dataNetworkType else tm.networkType
            when (t) {
                20 -> "5G NR"
                13 -> "4G LTE"
                19 -> "4G LTE-A (CA)"
                15, 10, 9, 8 -> "3G+ (HSPA)"
                3 -> "3G UMTS"
                1, 2 -> "2G"
                0 -> "-"
                else -> "Lainnya ($t)"
            }
        } catch (e: SecurityException) {
            "Butuh izin Telepon"
        } catch (e: Exception) { "-" }
    }

    private fun netInfo(): List<Pair<String, String>> {
        val rows = ArrayList<Pair<String, String>>()
        rows.add("Koneksi aktif" to activeNet())
        try {
            val tm = act.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val opName = tm.networkOperatorName
            val simName = tm.simOperatorName
            val mccmnc = tm.networkOperator ?: ""
            rows.add("Operator" to (if (opName.isNullOrEmpty()) "-" else opName))
            rows.add("Kartu SIM" to (if (simName.isNullOrEmpty()) "-" else simName))
            if (mccmnc.length >= 5) rows.add("MCC / MNC" to "${mccmnc.substring(0, 3)} / ${mccmnc.substring(3)}")
            rows.add("Jenis jaringan" to netType(tm))
            rows.add("Roaming" to (if (tm.isNetworkRoaming) "Ya" else "Tidak"))
        } catch (e: Exception) {
            rows.add("Operator" to "Tidak tersedia")
        }
        rows.add("IP publik" to ip)
        rows.add("ISP / AS" to isp)
        rows.add("Lokasi IP" to (if (loc.isEmpty()) "-" else loc))
        return rows
    }

    // ---------- logic ----------

    private fun live(v: Double?, s: String) {
        status = s
        if (v != null) gaugeText = fmt(v)
        act.ui.post {
            gaugeV?.text = gaugeText
            statV?.text = status
        }
    }

    private fun start() {
        if (running) return
        if (Build.VERSION.SDK_INT >= 23 &&
            act.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED
        ) {
            act.requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), 2)
        }
        running = true
        ping = "-"; jitter = "-"; down = "-"; up = "-"
        gaugeText = "0.0"
        status = "Menyiapkan jaringan..."
        act.refresh(3)
        Thread {
            try {
                val net = resolveNetwork()
                fetchMeta(net)
                doPing(net)
                doDown(net)
                doUp(net)
                gaugeText = fmt(downMbps)
                status = "Selesai • " + verdict(downMbps)
            } catch (e: Exception) {
                status = "Gagal: " + (e.message ?: "error")
            }
            try { val k = cb; if (k != null) act.cm.unregisterNetworkCallback(k) } catch (e: Exception) { }
            cb = null
            running = false
            act.ui.post { act.refresh(3) }
        }.start()
    }

    private fun verdict(d: Double): String = when {
        d >= 50 -> "Sangat cepat"
        d >= 20 -> "Bagus (streaming HD lancar)"
        d >= 5 -> "Cukup (browsing & video SD)"
        else -> "Lambat"
    }

    private fun resolveNetwork(): Network? {
        if (mode == 0) return null
        val transport = if (mode == 1) NetworkCapabilities.TRANSPORT_WIFI else NetworkCapabilities.TRANSPORT_CELLULAR
        val req = NetworkRequest.Builder()
            .addTransportType(transport)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val latch = CountDownLatch(1)
        var found: Network? = null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                found = network
                latch.countDown()
            }
        }
        cb = callback
        act.cm.requestNetwork(req, callback)
        val ok = latch.await(10, TimeUnit.SECONDS)
        val n = found
        if (!ok || n == null) {
            throw Exception(if (mode == 1) "WiFi tidak tersedia" else "Data seluler tidak aktif / tidak tersedia")
        }
        return n
    }

    private fun open(net: Network?, u: String): HttpURLConnection {
        val url = URL(u)
        val c = (if (net != null) net.openConnection(url) else url.openConnection()) as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 8000
        c.useCaches = false
        c.setRequestProperty("User-Agent", "VinzWifiAnalyzer/2.0")
        return c
    }

    private fun fetchMeta(net: Network?) {
        live(null, "Mendeteksi ISP / operator...")
        try {
            val c = open(net, "https://speed.cloudflare.com/meta")
            val txt = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            val j = JSONObject(txt)
            ip = j.optString("clientIp", "-")
            val org = j.optString("asOrganization", "")
            val asn = j.optString("asn", "")
            isp = if (org.isEmpty()) "-" else "$org (AS$asn)"
            loc = listOf(j.optString("city"), j.optString("region"), j.optString("country"))
                .filter { it.isNotEmpty() }.joinToString(", ")
            colo = j.optString("colo", "-")
        } catch (e: Exception) { }
        if (isp == "-") {
            try {
                val c = open(net, "https://ipinfo.io/json")
                val txt = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                val j = JSONObject(txt)
                ip = j.optString("ip", "-")
                isp = j.optString("org", "-")
                loc = listOf(j.optString("city"), j.optString("region"), j.optString("country"))
                    .filter { it.isNotEmpty() }.joinToString(", ")
            } catch (e: Exception) { }
        }
    }

    private fun doPing(net: Network?) {
        live(null, "Mengukur ping...")
        val times = ArrayList<Long>()
        for (i in 0 until 7) {
            try {
                val t0 = System.nanoTime()
                val c = open(net, "https://speed.cloudflare.com/__down?bytes=0&r=" + System.nanoTime())
                c.inputStream.use { it.readBytes() }
                c.disconnect()
                times.add((System.nanoTime() - t0) / 1_000_000)
            } catch (e: Exception) { }
        }
        if (times.isEmpty()) throw Exception("Tidak bisa terhubung ke server test (cek internet)")
        val t = if (times.size > 2) times.drop(1) else times
        ping = "${t.average().toInt()} ms"
        jitter = "${(t.maxOrNull() ?: 0L) - (t.minOrNull() ?: 0L)} ms"
    }

    private fun doDown(net: Network?) {
        live(0.0, "Mengukur download...")
        val total = AtomicLong(0)
        val t0 = System.nanoTime()
        val dur = 8_000_000_000L
        val threads = (1..4).map {
            val th = Thread {
                try {
                    while (System.nanoTime() - t0 < dur) {
                        val c = open(net, "https://speed.cloudflare.com/__down?bytes=25000000&r=" + System.nanoTime())
                        val ins = c.inputStream
                        val buf = ByteArray(65536)
                        while (System.nanoTime() - t0 < dur) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            total.addAndGet(n.toLong())
                        }
                        try { ins.close() } catch (e: Exception) { }
                        c.disconnect()
                    }
                } catch (e: Exception) { }
            }
            th.start()
            th
        }
        while (threads.any { it.isAlive }) {
            Thread.sleep(250)
            val el = (System.nanoTime() - t0) / 1e9
            if (el > 0.8) live(total.get() * 8 / el / 1e6, "Mengukur download...")
        }
        val el = (System.nanoTime() - t0) / 1e9
        val mbps = total.get() * 8 / el / 1e6
        if (total.get() == 0L) throw Exception("Download gagal (server tidak merespon)")
        downMbps = mbps
        down = fmt(mbps) + " Mbps"
    }

    private fun doUp(net: Network?) {
        live(0.0, "Mengukur upload...")
        val total = AtomicLong(0)
        val t0 = System.nanoTime()
        val dur = 6_000_000_000L
        val chunk = ByteArray(65536)
        val size = 4_000_000
        val threads = (1..3).map {
            val th = Thread {
                try {
                    while (System.nanoTime() - t0 < dur) {
                        val c = open(net, "https://speed.cloudflare.com/__up")
                        c.requestMethod = "POST"
                        c.doOutput = true
                        c.setFixedLengthStreamingMode(size)
                        c.setRequestProperty("Content-Type", "application/octet-stream")
                        val os = c.outputStream
                        var sent = 0
                        while (sent < size && System.nanoTime() - t0 < dur) {
                            val n = minOf(chunk.size, size - sent)
                            os.write(chunk, 0, n)
                            sent += n
                            total.addAndGet(n.toLong())
                        }
                        if (sent < size) {
                            c.disconnect()
                            break
                        }
                        os.close()
                        try { c.inputStream.use { it.readBytes() } } catch (e: Exception) { }
                        c.disconnect()
                    }
                } catch (e: Exception) { }
            }
            th.start()
            th
        }
        while (threads.any { it.isAlive }) {
            Thread.sleep(250)
            val el = (System.nanoTime() - t0) / 1e9
            if (el > 0.8) live(total.get() * 8 / el / 1e6, "Mengukur upload...")
        }
        val el = (System.nanoTime() - t0) / 1e9
        up = if (total.get() == 0L) "Gagal" else fmt(total.get() * 8 / el / 1e6) + " Mbps"
    }
}
