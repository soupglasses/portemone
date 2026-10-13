// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID
import kotlin.math.roundToInt

internal object CardLogoImport {
    private const val MAX_BYTES = 10 * 1024 * 1024
    private const val MAX_EDGE = 512

    fun read(stream: InputStream): String {
        val bytes = stream.use {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= MAX_BYTES) {
                val count = it.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        require(bytes.size <= MAX_BYTES) { throw UserFacingException(R.string.error_choose_an_image_smaller_than_10_mb) }
        val bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                require(info.size.width.toLong() * info.size.height <= 100_000_000) { throw UserFacingException(R.string.error_this_image_is_too_large_choose_a_smaller) }
                val scale =
                    minOf(1.0, MAX_EDGE.toDouble() / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize(
                    (info.size.width * scale).roundToInt().coerceAtLeast(1),
                    (info.size.height * scale).roundToInt().coerceAtLeast(1)
                )
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            }
        } catch (error: ImageDecoder.DecodeException) {
            throw UserFacingException(R.string.error_unreadable_image).apply { initCause(error) }
        }
        return try {
            val output = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            Base64.getEncoder().encodeToString(output.toByteArray())
        } finally {
            bitmap.recycle()
        }
    }

    // Keep image bytes out of the saved-state Bundle; only a small draft token survives recreation.
    fun import(context: Context, uri: Uri): String {
        val image =
            read(requireNotNull(context.contentResolver.openInputStream(uri)) { throw UserFacingException(R.string.error_the_image_could_not_be_opened) })
        val directory = directory(context).apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        directory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        val token = UUID.randomUUID().toString()
        File(directory, token).writeText(image)
        return token
    }

    fun restore(context: Context, token: String): String {
        require(UUID.fromString(token).toString() == token)
        return File(directory(context), token).readText()
    }

    private fun directory(context: Context) = File(context.noBackupFilesDir, "card-logo-drafts")
}
