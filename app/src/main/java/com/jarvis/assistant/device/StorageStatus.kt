package com.jarvis.assistant.device

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.util.Locale

/**
 * Internal-storage figures, read with [StatFs]. These are block-level totals for the data
 * partition, which is what a phone's "storage" page reports — no permission required.
 */
data class StorageStatus(
    val usedGb: Float = 0f,
    val freeGb: Float = 0f,
    val totalGb: Float = 0f,
    val percentUsed: Int = 0,
) {
    val usedLabel: String get() = String.format(Locale.US, "%.1f GB", usedGb)
    val freeLabel: String get() = String.format(Locale.US, "%.1f GB", freeGb)
    val totalLabel: String get() = String.format(Locale.US, "%.1f GB", totalGb)
}

fun readStorage(context: Context): StorageStatus {
    return try {
        val path = context.filesDir?.absolutePath
            ?: Environment.getDataDirectory().absolutePath
        val stat = StatFs(path)
        val blockSize = stat.blockSizeLong
        val total = stat.blockCountLong * blockSize
        val free = stat.availableBlocksLong * blockSize
        val used = (total - free).coerceAtLeast(0L)

        val gb = 1024.0 * 1024.0 * 1024.0
        StorageStatus(
            usedGb = (used / gb).toFloat(),
            freeGb = (free / gb).toFloat(),
            totalGb = (total / gb).toFloat(),
            percentUsed = if (total > 0L) ((used * 100.0 / total).toInt()).coerceIn(0, 100) else 0,
        )
    } catch (t: Throwable) {
        StorageStatus()
    }
}
