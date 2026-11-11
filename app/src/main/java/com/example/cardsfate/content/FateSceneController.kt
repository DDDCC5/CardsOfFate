package com.example.cardsfate.content

import android.content.Context
import android.util.Log
import com.example.cardsfate.domain.model.*
import com.example.cardsfate.platform.spatial.PlaneSpace
import com.example.cardsfate.platform.tracking.*
import com.example.cardsfate.ui.ritual.FateViewModel
import com.pico.spatial.core.ecs.*
import com.pico.spatial.core.ecs.anchor.AnchorTarget
import com.pico.spatial.core.ecs.resource.*
import com.pico.spatial.core.ecs.simulation.RigidBodyMode
import com.pico.spatial.core.math.*
import com.pico.spatial.tracking.hand.HandJoint.Index
import com.pico.spatial.tracking.hand.HandPose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlin.math.*

/** Main-thread scene owner. Neither sensor callbacks nor Compose recomposition drive entities. */
class FateSceneController(
    private val context: Context,
    private val vm: FateViewModel,
    private val tracking: InputTracking,
    private val space: PlaneSpace,
    private val scope: CoroutineScope,
) {
    val root = Entity()
    // Startup/error panels remain head-relative before any model or HMD sample is ready.
    private val startupAnchor = AnchorEntity(AnchorTarget.createCameraTarget()).also { root.addChild(it) }
    private data class Card(val id: String, val owner: Entity, val face: Entity,
                            var position: Vector3 = Vector3.ZERO, var rotation: Quat = Quat.identity())
    private data class Held(val card: Card, val left: Boolean, val palm: Vector3,
                            val rotation: Quat, val cardPosition: Vector3, val cardRotation: Quat,
                            val touchOnly: Boolean = false)
    private data class Pivot(val entity: Entity, val base: Quat, val axis: Vector3, val speed: Float, val continuous: Boolean)
    private val cards = linkedMapOf<String, Card>()
    private val decorations = mutableListOf<Entity>()
    private val pivots = mutableListOf<Pivot>()
    private val panels = mutableMapOf<String, Entity>()
    private val resources = mutableListOf<AutoCloseable>()
    private var classifier = HandRitualClassifier()
    private var diagnosticAt = 0L
    private var lastDiagnostic: String? = null
    private var loaded = false
    private var disposed = false
    private var initialHead: Vector3? = null
    private var yaw = Quat.identity()
    private var orbitRadius: Float? = null
    private var phase: RitualPhase? = null
    private var phaseElapsed = 0f
    private var previousFrame = 0L
    private var ritualSeconds = 0f
    private var held: Held? = null
    private var previousLeft: Vector3? = null
    private var previousRight: Vector3? = null
    private var swept = 0f
    private var unsafe = false
    private var controllerLastPosition: Vector3? = null
    private var controllerLastLeft: Vector3? = null
    private var controllerSampleTime = -1L
    private var controllerWindowStart = -1L
    private var controllerBaseline: Vector3? = null
    private var controllerBaselineLeft: Vector3? = null
    private var controllerRotation: Quat? = null
    private var controllerRotationLeft: Quat? = null
    private var controllerMovedAt = -10000L
    private var lastHandsTime = -1L
    private var tint: Entity? = null
    private var gazeFrame: Entity? = null
    private var lastShuffleSoundMs = -10000L
    private var transitionPositions = emptyMap<String, Vector3>()
    private var transitionRotations = emptyMap<String, Quat>()

    suspend fun load() {
        check(!disposed)
        if (loaded) return
        try {
            root.components.set(PhysicsWorldComponent())
            val language = if (vm.state.value.language == ReadingLanguage.ZH) "zh" else "en"
            val deck = Entity.loadSuspend("asset://models/deck_$language.glb")
            deck.enabled = false
            root.addChild(deck)
            vm.state.value.catalog.forEach { item ->
                val suffix = "${language}_${item.id.replace('-', '_')}"
                val imported = requireNotNull(deck.findEntity("Card_$suffix")) { "Missing approved card $suffix" }
                val face = requireNotNull(imported.findEntity("CardFace_$suffix")) { "Missing face $suffix" }
                face.enabled = false // Hide before attaching the extracted card to a live scene.
                imported.removeFromParent()
                imported.components.get<TransformComponent>()!!.position = Vector3.ZERO
                val owner = Entity()
                owner.enabled = false
                owner.addChild(imported) // Preserve the imported +90 degree X quaternion.
                root.addChild(owner)
                val bounds = owner.getVisualBounds(owner, recursive = true, enabledOnly = false)
                val size = bounds.size
                require(size.x > 0f && size.y > 0f && size.z > 0f) { "Invalid card bounds $suffix" }
                val shape = ShapeResource.createBox(size)
                val physics = PhysicsMaterialResource()
                resources += shape
                resources += physics
                owner.components.set(CollisionComponent(listOf(shape), physics))
                owner.components.set(RigidBodyComponent().apply {
                    rigidBodyMode = RigidBodyMode.KINEMATIC
                    isAffectedByGravity = false
                })
                cards[item.id] = Card(item.id, owner, face)
            }
            require(cards.size == 78) { "Approved deck must contain 78 distinct cards" }
            deck.destroy()
            (listOf("candle", "candle", "crystal", "astrolabe") + (0 until 24).map { "rune_${it % 4}" }).forEach { name ->
                val entity = Entity.loadSuspend("asset://models/$name.glb")
                entity.enabled = false
                root.addChild(entity)
                decorations += entity
                val targets = when (name) {
                    "candle" -> listOf("FlamePivot")
                    "crystal" -> listOf("CrystalOrbitPivot")
                    "astrolabe" -> listOf("AstrolabeRingPivot_0", "AstrolabeRingPivot_1", "AstrolabeRingPivot_2", "CompassPivot")
                    else -> emptyList()
                }
                targets.forEachIndexed { index, target ->
                    val pivot = requireNotNull(entity.findEntity(target)) { "Missing approved pivot $target" }
                    pivots += Pivot(pivot, pivot.components.get<TransformComponent>()!!.quaternion,
                        if (index % 2 == 0) Vector3.UP else Vector3.RIGHT, if(name == "candle") 2.3f else .12f + index * .06f, name != "candle")
                }
            }
            val mesh = MeshResource.createSphere(20f)
            val material = UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                setBaseColor(Color4(0f, 0f, 0f, .35f))
                setCullingMode(MaterialCullingMode.FRONT)
                setDepthTest(true)
                setDepthWrite(false)
            }
            resources += mesh
            resources += material
            tint = Entity().apply { enabled = false; components.set(ModelComponent(mesh, material)); root.addChild(this) }
            val gold = UnlitMaterial.create().apply {
                setBaseColor(Color4(.95f, .69f, .25f, 1f))
                setDepthTest(true)
                setDepthWrite(true)
            }
            val horizontal = MeshResource.createBox(Vector3(.104f, .0018f, .001f))
            val vertical = MeshResource.createBox(Vector3(.0018f, .174f, .001f))
            resources += gold
            resources += horizontal
            resources += vertical
            gazeFrame = Entity().apply {
                enabled = false
                root.addChild(this)
                listOf(Vector3(0f, -.087f, -.0015f), Vector3(0f, .087f, -.0015f),
                    Vector3(-.052f, 0f, -.0015f), Vector3(.052f, 0f, -.0015f)).forEachIndexed { index, offset ->
                    addChild(Entity().apply {
                        components.set(ModelComponent(if (index < 2) horizontal else vertical, gold))
                        components.get<TransformComponent>()!!.position = offset
                    })
                }
            }
            loaded = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.e(TAG, "Approved scene loading failed", failure)
            loaded = false
            vm.setRuntimeStatus(false, false, false, false, false)
            throw failure
        }
    }

    /** IDs accepted by the Compose host: main, history, reading, status. */
    fun setPanelEntity(id: String, entity: Entity) {
        require(id in setOf("main", "history", "reading", "status"))
        panels[id] = entity
        if (initialHead == null) startupAnchor.addChild(entity) else root.addChild(entity)
        updatePanels()
    }

    fun retrySpace() { orbitRadius = null; unsafe = false }

    fun frame(frameTimeNanos: Long) {
        if (disposed) return
        gazeFrame?.enabled = false
        val input = tracking.snapshot()
        val dt = if (previousFrame == 0L) 0f else ((frameTimeNanos - previousFrame) / 1e9f).coerceIn(0f, .05f)
        previousFrame = frameTimeNanos
        if (initialHead == null && input.hmdAvailable) {
            val pose = input.hmd!!.hmdPose
            initialHead = pose.position
            val forward = pose.rotation.rotateVector(Vector3(0f, 0f, -1f))
            if (forward.x * forward.x + forward.z * forward.z > .0001f)
                yaw = Quat(Vector3.UP, atan2(-forward.x, -forward.z))
            panels.values.forEach { it.removeFromParent(); root.addChild(it) }
        }
        updatePanels()
        val head = initialHead
        if (head == null) {
            vm.setRuntimeStatus(loaded, false, input.handsAvailable, false, input.gazeAvailable)
            return
        }
        if (!loaded) {
            vm.setRuntimeStatus(false, false, input.handsAvailable, false, input.gazeAvailable)
            return
        }
        val environment = space.snapshot()
        if (orbitRadius == null && environment.ready) {
            orbitRadius = listOf(2.1f, 1.8f, 1.5f, 1.35f).firstOrNull { radius ->
                (0 until 78).all { i -> environment.isSafe(orbit(i, radius, 0f), CARD_RADIUS) } &&
                    // Check the entire fan and all three reading slots, not just the endpoints.
                    (0 until 78).all { i -> environment.isSafe(
                        local(-.48f + i * .96f / 77f, -.30f, -.70f - i * .0009f), CARD_RADIUS + .035f) } &&
                    (-1..1).all { i -> environment.isSafe(local(i * .26f, -.12f, -1.1f), CARD_RADIUS) }
            }
        }
        val controller = input.controllers?.right
        val leftController = input.controllers?.left
        if (input.controllersAvailable && input.controllersReceivedAtMs != controllerSampleTime) {
            fun moved(position: Vector3?, baseline: Vector3?, rotation: Quat?, oldRotation: Quat?): Boolean {
                if (position == null || baseline == null) return false
                val translated = (position - baseline).length() > .015f
                val rotated = if (rotation != null && oldRotation != null)
                    (rotation.rotateVector(Vector3(0f, 0f, -1f)) - oldRotation.rotateVector(Vector3(0f, 0f, -1f))).length() > .08f else false
                return translated || rotated
            }
            if (moved(controller?.position, controllerBaseline, controller?.rotation, controllerRotation) ||
                moved(leftController?.position, controllerBaselineLeft, leftController?.rotation, controllerRotationLeft))
                controllerMovedAt = input.sampledAtMs
            // A bounded baseline catches deliberate slow movement without per-sample threshold loss.
            if (controllerWindowStart < 0L || input.sampledAtMs - controllerWindowStart > 500L) {
                controllerBaseline = controller?.position
                controllerBaselineLeft = leftController?.position
                controllerRotation = controller?.rotation
                controllerRotationLeft = leftController?.rotation
                controllerWindowStart = input.sampledAtMs
            }
            controllerSampleTime = input.controllersReceivedAtMs
        }
        val controllerActive = input.sampledAtMs - controllerMovedAt < 1000L || (input.controllersAvailable && !input.handsAvailable)
        val safe = orbitRadius != null && environment.ready && !unsafe
        val diagnostic = "phase=${vm.state.value.ritual.phase} scene=$loaded space=$safe planes=${environment.systemAnchorCount} hands=${input.handsAvailable}/${input.handsSupport} gaze=${input.gazeAvailable}/${input.eyesSupport} controllers=$controllerActive"
        if (diagnostic != lastDiagnostic && input.sampledAtMs - diagnosticAt > 2000L) {
            Log.i(TAG, diagnostic)
            diagnosticAt = input.sampledAtMs
            lastDiagnostic = diagnostic
        }
        vm.setRuntimeStatus(loaded, safe, input.handsAvailable, controllerActive, input.gazeAvailable)
        updatePanels()
        tint?.components?.get<TransformComponent>()?.position = input.hmd?.hmdPose?.position ?: head
        tint?.enabled = safe
        if (!safe) {
            cards.values.forEach { it.owner.enabled = false }
            decorations.forEach { it.enabled = false }
            return
        }
        val ui = vm.state.value
        val state = ui.ritual
        if (phase != state.phase) {
            // Process-owned ritual survives a Stage/Activity recreation; Entity handles do not.
            if (phase == null) {
                if (state.phase in setOf(RitualPhase.READING, RitualPhase.SUMMARY)) {
                    state.selectedCards.forEachIndexed { index, drawn ->
                        place(cards.getValue(drawn.cardId), local((index - 1) * .26f, -.12f, -1.1f),
                            yaw * (if (index < state.revealedCount) Quat.identity() else Quat(Vector3.UP, PI.toFloat())) *
                                Quat(Vector3(0f, 0f, 1f), if (drawn.reversed) PI.toFloat() else 0f))
                    }
                } else if (state.phase == RitualPhase.SPREAD) {
                    state.selectedCards.forEachIndexed { index, drawn ->
                        place(cards.getValue(drawn.cardId), local((index-1)*.18f, -.30f, -.70f), yaw * Quat(Vector3.UP, PI.toFloat()))
                    }
                } else if (state.phase in setOf(RitualPhase.SHUFFLE, RitualPhase.GATHER)) {
                    cards.values.forEachIndexed { index, card ->
                        val angle = index * 2.399963f
                        place(card, local(cos(angle) * .23f, -.35f + (index % 5) * .002f, -.65f + sin(angle) * .23f),
                            yaw * Quat(Vector3.RIGHT, PI.toFloat() / 2f))
                    }
                }
            }
            phase = state.phase
            phaseElapsed = 0f
            transitionPositions = cards.mapValues { it.value.position }
            transitionRotations = cards.mapValues { it.value.rotation }
            held = null
            if (phase == RitualPhase.SHUFFLE) swept = 0f
            if (phase == RitualPhase.SYSTEMS) { classifier = HandRitualClassifier(); lastHandsTime = -1L }
        }
        if (ui.historyOpen) {
            cards.values.forEach { it.owner.enabled = false }
            decorations.forEach { it.enabled = false }
            if (input.handsAvailable && input.handsReceivedAtMs != lastHandsTime) {
                lastHandsTime = input.handsReceivedAtMs
                val event = classifier.update(skeleton(input.hands?.left), skeleton(input.hands?.right), lastHandsTime)
                if (event.historyRequested) vm.toggleHistory()
            }
            held = null
            previousLeft = null
            previousRight = null
            return
        }
        if (state.suspension != null) {
            updateGazeFrame(input)
            classifier.update(null, null, input.sampledAtMs)
            held = null
            previousLeft = null
            previousRight = null
            return
        }
        ritualSeconds += dt
        phaseElapsed += dt
        val elapsed = phaseElapsed
        val deckOrder = if (state.deck.isEmpty()) cards.keys.toList() else state.deck.map { it.cardId }
        val selected = state.selectedIndices.map { deckOrder[it] }
        cards.values.forEach { it.owner.enabled = true }
        when (state.phase) {
            RitualPhase.CONTEMPLATE -> cards.values.forEachIndexed { index, card ->
                place(card, orbit(index, orbitRadius!!, ritualSeconds * PI.toFloat() / 90f),
                    yaw * Quat(Vector3.RIGHT, -PI.toFloat() / 2f))
                card.face.enabled = true
            }
            RitualPhase.GATHER -> {
                val t = (elapsed / 1.5f).coerceIn(0f, 1f)
                cards.values.forEachIndexed { index, card ->
                    card.face.enabled = false
                    val angle = index * 2.399963f
                    val target = local(cos(angle) * .23f, -.35f + (index % 5) * .002f,
                        -.65f + sin(angle) * .23f)
                    animate(card, target, yaw * Quat(Vector3.RIGHT, PI.toFloat() / 2f), t)
                }
                if (t == 1f) vm.onEvent(RitualEvent.GatherFinished)
            }
            RitualPhase.SHUFFLE -> cards.values.forEach { it.face.enabled = false }
            RitualPhase.INTERLACE, RitualPhase.DRAW -> deckOrder.forEachIndexed { index, id ->
                val card = cards.getValue(id)
                card.face.enabled = false
                val selectedLift = if (id in selected) .035f else 0f
                val target = if (state.phase == RitualPhase.DRAW)
                    local(-.48f + index * .96f / 77f, -.30f + selectedLift, -.70f - index * .0009f)
                else local(0f, -.35f + index * .00065f, -.65f)
                place(card, target, yaw * Quat(Vector3.UP, PI.toFloat()))
            }
            RitualPhase.SPREAD -> {
                val t = (elapsed / 1.2f).coerceIn(0f, 1f)
                cards.values.forEach { card ->
                    card.face.enabled = false
                    card.owner.enabled = card.id in selected
                    val index = selected.indexOf(card.id)
                    if (index >= 0) animate(card, local((index - 1) * .26f, -.12f, -1.1f),
                        yaw * Quat(Vector3.UP, PI.toFloat()) *
                            Quat(Vector3(0f, 0f, 1f), if (state.selectedCards[index].reversed) PI.toFloat() else 0f), t)
                }
                if (t == 1f) vm.onEvent(RitualEvent.SpreadFinished)
            }
            RitualPhase.READING, RitualPhase.SUMMARY -> cards.values.forEach { card ->
                val index = selected.indexOf(card.id)
                card.owner.enabled = index >= 0
                card.face.enabled = index >= 0 && index < state.revealedCount
            }
            else -> cards.values.forEach { it.owner.enabled = false; it.face.enabled = false }
        }
        decorations.forEachIndexed { index, entity ->
            val position = when(index) {
                0 -> local(-.85f, -.28f + sin(ritualSeconds*.4f)*.015f, -1.30f)
                1 -> local(.85f, -.28f + sin(ritualSeconds*.4f+1f)*.015f, -1.30f)
                2 -> local(-.88f, .12f + sin(ritualSeconds*.4f+2f)*.025f, -1.50f)
                3 -> local(.88f, .12f + sin(ritualSeconds*.4f+3f)*.025f, -1.50f)
                else -> {
                    val angle = (index-4) * 2f * PI.toFloat()/24f + ritualSeconds * .015f
                    local(cos(angle)*1.60f, .02f + sin(ritualSeconds*.4f+index)*.10f, sin(angle)*1.60f)
                }
            }
            entity.enabled = environment.isSafe(position, if(index<4) .18f else .06f)
            entity.components.get<TransformComponent>()!!.position = position
        }
        pivots.forEach { pivot -> pivot.entity.components.get<TransformComponent>()!!.quaternion =
            pivot.base * Quat(pivot.axis, if(pivot.continuous) ritualSeconds*pivot.speed else sin(ritualSeconds*pivot.speed)*.08f) }
        if (cards.values.any { it.owner.enabled && !environment.isSafe(it.position, CARD_RADIUS) }) unsafe = true
        if (unsafe) {
            cards.values.forEach { it.owner.enabled = false }
            decorations.forEach { it.enabled = false }
            held = null
            vm.setRuntimeStatus(loaded, false, input.handsAvailable, controllerActive, input.gazeAvailable)
            updatePanels()
            return
        }
        if (input.handsReceivedAtMs != lastHandsTime && input.handsAvailable) {
            lastHandsTime = input.handsReceivedAtMs
            val left = input.hands?.left
            val right = input.hands?.right
            val events = classifier.update(skeleton(left), skeleton(right), input.handsReceivedAtMs)
            if (events.historyRequested) vm.toggleHistory()
            if (events.resetCompleted && state.phase == RitualPhase.SUMMARY) {
                unsafe = false
                vm.onEvent(RitualEvent.Reset)
            }
            if (events.interlaceCycleCompleted && state.phase == RitualPhase.INTERLACE)
                vm.onEvent(RitualEvent.InterlaceCompleted)
            if (state.phase == RitualPhase.SHUFFLE) {
                val movedLeft = sweep(left, previousLeft)
                val movedRight = sweep(right, previousRight)
                if ((movedLeft || movedRight) && input.sampledAtMs - lastShuffleSoundMs >= 450L) {
                    lastShuffleSoundMs = input.sampledAtMs
                    vm.playEffect("shuffle_soft")
                }
                if (swept > .2f && cards.values.maxOf { (it.position - local(0f, -.35f, -.65f)).length() } < .2f)
                    vm.onEvent(RitualEvent.ShuffleFinished)
            }
            if (state.phase == RitualPhase.DRAW) {
                if (events.leftPinchStarted) pick(input, left)?.let { vm.onEvent(RitualEvent.ToggleCard(deckOrder.indexOf(it.id))) }
                else if (events.rightPinchStarted) pick(input, right)?.let { vm.onEvent(RitualEvent.ToggleCard(deckOrder.indexOf(it.id))) }
            }
            if (state.phase == RitualPhase.READING && !state.narrationPlaying && state.revealedCount < 3) {
                val expected = cards.getValue(selected[state.nextRevealIndex])
                if (held == null) {
                    if (events.leftPinchStarted && pick(input, left, listOf(expected)) == expected) hold(expected, left, true)
                    else if (events.rightPinchStarted && pick(input, right, listOf(expected)) == expected) hold(expected, right, false)
                    else if (left?.get(Index.INDEX_TIP)?.position?.let { (it - expected.position).length() < .045f } == true)
                        hold(expected, left, true, touchOnly = true)
                    else if (right?.get(Index.INDEX_TIP)?.position?.let { (it - expected.position).length() < .045f } == true)
                        hold(expected, right, false, touchOnly = true)
                }
                if (input.hmdAvailable) updateHeld(left, right, events, state.nextRevealIndex, input.hmd!!.hmdPose.position)
            }
            previousLeft = left?.get(Index.PALM)?.position
            previousRight = right?.get(Index.PALM)?.position
        } else if (!input.handsAvailable) {
            classifier.update(null, null, input.sampledAtMs)
            held = null
            previousLeft = null
            previousRight = null
        }
        if (unsafe) {
            cards.values.forEach { it.owner.enabled = false }
            decorations.forEach { it.enabled = false }
            held = null
            vm.setRuntimeStatus(loaded, false, input.handsAvailable, controllerActive, input.gazeAvailable)
            updatePanels()
        }
        if (!unsafe) updateGazeFrame(input)
    }

    private fun hold(card: Card, hand: HandPose?, left: Boolean, touchOnly: Boolean = false) {
        val palm = hand?.get(Index.PALM) ?: return
        held = Held(card, left, palm.position, palm.rotation, card.position, card.rotation, touchOnly)
    }

    private fun updateHeld(left: HandPose?, right: HandPose?, events: HandGestureEvents, index: Int, viewerPosition: Vector3) {
        val hold = held ?: return
        val hand = if (hold.left) left else right
        val palm = hand?.get(Index.PALM)
        if (palm == null || (!hold.touchOnly && ((hold.left && events.leftPinchEnded) || (!hold.left && events.rightPinchEnded)))) {
            held = null
            return
        }
        if (hold.touchOnly && hand != null && (hand[Index.INDEX_TIP].position - hold.card.position).length() > .15f) { held = null; return }
        val position = hold.cardPosition + (palm.position - hold.palm)
        if (!space.isSafe(position, CARD_RADIUS)) { unsafe = true; held = null; return }
        val rotation = palm.rotation * hold.rotation.conjugate() * hold.cardRotation
        place(hold.card, position, rotation)
        val towardHead = viewerPosition - position
        if (towardHead.length() > .001f && Vector3.dot(rotation.rotateVector(Vector3(0f, 0f, 1f)), towardHead.normalize()) > .35f) {
            hold.card.face.enabled = true
            vm.onEvent(RitualEvent.RevealCard(index))
            held = null
        }
    }

    private fun sweep(hand: HandPose?, previous: Vector3?): Boolean {
        if (hand == null || previous == null) return false
        val palm = hand[Index.PALM].position
        val tip = hand[Index.INDEX_TIP].position
        val delta = palm - previous
        if (delta.length() > .25f || delta.length() < .0001f) return false // Ignore discontinuities and stationary samples.
        var moved = false
        cards.values.forEach { card ->
            if (min((card.position - palm).length(), (card.position - tip).length()) < .13f) {
                val target = card.position + delta
                if (space.isSafe(target, CARD_RADIUS)) {
                    place(card, target, card.rotation)
                    swept += delta.length()
                    moved = true
                }
                else unsafe = true
            }
        }
        return moved
    }

    private fun pick(input: InputSnapshot, hand: HandPose?, candidates: List<Card> = cards.values.toList()): Card? {
        if (hand == null) return null
        val tip = hand[Index.INDEX_TIP].position
        candidates.filter { it.owner.enabled }.minByOrNull { (it.position - tip).length() }
            ?.takeIf { (it.position - tip).length() < .065f }?.let { return it }
        if (input.gazeAvailable) return gazeHit(input, candidates)
        val segment = tip - hand[Index.INDEX_PROXIMAL].position
        if (segment.length() < .001f) return null
        return rayHit(tip, segment.normalize(), candidates)
    }

    private fun gazeHit(input: InputSnapshot, candidates: List<Card> = cards.values.toList()): Card? {
        if (!input.gazeAvailable) return null
        val eye = input.eyes!!.eyePose
        return rayHit(eye.position, eye.rotation.rotateVector(Vector3(0f, 0f, -1f)), candidates)
    }

    /** Exact oriented card-rectangle intersection shared by highlighting and hand selection. */
    private fun rayHit(origin: Vector3, direction: Vector3, candidates: List<Card>): Card? {
        return candidates.filter { it.owner.enabled }.mapNotNull { card ->
            val inverse = card.rotation.conjugate()
            val o = inverse.rotateVector(origin - card.position)
            val d = inverse.rotateVector(direction)
            if (abs(d.z) < .00001f) null else {
                val distance = -o.z / d.z
                val p = o + d * distance
                if (distance > 0f && abs(p.x) <= .05f && abs(p.y) <= .0858f) card to distance else null
            }
        }.minByOrNull { it.second }?.first
    }

    private fun updateGazeFrame(input: InputSnapshot) {
        val frame = gazeFrame ?: return
        frame.enabled = false
        if (vm.state.value.ritual.phase != RitualPhase.DRAW || vm.state.value.historyOpen || !input.gazeAvailable) return
        val card = gazeHit(input) ?: return
        frame.components.get<TransformComponent>()!!.apply { position = card.position; quaternion = card.rotation }
        // A DRAW card's front normal is local +Z; its visible back is local -Z.
        // The bars sit 1 mm beyond the back surface, keeping normal depth occlusion.
        frame.enabled = true
    }

    private fun skeleton(hand: HandPose?): HandSkeleton? {
        if (hand == null) return null
        fun point(index: Index) = hand[index].position.let { Vec3(it.x, it.y, it.z) }
        fun finger(mcp: Index, tip: Index) = FingerSegment(point(mcp), point(tip))
        return HandSkeleton(point(Index.PALM), point(Index.WRIST),
            finger(Index.INDEX_METACARPAL, Index.INDEX_TIP), finger(Index.MIDDLE_METACARPAL, Index.MIDDLE_TIP),
            finger(Index.RING_METACARPAL, Index.RING_TIP), finger(Index.LITTLE_METACARPAL, Index.LITTLE_TIP), point(Index.THUMB_TIP))
    }

    private fun local(x: Float, y: Float, z: Float) = initialHead!! + yaw.rotateVector(Vector3(x, y, z))
    private fun updatePanels() {
        val ui = vm.state.value
        val reading = ui.ritual.phase in setOf(RitualPhase.READING, RitualPhase.SUMMARY)
        panels.forEach { (id, panel) ->
            panel.enabled = if (ui.historyOpen) id == "history" else when (id) {
                "main" -> !reading
                "reading" -> reading
                "status" -> !ui.sceneReady || !ui.spaceReady || !ui.handTrackingReady ||
                    ui.controllerActive || ui.audioError != null || ui.saveError != null
                else -> false
            }
            val x = if (id == "reading") .50f else 0f
            val y = when (id) { "reading" -> .05f; "status" -> .45f; else -> .10f }
            panel.components.get<TransformComponent>()!!.apply {
                position = if (initialHead == null) Vector3(x, y, -1.2f) else local(x, y, -1.2f)
                quaternion = if (initialHead == null) Quat.identity() else yaw
            }
        }
    }
    private fun orbit(index: Int, radius: Float, offset: Float): Vector3 {
        val angle = index * (2f * PI.toFloat() / 78f) + offset
        return local(cos(angle) * radius, -.35f, sin(angle) * radius)
    }
    private fun place(card: Card, position: Vector3, rotation: Quat) {
        if (!space.isSafe(position, CARD_RADIUS)) { unsafe = true; card.owner.enabled = false; return }
        card.position = position
        card.rotation = rotation
        card.owner.components.get<TransformComponent>()!!.apply { this.position = position; quaternion = rotation }
    }
    private fun animate(card: Card, position: Vector3, rotation: Quat, t: Float) {
        val start = transitionPositions[card.id] ?: position
        val from = transitionRotations[card.id] ?: rotation
        place(card, start + (position - start) * t, Quat.slerp(from, rotation, t))
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        held = null
        // Attachment lifecycle belongs to the Compose host, not imported scene disposal.
        panels.values.forEach { it.removeFromParent() }
        root.destroy()
        resources.asReversed().forEach { resource -> runCatching { resource.close() }.onFailure { Log.w(TAG, "Resource close failed", it) } }
        resources.clear()
        cards.clear()
        decorations.clear()
        panels.clear()
    }

    companion object {
        private const val TAG = "FateScene"
        private const val CARD_RADIUS = .105f
    }
}
