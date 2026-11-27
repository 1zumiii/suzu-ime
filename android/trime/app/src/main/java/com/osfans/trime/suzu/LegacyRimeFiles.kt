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
import java.util.zip.ZipInputStream

internal object LegacyRimeFiles {
    fun fromShizuku(context: Context): Int {
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
            val command = arrayOf("sh", "-c", "exec 2>/dev/null\n$script", "suzu-migrate", argument)
            val process = method.invoke(null, command, null, null) as Process
            try {
                val data = process.inputStream.buffered().use(read)
                check(process.waitFor() == 0) { "Unable to read legacy Rime data" }
                return data
            } finally {
                process.destroy()
            }
        }
        val entries = shell(script, source.absolutePath) { it.readBytes().toString(Charsets.UTF_8).split('\u0000') }
        return copy(context) { stage, target ->
            for (entry in entries) {
                if (entry.isEmpty()) continue
                val directory = entry.first() == 'D'
                check(directory || entry.first() == 'F')
                val path = relativePath(entry.substring(1)) ?: continue
                if (target.resolve(path.substringBefore('/')).exists()) continue
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

    fun fromZip(context: Context, uri: Uri): Int = copy(context) { stage, target ->
        val stream = requireNotNull(context.contentResolver.openInputStream(uri))
        ZipInputStream(stream.buffered()).use { zip ->
            var hasRimeEntries = false
            while (true) {
                val entry = zip.nextEntry ?: break
                val path = relativePath(entry.name)
                hasRimeEntries = hasRimeEntries || path != null
                if (path != null && !target.resolve(path.substringBefore('/')).exists()) {
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

    private fun copy(context: Context, fill: (stage: File, target: File) -> Unit): Int {
        val external = requireNotNull(context.getExternalFilesDir(null))
        val target = external.resolve("rime")
        check(target.isDirectory || target.mkdirs())
        val stage = File.createTempFile(".suzu-migrate-", "", external)
        check(stage.delete() && stage.mkdir())
        try {
            fill(stage, target)
            return requireNotNull(stage.listFiles()).count { entry ->
                val destination = target.resolve(entry.name)
                if (destination.exists()) false
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        Files.move(entry.toPath(), destination.toPath())
                        true
                    } catch (_: FileAlreadyExistsException) {
                        false
                    }
                } else {
                    check(entry.renameTo(destination))
                    true
                }
            }
        } finally {
            stage.deleteRecursively()
        }
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
