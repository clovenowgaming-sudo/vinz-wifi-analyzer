package com.vinz.wifianalyzer

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private class Probe(val ip: String, val ms: Long, val ports: List<Int>)

private class Info {
    @Volatile var host = ""
    @Volatile var mdns = ""
    @Volatile var nb = ""
    @Volatile var upnp = ""
    @Volatile var model = ""
    @Volatile var title = ""
}

class MainActivity : Activity() {

    data class Dev(
        val ip: String, val mac: String, val name: String, val host: String, val tag: String,
        val ports: String, val ms: Long, val kind: String, val model: String, val vendor: String
    )

    lateinit var wifi: WifiManager
    lateinit var cm: ConnectivityManager
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var tabViews: List<TextView>
    private lateinit var speedTab: SpeedTab
    private lateinit var secTab: SecurityTab
    private lateinit var hwTab: HardwareTab
    val ui = Handler(Looper.getMainLooper())

    var tab = 0
    private var scanning = false
    private var lastScan = 0L
    private var devices: List<Dev> = emptyList()
    private var devStatus = "Tekan tombol untuk scan perangkat yang terhubung di WiFi ini."
    private var macNote = ""
    private var rootMsg = ""
    private var devStatusView: TextView? = null

    private var saved: List<NetTools.Saved> = emptyList()
    private var pwStatus = ""
    private var pwLoading = false

    private val PORTS = intArrayOf(80, 443, 53, 22, 23, 139, 445, 554, 7000, 8008, 8009, 8080, 9100, 5555, 62078)

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

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        speedTab = SpeedTab(this)
        secTab = SecurityTab(this)
        hwTab = HardwareTab(this)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(cBg)
        root.setPadding(dp(14), dp(34), dp(14), dp(8))
        root.addView(tv("WiFi Analyzer", 24f, Color.WHITE, true))
        root.addView(tv("Mod By Vinz :3", 12f, cSub))

        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        hs.layoutParams = lp(12, 10)
        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        val names = listOf("Info", "Jaringan", "Perangkat", "Speed", "Keamanan", "Hardware")
        tabViews = names.mapIndexed { i, n ->
            val t = tv(n, 14f, Color.WHITE, true)
            t.gravity = Gravity.CENTER
            t.setPadding(dp(16), dp(10), dp(16), dp(10))
            val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            p.setMargins(dp(3), 0, dp(3), 0)
            t.layoutParams = p
            t.setOnClickListener { showTab(i) }
            t
        }
        tabViews.forEach { tabs.addView(it) }
        hs.addView(tabs)
        root.addView(hs)

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

    // ---------- permission / lokasi ----------

