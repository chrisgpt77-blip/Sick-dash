package com.sickdash

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.sickdash.service.CanService
import com.sickdash.service.ConnectionManager
import com.sickdash.ui.DashRenderer
import com.sickdash.ui.DashView

class MainActivity : AppCompatActivity() {
    private lateinit var dash: DashView
    private lateinit var controls: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        dash = findViewById(R.id.dash)
        controls = findViewById(R.id.controls)
        dash.setOnClickListener {
            controls.visibility = if (controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<Button>(R.id.btn_page).setOnClickListener {
            val r = dash.renderer
            r.page = if (r.page == DashRenderer.Page.DASH) DashRenderer.Page.RACE else DashRenderer.Page.DASH
        }
        findViewById<Button>(R.id.btn_reset).setOnClickListener { dash.renderer.resetPeaks() }
        findViewById<Button>(R.id.btn_sniffer).setOnClickListener { startActivity(Intent(this, SnifferActivity::class.java)) }
        findViewById<Button>(R.id.btn_settings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        requestNeededPermissions()
    }

    override fun onStart() {
        super.onStart()
        ConnectionManager.acquire(this, CLIENT)
        runCatching { CanService.start(this) }
    }

    override fun onStop() {
        ConnectionManager.release(CLIENT)
        super.onStop()
    }

    private fun requestNeededPermissions() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        ConnectionManager.restart()
    }

    private companion object {
        const val CLIENT = "phone-ui"
    }
}
