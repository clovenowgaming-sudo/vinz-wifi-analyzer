package com.vinz.wifianalyzer

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

val cBg = Color.parseColor("#0F1220")
val cCard = Color.parseColor("#1B2036")
val cAccent = Color.parseColor("#4F8CFF")
val cSub = Color.parseColor("#9AA4C7")
val cGood = Color.parseColor("#3DDC84")
val cWarn = Color.parseColor("#FFB020")
val cBad = Color.parseColor("#FF5C5C")

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.rounded(color: Int, r: Int): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(color)
    g.cornerRadius = dp(r).toFloat()
    return g
}

fun Context.tv(t: String, size: Float = 14f, color: Int = Color.WHITE, bold: Boolean = false): TextView {
    val v = TextView(this)
    v.text = t
    v.textSize = size
    v.setTextColor(color)
    if (bold) v.setTypeface(v.typeface, Typeface.BOLD)
    return v
}

fun Context.lp(top: Int = 0, bottom: Int = 10): LinearLayout.LayoutParams {
    val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    p.setMargins(0, dp(top), 0, dp(bottom))
    return p
}

fun Context.card(title: String?, rows: List<Pair<String, String>>, titleColor: Int = Color.WHITE): LinearLayout {
    val c = LinearLayout(this)
    c.orientation = LinearLayout.VERTICAL
    c.setPadding(dp(14), dp(12), dp(14), dp(12))
    c.background = rounded(cCard, 16)
    c.layoutParams = lp()
    if (title != null) {
        val t = tv(title, 16f, titleColor, true)
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

fun Context.button(text: String, onClick: () -> Unit): TextView {
    val b = tv(text, 15f, Color.WHITE, true)
    b.gravity = Gravity.CENTER
    b.setPadding(0, dp(12), 0, dp(12))
    b.background = rounded(cAccent, 14)
    b.layoutParams = lp(0, 10)
    b.setOnClickListener { onClick() }
    return b
}

// level: 0 bagus, 1 peringatan, 2 bahaya, 3 info
fun Context.finding(level: Int, title: String, detail: String): LinearLayout {
    val col = when (level) { 0 -> cGood; 1 -> cWarn; 2 -> cBad; else -> cAccent }
    val icon = when (level) { 0 -> "✔"; 1 -> "⚠"; 2 -> "✖"; else -> "ℹ" }
    val c = LinearLayout(this)
    c.orientation = LinearLayout.HORIZONTAL
    c.setPadding(dp(14), dp(12), dp(14), dp(12))
    c.background = rounded(cCard, 14)
    c.layoutParams = lp(0, 8)
    val i = tv(icon, 18f, col, true)
    i.setPadding(0, 0, dp(12), 0)
    val box = LinearLayout(this)
    box.orientation = LinearLayout.VERTICAL
    box.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    box.addView(tv(title, 14f, col, true))
    box.addView(tv(detail, 12f, cSub))
    c.addView(i)
    c.addView(box)
    return c
}

fun ipStr(v: Int): String = "${(v ushr 24) and 255}.${(v ushr 16) and 255}.${(v ushr 8) and 255}.${v and 255}"

fun dhcpIp(v: Int): String = if (v == 0) "-" else ipStr(Integer.reverseBytes(v))

fun chan(f: Int): Int = when {
    f == 2484 -> 14
    f in 2400..2483 -> (f - 2407) / 5
    f in 4900..5900 -> (f - 5000) / 5
    f in 5925..7125 -> (f - 5950) / 5
    else -> 0
}

fun band(f: Int): String = when {
    f < 3000 -> "2.4 GHz"
    f < 5900 -> "5 GHz"
    else -> "6 GHz"
}

// 0 terbuka, 1 WEP, 2 WPA1, 3 WPA2, 4 WPA3, 5 enterprise
fun secClass(cap: String): Int = when {
    cap.contains("EAP") -> 5
    cap.contains("SAE") -> 4
    cap.contains("WPA2") || cap.contains("RSN") || cap.contains("OWE") -> 3
    cap.contains("WPA") -> 2
    cap.contains("WEP") -> 1
    else -> 0
}

fun sec(cap: String): String {
    val base = when (secClass(cap)) {
        5 -> "Enterprise (802.1X)"
        4 -> if (cap.contains("PSK")) "WPA2/WPA3" else "WPA3"
        3 -> "WPA2"
        2 -> "WPA (lemah)"
        1 -> "WEP (tidak aman)"
        else -> "Terbuka"
    }
    return if (cap.contains("WPS")) "$base + WPS" else base
}

fun width(w: Int): String = when (w) {
    0 -> "20 MHz"
    1 -> "40 MHz"
    2 -> "80 MHz"
    3 -> "160 MHz"
    4 -> "80+80 MHz"
    else -> "-"
}

fun bars(rssi: Int): String = when {
    rssi >= -55 -> "▂▄▆█"
    rssi >= -65 -> "▂▄▆▁"
    rssi >= -75 -> "▂▄▁▁"
    rssi >= -85 -> "▂▁▁▁"
    else -> "▁▁▁▁"
}

fun quality(rssi: Int): Pair<String, Int> = when {
    rssi >= -55 -> Pair("Sangat bagus", cGood)
    rssi >= -67 -> Pair("Bagus", cGood)
    rssi >= -75 -> Pair("Lumayan", cWarn)
    rssi >= -85 -> Pair("Lemah", cBad)
    else -> Pair("Sangat lemah", cBad)
}
