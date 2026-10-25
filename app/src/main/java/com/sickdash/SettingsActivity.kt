package com.sickdash

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.sickdash.data.CarPreset
import com.sickdash.data.DashConfig
import com.sickdash.data.Recorder
import com.sickdash.data.Source
import com.sickdash.service.ConnectionManager
import com.sickdash.ui.Theme

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Sick Dash settings"
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(android.R.id.content, SettingsFragment()).commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            findPreference<ListPreference>("source")?.apply {
                entries = Source.entries.map { it.label }.toTypedArray()
                entryValues = Source.entries.map { it.key }.toTypedArray()
                if (value == null) value = Source.DEMO.key
            }
            findPreference<ListPreference>("car")?.apply {
                entries = CarPreset.entries.map { it.label }.toTypedArray()
                entryValues = CarPreset.entries.map { it.key }.toTypedArray()
                if (value == null) value = CarPreset.GR86.key
            }
            findPreference<ListPreference>("theme")?.apply {
                entries = Theme.ALL.map { it.name }.toTypedArray()
                entryValues = Theme.ALL.map { it.key }.toTypedArray()
                if (value == null) value = CarPreset.of(preferenceManager.sharedPreferences?.getString("car", null)).theme
            }
            fillBluetoothDevices()
            for (key in listOf("tcp_port", "redline", "shift_rpm", "max_rpm", "usb_baud")) {
                findPreference<EditTextPreference>(key)?.setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_NUMBER }
            }
            findPreference<EditTextPreference>("final_drive")?.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            }
            findPreference<Preference>("edit_profile")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), ProfileEditorActivity::class.java)); true
            }
            findPreference<Preference>("ble_scan")?.setOnPreferenceClickListener { startBleScan(); true }
            findPreference<Preference>("open_sniffer")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), SnifferActivity::class.java)); true
            }
            findPreference<Preference>("share_log")?.setOnPreferenceClickListener { shareLatestLog(); true }
            updateVisibility()
        }

        @SuppressLint("MissingPermission")
        private fun fillBluetoothDevices() {
            val pref = findPreference<ListPreference>("bt_device") ?: return
            val ctx = requireContext()
            if (Build.VERSION.SDK_INT >= 31 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                pref.summary = "Allow \"Nearby devices\" permission to list paired adapters"
                pref.isEnabled = false
                return
            }
            val bonded = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
                .sortedByDescending { d -> OBD_HINTS.any { (d.name ?: "").contains(it, ignoreCase = true) } }
            if (bonded.isEmpty() && pref.value.isNullOrBlank()) {
                pref.summary = "Pair your OBD adapter in Android Bluetooth settings first (PIN is usually 1234 or 0000)"
                pref.isEnabled = false
                return
            }
            val names = bonded.map { "${it.name ?: "Unknown"}  (${it.address})" }.toMutableList()
            val addresses = bonded.map { it.address }.toMutableList()
            // A device picked from a BLE scan that isn't bonded still needs to show as selected.
            pref.value?.takeIf { it.isNotBlank() && it !in addresses }?.let { names += "Scanned device  ($it)"; addresses += it }
            pref.entries = names.toTypedArray()
            pref.entryValues = addresses.toTypedArray()
            pref.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        }

        private fun shareLatestLog() {
            val ctx = requireContext()
            Recorder.init(ctx)
            val f = Recorder.latest()
            if (f == null) {
                Toast.makeText(ctx, "No drive logs yet. Tap RECORD on the Timers page.", Toast.LENGTH_LONG).show()
                return
            }
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.logs", f)
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/csv")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, f.name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, "Share ${f.name}"))
        }

        private fun updateVisibility() {
            val src = Source.of(preferenceManager.sharedPreferences?.getString("source", "demo"))
            findPreference<Preference>("bt_device")?.isVisible = src == Source.BT_ELM || src == Source.BLE_ELM
            findPreference<Preference>("ble_scan")?.isVisible = src == Source.BLE_ELM
            val tcp = src == Source.WIFI_ELM || src == Source.TCP_SLCAN
            findPreference<Preference>("tcp_host")?.isVisible = tcp
            findPreference<Preference>("tcp_port")?.isVisible = tcp
            findPreference<Preference>("usb_baud")?.isVisible = src == Source.USB_ELM || src == Source.USB_SLCAN
            findPreference<Preference>("elm_mode")?.isVisible =
                src == Source.BT_ELM || src == Source.BLE_ELM || src == Source.WIFI_ELM || src == Source.USB_ELM
        }

        /** Scans for nearby Bluetooth LE adapters for 10 s and lets the user pick one. */
        @SuppressLint("MissingPermission")
        private fun startBleScan() {
            val ctx = requireContext()
            val needed = if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            val missing = needed.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) {
                @Suppress("DEPRECATION") requestPermissions(missing.toTypedArray(), REQ_SCAN)
                return
            }
            val scanner = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
            if (scanner == null) {
                Toast.makeText(ctx, "Turn Bluetooth on first", Toast.LENGTH_SHORT).show()
                return
            }
            val found = LinkedHashMap<String, String>()
            val list = ArrayAdapter<String>(ctx, android.R.layout.simple_list_item_1)
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val name = result.device.name ?: result.scanRecord?.deviceName ?: return
                    val addr = result.device.address
                    if (found.put(addr, name) == null) list.add("$name  ($addr)")
                }
            }
            val dialog = AlertDialog.Builder(ctx)
                .setTitle("Scanning for Bluetooth LE adapters…")
                .setAdapter(list) { _, which ->
                    val addr = found.keys.elementAt(which)
                    preferenceManager.sharedPreferences?.edit()?.putString("bt_device", addr)?.apply()
                    findPreference<ListPreference>("bt_device")?.value = addr
                    fillBluetoothDevices()
                }
                .setNegativeButton("Close", null)
                .create()
            val handler = Handler(Looper.getMainLooper())
            val stop = Runnable { runCatching { scanner.stopScan(callback) }; dialog.setTitle("Pick your adapter") }
            dialog.setOnDismissListener { handler.removeCallbacks(stop); runCatching { scanner.stopScan(callback) } }
            scanner.startScan(callback)
            dialog.show()
            handler.postDelayed(stop, 10_000)
        }

        @Deprecated("Fragment permission callback")
        override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
            @Suppress("DEPRECATION") super.onRequestPermissionsResult(requestCode, permissions, grantResults)
            if (requestCode == REQ_SCAN && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startBleScan()
        }

        override fun onResume() {
            super.onResume()
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onPause() {
            preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
            super.onPause()
        }

        override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
            updateVisibility()
            if (prefs != null && (key == "car" || key == "transmission")) {
                DashConfig.applyPreset(prefs, CarPreset.of(prefs.getString("car", null)), prefs.getString("transmission", "mt") == "at")
                // Re-read the fields the preset just overwrote so the screen shows them.
                preferenceScreen.removeAll()
                onCreatePreferences(null, null)
            }
            if (key in DashConfig.CONNECTION_KEYS) ConnectionManager.restart()
        }

        private companion object {
            val OBD_HINTS = listOf("Dauntless", "OBD", "ELM", "OBDLink", "Vgate", "Veepeak", "WiCAN", "CAN")
            const val REQ_SCAN = 7
        }
    }
}
