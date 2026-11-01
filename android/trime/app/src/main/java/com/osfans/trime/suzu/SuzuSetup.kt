/*
 * SPDX-FileCopyrightText: 2026 1zumiii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.suzu

import timber.log.Timber

/** Rime 准备好之后、开始部署之前的步骤：迁移旧数据，确保启用了一个词库包。出错只记日志，不挡住 Rime 启动 */
object SuzuSetup {
    fun beforeMaintenance(): Boolean {
        val migrated = runCatching { SuzuMigration.run() }
            .onFailure { Timber.e(it, "Suzu migration failed") }
            .getOrDefault(false)
        val activated = runCatching { DictPacks.ensureActive() }
            .onFailure { Timber.e(it, "Activating dict pack failed") }
            .getOrDefault(false)
        return migrated || activated
    }
}
