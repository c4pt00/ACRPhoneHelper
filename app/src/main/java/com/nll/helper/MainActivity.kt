package com.nll.helper

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nll.helper.databinding.ActivityMainBinding
import com.nll.helper.recorder.CLog
import com.nll.helper.server.ClientContentProviderHelper
import com.nll.helper.support.AccessibilityCallRecordingService
import com.nll.helper.update.UpdateChecker
import com.nll.helper.update.UpdateResult
import com.nll.helper.update.downloader.AppVersionData
import com.nll.helper.update.downloader.UpdateActivity
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val logTag = "CR_MainActivity"
    private var askedClientToConnect = false
    private var audioRecordPermissionStatusDefaultTextColor: ColorStateList? = null

    /**
     * Sometimes Android thinks service is cached while it reports enabled.
     * So we let user open the settings to see
     */
    private var openHelperServiceSettingsIfNeededClickCount = 0
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainActivityViewModel by viewModels {
        MainActivityViewModel.Factory(application)
    }

    private val recordAudioPermission = activityResultRegistry.register("audio", ActivityResultContracts.RequestPermission()) { hasAudioRecordPermission ->
        if (!hasAudioRecordPermission) {
            Toast.makeText(this, R.string.permission_all_required, Toast.LENGTH_SHORT).show()
        }
        updateAudioPermissionDisplay(hasAudioRecordPermission)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setTitle(R.string.app_name_helper_long)

        binding.accessibilityServiceStatus.text = String.format("%s (%s)", getString(R.string.accessibility_service_name), getString(R.string.app_name_helper))
        audioRecordPermissionStatusDefaultTextColor = binding.audioRecordPermissionStatus.textColors
        binding.connectionBetweenAppsStatus.text = String.format("%s ⬌ %s", getString(R.string.app_name_helper), getString(R.string.app_name))

        viewModel.observeAccessibilityServicesChanges().observe(this) { isEnabled ->
                onAccessibilityChanged(isEnabled)
            }
            viewModel.observeClientConnected().observe(this) { isConnected ->
                Log.i(logTag, "observeClientConnected() -> isConnected: $isConnected")
                onClientConnected(isConnected)
            }
            binding.accessibilityServiceCardActionButton.setOnClickListener {
                Log.i(logTag, "accessibilityServiceCardActionButton()")
                val openWithoutCheckingIfEnabled = openHelperServiceSettingsIfNeededClickCount > 0
                val opened = AccessibilityCallRecordingService.openHelperServiceSettingsIfNeeded(this, openWithoutCheckingIfEnabled)
                if (opened) {
                    openHelperServiceSettingsIfNeededClickCount = 0
                } else {
                    openHelperServiceSettingsIfNeededClickCount++
                }
            }

            binding.installMainAppCardActionButton.setOnClickListener {
                Log.i(logTag, "installMainAppCardActionButton() -> setOnClickListener")
                openPlayStore()
            }

            //Not that there could be only one job per addRepeatingJob
            //MUST BE STARTED as RESUMED creates loop when user manually denied permision from the settings
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    Log.i(logTag, "lifecycleScope() -> STARTED")
                    checkMainApp()
                    checkForAudioRecordPermission()
                    //checkAccessibilityServiceState()
                }
            }

            lifecycleScope.launch {
                //Update check
                onVersionUpdateResult(UpdateChecker.checkUpdate(this@MainActivity))
            }



    }


    private fun onVersionUpdateResult(updateResult: UpdateResult) {

        if (CLog.isDebug()) {
            CLog.log(logTag, "onVersionUpdateResult -> updateResult: $updateResult")
        }
        when (updateResult) {
            is UpdateResult.Required -> {
                showUpdateMessage(updateResult)
            }
            is UpdateResult.NotRequired -> {
                if (CLog.isDebug()) {
                    CLog.log(logTag, "onVersionUpdateResult -> NotRequired")
                }
            }
        }
    }

    private fun showUpdateMessage(updateResult: UpdateResult.Required) {
        val appVersionData = AppVersionData(updateResult.remoteAppVersion.downloadUrl, updateResult.remoteAppVersion.versionCode, updateResult.remoteAppVersion.whatsNewMessage)
        UpdateActivity.start(this, appVersionData)
    }

    private fun onAccessibilityChanged(isEnabled: Boolean) {
        Log.i(logTag, "onAccessibilityChanged -> isEnabled: $isEnabled")

        binding.accessibilityServiceDisabledCard.isVisible = isEnabled.not()
        binding.accessibilityServiceStatus.extSetCompoundDrawablesWithIntrinsicBoundsToRightOrLeft(
            if (isEnabled) {
                R.drawable.ic_green_checked_24dp
            } else {
                R.drawable.ic_red_error_24dp
            }, 8f
        )


        /**
         * Re: lifecycleScope.launch
         * Seems to help with avoiding Skipped 38 frames!  The application may be doing too much work on its main thread.
         */
        lifecycleScope.launch {
            //Make sure we are not in the background to avoid -> Not allowed to start service Intent { cmp=com.nll.cb/.record.support.AccessibilityCallRecordingService }: app is in background
            if (isEnabled && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                //Start Service. We do this here rather then AndroidViewModelObserving because we would have called start service as many times as ViewModel count that extending AndroidViewModelObserving
                AccessibilityCallRecordingService.startHelperServiceIfIsNotRunning(application)
            }
        }
    }

    private fun onClientConnected(isConnected: Boolean) {
        if (!isConnected) {
            Log.i(logTag, "onClientConnected() -> isConnected is false. Calling checkMainApp()")
            checkMainApp()
        }
        binding.connectionBetweenAppsStatus.extSetCompoundDrawablesWithIntrinsicBoundsToRightOrLeft(
            if (isConnected) {
                R.drawable.ic_green_checked_24dp
            } else {
                R.drawable.ic_red_error_24dp
            }, 8f
        )
    }

    private fun checkMainApp() {
        val clientVersionData = ClientContentProviderHelper.getClientVersionData(this)
        val isAcrPhoneInstalled = clientVersionData != null
        val clientNeedsUpdating = if (isAcrPhoneInstalled) {
            (clientVersionData?.clientVersion ?: -1) < BuildConfig.MINIMUM_CLIENT_VERSION_CODE
        } else {
            false
        }
        Log.i(logTag, "checkMainApp() -> clientVersionData: $clientVersionData, clientNeedsUpdating: $clientNeedsUpdating")

        binding.installAcrPhone.isVisible = !isAcrPhoneInstalled
        if (isAcrPhoneInstalled && !askedClientToConnect) {
            //Ask client to connect as it may be in closed state
            ClientContentProviderHelper.askToClientToConnect(this)
            askedClientToConnect = true
        }

        binding.acrPhoneInstallationStatus.extSetCompoundDrawablesWithIntrinsicBoundsToRightOrLeft(
            if (isAcrPhoneInstalled) {
                R.drawable.ic_green_checked_24dp
            } else {
                R.drawable.ic_red_error_24dp
            }, 8f
        )

    }

    private fun checkForAudioRecordPermission() {
        val hasAudioRecordPermission = ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasAudioRecordPermission) {
            recordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        updateAudioPermissionDisplay(hasAudioRecordPermission)
    }

    private fun updateAudioPermissionDisplay(hasAudioRecordPermission: Boolean) {
        binding.audioRecordPermissionStatus.setTextColor(Color.BLUE)
        if (hasAudioRecordPermission) {
            binding.audioRecordPermissionStatus.setTextColor(audioRecordPermissionStatusDefaultTextColor)
            binding.audioRecordPermissionStatus.setOnClickListener(null)
        } else {
            binding.audioRecordPermissionStatus.setTextColor(Color.BLUE)
            binding.audioRecordPermissionStatus.setOnClickListener {
                extOpenAppDetailsSettings()
            }
        }
        binding.audioRecordPermissionStatus.extSetCompoundDrawablesWithIntrinsicBoundsToRightOrLeft(
            if (hasAudioRecordPermission) {
                R.drawable.ic_green_checked_24dp
            } else {
                R.drawable.ic_red_error_24dp
            }, 8f
        )


    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.i(logTag, "onNewIntent() -> intent: $intent")
        onAccessibilityChanged(AccessibilityCallRecordingService.isHelperServiceEnabled(this))
    }

    //Prevent close on back
    override fun onBackPressed() {
        Log.i(logTag, "onBackPressed()")
        moveTaskToBack(true)
    }

    private fun openPlayStore() = try {
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${ClientContentProviderHelper.clientPackageName}"))
            .let(::startActivity)
        true

    } catch (ignored: ActivityNotFoundException) {
        try {
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${ClientContentProviderHelper.clientPackageName}"))
                .let(::startActivity)
            true
        } catch (e: ActivityNotFoundException) {
            e.printStackTrace()
            false
        }
    }
}