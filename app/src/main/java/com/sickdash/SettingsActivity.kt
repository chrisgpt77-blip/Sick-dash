package com.sickdash

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.sickdash.data.CarPreset
import com.sickdash.data.DashConfig
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
            findPreference<Preference>("open_sniffer")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), SnifferActivity::class.java)); true
            }
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
            if (bonded.isEmpty()) {
                pref.summary = "Pair your OBD adapter in Android Bluetooth settings first (PIN is usually 1234 or 0000)"
                pref.isEnabled = false
                return
            }
            pref.entries = bonded.map { "${it.name ?: "Unknown"}  (${it.address})" }.toTypedArray()
            pref.entryValues = bonded.map { it.address }.toTypedArray()
            pref.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        }

        private fun updateVisibility() {
            val src = Source.of(preferenceManager.sharedPreferences?.getString("source", "demo"))
            findPreference<Preference>("bt_device")?.isVisible = src == Source.BT_ELM
            val tcp = src == Source.WIFI_ELM || src == Source.TCP_SLCAN
            findPreference<Preference>("tcp_host")?.isVisible = tcp
            findPreference<Preference>("tcp_port")?.isVisible = tcp
            findPreference<Preference>("usb_baud")?.isVisible = src == Source.USB_ELM || src == Source.USB_SLCAN
            findPreference<Preference>("elm_mode")?.isVisible = src == Source.BT_ELM || src == Source.WIFI_ELM || src == Source.USB_ELM
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
            val OBD_HINTS = listOf("OBD", "ELM", "OBDLink", "Vgate", "Veepeak", "WiCAN", "CAN")
        }
    }
}
