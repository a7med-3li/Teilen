package com.teilen.app

import android.os.ParcelFileDescriptor
import java.util.concurrent.ConcurrentHashMap

/**
 * In-process transfer holder.
 *
 * Android's ActivityManager strictly forbids writing FileDescriptors into Intent parcels
 * when starting a Service or ForegroundService ("Not allowed to write file descriptors here").
 * This singleton passes open ParcelFileDescriptors safely in memory between ShareReceiverActivity
 * and TransferService in the same process without IPC serialization.
 */
object PendingTransferHolder {
    data class Entry(
        val fds: List<ParcelFileDescriptor>,
        val names: List<String>,
        val mimes: List<String>,
        val sizes: List<Long?>
    )

    private val map = ConcurrentHashMap<String, Entry>()

    fun put(id: String, entry: Entry) {
        map[id] = entry
    }

    fun remove(id: String): Entry? {
        return map.remove(id)
    }

    fun clear() {
        map.values.forEach { entry ->
            entry.fds.forEach { runCatching { it.close() } }
        }
        map.clear()
    }
}
