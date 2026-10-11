package org.projectbarry.pbosinstaller.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import org.projectbarry.pbosinstaller.release.Asset
import org.projectbarry.pbosinstaller.release.ImageDownloader
import org.projectbarry.pbosinstaller.release.Releases
import org.projectbarry.pbosinstaller.release.Updates
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A newer release of this app: its APK and checksum list. */
data class AppRelease(val tag: String, val version: String, val apk: Asset, val sums: Asset)

/**
 * The app updating itself from its own GitHub releases, through Android's
 * package installer: Android checks the new APK is signed with the same key
 * and asks the user to confirm. The app checks that key too, first, so a
 * build signed with another key (debug builds) gets a clear message instead
 * of Android's generic failure.
 */
object AppUpdate {
    const val REPO = "project-barry/pb-os-installer-apk"

    /** The latest release when it is newer than [current] (versionName), else null. */
    fun findNewer(current: String): AppRelease? {
        val conn = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != 200) throw IllegalStateException("GitHub answered ${conn.responseCode}")
            return pick(JSONObject(conn.inputStream.bufferedReader().readText()), current)
        } finally {
            conn.disconnect()
        }
    }

    /** From a release (tag vX.Y.Z with pb-os-installer-vX.Y.Z.apk and SHA256SUMS), if newer than [current]. */
    fun pick(release: JSONObject, current: String): AppRelease? {
        val tag = release.optString("tag_name")
        val version = tag.removePrefix("v")
        if (!Regex("\\d+(\\.\\d+)*").matches(version) || Updates.compareVersions(version, current) <= 0) return null
        val list = release.getJSONArray("assets")
        val assets = (0 until list.length()).map {
            val a = list.getJSONObject(it)
            Asset(a.getString("name"), a.getString("browser_download_url"), a.getLong("size"))
        }
        val apk = assets.firstOrNull { it.name == "pb-os-installer-$tag.apk" } ?: return null
        val sums = assets.firstOrNull { it.name == "SHA256SUMS" } ?: return null
        return AppRelease(tag, version, apk, sums)
    }

    /** What the package installer last reported (see [Receiver]). */
    sealed interface Result {
        data object Waiting : Result
        data class Failed(val message: String) : Result
    }

    private val _result = MutableStateFlow<Result?>(null)
    val result: StateFlow<Result?> = _result

    /** Android's answers to [Updater.install]: asks the user to confirm, or reports why it failed. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    _result.value = Result.Waiting
                }
                // On success Android replaces the app and stops this process; nothing to do.
                PackageInstaller.STATUS_SUCCESS -> {}
                PackageInstaller.STATUS_FAILURE_ABORTED -> _result.value = Result.Failed("The update was cancelled.")
                else -> _result.value = Result.Failed(
                    intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.let { "Android couldn't install the update ($it)." }
                        ?: "Android couldn't install the update (status $status)."
                )
            }
        }
    }

    class UpdateError(message: String) : Exception(message)

    /** Downloads, checks and installs an [AppRelease]. */
    class Updater(private val context: Context) {
        private val downloader = ImageDownloader(context, "app-update")

        /** Downloads the APK and checks it: checksum, package and signing key. */
        suspend fun download(release: AppRelease, onProgress: (Long, Long) -> Unit): File {
            val sums = Releases.parseSums(URL(release.sums.url).readText())
            val expected = sums[release.apk.name] ?: throw UpdateError("The app release's checksum list has no entry for its APK.")
            val apk = downloader.download(listOf(release.apk)) { onProgress(it.done, it.total) }.single()
            if (sha256(apk) != expected) {
                apk.delete()
                throw UpdateError("The downloaded app update is damaged and was deleted. Try again.")
            }
            if (!sameApp(apk)) {
                throw UpdateError(
                    "The new version is signed with a different key than this copy of the app (a test build?). " +
                        "Install it from GitHub by hand instead."
                )
            }
            return apk
        }

        /** Same package, newer, signed with the key of the installed app. */
        private fun sameApp(apk: File): Boolean {
            val pm = context.packageManager
            val flags = PackageManager.GET_SIGNING_CERTIFICATES
            val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: return false
            val mine = pm.getPackageInfo(context.packageName, flags)
            if (archive.packageName != context.packageName || archive.longVersionCode <= mine.longVersionCode) return false
            fun keys(info: android.content.pm.SigningInfo?) =
                info?.apkContentsSigners.orEmpty().map { sha256(it.toByteArray()) }.toSet()
            val theirs = keys(archive.signingInfo)
            return theirs.isNotEmpty() && theirs == keys(mine.signingInfo)
        }

        /** False until the user allows this app to install apps (Android asks once). */
        fun allowed(): Boolean = context.packageManager.canRequestPackageInstalls()

        /** Android's setting page to allow it. */
        fun allowIntent(): Intent =
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Hands the APK to Android's installer; it asks the user, then replaces the app. */
        fun install(apk: File) {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                .apply { setAppPackageName(context.packageName) }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("app.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(context, Receiver::class.java).setPackage(context.packageName)
                val pending = PendingIntent.getBroadcast(
                    context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                _result.value = null
                session.commit(pending.intentSender)
            }
        }

        private fun sha256(file: File): String = file.inputStream().use { input ->
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }

        private fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }
}
