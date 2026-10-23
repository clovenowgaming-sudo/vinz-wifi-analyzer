package com.vinz.wifianalyzer

import android.graphics.Color
import android.net.wifi.ScanResult
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.SSLException

class SecurityTab(private val act: MainActivity) {

    private var running = false
    private var status = "Tekan tombol untuk memeriksa keamanan WiFi yang sedang kamu pakai."
    private var score = -1
    private var findings: List<Triple<Int, String, String>> = emptyList()

    fun render(c: LinearLayout) {
        c.addView(act.button(if (running) "Memeriksa..." else "🛡  Cek Keamanan WiFi") { start() })
        val st = act.tv(status, 13f, cSub)
        st.setPadding(act.dp(4), 0, act.dp(4), act.dp(10))
        c.addView(st)

        if (score >= 0) {
            val g = when {
                score >= 85 -> Pair("AMAN", cGood)
                score >= 65 -> Pair("CUKUP", cWarn)
                score >= 40 -> Pair("BERISIKO", cBad)
                else -> Pair("BAHAYA", cBad)
            }
            val hero = LinearLayout(act)
            hero.orientation = LinearLayout.VERTICAL
            hero.setPadding(act.dp(16), act.dp(18), act.dp(16), act.dp(18))
            hero.background = act.rounded(cCard, 18)
            hero.layoutParams = act.lp()
            val a = act.tv("$score / 100", 38f, g.second, true)
            a.gravity = Gravity.CENTER
            a.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            val b = act.tv(g.first, 16f, g.second, true)
            b.gravity = Gravity.CENTER
            b.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            hero.addView(a)
            hero.addView(b)
            c.addView(hero)
        }

        for (f in findings) c.addView(act.finding(f.first, f.second, f.third))

        c.addView(act.tv(
            "Audit ini hanya memeriksa jaringan yang sedang kamu pakai (enkripsi, WPS, port admin router, DNS, HTTPS). Tidak ada cracking password atau serangan. Pakai hanya di jaringan milikmu.",
            11f, cSub
        ).apply { setPadding(act.dp(4), act.dp(4), act.dp(4), act.dp(20)) })
    }

    private fun start() {
        if (running) return
        val d = act.wifi.dhcpInfo
        val info = act.wifi.connectionInfo
        if (!act.wifi.isWifiEnabled || d == null || d.ipAddress == 0 || info == null) {
            status = "Hubungkan ke WiFi dulu."
            act.refresh(4)
            return
        }
        running = true
        score = -1
        findings = emptyList()
        status = "Memeriksa... (sekitar 15 detik)"
        act.refresh(4)
        Thread {
            try {
                val r = audit()
                score = r.first
                findings = r.second
                status = "Selesai."
            } catch (e: Exception) {
                status = "Gagal: " + (e.message ?: "error")
            }
            running = false
            act.ui.post { act.refresh(4) }
        }.start()
    }

    private fun tcp(ip: String, port: Int, to: Int): Boolean {
        return try {
            Socket().use { it.connect(InetSocketAddress(ip, port), to) }
            true
        } catch (e: Exception) { false }
    }

    // 0 ok, 1 sertifikat bermasalah, 2 error jaringan
    private fun httpsCheck(u: String): Int {
        return try {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 6000
            c.readTimeout = 6000
            c.instanceFollowRedirects = false
            c.responseCode
            c.disconnect()
            0
        } catch (e: SSLException) {
            1
        } catch (e: Exception) {
            2
        }
    }

    // 0 normal, 1 dicegat/redirect, 2 error
    private fun portalCheck(): Int {
        return try {
            val c = URL("http://connectivitycheck.gstatic.com/generate_204").openConnection() as HttpURLConnection
            c.connectTimeout = 6000
            c.readTimeout = 6000
            c.instanceFollowRedirects = false
            val code = c.responseCode
            c.disconnect()
            if (code == 204) 0 else 1
        } catch (e: Exception) { 2 }
    }

