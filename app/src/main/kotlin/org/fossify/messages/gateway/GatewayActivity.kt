package org.fossify.messages.gateway

import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.text.format.DateUtils
import androidx.core.net.toUri
import androidx.core.widget.doAfterTextChanged
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.RadioItem
import org.fossify.messages.BuildConfig
import org.fossify.messages.R
import org.fossify.messages.activities.SimpleActivity
import org.fossify.messages.databinding.ActivityGatewayBinding
import org.fossify.messages.gateway.core.DoorbellState
import org.fossify.messages.gateway.core.GatewayApi
import org.fossify.messages.gateway.core.HandleNormalizer
import org.fossify.messages.gateway.core.OwnNumbers

/** The operator screen: configuration, live status, "Sync now" and "Send test heartbeat". */
@Suppress("TooManyFunctions")
class GatewayActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityGatewayBinding::inflate)
    private val gateway by lazy { Gateway.get(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var chosenSubscription = GatewaySettings.NO_SUBSCRIPTION

    private val refresher = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, REFRESH_MILLIS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.gatewayNestedScrollview))
        setupMaterialScrollListener(binding.gatewayNestedScrollview, binding.gatewayAppbar)

        val settings = gateway.settings
        binding.gatewayBaseUrl.setText(settings.baseUrl)
        binding.gatewayOwnNumbers.setText(settings.ownNumbers.joinToString("\n"))
        chosenSubscription = settings.subscriptionId
        binding.gatewayOwnNumbers.doAfterTextChanged { showHandlesPreview() }

        binding.gatewaySimHolder.setOnClickListener { pickSim() }
        binding.gatewaySaveHolder.setOnClickListener { save() }
        binding.gatewayEnabledHolder.setOnClickListener { toggleEnabled() }
        binding.gatewaySyncNowHolder.setOnClickListener { syncNow() }
        binding.gatewayTestHeartbeatHolder.setOnClickListener { testHeartbeat() }
        binding.gatewayDefaultSmsHolder.setOnClickListener { requestDefaultSmsRole() }
        binding.gatewayBatteryHolder.setOnClickListener { requestBatteryExemption() }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.gatewayAppbar, NavigationIcon.Arrow)
        updateTextColors(binding.gatewayNestedScrollview)
        arrayOf(binding.gatewayStatusLabel, binding.gatewayConfigLabel, binding.gatewayPhoneLabel).forEach {
            it.setTextColor(getProperPrimaryColor())
        }
        binding.gatewayEnabled.isChecked = gateway.settings.enabled
        showTokenHint()
        showSim()
        showHandlesPreview()
        handler.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
    }

    private fun showTokenHint() {
        binding.gatewayTokenHint.text = gateway.vault.hint()?.let { getString(R.string.gateway_token_saved, it) }
            ?: getString(R.string.gateway_token_none)
    }

    private fun showHandlesPreview() {
        val numbers = binding.gatewayOwnNumbers.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
        val normalizer = HandleNormalizer(gateway.device.simCountry(chosenSubscription))
        val handles = OwnNumbers(numbers, normalizer).handles
        binding.gatewayOwnNumbersPreview.text =
            if (handles.isEmpty()) "" else getString(R.string.gateway_own_numbers_preview, handles.joinToString())
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION") // SubscriptionInfo.number: display only, and still filled in where the platform allows it.
    private fun activeSims(): List<RadioItem> = try {
        getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList.orEmpty().map {
            val number = it.number.orEmpty()
            RadioItem(it.subscriptionId, "SIM ${it.simSlotIndex + 1}: ${it.displayName} $number".trim())
        }
    } catch (_: SecurityException) {
        emptyList()
    }

    private fun showSim() {
        binding.gatewaySim.text = activeSims().firstOrNull { it.id == chosenSubscription }?.title
            ?: getString(R.string.gateway_sim_none)
    }

    private fun pickSim() {
        val sims = ArrayList(activeSims())
        if (sims.isEmpty()) return
        RadioGroupDialog(this, sims, chosenSubscription) {
            chosenSubscription = it as Int
            showSim()
            showHandlesPreview()
        }
    }

    /** Validates and stores the form. Returns false (with a toast) if something is missing or wrong. */
    @Suppress("ReturnCount")
    private fun save(): Boolean {
        val url = binding.gatewayBaseUrl.text.toString().trim()
        val parsed = GatewayApi.parseBaseUrl(url, allowLocalHttp = BuildConfig.DEBUG)
        if (parsed.isFailure) {
            toast(getString(R.string.gateway_error_url, parsed.exceptionOrNull()?.message.orEmpty()))
            return false
        }
        val numbers = binding.gatewayOwnNumbers.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (numbers.isEmpty()) {
            toast(R.string.gateway_error_numbers)
            return false
        }
        if (chosenSubscription == GatewaySettings.NO_SUBSCRIPTION) {
            toast(R.string.gateway_error_sim)
            return false
        }
        val newToken = binding.gatewayToken.text.toString().trim()
        if (newToken.isEmpty() && gateway.vault.get() == null) {
            toast(R.string.gateway_error_token)
            return false
        }

        val settings = gateway.settings
        settings.baseUrl = parsed.getOrThrow().toString()
        settings.ownNumbers = numbers
        settings.subscriptionId = chosenSubscription
        if (newToken.isNotEmpty()) gateway.vault.set(newToken)
        binding.gatewayToken.text?.clear()
        showTokenHint()

        gateway.onConfigChanged()
        if (settings.enabled) GatewayService.start(this, GatewayService.ACTION_CONFIG_CHANGED)
        toast(R.string.gateway_saved)
        return true
    }

    private fun toggleEnabled() {
        if (gateway.settings.enabled) {
            gateway.disable()
            binding.gatewayEnabled.isChecked = false
            return
        }
        if (!save()) return
        ensureBackgroundThread {
            gateway.enable()
            runOnUiThread {
                binding.gatewayEnabled.isChecked = true
                if (!isIgnoringBatteryOptimizations()) requestBatteryExemption()
            }
        }
    }

    private fun syncNow() {
        toast(R.string.gateway_sync_started)
        ensureBackgroundThread {
            gateway.status.clearError()
            gateway.inboundStore.requeueRejected()
            gateway.outboundStore.requeueRejectedConfirmations()
            gateway.requestScanAndUpload()
            gateway.requestDrain()
            gateway.requestFlush()
        }
    }

    private fun testHeartbeat() {
        ensureBackgroundThread {
            val before = gateway.status.lastHeartbeatAt
            gateway.heartbeat(force = true)
            val sent = gateway.status.lastHeartbeatAt != before
            runOnUiThread {
                if (sent) {
                    toast(R.string.gateway_heartbeat_sent)
                } else {
                    toast(getString(R.string.gateway_heartbeat_result, gateway.status.lastError.orEmpty()))
                }
                refreshStatus()
            }
        }
    }

    private fun requestDefaultSmsRole() {
        if (gateway.device.isDefaultSmsApp()) return
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_SMS)
        } else {
            Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
        }
        runCatching { startActivity(intent) }
    }

    private fun isIgnoringBatteryOptimizations() =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        if (isIgnoringBatteryOptimizations()) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri())
        runCatching { startActivity(intent) }
    }

    private fun refreshStatus() {
        binding.gatewayDefaultSms.text =
            getString(if (gateway.device.isDefaultSmsApp()) R.string.gateway_yes else R.string.gateway_no_tap)
        binding.gatewayBattery.text = getString(
            if (isIgnoringBatteryOptimizations()) {
                R.string.gateway_battery_exempt
            } else {
                R.string.gateway_battery_optimised
            }
        )
        ensureBackgroundThread {
            val text = runCatching { statusText() }.getOrElse { it.javaClass.simpleName }
            runOnUiThread { if (!isFinishing) binding.gatewayStatus.text = text }
        }
    }

    private fun statusText(): String {
        val status = gateway.status
        val settings = gateway.settings
        val stats = gateway.queueStats()
        val doorbell = when {
            !settings.enabled -> "off"
            status.tokenRejected -> "token rejected"
            status.longPolling -> "unavailable, polling the outbox"
            status.doorbell == DoorbellState.CONNECTED -> "connected"
            status.doorbell == DoorbellState.TOKEN_REJECTED -> "token rejected"
            status.doorbell == DoorbellState.STOPPED -> "stopped"
            else -> "reconnecting"
        }
        val missing = status.unavailableFeatures().entries.joinToString { (feature, retryAt) ->
            "${feature.name.lowercase()} (retry ${ago(retryAt)})"
        }
        return buildString {
            appendLine("Doorbell: $doorbell")
            appendLine("Last upload: ${ago(status.lastUploadAt)}")
            appendLine("Last send: ${ago(status.lastSendAt)}")
            appendLine("Last heartbeat: ${ago(status.lastHeartbeatAt)}")
            appendLine("Queue: ${stats.inbound} in (oldest ${stats.oldestInboundSeconds}s), ${stats.outbound} out")
            val rejected = gateway.inboundStore.rejectedCount()
            if (rejected > 0) appendLine("Events rejected by the server: $rejected (Sync now retries them)")
            appendLine("MMS download failures: ${status.mmsDownloadFailuresTotal}")
            if (missing.isNotEmpty()) appendLine("Not on the server yet: $missing")
            if (status.serverOutOfDateAt > 0) {
                val since = ago(status.serverOutOfDateAt)
                appendLine("Server out of date: replies came without type/recipients ($since)")
            }
            append("Last error: ${status.lastError?.let { "$it (${ago(status.lastErrorAt)})" } ?: "none"}")
        }
    }

    private fun ago(time: Long): String = if (time <= 0) "never" else
        DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS).toString()

    companion object {
        private const val REFRESH_MILLIS = 2_000L
    }
}
