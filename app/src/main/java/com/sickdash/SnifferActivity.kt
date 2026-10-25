package com.sickdash

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sickdash.data.VehicleData
import com.sickdash.service.ConnectionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Live table of every CAN id on the bus. Bytes that changed in the last second light up,
 * which is how you find e.g. the RPM bytes: rev the engine and watch which ones move.
 */
class SnifferActivity : AppCompatActivity() {
    private var paused = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sniffer)
        title = "CAN sniffer"
        val table = findViewById<TextView>(R.id.table)
        val header = findViewById<TextView>(R.id.header)
        table.typeface = Typeface.MONOSPACE
        header.typeface = Typeface.MONOSPACE
        findViewById<Button>(R.id.btn_pause).apply {
            setOnClickListener { paused = !paused; text = if (paused) "Resume" else "Pause" }
        }
        findViewById<Button>(R.id.btn_clear).setOnClickListener { VehicleData.sniffer.clear() }

        lifecycleScope.launch {
            while (isActive) {
                if (!paused) render(table, header)
                delay(250)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ConnectionManager.acquire(this, "sniffer")
    }

    override fun onStop() {
        ConnectionManager.release("sniffer")
        super.onStop()
    }

    private fun render(table: TextView, header: TextView) {
        val now = System.currentTimeMillis()
        val rows = VehicleData.sniffer.values.sortedBy { it.id }
        val status = VehicleData.status.value.message
        header.text = if (rows.isEmpty()) {
            "$status\n\nNo raw frames yet. The sniffer needs a raw-CAN source: a USB/Wi-Fi slcan adapter, " +
                "or an ELM327 with \"Read mode\" set to \"Raw CAN monitor\" in Settings."
        } else {
            "$status\n%-4s %-5s %6s  %s".format("ID", "HZ", "COUNT", "DATA")
        }
        val sb = SpannableStringBuilder()
        for (e in rows) {
            synchronized(e) {
                val hz = if (e.periodMs > 0) 1000f / e.periodMs else 0f
                val stale = now - e.lastSeen > 2000
                val start = sb.length
                sb.append("%03X  %5.1f %6d  ".format(e.id, hz, e.count))
                if (stale) sb.setSpan(ForegroundColorSpan(Color.GRAY), start, sb.length, 0)
                for (i in e.data.indices) {
                    val s = sb.length
                    sb.append("%02X ".format(e.data[i]))
                    val age = now - e.changedAt[i]
                    val color = when {
                        age < 300 -> Color.rgb(255, 80, 60)
                        age < 1500 -> Color.rgb(255, 180, 0)
                        else -> Color.rgb(180, 190, 200)
                    }
                    sb.setSpan(ForegroundColorSpan(color), s, s + 2, 0)
                }
                sb.append('\n')
            }
        }
        table.text = sb
    }
}
