/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.graphics.drawable.DrawerArrowDrawable
import androidx.core.view.MenuItemCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.forEach
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.fragment.NavHostFragment
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import com.osfans.trime.BuildConfig
import com.osfans.trime.R
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.soundeffect.SoundEffectManager
import com.osfans.trime.databinding.ActivityMainBinding
import com.osfans.trime.suzu.AppUpdater
import com.osfans.trime.suzu.LegacyPackageMigration
import com.osfans.trime.ui.common.SheetButton
import com.osfans.trime.ui.common.mdi
import com.osfans.trime.ui.common.settingsSheet
import com.osfans.trime.ui.common.themeColor
import com.osfans.trime.ui.setup.SetupActivity
import com.osfans.trime.util.item
import com.osfans.trime.util.parcelable
import com.osfans.trime.util.startActivity
import kotlinx.coroutines.launch
import splitties.dimensions.dp
import splitties.views.topPadding

class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val uiMode by AppPrefs.defaultInstance().advanced.uiMode

    private lateinit var navController: NavController
    private lateinit var updateItem: MenuItem
    private var testInputMode = 0
    private var testInputSheet: BottomSheetDialog? = null
    private val legacyMigration = LegacyPackageMigration(this) {
        viewModel.rime.launchOnReady { it.deploy() }
    }

    internal fun showLegacyMigration(replaceExisting: Boolean = false) = legacyMigration.show(replaceExisting)

    override fun onCreate(savedInstanceState: Bundle?) {
        val uiMode =
            when (uiMode) {
                AppPrefs.Advanced.UiMode.AUTO -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                AppPrefs.Advanced.UiMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                AppPrefs.Advanced.UiMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            }
        AppCompatDelegate.setDefaultNightMode(uiMode)
        super.onCreate(savedInstanceState)
        if (LegacyPackageMigration.shouldOffer(this)) {
            legacyMigration.show()
            return
        }
        if (SetupActivity.shouldShowUp()) {
            startActivity<SetupActivity>()
            finish()
            return
        }
        enableEdgeToEdge()
        val binding = ActivityMainBinding.inflate(layoutInflater)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = systemBars.left
                rightMargin = systemBars.right
                bottomMargin = maxOf(systemBars.bottom, ime.bottom)
            }
            binding.mainToolbar.root.topPadding = systemBars.top
            windowInsets
        }
        ViewCompat.setWindowInsetsAnimationCallback(
            binding.root,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {
                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: List<WindowInsetsAnimationCompat?>,
                ): WindowInsetsCompat {
                    val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                    val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                    binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                        bottomMargin = maxOf(systemBars.bottom, ime.bottom)
                    }
                    return insets
                }
            },
        )
        // Material 3 的顶栏与页面同色，状态栏图标跟随深浅色
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }

        setContentView(binding.root)
        // 子页面显示返回箭头；主页用列表里的大标题，顶栏不显示标题和箭头
        val backIcon =
            DrawerArrowDrawable(this).apply {
                progress = 1f
                color = MaterialColors.getColor(binding.mainToolbar.toolbar, com.google.android.material.R.attr.colorOnSurface)
            }
        binding.mainToolbar.toolbar.navigationIcon = backIcon
        // show menu icon and other action icons on toolbar
        // don't use `setSupportActionBar(binding.toolbar)` here,
        // because navController would change toolbar title, we need to control it by ourselves
        setupToolbarMenu(binding.mainToolbar.toolbar.menu)
        navController = binding.navHostFragment.getFragment<NavHostFragment>().navController
        navController.graph = NavigationRoute.createGraph(navController)
        binding.mainToolbar.toolbar.setNavigationOnClickListener {
            // prevent navigate up when child fragment has enabled `OnBackPressedCallback`
            if (onBackPressedDispatcher.hasEnabledCallbacks()) {
                onBackPressedDispatcher.onBackPressed()
                return@setNavigationOnClickListener
            }
            // "minimize" the activity if we can't go back
            navController.navigateUp() || onSupportNavigateUp() || moveTaskToBack(false)
        }
        viewModel.toolbarTitle.observe(this) {
            binding.mainToolbar.toolbar.title = it
        }
        navController.addOnDestinationChangedListener { _, dest, _ ->
            val isMain = dest.hasRoute<NavigationRoute.Main>()
            viewModel.setToolbarTitle(if (isMain) "" else dest.label?.toString().orEmpty())
            binding.mainToolbar.toolbar.subtitle = ""
            binding.mainToolbar.toolbar.navigationIcon = if (isMain) null else backIcon
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        processIntent(intent)
        checkNotificationPermission()
        lifecycleScope.launch {
            AppUpdater.autoCheck(this@MainActivity)
            refreshUpdateItem()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (::navController.isInitialized) processIntent(intent) else setIntent(intent)
    }

    private fun processIntent(intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            Intent.ACTION_MAIN -> if (SetupActivity.shouldShowUp()) {
                startActivity<SetupActivity>()
            }

            Intent.ACTION_RUN -> {
                val route = intent.parcelable<NavigationRoute>(EXTRA_SETTINGS_ROUTE) ?: return
                navController.popBackStack(NavigationRoute.Main, false)
                navController.navigate(route)
            }
        }
    }

    private fun setupToolbarMenu(menu: Menu) {
        val deploy = menu.item(R.string.deploy, showAsAction = true) {
            viewModel.rime.launchOnReady { it.deploy() }
        }.apply { icon = mdi("refresh", toolbarIconColor()) }
        updateItem = menu.item(R.string.suzu_update_check, showAsAction = true) {
            showAppUpdates(allowSkip = true)
        }
        refreshUpdateItem()
        val optionMenuItems = listOf(
            deploy,
            updateItem,
            menu.item(R.string.test_input) {
                if (testInputSheet?.isShowing != true) {
                    testInputSheet = showTestInputSheet(testInputMode) { testInputMode = it }
                        .apply { setOnDismissListener { testInputSheet = null } }
                }
            },
            menu.item(R.string.developer) {
                navController.navigate(NavigationRoute.Developer)
            },
            menu.item(R.string.about) {
                navController.navigate(NavigationRoute.About)
            },
        )
        optionMenuItems.forEach { item ->
            viewModel.topOptionsMenu.observe(this) { enabled ->
                item.isVisible = enabled
            }
        }
        menu.item(R.string.edit, showAsAction = true) {
            viewModel.toolbarEditButtonOnClickListener.value?.invoke()
        }.apply {
            icon = mdi("pencil_outline", toolbarIconColor())
            viewModel.toolbarEditButtonVisible.observe(this@MainActivity) {
                isVisible = it
            }
        }
        menu.item(R.string.delete, showAsAction = true) {
            viewModel.toolbarDeleteButtonOnClickListener.value?.invoke()
        }.apply {
            icon = mdi("delete_outline", toolbarIconColor())
            viewModel.toolbarDeleteButtonOnClickListener.observe(this@MainActivity) {
                isVisible = it != null
            }
        }
        menu.forEach { item ->
            // show menu item on demand
            item.isVisible = false
        }
    }

    private fun toolbarIconColor() = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)

    private fun refreshUpdateItem() {
        val version = AppUpdater.availableVersion(this)
        val download = mdi("download_outline", toolbarIconColor())
        updateItem.icon = if (version == null) {
            download
        } else {
            val dot = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(233, 51, 62))
            }
            LayerDrawable(arrayOf(download, dot)).apply {
                setLayerInset(1, dp(17), dp(17), 0, 0)
            }
        }
        MenuItemCompat.setContentDescription(
            updateItem,
            if (version == null) getString(R.string.suzu_update_check) else getString(R.string.suzu_update_available, version),
        )
    }

    fun showAppUpdates(allowSkip: Boolean = false) {
        AppUpdater.manualCheck(this, lifecycleScope, allowSkip, ::refreshUpdateItem)
    }

    override fun onResume() {
        super.onResume()
        SoundEffectManager.init()
    }

    override fun onStop() {
        super.onStop()
        testInputSheet?.dismiss()
    }

    private fun checkNotificationPermission() {
        if (XXPermissions.isGranted(this, Permission.POST_NOTIFICATIONS)) {
            return
        } else {
            settingsSheet(
                icon = "bell_outline",
                title = getString(R.string.notification_permission_title),
                message = getString(R.string.notification_permission_message),
                cancel = getString(android.R.string.cancel),
                confirm = SheetButton(getString(R.string.grant_permission)) {
                    XXPermissions
                        .with(this)
                        .permission(Permission.POST_NOTIFICATIONS)
                        .request(null)
                },
            ).show()
        }
    }

    companion object {
        const val EXTRA_SETTINGS_ROUTE = "${BuildConfig.APPLICATION_ID}.EXTRA_SETTINGS_ROUTE"
    }
}
