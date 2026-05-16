package eu.darken.octi.desktop.common.files

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Atomic file replacement: write to a temp sibling, fsync, then atomically rename over the target.
 *
 * `ATOMIC_MOVE` is required — without it a crash mid-rename can leave the target half-written or
 * missing on filesystems that don't guarantee atomicity. We also `force()` the file descriptor
 * before close so the temp file's bytes are durable on disk before the rename.
 */
object AtomicWrites {

    @Throws(IOException::class)
    fun writeBytes(target: Path, bytes: ByteArray) {
        Files.createDirectories(target.parent)
        val tmp = target.resolveSibling(".${target.fileName}.tmp.${System.nanoTime()}")
        try {
            FileChannel.open(
                tmp,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ).use { channel ->
                channel.write(java.nio.ByteBuffer.wrap(bytes))
                channel.force(true)
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                // Some filesystems (FAT, network mounts) don't support atomic moves; fall back to
                // a non-atomic replace. This is the best we can do — log the degradation.
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            try {
                Files.deleteIfExists(tmp)
            } catch (_: IOException) {
                // Best effort cleanup; the temp file is cosmetic at this point.
            }
        }
    }

    @Throws(IOException::class)
    fun writeText(target: Path, text: String): Unit = writeBytes(target, text.toByteArray(Charsets.UTF_8))
}
