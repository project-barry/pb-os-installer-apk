package org.projectbarry.pbosinstaller.release

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseTest {
    private fun resource(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()

    private fun release(tag: String, vararg names: String) = JSONObject()
        .put("tag_name", tag)
        .put("name", "pb-os $tag")
        .put("assets", JSONArray().apply {
            names.forEach { put(JSONObject().put("name", it).put("browser_download_url", "https://x/$it").put("size", 10L)) }
        })

    @Test fun picksAllPartsInOrder() {
        val r = release(
            "alpha-v0.5.2", "SHA256SUMS", "SHA256SUMS.sig",
            "pb-os-alpha-v0.5.2-sm8550.img.7z.002", "pb-os-alpha-v0.5.2-sm8550.img.7z.001",
            "pb-os-alpha-v0.5.2-sm8550.img.7z.003", "pb-os-alpha-v0.5.2-sm8550.update.tar.gz.001",
            "pb-os-alpha-v0.5.2-pocketfit.img.7z.001",
        )
        val pick = Releases.pickImage(r, listOf("sm8550"))!!
        assertEquals(
            listOf("001", "002", "003").map { "pb-os-alpha-v0.5.2-sm8550.img.7z.$it" },
            pick.parts.map { it.name },
        )
        assertEquals(30L, pick.downloadSize)
    }

    @Test fun missingPartOrSignatureMeansNoImage() {
        assertNull(Releases.pickImage(release("t", "SHA256SUMS", "SHA256SUMS.sig", "pb-os-t-sm8550.img.7z.002"), listOf("sm8550")))
        assertNull(Releases.pickImage(release("t", "SHA256SUMS", "pb-os-t-sm8550.img.7z.001"), listOf("sm8550")))
    }

    @Test fun fallsThroughImageNames() {
        val r = release("t", "SHA256SUMS", "SHA256SUMS.sig", "pb-os-t-pocketfit.img.7z.001")
        assertEquals("pocketfit", Releases.pickImage(r, listOf("pb-os", "pocketfit"))?.image)
    }

    @Test fun parsesRealSums() {
        val sums = Releases.parseSums(String(resource("SHA256SUMS")))
        assertEquals(
            "7912a2e92a42c01122ec8d93f91378c50d357589a85fa48964069a87e706d2a5",
            sums["pb-os-alpha-v0.5.2-sm8550.img.7z.001"],
        )
        assertNotNull(sums["pb-os-alpha-v0.5.2-pocketfit.img.7z.003"])
    }

    @Test fun realReleaseSignatureVerifies() {
        assertTrue(SshSig.verify(resource("SHA256SUMS"), String(resource("SHA256SUMS.sig"))))
    }

    @Test fun tamperedSumsFail() {
        val sums = resource("SHA256SUMS").clone()
        sums[0] = if (sums[0] == 'a'.code.toByte()) 'b'.code.toByte() else 'a'.code.toByte()
        assertFalse(SshSig.verify(sums, String(resource("SHA256SUMS.sig"))))
    }

    @Test fun otherKeyOrNamespaceFails() {
        val sums = resource("SHA256SUMS")
        val sig = String(resource("SHA256SUMS.sig"))
        assertFalse(SshSig.verify(sums, sig, namespace = "file"))
        assertFalse(SshSig.verify(sums, sig, trustedKey = "AAAAC3NzaC1lZDI1NTE5AAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
    }
}
