// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone.imports

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.UUID

/** Saved state holds only a token; pass packages can exceed Android's Bundle limit. */
internal class PassImportDrafts(context: Context) {
    private val directory = File(context.noBackupFilesDir, "pass-import-drafts")

    fun save(bytes: ByteArray): String {
        directory.mkdirs()
        val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        directory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        val token = UUID.randomUUID().toString()
        val file = AtomicFile(file(token))
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
        return token
    }

    fun open(token: String) = AtomicFile(file(token)).openRead()
    fun remove(token: String) = AtomicFile(file(token)).delete()

    private fun file(token: String): File {
        require(UUID.fromString(token).toString() == token)
        return File(directory, token)
    }
}
