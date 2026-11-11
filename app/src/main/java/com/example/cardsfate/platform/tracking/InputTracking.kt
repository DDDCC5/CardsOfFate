package com.example.cardsfate.platform.tracking

import android.os.SystemClock
import androidx.annotation.MainThread
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.controller.ControllerTrackingData
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import com.pico.spatial.tracking.eye.EyeTrackingData
import com.pico.spatial.tracking.eye.EyeTrackingProvider
import com.pico.spatial.tracking.hand.HandTrackingData
import com.pico.spatial.tracking.hand.HandTrackingProvider
import com.pico.spatial.tracking.hmd.HMDTrackingData
import com.pico.spatial.tracking.hmd.HMDTrackingProvider

/** A coherent copy of the latest received samples; all receipt times use elapsedRealtime(). */
data class InputSnapshot(
    val sampledAtMs: Long,
    val hands: HandTrackingData?,
    val eyes: EyeTrackingData?,
    val controllers: ControllerTrackingData?,
    val hmd: HMDTrackingData?,
    val handsReceivedAtMs: Long,
    val eyesReceivedAtMs: Long,
    val controllersReceivedAtMs: Long,
    val hmdReceivedAtMs: Long,
    val handsSupport: DataProvider.SupportState,
    val eyesSupport: DataProvider.SupportState,
    val controllersSupport: DataProvider.SupportState,
    val hmdSupport: DataProvider.SupportState,
) {
    private fun fresh(receivedAtMs: Long) =
        receivedAtMs >= 0L && sampledAtMs - receivedAtMs in 0L..MAX_SAMPLE_AGE_MS

    val handsAvailable: Boolean
        get() = handsSupport == DataProvider.SupportState.SUPPORTED &&
            fresh(handsReceivedAtMs) && (hands?.left != null || hands?.right != null)

    /** Explicit eye tracking only. Missing permission/data never substitutes head direction. */
    val gazeAvailable: Boolean
        get() = eyesSupport == DataProvider.SupportState.SUPPORTED &&
            eyes != null && fresh(eyesReceivedAtMs)

    val hmdAvailable: Boolean
        get() = hmdSupport == DataProvider.SupportState.SUPPORTED &&
            hmd != null && fresh(hmdReceivedAtMs)

    /** Pose availability for status display; this does not authorize controller interaction. */
    val controllersAvailable: Boolean
        get() = controllersSupport == DataProvider.SupportState.SUPPORTED &&
            fresh(controllersReceivedAtMs) &&
            (controllers?.left != null || controllers?.right != null)

    companion object {
        const val MAX_SAMPLE_AGE_MS = 250L
    }
}

/**
 * Start on Activity/container resume and stop on pause/disposal. Requires Full Space.
 * Eye permission belongs to the Activity. This adapter observes controllers only: it neither
 * accepts controller actions nor changes the operating system's selected input mode.
 * SDK callbacks only cache immutable samples; main-thread scene code consumes snapshot().
 */
class InputTracking {
    private val handsProvider = HandTrackingProvider()
    private val eyesProvider = EyeTrackingProvider()
    private val controllersProvider = ControllerTrackingProvider()
    private val hmdProvider = HMDTrackingProvider()
    private val lock = Any()
    private var running = false
    private var hands: HandTrackingData? = null
    private var eyes: EyeTrackingData? = null
    private var controllers: ControllerTrackingData? = null
    private var hmd: HMDTrackingData? = null
    private var handsTime = -1L
    private var eyesTime = -1L
    private var controllersTime = -1L
    private var hmdTime = -1L

    private val handsListener = DataProvider.DataListener<HandTrackingData> { data ->
        synchronized(lock) {
            if (running) { hands = data; handsTime = SystemClock.elapsedRealtime() }
        }
    }
    private val eyesListener = DataProvider.DataListener<EyeTrackingData> { data ->
        synchronized(lock) {
            if (running) { eyes = data; eyesTime = SystemClock.elapsedRealtime() }
        }
    }
    private val controllersListener = DataProvider.DataListener<ControllerTrackingData> { data ->
        synchronized(lock) {
            if (running) { controllers = data; controllersTime = SystemClock.elapsedRealtime() }
        }
    }
    private val hmdListener = DataProvider.DataListener<HMDTrackingData> { data ->
        synchronized(lock) {
            if (running) { hmd = data; hmdTime = SystemClock.elapsedRealtime() }
        }
    }

    @MainThread
    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
        }
        try {
            handsProvider.addListener(handsListener)
            eyesProvider.addListener(eyesListener)
            controllersProvider.addListener(controllersListener)
            hmdProvider.addListener(hmdListener)
            // PENDING is supported: providers recover when Full Space/permission/device is ready.
            handsProvider.start()
            eyesProvider.start()
            controllersProvider.start()
            hmdProvider.start()
        } catch (error: Exception) {
            stop()
            throw error
        }
    }

    @MainThread
    fun stop() {
        synchronized(lock) {
            running = false
            hands = null
            eyes = null
            controllers = null
            hmd = null
            handsTime = -1L
            eyesTime = -1L
            controllersTime = -1L
            hmdTime = -1L
        }
        handsProvider.removeListener(handsListener)
        eyesProvider.removeListener(eyesListener)
        controllersProvider.removeListener(controllersListener)
        hmdProvider.removeListener(hmdListener)
        handsProvider.stop()
        eyesProvider.stop()
        controllersProvider.stop()
        hmdProvider.stop()
    }

    @MainThread
    fun snapshot(): InputSnapshot = synchronized(lock) {
        InputSnapshot(
            sampledAtMs = SystemClock.elapsedRealtime(),
            hands = hands,
            eyes = eyes,
            controllers = controllers,
            hmd = hmd,
            handsReceivedAtMs = handsTime,
            eyesReceivedAtMs = eyesTime,
            controllersReceivedAtMs = controllersTime,
            hmdReceivedAtMs = hmdTime,
            handsSupport = handsProvider.supportState,
            eyesSupport = eyesProvider.supportState,
            controllersSupport = controllersProvider.supportState,
            hmdSupport = hmdProvider.supportState,
        )
    }
}
