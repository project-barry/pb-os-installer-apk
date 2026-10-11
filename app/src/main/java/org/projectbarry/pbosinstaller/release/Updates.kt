package org.projectbarry.pbosinstaller.release

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The newest patch for the image the app installs, so PB-OS starts on it
 * instead of updating right after the first boot.
 *
 * pb-os patch releases each carry ONE delta, cumulative from their feature
 * release: pb-os-<tag>-<image>.from-<feature>.delta.tar.gz (split .001, ...).
 * The feature release's own updater installs it from ~/Downloads (it accepts
 * any signed delta built from its version), so the newest such delta is all
 * an image needs; patches in between are never required. Bridge releases
 * like alpha-v0.5.2.3 only matter to updaters looking online.
 *
 * Patches are published on pb-os-updates, older ones on pb-os, so both are
 * read, newest first, the way PB-OS's updater does (pb-os-updates' copy wins
 * when a tag is on both). Only published releases: GitHub doesn't list
 * drafts, and the dev channel (pb-os-dev) is never read.
 */
data class UpdateRelease(
    val tag: String,
    val title: String,
    val image: String,
    /** The feature release the delta installs on. */
    val from: String,
    val parts: List<Asset>,
    val sums: Asset,
    val sumsSig: Asset,
) {
    /** The joined package's name, as SHA256SUMS lists it. */
    val packageName: String get() = "pb-os-$tag-$image.from-$from.delta.tar.gz"
    val downloadSize: Long get() = parts.sumOf { it.size }
}

object Updates {
    val REPOS = listOf("project-barry/pb-os-updates", "project-barry/pb-os")

    /** The newest patch for [image] of feature release [imageTag], or null when there is none. */
    fun findPatch(imageTag: String, image: String, repos: List<String> = REPOS): UpdateRelease? {
        val releases = repos.flatMap { repo -> fetchReleases(repo) }
        return pickPatch(releases, imageTag, image)
    }

    private fun fetchReleases(repo: String): List<JSONObject> {
        val conn = URL("https://api.github.com/repos/$repo/releases?per_page=30").openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != 200) throw IllegalStateException("GitHub answered ${conn.responseCode} for $repo")
            val list = JSONArray(conn.inputStream.bufferedReader().readText())
            return (0 until list.length()).map { list.getJSONObject(it) }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * From [releases] (first copy of a tag wins), the newest one newer than
     * [imageTag] with a complete delta for [image] from [imageTag] plus a signed
     * checksum list.
     */
    fun pickPatch(releases: List<JSONObject>, imageTag: String, image: String): UpdateRelease? {
        val seen = mutableSetOf<String>()
        val candidates = releases.mapNotNull { r ->
            val tag = r.optString("tag_name")
            if (tag.isEmpty() || !seen.add(tag) || r.optBoolean("draft")) return@mapNotNull null
            if (compareVersions(tag, imageTag) <= 0) return@mapNotNull null
            val list = r.getJSONArray("assets")
            val assets = (0 until list.length()).map {
                val a = list.getJSONObject(it)
                Asset(a.getString("name"), a.getString("browser_download_url"), a.getLong("size"))
            }
            val sums = assets.firstOrNull { it.name == "SHA256SUMS" } ?: return@mapNotNull null
            val sig = assets.firstOrNull { it.name == "SHA256SUMS.sig" } ?: return@mapNotNull null
            val joined = "pb-os-$tag-$image.from-$imageTag.delta.tar.gz"
            val pattern = Regex("^${Regex.escape(joined)}\\.(\\d{3})$")
            val parts = assets.mapNotNull { a -> pattern.find(a.name)?.let { it.groupValues[1].toInt() to a } }
                .sortedBy { it.first }
            if (parts.isEmpty() || parts.withIndex().any { (i, p) -> p.first != i + 1 }) return@mapNotNull null
            UpdateRelease(tag, r.optString("name", tag), image, imageTag, parts.map { it.second }, sums, sig)
        }
        return candidates.maxWithOrNull { a, b -> compareVersions(a.tag, b.tag) }
    }

    /** alpha-v0.5.2.10 after alpha-v0.5.2.9: numbers compare as numbers (PB-OS's version_key). */
    fun compareVersions(a: String, b: String): Int {
        fun key(t: String) = Regex("\\d+|\\D+").findAll(t).map { it.value }.toList()
        val ka = key(a)
        val kb = key(b)
        for (i in 0 until minOf(ka.size, kb.size)) {
            val x = ka[i]
            val y = kb[i]
            val c = when {
                x[0].isDigit() && y[0].isDigit() -> x.toBigInteger().compareTo(y.toBigInteger())
                x[0].isDigit() -> -1
                y[0].isDigit() -> 1
                else -> x.compareTo(y)
            }
            if (c != 0) return c
        }
        return ka.size.compareTo(kb.size)
    }
}
