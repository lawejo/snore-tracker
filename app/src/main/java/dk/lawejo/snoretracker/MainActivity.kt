package dk.lawejo.snoretracker

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }

    /** Ask once to be exempt from battery optimisation, so the night isn't killed. */
    fun requestBatteryExemption() {
        val power = getSystemService(PowerManager::class.java)
        if (!power.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            )
        }
    }
}

private val time = SimpleDateFormat("HH:mm", Locale.ROOT)
private val clock = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
private val date = SimpleDateFormat("EEE d. MMM", Locale("da"))

@Composable
private fun App() {
    val activity = androidx.compose.ui.platform.LocalContext.current as MainActivity
    val recording by RecordingService.isRecording.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<File?>(null) }
    val nights = remember(recording, refresh) { NightStore.list(activity) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            activity.requestBatteryExemption()
            RecordingService.start(activity)
        }
    }

    val dir = selected
    if (dir != null) {
        BackHandler { selected = null }
        NightScreen(dir, recording, onBack = { selected = null; refresh++ })
        return
    }

    Column(Modifier.padding(24.dp)) {
        Text("Snore Tracker", fontSize = 28.sp)
        // versionCode is the GitHub build number, the same as "Build N" on the Releases page.
        val build = PackageInfoCompat.getLongVersionCode(activity.packageManager.getPackageInfo(activity.packageName, 0))
        Text("Build $build", fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
        Button(
            onClick = {
                if (recording) {
                    RecordingService.stop(activity)
                } else {
                    val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
                    permissions.launch(wanted.toTypedArray())
                }
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp).height(72.dp),
        ) {
            Text(if (recording) "Stop" else "Start nat", fontSize = 22.sp)
        }
        if (recording) Text("Optager. Lad tabletten lade, og luk ikke appen fra oversigten.")

        Text("Nætter", fontSize = 20.sp, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(nights, key = { it.first.name }) { (nightDir, night) ->
                Card(Modifier.fillMaxWidth().clickable { selected = nightDir }) {
                    Column(Modifier.padding(16.dp)) {
                        Text("${date.format(Date(night.startMs))}  ${time.format(Date(night.startMs))}–${night.endMs?.let { time.format(Date(it)) } ?: "…"}")
                        Text(summary(night), color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

private fun summary(night: Night) =
    if (night.snoreEvents.isEmpty()) "Ingen snorken registreret"
    else "Snorkede ca. ${night.snoreMinutes} min · ${night.snoreEvents.size} perioder"

@Composable
private fun NightScreen(dir: File, recording: Boolean, onBack: () -> Unit) {
    var night by remember(dir) { mutableStateOf(NightStore.load(dir)) }
    var selectedStart by remember { mutableStateOf<Long?>(null) }
    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableIntStateOf(0) }
    val player = remember { MediaPlayer() }
    val context = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(Unit) { onDispose { player.release() } }
    LaunchedEffect(playing) {
        while (playing) { positionMs = player.currentPosition; delay(50) }
    }

    val n = night ?: return onBack()
    val end = n.endMs ?: n.heartbeatMs
    val clips = n.events.filter { it.clip != null }
    val selected = clips.find { it.startMs == selectedStart }

    fun select(event: SnoreEvent, play: Boolean = true) {
        player.reset()
        selectedStart = event.startMs
        positionMs = 0
        player.setDataSource(File(dir, event.clip!!).path)
        player.setOnCompletionListener { playing = false; positionMs = it.duration }
        player.prepare()
        if (play) player.start()
        playing = play
    }
    LaunchedEffect(dir) { clips.firstOrNull()?.let { select(it, play = false) } }

    Column(Modifier.padding(24.dp)) {
        TextButton(onClick = onBack) { Text("‹ Tilbage") }
        Text(date.format(Date(n.startMs)), fontSize = 28.sp)
        Text("${time.format(Date(n.startMs))} – ${time.format(Date(end))}")
        if (n.endMs == null && !recording) {
            Text(
                "Optagelsen stoppede uventet. Sidste livstegn kl. ${time.format(Date(n.heartbeatMs))}.",
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(summary(n), fontSize = 22.sp, modifier = Modifier.padding(vertical = 16.dp))
        Timeline(n, end, selected) { t -> clips.minByOrNull { abs(it.startMs - t) }?.let { select(it) } }

        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(time.format(Date(n.startMs)), fontSize = 12.sp)
            Text(time.format(Date(end)), fontSize = 12.sp)
        }

        selected?.let { event ->
            val index = clips.indexOf(event)
            ClipPlayer(
                file = File(dir, event.clip!!),
                event = event,
                playing = playing,
                positionMs = positionMs,
                onToggle = { if (playing) player.pause() else player.start(); playing = !playing },
                onPrevious = clips.getOrNull(index - 1)?.let { { select(it) } },
                onNext = clips.getOrNull(index + 1)?.let { { select(it) } },
                onSeek = { player.seekTo(it); positionMs = it },
                onToggleNotSnore = {
                    NightStore.setNotSnore(dir, n, event, !event.notSnore)
                    night = NightStore.load(dir)
                },
            )
        }

        LazyColumn(Modifier.weight(1f).padding(top = 16.dp)) {
            items(n.events, key = { it.startMs }) { event ->
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = event.clip != null) { select(event) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${clock.format(Date(event.startMs))} · ${(event.endMs - event.startMs) / 1000} s" +
                            if (event.notSnore) " · ikke snorken" else "",
                        color = when {
                            event.startMs == selectedStart -> MaterialTheme.colorScheme.primary
                            event.notSnore -> Color.Gray
                            else -> Color.Unspecified
                        },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        if (event.startMs == selectedStart) { player.reset(); playing = false; selectedStart = null }
                        NightStore.deleteEvent(dir, n, event)
                        night = NightStore.load(dir)
                    }) { Text("Slet") }
                }
                HorizontalDivider()
            }
        }

        TextButton(onClick = { shareNight(context, dir) }) { Text("Del data (zip)") }

        if (!(recording && n.endMs == null)) {
            TextButton(onClick = { NightStore.deleteNight(dir); onBack() }) {
                Text("Slet hele natten", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Zips the night (scores.csv, samples, clips) and sends it to another app (Drive, mail …) for tuning. */
private fun shareNight(context: Context, dir: File) {
    val zip = File(context.cacheDir, "${dir.name}.zip")
    ZipOutputStream(zip.outputStream()).use { out ->
        dir.walkTopDown().filter { it.isFile }.forEach { file ->
            out.putNextEntry(ZipEntry(file.relativeTo(dir).path))
            file.inputStream().use { it.copyTo(out) }
        }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", zip)
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/zip")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null))
}

/** The night as a bar with a mark for every snore period. Tapping plays the nearest clip. */
@Composable
private fun Timeline(night: Night, end: Long, selected: SnoreEvent?, onTap: (Long) -> Unit) {
    val span = (end - night.startMs).coerceAtLeast(1).toFloat()
    Canvas(
        Modifier.fillMaxWidth().height(56.dp).pointerInput(night, end) {
            detectTapGestures { onTap(night.startMs + (it.x / size.width * span).toLong()) }
        },
    ) {
        drawRect(Color(0xFF2E3440))
        night.events.forEach { e ->
            val x = (e.startMs - night.startMs) / span * size.width
            val w = ((e.endMs - e.startMs) / span * size.width).coerceAtLeast(3f)
            val color = when {
                e == selected -> Color(0xFFE5C07B)
                e.notSnore -> Color(0xFF5C6370)
                else -> Color(0xFFE5534B)
            }
            drawRect(color, topLeft = Offset(x, 0f), size = Size(w, size.height))
        }
    }
}

/** One clip: time, position and length, waveform (tap to seek), previous/next and marking as not snoring. */
@Composable
private fun ClipPlayer(
    file: File,
    event: SnoreEvent,
    playing: Boolean,
    positionMs: Int,
    onToggle: () -> Unit,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    onSeek: (Int) -> Unit,
    onToggleNotSnore: () -> Unit,
) {
    val peaks = remember(file) { peaks(file) }
    val durationMs = ((file.length() - 44) * 1000 / (2 * Audio.SAMPLE_RATE)).toInt()
    Card(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(clock.format(Date(event.startMs)), fontSize = 22.sp, modifier = Modifier.weight(1f))
                Text("${mmss(positionMs)} / ${mmss(durationMs)}")
            }
            Canvas(
                Modifier.fillMaxWidth().padding(vertical = 8.dp).height(64.dp).pointerInput(durationMs) {
                    detectTapGestures { onSeek((it.x / size.width * durationMs).toInt()) }
                },
            ) {
                val bar = size.width / peaks.size
                peaks.forEachIndexed { i, p ->
                    val h = (p * size.height).coerceAtLeast(2f)
                    drawRect(Color(0xFF4A90D9), topLeft = Offset(i * bar, (size.height - h) / 2), size = Size(bar * 0.7f, h))
                }
                val x = positionMs.toFloat() / durationMs.coerceAtLeast(1) * size.width
                drawLine(Color(0xFFE5C07B), Offset(x, 0f), Offset(x, size.height), strokeWidth = 3f)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onPrevious?.invoke() }, enabled = onPrevious != null) { Text("⏮", fontSize = 24.sp) }
                Button(onClick = onToggle) { Text(if (playing) "⏸" else "▶", fontSize = 24.sp) }
                TextButton(onClick = { onNext?.invoke() }, enabled = onNext != null) { Text("⏭", fontSize = 24.sp) }
            }
            TextButton(onClick = onToggleNotSnore, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(if (event.notSnore) "Markeret som ikke snorken · fortryd" else "Ikke snorken")
            }
        }
    }
}

/** The loudest sample in each of [count] slices of a 16-bit wav, scaled so the loudest slice is 1. */
private fun peaks(file: File, count: Int = 150): FloatArray {
    val bytes = file.readBytes()
    val samples = (bytes.size - 44) / 2
    val perSlice = (samples / count).coerceAtLeast(1)
    val levels = FloatArray(count) { i ->
        (i * perSlice until minOf((i + 1) * perSlice, samples)).maxOfOrNull { s ->
            abs((bytes[45 + 2 * s].toInt() shl 8) or (bytes[44 + 2 * s].toInt() and 0xFF)).toFloat()
        } ?: 0f
    }
    val loudest = levels.max().coerceAtLeast(1f)
    return FloatArray(count) { levels[it] / loudest }
}

private fun mmss(ms: Int) = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)
