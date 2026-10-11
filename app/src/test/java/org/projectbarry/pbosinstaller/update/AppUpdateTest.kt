package org.projectbarry.pbosinstaller.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdateTest {
    private fun release(tag: String, vararg assets: String) = JSONObject().put("tag_name", tag)
        .put("assets", JSONArray(assets.map { JSONObject().put("name", it).put("browser_download_url", "https://x/$it").put("size", 5) }))

    @Test fun newerReleaseIsOffered() {
        val r = AppUpdate.pick(release("v0.3.0", "pb-os-installer-v0.3.0.apk", "SHA256SUMS", "release_notes.md"), "0.2.1")!!
        assertEquals("0.3.0", r.version)
        assertEquals("pb-os-installer-v0.3.0.apk", r.apk.name)
        assertEquals("0.2.10", AppUpdate.pick(release("v0.2.10", "pb-os-installer-v0.2.10.apk", "SHA256SUMS"), "0.2.9")!!.version)
    }

    @Test fun sameOlderOrIncompleteIsNot() {
        assertNull(AppUpdate.pick(release("v0.2.1", "pb-os-installer-v0.2.1.apk", "SHA256SUMS"), "0.2.1"))
        assertNull(AppUpdate.pick(release("v0.2.0", "pb-os-installer-v0.2.0.apk", "SHA256SUMS"), "0.2.1"))
        assertNull(AppUpdate.pick(release("v0.3.0", "pb-os-installer-v0.3.0.apk"), "0.2.1"))      // no checksums
        assertNull(AppUpdate.pick(release("v0.3.0", "other.apk", "SHA256SUMS"), "0.2.1"))
        assertNull(AppUpdate.pick(release("test-build", "pb-os-installer-test-build.apk", "SHA256SUMS"), "0.2.1"))
    }
}
