package me.ailinux.pocket

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Publish a fully verified and closed private model file under its final name.
 *
 * Android app-private filesystems may refuse hard-link creation with EPERM
 * even when both paths share a directory. Never require a hardlink for
 * valid model installation.
 *
 * Atomic SAME-DIRECTORY rename is the required operation. If unsupported,
 * leave the intact .part file: never fall back to an observable partial copy
 * at the installed-model path. ATOMIC_MOVE guarantees namespace atomicity,
 * not full storage durability against an abrupt power failure (no fsync).
 *
 * A same-process critical section, ModelStore's per-model transfer lease,
 * and app-private directory prevent app-owned concurrent publishers from
 * racing the target-exists check. External files must never be passed.
 * ATOMIC_MOVE itself does not guarantee NOREPLACE against an untrusted
 * process that can create the destination concurrently.
 */
internal object AtomicModelInstall {
    @Synchronized
    fun publish(
        verifiedPart: File,
        target: File,
        moveAtomically: (Path, Path) -> Path = { from, to ->
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
        }
    ): File {
        require(verifiedPart.isFile && verifiedPart.length() > 32L) {
            "Ein vollständig geprüftes Modell-Fragment ist erforderlich"
        }
        require(verifiedPart.canonicalFile.parentFile == target.canonicalFile.parentFile) {
            "Modell und Fragment müssen im selben privaten Ordner liegen"
        }
        if (target.exists()) throw FileAlreadyExistsException(target.name)
        try {
            moveAtomically(verifiedPart.toPath(), target.toPath())
        } catch (error: AtomicMoveNotSupportedException) {
            throw IOException(
                "Atomarer Modellabschluss auf diesem Android-Dateisystem nicht unterstützt; " +
                    "vollständige Teil-Datei bleibt erhalten", error
            )
        }
        check(target.isFile && !verifiedPart.exists()) {
            "Atomarer Modellabschluss nicht bestätigt; installierte Datei prüfen"
        }
        return target
    }
}
