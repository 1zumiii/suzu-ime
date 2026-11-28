package com.osfans.trime.suzu

import android.content.Context
import android.net.Uri
import android.os.Build
import com.osfans.trime.R
import rikka.shizuku.Shizuku
import java.io.File
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

internal object LegacyRimeFiles {
    fun fromShizuku(context: Context, replaceExisting: Boolean = false): Int {
        val external = requireNotNull(context.getExternalFilesDir(null))
        val packages = requireNotNull(external.parentFile?.parentFile)
        val source = packages.resolve("$LEGACY_SUZU_PACKAGE/files/rime")
        val script = context.resources.openRawResource(R.raw.suzu_legacy_list).bufferedReader().use { it.readText() }
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        ).apply { isAccessible = true }
        fun <T> shell(script: String, argument: String, read: (InputStream) -> T): T {
            val command = arrayOf("sh", "-c", script, "suzu-migrate", argument)
            val process = method.invoke(null, command, null, null) as Process
            try {
                val data = process.inputStream.buffered().use(read)
                val code = process.waitFor()
                if (code != 0) {
                    val detail = runCatching {
                        process.errorStream.bufferedReader().use { it.readText() }.trim().take(200)
                    }.getOrDefault("")
                    error(if (detail.isNotEmpty()) "Unable to read legacy Rime data ($code): $detail" else "Unable to read legacy Rime data ($code)")
                }
                return data
            } finally {
                process.destroy()
            }
        }
        val entries = shell(script, source.absolutePath) { it.readBytes().toString(Charsets.UTF_8).split('\u0000') }
        return copy(context, replaceExisting) { stage, target ->
            for (entry in entries) {
                if (entry.isEmpty()) continue
                val directory = entry.first() == 'D'
                check(directory || entry.first() == 'F')
                val path = relativePath(entry.substring(1)) ?: continue
                if (shouldSkip(target, path, directory, replaceExisting)) continue
                val file = stage.resolve(path)
                if (directory) {
                    check(file.isDirectory || file.mkdirs())
                } else {
                    ensureParent(file)
                    // The shell only reads. Files and directories in the new app must belong to its UID.
                    shell("cat -- \"\$1\"", source.resolve(path).absolutePath) { input ->
                        file.outputStream().use { input.copyTo(it) }
                    }
                }
            }
        }
    }

    fun fromZip(context: Context, uri: Uri, replaceExisting: Boolean = false): Int = copy(context, replaceExisting) { stage, target ->
        val stream = requireNotNull(context.contentResolver.openInputStream(uri))
        ZipInputStream(stream.buffered()).use { zip ->
            var hasRimeEntries = false
            while (true) {
                val entry = zip.nextEntry ?: break
                val path = relativePath(entry.name)
                hasRimeEntries = hasRimeEntries || path != null
                if (path != null && !shouldSkip(target, path, entry.isDirectory, replaceExisting)) {
                    val file = stage.resolve(path)
                    if (entry.isDirectory) {
                        check(file.isDirectory || file.mkdirs())
                    } else {
                        ensureParent(file)
                        check(!file.exists()) { "Duplicate Rime backup entry" }
                        file.outputStream().use { zip.copyTo(it) }
                    }
                }
                zip.closeEntry()
            }
            check(hasRimeEntries) { "Rime backup is empty" }
        }
    }

    private fun copy(context: Context, replaceExisting: Boolean, fill: (stage: File, target: File) -> Unit): Int {
        val external = requireNotNull(context.getExternalFilesDir(null))
        val target = external.resolve("rime")
        check(target.isDirectory || target.mkdirs())
        val stage = File.createTempFile(".suzu-migrate-", "", external)
        check(stage.delete() && stage.mkdir())
        try {
            fill(stage, target)
            return requireNotNull(stage.listFiles()).sumOf { entry ->
                moveEntry(entry, target.resolve(entry.name), replaceExisting)
            }
        } finally {
            stage.deleteRecursively()
        }
    }

    private fun isUserDb(topLevelName: String): Boolean = topLevelName.endsWith(".userdb")

    private fun shouldSkip(target: File, path: String, directory: Boolean, replaceExisting: Boolean): Boolean {
        if (replaceExisting) return false
        val top = path.substringBefore('/')
        if (isUserDb(top) && target.resolve(top).exists()) return true
        return !directory && target.resolve(path).exists()
    }

    private fun moveEntry(entry: File, destination: File, replaceExisting: Boolean): Int {
        if (!destination.exists()) {
            return if (moveOne(entry, destination, replaceExisting = false)) 1 else 0
        }
        if (entry.isDirectory && destination.isDirectory) {
            if (isUserDb(entry.name)) {
                if (!replaceExisting) return 0
                check(destination.deleteRecursively())
                return if (moveOne(entry, destination, replaceExisting = false)) 1 else 0
            }
            return entry.listFiles().orEmpty().sumOf { child ->
                moveEntry(child, destination.resolve(child.name), replaceExisting)
            }
        }
        if (!replaceExisting) return 0
        if (destination.isDirectory) check(destination.deleteRecursively())
        return if (moveOne(entry, destination, replaceExisting = true)) 1 else 0
    }

    private fun moveOne(source: File, destination: File, replaceExisting: Boolean): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                if (replaceExisting) {
                    Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } else {
                    Files.move(source.toPath(), destination.toPath())
                }
                true
            } catch (_: FileAlreadyExistsException) {
                false
            }
        } else {
            if (replaceExisting && destination.exists()) check(destination.delete())
            check(source.renameTo(destination))
            true
        }

    private fun relativePath(name: String): String? {
        val path = name.removePrefix("./").trimEnd('/')
        if (path.isEmpty() || path == ".") return null
        val components = path.split('/')
        check(components.none { it.isEmpty() || it == "." || it == ".." }) { "Unsafe Rime backup path" }
        return path.takeIf(RimeMigrationArchive::includes)
    }

    private fun ensureParent(file: File) {
        val parent = requireNotNull(file.parentFile)
        check(parent.isDirectory || parent.mkdirs())
    }
}
