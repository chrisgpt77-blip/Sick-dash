package com.sickdash

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sickdash.can.CanFrame
import com.sickdash.can.CanProfile
import com.sickdash.can.SignalLearner
import com.sickdash.data.Channel
import com.sickdash.data.ChannelInfo
import com.sickdash.data.DashConfig
import com.sickdash.data.Source
import com.sickdash.data.VehicleData
import com.sickdash.service.ConnectionManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Walks through the body controls one at a time (doors, locks, belt, lights…), has the user
 * put each one in every position a few times, and finds the CAN bits that follow. Each match
 * can be tried live before it's kept; the kept ones are written into the car's CAN profile.
 */
class LearnActivity : AppCompatActivity() {
    private class Step(val channel: Channel, val title: String, val states: List<Pair<String, Float>>, val note: String? = null) {
        /** Each position two or three times, in [SignalLearner.order]'s uneven order. */
        val sequence: List<Pair<String, Float>> get() = SignalLearner.order(states.size).map { states[it] }
    }

    private val steps = listOf(
        Step(Channel.DOOR_DRIVER, "DRIVER'S DOOR", listOf("Close all the doors" to 0f, "Open the driver's door" to 1f)),
        Step(Channel.DOOR_PASS, "PASSENGER DOOR", listOf("Close all the doors" to 0f, "Open the passenger door" to 1f)),
        Step(Channel.HATCH, "TRUNK", listOf("Close the trunk" to 0f, "Open the trunk" to 1f)),
        Step(Channel.DOOR_LOCK, "DOOR LOCKS", listOf("Unlock with the door switch" to 0f, "Lock with the door switch" to 1f)),
        Step(Channel.BELT, "SEATBELT", listOf("Unbuckle the driver's belt" to 0f, "Buckle the driver's belt" to 1f)),
        Step(Channel.PARK_BRAKE, "PARK BRAKE", listOf("Release the handbrake" to 0f, "Pull the handbrake on" to 1f),
            "Keep your foot on the brake while the handbrake is off."),
        Step(Channel.HEADLIGHTS, "HEADLIGHTS", listOf("Light switch off" to 0f, "Parking lights" to 1f, "Low beams" to 2f,
            "Hold the stalk for high beams" to 3f), "Use OFF, not AUTO, for the first position."),
        Step(Channel.TURN_SIGNAL, "TURN SIGNALS", listOf("Indicators off" to 0f, "Signal left" to 1f, "Signal right" to 2f,
            "Hazards on" to 3f)),
        Step(Channel.WIPERS, "WIPERS", listOf("Wipers off" to 0f, "Intermittent" to 1f, "Low" to 2f, "High" to 3f),
            "A couple of seconds each is enough. Wet the glass first if you like."),
        Step(Channel.VDC_OFF, "VDC", listOf("VDC on (normal)" to 0f, "Hold the Track / VDC button until VDC OFF lights" to 1f),
            "To turn it back on, press the button again."),
        Step(Channel.TRACK_MODE, "TRACK MODE", listOf("Normal mode" to 0f, "Press the Track button briefly" to 1f)),
        Step(Channel.CRUISE, "CRUISE CONTROL", listOf("Cruise main switch off" to 0f, "Cruise main switch on" to 1f)),
    )

    private val learner = SignalLearner()
    private val kept = LinkedHashMap<Channel, SignalLearner.Candidate>()
    private var stepIndex = 0
    private var captureIndex = 0
    private var candidates: List<SignalLearner.Candidate> = emptyList()
    private var candidateIndex = 0
    private var liveJob: Job? = null

