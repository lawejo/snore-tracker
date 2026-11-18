package dk.lawejo.snoretracker

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One folder per night: night.json, scores.csv and a clips folder with .wav files. */
object NightStore {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private fun root(context: Context) = File(context.filesDir, "nights")

    fun create(context: Context, startMs: Long): File {
        val name = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss", Locale.ROOT).format(Date(startMs))
        return File(root(context), name).apply { File(this, "clips").mkdirs() }
    }

    fun save(dir: File, night: Night) {
        val tmp = File(dir, "night.json.tmp")
        tmp.writeText(json.encodeToString(Night.serializer(), night))
        tmp.renameTo(File(dir, "night.json"))
    }

    fun list(context: Context): List<Pair<File, Night>> =
        root(context).listFiles().orEmpty()
            .mapNotNull { dir -> load(dir)?.let { dir to it } }
            .sortedByDescending { it.second.startMs }

    fun load(dir: File): Night? = runCatching {
        json.decodeFromString(Night.serializer(), File(dir, "night.json").readText())
    }.getOrNull()

    fun deleteNight(dir: File) = dir.deleteRecursively()

    fun setNotSnore(dir: File, night: Night, event: SnoreEvent, notSnore: Boolean) =
        save(dir, night.copy(events = night.events.map { if (it == event) it.copy(notSnore = notSnore) else it }))

    fun deleteEvent(dir: File, night: Night, event: SnoreEvent) {
        event.clip?.let { File(dir, it).delete() }
        save(dir, night.copy(events = night.events - event))
    }
}
