package org.projectbarry.pbosinstaller.release

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64

/**
 * Checks an `ssh-keygen -Y sign` signature (SSHSIG, ssh-ed25519 only), the way
 * `ssh-keygen -Y verify` does for pb-os's SHA256SUMS.sig.
 */
object SshSig {
    /** pb-os release key, from pb-os external-and-mods/konkr-update/allowed_signers. */
    const val PB_OS_RELEASE_KEY = "AAAAC3NzaC1lZDI1NTE5AAAAIM17MVOiPqqKKegL49NCt9okeILfAy2M1FqZ/YZ8rR7k"
    const val PB_OS_NAMESPACE = "pb-os-update"

    fun verify(
        message: ByteArray,
        armoredSig: String,
        trustedKey: String = PB_OS_RELEASE_KEY,
        namespace: String = PB_OS_NAMESPACE,
    ): Boolean = try {
        verifyOrThrow(message, armoredSig, trustedKey, namespace)
        true
    } catch (e: Exception) {
        false
    }

    private fun verifyOrThrow(message: ByteArray, armoredSig: String, trustedKey: String, namespace: String) {
        val body = armoredSig.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("-----") }
            .joinToString("")
        val blob = ByteBuffer.wrap(Base64.getDecoder().decode(body))
        val magic = ByteArray(6).also { blob.get(it) }
        require(String(magic, Charsets.US_ASCII) == "SSHSIG") { "not an SSH signature" }
        require(blob.int == 1) { "unknown SSHSIG version" }
        val publicKey = blob.sshString()
        val sigNamespace = blob.sshString()
        val reserved = blob.sshString()
        val hashAlg = String(blob.sshString(), Charsets.US_ASCII)
        val signature = ByteBuffer.wrap(blob.sshString())

        require(publicKey.contentEquals(Base64.getDecoder().decode(trustedKey))) { "signed by another key" }
        require(String(sigNamespace, Charsets.US_ASCII) == namespace) { "wrong namespace" }

        val keyBuf = ByteBuffer.wrap(publicKey)
        require(String(keyBuf.sshString(), Charsets.US_ASCII) == "ssh-ed25519") { "not an ed25519 key" }
        val rawKey = keyBuf.sshString()
        require(String(signature.sshString(), Charsets.US_ASCII) == "ssh-ed25519") { "not an ed25519 signature" }
        val rawSig = signature.sshString()

        val digest = when (hashAlg) {
            "sha512" -> MessageDigest.getInstance("SHA-512")
            "sha256" -> MessageDigest.getInstance("SHA-256")
            else -> throw IllegalArgumentException("unknown hash $hashAlg")
        }.digest(message)

        val signed = ByteArrayOutputStream().apply {
            write("SSHSIG".toByteArray(Charsets.US_ASCII))
            writeSshString(sigNamespace)
            writeSshString(reserved)
            writeSshString(hashAlg.toByteArray(Charsets.US_ASCII))
            writeSshString(digest)
        }.toByteArray()

        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(rawKey, 0))
        verifier.update(signed, 0, signed.size)
        require(verifier.verifySignature(rawSig)) { "bad signature" }
    }

    private fun ByteBuffer.sshString(): ByteArray {
        val len = int
        require(len in 0..remaining()) { "truncated signature" }
        return ByteArray(len).also { get(it) }
    }

    private fun ByteArrayOutputStream.writeSshString(bytes: ByteArray) {
        write(ByteBuffer.allocate(4).putInt(bytes.size).array())
        write(bytes)
    }
}
