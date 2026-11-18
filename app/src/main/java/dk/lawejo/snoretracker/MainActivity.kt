package dk.lawejo.snoretracker

import android.Manifest
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        setContent {
            MaterialTheme(colorScheme = Sky.colors, typography = Sky.typography) {
                // Sky behind the system bars.
                Surface(Modifier.fillMaxSize().nightSky()) { App() }
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
private val date = SimpleDateFormat("EEE d. MMM", Locale("da"))

/** Content max 640 dp wide, centered and clear of the system bars. */
@Composable
private fun Page(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.safeDrawingPadding().widthIn(max = 640.dp).fillMaxSize()) { content() }
    }
}

/** The current time, updated every 15 seconds. */
@Composable
private fun rememberNow(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(15_000)
            value = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
private fun App() {
    val activity = LocalContext.current as MainActivity
    val recording by RecordingService.isRecording.collectAsState()
    val since by RecordingService.recordingSince.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<File?>(null) }
    var sleeping by remember { mutableStateOf(false) }
    val nights = remember(recording, refresh) { NightStore.list(activity) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            activity.requestBatteryExemption()
            RecordingService.start(activity)
        }
    }

    if (sleeping && recording) {
        SleepScreen(onWake = { sleeping = false })
        return
    }

    val dir = selected
    if (dir != null) {
        BackHandler { selected = null }
        Page { NightScreen(dir, recording, onBack = { selected = null; refresh++ }) }
        return
    }

    Page {
        Column(Modifier.padding(16.dp)) {
            Text("☾ Snore Tracker", style = Sky.h1, color = Sky.Moon, letterSpacing = 0.04.em)
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
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(if (recording) "Stop" else "Start nat", fontSize = 22.sp)
            }
            if (recording) RecordingStatus(since, onSleep = { sleeping = true })

            Text("NÆTTER", style = Sky.eyebrow, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(nights, key = { it.first.name }) { (nightDir, night) ->
                    // Newest night first, highlighted as "i dag".
                    val newest = night === nights.first().second
                    Column(
                        Modifier.fillMaxWidth().skyCard(highlight = newest, onClick = { selected = nightDir }),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("${date.format(Date(night.startMs))}  ${time.format(Date(night.startMs))}–${night.endMs?.let { time.format(Date(it)) } ?: "…"}")
                        Timeline(night, night.endMs ?: night.heartbeatMs, Modifier.height(6.dp))
                        Badge(summary(night), statusColor(night))
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingStatus(since: Long?, onSleep: () -> Unit) {
    val now = rememberNow()
    val minutes = since?.let { (now - it) / 60_000 } ?: 0
    Text(
        if (since == null) "● Optager"
        else "● Optager siden ${time.format(Date(since))} · ${minutes / 60} t ${minutes % 60} min",
        color = Sky.Moon,
    )
    Text("Lad tabletten lade, og luk ikke appen fra oversigten.", color = Sky.Muted)
    TextButton(onClick = onSleep) { Text("☾ Sovevisning") }
}

/** Black screen with a faint clock and minimum brightness. Tap or back to leave. */
@Composable
private fun SleepScreen(onWake: () -> Unit) {
    val window = (LocalContext.current as MainActivity).window
    DisposableEffect(Unit) {
        window.attributes = window.attributes.apply { screenBrightness = 0.01f }
        onDispose {
            window.attributes = window.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }
    BackHandler(onBack = onWake)
    Box(
        Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onWake),
        contentAlignment = Alignment.Center,
    ) {
        Text(time.format(Date(rememberNow())), fontSize = 64.sp, color = Sky.Muted.copy(alpha = 0.35f))
    }
}

private fun statusColor(night: Night) = if (night.events.isEmpty()) Sky.Done else Sky.Tempo

private fun summary(night: Night) =
    if (night.events.isEmpty()) "Ingen snorken registreret"
    else "Snorkede ca. ${night.snoreMinutes} min · ${night.events.size} perioder"

@Composable
private fun NightScreen(dir: File, recording: Boolean, onBack: () -> Unit) {
    var night by remember(dir) { mutableStateOf(NightStore.load(dir)) }
    // Marked as not snoring: hidden at once, deleted when the undo snackbar closes.
    val rejected = remember { mutableStateListOf<SnoreEvent>() }
    var playing by remember { mutableStateOf<SnoreEvent?>(null) }
    var playAll by remember { mutableStateOf(false) }
    var reviewing by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf<SnoreEvent?>(null) } // clip under review
    var confirmDelete by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val player = remember { MediaPlayer() }

    fun visible() = night?.events.orEmpty() - rejected.toSet()
    fun next(after: SnoreEvent?) = visible().firstOrNull { it.clip != null && (after == null || it.startMs > after.startMs) }

    fun commit(event: SnoreEvent) {
        NightStore.load(dir)?.let { NightStore.deleteEvent(dir, it, event) }
        rejected -= event
        night = NightStore.load(dir)
    }

    fun play(event: SnoreEvent?) {
        player.reset()
        playing = event
        val clip = event?.clip ?: return
        player.setDataSource(File(dir, clip).path)
        player.setOnCompletionListener {
            val following = if (playAll) next(event) else null
            if (following == null) playAll = false
            play(following)
        }
        player.prepare()
        player.start()
    }

    fun toggle(event: SnoreEvent) {
        playAll = false
        play(if (playing == event) null else event)
    }

    fun reject(event: SnoreEvent) {
        rejected += event
        snackbar.currentSnackbarData?.dismiss() // the previous one is committed
        scope.launch {
            val result = snackbar.showSnackbar("Markeret som ikke snorken", "Fortryd", duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) rejected -= event else commit(event)
        }
    }

    fun review(event: SnoreEvent?) {
        current = event
        play(event)
    }

    DisposableEffect(Unit) {
        onDispose {
            player.release()
            rejected.toList().forEach { commit(it) }
        }
    }

    val n = night ?: return onBack()
    val end = n.endMs ?: n.heartbeatMs
    // The service keeps rewriting night.json while recording, so changes would be lost.
    val live = recording && n.endMs == null
    val events = visible()
    val shown = n.copy(events = events)
    val clips = events.filter { it.clip != null }

    if (reviewing) BackHandler { play(null); reviewing = false }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp)) {
            TextButton(onClick = onBack) { Text("‹ Tilbage") }
            Text(date.format(Date(n.startMs)), style = Sky.h1)
            Text("${time.format(Date(n.startMs))} – ${time.format(Date(end))}", color = Sky.Muted)
            if (n.endMs == null && !recording) {
                Text(
                    "Optagelsen stoppede uventet. Sidste livstegn kl. ${time.format(Date(n.heartbeatMs))}.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(summary(shown), fontSize = 22.sp, color = statusColor(shown), modifier = Modifier.padding(vertical = 16.dp))
            Timeline(shown, end, Modifier.height(40.dp), playing, onTap = { toggle(it) })

            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(time.format(Date(n.startMs)), fontSize = 12.sp, color = Sky.Muted)
                Text(time.format(Date(end)), fontSize = 12.sp, color = Sky.Muted)
            }

            if (reviewing) {
                Review(
                    event = current,
                    position = current?.let { clips.indexOf(it) + 1 } ?: 0,
                    total = clips.size,
                    playing = playing != null,
                    onReplay = { play(current) },
                    onSnore = { review(next(current)) },
                    onNotSnore = { current?.let { val following = next(it); reject(it); review(following) } },
                    onDone = { play(null); reviewing = false },
                    modifier = Modifier.weight(1f),
                )
            } else {
                if (clips.isNotEmpty()) {
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!live) {
                            Button(onClick = { playAll = false; reviewing = true; review(clips.first()) }, shape = RoundedCornerShape(10.dp)) {
                                Text("Gennemgå klip")
                            }
                        }
                        TextButton(onClick = {
                            if (playAll) { playAll = false; play(null) } else { playAll = true; play(clips.first()) }
                        }) { Text(if (playAll) "■ Stop" else "▶ Afspil alle") }
                    }
                }

                LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
                    items(events, key = { it.startMs }) { event ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${time.format(Date(event.startMs))} · ${(event.endMs - event.startMs) / 1000} s",
                                color = if (playing == event) Sky.Moon else Color.Unspecified,
                                modifier = Modifier.weight(1f),
                            )
                            if (event.clip != null) {
                                TextButton(onClick = { toggle(event) }) { Text(if (playing == event) "■ Stop" else "▶ Afspil") }
                            }
                            if (!live) {
                                TextButton(onClick = { if (playing == event) play(null); reject(event) }) {
                                    Text("Ikke snorken", color = Sky.Muted)
                                }
                            }
                        }
                        HorizontalDivider(color = Sky.Border)
                    }
                }

                if (!live) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("Slet hele natten", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Slet hele natten?") },
            text = { Text("Optagelsen og alle klip slettes permanent.") },
            confirmButton = {
                TextButton(onClick = { NightStore.deleteNight(dir); onBack() }) {
                    Text("Slet", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuller") } },
        )
    }
}

/** One clip at a time with two big buttons, like SnoreLab. */
@Composable
private fun Review(
    event: SnoreEvent?,
    position: Int,
    total: Int,
    playing: Boolean,
    onReplay: () -> Unit,
    onSnore: () -> Unit,
    onNotSnore: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (event == null) {
            Text("Alle klip er gennemgået.", fontSize = 22.sp, color = Sky.Done)
            Button(onClick = onDone, shape = RoundedCornerShape(10.dp)) { Text("Færdig") }
        } else {
            Text("KLIP $position AF $total", style = Sky.eyebrow)
            Text("${time.format(Date(event.startMs))} · ${(event.endMs - event.startMs) / 1000} s", fontSize = 22.sp)
            TextButton(onClick = onReplay) { Text(if (playing) "♪ Afspiller…" else "↻ Afspil igen") }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DecisionButton("Ikke snorken", Sky.Skipped, onNotSnore, Modifier.weight(1f))
                DecisionButton("Snorken", Sky.Done, onSnore, Modifier.weight(1f))
            }
            TextButton(onClick = onDone) { Text("Afslut gennemgang", color = Sky.Muted) }
        }
    }
}

