package org.projectbarry.pbosinstaller.root

import android.os.IBinder
import android.os.Parcel

/**
 * Retroid's root service: the binder service "PServerBinder", served by
 * /system/bin/pservice. Retroid's Handheld Settings uses it for "Run Script as
 * Root"; any app may call it. Call code 0 takes [command, "1"] and replies with
 * the command's output as a byte array.
 *
 * `service call` can't reach it (the service has no interface name), so the app
 * calls it directly.
 */
object PServer {
    private const val NAME = "PServerBinder"

    private fun binder(): IBinder? = runCatching {
        Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, NAME) as IBinder?
    }.getOrNull()?.takeIf { it.isBinderAlive }

    /** Runs [command] as root; returns its output, or null when the service couldn't be reached. */
    fun run(command: String): String? {
        val b = binder() ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeStringArray(arrayOf(command, "1"))
            if (b.transact(0, data, reply, 0)) String(reply.createByteArray() ?: ByteArray(0)) else null
        } catch (e: Exception) {
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** True when the service is registered (runs nothing). */
    fun present(): Boolean = binder() != null

    /** True when the service is there and really runs commands as root. */
    fun works(): Boolean = run("id -u")?.trim() == "0"
}
