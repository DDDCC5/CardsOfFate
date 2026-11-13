package com.example.cardsfate.content

import android.content.Context
import android.util.Log
import com.example.cardsfate.domain.model.*
import com.example.cardsfate.platform.spatial.PlaneSpace
import com.example.cardsfate.platform.spatial.PlaneSpaceSnapshot
import com.example.cardsfate.platform.tracking.*
import com.example.cardsfate.ui.ritual.FateViewModel
import com.pico.spatial.core.ecs.*
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
    // Captured at ritual entry; this Entity has no camera-tracking component.
    private val frontAnchor = Entity().also { root.addChild(it) }
    private var frontOrigin:Vector3?=null
    private var frontYaw=Quat.identity()
    private var frontConfirmed=false
    private data class Card(val id: String, val owner: Entity, val face: Entity,
                            val half: Vector3, val boundsCenter: Vector3,
                            var position: Vector3 = Vector3.ZERO, var rotation: Quat = Quat.identity())
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
    private var layoutLift = 0f
    private var layoutRevision = -1L
    private var placementAttemptMs = -1000L
    private var frameEnvironment: PlaneSpaceSnapshot? = null
    private var deckHalf = Vector3.ZERO
    private var deckBoundsCenter = Vector3.ZERO
    private var interactionHead:Vector3?=null
    private var interactionYaw=Quat.identity()
    private var stackCenter=Vector3.ZERO
    private var automaticHeight=.5f
    private var placementSession = -1L
    private data class LayoutChoice(val radius:Float,val lift:Float)
    private var phase: RitualPhase? = null
    private var phaseElapsed = 0f
    private var previousFrame = 0L
    private var ritualSeconds = 0f
    private data class RevealTurn(val index:Int,val cardId:String,val started:Float)
    private var revealTurn:RevealTurn?=null
    private val revealTap=StackTapGate()
    private var stackTouchDown=false
    private val readingTouches=mutableSetOf<String>()
    private val stackTap = StackTapGate()
    private var leftPinchDown = false
    private var rightPinchDown = false
    private data class DrawFlight(val start:Vector3,val rotation:Quat,val started:Float,val height:Float)
    private val drawFlights = mutableMapOf<String,DrawFlight>()
    private var unsafe = false
    private var rejectionLogged = false
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
                imported.components.get<TransformComponent>()!!.apply {
                    position = Vector3.ZERO
                    val sourceScale=scaleVector
                    // Imported card's thin local Y axis becomes owner Z after the preserved +90X.
                    scaleVector = Vector3(sourceScale.x*.85f,sourceScale.y*.425f,sourceScale.z*.85f)
                }
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
                cards[item.id] = Card(item.id, owner, face, size / 2f, bounds.center)
                deckHalf = Vector3(max(deckHalf.x,size.x/2f),max(deckHalf.y,size.y/2f),max(deckHalf.z,size.z/2f))
                deckBoundsCenter = bounds.center
            }
            require(cards.size == 78) { "Approved deck must contain 78 distinct cards" }
            deck.destroy()
            AtmosphereLayout.items.forEach { item ->
                val name=item.asset
                val entity = Entity.loadSuspend("asset://models/$name.glb")
                entity.components.get<TransformComponent>()!!.scaleVector=Vector3(item.scale)
                entity.enabled = false
                root.addChild(entity)
                decorations += entity
                val targets = when (name) {
                    "crystal" -> listOf("CrystalOrbitPivot")
                    "astrolabe" -> listOf("AstrolabeRingPivot_0", "AstrolabeRingPivot_1", "AstrolabeRingPivot_2", "CompassPivot")
                    else -> emptyList()
                }
                targets.forEachIndexed { index, target ->
                    val pivot = requireNotNull(entity.findEntity(target)) { "Missing approved pivot $target" }
                    pivots += Pivot(pivot, pivot.components.get<TransformComponent>()!!.quaternion,
                        if (index % 2 == 0) Vector3.UP else Vector3.RIGHT, .12f + index * .06f, true)
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
            val horizontal = MeshResource.createBox(Vector3(deckHalf.x*2f+.004f, .0018f, .001f))
            val vertical = MeshResource.createBox(Vector3(.0018f, deckHalf.y*2f+.004f, .001f))
            resources += gold
            resources += horizontal
            resources += vertical
            gazeFrame = Entity().apply {
                enabled = false
                root.addChild(this)
                listOf(Vector3(0f, -deckHalf.y-.002f, -.0015f), Vector3(0f, deckHalf.y+.002f, -.0015f),
                    Vector3(-deckHalf.x-.002f, 0f, -.0015f), Vector3(deckHalf.x+.002f, 0f, -.0015f)).forEachIndexed { index, offset ->
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
        if(id=="reading") root.addChild(entity) else frontAnchor.addChild(entity)
        updatePanels()
    }

    fun retrySpace() {
        if(vm.state.value.ritual.phase !in setOf(RitualPhase.LOGO,RitualPhase.SAFETY,RitualPhase.SYSTEMS)) vm.onEvent(RitualEvent.Reset)
        cards.values.forEach { it.owner.enabled=false }
        resetPressInputs()
        orbitRadius=null;interactionHead=null;layoutRevision=-1L;placementAttemptMs=-1000L;unsafe=false;rejectionLogged=false
    }

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
        }
        if(input.hmdAvailable && (frontOrigin==null || (!frontConfirmed && input.handsAvailable))) captureFront(input)
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
        frameEnvironment = environment
        if(environment.ready && cards.values.any { it.owner.enabled && !cardSafe(it,it.position,it.rotation,"environment-update") }) unsafe=true
        val ritual=vm.state.value.ritual
        val entering=ritual.phase==RitualPhase.CONTEMPLATE && ritual.sessionNumber!=placementSession
        if(entering) { placementSession=ritual.sessionNumber; orbitRadius=null;placementAttemptMs=-1000L;layoutRevision=-1L }
        val prelude = ritual.phase in setOf(RitualPhase.LOGO,RitualPhase.SAFETY,RitualPhase.SYSTEMS) || (ritual.phase==RitualPhase.CONTEMPLATE && phase!=RitualPhase.CONTEMPLATE)
        if (environment.ready && (orbitRadius == null || (prelude && layoutRevision != environment.revision)) &&
            input.sampledAtMs-placementAttemptMs >= 250L) {
            placementAttemptMs=input.sampledAtMs
            if (prelude && input.hmdAvailable && orbitRadius == null) {
                initialHead=input.hmd!!.hmdPose.position
                val forward=input.hmd!!.hmdPose.rotation.rotateVector(Vector3(0f,0f,-1f))
                if(forward.x*forward.x+forward.z*forward.z>.0001f) yaw=Quat(Vector3.UP,atan2(-forward.x,-forward.z))
            }
            val previous=orbitRadius
            val choice = if(previous!=null && orbitFits(environment,previous,layoutLift))
                LayoutChoice(previous,layoutLift)
            else {
                val origin=initialHead!!
                val support=environment.obstacles.filter { obstacle ->
                    val lo=obstacle.minimum;val hi=obstacle.maximum
                    val dx=maxOf(lo.x-origin.x,0f,origin.x-hi.x)
                    val dz=maxOf(lo.z-origin.z,0f,origin.z-hi.z)
                    hi.y-lo.y<.035f && hi.y<=origin.y+.05f && dx*dx+dz*dz<2.56f
                }.maxOfOrNull { it.maximum.y }
                val minimumLift=LayoutSupportHeight.minimumLift(origin.y.toDouble(),support?.toDouble(),SpacePoint(deckHalf.x.toDouble(),deckHalf.y.toDouble(),deckHalf.z.toDouble())).toFloat()
                listOf(0f,.04f,.08f,.12f,.18f,.24f,.32f,.40f).map { minimumLift+it }.firstNotNullOfOrNull { lift ->
                listOf(1.1f,1.2f,1.35f).firstNotNullOfOrNull { radius ->
                    if(orbitFits(environment,radius,lift)) LayoutChoice(radius,lift) else null
                }
            }
            }
            orbitRadius=choice?.radius
            layoutLift=choice?.lift ?: 0f
            layoutRevision=environment.revision
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
        val safe = orbitRadius != null && environment.ready && !unsafe && input.hmdAvailable
        val diagnostic = "phase=${vm.state.value.ritual.phase} scene=$loaded space=$safe planes=${environment.systemAnchorCount} ready=${environment.ready} radius=$orbitRadius lift=$layoutLift stack=$stackCenter hands=${input.handsAvailable}/${input.handsSupport} gaze=${input.gazeAvailable}/${input.eyesSupport} controllers=$controllerActive drawn=${vm.state.value.ritual.selectedIndices.size} revealed=${vm.state.value.ritual.revealedCount}"
        if (diagnostic != lastDiagnostic && input.sampledAtMs - diagnosticAt > 2000L) {
            Log.i(TAG, diagnostic)
            diagnosticAt = input.sampledAtMs
            lastDiagnostic = diagnostic
        }
        vm.setRuntimeStatus(loaded, safe, input.handsAvailable, controllerActive, input.gazeAvailable, environment.ready)
        updatePanels()
        tint?.components?.get<TransformComponent>()?.position = root.convertPositionFrom(input.hmd?.hmdPose?.position ?: head,null)
        tint?.enabled = safe
        if (!safe) {
            resetPressInputs()
            cards.values.forEach { it.owner.enabled = false }
            decorations.forEach { it.enabled = false }
            return
        }
        val ui = vm.state.value
        val state = ui.ritual
        if (phase != state.phase) {
            if(state.phase in setOf(RitualPhase.SYSTEMS,RitualPhase.CONTEMPLATE)) captureFront(input)
            val needsInteraction=state.phase==RitualPhase.GATHER || (interactionHead==null && state.phase in
                setOf(RitualPhase.DRAW,RitualPhase.SPREAD,RitualPhase.READING,RitualPhase.SUMMARY))
            val gatherPrepared=needsInteraction && phase!=null && state.phase==RitualPhase.GATHER
            if(needsInteraction && !chooseInteractionLayout(state,input,gatherPrepared)) {
                unsafe=true
                cards.values.forEach { it.owner.enabled=false }
                decorations.forEach { it.enabled=false }
                vm.setRuntimeStatus(loaded,false,input.handsAvailable,controllerActive,input.gazeAvailable,environment.ready)
                updatePanels();return
            }
            // Process-owned ritual survives a Stage/Activity recreation; Entity handles do not.
            if (phase == null) {
                if (state.phase in setOf(RitualPhase.READING, RitualPhase.SUMMARY)) {
                    state.selectedCards.forEachIndexed { index, drawn ->
                        place(cards.getValue(drawn.cardId), readingPosition(index),
                            if(index<state.revealedCount) interactionYaw else readingBackRotation())
                    }
                } else if (state.phase in setOf(RitualPhase.DRAW,RitualPhase.SPREAD)) {
                    state.deck.forEachIndexed { index,drawn ->
                        val card=cards.getValue(drawn.cardId)
                        val selectedIndex=state.selectedIndices.indexOf(index)
                        if(selectedIndex>=0) {
                            val position=readingPosition(selectedIndex)
                            val rotation=readingBackRotation()
                            place(card,position,rotation)
                            drawFlights[card.id]=DrawFlight(position,rotation,ritualSeconds-1f,.5f)
                        } else place(card,stackPosition(index-state.selectedIndices.size),stackRotation())
                    }
                } else if (state.phase == RitualPhase.GATHER) {
                    cards.values.forEachIndexed { index,card -> place(card,orbit(index,orbitRadius!!,0f),orbitRotation(index,0f)) }
                }
            }
            phase = state.phase
            phaseElapsed = 0f
            transitionPositions = cards.mapValues { it.value.position }
            transitionRotations = cards.mapValues { it.value.rotation }
            revealTurn=null
            if (phase == RitualPhase.GATHER) { drawFlights.clear(); resetPressInputs() }
            if (phase in setOf(RitualPhase.DRAW,RitualPhase.READING)) resetPressInputs()
            if (phase == RitualPhase.SYSTEMS) { classifier = HandRitualClassifier(); lastHandsTime = -1L; drawFlights.clear(); resetPressInputs() }
            if(phase == RitualPhase.GATHER && !gatherPrepared && !prepareAutomaticMotion(state)) unsafe=true
        }
        if(unsafe) {
            cards.values.forEach { it.owner.enabled=false };decorations.forEach { it.enabled=false }
            vm.setRuntimeStatus(loaded,false,input.handsAvailable,controllerActive,input.gazeAvailable, environment.ready)
            updatePanels();return
        }
        if (ui.historyOpen) {
            cards.values.forEach { it.owner.enabled = false }
            decorations.forEach { it.enabled = false }
            if (input.handsAvailable && input.handsReceivedAtMs != lastHandsTime) {
                lastHandsTime = input.handsReceivedAtMs
                val event = classifier.update(skeleton(input.hands?.left), skeleton(input.hands?.right), lastHandsTime)
                if (event.historyRequested && ExperiencePolicy.historyAvailable(state.phase)) vm.toggleHistory()
            }
            resetPressInputs()
            return
        }
        if (state.suspension != null) {
            updateGazeFrame(input)
            classifier.update(null, null, input.sampledAtMs)
            decorations.forEach { it.enabled=false }
            resetPressInputs()
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
                    orbitRotation(index,ritualSeconds * PI.toFloat() / 90f))
                card.face.enabled = true
                val view=frontYaw.conjugate().rotateVector(card.position-(frontOrigin ?: initialHead!!))
                card.owner.enabled=!ViewLayout.obstructsGuidance(point(view),card.half.length().toDouble())
            }
            RitualPhase.GATHER -> {
                val t = (elapsed / 2.4f).coerceIn(0f, 1f)
                deckOrder.forEachIndexed { index,id ->
                    val card=cards.getValue(id)
                    card.face.enabled=false
                    val target=stackPosition(index)
                    val start=transitionPositions[card.id] ?: target
                    val from=transitionRotations[card.id] ?: orbitRotation(index,0f)
                    place(card,flightPosition(start,target,t),Quat.slerp(from,stackRotation(),RitualFlightPath.flipProgress(t.toDouble()).toFloat()))
                }
                if (t == 1f) vm.onEvent(RitualEvent.GatherFinished)
            }
            RitualPhase.DRAW, RitualPhase.SPREAD -> {
                val settled=renderDraw(state)
                if(state.phase==RitualPhase.SPREAD && settled && !unsafe) vm.onEvent(RitualEvent.SpreadFinished)
            }
            RitualPhase.READING, RitualPhase.SUMMARY -> renderReading(state)
            else -> cards.values.forEach { it.owner.enabled = false; it.face.enabled = false }
        }
        decorations.forEachIndexed { index, entity ->
            val item=AtmosphereLayout.items[index]
            val offset=vector(item.position)+Vector3(0f,sin(ritualSeconds*.35f+index)*.025f,0f)
            val position=(frontOrigin ?: initialHead!!)+frontYaw.rotateVector(offset)
            entity.enabled=environment.isSafe(position,item.clearance)
            entity.components.get<TransformComponent>()!!.position=root.convertPositionFrom(position,null)
        }
        pivots.forEach { pivot -> pivot.entity.components.get<TransformComponent>()!!.quaternion =
            pivot.base * Quat(pivot.axis, if(pivot.continuous) ritualSeconds*pivot.speed else sin(ritualSeconds*pivot.speed)*.08f) }
        if (cards.values.any { it.owner.enabled && !cardSafe(it,it.position,it.rotation) }) unsafe = true
        if (unsafe) {
            cards.values.forEach { it.owner.enabled = false }
            decorations.forEach { it.enabled = false }
            vm.setRuntimeStatus(loaded, false, input.handsAvailable, controllerActive, input.gazeAvailable, environment.ready)
            updatePanels()
            return
        }
        if (input.handsReceivedAtMs != lastHandsTime && input.handsAvailable) {
            lastHandsTime = input.handsReceivedAtMs
            val left = input.hands?.left
            val right = input.hands?.right
            val events = classifier.update(skeleton(left), skeleton(right), input.handsReceivedAtMs)
            if (events.historyRequested && ExperiencePolicy.historyAvailable(state.phase)) vm.toggleHistory()
            if (events.resetCompleted && state.phase == RitualPhase.SUMMARY) {
                unsafe = false
                vm.onEvent(RitualEvent.Reset)
            }
            if(events.leftPinchStarted) leftPinchDown=true
            if(events.leftPinchEnded || left==null) leftPinchDown=false
            if(events.rightPinchStarted) rightPinchDown=true
            if(events.rightPinchEnded || right==null) rightPinchDown=false
            if (state.phase == RitualPhase.DRAW) {
                val top=state.deck.getOrNull(state.selectedIndices.size)?.let { cards.getValue(it.cardId) }
                val touching=top!=null && (touchesCard(left,top,stackTouchDown) || touchesCard(right,top,stackTouchDown))
                stackTouchDown=touching
                val targeted=touching || (top!=null &&
                    ((leftPinchDown && pick(input,left,listOf(top))!=null) ||
                     (rightPinchDown && pick(input,right,listOf(top))!=null)))
                if(stackTap.update(touching || leftPinchDown || rightPinchDown,targeted,input.handsReceivedAtMs)) {
                    val before=vm.state.value.ritual.selectedIndices.size
                    vm.onEvent(RitualEvent.DrawFromStack)
                    Log.i(TAG,"DRAW_ACCEPT sample=${input.handsReceivedAtMs} count=$before->${vm.state.value.ritual.selectedIndices.size}")
                }
            }
            val current=vm.state.value.ritual
            if (current.phase == RitualPhase.READING) {
                val expected=current.selectedCards.getOrNull(current.nextRevealIndex)?.let { cards.getValue(it.cardId) }
                current.selectedCards.forEach { drawn ->
                    val card=cards.getValue(drawn.cardId)
                    if(touchesCard(left,card,card.id in readingTouches) || touchesCard(right,card,card.id in readingTouches))
                        readingTouches.add(card.id) else readingTouches.remove(card.id)
                }
                val eligible=expected!=null && !current.narrationPlaying && current.revealedCount<3 && revealTurn==null
                val gazeTarget=expected!=null && input.gazeAvailable && gazeHit(input,listOf(expected))==expected
                val handTarget=expected!=null && !input.gazeAvailable &&
                    ((leftPinchDown && pick(input,left,listOf(expected))!=null) || (rightPinchDown && pick(input,right,listOf(expected))!=null))
                val targeted=eligible && (expected!!.id in readingTouches ||
                    ((leftPinchDown || rightPinchDown) && (gazeTarget || handTarget)))
                val pressed=readingTouches.isNotEmpty() || leftPinchDown || rightPinchDown
                if(revealTap.update(pressed,targeted,input.handsReceivedAtMs) && expected!=null) startReveal(current.nextRevealIndex,expected)
            }

        } else if (!input.handsAvailable) {
            classifier.update(null, null, input.sampledAtMs)
            resetPressInputs()
        }
        if (unsafe) {
            cards.values.forEach { it.owner.enabled = false }
            decorations.forEach { it.enabled = false }
            vm.setRuntimeStatus(loaded, false, input.handsAvailable, controllerActive, input.gazeAvailable, environment.ready)
            updatePanels()
        }
        if (!unsafe) updateGazeFrame(input)
    }

    private fun resetPressInputs() {
        stackTap.reset()
        revealTap.reset()
        stackTouchDown=false
        readingTouches.clear()
        leftPinchDown=false
        rightPinchDown=false
    }

    /** Enter near the surface; remain pressed until the finger clearly leaves it. */
    private fun touchesCard(hand:HandPose?,card:Card,wasTouching:Boolean):Boolean {
        val tip=hand?.get(Index.INDEX_TIP)?.position ?: return false
        val p=card.rotation.conjugate().rotateVector(tip-card.position)-card.boundsCenter
        val depth=if(wasTouching) .045f else .018f
        return abs(p.x)<card.half.x+.015f && abs(p.y)<card.half.y+.015f && abs(p.z)<depth
    }

    private fun startReveal(index:Int,card:Card) {
        val env=frameEnvironment ?: space.snapshot()
        val clear=(0..40).all { step ->
            env.isCardSafe(card.position,interactionYaw*Quat(Vector3.UP,CardFlipMotion.yaw(step/40.0).toFloat()),card.half,card.boundsCenter)
        }
        if(!clear) { Log.w(TAG,"REJECT source=reveal-turn card=${card.id}");unsafe=true;return }
        revealTurn=RevealTurn(index,card.id,ritualSeconds)
        vm.playEffect("flip")
        Log.i(TAG,"REVEAL_ACCEPT index=$index card=${card.id}")
    }

    private fun renderReading(state:RitualState) {
        val selected=state.selectedCards.map { it.cardId }
        cards.values.forEach { card ->
            val index=selected.indexOf(card.id)
            card.owner.enabled=index>=0
            if(index<0) { card.face.enabled=false;return@forEach }
            val turn=revealTurn?.takeIf { it.cardId==card.id }
            if(turn!=null) {
                val t=((ritualSeconds-turn.started)/CardFlipMotion.DURATION_SECONDS).coerceIn(0f,1f)
                place(card,readingPosition(index),interactionYaw*Quat(Vector3.UP,CardFlipMotion.yaw(t.toDouble()).toFloat()))
                card.face.enabled=true
                if(t==1f && !unsafe) { revealTurn=null;vm.onEvent(RitualEvent.RevealCard(turn.index)) }
            } else {
                val revealed=index<state.revealedCount
                place(card,readingPosition(index),if(revealed) interactionYaw else readingBackRotation())
                card.face.enabled=revealed
            }
        }
    }

    private fun stackRotation()=interactionYaw*Quat(Vector3.RIGHT,PI.toFloat()/2f)
    private fun stackSpacing()=maxOf(.0004f,deckHalf.z*2f+.00005f)
    private fun stackPosition(index:Int)=stackCenter+Vector3(0f,-index*stackSpacing(),0f)
    private fun readingBackRotation()=interactionYaw*Quat(Vector3.UP,PI.toFloat())
    private fun readingPosition(index:Int)=stackCenter+interactionYaw.rotateVector(vector(ViewLayout.readingOffset(index)))

    /** Each accepted tap has its own animation, so rapid distinct taps never restart prior cards. */
    private fun renderDraw(state:RitualState):Boolean {
        var settled=true
        val selected=state.selectedCards.map { it.cardId }
        state.deck.forEachIndexed { index,drawn ->
            val card=cards.getValue(drawn.cardId)
            card.face.enabled=false
            val slot=selected.indexOf(card.id)
            card.owner.enabled=state.phase==RitualPhase.DRAW || slot>=0
            if(slot<0) {
                if(card.owner.enabled) place(card,stackPosition(index-selected.size),stackRotation())
            } else {
                val target=readingPosition(slot)
                val rotation=readingBackRotation()
                var flight=drawFlights[card.id]
                if(flight==null) {
                    val height=clearDrawHeight(card,card.position,card.rotation,target,rotation)
                    if(height==null) { Log.w(TAG,"REJECT source=draw-flight card=${card.id} from=${card.position} target=$target");unsafe=true;return false }
                    flight=DrawFlight(card.position,card.rotation,ritualSeconds,height)
                    drawFlights[card.id]=flight
                }
                val t=((ritualSeconds-flight.started)/.9f).coerceIn(0f,1f)
                place(card,drawFlightPosition(flight.start,target,t,flight.height),
                    Quat.slerp(flight.rotation,rotation,DrawFlightPath.rotationProgress(t.toDouble()).toFloat()))
                settled=settled && t==1f
            }
        }
        return settled && selected.size==3
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
                if (distance > 0f && abs(p.x-card.boundsCenter.x) <= card.half.x && abs(p.y-card.boundsCenter.y) <= card.half.y) card to distance else null
            }
        }.minByOrNull { it.second }?.first
    }

    private fun updateGazeFrame(input: InputSnapshot) {
        val frame=gazeFrame ?: return
        frame.enabled=false
        val ui=vm.state.value
        val state=ui.ritual
        if(ui.historyOpen || state.suspension!=null || !input.gazeAvailable) return
        val target=when(state.phase) {
            RitualPhase.DRAW -> state.deck.getOrNull(state.selectedIndices.size)?.let { cards[it.cardId] }
            RitualPhase.READING -> if(!state.narrationPlaying && state.revealedCount<3 && revealTurn==null)
                state.selectedCards.getOrNull(state.nextRevealIndex)?.let { cards[it.cardId] } else null
            else -> null
        } ?: return
        val card=gazeHit(input,listOf(target)) ?: return
        frame.components.get<TransformComponent>()!!.apply {
            position=root.convertPositionFrom(card.position,null)
            quaternion=root.convertRotationFrom(card.rotation,null)
        }
        frame.enabled=true
    }

    private fun skeleton(hand: HandPose?): HandSkeleton? {
        if (hand == null) return null
        fun point(index: Index) = hand[index].position.let { Vec3(it.x, it.y, it.z) }
        fun finger(mcp: Index, tip: Index) = FingerSegment(point(mcp), point(tip))
        return HandSkeleton(point(Index.PALM), point(Index.WRIST),
            finger(Index.INDEX_METACARPAL, Index.INDEX_TIP), finger(Index.MIDDLE_METACARPAL, Index.MIDDLE_TIP),
            finger(Index.RING_METACARPAL, Index.RING_TIP), finger(Index.LITTLE_METACARPAL, Index.LITTLE_TIP), point(Index.THUMB_TIP))
    }

    private fun cardSafe(card: Card, position: Vector3, rotation: Quat, source: String = "pose"): Boolean {
        val env=frameEnvironment ?: space.snapshot()
        val clear=env.isCardSafe(position,rotation,card.half,card.boundsCenter)
        if(!clear && !unsafe && !rejectionLogged) {
            rejectionLogged=true
            val hits=env.obstacles.filter { obstacle ->
                !env.copy(obstacles=listOf(obstacle)).isCardSafe(position,rotation,card.half,card.boundsCenter)
            }
            Log.w(TAG,"REJECT source=$source phase=$phase card=${card.id} from=${card.position} target=$position rotation=$rotation half=${card.half} ready=${env.ready} error=${env.error} head=$initialHead hits=${hits.map { "${it.anchorId}:${it.minimum}..${it.maximum}" }}")
        }
        return clear
    }

    private fun point(p:Vector3)=SpacePoint(p.x.toDouble(),p.y.toDouble(),p.z.toDouble())
    private fun vector(p:SpacePoint)=Vector3(p.x.toFloat(),p.y.toFloat(),p.z.toFloat())

    /** Orbit height solves only the orbit; it must never lift or side-shift the reachable workspace. */
    private fun orbitFits(env:PlaneSpaceSnapshot,radius:Float,lift:Float):Boolean = (0 until 78).all { index ->
        val angle=index*2f*PI.toFloat()/78f
        val p=local(cos(angle)*radius,-.20f+lift,sin(angle)*radius)
        env.isSafe(p,deckHalf.length())
    }

    /** Rebase at the actual end of contemplation, using centered and down-only view candidates. */
    private fun chooseInteractionLayout(state:RitualState,input:InputSnapshot,checkGather:Boolean):Boolean {
        if(!input.hmdAvailable) return false
        val pose=input.hmd!!.hmdPose
        interactionHead=frontOrigin ?: pose.position
        interactionYaw=frontYaw
        val env=frameEnvironment ?: space.snapshot()
        for(offset in ViewLayout.stackCandidates()) {
            stackCenter=pose.position+pose.rotation.rotateVector(vector(offset))
            val clearStack=(0 until 78).all { env.isCardSafe(stackPosition(it),stackRotation(),deckHalf,deckBoundsCenter) }
            if(!clearStack) continue
            val clearRead=(0..2).all { index ->
                val position=readingPosition(index)
                (0..20).all { step ->
                    env.isCardSafe(position,interactionYaw*Quat(Vector3.UP,CardFlipMotion.yaw(step/20.0).toFloat()),deckHalf,deckBoundsCenter)
                } && listOf(.04f,.08f,.16f,.30f,.50f).any { height -> (0..40).all { step ->
                    val t=step/40f
                    env.isCardSafe(drawFlightPosition(stackCenter,position,t,height),
                        Quat.slerp(stackRotation(),readingBackRotation(),DrawFlightPath.rotationProgress(t.toDouble()).toFloat()),deckHalf,deckBoundsCenter)
                } }
            }
            if(clearRead && (!checkGather || prepareAutomaticMotion(state))) {
                Log.i(TAG,"WORKSPACE_ACCEPT viewOffset=$offset center=$stackCenter")
                return true
            }
        }
        Log.w(TAG,"REJECT source=centered-down-workspace; no safe current-view candidate")
        return false
    }

    private fun clearDrawHeight(card:Card,start:Vector3,from:Quat,end:Vector3,to:Quat):Float? {
        val env=frameEnvironment ?: space.snapshot()
        return listOf(.04f,.08f,.16f,.30f,.50f).firstOrNull { height -> (0..60).all { step ->
            val t=step/60f
            env.isCardSafe(drawFlightPosition(start,end,t,height),Quat.slerp(from,to,DrawFlightPath.rotationProgress(t.toDouble()).toFloat()),card.half,card.boundsCenter)
        } }
    }

    private fun prepareAutomaticMotion(state:RitualState):Boolean {
        val env=frameEnvironment ?: space.snapshot()
        val height=listOf(.5f,.75f,1f).firstOrNull { height -> state.deck.withIndex().all { (index,drawn) ->
            val card=cards.getValue(drawn.cardId)
            (0..60).all { sample ->
                val t=sample/60f
                env.isCardSafe(flightPosition(card.position,stackPosition(index),t,height),
                    Quat.slerp(card.rotation,stackRotation(),RitualFlightPath.flipProgress(t.toDouble()).toFloat()),card.half,card.boundsCenter)
            }
        } } ?: return false
        automaticHeight=height
        return true
    }
    private fun drawFlightPosition(start:Vector3,end:Vector3,t:Float,height:Float):Vector3 {
        val p=DrawFlightPath.position(point(start),point(end),t.toDouble(),height.toDouble())
        return vector(p)
    }
    private fun flightPosition(start:Vector3,end:Vector3,t:Float,height:Float=automaticHeight):Vector3 {
        fun point(p:Vector3)=SpacePoint(p.x.toDouble(),p.y.toDouble(),p.z.toDouble())
        val p=RitualFlightPath.position(point(start),point(end),t.toDouble(),height.toDouble())
        return Vector3(p.x.toFloat(),p.y.toFloat(),p.z.toFloat())
    }
    private fun local(x: Float, y: Float, z: Float) = initialHead!! + yaw.rotateVector(Vector3(x, y, z))
    private fun captureFront(input:InputSnapshot) {
        if(!input.hmdAvailable) return
        val pose=input.hmd!!.hmdPose
        val forward=pose.rotation.rotateVector(Vector3(0f,0f,-1f))
        frontOrigin=pose.position
        if(forward.x*forward.x+forward.z*forward.z>.0001f) frontYaw=Quat(Vector3.UP,atan2(-forward.x,-forward.z))
        frontConfirmed=frontConfirmed || input.handsAvailable
        frontAnchor.components.get<TransformComponent>()!!.apply {
            position=root.convertPositionFrom(pose.position,null)
            quaternion=root.convertRotationFrom(frontYaw,null)
        }
    }

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
            val offset=when(id) {
                "reading" -> ViewLayout.readingPanel
                "status" -> SpacePoint(0.0,.32,-.70)
                "main" -> if(ui.ritual.phase in setOf(RitualPhase.DRAW,RitualPhase.SPREAD)) ViewLayout.drawGuidance else ViewLayout.mainPanel
                else -> ViewLayout.mainPanel
            }
            panel.components.get<TransformComponent>()!!.apply {
                if(id=="reading") {
                    val origin=if(interactionHead!=null) stackCenter else frontOrigin
                    val rotation=if(interactionHead!=null) interactionYaw else frontYaw
                    position=if(origin==null) vector(offset) else root.convertPositionFrom(origin+rotation.rotateVector(vector(offset)),null)
                    quaternion=root.convertRotationFrom(rotation,null)
                } else {
                    position=vector(offset)
                    quaternion=Quat.identity()
                }
            }
        }
    }
    private fun orbit(index: Int, radius: Float, offset: Float): Vector3 {
        val angle = index * (2f * PI.toFloat() / 78f) + offset
        return local(cos(angle) * radius, -.20f+layoutLift, sin(angle) * radius)
    }
    private fun orbitRotation(index: Int, offset: Float): Quat {
        val angle=index*(2f*PI.toFloat()/78f)+offset
        return yaw*Quat(Vector3.UP,-angle-PI.toFloat()/2f)
    }
    private fun place(card: Card, position: Vector3, rotation: Quat) {
        if (!cardSafe(card,position,rotation)) { unsafe = true; card.owner.enabled = false; return }
        card.position = position
        card.rotation = rotation
        card.owner.components.get<TransformComponent>()!!.apply { this.position = root.convertPositionFrom(position,null); quaternion = root.convertRotationFrom(rotation,null) }
    }
    private fun animate(card: Card, position: Vector3, rotation: Quat, t: Float) {
        val start = transitionPositions[card.id] ?: position
        val from = transitionRotations[card.id] ?: rotation
        place(card, flightPosition(start,position,t), Quat.slerp(from, rotation, RitualFlightPath.flipProgress(t.toDouble()).toFloat()))
    }

    fun dispose() {
        if (disposed) return
        disposed = true
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
    }
}