@Composable
private fun DecisionButton(label: String, color: Color, onClick: () -> Unit, modifier: Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(72.dp),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f)),
    ) { Text(label, color = color, fontSize = 18.sp) }
}

/** The night as a bar with a mark per snore period. Tap jumps to the nearest clip. */
@Composable
private fun Timeline(
    night: Night,
    end: Long,
    modifier: Modifier,
    playing: SnoreEvent? = null,
    onTap: ((SnoreEvent) -> Unit)? = null,
) {
    val span = (end - night.startMs).coerceAtLeast(1).toFloat()
    val tap by rememberUpdatedState(onTap)
    val tapModifier = if (onTap == null) Modifier else Modifier.pointerInput(night) {
        detectTapGestures { pos ->
            val tapped = pos.x / size.width * span // ms since start
            night.events.filter { it.clip != null }
                .minByOrNull { abs((it.startMs + it.endMs) / 2 - night.startMs - tapped) }
                ?.let { tap?.invoke(it) }
        }
    }
    Canvas(modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).then(tapModifier)) {
        drawRect(Sky.Border)
        night.events.forEach { e ->
            val x = (e.startMs - night.startMs) / span * size.width
            val w = ((e.endMs - e.startMs) / span * size.width).coerceAtLeast(3f)
            drawRect(if (e == playing) Sky.Moon else Sky.Tempo, topLeft = Offset(x, 0f), size = Size(w, size.height))
        }
    }
}
