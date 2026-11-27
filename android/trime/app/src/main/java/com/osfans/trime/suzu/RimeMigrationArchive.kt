package com.osfans.trime.suzu

import android.content.Context
import android.net.Uri
import com.osfans.trime.data.base.DataManager
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object RimeMigrationArchive {
    private val excludedRoots = setOf(
        "build", "llm_token", "ai_status.json", "ai_enabled.state", ".suzu-model-downloads",
        "input_log.jsonl", "select_stats.tsv", "select_corpus.tsv", "fix_stats.tsv",
    )

    fun includes(path: String): Boolean {
        val root = path.substringBefore('/')
        return root !in excludedRoots && !root.startsWith("select_replay") &&
            !root.startsWith(".suzu-migrate") && !root.endsWith(".log") &&
            !path.endsWith(".part") && !path.endsWith(".part.sha256")
    }

    fun export(context: Context, uri: Uri) {
        val root = DataManager.userDataDir.canonicalFile
        val output = requireNotNull(context.contentResolver.openOutputStream(uri, "wt"))
        ZipOutputStream(output.buffered()).use { zip ->
            zip.setLevel(1)
            val files = root.walkTopDown().onEnter { dir ->
                dir == root || includes(dir.relativeTo(root).invariantSeparatorsPath) && dir == dir.canonicalFile
            }
            for (file in files) {
                if (!file.isFile || file != file.canonicalFile) continue
                val path = file.relativeTo(root).invariantSeparatorsPath
                if (!includes(path)) continue
                zip.putNextEntry(ZipEntry(path).apply { time = file.lastModified() })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}
