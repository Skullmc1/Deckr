package app.deckr

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Motion aiming, like gyro aim on Switch, PlayStation and Steam Input.
 *
 * Reads the gyroscope and turns how fast the phone rotates into aim: either mouse movement
 * (precise, like Steam's gyro-to-mouse) or right-stick deflection (for games that only read the
 * controller). Yaw is worked out in "player space" using gravity, so turning left and right feels
 * the same however upright or flat the phone is held.
 *
 * [context] must be the activity, so the screen rotation can be read.
 */
class GyroAim(private val context: Context, private val vm: DeckrViewModel) : SensorEventListener {

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val gyro: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gravitySensor: Sensor? =
        sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    val available: Boolean get() = gyro != null

    private var thread: HandlerThread? = null

    /** Unit vector pointing up, in the phone's own axes. */
    private val up = floatArrayOf(0f, 1f, 0f)
    private var lastNs = 0L
    private var rotation = Surface.ROTATION_90
    private var rotationCheckedNs = 0L
    private var stickX = 0f
    private var stickY = 0f

    fun start() {
        val sm = sensors ?: return
        if (gyro == null || thread != null) return
        val t = HandlerThread("deckr-gyro").also { it.start() }
        thread = t
        val handler = Handler(t.looper)
        lastNs = 0L
        sm.registerListener(this, gyro, SensorManager.SENSOR_DELAY_FASTEST, handler)
        gravitySensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler) }
    }

    fun stop() {
        sensors?.unregisterListener(this)
        thread?.quitSafely()
        thread = null
        stickX = 0f
        stickY = 0f
        vm.setGyroStick(0f, 0f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> {
                val raw = e.values
                val k = if (e.sensor.type == Sensor.TYPE_ACCELEROMETER) 0.1f else 1f // smooth raw accelerometer
                for (i in 0..2) up[i] += (raw[i] - up[i]) * k
            }
            Sensor.TYPE_GYROSCOPE -> onGyro(e)
        }
    }

    private fun onGyro(e: SensorEvent) {
        val dt = if (lastNs == 0L) 0f else (e.timestamp - lastNs) / 1e9f
        lastNs = e.timestamp
        if (dt <= 0f || dt > 0.1f) return

        if (!vm.gyroActive()) {
            if (stickX != 0f || stickY != 0f) {
                stickX = 0f
                stickY = 0f
                vm.setGyroStick(0f, 0f)
            }
            return
        }

        if (e.timestamp - rotationCheckedNs > 500_000_000L) {
            rotationCheckedNs = e.timestamp
            @Suppress("DEPRECATION")
            rotation = context.getSystemService(WindowManager::class.java)?.defaultDisplay?.rotation ?: rotation
        }

        // Rotation rates (rad/s) about the screen's right, up and towards-you axes.
        val (gx, gy, gz) = toScreen(e.values[0], e.values[1], e.values[2])
        val (ux, uy, uz) = toScreen(up[0], up[1], up[2])
        val n = sqrt(ux * ux + uy * uy + uz * uz).takeIf { it > 0.01f } ?: return

        // Player-space yaw (Jibb Smart): turning about the real-world vertical, with a little
        // slack so leaning the phone still turns you.
        val worldYaw = (gy * uy + gz * uz) / n
        val yaw = sign(worldYaw) * min(abs(worldYaw) * 1.41f, sqrt(gy * gy + gz * gz))
        val pitch = gx

        // Degrees per second; right and down are positive, like a mouse.
        var ax = -yaw * RAD_TO_DEG
        var ay = -pitch * RAD_TO_DEG
        if (vm.gyroInvertY) ay = -ay

        // Soft tightening: very slow movement (hand tremor) is scaled down so the aim stays still.
        val speed = hypot(ax, ay)
        if (speed < TIGHTEN_DEG_S) {
            val k = speed / TIGHTEN_DEG_S
            ax *= k
            ay *= k
        }

        val sens = vm.gyroSensitivity
        if (vm.gyroToStick) {
            // Stick deflection follows turn speed. Small turns still push past the game's own
            // stick dead zone, so slow aiming isn't swallowed.
            val full = FULL_STICK_DEG_S / sens
            val m = hypot(ax, ay) / full
            var sx = 0f
            var sy = 0f
            if (m > 0.002f) {
                val out = STICK_FLOOR + (1f - STICK_FLOOR) * min(m, 1f)
                sx = ax / full / m * out
                sy = ay / full / m * out
            }
            stickX += (sx - stickX) * 0.5f
            stickY += (sy - stickY) * 0.5f
            vm.setGyroStick(stickX, stickY)
        } else {
            val px = PIXELS_PER_DEGREE * sens * dt
            vm.link.move(ax * px, ay * px)
        }
    }

    /** Phone axes to screen axes for the current screen rotation. */
    private fun toScreen(x: Float, y: Float, z: Float): Triple<Float, Float, Float> = when (rotation) {
        Surface.ROTATION_90 -> Triple(-y, x, z)
        Surface.ROTATION_270 -> Triple(y, -x, z)
        Surface.ROTATION_180 -> Triple(-x, -y, z)
        else -> Triple(x, y, z)
    }

    private companion object {
        const val RAD_TO_DEG = 57.29578f
        const val TIGHTEN_DEG_S = 2.5f
        /** Mouse pixels per degree of turn at sensitivity 1. */
        const val PIXELS_PER_DEGREE = 14f
        /** Turn speed for full stick at sensitivity 1. */
        const val FULL_STICK_DEG_S = 240f
        /** Smallest stick deflection while turning at all, to get past in-game dead zones. */
        const val STICK_FLOOR = 0.16f
    }
}
