/*
 * Copyright (c) 2017, 2018, 2019 Adetunji Dahunsi.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tunjid.fingergestures.activities

import android.Manifest
import android.content.Intent
import android.content.Intent.ACTION_SEND
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.net.Uri
import android.os.Bundle
import android.transition.AutoTransition
import android.transition.Transition
import android.transition.TransitionListenerAdapter
import android.transition.TransitionManager
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.WindowInsets
import android.view.animation.AnimationUtils.loadAnimation
import android.widget.TextSwitcher
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.annotation.IntDef
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import com.android.billingclient.api.BillingClient
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HIDDEN
import com.google.android.material.snackbar.BaseTransientBottomBar.LENGTH_INDEFINITE
import com.google.android.material.snackbar.BaseTransientBottomBar.LENGTH_SHORT
import com.google.android.material.snackbar.Snackbar
import com.tunjid.androidx.core.content.colorAt
import com.tunjid.androidx.navigation.MultiStackNavigator
import com.tunjid.androidx.navigation.Navigator
import com.tunjid.androidx.navigation.doOnLifecycleEvent
import com.tunjid.androidx.navigation.multiStackNavigationController
import com.tunjid.fingergestures.App
import com.tunjid.fingergestures.App.Companion.accessibilityServiceEnabled
import com.tunjid.fingergestures.App.Companion.hasStoragePermission
import com.tunjid.fingergestures.App.Companion.withApp
import com.tunjid.fingergestures.BackgroundManager
import com.tunjid.fingergestures.BackgroundManager.Companion.ACTION_EDIT_WALLPAPER
import com.tunjid.fingergestures.BackgroundManager.Companion.ACTION_NAV_BAR_CHANGED
import com.tunjid.fingergestures.GlobalUiController
import com.tunjid.fingergestures.InsetLifecycleCallbacks
import com.tunjid.fingergestures.R
import com.tunjid.fingergestures.billing.BillingManager
import com.tunjid.fingergestures.billing.PurchasesManager
import com.tunjid.fingergestures.billing.PurchasesManager.Companion.ACTION_LOCKED_CONTENT_CHANGED
import com.tunjid.fingergestures.fragments.AppFragment
import com.tunjid.fingergestures.globalUiDriver
import com.tunjid.fingergestures.models.TextLink
import com.tunjid.fingergestures.models.UiState
import com.tunjid.fingergestures.services.FingerGestureService.Companion.ACTION_SHOW_SNACK_BAR
import com.tunjid.fingergestures.services.FingerGestureService.Companion.EXTRA_SHOW_SNACK_BAR
import com.tunjid.fingergestures.viewholders.DiffViewHolder
import com.tunjid.fingergestures.viewmodels.AppViewModel
import com.tunjid.fingergestures.viewmodels.UiUpdate
import io.reactivex.disposables.CompositeDisposable

class MainActivity : AppCompatActivity(R.layout.activity_main), GlobalUiController, Navigator.Controller {

    private val constraintLayout by lazy { findViewById<ConstraintLayout>(R.id.constraint_layout) }
    private val shillSwitcher by lazy { findViewById<TextSwitcher>(R.id.upgrade_prompt) }
    private val bottomSheetBehavior by lazy { BottomSheetBehavior.from(findViewById<View>(R.id.bottom_sheet)) }

    private val coordinator by lazy { findViewById<View>(R.id.coordinator_layout) }

    private var billingManager: BillingManager? = null

    private val disposables = CompositeDisposable()

    private val viewModel by viewModels<AppViewModel>()

    override var uiState: UiState by globalUiDriver { navigator.activeNavigator }

    override val navigator: MultiStackNavigator by multiStackNavigationController(
            5,
            R.id.main_fragment_container
    ) {
        AppFragment.newInstance(viewModel.navItems[it])
    }

    private val navBarColor: Int
        get() = colorAt(
                if (BackgroundManager.instance.usesColoredNav()) R.color.colorPrimary
                else R.color.black
        )

    private val transition: Transition
        get() = AutoTransition().excludeTarget(RecyclerView::class.java, true)

    @Retention(AnnotationRetention.SOURCE)
    @IntDef(STORAGE_CODE, SETTINGS_CODE, ACCESSIBILITY_CODE, DO_NOT_DISTURB_CODE)
    annotation class PermissionRequest

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        uiState = uiState.copy(
                navBarColor = navBarColor,
                toolBarMenu = R.menu.activity_main,
                toolbarTitle = getString(R.string.app_name),
                fabClickListener = View.OnClickListener { viewModel.onPermissionClicked(this::onPermissionClicked) }
        )

        val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        bottomNavigationView.setOnNavigationItemSelectedListener(this::onOptionsItemSelected)
        bottomNavigationView.setOnApplyWindowInsetsListener { _: View?, windowInsets: WindowInsets? -> windowInsets }

        supportFragmentManager.registerFragmentLifecycleCallbacks(InsetLifecycleCallbacks(
                this@MainActivity,
                this@MainActivity.findViewById(R.id.constraint_layout),
                this@MainActivity.findViewById(R.id.main_fragment_container),
                this@MainActivity.findViewById(R.id.coordinator_layout),
                this@MainActivity.findViewById(R.id.toolbar),
                bottomNavigationView,
                this@MainActivity.navigator::activeNavigator
        ), true)

        navigator.stackSelectedListener = {
            viewModel.checkPermissions()
            val current = navigator.current
            if (current is AppFragment) updateBottomNav(current, bottomNavigationView)
        }
        onBackPressedDispatcher.addCallback(this) { if (!navigator.pop()) finish() }

        toggleBottomSheet(false)

        val startIntent = intent
        val isPickIntent = startIntent != null && ACTION_SEND == startIntent.action

        if (savedInstanceState == null && isPickIntent) handleIntent(startIntent)

        setUpSwitcher()
    }

    override fun onResume() {
        super.onResume()

        billingManager = BillingManager(applicationContext)

        if (PurchasesManager.instance.hasAds()) shill()
        else hideAds()

        if (!accessibilityServiceEnabled()) requestPermission(ACCESSIBILITY_CODE)

        uiState = uiState.copy(toolbarInvalidated = true)

        subscribeToBroadcasts()
        disposables.add(viewModel.uiState().subscribe(this::onStateChanged, Throwable::printStackTrace))
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_start_trial -> {
                val purchasesManager = PurchasesManager.instance
                val isTrialRunning = purchasesManager.isTrialRunning

                withSnackbar { snackbar ->
                    snackbar.setText(purchasesManager.trialPeriodText)
                    snackbar.duration = if (isTrialRunning) LENGTH_SHORT else LENGTH_INDEFINITE

                    if (!isTrialRunning)
                        snackbar.setAction(android.R.string.yes) { purchasesManager.startTrial(); recreate() }

                    snackbar.show()
                }
            }
            R.id.action_directions -> {
                showAppFragment(viewModel.gestureItems)
                return true
            }
            R.id.action_slider -> {
                showAppFragment(viewModel.brightnessItems)
                return true
            }
            R.id.action_audio -> {
                showAppFragment(viewModel.audioItems)
                return true
            }
            R.id.action_accessibility_popup -> {
                showAppFragment(viewModel.popupItems)
                return true
            }
            R.id.action_wallpaper -> {
                showAppFragment(viewModel.appearanceItems)
                return true
            }
            R.id.info -> {
                AlertDialog.Builder(this)
                        .setTitle(R.string.open_source_libraries)
                        .setItems(viewModel.state.links) { _, index -> showLink(viewModel.state.links[index]) }
                        .show()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() =
            if (bottomSheetBehavior.state != STATE_HIDDEN) toggleBottomSheet(false)
            else super.onBackPressed()

    override fun onPause() {
        billingManager?.destroy()
        billingManager = null
        disposables.clear()
        super.onPause()
    }

    override fun onDestroy() {
        DiffViewHolder.onActivityDestroyed()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        viewModel.onPermissionChange(requestCode)?.apply { showSnackbar(this) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != STORAGE_CODE || grantResults.isEmpty() || grantResults[0] != PERMISSION_GRANTED)
            return

        viewModel.onPermissionChange(requestCode)?.apply { showSnackbar(this) }
        (navigator.current as? AppFragment)?.notifyDataSetChanged()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    fun requestPermission(@PermissionRequest permission: Int) {
        viewModel.requestPermission(permission)
    }

    fun toggleBottomSheet(show: Boolean) {
        bottomSheetBehavior.state = if (show) STATE_COLLAPSED else STATE_HIDDEN
    }

    private fun showAppFragment(items: IntArray) {
        val index = viewModel.navItems.indexOf(items)
        if (index >= 0) navigator.show(index)
    }

    private fun handleIntent(intent: Intent) {
        val action = intent.action
        val type = intent.type

        if (ACTION_SEND != action || type == null || !type.startsWith("image/")) return

        if (!hasStoragePermission) {
            showSnackbar(R.string.enable_storage_settings)
            showAppFragment(viewModel.gestureItems)
            return
        }

        val imageUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return

        val toShow = AppFragment.newInstance(viewModel.appearanceItems)
        val tag = toShow.stableTag

        navigator.show(viewModel.navItems.indexOf(viewModel.appearanceItems))

        BackgroundManager.instance.requestWallPaperConstant(R.string.choose_target, this) { selection ->
            val shown = navigator.find(tag) as AppFragment?

            if (shown != null && shown.isVisible) shown.doOnLifecycleEvent(Lifecycle.Event.ON_RESUME) { shown.cropImage(imageUri, selection) }
            else showSnackbar(R.string.error_wallpaper)
        }
    }

    private fun askForStorage() {
        showPermissionDialog(R.string.wallpaper_permission_request) { requestPermissions(STORAGE_PERMISSIONS, STORAGE_CODE) }
    }

    private fun askForSettings() {
        showPermissionDialog(R.string.settings_permission_request) { startActivityForResult(App.settingsIntent, SETTINGS_CODE) }
    }

    private fun askForAccessibility() {
        showPermissionDialog(R.string.accessibility_permissions_request) { startActivityForResult(App.accessibilityIntent, ACCESSIBILITY_CODE) }
    }

    private fun askForDoNotDisturb() {
        showPermissionDialog(R.string.do_not_disturb_permissions_request) { startActivityForResult(App.doNotDisturbIntent, DO_NOT_DISTURB_CODE) }
    }

    private fun showPermissionDialog(@StringRes stringRes: Int, yesAction: () -> Unit) {
        AlertDialog.Builder(this)
                .setTitle(R.string.permission_required)
                .setMessage(stringRes)
                .setPositiveButton(R.string.yes) { _, _ -> yesAction.invoke() }
                .setNegativeButton(R.string.no) { dialog, _ -> dialog.dismiss() }
                .show()
    }

    private fun onPermissionClicked(permissionRequest: Int) {
        when (permissionRequest) {
            DO_NOT_DISTURB_CODE -> askForDoNotDisturb()
            ACCESSIBILITY_CODE -> askForAccessibility()
            SETTINGS_CODE -> askForSettings()
            STORAGE_CODE -> askForStorage()
        }
    }

    private fun updateBottomNav(fragment: AppFragment, bottomNavigationView: BottomNavigationView) =
            viewModel.updateBottomNav(fragment.items.contentHashCode())?.apply { bottomNavigationView.selectedItemId = this }

    private fun showLink(textLink: TextLink) {
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(textLink.link))
        startActivity(browserIntent)
    }

    private fun shill() {
        disposables.add(viewModel.shill().subscribe(shillSwitcher::setText, Throwable::printStackTrace))
    }

    private fun hideAds() {
        viewModel.calmIt()
        if (shillSwitcher.visibility == View.GONE) return

        val hideTransition = transition
        hideTransition.addListener(object : TransitionListenerAdapter() {
            override fun onTransitionEnd(transition: Transition) = showSnackbar(R.string.billing_thanks)
        })
        TransitionManager.beginDelayedTransition(constraintLayout, hideTransition)
        shillSwitcher.visibility = View.GONE
    }

    private fun setUpSwitcher() {
        shillSwitcher.setFactory {
            val view = LayoutInflater.from(this).inflate(R.layout.text_switch, shillSwitcher, false)
            view.setOnClickListener { viewModel.shillMoar() }
            view
        }

        shillSwitcher.inAnimation = loadAnimation(this, android.R.anim.slide_in_left)
        shillSwitcher.outAnimation = loadAnimation(this, android.R.anim.slide_out_right)
    }

    private fun onStateChanged(uiUpdate: UiUpdate) {
        uiState = uiState.copy(
                fabIcon = uiUpdate.iconRes,
                fabText = getString(uiUpdate.titleRes),
                fabShows = uiUpdate.fabVisible
        )
    }

    private fun subscribeToBroadcasts() {
        withApp { app ->
            disposables.add(app.broadcasts()
                    .filter { this.intentMatches(it) }
                    .subscribe({ this.onBroadcastReceived(it) }, { error ->
                        error.printStackTrace()
                        subscribeToBroadcasts() // Resubscribe on error
                    }))
        }
    }

    private fun onBroadcastReceived(intent: Intent) {
        when (intent.action) {
            ACTION_EDIT_WALLPAPER -> showSnackbar(R.string.error_wallpaper_google_photos)
            ACTION_SHOW_SNACK_BAR -> showSnackbar(intent.getIntExtra(EXTRA_SHOW_SNACK_BAR, R.string.generic_error))
            ACTION_NAV_BAR_CHANGED -> window.navigationBarColor = navBarColor
            ACTION_LOCKED_CONTENT_CHANGED -> recreate()
        }
    }

    private fun intentMatches(intent: Intent): Boolean {
        val action = intent.action
        return (ACTION_EDIT_WALLPAPER == action
                || ACTION_SHOW_SNACK_BAR == action
                || ACTION_NAV_BAR_CHANGED == action
                || ACTION_LOCKED_CONTENT_CHANGED == action)
    }

    fun showSnackbar(@StringRes resource: Int) =
            withSnackbar { snackbar -> snackbar.setText(resource);snackbar.show() }


    fun purchase(@PurchasesManager.SKU sku: String) = when (val billingManager = billingManager) {
        null -> showSnackbar(R.string.generic_error)
        else -> disposables.add(billingManager.initiatePurchaseFlow(this, sku)
                .subscribe({ launchStatus ->
                    when (launchStatus) {
                        BillingClient.BillingResponse.OK -> Unit
                        BillingClient.BillingResponse.SERVICE_UNAVAILABLE, BillingClient.BillingResponse.SERVICE_DISCONNECTED -> showSnackbar(R.string.billing_not_connected)
                        BillingClient.BillingResponse.ITEM_ALREADY_OWNED -> showSnackbar(R.string.billing_you_own_this)
                        else -> showSnackbar(R.string.generic_error)
                    }
                }, { showSnackbar(R.string.generic_error) })).let { }
    }

    private fun withSnackbar(consumer: (Snackbar) -> Unit) {
        val snackbar = Snackbar.make(coordinator, R.string.app_name, Snackbar.LENGTH_SHORT)
        snackbar.view.setOnApplyWindowInsetsListener { _, insets -> insets }
        consumer.invoke(snackbar)
    }

    companion object {

        const val STORAGE_CODE = 100
        const val SETTINGS_CODE = 200
        const val ACCESSIBILITY_CODE = 300
        const val DO_NOT_DISTURB_CODE = 400

        private val STORAGE_PERMISSIONS = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}