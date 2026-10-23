package com.vinz.wifianalyzer

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {

    data class Dev(val ip: String, val mac: String, val host: String, val tag: String)

    private val cBg = Color.parseColor("#0F1220")
    private val cCard = Color.parseColor("#1B2036")
    private val cAccent = Color.parseColor("#4F8CFF")
    private val cSub = Color.parseColor("#9AA4C7")
    private val cGood = Color.parseColor("#3DDC84")
    private val cWarn = Color.parseColor("#FFB020")
    private val cBad = Color.parseColor("#FF5C5C")

    private lateinit var wifi: WifiManager
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var tabViews: List<TextView>
    private val ui = Handler(Looper.getMainLooper())

    private var tab = 0
    private var scanning = false
    private var devices: List<Dev> = emptyList()
    private var devStatus = "Tekan tombol untuk scan perangkat yang terhubung di WiFi ini."
    private var devStatusView: TextView? = null

    private val ticker = object : Runnable {
        override fun run() {
            if (tab == 0) {
                val y = scroll.scrollY
                showTab(0)
                scroll.post { scroll.scrollTo(0, y) }
            }
            ui.postDelayed(this, 3000)
        }
    }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (tab == 1) showTab(1)
        }
    }

    private val OUI = mapOf(
        "B8:27:EB" to "Raspberry Pi",
        "DC:A6:32" to "Raspberry Pi",
        "E4:5F:01" to "Raspberry Pi",
        "00:50:56" to "VMware",
        "08:00:27" to "VirtualBox",
        "3C:5A:B4" to "Google"
    )

    // ---------- helpers UI ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, r: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
    }

    private fun tv(t: String, size: Float = 14f, color: Int = Color.WHITE, bold: Boolean = false): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = size
        v.setTextColor(color)
        if (bold) v.setTypeface(v.typeface, Typeface.BOLD)
        return v
    }

    private fun lp(top: Int = 0, bottom: Int = 10): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        p.setMargins(0, dp(top), 0, dp(bottom))
        return p
    }

    private fun card(title: String?, rows: List<Pair<String, String>>): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(14), dp(12), dp(14), dp(12))
        c.background = rounded(cCard, 16)
        c.layoutParams = lp()
        if (title != null) {
            val t = tv(title, 16f, Color.WHITE, true)
            t.setPadding(0, 0, 0, dp(6))
            c.addView(t)
        }
        for ((k, v) in rows) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.setPadding(0, dp(3), 0, dp(3))
            val a = tv(k, 13f, cSub)
            a.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.4f)
            val b = tv(v, 13f, Color.WHITE)
            b.setTextIsSelectable(true)
            b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.6f)
            r.addView(a)
            r.addView(b)
            c.addView(r)
        }
        return c
    }

    private fun button(text: String, onClick: () -> Unit): TextView {
        val b = tv(text, 15f, Color.WHITE, true)
        b.gravity = Gravity.CENTER
        b.setPadding(0, dp(12), 0, dp(12))
        b.background = rounded(cAccent, 14)
        b.layoutParams = lp(0, 10)
        b.setOnClickListener { onClick() }
        return b
    }

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(cBg)
        root.setPadding(dp(14), dp(34), dp(14), dp(8))
        root.addView(tv("WiFi Analyzer", 24f, Color.WHITE, true))
        root.addView(tv("Mod By Vinz :3", 12f, cSub))

        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        tabs.layoutParams = lp(12, 10)
        val names = listOf("Info", "Jaringan", "Perangkat")
        tabViews = names.mapIndexed { i, n ->
            val t = tv(n, 14f, Color.WHITE, true)
            t.gravity = Gravity.CENTER
            t.setPadding(0, dp(10), 0, dp(10))
            val p = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            p.setMargins(dp(3), 0, dp(3), 0)
            t.layoutParams = p
            t.setOnClickListener { showTab(i) }
            t
        }
        tabViews.forEach { tabs.addView(it) }
        root.addView(tabs)

        scroll = ScrollView(this)
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        requestPerms()
        showTab(0)
    }

    override fun onResume() {
        super.onResume()
        val f = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(scanReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(scanReceiver, f)
        }
        ui.postDelayed(ticker, 3000)
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(scanReceiver) } catch (e: Exception) { }
        ui.removeCallbacks(ticker)
    }

    // ---------- permission ----------

    private fun hasLoc() = Build.VERSION.SDK_INT < 23 ||
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestPerms() {
        if (Build.VERSION.SDK_INT >= 23 && !hasLoc()) {
            val p = if (Build.VERSION.SDK_INT >= 33)
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, "android.permission.NEARBY_WIFI_DEVICES")
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            requestPermissions(p, 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        showTab(tab)
    }

    // ---------- tabs ----------

    private fun showTab(i: Int) {
        tab = i
        tabViews.forEachIndexed { idx, t ->
            t.background = rounded(if (idx == i) cAccent else cCard, 12)
        }
        content.removeAllViews()
        devStatusView = null
        when (i) {
            0 -> renderInfo()
            1 -> renderScan()
            2 -> renderDevices()
        }
    }

    // ---------- wifi helpers ----------

    private fun ipStr(v: Int) = "${(v ushr 24) and 255}.${(v ushr 16) and 255}.${(v ushr 8) and 255}.${v and 255}"

    private fun dhcpIp(v: Int) = if (v == 0) "-" else ipStr(Integer.reverseBytes(v))

    private fun chan(f: Int): Int = when {
        f == 2484 -> 14
        f in 2400..2483 -> (f - 2407) / 5
        f in 4900..5900 -> (f - 5000) / 5
        f in 5925..7125 -> (f - 5950) / 5
        else -> 0
    }

    private fun band(f: Int) = when {
        f < 3000 -> "2.4 GHz"
        f < 5900 -> "5 GHz"
        else -> "6 GHz"
    }

    private fun sec(cap: String): String {
        val base = when {
            cap.contains("SAE") && cap.contains("PSK") -> "WPA2/WPA3"
            cap.contains("SAE") -> "WPA3"
            cap.contains("WPA3") -> "WPA3"
            cap.contains("WPA2") -> "WPA2"
            cap.contains("WPA") -> "WPA (lemah)"
            cap.contains("WEP") -> "WEP (tidak aman)"
            cap.contains("OWE") -> "OWE"
            else -> "Terbuka"
        }
        return if (cap.contains("WPS")) "$base + WPS" else base
    }

    private fun width(w: Int) = when (w) {
        0 -> "20 MHz"
        1 -> "40 MHz"
        2 -> "80 MHz"
        3 -> "160 MHz"
        4 -> "80+80 MHz"
        else -> "-"
    }

    private fun bars(rssi: Int): String = when {
        rssi >= -55 -> "▂▄▆█"
        rssi >= -65 -> "▂▄▆▁"
        rssi >= -75 -> "▂▄▁▁"
        rssi >= -85 -> "▂▁▁▁"
        else -> "▁▁▁▁"
    }

    private fun quality(rssi: Int): Pair<String, Int> = when {
        rssi >= -55 -> "Sangat bagus" to cGood
        rssi >= -67 -> "Bagus" to cGood
        rssi >= -75 -> "Lumayan" to cWarn
        rssi >= -85 -> "Lemah" to cBad
        else -> "Sangat lemah" to cBad
    }

    private fun safeScan(): List<ScanResult> =
        try { wifi.scanResults ?: emptyList() } catch (e: SecurityException) { emptyList() }

    // ---------- tab 0: info ----------

    private fun renderInfo() {
        if (!hasLoc()) {
            content.addView(card("Izin diperlukan", listOf(
                "Info" to "Android butuh izin Lokasi supaya nama WiFi (SSID) bisa dibaca. Izinkan lalu buka tab ini lagi."
            )))
        }
        if (!wifi.isWifiEnabled) {
            content.addView(card("WiFi mati", listOf("Status" to "Nyalakan WiFi dulu")))
            return
        }
        val info = wifi.connectionInfo
        val d = wifi.dhcpInfo
        if (info == null || d == null || d.ipAddress == 0) {
            content.addView(card("Tidak terhubung", listOf("Status" to "Hubungkan HP ke jaringan WiFi")))
            return
        }

        val ssid = info.ssid?.trim('"') ?: "?"
        val rssi = info.rssi
        val freq = info.frequency
        val (qText, qColor) = quality(rssi)
        val pct = ((rssi + 100) * 2).coerceIn(0, 100)

        val hero = LinearLayout(this)
        hero.orientation = LinearLayout.VERTICAL
        hero.setPadding(dp(16), dp(16), dp(16), dp(16))
        hero.background = rounded(cCard, 18)
        hero.layoutParams = lp()
        hero.addView(tv(if (ssid.contains("unknown")) "(SSID disembunyikan - izinkan Lokasi)" else ssid, 20f, Color.WHITE, true))
        hero.addView(tv("${bars(rssi)}  $rssi dBm  •  $pct%", 16f, qColor, true))
        hero.addView(tv(qText, 13f, qColor))
        content.addView(hero)

        var std = "-"
        if (Build.VERSION.SDK_INT >= 30) {
            std = when (info.wifiStandard) {
                4 -> "Wi-Fi 4 (802.11n)"
                5 -> "Wi-Fi 5 (802.11ac)"
                6 -> "Wi-Fi 6 (802.11ax)"
                8 -> "Wi-Fi 7 (802.11be)"
                1 -> "Legacy (a/b/g)"
                else -> "-"
            }
        }
        val match = safeScan().firstOrNull { it.BSSID == info.bssid }
        val mac = info.macAddress ?: "-"

        content.addView(card("Koneksi", listOf(
            "BSSID" to (info.bssid ?: "-"),
            "Band" to band(freq),
            "Channel" to "${chan(freq)}  ($freq MHz)",
            "Lebar channel" to (match?.let { width(it.channelWidth) } ?: "-"),
            "Link speed" to "${info.linkSpeed} Mbps",
            "Standar" to std,
            "Keamanan" to (match?.let { sec(it.capabilities) } ?: "-"),
            "MAC HP ini" to if (mac.startsWith("02:00:00")) "Disembunyikan Android (privasi)" else mac
        )))

        val cidr = Integer.bitCount(d.netmask)
        content.addView(card("Alamat IP", listOf(
            "IP HP" to dhcpIp(d.ipAddress),
            "Subnet" to "${dhcpIp(d.netmask)}  (/$cidr)",
            "Gateway/Router" to dhcpIp(d.gateway),
            "DNS 1" to dhcpIp(d.dns1),
            "DNS 2" to dhcpIp(d.dns2),
            "DHCP server" to dhcpIp(d.serverAddress),
            "Lease" to if (d.leaseDuration > 0) "${d.leaseDuration / 60} menit" else "-"
        )))
    }

    // ---------- tab 1: scan jaringan ----------

    private fun startScan() {
        val ok = try { wifi.startScan() } catch (e: SecurityException) { false }
        Toast.makeText(
            this,
            if (ok) "Scanning..." else "Scan dibatasi Android (maks 4x / 2 menit). Menampilkan hasil terakhir.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun renderScan() {
        content.addView(button("⟳  Scan Ulang") { startScan() })
        if (!hasLoc()) {
            content.addView(card("Izin diperlukan", listOf("Info" to "Izinkan akses Lokasi agar hasil scan muncul.")))
            return
        }
        val res = safeScan().sortedByDescending { it.level }
        val connected = try { wifi.connectionInfo?.bssid } catch (e: Exception) { null }

        val g24 = res.filter { it.frequency in 2400..2500 }
        if (g24.isNotEmpty()) {
            val counts = listOf(1, 6, 11).associateWith { c -> g24.count { Math.abs(chan(it.frequency) - c) <= 2 } }
            val best = counts.minByOrNull { it.value }
            content.addView(card("Saran channel 2.4 GHz", listOf(
                "Paling sepi" to "Channel ${best?.key}  (${best?.value} jaringan bentrok)",
                "Kepadatan" to counts.entries.joinToString("  ") { "Ch${it.key}: ${it.value}" }
            )))
        }

        content.addView(tv("${res.size} jaringan ditemukan", 13f, cSub).apply { setPadding(dp(4), 0, 0, dp(8)) })

        for (r in res) {
            val name = if (r.SSID.isNullOrEmpty()) "(Tersembunyi)" else r.SSID
            val (q, col) = quality(r.level)
            val c = card(
                (if (r.BSSID == connected) "● " else "") + name,
                listOf(
                    "Sinyal" to "${bars(r.level)}  ${r.level} dBm  ($q)",
                    "Band / Ch" to "${band(r.frequency)}  •  Ch ${chan(r.frequency)}  •  ${width(r.channelWidth)}",
                    "Keamanan" to sec(r.capabilities),
                    "BSSID" to r.BSSID
                )
            )
            (c.getChildAt(0) as TextView).setTextColor(if (r.BSSID == connected) cGood else col)
            content.addView(c)
        }
    }

    // ---------- tab 2: perangkat ----------

    private fun renderDevices() {
        content.addView(button(if (scanning) "Scanning..." else "🔍  Scan Perangkat") { scanDevices() })
        val st = tv(devStatus, 13f, cSub)
        st.setPadding(dp(4), 0, dp(4), dp(10))
        devStatusView = st
        content.addView(st)

        for (d in devices) {
            content.addView(card(
                d.ip + (if (d.tag.isNotEmpty()) "   ${d.tag}" else ""),
                listOf(
                    "Hostname" to (if (d.host.isEmpty()) "-" else d.host),
                    "MAC" to d.mac,
                    "Tipe MAC" to macInfo(d.mac)
                )
            ))
        }
        content.addView(tv(
            "Catatan: Android 10+ memblokir pembacaan tabel ARP, jadi MAC bisa kosong (-). Perangkat yang memblokir ping/port juga bisa tidak terdeteksi.",
            11f, cSub
        ).apply { setPadding(dp(4), dp(6), dp(4), dp(20)) })
    }

    private fun macInfo(mac: String): String {
        if (mac.length < 17) return "-"
        val first = mac.substring(0, 2).toIntOrNull(16) ?: return "-"
        if ((first and 2) != 0) return "MAC acak/privat (HP modern)"
        return OUI[mac.substring(0, 8)] ?: "Vendor tidak dikenal"
    }

    private fun readArp(): Map<String, String> {
        val m = HashMap<String, String>()
        try {
            File("/proc/net/arp").forEachLine { l ->
                val p = l.trim().split(Regex("\\s+"))
                if (p.size >= 4 && p[0] != "IP" && p[3] != "00:00:00:00:00:00" && p[2] != "0x0") {
                    m[p[0]] = p[3].uppercase()
                }
            }
        } catch (e: Exception) { }
        return m
    }

    private fun alive(ip: String): Boolean {
        try {
            if (InetAddress.getByName(ip).isReachable(350)) return true
        } catch (e: Exception) { }
        val ports = intArrayOf(80, 443, 53, 22, 445, 139, 8080, 62078, 8008, 554, 5555)
        for (p in ports) {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(ip, p), 150)
                    return true
                }
            } catch (e: Exception) {
                val m = e.message ?: ""
                if (m.contains("ECONNREFUSED") || m.contains("refused")) return true
            }
        }
        return false
    }

    private fun scanDevices() {
        if (scanning) return
        val d = wifi.dhcpInfo
        if (d == null || d.ipAddress == 0) {
            devStatus = "Belum terhubung ke WiFi."
            showTab(2)
            return
        }
        scanning = true
        devices = emptyList()
        devStatus = "Mulai scan..."
        showTab(2)

        val myIp = Integer.reverseBytes(d.ipAddress)
        val gwIp = Integer.reverseBytes(d.gateway)
        val base = myIp and -256 // /24
        val myStr = ipStr(myIp)
        val gwStr = ipStr(gwIp)

        Thread {
            val pool = Executors.newFixedThreadPool(40)
            val done = AtomicInteger(0)
            try {
                val tasks = (1..254).map { i ->
                    Callable<String?> {
                        val ip = ipStr(base + i)
                        val ok = ip == myStr || ip == gwStr || alive(ip)
                        val n = done.incrementAndGet()
                        if (n % 6 == 0 || n == 254) {
                            ui.post {
                                devStatus = "Scanning... $n/254"
                                devStatusView?.text = devStatus
                            }
                        }
                        if (ok) ip else null
                    }
                }
                val aliveIps = pool.invokeAll(tasks).mapNotNull {
                    try { it.get() } catch (e: Exception) { null }
                }.toMutableSet()

                val arp = readArp()
                for ((ip, _) in arp) if (ip.startsWith(ipStr(base).substringBeforeLast(".") + ".")) aliveIps.add(ip)

                val nameTasks = aliveIps.map { ip ->
                    Callable<Pair<String, String>> {
                        val h = try { InetAddress.getByName(ip).canonicalHostName } catch (e: Exception) { ip }
                        ip to (if (h == ip) "" else h)
                    }
                }
                val names = HashMap<String, String>()
                for (f in pool.invokeAll(nameTasks, 8, TimeUnit.SECONDS)) {
                    try { val (ip, h) = f.get(); names[ip] = h } catch (e: Exception) { }
                }

                val list = aliveIps.sortedBy { it.substringAfterLast(".").toIntOrNull() ?: 0 }.map { ip ->
                    val tag = when (ip) {
                        myStr -> "(HP ini)"
                        gwStr -> "(Router)"
                        else -> ""
                    }
                    Dev(ip, if (ip == myStr) "-" else (arp[ip] ?: "-"), names[ip] ?: "", tag)
                }
                ui.post {
                    scanning = false
                    devices = list
                    devStatus = "Selesai: ${list.size} perangkat ditemukan."
                    if (tab == 2) showTab(2)
                }
            } catch (e: Exception) {
                ui.post {
                    scanning = false
                    devStatus = "Scan gagal: ${e.message}"
                    if (tab == 2) showTab(2)
                }
            } finally {
                pool.shutdownNow()
            }
        }.start()
    }
}
