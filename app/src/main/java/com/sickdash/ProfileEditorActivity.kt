package com.sickdash

import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.sickdash.can.CanProfile
import com.sickdash.service.ConnectionManager
import com.sickdash.data.DashConfig

/** Edit the JSON that maps CAN ids/bytes to dash values. */
class ProfileEditorActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)
        title = "CAN profile"
        val editor = findViewById<EditText>(R.id.editor)
        val status = findViewById<TextView>(R.id.status)
        editor.typeface = Typeface.MONOSPACE
        editor.setText(ConnectionManager.profileText(this))
        val file = ConnectionManager.profileFile(this)

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            val txt = editor.text.toString()
            try {
                val p = CanProfile.parse(txt)
                file.writeText(txt)
                status.text = "Saved \"${p.name}\": ${p.signals.size} signals on ${p.ids.size} ids. Reconnecting…"
                ConnectionManager.restart()
            } catch (e: Exception) {
                status.text = "Not saved — ${e.message}"
            }
        }
        findViewById<Button>(R.id.btn_reset).setOnClickListener {
            file.delete()
            editor.setText(ConnectionManager.profileText(this))
            status.text = "Restored the built-in profile for ${DashConfig.get(this).car.label}."
            ConnectionManager.restart()
        }
    }
}
