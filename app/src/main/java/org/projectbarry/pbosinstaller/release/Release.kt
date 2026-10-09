package org.projectbarry.pbosinstaller.release

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Asset(val name: String, val url: String, val size: Long)

/** One image of one release: the split .7z parts plus the signed checksum list. */
data class ImageRelease(
    val tag: String,
    val title: String,
    val image: String,
    val parts: List<Asset>,
    val sums: Asset,
    val sumsSig: Asset,
) {
    val downloadSize: Long get() = parts.sumOf { it.size }
}

object Releases {
    /**
     * The newest feature release. pb-os keeps only the newest feature release
     * marked Latest (patches are delta updates, they have no image), so
     * GitHub's /releases/latest is the image to flash.
     */
    fun fetchLatest(repo: String): Pair<String, JSONObject> {
        val conn = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != 200) throw IllegalStateException("GitHub answered ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            return json.getString("tag_name") to json
        } finally {
            conn.disconnect()
        }
    }

    /** The first of [images] that this release has a complete image for, or null. */
    fun pickImage(release: JSONObject, images: List<String>): ImageRelease? {
        val tag = release.getString("tag_name")
        val list = release.getJSONArray("assets")
        val assets = (0 until list.length()).map {
            val a = list.getJSONObject(it)
            Asset(a.getString("name"), a.getString("browser_download_url"), a.getLong("size"))
        }
        val sums = assets.firstOrNull { it.name == "SHA256SUMS" } ?: return null
        val sig = assets.firstOrNull { it.name == "SHA256SUMS.sig" } ?: return null
        for (image in images) {
            val pattern = Regex("^pb-os-${Regex.escape(tag)}-${Regex.escape(image)}\\.img\\.7z\\.(\\d{3})$")
            val parts = assets.mapNotNull { a -> pattern.find(a.name)?.let { it.groupValues[1].toInt() to a } }
                .sortedBy { it.first }
            // Parts must run 001, 002, ... with no gap, or 7-Zip can't join them.
            if (parts.isEmpty() || parts.withIndex().any { (i, p) -> p.first != i + 1 }) continue
            return ImageRelease(tag, release.optString("name", tag), image, parts.map { it.second }, sums, sig)
        }
        return null
    }

    /** "hash  name" lines of a SHA256SUMS file, as name to lowercase hash. */
    fun parseSums(text: String): Map<String, String> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val m = Regex("^([0-9a-fA-F]{64})\\s+\\*?(.+)$").find(line) ?: return@mapNotNull null
            m.groupValues[2] to m.groupValues[1].lowercase()
        }
        .toMap()
}