    private lateinit var stepView: TextView
    private lateinit var prompt: TextView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var live: TextView
    private lateinit var main: Button
    private lateinit var alt: Button
    private lateinit var skip: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_learn)
        title = "Learn body signals"
        stepView = findViewById(R.id.step)
        prompt = findViewById(R.id.prompt)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        result = findViewById(R.id.result)
        live = findViewById(R.id.live)
        main = findViewById(R.id.btn_main)
        alt = findViewById(R.id.btn_alt)
        skip = findViewById(R.id.btn_skip)
        intro()
        lifecycleScope.launch {
            while (isActive) {
                val ids = VehicleData.sniffer.size
                val fps = VehicleData.snapshot().framesPerSecond
                if (progress.tag != "recording") {
                    status.text = when {
                        DashConfig.get(this@LearnActivity).source == Source.DEMO -> "Demo mode has no CAN bus. Pick your adapter in Settings first."
                        VehicleData.linkMode != "CAN" -> "Waiting for raw CAN… (${VehicleData.status.value.message})"
                        else -> "Listening to every id: $ids ids · $fps msgs/s"
                    }
                }
                delay(500)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        VehicleData.listenAll = true
        VehicleData.frameTap = { learner.onFrame(it) }
        ConnectionManager.acquire(this, CLIENT)
        ConnectionManager.restart()   // reconnect with the adapter's filters open
    }

    override fun onStop() {
        VehicleData.frameTap = null
        VehicleData.listenAll = false
        ConnectionManager.release(CLIENT)
        ConnectionManager.restart()   // back to filtered, if anything else still holds the link
        super.onStop()
    }

    // ---- screens -------------------------------------------------------------------------------

    private fun buttons(m: String?, a: String?, s: String?, onMain: () -> Unit = {}, onAlt: () -> Unit = {}, onSkip: () -> Unit = {}) {
        for ((b, label, action) in listOf(Triple(main, m, onMain), Triple(alt, a, onAlt), Triple(skip, s, onSkip))) {
            b.visibility = if (label == null) View.GONE else View.VISIBLE
            b.text = label ?: ""
            b.isEnabled = true
            b.setOnClickListener { action() }
        }
    }

    private fun intro() {
        stepView.text = "BEFORE YOU START"
        prompt.text = "Park, engine running or ignition on, adapter on the ASC or DCM connector."
        result.text = "Sick Dash will ask you to work each control a few times: doors, locks, belt, lights, " +
            "indicators, wipers, VDC, Track mode, cruise. It records the whole CAN bus while you do, " +
            "and finds the bits that follow. Each match can be tried before you keep it.\n\n" +
            "Some controls may not be on the connector you're using; those are reported as not found."
        live.text = ""
        progress.progress = 0
        buttons("Start", null, null, onMain = { startStep(0) })
    }

    private fun startStep(i: Int) {
        stopLive()
        if (i >= steps.size) { finishAll(); return }
        stepIndex = i
        captureIndex = 0
        learner.clear()
        showCapturePrompt()
    }

    private fun showCapturePrompt() {
        val step = steps[stepIndex]
        val seq = step.sequence
        stepView.text = "STEP ${stepIndex + 1} OF ${steps.size} · ${step.title} · ${captureIndex + 1} / ${seq.size}"
        val same = captureIndex > 0 && seq[captureIndex - 1] == seq[captureIndex]
        prompt.text = if (same) "Leave it: ${seq[captureIndex].first.replaceFirstChar { it.lowercase() }}" else seq[captureIndex].first
        result.text = (step.note?.let { "$it\n\n" } ?: "") + "Do it, then tap Record and leave it for a moment."
        live.text = ""
        progress.progress = captureIndex * 100 / seq.size
        buttons("Record", if (captureIndex > 0) "Start over" else null, "Skip",
            onMain = { record() }, onAlt = { startStep(stepIndex) }, onSkip = { startStep(stepIndex + 1) })
    }

    private fun record() {
        if (VehicleData.linkMode != "CAN") {
            result.text = "No raw CAN yet. Check the adapter is on the ASC / DCM connector and Read mode is Auto or Raw CAN."
            return
        }
        val step = steps[stepIndex]
        val seq = step.sequence
        main.isEnabled = false; alt.isEnabled = false; skip.isEnabled = false
        progress.tag = "recording"
        lifecycleScope.launch {
            status.text = "Recording…"
            delay(400)                      // let the change settle
            learner.begin(seq[captureIndex].second)
            delay(1500)
            learner.end()
            progress.tag = null
            captureIndex++
            if (captureIndex < seq.size) showCapturePrompt() else showResults()
        }
    }

    private fun showResults() {
        candidates = learner.analyse()
        candidateIndex = 0
        progress.progress = 100
        if (candidates.isEmpty()) {
            prompt.text = "Not found"
            result.text = "Nothing on the bus followed it. It may not be on this connector, or it changed too slowly. " +
                "You can record it again or skip it."
            buttons("Record again", null, "Skip", onMain = { startStep(stepIndex) }, onSkip = { startStep(stepIndex + 1) })
            return
        }
        showCandidate()
    }

    private fun showCandidate() {
        val step = steps[stepIndex]
        val c = candidates[candidateIndex]
        prompt.text = "Found: ${c.describe}"
        result.text = buildString {
            append(if (candidates.size > 1) "Match ${candidateIndex + 1} of ${candidates.size}. " else "")
            if (!c.distinguishesAll) append("It can't tell every position apart. ")
            if (c.blinks) append("It flashes with the lamps. ")
            append("Try it now: work the control and check the reading below follows.")
        }
        startLive(step.channel, c)
        buttons("Use it", if (candidates.size > 1) "Next match" else "Record again", "Skip",
            onMain = { kept[step.channel] = c; startStep(stepIndex + 1) },
            onAlt = {
                if (candidates.size > 1) { candidateIndex = (candidateIndex + 1) % candidates.size; showCandidate() }
                else startStep(stepIndex)
            },
            onSkip = { startStep(stepIndex + 1) })
    }

    private fun startLive(channel: Channel, c: SignalLearner.Candidate) {
        stopLive()
        val imperial = DashConfig.get(this).imperial
        liveJob = lifecycleScope.launch {
            while (isActive) {
                val e = VehicleData.sniffer[c.id]
                val v = e?.let { synchronized(it) { c.decode(CanFrame(c.id, it.data.copyOf())) } }
                live.text = "NOW: " + (v?.let { ChannelInfo.format(channel, it, imperial) } ?: "—")
                delay(150)
            }
        }
    }

    private fun stopLive() { liveJob?.cancel(); liveJob = null; live.text = "" }

    private fun finishAll() {
        stopLive()
        stepView.text = "DONE"
        progress.progress = 100
        if (kept.isEmpty()) {
            prompt.text = "Nothing kept"
            result.text = "No changes to your CAN profile."
            buttons("Close", null, null, onMain = { finish() })
            return
        }
        prompt.text = "Keep ${kept.size} signal${if (kept.size > 1) "s" else ""}?"
        result.text = kept.entries.joinToString("\n") { (ch, c) -> "${ChannelInfo.of(ch)?.name ?: ch.key}  →  ${c.describe}" } +
            "\n\nThey're added to your CAN profile for this car (Settings → Edit CAN profile), replacing any earlier ones."
        buttons("Save", "Discard", null, onMain = { save() }, onAlt = { finish() })
    }

    private fun save() {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        try {
            var json = ConnectionManager.profileText(this)
            for ((ch, c) in kept) json = SignalLearner.withSignal(json, ch, c, "Learned with the wizard on $date")
            CanProfile.parse(json)
            ConnectionManager.profileFile(this).writeText(json)
            prompt.text = "Saved"
            result.text = "The Body page will show them once the dash reconnects."
            buttons("Close", null, null, onMain = { finish() })
        } catch (e: Exception) {
            result.text = "Couldn't save: ${e.message}"
        }
    }

    private companion object {
        const val CLIENT = "learn"
    }
}
