package com.osfans.trime.suzu

import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.osfans.trime.R
import com.osfans.trime.ui.common.SheetButton
import com.osfans.trime.ui.common.settingsSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

internal class LegacyPackageMigration(private val activity: ComponentActivity) {
    private val backupPicker = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) show() else migrate { LegacyRimeFiles.fromZip(activity, uri) }
    }

    companion object {
        private const val COMPLETE = "suzu_legacy_package_migration_handled"
        private val migrationLock = Mutex()

        @Suppress("DEPRECATION")
        fun shouldOffer(activity: ComponentActivity): Boolean {
            if (activity.packageName != SUZU_PACKAGE ||
                PreferenceManager.getDefaultSharedPreferences(activity).getBoolean(COMPLETE, false)
            ) return false
            return runCatching { activity.packageManager.getApplicationInfo(LEGACY_SUZU_PACKAGE, 0) }.isSuccess
        }
    }

    fun show() {
        val availability = AiDaemonLauncher.availability()
        if (availability == AiDaemonLauncher.Availability.SHIZUKU_NOT_RUNNING) {
            showHelp(R.string.legacy_migration_shizuku_stopped)
            return
        }
        val needsPermission = availability == AiDaemonLauncher.Availability.NEEDS_PERMISSION
        val guide = zipImportButton()
        activity.settingsSheet(
            icon = "archive_outline",
            title = activity.getString(R.string.legacy_migration_title),
            message = activity.getString(if (needsPermission) R.string.legacy_migration_permission else R.string.legacy_migration_message),
            body = guide,
            confirm = SheetButton(activity.getString(if (needsPermission) R.string.grant_permission else R.string.legacy_migration_start)) {
                start()
            },
            extra = SheetButton(activity.getString(R.string.legacy_migration_skip), ::finish),
            cancelable = false,
        ).apply {
            guide.setOnClickListener {
                dismiss()
                openZipPicker()
            }
            show()
        }
    }

    private fun start() {
        when (AiDaemonLauncher.availability()) {
            AiDaemonLauncher.Availability.READY -> migrate { LegacyRimeFiles.fromShizuku(activity) }
            AiDaemonLauncher.Availability.NEEDS_PERMISSION -> AiDaemonLauncher.requestPermission(
                onDenied = { if (!activity.isFinishing && !activity.isDestroyed) showHelp(R.string.legacy_migration_permission) },
            ) {
                if (!activity.isFinishing && !activity.isDestroyed) show()
            }
            AiDaemonLauncher.Availability.SHIZUKU_NOT_RUNNING -> showHelp(R.string.legacy_migration_shizuku_stopped)
        }
    }

    private fun migrate(copyFiles: () -> Int) {
        activity.lifecycleScope.launch {
            val progress = ProgressDialog(activity, activity.getString(R.string.legacy_migration_title))
            val result = try {
                withContext(Dispatchers.IO) {
                    migrationLock.withLock {
                        val files = copyFiles()
                        val settings = activity.importLegacySettings()
                        settings to files
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Timber.w(error, "Legacy migration incomplete")
                null
            } finally {
                progress.dismiss()
            }
            val (settings, files) = result ?: return@launch showHelp(R.string.legacy_migration_failed)
            PreferenceManager.getDefaultSharedPreferences(activity).edit { putBoolean(COMPLETE, true) }
            val summary = activity.getString(
                if (settings) R.string.legacy_migration_done else R.string.legacy_migration_done_files,
                files,
            )
            activity.settingsSheet(
                icon = "archive_outline",
                title = activity.getString(R.string.legacy_migration_title),
                message = summary,
                confirm = SheetButton(activity.getString(R.string.done)) { activity.recreate() },
                cancelable = false,
            ).show()
        }
    }

    private fun zipImportButton() =
        MaterialButton(activity, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            setText(R.string.legacy_migration_manual)
        }

    private fun openZipPicker() {
        backupPicker.launch(arrayOf("application/zip", "application/x-zip-compressed"))
    }

    private fun showHelp(message: Int) {
        val guide = zipImportButton()
        activity.settingsSheet(
            icon = "information_outline",
            title = activity.getString(R.string.legacy_migration_title),
            message = activity.getString(message),
            body = guide,
            confirm = SheetButton(activity.getString(R.string.legacy_migration_retry), ::show),
            extra = SheetButton(activity.getString(R.string.legacy_migration_skip), ::finish),
            cancelable = false,
        ).apply {
            guide.setOnClickListener {
                dismiss()
                openZipPicker()
            }
            show()
        }
    }

    private fun finish() {
        PreferenceManager.getDefaultSharedPreferences(activity).edit { putBoolean(COMPLETE, true) }
        activity.recreate()
    }
}
