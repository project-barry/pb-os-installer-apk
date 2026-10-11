package org.projectbarry.pbosinstaller.release

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    private fun release(tag: String, vararg assets: String, draft: Boolean = false) = JSONObject()
        .put("tag_name", tag).put("name", "pb-os $tag").put("draft", draft)
        .put("assets", JSONArray(assets.map { JSONObject().put("name", it).put("browser_download_url", "https://x/$it").put("size", 10) }))

    private fun delta(tag: String, image: String, from: String = "alpha-v0.5.2", parts: Int = 1) =
        (1..parts).map { "pb-os-$tag-$image.from-$from.delta.tar.gz.%03d".format(it) }.toTypedArray()

    private val sums = arrayOf("SHA256SUMS", "SHA256SUMS.sig")

    @Test fun versionsCompareAsNumbers() {
        assertTrue(Updates.compareVersions("alpha-v0.5.2.10", "alpha-v0.5.2.9") > 0)
        assertTrue(Updates.compareVersions("alpha-v0.5.2.1", "alpha-v0.5.2") > 0)
        assertEquals(0, Updates.compareVersions("alpha-v0.5.2", "alpha-v0.5.2"))
        assertTrue(Updates.compareVersions("alpha-v0.5.10", "alpha-v0.5.2.10") > 0)
    }

    @Test fun newestPatchFromTheImagesReleaseWins() {
        // As published now: pb-os-updates (first), then pb-os with the 0.5.2.3 bridge and the feature release.
        val list = listOf(
            release("alpha-v0.5.2.10", *delta("alpha-v0.5.2.10", "sm8550"), *delta("alpha-v0.5.2.10", "pocketfit"), *sums),
            release("alpha-v0.5.2.8", *delta("alpha-v0.5.2.8", "sm8550"), *sums),
            release("alpha-v0.5.2.3", *delta("alpha-v0.5.2.3", "sm8550"), *sums),
            release("alpha-v0.5.2", "pb-os-alpha-v0.5.2-sm8550.img.7z.001", *sums),
        )
        val u = Updates.pickPatch(list, "alpha-v0.5.2", "sm8550")!!
        assertEquals("alpha-v0.5.2.10", u.tag)
        assertEquals("pb-os-alpha-v0.5.2.10-sm8550.from-alpha-v0.5.2.delta.tar.gz", u.packageName)
        assertEquals(1, u.parts.size)
        assertEquals("alpha-v0.5.2.10", Updates.pickPatch(list, "alpha-v0.5.2", "pocketfit")!!.tag)
    }

    @Test fun onlyPatchesThatFitAreTaken() {
        val list = listOf(
            release("alpha-v0.5.2.11", *delta("alpha-v0.5.2.11", "pocketfit"), *sums),       // other image
            release("alpha-v0.5.2.10", *delta("alpha-v0.5.2.10", "sm8550", from = "alpha-v0.5.1"), *sums), // other base
            release("alpha-v0.5.2.9", *delta("alpha-v0.5.2.9", "sm8550"), *sums, draft = true), // draft
            release("alpha-v0.5.2.7", *delta("alpha-v0.5.2.7", "sm8550")),                      // unsigned
            release("alpha-v0.5.2.6", "pb-os-alpha-v0.5.2.6-sm8550.from-alpha-v0.5.2.delta.tar.gz.002", *sums), // gap
            release("alpha-v0.5.2.5", *delta("alpha-v0.5.2.5", "sm8550", parts = 2), *sums),
        )
        val u = Updates.pickPatch(list, "alpha-v0.5.2", "sm8550")!!
        assertEquals("alpha-v0.5.2.5", u.tag)
        assertEquals(2, u.parts.size)
    }

    @Test fun nothingNewerMeansNoUpdate() {
        val list = listOf(release("alpha-v0.5.2", *delta("alpha-v0.5.2", "sm8550", from = "alpha-v0.5.1"), *sums))
        assertNull(Updates.pickPatch(list, "alpha-v0.5.2", "sm8550"))
        assertNull(Updates.pickPatch(emptyList(), "alpha-v0.5.2", "sm8550"))
    }

    @Test fun theFirstCopyOfATagWins() {
        val list = listOf(
            release("alpha-v0.5.2.4", *delta("alpha-v0.5.2.4", "sm8550"), *sums),
            release("alpha-v0.5.2.4", *sums), // the same tag on pb-os, incomplete
        )
        assertEquals("alpha-v0.5.2.4", Updates.pickPatch(list, "alpha-v0.5.2", "sm8550")!!.tag)
    }
}
