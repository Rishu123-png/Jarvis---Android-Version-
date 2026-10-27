package com.jarvis.assistant.device

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Steps, distance walked, water and — only if the phone really has one — heart rate.
 *
 * The honesty rules here are stricter than anywhere else in the app, because health numbers are
 * believed:
 *
 *  - Steps come from the hardware step counter, never from an estimate. If there is no sensor,
 *    JARVIS says so instead of inventing a number.
 *  - The counter is cumulative *since boot*, so "today" means "since JARVIS first read the counter
 *    today". That is stated plainly in the health card, and the count restarts at midnight.
 *  - Distance is steps × stride, and is labelled an estimate, because it is one.
 *  - Water is only what the operator logged. Nothing is guessed.
 *  - Sleep is the screen-off gap overnight, labelled an estimate. It is not a sleep study.
 *  - Heart rate needs `BODY_SENSORS` and real hardware; most phones lack it, and the card hides
 *    itself rather than showing a zero.
 */
object HealthLog {

    private const val PREFS = "jarvis_health"
    private const val KEY = "steps"

    fun hasActivityPermission(context: Context): Boolean = ContextCompat.checkSelfPermission(
        context.applicationContext,
        Manifest.permission.ACTIVITY_RECOGNITION,
    ) == PackageManager.PERMISSION_GRANTED

    fun hasBodySensors(context: Context): Boolean = ContextCompat.checkSelfPermission(
        context.applicationContext,
        Manifest.permission.BODY_SENSORS,
    ) == PackageManager.PERMISSION_GRANTED

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    // ---------------------------------------------------------------------------------------------
    // Steps
    // ---------------------------------------------------------------------------------------------

    fun hasStepSensor(context: Context): Boolean = try {
        val manager = context.getSystemService(SensorManager::class.java)
        manager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
    } catch (t: Throwable) {
        false
    }

    /**
     * Steps counted today, or null when the phone cannot count them.
     *
     * Waits briefly for the sensor's first reading: the counter only reports on change, so a cold
     * start needs a couple of seconds of patience rather than a wrong zero.
     */
    suspend fun stepsToday(context: Context): Int? {
        if (!hasActivityPermission(context) || !hasStepSensor(context)) return null

        StepWatch.start(context)
        val total = withTimeoutOrNull(2_500L) {
            while (StepWatch.latest == null) delay(100)
            StepWatch.latest
        } ?: StepWatch.latest ?: return null

        val store = prefs(context)
        val storedDay = store.getString("steps_day", null)
        val baseline = store.getLong("steps_baseline", -1L)
        val day = today()

        val sameDay = storedDay == day && baseline >= 0
        val steps = StepMath.stepsFor(total = total, baseline = baseline, sameDay = sameDay)

        if (!sameDay || total < baseline) {
            // New day, or the phone rebooted and the counter restarted at zero.
            store.edit()
                .putString("steps_day", day)
                .putLong("steps_baseline", total)
                .apply()
            return 0
        }

        store.edit().putLong("steps_baseline", baseline).apply()
        return steps
    }

    /** Distance from steps, labelled an estimate wherever it is shown. */
    fun distanceKm(steps: Int, strideMeters: Double = 0.72): Double =
        StepMath.distanceKm(steps, strideMeters)

    // ---------------------------------------------------------------------------------------------
    // Water
    // ---------------------------------------------------------------------------------------------

    fun waterToday(context: Context): Int {
        val store = prefs(context)
        if (store.getString("water_day", null) != today()) return 0
        return store.getInt("water_ml", 0)
    }

    fun addWater(context: Context, millilitres: Int): Int {
        val store = prefs(context)
        val day = today()
        val current = if (store.getString("water_day", null) == day) store.getInt("water_ml", 0) else 0
        val updated = (current + millilitres).coerceIn(0, 20_000)
        store.edit()
            .putString("water_day", day)
            .putInt("water_ml", updated)
            .apply()
        return updated
    }

    fun waterTargetMl(context: Context): Int = prefs(context).getInt("water_target", 2_500)

    fun setWaterTargetMl(context: Context, millilitres: Int) {
        prefs(context).edit().putInt("water_target", millilitres.coerceIn(500, 8_000)).apply()
    }

    // ---------------------------------------------------------------------------------------------
    // Heart rate
    // ---------------------------------------------------------------------------------------------

    fun hasHeartSensor(context: Context): Boolean = try {
        val manager = context.getSystemService(SensorManager::class.java)
        manager?.getDefaultSensor(Sensor.TYPE_HEART_RATE) != null
    } catch (t: Throwable) {
        false
    }