    fun hasLoc(): Boolean = Build.VERSION.SDK_INT < 23 ||
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun locEnabled(): Boolean {
        return try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
            else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) { true }
    }

    fun requestPerms() {
        if (Build.VERSION.SDK_INT >= 23 && !hasLoc()) {
            val p = if (Build.VERSION.SDK_INT >= 33)
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, "android.permission.NEARBY_WIFI_DEVICES")
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            requestPermissions(p, 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        showTab(tab)
    }

    private fun openLocationSettings() {
        try { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (e: Exception) { }
    }

    private fun openWifiSettings() {
        try { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } catch (e: Exception) { }
    }

    // ---------- tabs ----------

    fun refresh(i: Int) {
        if (tab == i) showTab(i)
    }

    fun showTab(i: Int) {
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
            3 -> speedTab.render(content)
            4 -> secTab.render(content)
            5 -> hwTab.render(content)
        }
    }

    private fun safeScan(): List<ScanResult> =
        try { wifi.scanResults ?: emptyList() } catch (e: SecurityException) { emptyList() }

    private fun permCards() {
        if (!hasLoc()) {
            content.addView(card("Izin Lokasi belum diberikan", listOf(
                "Info" to "Android butuh izin Lokasi (pilih \"Presisi/Precise\") supaya nama WiFi dan hasil scan terbaca."
            ), cWarn))
            content.addView(button("Izinkan Lokasi") { requestPerms() })
        } else if (!locEnabled()) {
            content.addView(card("Layanan Lokasi (GPS) mati", listOf(
                "Info" to "Android menyembunyikan hasil scan WiFi kalau Lokasi mati. Nyalakan Lokasi lalu kembali ke app."
            ), cWarn))
            content.addView(button("Buka Pengaturan Lokasi") { openLocationSettings() })
        }
    }

    // ---------- tab 0: info ----------

    private fun renderInfo() {
        permCards()
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
        val q = quality(rssi)
        val pct = ((rssi + 100) * 2).coerceIn(0, 100)

        val hero = LinearLayout(this)
        hero.orientation = LinearLayout.VERTICAL
        hero.setPadding(dp(16), dp(16), dp(16), dp(16))
        hero.background = rounded(cCard, 18)
        hero.layoutParams = lp()
        hero.addView(tv(if (ssid.contains("unknown")) "(SSID disembunyikan - nyalakan Lokasi)" else ssid, 20f, Color.WHITE, true))
        hero.addView(tv("${bars(rssi)}  $rssi dBm  •  $pct%", 16f, q.second, true))
        hero.addView(tv(q.first, 13f, q.second))
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
        val pw = saved.firstOrNull { it.ssid == ssid }

        val rows = ArrayList<Pair<String, String>>()
        rows.add("BSSID" to (info.bssid ?: "-"))
        rows.add("Band" to band(freq))
        rows.add("Channel" to "${chan(freq)}  ($freq MHz)")
        rows.add("Lebar channel" to (if (match != null) width(match.channelWidth) else "-"))
        rows.add("Link speed" to "${info.linkSpeed} Mbps")
        rows.add("Standar" to std)
        rows.add("Keamanan" to (if (match != null) sec(match.capabilities) else "-"))
        if (pw != null) rows.add("Password" to (if (pw.pass.isEmpty()) "(terbuka)" else pw.pass))
        rows.add("MAC HP ini" to (if (mac.startsWith("02:00:00")) "Disembunyikan Android (privasi)" else mac))
        content.addView(card("Koneksi", rows))

        val cidr = Integer.bitCount(d.netmask)
        content.addView(card("Alamat IP", listOf(
            "IP HP" to dhcpIp(d.ipAddress),
            "Subnet" to "${dhcpIp(d.netmask)}  (/$cidr)",
            "Gateway/Router" to dhcpIp(d.gateway),
            "DNS 1" to dhcpIp(d.dns1),
            "DNS 2" to dhcpIp(d.dns2),
            "DHCP server" to dhcpIp(d.serverAddress),
            "Lease" to (if (d.leaseDuration > 0) "${d.leaseDuration / 60} menit" else "-")
        )))
    }

    // ---------- tab 1: scan jaringan + password tersimpan ----------

    private fun startScan(silent: Boolean) {
        lastScan = System.currentTimeMillis()
        val ok = try { wifi.startScan() } catch (e: SecurityException) { false }
        if (!silent) {
            Toast.makeText(
                this,
                if (ok) "Scanning..." else "Scan dibatasi Android (maks 4x / 2 menit). Menampilkan hasil terakhir.",
                Toast.LENGTH_SHORT
            ).show()
        }
        ui.postDelayed({ if (tab == 1) showTab(1) }, 3500)
    }

    private fun loadSaved() {
        if (pwLoading) return
        pwLoading = true
        pwStatus = "Meminta akses Root... (tekan Izinkan/Grant di pop-up root)"
        showTab(1)
        Thread {
            val r = NetTools.savedWifi()
            ui.post {
                pwLoading = false
                saved = r.first
                pwStatus = r.second
                if (tab == 1) showTab(1)
            }
        }.start()
    }

    private fun pwCard() {
        if (saved.isEmpty()) {
            content.addView(card("Password WiFi tersimpan", listOf(
                "Info" to (if (pwStatus.isEmpty())
                    "Tampilkan password WiFi yang pernah tersimpan di HP ini, termasuk yang sedang tidak tersambung. Butuh akses Root."
                else pwStatus)
            )))
            content.addView(button(if (pwLoading) "Membaca..." else "🔑  Tampilkan Password Tersimpan (Root)") { loadSaved() })
            content.addView(button("Tanpa Root: buka Pengaturan WiFi (Bagikan QR)") { openWifiSettings() })
        } else {
            content.addView(card("Password tersimpan (${saved.size})", saved.map {
                Pair(it.ssid, if (it.pass.isEmpty()) "(terbuka)" else it.pass)
            }, cGood))
        }
    }

    private fun renderScan() {
        content.addView(button("⟳  Scan Ulang") { startScan(false) })
        pwCard()
        permCards()
        if (!hasLoc()) return

        if (System.currentTimeMillis() - lastScan > 30000) startScan(true)

        val res = safeScan().sortedByDescending { it.level }
        val connected = try { wifi.connectionInfo?.bssid } catch (e: Exception) { null }

        if (res.isEmpty()) {
            content.addView(card("Belum ada hasil scan", listOf(
                "Cek 1" to "Layanan Lokasi/GPS harus ON",
                "Cek 2" to "Izin Lokasi harus \"Presisi\", bukan \"Perkiraan\"",
                "Cek 3" to "Tunggu 3-5 detik lalu tekan Scan Ulang",
                "Cek 4" to "Matikan mode hemat daya / hemat baterai ekstrem"
            )))
            return
        }

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
            val qq = quality(r.level)
            val isMe = r.BSSID == connected
            val rows = ArrayList<Pair<String, String>>()
            rows.add("Sinyal" to "${bars(r.level)}  ${r.level} dBm  (${qq.first})")
            rows.add("Band / Ch" to "${band(r.frequency)}  •  Ch ${chan(r.frequency)}  •  ${width(r.channelWidth)}")
            rows.add("Keamanan" to sec(r.capabilities))
            val pw = saved.firstOrNull { it.ssid == r.SSID }
            if (pw != null) rows.add("Password" to (if (pw.pass.isEmpty()) "(terbuka)" else pw.pass))
            rows.add("BSSID" to r.BSSID)
            content.addView(card((if (isMe) "● " else "") + name, rows, if (isMe) cGood else qq.second))
        }
    }

    // ---------- tab 2: perangkat ----------

    private fun requestRoot() {
        rootMsg = "Meminta akses Root... (tekan Izinkan/Grant di pop-up root)"
        showTab(2)
        Thread {
            val ok = NetTools.hasRoot(true)
            ui.post {
                rootMsg = if (ok) "Root: AKTIF ✔ (MAC akan dibaca via root saat scan)"
                else "Root: tidak tersedia / ditolak"
                if (tab == 2) showTab(2)
            }
        }.start()
    }

    private fun renderDevices() {
        content.addView(button(if (scanning) "Scanning..." else "🔍  Scan Perangkat") { scanDevices() })
        content.addView(button("Izinkan Root (untuk baca MAC)") { requestRoot() })
        if (rootMsg.isNotEmpty()) {
            content.addView(tv(rootMsg, 12f, if (NetTools.rootGranted()) cGood else cWarn).apply { setPadding(dp(4), 0, dp(4), dp(6)) })
        }
        val st = tv(devStatus, 13f, cSub)
        st.setPadding(dp(4), 0, dp(4), dp(8))
        devStatusView = st
        content.addView(st)
        if (macNote.isNotEmpty()) {
            content.addView(tv(macNote, 12f, cSub).apply { setPadding(dp(4), 0, dp(4), dp(10)) })
        }

        for (d in devices) {
            val rows = ArrayList<Pair<String, String>>()
            rows.add("Nama" to (if (d.name.isEmpty()) "-" else d.name))
            rows.add("Jenis" to d.kind)
            if (d.model.isNotEmpty()) rows.add("Model" to d.model)
            if (d.host.isNotEmpty() && d.host != d.name) rows.add("Hostname" to d.host)
            rows.add("MAC" to (if (d.mac == "-") macMissing() else d.mac))
            if (d.mac != "-") {
                rows.add("Vendor" to when {
                    NetTools.isRandomMac(d.mac) -> "MAC acak/privat (HP modern)"
                    d.vendor.isNotEmpty() -> d.vendor
                    else -> "Mencari..."
                })
            }
            rows.add("Port terbuka" to d.ports)
            rows.add("Respon" to (if (d.ms < 0) "-" else "${d.ms} ms"))
            content.addView(card(d.ip + (if (d.tag.isNotEmpty()) "   ${d.tag}" else ""), rows))
        }
        content.addView(tv(
            "Catatan: nama diambil dari mDNS, NetBIOS, UPnP, judul web, dan DNS. Perangkat yang menyembunyikan diri (kebanyakan HP Android) tidak punya nama yang bisa dibaca dari jaringan.",
            11f, cSub
        ).apply { setPadding(dp(4), dp(6), dp(4), dp(20)) })
    }

    private fun macMissing(): String =
        if (Build.VERSION.SDK_INT >= 30) "Diblokir Android 11+ (butuh Root)" else "Tidak terbaca (coba izinkan Root)"

    private fun probe(ip: String): Probe? {
        var up = false
        var ms = -1L
        val open = ArrayList<Int>()
        var t = System.currentTimeMillis()
        try {
            if (InetAddress.getByName(ip).isReachable(300)) {
                up = true
                ms = System.currentTimeMillis() - t
            }
        } catch (e: Exception) { }
        for (p in PORTS) {
            t = System.currentTimeMillis()
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(ip, p), 120)
                    open.add(p)
                    up = true
                    if (ms < 0) ms = System.currentTimeMillis() - t
                }
            } catch (e: Exception) {
                val m = e.message ?: ""
                if (m.contains("ECONNREFUSED") || m.contains("refused")) {
                    up = true
                    if (ms < 0) ms = System.currentTimeMillis() - t
                }
            }
        }
        return if (up) Probe(ip, ms, open) else null
    }

    private fun guessKind(ports: List<Int>, gw: Boolean, me: Boolean, hint: String): String = when {
        me -> Build.MANUFACTURER + " " + Build.MODEL + " (HP ini)"
        gw -> "Router / Gateway"
        hint.contains("iphone") -> "iPhone (Apple)"
        hint.contains("ipad") -> "iPad (Apple)"
        hint.contains("macbook") || hint.contains("imac") -> "Mac (Apple)"
        hint.contains("android") || hint.contains("galaxy") || hint.contains("redmi") ||
            hint.contains("xiaomi") || hint.contains("oppo") || hint.contains("vivo") ||
            hint.contains("realme") || hint.contains("infinix") || hint.contains("tecno") -> "HP Android"
        hint.contains("desktop-") || hint.contains("laptop-") || hint.contains("windows") -> "PC / Laptop Windows"
        hint.contains("chromecast") -> "Chromecast"
        hint.contains("bravia") || hint.contains("roku") || hint.contains("smarttv") || hint.contains("smart tv") -> "Smart TV"
        62078 in ports -> "iPhone / iPad (Apple)"
        7000 in ports -> "Apple TV / AirPlay"
        8009 in ports || 8008 in ports -> "Chromecast / Google TV"
        554 in ports -> "Kamera IP / CCTV"
        9100 in ports -> "Printer"
        5555 in ports -> "Android (ADB terbuka!)"
        445 in ports || 139 in ports -> "PC Windows / NAS"
        22 in ports -> "Linux / perangkat SSH"
        80 in ports || 443 in ports || 8080 in ports -> "Perangkat dengan web UI (IoT/AP/repeater)"
        else -> "Tidak diketahui (kemungkinan HP/laptop, semua port tertutup)"
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
        macNote = ""
        devStatus = "Mulai scan..."
        showTab(2)

        val myIp = Integer.reverseBytes(d.ipAddress)
        val gwIp = Integer.reverseBytes(d.gateway)
        val base = myIp and -256
        val myStr = ipStr(myIp)
        val gwStr = ipStr(gwIp)
        val prefix = myStr.substringBeforeLast(".") + "."
        val myName = try {
            Settings.Global.getString(contentResolver, "device_name") ?: Build.MODEL
        } catch (e: Exception) { Build.MODEL }

        Thread {
            val pool = Executors.newFixedThreadPool(64)
            val done = AtomicInteger(0)
            var finalList: List<Dev> = emptyList()
            try {
                val tasks = (1..254).map { i ->
                    Callable<Probe?> {
                        val ip = ipStr(base + i)
                        val r: Probe? = if (ip == myStr) Probe(ip, 0L, emptyList())
                        else probe(ip) ?: (if (ip == gwStr) Probe(ip, -1L, emptyList()) else null)
                        val n = done.incrementAndGet()
                        if (n % 6 == 0 || n == 254) {
                            ui.post {
                                devStatus = "Scanning... $n/254"
                                devStatusView?.text = devStatus
                            }
                        }
                        r
                    }
                }
                val found = HashMap<String, Probe>()
                for (f in pool.invokeAll(tasks)) {
                    try { val p = f.get(); if (p != null) found[p.ip] = p } catch (e: Exception) { }
                }

                val arpRes = NetTools.arp()
                val arp = arpRes.first
                for ((ip, _) in arp) {
                    if (ip.startsWith(prefix) && !found.containsKey(ip)) found[ip] = Probe(ip, -1L, emptyList())
                }

                ui.post { devStatus = "Mencari nama perangkat..."; devStatusView?.text = devStatus }

                val ss = NetTools.ssdp()
                val infos = HashMap<String, Info>()
                for (ip in found.keys) infos[ip] = Info()

                val nameTasks = found.keys.filter { it != myStr }.map { ip ->
                    Callable<Boolean> {
                        val inf = infos[ip]!!
                        val p = found[ip]
                        inf.mdns = NetTools.mdnsName(ip)
                        inf.nb = NetTools.netbiosName(ip)
                        val s = ss[ip]
                        if (s != null && s.second.isNotEmpty()) {
                            val u = NetTools.upnpInfo(s.second, ip)
                            inf.upnp = u.first
                            inf.model = u.second
                        }
                        if (p != null) {
                            val hp = if (80 in p.ports) 80 else if (8080 in p.ports) 8080 else 0
                            if (hp != 0) inf.title = NetTools.httpTitle(ip, hp)
                        }
                        inf.host = try {
                            val h = InetAddress.getByName(ip).canonicalHostName
                            if (h == ip) "" else h
                        } catch (e: Exception) { "" }
                        true
                    }
                }
                pool.invokeAll(nameTasks, 15, TimeUnit.SECONDS)

                finalList = found.values
                    .sortedBy { it.ip.substringAfterLast(".").toIntOrNull() ?: 0 }
                    .map { p ->
                        val isMe = p.ip == myStr
                        val isGw = p.ip == gwStr
                        val tag = if (isMe) "(HP ini)" else if (isGw) "(Router)" else ""
                        val inf = infos[p.ip]!!
                        val name = if (isMe) myName else
                            listOf(inf.upnp, inf.mdns, inf.nb, inf.host, inf.title).firstOrNull { it.isNotEmpty() } ?: ""
                        val hint = (name + " " + inf.model + " " + inf.host).lowercase()
                        Dev(
                            p.ip,
                            if (isMe) "-" else (arp[p.ip] ?: "-"),
                            name,
                            if (isMe) "" else inf.host,
                            tag,
                            if (p.ports.isEmpty()) "-" else p.ports.joinToString(", "),
                            p.ms,
                            guessKind(p.ports, isGw, isMe, hint),
                            inf.model,
                            ""
                        )
                    }
                val note = if (arp.isNotEmpty()) "MAC dibaca via ${arpRes.second}."
                else if (Build.VERSION.SDK_INT >= 29)
                    "MAC tidak terbaca. Android 10+ memblokir tabel ARP untuk app biasa dan tidak ada izin biasa untuk membukanya. Satu-satunya jalan: tekan \"Izinkan Root\" (HP harus di-root)."
                else "MAC tidak terbaca di HP ini."
                val fl = finalList
                ui.post {
                    scanning = false
                    devices = fl
                    macNote = note
                    devStatus = "Selesai: ${fl.size} perangkat ditemukan."
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

            // cari vendor dari MAC (online, pelan: 1 request / detik)
            try {
                val withMac = finalList.filter { it.mac != "-" && !NetTools.isRandomMac(it.mac) }.take(12)
                if (withMac.isNotEmpty()) {
                    val vmap = HashMap<String, String>()
                    for (dv in withMac) {
                        val v = NetTools.vendor(dv.mac)
                        vmap[dv.ip] = if (v.isEmpty()) "Tidak dikenal" else v
                        Thread.sleep(1100)
                    }
                    ui.post {
                        devices = devices.map { x ->
                            val v = vmap[x.ip]
                            if (v != null) x.copy(vendor = v) else x
                        }
                        if (tab == 2) showTab(2)
                    }
                }
            } catch (e: Exception) { }
        }.start()
    }
}