    private fun audit(): Pair<Int, List<Triple<Int, String, String>>> {
        val f = ArrayList<Triple<Int, String, String>>()
        var s = 100
        fun add(level: Int, title: String, detail: String, pen: Int = 0) {
            f.add(Triple(level, title, detail))
            s -= pen
        }

        val wifi = act.wifi
        val info = wifi.connectionInfo
        val d = wifi.dhcpInfo
        val ssid = info.ssid?.trim('"') ?: ""
        val scans: List<ScanResult> = try { wifi.scanResults ?: emptyList() } catch (e: SecurityException) { emptyList() }
        val me = scans.firstOrNull { it.BSSID == info.bssid }

        // 1. Enkripsi
        if (me != null) {
            val cap = me.capabilities
            when (secClass(cap)) {
                0 -> add(2, "Jaringan TERBUKA", "Tanpa enkripsi: data bisa disadap orang di sekitar. Pasang password WPA2/WPA3.", 45)
                1 -> add(2, "Enkripsi WEP", "WEP bisa dibobol dalam hitungan menit. Ganti ke WPA2/WPA3.", 45)
                2 -> add(2, "Enkripsi WPA1 (usang)", "Ganti ke WPA2-AES atau WPA3.", 30)
                3 -> add(0, "Enkripsi WPA2", "Cukup aman dengan password kuat (12+ karakter). Upgrade ke WPA3 kalau router mendukung.", 3)
                4 -> add(0, "Enkripsi WPA3", "Standar keamanan WiFi terbaru.")
                else -> add(0, "Enkripsi Enterprise (802.1X)", "Autentikasi per-user, bagus.")
            }
            if (cap.contains("TKIP")) add(1, "Cipher TKIP aktif", "TKIP lemah. Set router ke WPA2-AES (CCMP) saja.", 12)
            if (cap.contains("WPS")) add(1, "WPS aktif", "WPS PIN rawan brute-force. Matikan WPS di pengaturan router.", 10)
        } else {
            add(1, "Enkripsi tidak terbaca", "Izinkan Lokasi (Presisi) dan nyalakan GPS supaya jenis enkripsi bisa dinilai.", 10)
        }

        // 2. AP kembar
        if (me != null && ssid.isNotEmpty()) {
            val twins = scans.filter { it.SSID == ssid && it.BSSID != info.bssid }
            if (twins.isNotEmpty()) {
                val mine = secClass(me.capabilities)
                val openTwin = twins.any { secClass(it.capabilities) == 0 }
                val diff = twins.any { secClass(it.capabilities) != mine }
                when {
                    openTwin && mine != 0 -> add(2, "AP kembar TERBUKA terdeteksi", "Ada AP dengan nama sama tapi tanpa password. Bisa jadi evil twin. Jangan sambung ke yang itu.", 25)
                    diff -> add(1, "AP bernama sama, keamanan beda", "Bisa repeater lama atau AP palsu. Pastikan itu perangkatmu.", 8)
                    else -> add(0, "${twins.size} AP lain bernama sama", "Keamanannya sama, kemungkinan mesh/repeater milikmu.")
                }
            }
        }

        // 3. Port admin router
        val gw = dhcpIp(d.gateway)
        val open = ArrayList<Int>()
        for (p in intArrayOf(21, 22, 23, 80, 443, 2323, 5555, 7547, 8080)) {
            if (tcp(gw, p, 350)) open.add(p)
        }
        var risky = false
        if (open.contains(23) || open.contains(2323)) {
            risky = true
            add(2, "Telnet terbuka di router", "Telnet tanpa enkripsi dan sering jadi target malware IoT. Matikan di router.", 20)
        }
        if (open.contains(21)) {
            risky = true
            add(1, "FTP terbuka di router", "Matikan FTP kalau tidak dipakai.", 8)
        }
        if (open.contains(5555)) {
            risky = true
            add(2, "ADB terbuka di gateway", "Port debug Android terbuka, sangat berbahaya.", 15)
        }
        if (open.contains(80) && !open.contains(443)) {
            add(1, "Panel admin router hanya HTTP", "Password admin dikirim tanpa enkripsi di jaringan. Ganti password admin default.", 3)
        }
        if (open.contains(7547)) {
            add(3, "Port TR-069 (7547) terbuka", "Dipakai ISP untuk manajemen jarak jauh. Normal, tapi pastikan firmware router terbaru.")
        }
        if (!risky) {
            add(0, "Port berbahaya router tertutup", "Port terbuka di $gw: " + (if (open.isEmpty()) "tidak ada" else open.joinToString(", ")))
        }

        // 4. DNS
        val dns = dhcpIp(d.dns1)
        val pub = listOf("8.8.8.8", "8.8.4.4", "1.1.1.1", "1.0.0.1", "9.9.9.9", "149.112.112.112", "208.67.222.222", "208.67.220.220")
        when {
            dns == "-" -> add(1, "DNS kosong", "Router tidak memberi DNS.", 0)
            dns == gw -> add(0, "DNS lewat router", "DNS $dns sama dengan router (normal).")
            pub.contains(dns) -> add(0, "DNS publik tepercaya", "DNS $dns.")
            else -> add(3, "DNS: $dns", "Pastikan ini DNS ISP/pilihanmu. Kalau tidak kenal, DNS bisa dibelokkan (hijack). Alternatif aman: 1.1.1.1 atau 8.8.8.8.")
        }

        // 5. HTTPS / MITM
        val r1 = httpsCheck("https://www.google.com/generate_204")
        val r2 = httpsCheck("https://www.cloudflare.com/cdn-cgi/trace")
        when {
            r1 == 1 || r2 == 1 -> add(2, "Sertifikat HTTPS tidak valid", "Koneksi HTTPS ditolak karena sertifikat bermasalah. Kemungkinan ada proxy/MITM di jaringan ini. Jangan login ke akun penting.", 30)
            r1 == 2 && r2 == 2 -> add(1, "Tes HTTPS tidak bisa dijalankan", "Internet tidak tersedia atau diblokir.", 0)
            else -> add(0, "HTTPS aman", "Sertifikat situs valid, tidak ada tanda penyadapan HTTPS.")
        }

        // 6. Captive portal / injeksi HTTP
        when (portalCheck()) {
            0 -> add(0, "Tidak ada pembelokan HTTP", "Tes koneksi HTTP normal.")
            1 -> add(1, "HTTP dibelokkan / ada portal", "Ada redirect atau modifikasi trafik HTTP (captive portal atau injeksi iklan).", 8)
            else -> add(3, "Tes HTTP tidak bisa dijalankan", "Tidak ada respon.")
        }

        // 7. Info sinyal & band
        val q = quality(info.rssi)
        add(3, "Sinyal ${q.first}", "${info.rssi} dBm di ${band(info.frequency)} (Ch ${chan(info.frequency)}).")
        if (info.frequency < 3000 && wifi.is5GHzBandSupported) {
            add(3, "HP mendukung 5 GHz", "Kalau router dual-band, pakai 5 GHz: lebih cepat dan jangkauannya lebih terbatas.")
        }

        return Pair(s.coerceIn(0, 100), f)
    }
}
