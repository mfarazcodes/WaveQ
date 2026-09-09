package com.waveq.app.sensor

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val PREFS_FILE = "waveq_sensor_settings"
private const val KEY_ENABLED = "enabled"
private const val KEY_HOST = "host"
private const val KEY_TRIGGER_PERCENT = "trigger_percent"
private const val KEY_CLEAR_PERCENT = "clear_percent"
private const val KEY_CONSECUTIVE = "consecutive_required"
private const val KEY_POLL_SECONDS = "poll_seconds"

/**
 * Defaults calibrated against the real probe.
 *
 * Measured behaviour across repeated dips is 0-1% in air and 96-100% submerged,
 * with no intermediate values ever observed: it is a threshold detector at a
 * fixed height, not a level gauge. The thresholds are therefore set wide of both
 * ends rather than tuned to a midpoint, and the gap between trigger and clear is
 * the latch band.
 */
const val DEFAULT_TRIGGER_PERCENT = 50
const val DEFAULT_CLEAR_PERCENT = 20
const val DEFAULT_CONSECUTIVE_REQUIRED = 2
const val DEFAULT_POLL_SECONDS = 2

/**
 * Configuration for the physical water-level probe.
 *
 * Plain SharedPreferences behind StateFlows, matching
 * [com.waveq.app.alerts.AlertSettings]: [WaterLevelMonitor] polls from a
 * process-scoped coroutine with no ViewModel in scope, and the poll loop needs a
 * synchronous read of the current host and thresholds on every tick.
 *
 * The host is deliberately user-configurable and has no default. The dev unit is
 * DHCP-assigned and changes on reboot; a hardcoded address would work exactly
 * once.
 */
object SensorSettings {

    @Volatile private var prefs: android.content.SharedPreferences? = null

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    private val _host = MutableStateFlow("")
    /** Bare host or host:port, e.g. `10.185.203.84`. Empty means unconfigured. */
    val host: StateFlow<String> = _host

    private val _triggerPercent = MutableStateFlow(DEFAULT_TRIGGER_PERCENT)
    val triggerPercent: StateFlow<Int> = _triggerPercent

    private val _clearPercent = MutableStateFlow(DEFAULT_CLEAR_PERCENT)
    val clearPercent: StateFlow<Int> = _clearPercent

    private val _consecutiveRequired = MutableStateFlow(DEFAULT_CONSECUTIVE_REQUIRED)
    val consecutiveRequired: StateFlow<Int> = _consecutiveRequired

    private val _pollSeconds = MutableStateFlow(DEFAULT_POLL_SECONDS)
    val pollSeconds: StateFlow<Int> = _pollSeconds

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val store = context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        prefs = store
        _enabled.value = store.getBoolean(KEY_ENABLED, false)
        _host.value = store.getString(KEY_HOST, "").orEmpty()
        _triggerPercent.value = store.getInt(KEY_TRIGGER_PERCENT, DEFAULT_TRIGGER_PERCENT)
        _clearPercent.value = store.getInt(KEY_CLEAR_PERCENT, DEFAULT_CLEAR_PERCENT)
        _consecutiveRequired.value = store.getInt(KEY_CONSECUTIVE, DEFAULT_CONSECUTIVE_REQUIRED)
        _pollSeconds.value = store.getInt(KEY_POLL_SECONDS, DEFAULT_POLL_SECONDS)
    }

    fun setEnabled(context: Context, value: Boolean) {
        init(context)
        _enabled.value = value
        prefs?.edit()?.putBoolean(KEY_ENABLED, value)?.apply()
    }

    fun setHost(context: Context, value: String) {
        init(context)
        val cleaned = value.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        _host.value = cleaned
        prefs?.edit()?.putString(KEY_HOST, cleaned)?.apply()
    }

    /**
     * Trigger must stay strictly above clear, or the latch band collapses and a
     * reading hovering on the line would fire repeatedly.
     */
    fun setThresholds(context: Context, trigger: Int, clear: Int) {
        init(context)
        val safeTrigger = trigger.coerceIn(1, 100)
        val safeClear = clear.coerceIn(0, safeTrigger - 1)
        _triggerPercent.value = safeTrigger
        _clearPercent.value = safeClear
        prefs?.edit()
            ?.putInt(KEY_TRIGGER_PERCENT, safeTrigger)
            ?.putInt(KEY_CLEAR_PERCENT, safeClear)
            ?.apply()
    }

    fun setConsecutiveRequired(context: Context, value: Int) {
        init(context)
        val safe = value.coerceIn(1, 10)
        _consecutiveRequired.value = safe
        prefs?.edit()?.putInt(KEY_CONSECUTIVE, safe)?.apply()
    }

    fun setPollSeconds(context: Context, value: Int) {
        init(context)
        val safe = value.coerceIn(1, 300)
        _pollSeconds.value = safe
        prefs?.edit()?.putInt(KEY_POLL_SECONDS, safe)?.apply()
    }

    /** True when the monitor has everything it needs to start polling. */
    fun isConfigured(): Boolean = _enabled.value && _host.value.isNotBlank()
}
