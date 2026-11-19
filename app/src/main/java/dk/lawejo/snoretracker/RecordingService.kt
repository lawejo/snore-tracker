package dk.lawejo.snoretracker

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Records the whole night in the foreground and saves a clip whenever snoring is detected. */
class RecordingService : Service() {

    companion object {
        val isRecording = MutableStateFlow(false)
        val recordingSince = MutableStateFlow<Long?>(null)

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java))

        fun stop(context: Context) =
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))

        private const val ACTION_STOP = "stop"
        private const val CHANNEL = "recording"

        // Starting values from the research; tune them using scores.csv.
        // Quiet snoring is only 3–7 dB above the floor. False hits are marked in the app the next day.
        private const val GATE_DB = 4f
        private const val MIN_SNORE_SCORE = 0.30f
        private const val MIN_MARGIN = 0.05f
        private val COMPETITORS = listOf("Speech", "Laughter", "Cough")
        private const val PRE_ROLL = 5 * Audio.SAMPLE_RATE
        private const val MAX_CLIP_MS = 30_000L

        // Diagnostics: a fixed sample of the night, whether or not snoring is detected.
        private const val SAMPLE_EVERY_MS = 10 * 60_000L
        private const val SAMPLE_MS = 30_000L
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (job != null) return START_NOT_STICKY

        ServiceCompat.startForeground(this, 1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SnoreTracker:night")
            .apply { acquire() }
        isRecording.value = true
        job = scope.launch { record() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runBlocking { job?.cancelAndJoin() }
        wakeLock?.release()
        isRecording.value = false
        recordingSince.value = null
        super.onDestroy()
    }

    @SuppressLint("MissingPermission") // Checked in MainActivity before starting.
    private fun CoroutineScope.record() {
        val start = System.currentTimeMillis()
        recordingSince.value = start
        val dir = NightStore.create(this@RecordingService, start)
        var night = Night(startMs = start)
        NightStore.save(dir, night)

        val classifier = SnoreClassifier(this@RecordingService)
        // VOICE_RECOGNITION: no noise suppression or auto gain, which on MIC can remove steady sounds like snoring.
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, Audio.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(AudioRecord.getMinBufferSize(Audio.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), Audio.HOP * 4),
        )
        // An event can start up to a minute before it opens, plus the pre-roll.
        val ring = RingBuffer(70 * Audio.SAMPLE_RATE)
        val block = ShortArray(Audio.HOP)
        val floor = NoiseFloor()
        val detector = EventDetector()
        val csv = File(dir, "scores.csv").bufferedWriter()
        csv.write("time,db,floor,snoring,competitor,top,topScore,positive,breathing,snort\n")
        val samples = File(dir, "samples").apply { mkdirs() }
        val sampleName = SimpleDateFormat("HH-mm-ss", Locale.ROOT)

        var clip: WavWriter? = null
        var clipName: String? = null
        var clipCount = 0
        var lastHeartbeat = start
        var sample: WavWriter? = null
        var lastSample = 0L

        fun addEvent(c: EventDetector.Closed) {
            night = night.copy(events = night.events + SnoreEvent(c.startMs, c.endMs, clipName, c.peak, c.positives))
            NightStore.save(dir, night)
        }

        recorder.startRecording()
        try {
            while (isActive) {
                val n = recorder.read(block, 0, block.size)
                if (n <= 0) continue
                val now = System.currentTimeMillis()
                ring.write(block, n)
                val db = Audio.dbfs(block, n)
                val noise = floor.add(db)

                var snore = 0f
                var competitor = 0f
                var top = ""
                var topScore = 0f
                var breathing = 0f
                var snort = 0f
                // Classify every window, not only above the gate, so scores.csv shows what YAMNet hears all night.
                if (ring.size >= Audio.WINDOW) {
                    val scores = classifier.classify(ring.latest(Audio.WINDOW))
                    snore = scores["Snoring"] ?: 0f
                    competitor = COMPETITORS.maxOf { scores[it] ?: 0f }
                    scores.maxByOrNull { it.value }?.let { top = it.key; topScore = it.value }
                    breathing = scores["Breathing"] ?: 0f
                    snort = scores["Snort"] ?: 0f
                }
                val positive = db >= noise + GATE_DB && snore >= MIN_SNORE_SCORE && snore - competitor >= MIN_MARGIN
                csv.write("$now,%.1f,%.1f,%.3f,%.3f,\"$top\",%.3f,$positive,%.3f,%.3f\n".format(Locale.ROOT, db, noise, snore, competitor, topScore, breathing, snort))

                if (sample == null && now - lastSample >= SAMPLE_EVERY_MS) {
                    lastSample = now
                    sample = WavWriter(File(samples, "${sampleName.format(Date(now))}.wav"))
                }
                sample?.let {
                    it.write(block, n)
                    if (it.durationMs >= SAMPLE_MS) { it.close(); sample = null }
                }

                clip?.let {
                    it.write(block, n)
                    if (it.durationMs >= MAX_CLIP_MS) { it.close(); clip = null }
                }

                when (val change = detector.onWindow(now, positive, snore)) {
                    is EventDetector.Opened -> {
                        clipName = "clips/%04d.wav".format(++clipCount)
                        // Start the clip at the event's first positive window, not when it opened.
                        val sinceStart = ((now - change.startMs) * Audio.SAMPLE_RATE / 1000).toInt()
                        clip = WavWriter(File(dir, clipName!!)).apply { write(ring.latest(PRE_ROLL + sinceStart)) }
                    }
                    is EventDetector.Closed -> {
                        clip?.close(); clip = null
                        addEvent(change)
                    }
                    null -> Unit
                }

                // Heartbeat: shows next morning if the recording was killed during the night.
                if (now - lastHeartbeat >= 60_000) {
                    lastHeartbeat = now
                    night = night.copy(heartbeatMs = now)
                    NightStore.save(dir, night)
                    csv.flush()
                }
            }
        } finally {
            val end = System.currentTimeMillis()
            recorder.stop()
            recorder.release()
            clip?.close()
            sample?.close()
            detector.flush(end)?.let { addEvent(it) }
            csv.close()
            classifier.close()
            night = night.copy(endMs = end, heartbeatMs = end)
            NightStore.save(dir, night)
        }
    }

    private fun notification() = run {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Optagelse", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Optager natten")
            .setContentText("Lytter efter snorken")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }
}
