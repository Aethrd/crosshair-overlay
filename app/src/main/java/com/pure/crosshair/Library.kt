package com.pure.crosshair

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/**
 * The user's imported crosshairs.
 *
 * Images are copied into the app's private storage on import, so the app keeps working after the
 * user moves or deletes the original file, and no long lived storage permission is needed.
 */
class Library(context: Context) {

    private val app = context.applicationContext
    private val dir = File(app.filesDir, "crosshairs")

    /** Imported files, oldest first. Names are timestamped, so name order is import order. */
    fun list(): List<File> =
        dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()

    fun isEmpty(): Boolean = list().isEmpty()

    fun file(name: String?): File? {
        if (name.isNullOrEmpty()) return null
        // Never let a stored name escape the library directory.
        if (name.contains('/') || name.contains("..")) return null
        val f = File(dir, name)
        return if (f.isFile) f else null
    }

    /**
     * Copies the picked image in. Returns the stored name, or null if it could not be read or
     * was not a decodable image.
     */
    fun import(uri: Uri): String? {
        if (!dir.exists() && !dir.mkdirs()) return null

        val name = "img_${System.currentTimeMillis()}_${(100..999).random()}"
        val target = File(dir, name)

        return runCatching {
            val copied = app.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            if (copied == null || target.length() == 0L) {
                target.delete()
                return null
            }
            // Reject anything Android cannot actually decode as an image.
            if (!isDecodable(target)) {
                target.delete()
                return null
            }
            name
        }.getOrElse {
            target.delete()
            null
        }
    }

    fun delete(name: String) {
        file(name)?.delete()
    }

    /** The next image to fall back to after [name] is removed, or null if the library is empty. */
    fun neighbourOf(name: String): String? {
        val all = list()
        if (all.isEmpty()) return null
        val i = all.indexOfFirst { it.name == name }
        val next = all.getOrNull(i + 1) ?: all.getOrNull(i - 1) ?: all.firstOrNull()
        return next?.name?.takeIf { it != name }
    }

    companion object {
        /**
         * Decodes downsampled to roughly [maxPx] on the long edge. Someone importing a 12 MP
         * photo should not be able to push the overlay into an OutOfMemoryError.
         */
        fun decode(file: File, maxPx: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching { BitmapFactory.decodeFile(file.path, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (bounds.outWidth / sample > maxPx || bounds.outHeight / sample > maxPx) {
                sample *= 2
            }

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            return runCatching { BitmapFactory.decodeFile(file.path, opts) }.getOrNull()
        }

        private fun isDecodable(file: File): Boolean {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching { BitmapFactory.decodeFile(file.path, bounds) }
            return bounds.outWidth > 0 && bounds.outHeight > 0
        }

        /** Long edge used for the on screen crosshair. */
        const val DISPLAY_PX = 1536

        /** Long edge used for picker thumbnails. */
        const val THUMB_PX = 128
    }
}
