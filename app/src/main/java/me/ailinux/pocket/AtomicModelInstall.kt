package me.ailinux.pocket

import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files

/**
 * Publish a fully verified private model file without overwriting an existing
 * model. Both paths are in the same app-private directory/filesystem.
 *
 * POSIX rename() replaces an existing destination; createLink is atomic and
 * explicitly fails with FileAlreadyExistsException in that case. The partial
 * remains available if installation fails; callers own cleanup.
 */
internal object AtomicModelInstall {
    fun publish(verifiedPart: File, target: File): File {
        require(verifiedPart.isFile && verifiedPart.length() > 32L) {
            "Ein vollständig geprüftes Modell-Fragment ist erforderlich"
        }
        require(verifiedPart.canonicalFile.parentFile == target.canonicalFile.parentFile) {
            "Modell und Fragment müssen im selben privaten Ordner liegen"
        }
        if (target.exists()) throw FileAlreadyExistsException(target.name)
        Files.createLink(target.toPath(), verifiedPart.toPath())
        // A complete inode is already present at the final destination.
        // If partial cleanup fails, installed content remains valid and the
        // caller can remove the redundant .part later.
        Files.deleteIfExists(verifiedPart.toPath())
        return target
    }
}
