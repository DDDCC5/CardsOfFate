package com.example.cardsfate.platform.spatial

import android.util.Log
import com.example.cardsfate.domain.model.*
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.lifecycle.Cancellable
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.sense.base.AnchorUpdate
import com.pico.spatial.sense.base.TrackingState
import com.pico.spatial.sense.plane.PlaneAnchor
import com.pico.spatial.sense.plane.PlaneTrackingManager
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Conservative Stage-space AABB of all vertices of a system plane, measured in meters. */
data class PlaneObstacle(val anchorId: UUID, val minimum: Vector3, val maximum: Vector3, val mesh: PlaneTriangles)

data class PlaneSpaceSnapshot(
    val obstacles: List<PlaneObstacle>,
    val systemAnchorCount: Int,
    val running: Boolean,
    val error: String?,
    val revision: Long = 0L,
) {
    val ready: Boolean get() = running && error == null && obstacles.isNotEmpty()

    /** Conservative box for non-card props and automated rotation envelopes. */
    fun isSafe(position: Vector3, radius: Float): Boolean =
        isCardSafe(position, Quat.identity(), Vector3(radius,radius,radius))

    fun isCardSafe(position: Vector3, rotation: Quat, half: Vector3, center: Vector3 = Vector3.ZERO): Boolean {
        if (!ready) return false
        fun point(v: Vector3) = SpacePoint(v.x.toDouble(),v.y.toDouble(),v.z.toDouble())
        val box=SpaceBox(point(position+rotation.rotateVector(center)),
            listOf(Vector3.RIGHT,Vector3.UP,Vector3(0f,0f,1f)).map { point(rotation.rotateVector(it)) },point(half))
        return obstacles.all { it.mesh.isBoxClear(box,.025) }
    }

}

/**
 * Consumes existing system plane tracking in Full Space; never opens a scanning UI or mutates entities.
 * One owner per application: PlaneTrackingManager is a process-wide singleton.
 * Call stop before Stage disposal; a cancelled start scope also stops tracking.
 * Conservative bounds may reject empty space around slanted/irregular planes. No occlusion is installed.
 */
class PlaneSpace {
    private val lock = Any()
    private var subscription: Cancellable? = null
    private var job: Job? = null
    private var active = false
    private var generation = 0L
    private var revision = 0L
    private var loadError: String? = null
    private val geometry = linkedMapOf<UUID, PlaneObstacle?>()
    private val geometryErrors = linkedMapOf<UUID, String>()
    private val touchedSinceStart = mutableSetOf<UUID>()

