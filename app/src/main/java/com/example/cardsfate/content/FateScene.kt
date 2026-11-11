package com.example.cardsfate.content

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.cardsfate.domain.model.ReadingLanguage
import com.example.cardsfate.platform.spatial.PlaneSpace
import com.example.cardsfate.platform.tracking.InputTracking
import com.example.cardsfate.ui.ritual.FateScreen
import com.example.cardsfate.ui.ritual.FateButton
import com.example.cardsfate.ui.theme.FatePalette
import com.example.cardsfate.ui.theme.FateFont
import com.example.cardsfate.ui.ritual.FateViewModel
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.content.SpatialView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@Composable
fun FateScene(vm: FateViewModel) {
    var attempt by remember { mutableIntStateOf(0) }
    val ui by vm.state.collectAsStateWithLifecycle()
    key(attempt, ui.language) { FateSceneInstance(vm) { attempt++ } }
}

/** Compose owns setup/lifecycle; the controller owns per-frame scene updates. */
@Composable
private fun FateSceneInstance(vm: FateViewModel, onReload: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val tracking = remember { InputTracking() }
    val planes = remember { PlaneSpace() }
    val controller = remember { FateSceneController(context.applicationContext, vm, tracking, planes, scope) }
    var loaded by remember { mutableStateOf(false) }
    var resumed by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var diagnostic by remember { mutableStateOf<Entity?>(null) }
    val zh = vm.state.value.language == ReadingLanguage.ZH
    fun fail(error: Throwable) {
        android.util.Log.e("FateScene", "Scene initialization failed", error)
        failure = if(zh) "场景暂时无法载入，请重试。" else "The scene could not load. Please retry."
        controller.root.enabled = false
        diagnostic?.enabled = true
        vm.setRuntimeStatus(false, false, false, false, false)
        vm.pauseAudio()
    }
    DisposableEffect(lifecycle, controller) {
        fun resume() {
            if (resumed) return
            try { tracking.start(); planes.start(scope); resumed = true }
            catch (error: Exception) { fail(error) }
        }
        fun pause() {
            resumed = false
            vm.pauseAudio()
            vm.setRuntimeStatus(loaded, false, false, false, false)
            runCatching { tracking.stop() }.onFailure { failure = it.message }
            planes.stop()
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resume()
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> pause()
                else -> Unit
            }
        }
        vm.pauseAudio()
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resume()
        onDispose {
            lifecycle.removeObserver(observer)
            pause()
            diagnostic?.removeFromParent()
            controller.dispose()
        }
    }
    LaunchedEffect(controller, loaded, resumed, failure) {
        if (!resumed || failure != null) return@LaunchedEffect
        while (isActive) {
            withFrameNanos { time ->
                try {
                    controller.frame(time)
                    diagnostic?.enabled = !controller.root.enabled
                    if (vm.state.value.ritual.suspension == null) vm.resumeAudio() else vm.pauseAudio()
                } catch (error: Exception) { fail(error) }
            }
            if (failure != null) break
        }
    }
    SpatialView(
        modifier = Modifier.fillMaxSize(),
        initial = { content, attachments ->
            val errorEntity = requireNotNull(attachments.entity("load-error"))
            diagnostic = errorEntity
            errorEntity.components[TransformComponent::class.java]?.setPosition(Vector3(0f, 0f, -1.2f))
            content.addEntity(errorEntity)
            try {
                listOf("main", "history", "reading", "status").forEach { id ->
                    controller.setPanelEntity(id, requireNotNull(attachments.entity(id)) { "Missing $id attachment" })
                }
                content.addEntity(controller.root)
                errorEntity.enabled = false
                // Return the first spatial frame immediately. Asset IO must not gate the logo.
                scope.launch {
                    try {
                        if (vm.state.value.ritual.phase == com.example.cardsfate.domain.model.RitualPhase.LOGO) delay(6300)
                        controller.load()
                        loaded = true
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { fail(error) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { fail(error) }
        },
        attachments = {
            listOf("main", "history", "reading", "status").forEach { id ->
                AttachmentPanel(id = id) {
                    PicoTheme {
                        FateScreen(id, onRetrySpace = {
                            planes.stop()
                            if (resumed) planes.start(scope)
                            controller.retrySpace()
                        })
                    }
                }
            }
            AttachmentPanel(id = "load-error") {
                PicoTheme {
                    SceneDiagnostic(
                        title = if (failure != null) { if (zh) "场景加载失败" else "Scene unavailable" }
                            else if (loaded) { if (zh) "等待头显追踪…" else "Waiting for headset tracking…" }
                            else if (zh) "正在载入仪式…" else "Loading the ritual…",
                        message = failure,
                        retryLabel = if (zh) "重试" else "Retry",
                        onRetry = if (failure == null) null else onReload,
                    )
                }
            }
        },
    )
}

@Composable
internal fun SceneDiagnostic(title: String, message: String?, retryLabel: String, onRetry: (() -> Unit)?) {
    Column(Modifier.width(620.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style=PicoTheme.typography.headlineSmall.copy(fontFamily=FateFont), color=FatePalette.ritualGold)
        message?.let { Text(it, style=PicoTheme.typography.bodyLarge.copy(fontFamily=FateFont), color=FatePalette.ritualIvory) }
        if (onRetry != null) FateButton(retryLabel, true, onRetry)
    }
}
