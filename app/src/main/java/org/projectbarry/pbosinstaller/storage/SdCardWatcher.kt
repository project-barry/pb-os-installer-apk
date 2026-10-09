package org.projectbarry.pbosinstaller.storage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A microSD card in the slot. [sizeBytes] is null when Android can't read the card (e.g. a Linux card). */
data class SdCard(val label: String, val state: String, val sizeBytes: Long?) {
    /** False only when we know the card is too small; the flash step checks unknown sizes. */
    val bigEnough: Boolean get() = sizeBytes == null || sizeBytes >= MIN_BYTES

    companion object {
        /** The image is ~16.5 GB; a "32 GB" card reads as ~29-31 GB. */
        const val MIN_BYTES = 28_000_000_000L
    }
}

/**
 * Watches the microSD slot. A card counts as inserted whatever is on it: a
 * card that already holds pb-os (Linux file systems) shows as "unmountable".
 */
class SdCardWatcher(private val context: Context) {
    private val storage = context.getSystemService(StorageManager::class.java)
    private val _card = MutableStateFlow(find())
    val card: StateFlow<SdCard?> = _card

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = refresh()
    }
    private val callback = object : StorageManager.StorageVolumeCallback() {
        override fun onStateChanged(volume: StorageVolume) = refresh()
    }

    fun start() {
        val filter = IntentFilter().apply {
            listOf(
                Intent.ACTION_MEDIA_MOUNTED, Intent.ACTION_MEDIA_UNMOUNTED, Intent.ACTION_MEDIA_REMOVED,
                Intent.ACTION_MEDIA_BAD_REMOVAL, Intent.ACTION_MEDIA_EJECT, Intent.ACTION_MEDIA_CHECKING,
                Intent.ACTION_MEDIA_UNMOUNTABLE, Intent.ACTION_MEDIA_NOFS,
            ).forEach(::addAction)
            addDataScheme("file")
        }
        // System media broadcasts only; exported is required to receive them.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        storage.registerStorageVolumeCallback(context.mainExecutor, callback)
        refresh()
    }

    fun stop() {
        context.unregisterReceiver(receiver)
        storage.unregisterStorageVolumeCallback(callback)
    }

    fun refresh() {
        _card.value = find()
    }

    private fun find(): SdCard? {
        val volume = storage.storageVolumes.firstOrNull { it.isRemovable && !it.isPrimary } ?: return null
        val state = volume.state
        if (state in GONE) return null
        val size = volume.directory?.let { dir -> runCatching { StatFs(dir.path).totalBytes }.getOrNull() }
        return SdCard(volume.getDescription(context), state, size)
    }

    private companion object {
        val GONE = setOf(Environment.MEDIA_REMOVED, Environment.MEDIA_BAD_REMOVAL, Environment.MEDIA_EJECTING)
    }
}