    /** One reading, or null when the phone has no sensor or does not report in time. */
    suspend fun heartRate(context: Context, timeoutMs: Long = 8_000L): Int? {
        if (!hasHeartSensor(context) || !hasBodySensors(context)) return null
        val manager = context.getSystemService(SensorManager::class.java) ?: return null
        val sensor = manager.getDefaultSensor(Sensor.TYPE_HEART_RATE) ?: return null

        var reading: Int? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val value = event.values.firstOrNull() ?: return
                if (value > 25f && value < 250f) reading = value.roundToInt()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        return try {
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            withTimeoutOrNull(timeoutMs) {
                while (reading == null) delay(150)
                reading
            }
        } catch (t: Throwable) {
            null
        } finally {
            try {
                manager.unregisterListener(listener)
            } catch (t: Throwable) {
                Unit
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Sleep estimate
    // ---------------------------------------------------------------------------------------------

    /** Minutes of continuous screen-off during last night, or null when there is no data yet. */
    fun sleepLastNight(context: Context): Int? {
        val store = prefs(context)
        val minutes = store.getInt("sleep_minutes", -1)
        val stamped = store.getLong("sleep_at", 0L)
        if (minutes < 0) return null
        // Older than two days is stale and would be a lie to repeat.
        if (System.currentTimeMillis() - stamped > 48 * 60 * 60 * 1000L) return null
        return minutes
    }
}

/**
 * The one number in health that can be computed without asking anyone: how long the screen stayed
 * off overnight.
 */
object SleepWatch {

    private const val PREFS = "jarvis_health"
    private const val KEY_OFF = "screen_off_at"
    private const val KEY_ON = "screen_on_at"

    fun onScreenOff(context: Context, atMillis: Long = System.currentTimeMillis()) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_OFF, atMillis).apply()
    }

    fun onScreenOn(context: Context, atMillis: Long = System.currentTimeMillis()) {
        val store = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val off = store.getLong(KEY_OFF, 0L)
        if (off <= 0L) return
        if (!Sleep.isOvernight(off, atMillis)) return

        val minutes = Sleep.estimateMinutes(off, atMillis)
        if (minutes >= 120) {
            store.edit()
                .putInt("sleep_minutes", minutes)
                .putLong("sleep_at", atMillis)
                .apply()
        }
    }
}

/** Pure maths for both, so CI can test them without a phone. */
object StepMath {

    /** Steps so far today given the sensor's since-boot total and the stored baseline. */
    fun stepsFor(total: Long, baseline: Long, sameDay: Boolean): Int {
        if (baseline < 0L) return 0
        if (!sameDay) return 0
        return (total - baseline).coerceAtLeast(0L).toInt()
    }

    fun distanceKm(steps: Int, strideMeters: Double): Double {
        val safeStride = if (strideMeters in 0.3..1.2) strideMeters else 0.72
        return steps.coerceAtLeast(0) * safeStride / 1000.0
    }
}

object Sleep {

    /** True when the gap spans a night rather than an afternoon nap or a reboot. */
    fun isOvernight(offAt: Long, onAt: Long): Boolean {
        if (onAt <= offAt) return false
        val hourOff = hourOf(offAt)
        return hourOff >= 20 || hourOff <= 4
    }

    /** Minutes between screen-off and screen-on, capped so a forgotten phone is not "12 hours". */
    fun estimateMinutes(offAt: Long, onAt: Long): Int {
        val minutes = ((onAt - offAt) / 60_000L).toInt()
        return minutes.coerceIn(0, 14 * 60)
    }

    private fun hourOf(millis: Long): Int = try {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = millis
        calendar.get(java.util.Calendar.HOUR_OF_DAY)
    } catch (t: Throwable) {
        12
    }
}

/** Keeps the screen-off / screen-on transitions the sleep estimate needs. */
class ScreenWatchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val app = context?.applicationContext ?: return
        when (intent?.action) {
            Intent.ACTION_SCREEN_OFF -> SleepWatch.onScreenOff(app)
            Intent.ACTION_SCREEN_ON -> SleepWatch.onScreenOn(app)
            else -> Unit
        }
    }
}

/**
 * Holds the step counter's latest value while the process is alive.
 *
 * The sensor reports only when the count changes, so one reading is cached and reused; the listener
 * is left registered for the life of the process because this is the cheapest way to keep "steps
 * today" accurate without a foreground service.
 */
internal object StepWatch {

    var latest: Long? = null
        private set

    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        try {
            val manager = context.applicationContext
                .getSystemService(SensorManager::class.java) ?: return
            val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    latest = event.values.firstOrNull()?.toLong()
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        } catch (t: Throwable) {
            started = false
        }
    }
}
