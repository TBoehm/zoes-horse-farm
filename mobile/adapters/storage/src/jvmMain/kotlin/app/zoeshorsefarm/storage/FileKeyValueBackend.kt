package app.zoeshorsefarm.storage

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * One file per key inside [dir] (created on the first write): the backend for tests and desktop
 * development. A write goes to a temporary file first and replaces the old one in one step, so a
 * crash never leaves half a save.
 */
class FileKeyValueBackend(
    private val dir: Path,
) : KeyValueBackend {
    // every character outside [A-Za-z0-9_-] is written as %XX of its UTF-8 bytes, so a key can
    // never leave the folder; the suffix keeps "." and ".." from becoming folder names
    private fun fileOf(key: String): Path = dir.resolve(encodeName(key) + SUFFIX)

    override fun getString(key: String): String? =
        try {
            String(Files.readAllBytes(fileOf(key)), StandardCharsets.UTF_8)
        } catch (_: NoSuchFileException) {
            null
        }

    override fun setString(
        key: String,
        value: String,
    ) {
        Files.createDirectories(dir)
        val target = fileOf(key)
        val temp = Files.createTempFile(dir, "write-", TEMP_SUFFIX)
        try {
            Files.write(temp, value.toByteArray(StandardCharsets.UTF_8))
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    override fun remove(key: String) {
        Files.deleteIfExists(fileOf(key))
    }

    private fun encodeName(key: String): String =
        buildString {
            for (byte in key.toByteArray(StandardCharsets.UTF_8)) {
                val c = byte.toInt().toChar()
                if (byte >= 0 && isSafe(c)) {
                    append(c)
                } else {
                    append('%').append("%02X".format(byte.toInt() and BYTE_MASK))
                }
            }
        }

    private fun isSafe(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-'

    private companion object {
        const val SUFFIX = ".value"
        const val TEMP_SUFFIX = ".tmp"
        const val BYTE_MASK = 0xFF
    }
}