    fun start(scope: CoroutineScope) {
        val token = synchronized(lock) {
            if (active) return
            active = true
            generation++
            revision++
            loadError = null
            geometry.clear()
            geometryErrors.clear()
            touchedSinceStart.clear()
            generation
        }
        try {
            synchronized(lock) {
                if (!active || generation != token) return
                subscription = PlaneTrackingManager.subscribeAnchorUpdate { update ->
                    synchronized(lock) {
                        if (!active || generation != token) return@subscribeAnchorUpdate
                        val id = update.anchor.anchorUUID
                        touchedSinceStart.add(id)
                        when (update.event) {
                            AnchorUpdate.Event.REMOVED -> { geometry.remove(id); geometryErrors.remove(id); revision++ }
                            AnchorUpdate.Event.ADDED, AnchorUpdate.Event.UPDATED, AnchorUpdate.Event.LOADED -> cache(update.anchor)
                            else -> Unit
                        }
                    }
                }
                PlaneTrackingManager.start()
                Log.i("FatePlanes", "start state=${PlaneTrackingManager.state}")
            }
            val loading = scope.launch {
                try {
                    // Native tracking may start asynchronously. Never treat an early empty load as ready.
                    var attempts = 0
                    while (PlaneTrackingManager.state != TrackingState.RUNNING && attempts++ < 50) delay(100)
                    check(PlaneTrackingManager.state == TrackingState.RUNNING) { "System plane tracking is unavailable" }
                    val anchors = PlaneTrackingManager.loadAllAnchors()
                    Log.i("FatePlanes", "loaded=${anchors.size} state=${PlaneTrackingManager.state}")
                    synchronized(lock) {
                        if (active && generation == token) {
                            anchors.forEach { if (it.anchorUUID !in touchedSinceStart) cache(it) }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Log.e("FatePlanes", "Initial system planes unavailable state=${PlaneTrackingManager.state}", failure)
                    synchronized(lock) { if (active && generation == token) loadError = failure.message ?: failure.javaClass.simpleName }
                }
            }
            synchronized(lock) {
                if (active && generation == token) job = loading else loading.cancel()
            }
            // Remains attached to the supplied scope for its lifetime, not just until the initial load.
            scope.coroutineContext[Job]?.invokeOnCompletion { stopGeneration(token) }
        } catch (failure: Exception) {
            stopGeneration(token)
            synchronized(lock) { if (generation == token + 1) loadError = failure.message ?: failure.javaClass.simpleName }
        }
    }

    fun stop() = stopGeneration(null)

    private fun stopGeneration(expected: Long?) {
        synchronized(lock) {
            if (!active || (expected != null && generation != expected)) return
            active = false
            generation++ // Invalidates native callbacks and any suspended initial load.
            job?.cancel()
            job = null
            subscription?.cancel()
            subscription = null
            try { PlaneTrackingManager.stop() } catch (failure: Exception) {
                loadError = failure.message ?: failure.javaClass.simpleName
            }
            geometry.clear()
            geometryErrors.clear()
            touchedSinceStart.clear()
        }
    }

    fun snapshot(): PlaneSpaceSnapshot = synchronized(lock) {
        val running = active && try { PlaneTrackingManager.state == TrackingState.RUNNING } catch (_: Exception) { false }
        PlaneSpaceSnapshot(geometry.values.filterNotNull(), geometry.size, running,
            loadError ?: geometryErrors.values.firstOrNull() ?: if (active && !running) "System plane tracking is not running" else null, revision)
    }

    fun isSafe(position: Vector3, radius: Float): Boolean = snapshot().isSafe(position, radius)

    /** Invoked under lock; stores only copied immutable bounds, never native anchor references. */
    private fun cache(anchor: PlaneAnchor) {
        val id = anchor.anchorUUID
        revision++
        try {
            val local = anchor.vertices.toList()
            val indices = anchor.indices.toList()
            require(local.size >= 3 && indices.size >= 3 && indices.size % 3 == 0 && indices.all { it in local.indices }) {
                "System plane has invalid triangle geometry"
            }
            val transform = anchor.transform
            val world = local.map { transform.quaternion.rotateVector(it * transform.scale) + transform.position }
            require(world.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() }) { "System plane has nonfinite geometry" }
            val minimum = Vector3(world.minOf { it.x }, world.minOf { it.y }, world.minOf { it.z })
            val maximum = Vector3(world.maxOf { it.x }, world.maxOf { it.y }, world.maxOf { it.z })
            val extents = listOf(maximum.x - minimum.x, maximum.y - minimum.y, maximum.z - minimum.z)
            require(extents.count { it > 0.001f } >= 2) { "System plane is degenerate" }
            val mesh=PlaneTriangles(world.map { SpacePoint(it.x.toDouble(),it.y.toDouble(),it.z.toDouble()) },indices)
            require(mesh.valid) { "System plane has no usable triangles" }
            geometry[id] = PlaneObstacle(id, minimum, maximum, mesh)
            geometryErrors.remove(id)
            loadError = null
        } catch (failure: Exception) {
            Log.w("FatePlanes", "Invalid plane geometry id=$id", failure)
            geometry[id] = null
            geometryErrors[id] = failure.message ?: "Invalid system plane geometry"
        }
    }
}
