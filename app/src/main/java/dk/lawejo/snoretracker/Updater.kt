package dk.lawejo.snoretracker

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL

/** Finds and installs new builds from the GitHub releases, so updating doesn't require a visit to GitHub. */
object Updater {
    private const val LATEST = "https://api.github.com/repos/lawejo/snore-tracker/releases/latest"

    data class Release(val build: Long, val apkUrl: String)

    /** The newest release, or null if GitHub can't be reached. */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val json = JSONObject(URL(LATEST).readText())
            val assets = json.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .first { it.getString("name").endsWith(".apk") }
            Release(json.getString("tag_name").removePrefix("build-").toLong(), apk.getString("browser_download_url"))
        }.getOrNull()
    }

    /** Downloads the apk and opens Android's installer, where the update is confirmed. */
    suspend fun install(context: Context, release: Release) {
        val apk = File(context.cacheDir, "update.apk")
        withContext(Dispatchers.IO) {
            URL(release.apkUrl).openStream().use { input -> apk.outputStream().use { input.copyTo(it) } }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }
}
